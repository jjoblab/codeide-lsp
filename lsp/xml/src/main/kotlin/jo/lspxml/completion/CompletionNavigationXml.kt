package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre NAVIGATION (XP12, plan de stabilisation LSP
 * §3.3 S2 — « genres étendus … res/navigation ») : graphes de
 * Navigation Component — racine `navigation`, destinations
 * `fragment`/`activity`/`dialog`, graphes imbriqués, `include` ;
 * `action` (navigation déclarative) et `argument`/`deepLink` sous
 * les destinations ; attributs `android:` (id, name, label,
 * startDestination) et `app:` (destination, argType, popUpTo…).
 *
 * Catalogue FIXE par la documentation (motif XE5). Conservateur
 * (X4) : parent inconnu = silence ; les attributs de navigation
 * vivent dans les styleables des libs (androidx.navigation) qui ne
 * sont PAS indexées sans l'index AAR (S3) — le catalogue fixe est le
 * seul honnête à ce palier.
 */
internal object CompletionNavigationXml {
    /** Balises d'un graphe de navigation (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "navigation" to "racine ou graphe imbriqué (startDestination)",
            "fragment" to "destination fragment (android:name = classe)",
            "activity" to "destination activité (android:name)",
            "dialog" to "destination dialogue (android:name)",
            "include" to "graphe externe fusionné (app:graph)",
            "action" to "transition déclarative (app:destination)",
            "argument" to "argument de destination ou d'action (name, argType)",
            "deepLink" to "lien profond vers la destination (app:uri)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "navigation" to listOf("fragment", "activity", "dialog", "navigation", "include"),
            "fragment" to listOf("action", "argument", "deepLink"),
            "activity" to listOf("argument", "deepLink"),
            "dialog" to listOf("action", "argument", "deepLink"),
            "action" to listOf("argument"),
        )

    /** Attributs PAR BALISE (nom complet → détail court). */
    private val ATTRIBUTS: Map<String, List<Pair<String, String>>> =
        mapOf(
            "navigation" to listOf(
                "android:id" to "identifiant du graphe (@+id/…)",
                "android:startDestination" to "destination de départ (@id/…)",
                "android:label" to "libellé du graphe",
            ),
            "fragment" to listOf(
                "android:id" to "identifiant de la destination (@+id/…)",
                "android:name" to "classe du fragment (FQN)",
                "android:label" to "libellé affiché",
                "tools:layout" to "aperçu de layout (éditeur)",
            ),
            "activity" to listOf(
                "android:id" to "identifiant de la destination (@+id/…)",
                "android:name" to "classe de l'activité (FQN)",
                "android:label" to "libellé affiché",
            ),
            "dialog" to listOf(
                "android:id" to "identifiant de la destination (@+id/…)",
                "android:name" to "classe du dialogue (FQN)",
                "android:label" to "libellé affiché",
            ),
            "include" to listOf(
                "app:graph" to "graphe fusionné (@navigation/…)",
            ),
            "action" to listOf(
                "android:id" to "identifiant de l'action (@+id/…)",
                "app:destination" to "destination visée (@id/…)",
                "app:popUpTo" to "destination de remontée de pile (@id/…)",
                "app:popUpToInclusive" to "popUpTo aussi retiré",
                "app:launchSingleTop" to "pas de doublon au sommet de pile",
                "app:popEnterAnim" to "animation d'entrée à la remontée (@anim/…)",
                "app:popExitAnim" to "animation de sortie à la remontée (@anim/…)",
                "app:enterAnim" to "animation d'entrée (@anim/…)",
                "app:exitAnim" to "animation de sortie (@anim/…)",
            ),
            "argument" to listOf(
                "android:name" to "nom de l'argument",
                "app:argType" to "type de l'argument (integer, string, FQN…)",
                "app:defaultValue" to "valeur par défaut",
                "app:nullable" to "argument nullable",
            ),
            "deepLink" to listOf(
                "android:id" to "identifiant du lien (@+id/…)",
                "app:uri" to "schéma d'URI du lien (https://…)",
                "app:autoVerify" to "vérification App Links",
            ),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "popUpToInclusive" to listOf("true", "false"),
            "launchSingleTop" to listOf("true", "false"),
            "autoVerify" to listOf("true", "false"),
            "nullable" to listOf("true", "false"),
        )

    /**
     * Complétion du NOM DE BALISE — `navigation` en racine, enfants
     * du parent connu (X4 : autre parent = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> listOf("navigation")

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
     * Complétion du NOM D'ATTRIBUT — PAR BALISE, déclarations xmlns:
     * en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, balise inconnue) —
     * chacun porte sa décision de silence X4.
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
                        texteTri = ClassementXml.cleTri(ClassementXml.rangAttribut(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /** Complétion de VALEUR — énumérations de navigation connues. */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> {
        val valeurs = ENUMERATIONS[contexte.attribut.substringAfter(':')] ?: return emptyList()
        val prefixe = contexte.prefixeTape
        return valeurs
            .filter { prefixe.isEmpty() || it.startsWith(prefixe) }
            .map { nom ->
                PropositionCompletionXml(
                    libelle = nom,
                    genre = GenreCompletionXml.VALEUR_ENUM,
                    detail = "enum",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                )
            }
    }
}
