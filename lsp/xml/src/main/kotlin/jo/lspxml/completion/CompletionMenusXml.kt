package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre MENU (XE5, LSP_XML.md §4.3 — « menus : mêmes
 * moteurs, catalogues distincts ») — hiérarchie `menu` → `item`/
 * `group` → sous-menu `menu` dans un `item` ; attributs PAR BALISE
 * (`id`, `title`, `icon`, `showAsAction`…, préfixe `app:` pour
 * AppCompat) et énumérations (`showAsAction`, `menuCategory`,
 * `checkableBehavior`, booléens).
 *
 * Catalogue FIXE par la documentation (motif XE2/XE4 : `tools:`,
 * entrées nommées) — les attributs de menus ne vivent dans aucun
 * styleable de vues. Conservateur (X4) : balise inconnue du
 * contributeur = silence.
 */
internal object CompletionMenusXml {
    /** Balises d'un menu (nom → détail) — `menu` est LA racine. */
    private val BALISES: Map<String, String> =
        mapOf(
            "menu" to "racine du menu d'options",
            "item" to "élément de menu (id, title, icon)",
            "group" to "groupe d'items (checkableBehavior)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "menu" to listOf("item", "group"),
            "group" to listOf("item"),
            "item" to listOf("menu"),
        )

    /** Attributs PAR BALISE (nom complet → détail court). */
    private val ATTRIBUTS: Map<String, List<Pair<String, String>>> =
        mapOf(
            "item" to listOf(
                "android:id" to "identifiant (@+id/…)",
                "android:icon" to "icône (@drawable/…)",
                "android:title" to "libellé affiché",
                "android:titleCondensed" to "libellé condensé",
                "android:showAsAction" to "affichage en barre d'action",
                "android:orderInCategory" to "ordre dans la catégorie",
                "android:menuCategory" to "catégorie (container, system…)",
                "android:visible" to "visibilité",
                "android:enabled" to "activé",
                "android:checkable" to "cochable",
                "android:checked" to "coché",
                "android:alphabeticShortcut" to "raccourci clavier (lettre)",
                "android:numericShortcut" to "raccourci pavé (chiffre)",
                "android:actionViewClass" to "vue d'action (FQN)",
                "android:actionProviderClass" to "fournisseur d'action (FQN)",
                "android:actionLayout" to "layout de vue d'action",
                "app:showAsAction" to "affichage AppCompat en barre d'action",
                "app:actionViewClass" to "vue d'action AppCompat (FQN)",
                "app:actionProviderClass" to "fournisseur AppCompat (FQN)",
                "app:actionLayout" to "layout de vue d'action AppCompat",
            ),
            "group" to listOf(
                "android:id" to "identifiant du groupe",
                "android:menuCategory" to "catégorie (container, system…)",
                "android:orderInCategory" to "ordre du groupe",
                "android:visible" to "visibilité du groupe",
                "android:enabled" to "activation du groupe",
                "android:checkableBehavior" to "cohésion : none, all, single",
            ),
        )

    /** Attributs d'abord (le candidat probable en tête de palette). */
    private val ATTRIBUTS_FREQUENTS: List<String> =
        listOf(
            "android:id", "android:title", "android:icon",
            "android:showAsAction", "app:showAsAction",
            "android:orderInCategory",
        )

    /** FANIONS combinables (`|`) — showAsAction est un FLAG, pas un enum. */
    private val FANIONS: Map<String, List<String>> =
        mapOf(
            "showAsAction" to listOf("never", "ifRoom", "always", "withText", "collapseActionView"),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "menuCategory" to listOf("container", "system", "secondary", "alternative"),
            "checkableBehavior" to listOf("none", "all", "single"),
            "visible" to listOf("true", "false"),
            "enabled" to listOf("true", "false"),
            "checkable" to listOf("true", "false"),
            "checked" to listOf("true", "false"),
        )

    /**
     * Complétion du NOM DE BALISE — `menu` en racine, ENFANTS du
     * parent connu (X4 : parent inconnu = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> listOf("menu")

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
     * Complétion du NOM D'ATTRIBUT — PAR BALISE (la racine `menu` ne
     * porte que ses déclarations xmlns:, d'où la liste vide), déclarations
     * xmlns: en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, balise inconnue,
     * racine sans attributs) — chacun porte sa décision de silence X4.
     */
    @Suppress("ReturnCount")
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        if (CompletionXml.frappeDeDeclarationXmlns(prefixe)) {
            return CompletionXml.attributsXmlns(prefixe)
        }
        val element = contexte.element ?: return emptyList()
        val candidats = ATTRIBUTS[element.nom] ?: return emptyList()
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in candidats) {
            if (CompletionXml.correspondancePrefixe(prefixe, nom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = detail,
                        texteTri =
                            ClassementXml.cleTri(
                                ATTRIBUTS_FREQUENTS.indexOf(nom).takeIf { it >= 0 }
                                    ?: ClassementXml.rangAttribut(nom),
                                nom,
                            ),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion de VALEUR — fanions `showAsAction` (combinables par
     * `|`) et énumérations des attributs connus ; le contributeur
     * VALUES général apporte les références `@…` (icônes, titres) et
     * les énumérations du catalogue : la SESSION fusionne.
     */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> {
        val local = contexte.attribut.substringAfter(':')
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in FANIONS[local] ?: emptyList()) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.VALEUR_FANION,
                        detail = "flag (combinables par |)",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                    ),
                )
            }
        }
        for (nom in ENUMERATIONS[local] ?: emptyList()) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.VALEUR_ENUM,
                        detail = "enum",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                    ),
                )
            }
        }
        return propositions
    }
}
