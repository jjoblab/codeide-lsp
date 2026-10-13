package jo.lspxml.api

/**
 * Types NEUTRES des requêtes de symboles du moteur XML (XE2/XE3/XE4,
 * LSP_XML.md §4.3) — miroir de `SymbolesKotlin` : le moteur n'expose
 * AUCUN type LSP4J (condition du pack langue), l'hôte traduit vers le
 * protocole (positions, gravités, kinds LSP).
 *
 * Les positions sont des DÉCALAGES de caractères dans le texte source
 * analysé ; la conversion ligne/colonne UTF-16 appartient à l'hôte.
 */

/**
 * Proposition de complétion (une entrée de la liste `textDocument/
 * completion` après traduction LSP).
 *
 * @property libelle texte affiché/inséré (`TextView`, `android:text`,
 *           `@string/hello`, `match_parent`).
 * @property genre genre du candidat (traduit en CompletionItemKind).
 * @property detail qualification affichée après le libellé
 *           (`android.widget.TextView`, `format: dimension`,
 *           `string — projet (values/strings.xml)`).
 * @property texteTri clé de tri encodée par le moteur (pertinence
 *           décroissante + longueur + libellé — le client LSP trie par
 *           sortText seul, le tie-break voyage dans la clé, motif du
 *           LSP Java/Kotlin).
 */
public data class PropositionCompletionXml(
    public val libelle: String,
    public val genre: GenreCompletionXml,
    public val detail: String?,
    public val texteTri: String,
)

/** Genre d'une proposition (→ CompletionItemKind LSP côté hôte). */
public enum class GenreCompletionXml {
    /** Balise/élément (`TextView`, `<include>`). */
    BALISE,

    /** Attribut (`android:text`, `tools:visibility`). */
    ATTRIBUT,

    /** Valeur d'énumération (`match_parent`, `wrap_content`). */
    VALEUR_ENUM,

    /** Valeur de fanion combinable (`center_vertical|…`). */
    VALEUR_FANION,

    /** Référence de ressource (`@string/hello`, `?attr/colorPrimary`). */
    REFERENCE,

    /** Balise d'entrée de `res/values` (`string`, `color`, `dimen`). */
    ENTREE_VALEURS,

    /** Nom de style/thème (`Theme.Material.Light`, `AppTheme`). */
    STYLE,
}

/**
 * Contenu de survol (`textDocument/hover`) — texte simple (même forme
 * que le LSP Kotlin : le client rend une chaîne).
 *
 * @property valeur texte du survol (`class android.widget.TextView`,
 *           `string « hello » — projet, values/strings.xml`).
 */
public data class ContenuSurvoleXml(
    public val valeur: String,
)

/**
 * Emplacement d'une définition (`textDocument/definition`) — DÉCALAGES
 * dans le texte du fichier CIBLE.
 *
 * @property uri URI du fichier déclarant (`file://…`).
 * @property debut décalage de début dans la cible (inclus).
 * @property fin décalage de fin dans la cible (exclusif).
 */
public data class EmplacementXml(
    public val uri: String,
    public val debut: Int,
    public val fin: Int,
)

/**
 * Édition texte NEUTRE d'un correctif (XE4 quick-fixes) — l'hôte traduit
 * les décalages en positions LSP (il possède le document), le moteur
 * reste maître de la SÉMANTIQUE de la correction.
 *
 * @property debut décalage de début du remplacement (inclus).
 * @property fin décalage de fin du remplacement (exclusif).
 * @property texte texte de remplacement (vide = suppression pure,
 *           égal au texte existant = insertion pure).
 */
public data class EditionXml(
    public val debut: Int,
    public val fin: Int,
    public val texte: String,
)

/**
 * Correctif rapide (XE4) : un titre lisible + les éditions qui le
 * réalisent (`Insérer xmlns:android`, `Remplacer par « TextView »`).
 *
 * @property titre titre du CodeAction côté LSP.
 * @property editions éditions à appliquer DANS LE FICHIER COURANT.
 */
public data class CorrectifXml(
    public val titre: String,
    public val editions: List<EditionXml>,
)
