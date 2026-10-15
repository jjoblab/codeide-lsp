package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre COULEUR (XP12, plan de stabilisation LSP §3.3
 * S2 — « réutiliser le contributeur drawables ») : une liste d'états
 * de couleur (`res/color/nom.xml`) est un `selector` dont les `item`
 * portent `android:color` (et `android:alpha`, API 26+) au lieu d'un
 * drawable enfant — la STRUCTURE est celle du selector drawable, seul
 * l'ATTRIBUT du calque diffère.
 *
 * Réutilisation (motif XE5) : les balises racine/enfant et les états
 * d'`item` (state_pressed…) viennent de [CompletionDrawablesXml] —
 * la donnée ne vit qu'une fois ; ce contributeur n'ajoute QUE la
 * colorisation de l'item. X4 : racine autre que `selector` = silence
 * (une color list n'admet pas de `shape` ni de `vector` — proposer
 * des drawables ici serait faux).
 */
internal object CompletionCouleursXml {
    /** Balises d'une liste d'états de couleur (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "selector" to "liste d'états de couleur (item android:color)",
            "item" to "couleur d'un état (android:color + state_*)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "selector" to listOf("item"),
        )

    /** Colorisation d'un `item` — CE qui distingue du selector drawable. */
    private val ATTRIBUTS_ITEM_COULEUR: List<Pair<String, String>> =
        listOf(
            "android:color" to "couleur de cet état (#rrggbb ou @color/…)",
            "android:alpha" to "opacité de l'état (0–1, API 26)",
        )

    /**
     * Complétion du NOM DE BALISE — `selector` en racine, `item` sous
     * le selector (X4 : autre parent = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> listOf("selector")

                ENFANTS.containsKey(parent.nom) -> ENFANTS.getValue(parent.nom)

                else -> return emptyList()
            }
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in admissibles) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.BALISE,
                        detail = BALISES[nom],
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT — états du selector drawable
     * (partagés) PLUS la colorisation de l'item ; déclarations xmlns:
     * en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, hors item) —
     * chacun porte sa décision de silence X4.
     */
    @Suppress("ReturnCount")
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        if (CompletionXml.frappeDeDeclarationXmlns(prefixe)) {
            return CompletionXml.attributsXmlns(prefixe)
        }
        val element = contexte.element ?: return emptyList()
        if (element.nom != "item") return emptyList()
        // Les états du selector drawable SANS android:drawable (une
        // color state list porte android:color, jamais de drawable).
        val candidats =
            CompletionDrawablesXml.attributsItemSelector()
                .filter { paire -> paire.first != "android:drawable" } + ATTRIBUTS_ITEM_COULEUR
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in candidats) {
            if (CompletionXml.correspondancePrefixe(prefixe, nom)) {
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
        return propositions.sortedBy { it.texteTri }.distinctBy { it.libelle }
    }

    /**
     * Complétion de VALEUR — déléguée au contributeur drawables
     * (énumérations de ses attributs partagés) ; la VALEUR de
     * `android:color` elle-même voyage par la fusion session (le
     * contributeur général XE3 porte les références `@color/…`).
     */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> =
        CompletionDrawablesXml.valeurs(contexte)
}
