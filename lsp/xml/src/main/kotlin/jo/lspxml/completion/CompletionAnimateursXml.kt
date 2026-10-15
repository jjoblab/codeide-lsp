package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre ANIMATEUR (XP12, plan de stabilisation LSP
 * §3.3 S2 — « genres étendus … res/anim* » côté `res/animator*`) :
 * animateurs de PROPRIÉTÉ (`android.animation`) — racines `set`,
 * `objectAnimator`, `animator` (un `propertyValuesHolder` n'est JAMAIS
 * racine), `propertyValuesHolder` sous un objectAnimator ou un set ;
 * attributs de propriété (`propertyName`, `valueFrom`/`valueTo`,
 * `valueType`) et de timing (duration, repeat*), `ordering` sur les
 * sets.
 *
 * Catalogue FIXE par la documentation (motif XE5). Conservateur
 * (X4) : parent inconnu = silence ; les animations TWEEN de
 * `res/anim*` voyagent dans [CompletionAnimationsXml] — proposer un
 * `objectAnimator` dans un tween (ou l'inverse) ferait un XML qui
 * plante au gonflage.
 */
internal object CompletionAnimateursXml {
    /** Balises d'un animateur de propriété (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "set" to "groupe d'animateurs (ordering together/sequentially)",
            "objectAnimator" to "animation d'une propriété nommée (propertyName)",
            "animator" to "ValueAnimator (valeurs brutes, sans propertyName)",
            "propertyValuesHolder" to "porte-valeurs nommé (keyframes)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "set" to listOf("set", "objectAnimator", "animator", "propertyValuesHolder"),
            "objectAnimator" to listOf("propertyValuesHolder"),
        )

    /** Attributs COMMUNS de timing (set exclu — ordering le remplace). */
    private val ATTRIBUTS_COMMUNS: List<Pair<String, String>> =
        listOf(
            "android:duration" to "durée (ms)",
            "android:startOffset" to "décalage avant démarrage (ms)",
            "android:repeatCount" to "répétitions (-1 = infini)",
            "android:repeatMode" to "reprise : restart, reverse",
            "android:interpolator" to "interpolateur (@android:anim/…)",
        )

    /** Attributs PAR BALISE (spécifiques — communs ajoutés au rendu). */
    private val ATTRIBUTS_SPECIFIQUES: Map<String, List<Pair<String, String>>> =
        mapOf(
            "set" to listOf(
                "android:ordering" to "ordre des enfants : together, sequentially",
            ),
            "objectAnimator" to listOf(
                "android:propertyName" to "propriété animée (setters propertyName/…)",
                "android:valueFrom" to "valeur de départ",
                "android:valueTo" to "valeur d'arrivée",
                "android:valueType" to "type des valeurs : floatType, intType, pathType",
            ),
            "animator" to listOf(
                "android:valueFrom" to "valeur de départ",
                "android:valueTo" to "valeur d'arrivée",
                "android:valueType" to "type des valeurs : floatType, intType, pathType",
            ),
            "propertyValuesHolder" to listOf(
                "android:propertyName" to "propriété portée par le holder",
                "android:valueFrom" to "valeur de départ",
                "android:valueTo" to "valeur d'arrivée",
                "android:valueType" to "type des valeurs : floatType, intType, pathType",
            ),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "ordering" to listOf("together", "sequentially"),
            "valueType" to listOf("floatType", "intType", "pathType"),
            "repeatMode" to listOf("restart", "reverse"),
        )

    /** Interpolateurs du FRAMEWORK (même liste que les tween — XP12). */
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
     * Complétion du NOM DE BALISE — racines animateurs ou enfants du
     * parent connu (X4 : autre parent = silence, propertyValuesHolder
     * jamais racine).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> listOf("set", "objectAnimator", "animator")

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
     * Complétion de VALEUR — énumérations de propriété (ordering,
     * valueType) ET interpolateurs du framework sur
     * `android:interpolator` (le contributeur général XE3 complète
     * avec les références `@animator/…` du projet).
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
