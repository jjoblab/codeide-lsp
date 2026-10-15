package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre ANIMATION (XP12, plan de stabilisation LSP
 * §3.3 S2 — « genres étendus … res/anim* ») : animations de vue
 * TWEEN (v0.117+ `android.view.animation`) — racines `set`/`alpha`/
 * `scale`/`translate`/`rotate` (un élément isolé est une racine
 * légale), `set` imbriqués ; attributs PAR BALISE (fromX/toY de
 * chaque transformation) PLUS les attributs communs de timing
 * (duration, startOffset, fill*, repeat*) et les interpolateurs
 * du framework en valeur de `android:interpolator`.
 *
 * Catalogue FIXE par la documentation (motif XE2/XE4/XE5) — les
 * attributs tween ne vivent dans AUCUN styleable de vues.
 * Conservateur (X4) : parent inconnu = silence. Les animateurs de
 * PROPRIÉTÉ (`res/animator*`) voyagent dans [CompletionAnimateursXml]
 * — les deux familles ont des racines distinctes (alpha vs
 * objectAnimator) et ne se confondent PAS.
 */
internal object CompletionAnimationsXml {
    /** Balises d'une animation tween (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "set" to "groupe d'animations (ordre de déclaration)",
            "alpha" to "fondu (fromAlpha, toAlpha)",
            "scale" to "échelle (fromXScale, toYScale, pivot)",
            "translate" to "translation (fromXDelta, toYDelta)",
            "rotate" to "rotation (fromDegrees, pivot)",
        )

    /** Balises usuelles d'abord. */
    private val BALISES_FREQUENTES: List<String> =
        listOf("set", "alpha", "scale", "translate", "rotate")

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "set" to listOf("set", "alpha", "scale", "translate", "rotate"),
        )

    /** Attributs COMMUNS de timing (toutes les balises de tween). */
    private val ATTRIBUTS_COMMUNS: List<Pair<String, String>> =
        listOf(
            "android:duration" to "durée (ms)",
            "android:startOffset" to "décalage avant démarrage (ms)",
            "android:fillAfter" to "garder la transformation finale",
            "android:fillBefore" to "appliquer la transformation avant démarrage",
            "android:fillEnabled" to "activer fillBefore/fillAfter",
            "android:repeatCount" to "répétitions (-1 = infini)",
            "android:repeatMode" to "reprise : restart, reverse",
            "android:interpolator" to "interpolateur (@android:anim/…)",
            "android:zAdjustment" to "ordre Z pendant l'animation : normal, top, bottom",
        )

    /** Attributs PAR BALISE (spécifiques — communs ajoutés au rendu). */
    private val ATTRIBUTS_SPECIFIQUES: Map<String, List<Pair<String, String>>> =
        mapOf(
            "set" to listOf(
                "android:shareInterpolator" to "interpolateur partagé aux enfants",
            ),
            "alpha" to listOf(
                "android:fromAlpha" to "opacité de départ (0–1)",
                "android:toAlpha" to "opacité d'arrivée (0–1)",
            ),
            "scale" to listOf(
                "android:fromXScale" to "échelle X de départ (1 = neutre)",
                "android:toXScale" to "échelle X d'arrivée",
                "android:fromYScale" to "échelle Y de départ",
                "android:toYScale" to "échelle Y d'arrivée",
                "android:pivotX" to "pivot X (px, % ou %p)",
                "android:pivotY" to "pivot Y (px, % ou %p)",
            ),
            "translate" to listOf(
                "android:fromXDelta" to "translation X de départ (px, % ou %p)",
                "android:toXDelta" to "translation X d'arrivée",
                "android:fromYDelta" to "translation Y de départ",
                "android:toYDelta" to "translation Y d'arrivée",
            ),
            "rotate" to listOf(
                "android:fromDegrees" to "angle de départ (degrés)",
                "android:toDegrees" to "angle d'arrivée",
                "android:pivotX" to "pivot X (px, % ou %p)",
                "android:pivotY" to "pivot Y (px, % ou %p)",
            ),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "repeatMode" to listOf("restart", "reverse"),
            "zAdjustment" to listOf("normal", "top", "bottom"),
            "fillAfter" to listOf("true", "false"),
            "fillBefore" to listOf("true", "false"),
            "fillEnabled" to listOf("true", "false"),
            "shareInterpolator" to listOf("true", "false"),
        )

    /**
     * Interpolateurs du FRAMEWORK (valeur de `android:interpolator`,
     * `@android:anim/…` — public par construction, comme les styles
     * `@android:style/` du contributeur XE4).
     */
    private val INTERPOLATEURS: List<String> =
        listOf(
            "@android:anim/accelerate_decelerate_interpolator",
            "@android:anim/accelerate_interpolator",
            "@android:anim/anticipate_interpolator",
            "@android:anim/anticipate_overshoot_interpolator",
            "@android:anim/bounce_interpolator",
            "@android:anim/cycle_interpolator",
            "@android:anim/decelerate_interpolator",
            "@android:anim/linear_interpolator",
            "@android:anim/overshoot_interpolator",
        )

    /**
     * Complétion du NOM DE BALISE — racines tween (élément isolé
     * légal) ou enfants d'un `set` (X4 : autre parent = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> BALISES.keys.toList()

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
                        texteTri =
                            ClassementXml.cleTri(
                                BALISES_FREQUENTES.indexOf(nom).takeIf { it >= 0 }
                                    ?: ClassementXml.rangBalise(nom),
                                nom,
                            ),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT — spécifiques de la balise PLUS
     * communs de timing ; déclarations xmlns: en frappe de préfixe.
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
        val specifiques = ATTRIBUTS_SPECIFIQUES[element.nom] ?: return emptyList()
        val candidats = specifiques + ATTRIBUTS_COMMUNS
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
     * Complétion de VALEUR — énumérations de timing ET interpolateurs
     * du framework sur `android:interpolator` (le contributeur
     * général XE3 complète avec les références `@anim/…` du projet).
     */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        if (contexte.attribut.substringAfter(':') == "interpolator") {
            for (reference in INTERPOLATEURS) {
                if (prefixe.isEmpty() || reference.startsWith(prefixe)) {
                    propositions.add(
                        PropositionCompletionXml(
                            libelle = reference,
                            genre = GenreCompletionXml.REFERENCE,
                            detail = "interpolateur framework",
                            texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE_FW, reference),
                        ),
                    )
                }
            }
        }
        val enumerations = ENUMERATIONS[contexte.attribut.substringAfter(':')] ?: return propositions
        for (valeur in enumerations) {
            if (prefixe.isEmpty() || valeur.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = valeur,
                        genre = GenreCompletionXml.VALEUR_ENUM,
                        detail = "enum",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, valeur),
                    ),
                )
            }
        }
        return propositions
    }
}
