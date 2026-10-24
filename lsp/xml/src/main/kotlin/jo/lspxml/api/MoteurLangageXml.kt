package jo.lspxml.api

import jo.lspxml.parse.DocumentXml
import java.io.Closeable

/**
 * SPI mince du moteur XML — LE seul contrat par lequel le reste de
 * CodeIDE peut voir `:lsp:xml` (LSP_XML.md §3 décision X2 : le module
 * doit rester découplable en « pack langue » téléchargeable ; le
 * découpage réel suit la même décision propriétaire que Kotlin —
 * différé à post-stabilisation, l'architecture ne doit jamais le
 * rendre coûteux).
 *
 * Règles du contrat (checkModuleDependencies + revue) :
 * - aucune classe du module n'est référencée nommément hors du module ;
 * - l'implémentation est résolue par ServiceLoader (descripteur
 *   `META-INF/services/jo.lspxml.api.MoteurLangageXml`), pas par type
 *   concret : [ServiceMoteurLangageXml.unique] ;
 * - le descripteur de pack `jo/lspxml/pack/version.properties`
 *   (identifiant, version, version minimale d'app) accompagne l'artefact.
 */
public interface MoteurLangageXml {
    /** Identifiant stable du moteur/pack (`jo.codeide.lsp.xml`). */
    public val identifiant: String

    /** Version du moteur (version CodeIDE qui l'a livré). */
    public val version: String

    /** Version minimale de l'application hôte acceptée par ce moteur. */
    public val versionMinimaleApp: String

    /**
     * Analyse une source XML complète — TOLÉRANTE (X1 : ne lève JAMAIS
     * sur un buffer à moitié tapé ; les erreurs voyagent comme
     * diagnostics de reprise de l'arbre).
     *
     * @param texte source à analyser.
     * @return l'arbre DOM neutre (racine `null` pour un fichier sans
     *         aucune balise).
     */
    public fun analyserSource(texte: CharSequence): DocumentXml

    /**
     * Crée une session de langue NEUVE et autonome : une par connexion
     * LSP côté hôte (cycle initialize/shutdown complet), documents +
     * diagnostics confinés dedans. La session est responsable de sa
     * fermeture ([SessionLangageXml.close]).
     */
    public fun creerSession(): SessionLangageXml

    /**
     * Nourrit l'état PARTAGÉ du moteur (ADR 0211 — demande D1 de
     * CodeIDE, annexe D1-d) : UNE fois par sync, sur un fil de fond du
     * moteur — l'index des ressources (racines `res/` + AAR des
     * dépendances, scans incrémentaux mtime) et le catalogue XE2 de la
     * plateforme (pré-chauffé : son coût quitte la première frappe de
     * diagnostic). Les sessions créées ensuite se BRANCHENT sans
     * re-balayage ([SessionLangageXml.definirClasspath] devient un
     * no-op quand le moteur possède le nourrissage).
     *
     * Tolérance de version : défaut `false` — un pack XE antérieur ne
     * possède pas le nourrissage moteur (la façade retombe sur la
     * poussée synchrone par session, le monde d'avant, jamais une
     * exception).
     *
     * @param classpath le classpath poussé par la sync.
     * @return `true` si le moteur a PRIS LA DEMANDE en charge (passe
     *         asynchrone en route) ; `false` pour un pack ancien.
     */
    public fun definirClasspath(classpath: ClasspathXml): Boolean = false

    /**
     * Enregistre un observateur de l'indexation moteur (ADR 0213 —
     * demande D3 : écran multi-moteurs de CodeIDE). La poignée
     * retournée DÉSINSCRIT l'observateur.
     *
     * Tolérance de version : défaut = poignée vide, AUCUN événement.
     *
     * @param observateur le destinataire des événements (fil de fond
     *        du moteur — rapide, jamais d'exception).
     * @return la poignée de désinscription.
     */
    public fun enregistrerObservateurIndexation(observateur: ObservateurIndexationXml): Closeable =
        PoigneeObservationVideXml

    /**
     * Annule le passage d'indexation EN VOL (ADR 0214 — demande D5) :
     * drapeau coopératif testé ENTRE les unités (racines, AAR), le
     * passage publie son acquis et se marque PARTIEL « annulé par
     * l'utilisateur ».
     *
     * Tolérance de version : défaut `false` (pack ancien).
     *
     * @return `true` si un passage était en vol ; `false` sinon.
     */
    public fun annulerPassage(): Boolean = false

    /**
     * Retente les ÉCHECS du dernier passage SEULEMENT (ADR 0214 —
     * demande D4) : les unités saines sont reprises du cache mtime sans
     * re-lecture, seuls les dossiers/AAR douteux et les pendances d'une
     * annulation sont re-payés.
     *
     * Tolérance de version : défaut `false` (pack ancien).
     *
     * @return `true` s'il y avait des échecs/pendances à retenter ;
     *         `false` sinon.
     */
    public fun reesayerEchecs(): Boolean = false
}

/**
 * Résolution ServiceLoader du moteur XML — l'unique façon NON interne
 * d'obtenir une instance. Échoue explicitement (erreur lisible, pas de
 * comportement fantôme) si aucun moteur n'est enregistré.
 */
public object ServiceMoteurLangageXml {
    /**
     * Charge le moteur unique du classpath. Un moteur absent du graphe
     * de dépendances est une erreur de BUILD (le module n'est pas
     * branché), pas une erreur d'exécution à gérer silencieusement.
     */
    public fun unique(): MoteurLangageXml {
        val moteurs =
            java.util.ServiceLoader
                .load(MoteurLangageXml::class.java)
                .toList()
        check(moteurs.isNotEmpty()) {
            "aucun moteur XML enregistré : jo.lspxml.api.MoteurLangageXml absent du classpath"
        }
        check(moteurs.size == 1) {
            "plusieurs moteurs XML enregistrés : " +
                moteurs.joinToString { it.identifiant }
        }
        return moteurs.single()
    }
}
