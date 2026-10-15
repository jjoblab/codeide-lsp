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

    /**
     * `res/color*` — listes d'états de couleur (XP12) : `selector`
     * → `item android:color` — structure de DRAWABLE (selector/item
     * partagés), périmètre colorisé.
     */
    COULEUR,

    /** `res/anim*` — animations de vue tween (XP12) : `set`, `alpha`,
     * `scale`, `translate`, `rotate`. */
    ANIMATION,

    /** `res/animator*` — animateurs de propriété (XP12) : `set`,
     * `objectAnimator`, `animator`, `propertyValuesHolder`. */
    ANIMATEUR,

    /** `res/xml*` — fichiers de configuration d'application (XP12) :
     * `appwidget-provider`, `shortcuts`, `paths`, sécurité réseau… */
    XML_APP,

    /** `res/navigation*` — graphes de navigation (XP12) : `navigation`
     * → destinations `fragment`/`activity`/`dialog`, `action`… */
    NAVIGATION,

    /** `res/transition*` — transitions du framework (XP12) :
     * `transitionSet`, `fade`, `slide`, `changeBounds`… */
    TRANSITION,

    /** `res/font*` — familles de polices (XP12) : `font-family`
     * → `font` (locale ou téléchargeable). */
    POLICE,

    /** Chemin inconnu — règles GÉNÉRIQUES seules (X4 conservatoire). */
    AUTRE, ;

    public companion object {
        /**
         * Préfixes de chemin `res/…` → genre (ORDRE SIGNIFICATIF —
         * le premier préfixe contenu dans le chemin gagne) :
         * `/res/animator` AVANT `/res/anim` (le préfixe de animator
         * contient anim — un inverser ferait deux genres pour un dir).
         */
        private val GENRES_RESSOURCES: List<Pair<String, GenreDocument>> =
            listOf(
                "/res/layout" to LAYOUT,
                "/res/values" to VALEURS,
                "/res/drawable" to DRAWABLE,
                "/res/menu" to MENU,
                "/res/color" to COULEUR,
                "/res/animator" to ANIMATEUR,
                "/res/anim" to ANIMATION,
                "/res/xml" to XML_APP,
                "/res/navigation" to NAVIGATION,
                "/res/transition" to TRANSITION,
                "/res/font" to POLICE,
            )

        /**
         * Genre d'une URI de document — le CHEMIN décide (sensible à la
         * casse par convention de dépôt, `res/Layout` n'existe pas).
         *
         * v0.134.4 — XP13 : un manifest à la RACINE d'un conteneur
         * composite (`…/quelque.aar!/AndroidManifest.xml` — sans slash
         * de tête après `!/`) est aussi un MANIFEST (la portion après
         * `!/` peut être « AndroidManifest.xml » seul).
         *
         * v0.134.10 — XP12 : genres étendus `res/color*`, `res/anim*`,
         * `res/animator*`, `res/xml*`, `res/navigation*`,
         * `res/transition*`, `res/font*` (qualifiers compris —
         * `res/color-night`, `res/anim-v24`…). La portion après `!/`
         * d'un conteneur composite PERD son slash de tête
         * (`….aar!/res/xml/…` → « res/xml/… ») — on le restitue pour
         * que les préfixes « /res/… » s'appliquent aux entrées de
         * conteneurs (la couture XP13, JUSQU'ICI limitée au manifest
         * racine, vaut pour TOUS les genres).
         *
         * @param uri URI LSP (`file:///…/res/layout-land/main.xml`) ou
         *        chemin — seule la portion après le dernier `!/` est
         *        regardée (conteneurs composites).
         */
        public fun depuisUri(uri: String): GenreDocument {
            val chemin = uri.substringAfterLast("!/", uri)
            val remonte = if (chemin.startsWith("/") || chemin.startsWith("file:")) chemin else "/$chemin"
            val normalise = remonte.replace('\\', '/')
            return GENRES_RESSOURCES
                .firstOrNull { paire -> normalise.contains(paire.first) }
                ?.second
                ?: manifestOuAutre(normalise)
        }

        /** Manifest (son nom décide — un manifest ne vit pas sous res/) sinon AUTRE. */
        private fun manifestOuAutre(normalise: String): GenreDocument =
            if (normalise.endsWith("/AndroidManifest.xml") || normalise == "AndroidManifest.xml") {
                MANIFEST
            } else {
                AUTRE
            }
    }
}
