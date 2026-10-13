package jo.lspxml.rules

/**
 * Genre de document XML déduit de l'URI (XE1, LSP_XML.md §4.1 — « le
 * genre layout/values/manifest s'en déduit, X1 ») : les RÈGLES de
 * structure ne s'appliquent qu'à leur genre (un `res/values/strings.xml`
 * n'a pas de `layout_width` à exiger ; un layout n'a pas d'entrées
 * nommées) — la déduction par CHEMIN est la convention AGP (`src/main/
 * res/…`), tout chemin inconnu est [AUTRE] (règles génériques seules,
 * conservatoire X4).
 */
public enum class GenreDocument {
    /** `res/layout*` — hiérarchie de vues (`layout`, `layout-land`…). */
    LAYOUT,

    /** `res/values*` — entrées nommées (`string`, `color`, `dimen`…). */
    VALEURS,

    /** `AndroidManifest.xml` (XE4 — règles dédiées). */
    MANIFEST,

    /** `res/drawable*` — drawables XML (`shape`, `selector`, `vector`… — XE5). */
    DRAWABLE,

    /** `res/menu*` — menus d'options (`item`, `group` — XE5). */
    MENU,

    /** Chemin inconnu — règles GÉNÉRIQUES seules (X4 conservatoire). */
    AUTRE, ;

    public companion object {
        /**
         * Genre d'une URI de document — le CHEMIN décide (sensible à la
         * casse par convention de dépôt, `res/Layout` n'existe pas).
         *
         * @param uri URI LSP (`file:///…/res/layout-land/main.xml`) ou
         *        chemin — seule la portion après le dernier `!/` est
         *        regardée (conteneurs composites).
         */
        public fun depuisUri(uri: String): GenreDocument {
            val chemin = uri.substringAfterLast("!/", uri)
            val normalise = chemin.replace('\\', '/')
            return when {
                normalise.contains("/res/layout") -> LAYOUT

                normalise.contains("/res/values") -> VALEURS

                normalise.endsWith("/AndroidManifest.xml") -> MANIFEST

                normalise.contains("/res/drawable") -> DRAWABLE

                normalise.contains("/res/menu") -> MENU

                else -> AUTRE
            }
        }
    }
}
