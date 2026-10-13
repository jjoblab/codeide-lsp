package jo.lspxml.api

import jo.lspxml.parse.DocumentXml

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
