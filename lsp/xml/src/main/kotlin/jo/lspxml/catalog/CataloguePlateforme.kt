package jo.lspxml.catalog

import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement
import java.io.File

/**
 * Catalogue de la PLATEFORME Android (XE2, décision X3 : « connaissance
 * Android = données, pas code ») — dérivé des données de la plateforme
 * INSTALLÉE du projet (`platforms/android-XX/data`), versionné par
 * plateforme (risque §7 : JAMAIS par constante) :
 *
 * - `data/widgets.txt` : classes de vues (FQCN + chaîne de
 *   super-classes) — la complétion de BALISES ;
 * - `data/res/values/attrs.xml` : `declare-styleable` (attributs par
 *   famille) + `attr` de premier niveau (formats, énumérations,
 *   fanions) — la complétion d'ATTRIBUTS (XE2) et de VALEURS (XE3).
 *
 * Le parsing se fait par NOTRE parseur tolérant (X1 : le DOM neutre
 * sert aussi aux données de plateforme — un attrs.xml un peu exotique
 * sous-rapporte, il ne casse JAMAIS le chargement).
 *
 * Chargement LAZY, UNE fois par plateforme (empreinte = le chemin du
 * dossier — le contenu d'une plateforme installée ne change pas sous
 * nos pieds) ; budget cadrage §6 : < 300 ms.
 */
public class CataloguePlateforme internal constructor(
    /** Balises de vues (widgets.txt) — simple nom → entrée. */
    internal val balises: Map<String, BaliseCatalogue>,
    /** Styleables (attrs.xml) — nom → noms d'attributs admissibles. */
    internal val styleables: Map<String, StyleableCatalogue>,
    /** Registre GLOBAL des attributs — nom → formats/énumérations. */
    internal val attributs: Map<String, AttrCatalogue>,
) {
    /**
     * Une balise de vue du catalogue (widgets.txt, préfixe `W`).
     *
     * @property nom nom SIMPLE (`TextView` — le tag habituel).
     * @property fqcn nom pleinement qualifié (`android.widget.TextView`).
     * @property superieurs chaîne des super-classes (SIMPLES, ordre du
     *           fichier : parent d'abord — `TextView` → `View` → `Object`).
     */
    public class BaliseCatalogue(
        public val nom: String,
        public val fqcn: String,
        public val superieurs: List<String>,
    )

    /**
     * Un `declare-styleable` (famille d'attributs).
     *
     * @property nom nom du styleable (`TextView`, `ViewGroup_Layout`).
     * @property attributs noms d'attributs admissibles (`text`, `id`).
     */
    public class StyleableCatalogue(
        public val nom: String,
        public val attributs: List<String>,
    )

    /**
     * Un attribut du registre global (déclaration la plus riche vue —
     * premier niveau OU première déclaration porteuse de format).
     *
     * @property nom nom SANS préfixe (`text`, `layout_width`).
     * @property formats formats déclarés (`string`, `dimension`,
     *           `reference|color` → ensemble éclaté).
     * @property enumerations valeurs d'enum (`match_parent`…).
     * @property fanions valeurs de fanion combinables.
     */
    public class AttrCatalogue(
        public val nom: String,
        public val formats: Set<String>,
        public val enumerations: List<String>,
        public val fanions: List<String>,
    )

    /**
     * Chaîne de super-classes (SIMPLES) d'une balise — la balise
     * elle-même en TÊTE ; `null` si inconnue du catalogue (le tag
     * pleinement qualifié retombe sur son nom SIMPLE).
     */
    internal fun chaineDe(nomDeBalise: String): List<String>? {
        val balise =
            balises[nomDeBalise]
                ?: balises[nomDeBalise.substringAfterLast('.')]
                ?: return null
        return listOf(balise.nom) + balise.superieurs
    }

    /** Attributs d'un styleable (vide si inconnu — sous-rapport honnête). */
    internal fun attributsDeStyleable(nom: String): List<String> =
        styleables[nom]?.attributs ?: emptyList()

    public companion object {
        /**
         * Charge le catalogue d'une plateforme — les fichiers manquants
         * sont TOLÉRÉS un par un (catalogue partiel honnête, X4) ;
         * `null` si le dossier n'existe pas ou ne porte RIEN.
         *
         * @param dossier dossier `platforms/android-*` (le PARENT de
         *        `android.jar`).
         */
        public fun charger(dossier: File): CataloguePlateforme? {
            if (!dossier.isDirectory) return null
            val balises = chargerWidgets(File(dossier, "data/widgets.txt"))
            val documentAttrs = analyserAttrs(File(dossier, "data/res/values/attrs.xml"))
            val styleables = mutableMapOf<String, StyleableCatalogue>()
            val attributs = mutableMapOf<String, AttrCatalogue>()
            if (documentAttrs != null) {
                for (ressources in documentAttrs.racines) {
                    lireRessources(ressources, styleables, attributs)
                }
            }
            // Rien de lisible : SANS catalogue (sous-rapport honnête, X4).
            return if (balises.isEmpty() && styleables.isEmpty()) {
                null
            } else {
                CataloguePlateforme(balises, styleables, attributs)
            }
        }

        /** widgets.txt : lignes `Wfqcn super1 super2 …` (P = ignoré). */
        private fun chargerWidgets(fichier: File): Map<String, BaliseCatalogue> {
            if (!fichier.isFile) return emptyMap()
            val balises = LinkedHashMap<String, BaliseCatalogue>()
            for (ligne in fichier.readLines().filter { it.startsWith("W") }) {
                val fqcn = ligne.removePrefix("W").substringBefore(' ')
                if (fqcn.isNotEmpty()) {
                    val superieurs =
                        ligne
                            .removePrefix("W")
                            .split(' ')
                            .filter { it.isNotEmpty() }
                            .drop(1)
                            .map { it.substringAfterLast('.') }
                    balises[fqcn.substringAfterLast('.')] =
                        BaliseCatalogue(fqcn.substringAfterLast('.'), fqcn, superieurs)
                }
            }
            return balises
        }

        /** attrs.xml par le parseur tolérant (un fichier cassé = null). */
        private fun analyserAttrs(fichier: File): DocumentXml? =
            if (fichier.isFile) {
                jo.lspxml.parse.ParseurXml().analyser(fichier.readText())
            } else {
                null
            }

        /**
         * Lit `<resources>` : styleables + attrs de premier niveau.
         */
        private fun lireRessources(
            ressources: XmlElement,
            styleables: MutableMap<String, StyleableCatalogue>,
            attributs: MutableMap<String, AttrCatalogue>,
        ) {
            for (enfant in ressources.enfants) {
                when (enfant.nom) {
                    "declare-styleable" -> {
                        val nomStyleable = enfant.attribut("name")?.valeur ?: continue
                        val nomsAttrs =
                            enfant.enfants
                                .filter { it.nom == "attr" }
                                .mapNotNull { it.attribut("name")?.valeur }
                        styleables.putIfAbsent(nomStyleable, StyleableCatalogue(nomStyleable, nomsAttrs))
                        // Les attributs PORTÉS (avec enfants enum/flag ou
                        // format) enrichissent le registre global.
                        enfant.enfants.filter { it.nom == "attr" }.forEach { lireAttr(it, attributs) }
                    }

                    "attr" -> lireAttr(enfant, attributs)
                }
            }
        }

        /** Lit UN `<attr>` (format + énumérations/fanions enfants). */
        private fun lireAttr(
            attr: XmlElement,
            attributs: MutableMap<String, AttrCatalogue>,
        ) {
            val nom = attr.attribut("name")?.valeur ?: return
            val formats =
                attr.attribut("format")?.valeur
                    ?.split('|')
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet()
            val enumerations =
                attr.enfants
                    .filter { it.nom == "enum" }
                    .mapNotNull { it.attribut("name")?.valeur }
            val fanions =
                attr.enfants
                    .filter { it.nom == "flag" }
                    .mapNotNull { it.attribut("name")?.valeur }
            val existant = attributs[nom]
            attributs[nom] =
                if (existant == null) {
                    AttrCatalogue(nom, formats, enumerations, fanions)
                } else {
                    // Fusion : le premier porteur garde ses données, le
                    // plus RICHE gagne champ par champ.
                    AttrCatalogue(
                        nom,
                        if (formats.isEmpty()) existant.formats else formats,
                        if (enumerations.isEmpty()) existant.enumerations else enumerations,
                        if (fanions.isEmpty()) existant.fanions else fanions,
                    )
                }
        }
    }
}
