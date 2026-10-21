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

/** Genre d'un surlignage d'occurrence (→ DocumentHighlightKind LSP). */
public enum class GenreSurlignageXml {
    /** LECTURE de la ressource (référence — DocumentHighlightKind.Read). */
    LECTURE,

    /** ÉCRITURE (déclaration — DocumentHighlightKind.Write). */
    ECRITURE,
}

/**
 * Surlignage d'une occurrence de ressource (`textDocument/
 * documentHighlight`, lot L3) — décalages dans le texte du document
 * COURANT.
 *
 * @property debut décalage de début de l'occurrence (inclus).
 * @property fin décalage de fin de l'occurrence (exclusif).
 * @property genre nature de l'occurrence (lecture / écriture).
 */
public data class PlageSurligneeXml(
    public val debut: Int,
    public val fin: Int,
    public val genre: GenreSurlignageXml,
)

/**
 * Une couleur de document (`textDocument/documentColor`, lot L3 du
 * plan 1.3.0) : littéral `#RGB`/`#ARGB`/`#RRGGBB`/`#AARRGGBB` ou
 * référence `@color/…` RÉSOLUE par l'index — canaux 0..1 (contrat LSP).
 *
 * @property debut décalage de début (inclus) — après le « # » pour un
 *           littéral, sur le nom pour une référence.
 * @property fin décalage de fin (exclusif).
 * @property rouge canal rouge 0..1.
 * @property vert canal vert 0..1.
 * @property bleu canal bleu 0..1.
 * @property alpha canal alpha 0..1 (1 = opaque).
 */
public data class CouleurDocumentXml(
    public val debut: Int,
    public val fin: Int,
    public val rouge: Double,
    public val vert: Double,
    public val bleu: Double,
    public val alpha: Double,
)

/**
 * Présentation d'une couleur (`textDocument/colorPresentation`, lot
 * L3) : le libellé canonique (`#RRGGBB` opaque, `#AARRGGBB` sinon) et
 * l'édition de remplacement de la plage d'origine par CE libellé.
 *
 * @property debut décalage de début du remplacement (inclus).
 * @property fin décalage de fin du remplacement (exclusif).
 * @property libelle le libellé canonique.
 */
public data class PresentationCouleurXml(
    public val debut: Int,
    public val fin: Int,
    public val libelle: String,
)

/**
 * Canaux d'une couleur choisie dans le sélecteur de l'éditeur (lot L3)
 * — 0..1 chacun (contrat LSP `Color`).
 *
 * @property rouge canal rouge 0..1.
 * @property vert canal vert 0..1.
 * @property bleu canal bleu 0..1.
 * @property alpha canal alpha 0..1 (1 = opaque).
 */
public data class CanauxCouleurXml(
    public val rouge: Double,
    public val vert: Double,
    public val bleu: Double,
    public val alpha: Double,
)

/**
 * Plages LIÉES d'une balise (`textDocument/linkedEditingRange`, lot L3
 * du plan 1.3.0) : le nom de l'ouvrante et le nom de la fermante du
 * MÊME élément — l'édition de l'une entraîne celle de l'autre côté
 * client.
 *
 * @property ouvrante plage du nom dans la balise ouvrante.
 * @property fermante plage du nom dans la balise fermante.
 */
public data class PlageLieeXml(
    public val ouvrante: PlageSelectionXml,
    public val fermante: PlageSelectionXml,
)

/**
 * Plage d'un maillon de la chaîne de sélection (lot L3).
 *
 * @property debut décalage de début du maillon (inclus).
 * @property fin décalage de fin du maillon (exclusif).
 */
public data class PlageSelectionXml(
    public val debut: Int,
    public val fin: Int,
)

/**
 * Chaîne des nœuds englobants d'une position (`textDocument/
 * selectionRange`, lot L3) : l'expansion progressive de la sélection —
 * chaque maillon STRICTEMENT plus grand que le précédent (le plus
 * profond d'abord, le fichier entier en dernier).
 *
 * @property plages les maillons du plus profond au plus englobant,
 *           STRICTEMENT croissants.
 */
public data class ChaineSelectionXml(
    public val plages: List<PlageSelectionXml>,
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

/**
 * Symbole de document (`textDocument/documentSymbol`, S1) — un nœud
 * HIÉRARCHIQUE de l'arbre XML (racine, enfant, petit-enfant…), miroir
 * DOM → outline éditeur. La traduction vers `DocumentSymbol` LSP
 * (kinds, ranges UTF-16, enfants imbriqués) appartient à l'hôte.
 *
 * Plages : décalages de caractères dans le texte source (la conversion
 * ligne/colonne appartient à l'hôte, comme pour `EmplacementXml`).
 *
 * @property nom nom de la balise (`LinearLayout`, `string`, `item`) ;
 *           pour les entrées values l'attribut `name=` est ajouté entre
 *           crochets (`string[hello]`) — l'outline distingue deux
 *           entrées sœurs d'un même `values/strings.xml`.
 * @property detail détail optionnel : préfixe d'espace de noms + nom
 *           (`android.widget.LinearLayout`), ou nom local court. `null`
 *           si non pertinent.
 * @property genre genre du symbole (→ SymbolKind LSP côté hôte).
 * @property debutPlage décalage de début du NOM du symbole (sélection
 *           naturelle — curseur « aller à »).
 * @property finPlage décalage de fin du NOM du symbole (exclusif).
 * @property debutSelection décalage de début de la PLAGE COMPLÈTE du
 *           symbole (balise ouvrante → fermante, ou `<>` auto-fermante).
 * @property finSelection décalage de fin de la PLAGE COMPLÈTE (exclusif).
 * @property enfants symboles enfants (récursif — l'arbre LSP est
 *           strictement l'arbre DOM).
 */
public data class SymboleDocumentXml(
    public val nom: String,
    public val detail: String?,
    public val genre: GenreSymboleXml,
    public val debutPlage: Int,
    public val finPlage: Int,
    public val debutSelection: Int,
    public val finSelection: Int,
    public val enfants: List<SymboleDocumentXml>,
)

/** Genre d'un symbole de document XML (→ SymbolKind LSP côté hôte). */
public enum class GenreSymboleXml {
    /** Élément racine d'un layout/manifest/menu (`<LinearLayout>`, `<manifest>`). */
    RACINE,

    /** Vue usuelle (`TextView`, `Button`, `ImageView`…) — kind Class LSP. */
    VUE,

    /** Conteneur de vues (`LinearLayout`, `FrameLayout`…). */
    CONTENEUR,

    /** Balise « include » ou « merge » (référence / racine fragile). */
    INCLUSION,

    /** Entrée de `res/values` (`<string>`, `<color>`, `<dimen>`). */
    ENTREE_VALEURS,

    /** Style ou thème (`<style>`). */
    STYLE,

    /** Item d'un menu (`<item>`) ou élément générique inconnu. */
    ITEM,

    /** Balise inconnue / sans genre reconnu. */
    AUTRE,
}

/**
 * Plage pliable (`textDocument/foldingRange`, XP11 — plan de
 * stabilisation LSP §3.3 S2) : un couple de DÉCALAGES du document
 * courant, du DÉBUT de la balise ouvrante à la FIN de la fermante.
 * La traduction en lignes LSP (`FoldingRange` startLine/endLine)
 * appartient à l'hôte (il possède le convertisseur du document).
 *
 * @property debut décalage de début de la balise ouvrante (inclus).
 * @property fin décalage de fin de la fermante (exclusif).
 */
public data class PlagePliableXml(
    public val debut: Int,
    public val fin: Int,
)

/**
 * Plage renommable (`textDocument/prepareRename`, S3 — plan de
 * stabilisation LSP §3.3 « rename borné des entrées values ») : la
 * plage du NOM d'une ressource projet + le NOM COURANT (placeholder
 * pré-rempli dans l'éditeur). Miroir de `PlageRenommableKotlin` — la
 * traduction en Range LSP appartient à l'hôte.
 *
 * @property plage plage du NOM renommable (décalages du document).
 * @property placeholder nom courant (valeur pré-remplie du renommage).
 */
public data class PlageRenommableXml(
    public val plage: jo.lspxml.parse.Plage,
    public val placeholder: String,
)
