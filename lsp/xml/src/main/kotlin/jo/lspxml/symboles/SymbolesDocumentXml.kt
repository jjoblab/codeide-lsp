package jo.lspxml.symboles

import jo.lspxml.api.GenreSymboleXml
import jo.lspxml.api.SymboleDocumentXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * Génération de la liste `textDocument/documentSymbol` (S1) —
 * traduction du DOM en symboles HIÉRARCHIQUES LSP (miroir de l'arbre
 * XML → outline éditeur).
 *
 * Le genre de chaque nœud est DÉDUIT du nom de balise et (rarement)
 * de la position dans le document (la racine canonique = RACINE). Le
 * détail reprend le FQCN quand la balise est un widget connu (utilisé
 * par l'outline pour afficher `android.widget.TextView` à côté du nom).
 *
 * Tolérance (X1) : un document vide / malformé rend une liste VIDE
 * (jamais d'exception — la racine absente est un cas légitime pendant
 * la frappe).
 */
internal object SymbolesDocumentXml {
    /**
     * Conteneurs (ViewGroup) — une balise nommée ainsi est marquée
     * CONTENEUR dans l'outline (au lieu de VUE) pour distinguer les
     * nœuds structurants des feuilles dans la vue éditeur.
     *
     * Cohérent avec [jo.lspxml.catalog.CataloguePlateforme] (XP4) —
     * un seul endroit à mettre à jour si un nouveau ViewGroup entre.
     */
    private val CONTENEURS: Set<String> =
        setOf(
            "LinearLayout", "FrameLayout", "RelativeLayout",
            "GridLayout", "TableLayout", "TableRow",
            "ListView", "GridView", "ScrollView", "HorizontalScrollView",
            "NestedScrollView", "RecyclerView", "ConstraintLayout",
            "CoordinatorLayout", "AppBarLayout", "Toolbar",
            "TabLayout", "CardView", "ViewSwitcher", "ViewFlipper",
            "AdapterView", "AdapterViewAnimator", "Gallery",
            "AbsListView", "AbsSpinner", "ExpandableListView",
            "DatePicker", "TimePicker", "CalendarView",
            "NumberPicker", "GestureOverlayView",
        )

    /** Balises d'entrée `res/values` —> genre ENTREE_VALEURS (ou STYLE). */
    private val ENTREES_VALEURS: Set<String> =
        setOf("string", "color", "dimen", "bool", "integer", "array", "integer-array", "string-array", "attr", "item")

    /**
     * Génère la liste des symboles RACINES (canoniquement une ; les
     * sœurs de reprise d'un document invalide vivent dans la liste).
     */
    internal fun depuis(document: DocumentXml): List<SymboleDocumentXml> =
        document.racines.map { racine ->
            convertir(racine, estRacine = true)
        }

    /**
     * Convertit un élément DOM en symbole LSP — la récursion suit
     * [XmlElement.enfants] strictement (l'arbre LSP == l'arbre XML).
     */
    private fun convertir(element: XmlElement, estRacine: Boolean): SymboleDocumentXml {
        val nom = element.nom
        val genre = genreDe(nom, estRacine)
        val nomAffiche = nomAfficheDe(nom, element)
        val detail = detailDe(nom)
        // Plage du nom = sélection fine (curseur « aller à »).
        val debutPlage = element.plageNom.debut
        val finPlage = element.plageNom.fin
        // Plage complète = balise ouvrante → fermante (ou auto-fermante).
        val debutSelection = element.plageOuvrante.debut
        val finSelection = element.plageFermante?.fin ?: element.plageOuvrante.fin
        val enfantsSymboles = element.enfants.map { enfant -> convertir(enfant, estRacine = false) }
        return SymboleDocumentXml(
            nom = nomAffiche,
            detail = detail,
            genre = genre,
            debutPlage = debutPlage,
            finPlage = finPlage,
            debutSelection = debutSelection,
            finSelection = finSelection,
            enfants = enfantsSymboles,
        )
    }

    /**
     * Genre LSP déduit du nom de balise et de la position.
     *
     * - RACINE si l'élément n'a pas de parent (canonique : un layout a
     *   une seule racine — `<LinearLayout>` au sommet).
     * - INCLUSION pour `<include>` / `<merge>` / `<view>` (balises de
     *   rebond — l'outline les marque pour les distinguer des vues).
     * - STYLE pour `<style>` (res/values/styles.xml).
     * - ENTREE_VALEURS pour les balises de `res/values`.
     * - CONTENEUR pour les ViewGroups connus (la liste ci-dessus).
     * - VUE pour les widgets feuilles (`TextView`, `Button`…) : balise
     *   commençant par une MAJUSCULE (convention Android).
     * - ITEM pour `<item>` (menus, arrays).
     * - AUTRE sinon (balise inconnue / non classée).
     */
    private fun genreDe(nom: String, estRacine: Boolean): GenreSymboleXml =
        when {
            estRacine -> GenreSymboleXml.RACINE
            nom == "include" || nom == "merge" || nom == "view" -> GenreSymboleXml.INCLUSION
            nom == "style" -> GenreSymboleXml.STYLE
            nom in ENTREES_VALEURS && nom != "item" -> GenreSymboleXml.ENTREE_VALEURS
            nom == "item" -> GenreSymboleXml.ITEM
            nom in CONTENEURS -> GenreSymboleXml.CONTENEUR
            nom.firstOrNull()?.isUpperCase() == true -> GenreSymboleXml.VUE
            else -> GenreSymboleXml.AUTRE
        }

    /**
     * Nom affiché dans l'outline : pour une entrée values (`<string
     * name="hello">`), on ajoute l'attribut `name` entre crochets
     * (`string[hello]`) — l'outline distingue deux entrées sœurs.
     */
    private fun nomAfficheDe(nom: String, element: XmlElement): String {
        val cle = element.valeurDe("name")
        return when {
            cle == null -> nom
            nom in ENTREES_VALEURS && nom != "item" -> "$nom[$cle]"
            nom == "style" -> "style[$cle]"
            else -> nom
        }
    }

    /** Détail : le FQCN `android.widget.X` pour les vues connues. */
    private fun detailDe(nom: String): String? =
        when {
            nom in CONTENEURS -> "android.widget.$nom"
            nom.firstOrNull()?.isUpperCase() == true -> "android.widget.$nom"
            else -> null
        }
}
