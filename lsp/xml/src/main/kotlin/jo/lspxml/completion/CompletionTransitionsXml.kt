package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre TRANSITION (XP12, plan de stabilisation LSP
 * §3.3 S2 — « genres étendus ») : transitions du framework
 * (`android.transition`, `res/transition/nom.xml`) — racines
 * `transitionSet` ou transition isolée (`fade`, `slide`, `explode`,
 * `changeBounds`…), ciblage (`targets`/`target`), trajectoires
 * (`arcMotion`, `patternPathMotion`).
 *
 * Catalogue FIXE par la documentation (motif XE5). Conservateur
 * (X4) : parent inconnu = silence.
 */
internal object CompletionTransitionsXml {
    /** Balises de transition (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "transitionSet" to "groupe de transitions (transitionOrdering)",
            "autoTransition" to "set Fade+Move+Resize ordonné",
            "fade" to "fondu (fadingMode in/out)",
            "slide" to "glissement depuis un bord (slideEdge)",
            "explode" to "explosion depuis le centre",
            "changeBounds" to "animation des limites (resizeClip)",
            "changeTransform" to "animation scale/rotation/translation",
            "changeClipBounds" to "animation du clip",
            "changeImageTransform" to "animation matrix d'ImageView",
            "changeScroll" to "animation scrollX/scrollY",
            "targets" to "regroupement de cibles",
            "target" to "cible (targetId, targetName, exclude*)",
            "arcMotion" to "trajectoire en arc (maximumAngle)",
            "patternPathMotion" to "trajectoire le long d'un tracé (patternPathData)",
        )

    /** Transitions utilisables COMME enfant d'un set (ou racine isolée). */
    private val TRANSITIONS: List<String> =
        listOf(
            "transitionSet", "autoTransition", "fade", "slide", "explode",
            "changeBounds", "changeTransform", "changeClipBounds",
            "changeImageTransform", "changeScroll",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "transitionSet" to TRANSITIONS + listOf("targets"),
            "targets" to listOf("target"),
            "fade" to listOf("targets"),
            "slide" to listOf("targets"),
            "explode" to listOf("targets"),
            "changeBounds" to listOf("targets", "arcMotion", "patternPathMotion"),
            "changeTransform" to listOf("targets"),
            "changeClipBounds" to listOf("targets"),
            "changeImageTransform" to listOf("targets"),
            "changeScroll" to listOf("targets"),
            "autoTransition" to listOf("targets"),
        )

    /** Attributs COMMUNS de ciblage horaire (toutes les transitions). */
    private val ATTRIBUTS_COMMUNS: List<Pair<String, String>> =
        listOf(
            "android:duration" to "durée (ms)",
            "android:startDelay" to "décalage avant démarrage (ms)",
            "android:interpolator" to "interpolateur (@android:anim/…)",
        )

    /** Attributs PAR BALISE (spécifiques — communs ajoutés au rendu). */
    private val ATTRIBUTS_SPECIFIQUES: Map<String, List<Pair<String, String>>> =
        mapOf(
            "transitionSet" to listOf(
                "android:transitionOrdering" to "ordre : together, sequentially",
            ),
            "fade" to listOf(
                "android:fadingMode" to "sens : in, out",
            ),
            "slide" to listOf(
                "android:slideEdge" to "bord : left, right, top, bottom, start, end",
            ),
            "changeBounds" to listOf(
                "android:resizeClip" to "redimension par clip (true) ou limites (false)",
            ),
            "target" to listOf(
                "android:targetId" to "vue ciblée (@id/…)",
                "android:targetName" to "nom de transition ciblé (transitionName)",
                "android:excludeId" to "vue exclue (@id/…)",
                "android:excludeName" to "nom de transition exclu",
                "android:excludeClass" to "classe de vue exclue",
                "android:targetClass" to "classe de vue ciblée",
            ),
            "arcMotion" to listOf(
                "android:minimumHorizontalAngle" to "angle minimal horizontal (degrés)",
                "android:minimumVerticalAngle" to "angle minimal vertical (degrés)",
                "android:maximumAngle" to "angle maximal de l'arc (degrés)",
            ),
            "patternPathMotion" to listOf(
                "android:patternPathData" to "tracé du motif (M, L, C…)",
            ),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "transitionOrdering" to listOf("together", "sequentially"),
            "fadingMode" to listOf("in", "out"),
            "slideEdge" to listOf("left", "right", "top", "bottom", "start", "end"),
            "resizeClip" to listOf("true", "false"),
        )

    /**
     * Complétion du NOM DE BALISE — racines (set OU transition isolée
     * — une transition seule est une racine légale) ou enfants du
     * parent connu (X4 : autre parent = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> TRANSITIONS

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
     * communs horaires ; déclarations xmlns: en frappe de préfixe.
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

    /** Complétion de VALEUR — énumérations de transition connues. */
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
