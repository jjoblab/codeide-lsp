package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.context.Contexte
import jo.lspxml.resources.IndexRessources

/**
 * Complétion du genre VALUES (XE4, LSP_XML.md §4.3 — « manifest, res/
 * values, drawables : mêmes moteurs, catalogues distincts ») —
 * balises d'ENTRÉES nommées, attributs d'entrée (`name`, `parent`,
 * `translatable`, `format`) et noms de STYLES du projet pour
 * `parent=` (thèmes framework via public-final).
 *
 * Conservateur (X4) : sans index les styles du projet se taisent.
 */
internal object CompletionEntreesXml {
    /** Balises d'entrées nommées proposables dans un fichier values. */
    private val ENTREES: Map<String, String> =
        mapOf(
            "string" to "chaîne localisable",
            "plurals" to "chaîne au pluriel (item quantity)",
            "color" to "couleur (#RGB ou @référence)",
            "dimen" to "dimension (8dp, 16sp…)",
            "integer" to "entier",
            "bool" to "booléen",
            "array" to "tableau générique",
            "string-array" to "tableau de chaînes",
            "integer-array" to "tableau d'entiers",
            "style" to "style/thème (parent, item)",
            "drawable" to "drawable valeurs (couleur d'état)",
            "mipmap" to "mipmap valeurs",
            "font" to "famille de polices",
            "attr" to "attribut personnalisé (format)",
            "declare-styleable" to "famille d'attributs (attr enfants)",
        )

    /** Attributs d'entrée proposables (name est TOUJOURS en tête). */
    private val ATTRIBUTS: Map<String, String> =
        mapOf(
            "name" to "nom de la ressource (exigé)",
            "parent" to "style parent (style seul)",
            "translatable" to "string : traduisible",
            "format" to "attr : formats (reference|color…)",
        )

    /**
     * Complétion du NOM DE BALISE dans un values (entrées nommées).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in ENTREES) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ENTREE_VALEURS,
                        detail = detail,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        // Les items de style vivent SOUS un style : proposer `item`
        // quand le parent est un style.
        val parent = contexte.parent
        if (parent?.nom == "style" && (prefixe.isEmpty() || "item".startsWith(prefixe))) {
            propositions.add(
                PropositionCompletionXml(
                    libelle = "item",
                    genre = GenreCompletionXml.ENTREE_VALEURS,
                    detail = "entrée de style (name = attribut)",
                    texteTri = ClassementXml.cleTri(ClassementXml.rangBalise("item"), "item"),
                ),
            )
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT dans un values (name, parent…).
     */
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in ATTRIBUTS) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = detail,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangAttribut(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion de VALEUR dans un values — `parent="…"` d'un style :
     * styles du PROJET (index), styles des BIBLIOTHÈQUES (P3 — les
     * thèmes `Theme.Material3.*` apparaissent) et thèmes du FRAMEWORK
     * (`@android:style/…`, public-final).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les régimes distincts (hors parent, sans framework).
     */
    @Suppress("ReturnCount")
    internal fun valeurs(
        contexte: Contexte.Valeur,
        catalogue: CataloguePlateforme?,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        if (contexte.attribut != "parent") return emptyList()
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        // Styles du projet.
        val projet = index.nomsParType("style")
        for (nom in projet) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.STYLE,
                        detail = "style — projet",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE, nom),
                    ),
                )
            }
        }
        // Styles des bibliothèques (AAR indexées — P3, racine de XP1).
        for (nom in index.nomsLibParType("style")) {
            if (nom in projet.toSet()) continue
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.STYLE,
                        detail = "style — bibliothèque",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE, nom),
                    ),
                )
            }
        }
        // Thèmes du framework (préfixe @android:style/).
        val framework = catalogue?.ressourcesFramework?.get("style") ?: return propositions
        val apres = prefixe.removePrefix("@android:style/")
        for (nom in framework.sorted()) {
            if (apres.isEmpty() || nom.startsWith(apres)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "@android:style/$nom",
                        genre = GenreCompletionXml.STYLE,
                        detail = "thème — framework",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE_FW, nom),
                    ),
                )
            }
        }
        return propositions
    }
}
