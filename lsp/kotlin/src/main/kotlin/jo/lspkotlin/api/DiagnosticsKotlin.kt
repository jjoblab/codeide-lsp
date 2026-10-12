package jo.lspkotlin.api

/**
 * Diagnostic du moteur Kotlin — type NEUTRE de protocole (K2,
 * LSP_KOTLIN.md §4.1 « diagnostics ») : le moteur n'expose AUCUN type
 * LSP4J (condition du découplage futur en « pack langue » téléchargeable,
 * décision D6) ; l'hôte (`:lsp:host`) traduit ces données vers LSP
 * (positions ligne/colonne, sévérités du protocole).
 *
 * Philosophie D3 (LSP_KOTLIN.md §3) : le catalogue est CONSERVATEUR —
 * toute forme non modélisée sous-rapporte (jamais de faux positif) ;
 * l'utilisateur a le build pour la vérité. Chaque code a sa fixture TDD
 * et son test de sous-rapport.
 *
 * Les positions sont des DÉCALAGES de caractères (offsets) dans le texte
 * source analysé — la conversion ligne/colonne est un soin de l'hôte, qui
 * possède le document LSP.
 *
 * @param code code stable du diagnostic (voir [CodesDiagnosticsKotlin]).
 * @param severite gravité (erreur/avertissement).
 * @param message message lisible (français, règles du dépôt).
 * @param debut décalage de début dans la source (inclus).
 * @param fin décalage de fin dans la source (exclusif).
 */
public data class DiagnosticKotlin(
    public val code: String,
    public val severite: SeveriteKotlin,
    public val message: String,
    public val debut: Int,
    public val fin: Int,
)

/**
 * Gravité d'un diagnostic — neutre de protocole, traduite par l'hôte.
 */
public enum class SeveriteKotlin {
    /** Le code ne compile pas (ou le conflit est certain). */
    ERREUR,

    /** Réel mais non bloquant (ex. import inutilisé). */
    AVERTISSEMENT,
}

/**
 * Catalogue des codes de diagnostics du moteur Kotlin (K2 : périmètre
 * SYNTAXIQUE seul — les diagnostics sémantiques arrivent K3/K4 avec le
 * modèle de symboles, LSP_KOTLIN.md §4.3).
 *
 * Convention de nommage héritée du cadrage : préfixe `kt.` — l'éditeur
 * distingue nos diagnostics de ceux du compilateur Gradle (qui n'ont pas
 * de code) et des diagnostics Java (`JavaProblemFamily`).
 */
public object CodesDiagnosticsKotlin {
    /**
     * Fragment non reconnu par la grammaire vendorée (marqueur d'erreur
     * du parseur) — édition en cours ou syntaxe fausse. Message = celui
     * du parseur quand il en porte un, générique sinon.
     */
    public const val SYNTAXE: String = "kt.syntax"

    /**
     * Import non utilisé : le nom importé (simple ou alias) n'apparaît
     * JAMAIS comme identifiant hors des directives package/import du
     * fichier. Conservative par construction : un nom présent en
     * commentaire, chaîne ou position inconnue n'est PAS signalé
     * (sous-rapport volontaire, D3). Les imports joker (`.*`) et les
     * directives vides/en édition ne sont jamais signalés.
     */
    public const val IMPORT_INUTILISE: String = "kt.unusedImport"

    /**
     * Imports conflictuels : deux imports non joker de noms qualifiés
     * DISTINCTS partagent le même nom effectif (alias ou dernier
     * segment) — la référence serait ambiguë. Signifié sur l'import le
     * plus tardif, message portant les deux noms qualifiés.
     */
    public const val IMPORT_CONFLIT: String = "kt.conflictingImport"
}
