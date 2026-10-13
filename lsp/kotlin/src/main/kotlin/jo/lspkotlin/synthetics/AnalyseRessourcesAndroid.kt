package jo.lspkotlin.synthetics

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lecture CONSERVATRICE des ressources Android (K5, LSP_KOTLIN.md §5
 * décision D5) — le versant « entrées » des classes synthétiques : le
 * paquet du manifest, les identifiants et racines des layouts, les
 * entrées nommées de `values`, les noms de fichiers de ressources.
 *
 * Toute entrée ILLISIBLE est ABSENTE (partiel honnête D3) : un XML
 * cassé, un dossier introuvable ou un manifeste sans attribut
 * `package` ne produit RIEN — jamais d'exception vers l'appelant (la
 * complétion vit sur le chemin des frappes).
 *
 * Analyses XML minimales par DOM : les documents de ressources sont
 * petits (quelques Ko) et les attributs cherchés sont peu nombreux ;
 * les DOCTYPE sont refusés (ressources locales attendues, entrée
 * externe = rejet du fichier).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le catalogue
 * des LECTURES (manifest, layouts, valeurs, fichiers, widgets) : une
 * fonction = UNE question posée aux ressources, même patron que
 * ScanProjet/CompletionKotlin ; SwallowedException — l'exception EST
 * le signal du partiel honnête, le `null`/liste vide est le contrat.
 */
@Suppress("TooManyFunctions", "SwallowedException")
internal object AnalyseRessourcesAndroid {
    /** Paquet déclaré du manifeste (`<manifest package="…">`). */
    internal fun paquetDuManifest(manifest: File): String? {
        val document = lire(manifest) ?: return null
        val paquet = document.documentElement.getAttribute("package")
        return paquet.takeIf { it.isNotBlank() }
    }

    /**
     * Analyse d'UN layout : racine, identifiants portés (nom + type du
     * porteur), nature DataBinding, variables `<data>`.
     *
     * @property fichier chemin du layout (cible de go-to-definition).
     * @property racine tag de l'élément racine (`TextView`, `layout`,
     *           `merge`…).
     * @property ids identifiants déclarés `@+id/…` : nom → type de vue.
     * @property dataBinding racine `<layout>` (AGP ne compile ces
     *           fichiers qu'avec dataBinding activé — la PREUVE vit
     *           dans le fichier, décision D3).
     * @property variables variables `<variable name type>` de la
     *           section `<data>` (DataBinding seul).
     */
    internal class LayoutAnalyse(
        internal val fichier: File,
        internal val racine: String,
        internal val ids: LinkedHashMap<String, String>,
        internal val dataBinding: Boolean,
        internal val variables: List<Pair<String, String>>,
    )

    /**
     * Analyse TOUS les layouts de `res/layout*` (bornée) — ordre
     * déterministe (tri par chemin) ; racines `<merge>` nourrissent
     * `R.id` mais pas de BINDING (AGP n'y génère pas de classe).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — le continue du fichier illisible et celui de la variable muette
     * sont les gardes du partiel honnête (D3).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun layouts(res: File): List<LayoutAnalyse> =
        fichiersSous(res, prefixe = "layout", suffixe = ".xml").mapNotNull { fichier ->
            val document = lire(fichier) ?: return@mapNotNull null
            val racineElement = document.documentElement ?: return@mapNotNull null
            val estDataBinding = racineElement.tagName == "layout"
            val ids = LinkedHashMap<String, String>()
            for (element in descendants(racineElement)) {
                val brut = element.getAttribute("android:id")
                val nom = nomId(brut) ?: continue
                ids.putIfAbsent(nom, typeVue(element.tagName))
            }
            val variables =
                if (estDataBinding) {
                    descendants(racineElement).mapNotNull { element ->
                        if (element.tagName != "variable") return@mapNotNull null
                        val nom = element.getAttribute("name")
                        val type = element.getAttribute("type")
                        if (nom.isBlank() || type.isBlank()) null else Pair(nom, type)
                    }
                } else {
                    emptyList()
                }
            LayoutAnalyse(fichier, racineElement.tagName, ids, estDataBinding, variables)
        }

    /**
     * Entrées nommées de `res/values*` par FAMILLE R — `string`,
     * `color`, `dimen`, `integer`, `bool`, `array` (`string-array` et
     * `integer-array` compris). Styles et pluriels exclus (formats
     * à part, sous-rapport honnête).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue du fichier illisible et de l'entrée non nommée
     * sont les gardes du partiel honnête (D3).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun entreesValeurs(res: File): Map<String, List<String>> {
        val parFamille = HashMap<String, MutableSet<String>>()
        for (fichier in fichiersSous(res, prefixe = "values", suffixe = ".xml")) {
            val document = lire(fichier) ?: continue
            val racineElement = document.documentElement ?: continue
            for (element in descendants(racineElement)) {
                val famille = familleR(element.tagName) ?: continue
                val nom = element.getAttribute("name")
                if (nom.isBlank()) continue
                parFamille.getOrPut(famille) { linkedSetOf() }.add(nom)
            }
        }
        return parFamille.mapValues { (_, noms) -> noms.toList() }
    }

    /** Noms de fichiers (sans extension) sous les dossiers `[prefixe]*`. */
    internal fun nomsFichiers(
        res: File,
        prefixe: String,
    ): List<String> = fichiersSous(res, prefixe, suffixe = null).map { fichier ->
        fichier.name.substringBeforeLast('.')
    }

    /**
     * Type de vue d'un tag de layout — composants connus mappés sur
     * leur classe `android.*`, tags PLEINEMENT QUALIFIÉS pris tels
     * quels (vues personnalisées, `androidx.*`), inconnu nu rabattu
     * sur `android.view.View` (jamais faux : le champ existe, son type
     * précis appartient au build).
     */
    internal fun typeVue(tag: String): String {
        if (tag.contains('.')) return tag
        return VUES_CONNUES[tag] ?: "android.view.View"
    }

    /** Nom d'un identifiant `@+id/…` (les autres formes sont ignorées). */
    private fun nomId(brut: String): String? =
        if (brut.startsWith("@+id/")) brut.removePrefix("@+id/").takeIf { it.isNotBlank() } else null

    /** Descendants ÉLÉMENTS d'un nœud (profondeur comprise, ordre du document). */
    private fun descendants(racine: Element): List<Element> {
        val resultat = mutableListOf<Element>()
        fun visiter(element: Element) {
            resultat.add(element)
            val enfants = element.childNodes
            for (indice in 0 until enfants.length) {
                (enfants.item(indice) as? Element)?.let { visiter(it) }
            }
        }
        visiter(racine)
        return resultat
    }

    /** Famille R d'un tag de `values` (`string-array` → `array`). */
    private fun familleR(tag: String): String? =
        when (tag) {
            "string", "color", "dimen", "integer", "bool" -> tag
            "string-array", "integer-array" -> "array"
            else -> null
        }

    /**
     * Fichiers des dossiers `res/<prefixe>*` portant le suffixe donné
     * (`null` = tout), triés par chemin, bornés [BORN_FICHIERS_DOSSIER]
     * — au-delà : troncature honnête (D3).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — dossier de base
     * absent, borne atteinte, fin du catalogue : TROIS issues distinctes
     * du parcours borné ; LoopWithTooManyJumpStatements — les continue du
     * dossier illisible, du non-fichier et du suffixe non porté sont le
     * patron même de la marche bornée (précédent ScanProjet.parcourir).
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun fichiersSous(
        res: File,
        prefixe: String,
        suffixe: String?,
    ): List<File> {
        val dossier = File(res, prefixe)
        val resultat = mutableListOf<File>()
        if (!dossier.isDirectory) return resultat
        val dossiers = listOf(dossier)
        // Dossiers qualifiés (`layout-land`, `values-night`) : frères
        // du dossier de base portant le MÊME préfixe.
        val freres =
            res.listFiles()?.filter { it.name.length > prefixe.length && it.name.startsWith("$prefixe-") }
                ?: emptyList()
        for (repertoire in (dossiers + freres).sortedBy { it.path }) {
            val enfants = repertoire.listFiles() ?: continue
            for (enfant in enfants.sortedBy { it.name }) {
                if (resultat.size >= BORN_FICHIERS_DOSSIER) return resultat
                if (!enfant.isFile) continue
                if (suffixe != null && !enfant.name.endsWith(suffixe)) continue
                resultat.add(enfant)
            }
        }
        return resultat
    }

    /**
     * Lecture DOM d'un XML — `null` si illisible (le rejet du fichier
     * EST le contrat D3 ; DOCTYPE refusé : une ressource locale n'en
     * déclare jamais, une entrée externe est un rejet sain).
     */
    private fun lire(fichier: File): Document? =
        try {
            fabrique.newDocumentBuilder().parse(fichier)
        } catch (t: Throwable) {
            // Illisible : absent (partiel honnête D3) — l'exception est
            // le signal, pas une perte.
            null
        }

    /** Fabrique DOM durcie (DOCTYPE refusé, espaces de noms neutres). */
    private val fabrique: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            essayerFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            essayerFeature("http://xml.org/sax/features/external-general-entities", false)
            essayerFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    /** Applique une fonctionnalité de durcissement si le parseur la connaît. */
    private fun DocumentBuilderFactory.essayerFeature(
        nom: String,
        valeur: Boolean,
    ) {
        try {
            setFeature(nom, valeur)
        } catch (t: Throwable) {
            // Parseur sans cette fonctionnalité : durcissement best
            // effort, pas un échec (les fichiers locaux dominent).
        }
    }

    /** Composants `android.*` usuels des layouts. */
    private val VUES_CONNUES: Map<String, String> =
        mapOf(
            "View" to "android.view.View",
            "ViewGroup" to "android.view.ViewGroup",
            "SurfaceView" to "android.view.SurfaceView",
            "TextureView" to "android.view.TextureView",
            "ViewStub" to "android.view.ViewStub",
            "TextView" to "android.widget.TextView",
            "Button" to "android.widget.Button",
            "ImageButton" to "android.widget.ImageButton",
            "EditText" to "android.widget.EditText",
            "AutoCompleteTextView" to "android.widget.AutoCompleteTextView",
            "CheckBox" to "android.widget.CheckBox",
            "CheckedTextView" to "android.widget.CheckedTextView",
            "RadioButton" to "android.widget.RadioButton",
            "RadioGroup" to "android.widget.RadioGroup",
            "Switch" to "android.widget.Switch",
            "ToggleButton" to "android.widget.ToggleButton",
            "ImageView" to "android.widget.ImageView",
            "Spinner" to "android.widget.Spinner",
            "ProgressBar" to "android.widget.ProgressBar",
            "SeekBar" to "android.widget.SeekBar",
            "RatingBar" to "android.widget.RatingBar",
            "Toolbar" to "android.widget.Toolbar",
            "ListView" to "android.widget.ListView",
            "GridView" to "android.widget.GridView",
            "ScrollView" to "android.widget.ScrollView",
            "HorizontalScrollView" to "android.widget.HorizontalScrollView",
            "WebView" to "android.webkit.WebView",
            "VideoView" to "android.widget.VideoView",
            "Chronometer" to "android.widget.Chronometer",
            "ViewFlipper" to "android.widget.ViewFlipper",
            "TextClock" to "android.widget.TextClock",
            "CalendarView" to "android.widget.CalendarView",
            "DatePicker" to "android.widget.DatePicker",
            "TimePicker" to "android.widget.TimePicker",
            "NumberPicker" to "android.widget.NumberPicker",
            "SearchView" to "android.widget.SearchView",
        )

    private const val BORN_FICHIERS_DOSSIER: Int = 400
}
