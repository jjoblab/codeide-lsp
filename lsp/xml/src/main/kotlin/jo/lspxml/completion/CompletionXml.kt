package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.context.Contexte
import jo.lspxml.parse.XmlElement

/**
 * Complétion par CONTRIBUTEURS (XE2, LSP_XML.md §4.1 « completion », §4.3
 * — motif projet de référence : la connaissance Android vit dans le catalogue
 * plateforme, pas dans le scanner) — le scanner de contexte a déjà
 * tranché LA zone ; ici on fusionne les candidats et on CLASSE
 * (contexte + fréquence SDK + préfixe, risque §7 « complétion bruyante »
 * — le scoreur vit dans [ClassementXml]).
 *
 * Conservateur (X4) : ce que le catalogue ne prouve pas n'apparaît pas —
 * jamais de candidat inventé.
 */
public object CompletionXml {
    /**
     * Styleables TOUJOURS admissibles sur un élément de layout : les
     * paramètres de mise en page du gonfleur (ViewGroup_Layout =
     * width/height, ViewGroup_MarginLayout = marges).
     */
    private val STYLEABLES_LAYOUT_UNIVERSAUX: List<String> =
        listOf("ViewGroup_Layout", "ViewGroup_MarginLayout")

    /**
     * Balises SPÉCIALES d'un layout (hors classes de vues — le gonfleur
     * les connaît, le catalogue ne les porte pas) — EXPOSÉES aux règles
     * (garde « balise inconnue » X4 : jamais signalées).
     */
    internal val BALISES_SPECIALES_NOMS: Set<String> =
        setOf(
            "merge", "include", "fragment", "requestFocus",
        )

    /**
     * Attributs `tools:` usuels (espaces de noms outils — fixés par la
     * documentation, pas par le catalogue).
     */
    private val ATTRIBUTS_OUTILS: List<String> =
        listOf(
            "tools:ignore", "tools:targetApi", "tools:visibility",
            "tools:context", "tools:showIn", "tools:layout",
            "tools:replace", "tools:keep", "tools:remove",
        )

    /** Longueur du mot-clé `xmlns` (garde de frappe symétrique). */
    internal const val LONGUEUR_XMLNS: Int = 5

    /**
     * Le préfixe tapé est-il une frappe de DÉCLARATION xmlns (x…, xmln,
     * xmlns, xmlns:…) ? — garde SYMÉTRIQUE (le préfixe peut être PLUS
     * COURT que « xmlns »), partagée avec drawables/menus (XE5).
     */
    internal fun frappeDeDeclarationXmlns(prefixe: String): Boolean =
        prefixe.isNotEmpty() &&
            (prefixe.length <= LONGUEUR_XMLNS && "xmlns".startsWith(prefixe) ||
                prefixe.startsWith("xmlns"))

    /** Détails des balises spéciales (BALISES_SPECIALES_NOMS). */
    private val DETAILS_SPECIALES: Map<String, String> =
        mapOf(
            "merge" to "racine de fusion (le gonfleur insère les enfants directement)",
            "include" to "inclusion d'un autre layout (@layout/…)",
            "fragment" to "fragment android.app (class/name)",
            "requestFocus" to "prise de focus initiale de l'élément parent",
        )

    /**
     * Complétion du NOM DE BALISE — toutes les vues du catalogue
     * (XE2 : « tous les View/ViewGroup du SDK + balises spéciales si
     * le parent l'admet » — le filtrage par parent suit les retours).
     *
     * @param contexte zone (préfixe tapé, parent).
     * @param catalogue catalogue plateforme (`null` : liste VIDE — la
     *        complétion balise SANS catalogue serait du bruit inventé).
     */
    public fun balises(
        contexte: Contexte.NomDeBalise,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        if (catalogue == null) return emptyList()
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, balise) in catalogue.balises) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.BALISE,
                        detail = balise.fqcn,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        for (nom in BALISES_SPECIALES_NOMS) {
            val detail = DETAILS_SPECIALES[nom] ?: ""
            val visible =
                (prefixe.isEmpty() || nom.startsWith(prefixe)) &&
                    !catalogue.balises.containsKey(nom)
            if (visible) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.BALISE,
                        detail = detail,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT — styleable de l'élément (chaîne de
     * super-classes), paramètres de mise en page du PARENT (sémantique
     * Android : `layout_*` appartient au parent), `tools:` fixes et
     * déclarations `xmlns:` en frappe de préfixe.
     *
     * @param contexte zone (préfixe tapé, élément porteur).
     * @param catalogue catalogue plateforme (`null` : tools/xmlns seuls).
     */
    public fun attributs(
        contexte: Contexte.NomAttribut,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        // Préfixe xmlns en frappe (x…, xmln, xmlns, xmlns:…) : proposer
        // les déclarations utiles — la garde est SYMÉTRIQUE (le préfixe
        // peut être PLUS COURT que « xmlns »), VIDE = attributs normaux.
        if (frappeDeDeclarationXmlns(prefixe)) {
            return attributsXmlns(prefixe)
        }
        val candidats = LinkedHashSet<String>()

        // Attributs de l'ÉLÉMENT (styleables de la chaîne de classes).
        val element = contexte.element
        if (element != null) {
            for (classe in chaineClasses(element, catalogue)) {
                catalogue?.attributsDeStyleable(classe)?.forEach { candidats.add("android:$it") }
            }
            // Paramètres de mise en page : styleables _Layout du PARENT
            // (le parent consomme layout_*), universels en racine.
            val parent = element.parent
            val chaineParent =
                if (parent != null) chaineClasses(parent, catalogue) else listOf("ViewGroup")
            for (styleable in styleablesLayout(chaineParent)) {
                catalogue?.attributsDeStyleable(styleable)?.forEach { candidats.add("android:$it") }
            }
        }
        // tools: toujours proposables (indépendants du catalogue).
        candidats.addAll(ATTRIBUTS_OUTILS)

        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in candidats) {
            if (correspondancePrefixe(prefixe, nom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = detailAttribut(nom, catalogue),
                        texteTri = ClassementXml.cleTri(ClassementXml.rangAttribut(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    // ── Filtrage par préfixe ───────────────────────────────────────

    /**
     * Le préfixe TAPÉ (sans deux-points, ou avec) correspond-il au nom
     * COMPLET d'attribut ? `te` → `android:text` (nom local) ;
     * `android:` → tout android: ; `andr` → `android:…` (début du
     * préfixe d'espace de noms).
     */
    internal fun correspondancePrefixe(prefixe: String, nom: String): Boolean {
        if (prefixe.isEmpty()) return true
        return if (prefixe.contains(':')) {
            nom.startsWith(prefixe)
        } else {
            nom.startsWith(prefixe) || nom.substringAfter(':').startsWith(prefixe)
        }
    }

    /** Chaîne de classes (SIMPLES) d'un élément — [View] en repli. */
    private fun chaineClasses(
        element: XmlElement,
        catalogue: CataloguePlateforme?,
    ): List<String> =
        if (catalogue != null) {
            catalogue.chaineDe(element.nom) ?: listOf("View")
        } else {
            listOf("View")
        }

    /** Styleables de mise en page de la chaîne du PARENT. */
    private fun styleablesLayout(chaineParent: List<String>): List<String> {
        val styleables = mutableListOf<String>()
        styleables.addAll(STYLEABLES_LAYOUT_UNIVERSAUX)
        for (classe in chaineParent) {
            styleables.add("${classe}_Layout")
        }
        return styleables
    }

    /** Détail d'un attribut (formats + énumérations du registre). */
    private fun detailAttribut(
        nom: String,
        catalogue: CataloguePlateforme?,
    ): String? {
        val attr = catalogue?.attributs?.get(nom.substringAfter(':')) ?: return null
        val parties = mutableListOf<String>()
        if (attr.formats.isNotEmpty()) parties.add("format: ${attr.formats.joinToString("|")}")
        if (attr.enumerations.isNotEmpty()) {
            parties.add("enum: ${attr.enumerations.take(ClassementXml.APERCU_VALEURS).joinToString("|")}")
        }
        if (attr.fanions.isNotEmpty()) {
            parties.add("flag: ${attr.fanions.take(ClassementXml.APERCU_VALEURS).joinToString("|")}")
        }
        return if (parties.isEmpty()) null else parties.joinToString(" — ")
    }

    /**
     * Déclarations xmlns: utiles (frappe du préfixe xmlns) — PARTAGÉ
     * avec les contributeurs drawables/menus (XE5 : mêmes racines
     * xmlns:android/tools/app).
     */
    internal fun attributsXmlns(prefixe: String): List<PropositionCompletionXml> {
        val propositions = mutableListOf<PropositionCompletionXml>()
        val candidates =
            listOf(
                "xmlns:android" to "http://schemas.android.com/apk/res/android",
                "xmlns:tools" to "http://schemas.android.com/tools",
                "xmlns:app" to "http://schemas.android.com/apk/res-auto",
            )
        for ((nom, uri) in candidates) {
            if (prefixe.length <= "xmlns".length || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = uri,
                        texteTri = ClassementXml.cleTri(0, nom),
                    ),
                )
            }
        }
        return propositions
    }
}
