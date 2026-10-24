package jo.lspkotlin.api

import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import java.io.Closeable

/**
 * SPI mince du moteur Kotlin — LE seul contrat par lequel le reste de
 * CodeIDE peut voir `:lsp:kotlin` (LSP_KOTLIN §6, décision D6 : le module
 * doit rester découplable en « pack langue » téléchargeable ; le
 * découpage réel est DIFFÉRÉ à post-stabilisation sur décision du
 * propriétaire, mais l'architecture ne doit jamais le rendre coûteux).
 *
 * Règles du contrat (checkModuleDependencies + revue) :
 * - aucune classe du module n'est référencée nommément hors du module ;
 * - l'implémentation est résolue par ServiceLoader (descripteur
 *   `META-INF/services/jo.lspkotlin.api.MoteurLangageKotlin`), pas par
 *   type concret : [ServiceMoteurLangageKotlin.unique] ;
 * - le descripteur de pack `jo/lspkotlin/pack/version.properties`
 *   (identifiant, version, version minimale d'app) accompagne l'artefact.
 *
 * Périmètre K1 : analyse syntaxique seule. K2 (livré) y ajoute le contrat
 * de session (cycle de vie document/diagnostics, [SessionLangageKotlin] —
 * même forme que `:lsp:java`, cf. LSP_JAVA.md), en le gardant TOUT ENTIER
 * derrière ce point d'entrée.
 */
public interface MoteurLangageKotlin {
    /** Identifiant stable du moteur/pack (`jo.codeide.lsp.kotlin`). */
    public val identifiant: String

    /** Version du moteur (version CodeIDE qui l'a livré). */
    public val version: String

    /** Version minimale de l'application hôte acceptée par ce moteur. */
    public val versionMinimaleApp: String

    /** Tag amont de la grammaire vendorée (ex. `v2.3.0`) — traçabilité. */
    public val versionGrammaire: String

    /**
     * Analyse une source Kotlin complète (fichier ou script). Tolérant :
     * ne lève jamais sur du texte invalide (cf. ParseurKotlin).
     *
     * @param texte source à analyser.
     * @param script vrai pour un `.kts`.
     * @return l'arbre syntaxique reconstruit.
     */
    public fun analyserSource(texte: CharSequence, script: Boolean = false): ArbreSyntaxiqueKotlin

    /**
     * Crée une session de langue NEUVE et autonome (K2) : une par
     * connexion LSP côté hôte (cycle initialize/shutdown complet),
     * documents + diagnostics syntaxiques confinés dedans. La session
     * est responsable de sa fermeture ([SessionLangageKotlin.close]).
     *
     * @return la session prête à recevoir des documents.
     */
    public fun creerSession(): SessionLangageKotlin

    /**
     * Nourrit l'état PARTAGÉ du moteur (ADR 0211 — demande D1 de
     * CodeIDE) : UNE fois par sync, sur un fil de fond du moteur —
     * l'index des appelables (segments par empreinte) et le scan des
     * racines sources. Les sessions créées ensuite se BRANCHENT à bon
     * marché ([SessionLangageKotlin.definirClasspath] devient un
     * no-op chaud quand le snapshot couvre les empreintes) : le
     * supplier de connexion ne paie plus AUCUNE E/S lourde.
     *
     * Tolérance de version : défaut `false` — un pack K3 antérieur ne
     * possède pas le nourrissage moteur (la façade retombe sur la
     * poussée synchrone par session, le monde d'avant, jamais une
     * exception).
     *
     * @param classpath le classpath poussé par la sync (même objet que
     *        celui des sessions).
     * @return `true` si le moteur a PRIS LA DEMANDE en charge (passe
     *         asynchrone en route — progression observable par
     *         [enregistrerObservateurIndexation]) ; `false` pour un
     *         pack ancien.
     */
    public fun definirClasspath(classpath: ClasspathKotlin): Boolean = false

    /**
     * Enregistre un observateur de l'indexation moteur (ADR 0213 —
     * demande D3 : écran multi-moteurs de CodeIDE). La poignée
     * retournée DÉSINSCRIT l'observateur (une façade fermée ne reçoit
     * plus rien).
     *
     * Tolérance de version : défaut = poignée vide, AUCUN événement —
     * un pack ancien n'est pas observable (la façade synthétise ses
     * fenêtres).
     *
     * @param observateur le destinataire des événements (fil de fond
     *        du moteur — rapide, jamais d'exception).
     * @return la poignée de désinscription.
     */
    public fun enregistrerObservateurIndexation(observateur: ObservateurIndexationKotlin): Closeable =
        PoigneeObservationVideKotlin

    /**
     * Annule le passage d'indexation EN VOL (ADR 0214 — demande D5) :
     * drapeau coopératif testé ENTRE les entrées, le passage publie ce
     * qui est ACQUIS et se marque PARTIEL « annulé par l'utilisateur ».
     *
     * Tolérance de version : défaut `false` (pack ancien — l'écran
     * retire l'offre).
     *
     * @return `true` si un passage était en vol et a reçu la demande ;
     *         `false` sinon (rien à annuler, ou pack ancien).
     */
    public fun annulerPassage(): Boolean = false

    /**
     * Retente les ÉCHECS du dernier passage SEULEMENT (ADR 0214 —
     * demande D4) : les entrées saines sont reprises du cache sans
     * re-lecture (REPRIS massifs — la reprise est visible et bon
     * marché), seules les écarts et les non-traités (passage annulé)
     * sont re-payés.
     *
     * Tolérance de version : défaut `false` (pack ancien).
     *
     * @return `true` s'il y avait des échecs/pendances à retenter et
     *         qu'une passe a été relancée ; `false` sinon.
     */
    public fun reesayerEchecs(): Boolean = false
}

/**
 * Résolution ServiceLoader du moteur Kotlin — l'unique façon NON interne
 * d'obtenir une instance. Échoue explicitement (erreur lisible, pas de
 * comportement fantôme) si aucun moteur n'est enregistré.
 */
public object ServiceMoteurLangageKotlin {
    /**
     * Charge le moteur unique du classpath. Un moteur absent du graphe de
     * dépendances est une erreur de BUILD (le module n'est pas branché),
     * pas une erreur d'exécution à gérer silencieusement.
     */
    public fun unique(): MoteurLangageKotlin {
        val moteurs =
            java.util.ServiceLoader
                .load(MoteurLangageKotlin::class.java)
                .toList()
        check(moteurs.isNotEmpty()) {
            "aucun moteur Kotlin enregistré : jo.lspkotlin.api.MoteurLangageKotlin absent du classpath"
        }
        check(moteurs.size == 1) {
            "plusieurs moteurs Kotlin enregistrés : " +
                moteurs.joinToString { it.identifiant }
        }
        return moteurs.single()
    }
}
