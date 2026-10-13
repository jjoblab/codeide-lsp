package jo.lspxml.api

/**
 * Diagnostic du moteur XML — type NEUTRE de protocole (XE1, LSP_XML.md §4 :
 * le moteur n'expose AUCUN type LSP4J, condition du découplage « pack
 * langue » ; l'hôte `:lsp:host` traduit vers LSP). Miroir de
 * `DiagnosticKotlin` : positions en DÉCALAGES de caractères, conversion
 * ligne/colonne appartenant à l'hôte qui possède le document LSP.
 *
 * Philosophie X4 : le catalogue est CONSERVATEUR — une forme non modélisée
 * sous-rapporte (jamais de faux positif) ; l'utilisateur a `aapt` pour la
 * vérité. Chaque code a sa fixture TDD et sa garde de sous-rapport.
 *
 * @param code code stable du diagnostic (voir [CodesDiagnosticsXml]).
 * @param severite gravité (erreur/avertissement).
 * @param message message lisible (français, règles du dépôt).
 * @param debut décalage de début dans la source (inclus).
 * @param fin décalage de fin dans la source (exclusif).
 */
public data class DiagnosticXml(
    public val code: String,
    public val severite: SeveriteXml,
    public val message: String,
    public val debut: Int,
    public val fin: Int,
)

/** Gravité d'un diagnostic — neutre de protocole, traduite par l'hôte. */
public enum class SeveriteXml {
    /** Le XML ne compile pas (aapt le rejetterait). */
    ERREUR,

    /** Réel mais non bloquant (suggestion conservatoire). */
    AVERTISSEMENT,
}

/**
 * Catalogue des codes de diagnostics du moteur XML (XE1 : syntaxe +
 * règles de structure ; XE3 : références de ressources ; XE4 : manifest).
 *
 * Convention de nommage : préfixe `xml.` — l'éditeur distingue nos
 * diagnostics de ceux du build Gradle (sans code) et des diagnostics
 * Kotlin (`kt.`).
 */
public object CodesDiagnosticsXml {
    /** Balise ouverte jamais fermée (fin de fichier atteinte). */
    public const val TAG_NON_FERME: String = "xml.tagNonFermee"

    /** Balise fermante sans balise ouvrante correspondante (orpheline). */
    public const val TAG_ORPHELINE: String = "xml.tagOrpheline"

    /** Balise fermante refermant des éléments intermédiaires non fermés. */
    public const val TAG_FERMANTE_INCOHERENTE: String = "xml.tagFermanteIncoherente"

    /** Attribut porté DEUX FOIS par le même élément. */
    public const val ATTRIBUT_DUPLIQUE: String = "xml.attributDuplique"

    /** Guillemet de valeur d'attribut non refermé. */
    public const val GUILLEMET_NON_FERME: String = "xml.guillemetNonFerme"

    /** Esperluette non échappée dans une section de texte. */
    public const val ENTITE_INVALIDE: String = "xml.entiteInvalide"

    /** `xmlns:android` manquant sur la racine d'un layout. */
    public const val MANQUE_XMLNS_ANDROID: String = "xml.manqueXmlnsAndroid"

    /**
     * `layout_width`/`layout_height` absents d'un élément de layout —
     * AVERTISSEMENT volontaire (le cadrage dit « hint, pas error » :
     * un tools:targetApi ou un include stylé peut l'expliquer).
     */
    public const val TAILLES_MANQUANTES: String = "xml.taillesManquantes"

    /** `@+id/…` déclaré deux fois dans le MÊME fichier. */
    public const val ID_DUPLIQUE: String = "xml.idDuplique"

    /** `<include>` sans attribut `layout`. */
    public const val INCLUDE_SANS_LAYOUT: String = "xml.includeSansLayout"

    /** `<merge>` hors position racine. */
    public const val MERGE_NON_RACINE: String = "xml.mergeNonRacine"

    /** Entrée de `res/values` sans attribut `name`. */
    public const val ENTREE_SANS_NOM: String = "xml.entreeSansNom"

    /** Entrée de `res/values` au `name` dupliqué dans le fichier. */
    public const val NOM_DUPLIQUE: String = "xml.nomDuplique"

    /**
     * Balise de classe INCONNUE (XE4, couture conservatrice X4 : ne
     * s'affiche que si le catalogue plateforme + le scan des sources +
     * la vue de noms du classpath sont TOUS d'accord pour dire que la
     * classe n'existe pas — jamais à chaud, jamais sans classpath).
     */
    public const val TAG_INCONNU: String = "xml.tagInconnu"

    /** Référence `@type/nom` non résolue (XE3, projet + framework). */
    public const val REFERENCE_NON_RESOLUE: String = "xml.referenceNonResolue"

    /** Manifest : attribut `package` absent de la racine `<manifest>`. */
    public const val MANIFEST_SANS_PACKAGE: String = "xml.manifestSansPackage"

    /** Composant du manifest sans `android:name` (XE4, conservatoire). */
    public const val COMPOSANT_SANS_NOM: String = "xml.composantSansNom"
}
