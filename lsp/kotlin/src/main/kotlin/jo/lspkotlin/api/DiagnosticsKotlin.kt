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
 * SYNTAXIQUE ; K4 : périmètre SÉMANTIQUE conservateur — LSP_KOTLIN.md
 * §4.3 « diagnostics initiaux », chacun avec sa fixture TDD et sa
 * garde de sous-rapport).
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

    /**
     * Référence NON RÉSOLUE (K4) : un identifiant simple en position
     * d'expression qui ne résout NI par les portées (locales,
     * paramètres, membres des englobantes, premier niveau du fichier),
     * NI par les déclarations du projet, NI par les classes (imports,
     * paquet, imports par défaut), NI par les appelables de premier
     * niveau de l'index. Conservateur : SÉLECTEURS qualifiés jamais
     * signalés (le récepteur peut être inconnu), `it` jamais signalé,
     * fichiers portant une erreur de parse jamais diagnostiqués (édition
     * en cours), régime sans classpath jamais signalé (sous-rapport D3).
     * C'est LA cible des quick-fixes d'import ([importsCandidats]).
     */
    public const val REFERENCE_NON_RESOLUE: String = "kt.unresolved"

    /**
     * Type d'initialiseur INCOMPATIBLE avec le type écrit (K4, cas
     * SIMPLES seulement) : littéral de famille A affecté à un type écrit
     * d'une famille B DÉMONTRABLE (primitives Kotlin usuels). Les
     * familles : numérique (littéral entier → Int/Long/Short/Byte/
     * Float/Double — conversion littérale légale), décimal (Float/
     * Double), chaîne, booléen, caractère. Tout ce qui n'est pas un
     * littéral, ou un type écrit hors des familles connues, n'est JAMAIS
     * signalé (opérateurs, conversions, sous-rapport D3).
     */
    public const val TYPE_INCOMPATIBLE: String = "kt.typeMismatch"

    /**
     * Nombre d'ARGUMENTS incompatible (K4) : appel d'une candidate du
     * PROJET (fonction locale/membre/premier niveau, constructeur de
     * classe du projet) dont AUCUNE surcharge n'accepte l'arité écrite.
     * Les paramètres par défaut élargissent la plage légale
     * (minimum = paramètres − défauts) et un `vararg` rend la candidate
     * inéligible au contrôle (sous-rapport). Les cibles du classpath ne
     * sont pas contrôlées à K4 (surcharges par arité non lues).
     */
    public const val NOMBRE_ARGUMENTS: String = "kt.argumentCount"

    /**
     * Accès SÛR manquant (K4) : sélecteur par `.` sur un récepteur de
     * type écrit NULLABLE démontrable (`String?`), sans garde de
     * nullité dominante (`!= null`, garde `== null` à sortie précoce)
     * — Kotlin exige `?.` ou `!!`. Uniquement les récepteurs PAR NOM
     * SIMPLE au type écrit nullable : retours d'appels, chaînes et
     * récepteurs inconnus jamais signalés (sous-rapport D3).
     */
    public const val ACCES_NON_SUR: String = "kt.unsafeNullable"

    /**
     * RETOUR manquant (K4) : fonction à corps de BLOC, type de retour
     * ÉCRIT non Unit, dont le corps ne contient NI return NI throw (en
     * dehors des littéraux de fonction et fonctions locales imbriquées).
     * Les corps d'expression, types inférés et fonctions abstraites ne
     * sont jamais signalés.
     */
    public const val RETOUR_MANQUANT: String = "kt.missingReturn"

    /**
     * Code INATTEIGNABLE (K4) : première déclaration d'un bloc qui suit
     * un `return`/`throw` FRÈRE direct — le reste du bloc est mort.
     * Signalé UNE fois par bloc, sur la plage de cette première
     * déclaration ; `break`/`continue` ne comptent pas (terminaux
     * seulement dans les boucles, K5).
     */
    public const val CODE_INATTEIGNABLE: String = "kt.unreachable"

    /**
     * Réaffectation d'un `val` (K4) : affectation (`=`, pas `==`) à un
     * nom simple déclaré `val` — locale, paramètre (immuable par
     * construction en Kotlin) ou propriété de premier niveau/membre.
     * L'affectation QUALIFIÉE (`a.b = …`) n'est jamais signalée (le
     * propriétaire peut être mutable ailleurs), idem les cibles
     * introuvables (sous-rapport D3).
     */
    public const val REAFFECTATION_VAL: String = "kt.valReassign"
}
