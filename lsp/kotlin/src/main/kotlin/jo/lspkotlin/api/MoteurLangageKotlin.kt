package jo.lspkotlin.api

import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin

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
 * Périmètre K1 : analyse syntaxique seule. K2 y ajoutera le contrat de
 * session (cycle de vie SessionProjet/RegistreFermeture, capacités,
 * publication de diagnostics — même forme que `:lsp:java`, cf.
 * LSP_JAVA.md), en le gardant TOUT ENTIER derrière ce point d'entrée.
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
