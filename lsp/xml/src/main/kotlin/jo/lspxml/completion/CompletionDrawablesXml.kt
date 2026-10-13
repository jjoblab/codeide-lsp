package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre DRAWABLE (XE5, LSP_XML.md §4.3 — « drawables :
 * mêmes moteurs, catalogues distincts ») — balises racines et ENFANTS
 * selon le parent (shape → corners/gradient/solid…, vector → group/
 * path…), attributs PAR BALISE (parent-conscients pour `item` : états
 * d'un selector, calques d'un layer-list, frame d'une animation-list)
 * et énumérations des attributs connus (`shape`, `type`, `tileMode`…).
 *
 * Catalogue FIXE par la documentation (comme `tools:` de XE2 et les
 * entrées nommées de XE4) : les attributs de drawables ne vivent dans
 * AUCUN styleable de vues — le contributeur les porte, le REGISTRE
 * GLOBAL du catalogue complète les formats quand il les connaît.
 * Conservateur (X4) : un parent inconnu ne propose RIEN (silence
 * plutôt que bruit).
 */
internal object CompletionDrawablesXml {
    /** Balises RACINES d'un drawable XML (nom → détail). */
    private val BALISES_RACINES: Map<String, String> =
        mapOf(
            "shape" to "drawable de forme (corners, gradient, solid, stroke)",
            "selector" to "liste d'états (state_pressed…)",
            "layer-list" to "pile de calques (item left/top/right/bottom)",
            "ripple" to "retour tactile Material (API 21+)",
            "vector" to "drawable vectoriel (group, path)",
            "inset" to "drawable encarté (insetLeft…)",
            "clip" to "drawable rogné (clipOrientation, gravity)",
            "scale" to "drawable mis à l'échelle (scaleWidth/Height)",
            "rotate" to "drawable pivoté (fromDegrees, toDegrees)",
            "bitmap" to "bitmap tuilable (src, tileMode)",
            "animation-list" to "animation image par image (item duration)",
            "level-list" to "liste de niveaux (minLevel, maxLevel)",
            "transition" to "drawable de transition (deux calques)",
            "animated-vector" to "vector animé (target)",
        )

    /** Balises usuelles d'abord (le candidat probable en tête). */
    private val BALISES_FREQUENTES: List<String> =
        listOf("shape", "selector", "vector", "layer-list", "ripple", "inset")

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "shape" to listOf("corners", "gradient", "solid", "stroke", "padding", "size"),
            "vector" to listOf("group", "path", "clip-path"),
            "group" to listOf("group", "path", "clip-path"),
            "selector" to listOf("item"),
            "layer-list" to listOf("item"),
            "ripple" to listOf("item"),
            "animation-list" to listOf("item"),
            "level-list" to listOf("item"),
            "transition" to listOf("item"),
            "animated-vector" to listOf("target"),
            // Enveloppes et calques : le drawable enfant vit
            // DIRECTEMENT (pas de <item> intermédiaire).
            "inset" to BALISES_RACINES.keys.toList(),
            "clip" to BALISES_RACINES.keys.toList(),
            "scale" to BALISES_RACINES.keys.toList(),
            "rotate" to BALISES_RACINES.keys.toList(),
            "bitmap" to BALISES_RACINES.keys.toList(),
            "item" to BALISES_RACINES.keys.toList(),
        )

    /** Détails des balises ENFANTS (absentes des racines). */
    private val DETAILS_ENFANTS: Map<String, String> =
        mapOf(
            "corners" to "rayons des coins (shape)",
            "gradient" to "dégradé (startColor, endColor…)",
            "solid" to "remplissage plein (color)",
            "stroke" to "contour (width, dashWidth, dashGap)",
            "padding" to "marges internes du drawable",
            "size" to "taille intrinsèque (width, height)",
            "item" to "calque ou état (selon le parent)",
            "group" to "transformation d'un sous-arbre (rotation…)",
            "path" to "tracé vectoriel (pathData)",
            "clip-path" to "tracé de découpe",
            "target" to "cible d'animation (name, animation)",
        )

    /** Attributs PAR BALISE (nom complet → détail court). */
    private val ATTRIBUTS: Map<String, List<Pair<String, String>>> =
        mapOf(
            "shape" to listOf(
                "android:shape" to "forme : rectangle, oval, line, ring",
                "android:tint" to "teinte",
                "android:innerRadius" to "rayon intérieur (ring)",
                "android:innerRadiusRatio" to "ratio du rayon intérieur (ring)",
                "android:thickness" to "épaisseur (ring)",
                "android:thicknessRatio" to "ratio d'épaisseur (ring)",
                "android:useLevel" to "niveau = progression",
            ),
            "corners" to listOf(
                "android:radius" to "rayon des quatre coins",
                "android:topLeftRadius" to "rayon du coin haut-gauche",
                "android:topRightRadius" to "rayon du coin haut-droit",
                "android:bottomLeftRadius" to "rayon du coin bas-gauche",
                "android:bottomRightRadius" to "rayon du coin bas-droit",
            ),
            "gradient" to listOf(
                "android:angle" to "angle (multiple de 45)",
                "android:startColor" to "couleur de début",
                "android:centerColor" to "couleur du centre",
                "android:endColor" to "couleur de fin",
                "android:type" to "type : linear, radial, sweep",
                "android:centerX" to "abscisse du centre (0–1)",
                "android:centerY" to "ordonnée du centre (0–1)",
                "android:gradientRadius" to "rayon du radial",
                "android:startX" to "début X (linear, API 24)",
                "android:startY" to "début Y (linear, API 24)",
                "android:endX" to "fin X (linear, API 24)",
                "android:endY" to "fin Y (linear, API 24)",
            ),
            "solid" to listOf("android:color" to "couleur de remplissage"),
            "stroke" to listOf(
                "android:width" to "épaisseur du contour",
                "android:color" to "couleur du contour",
                "android:dashWidth" to "longueur des tirets",
                "android:dashGap" to "écart des tirets",
            ),
            "padding" to listOf(
                "android:left" to "marge interne gauche",
                "android:top" to "marge interne haute",
                "android:right" to "marge interne droite",
                "android:bottom" to "marge interne basse",
            ),
            "size" to listOf(
                "android:width" to "largeur intrinsèque",
                "android:height" to "hauteur intrinsèque",
            ),
            "ripple" to listOf(
                "android:color" to "couleur de l'ondulation",
                "android:radius" to "rayon maximal (défaut : moitié)",
            ),
            "vector" to listOf(
                "android:width" to "largeur de rendu (dp)",
                "android:height" to "hauteur de rendu (dp)",
                "android:viewportWidth" to "largeur du repère",
                "android:viewportHeight" to "hauteur du repère",
                "android:tint" to "teinte",
                "android:alpha" to "opacité (0–1)",
                "android:autoMirrored" to "miroir en RTL",
            ),
            "group" to listOf(
                "android:name" to "nom du groupe",
                "android:translateX" to "translation X",
                "android:translateY" to "translation Y",
                "android:rotation" to "rotation (degrés)",
                "android:pivotX" to "pivot X",
                "android:pivotY" to "pivot Y",
                "android:scaleX" to "échelle X",
                "android:scaleY" to "échelle Y",
            ),
            "path" to listOf(
                "android:name" to "nom du tracé",
                "android:pathData" to "données du tracé (M, L, C…)",
                "android:fillColor" to "couleur de remplissage",
                "android:fillAlpha" to "opacité du remplissage",
                "android:fillType" to "règle de remplissage",
                "android:strokeColor" to "couleur du contour",
                "android:strokeWidth" to "épaisseur du contour",
                "android:strokeAlpha" to "opacité du contour",
                "android:strokeLineCap" to "terminaison : butt, round, square",
                "android:strokeLineJoin" to "jointure : miter, round, bevel",
                "android:strokeMiterLimit" to "limite de l'onglet",
                "android:trimPathStart" to "rognage début (0–1)",
                "android:trimPathEnd" to "rognage fin (0–1)",
                "android:trimPathOffset" to "décalage du rognage",
            ),
            "clip-path" to listOf(
                "android:name" to "nom de la découpe",
                "android:pathData" to "données du tracé de découpe",
            ),
            "inset" to listOf(
                "android:drawable" to "drawable enfant",
                "android:insetLeft" to "encart gauche",
                "android:insetTop" to "encart haut",
                "android:insetRight" to "encart droit",
                "android:insetBottom" to "encart bas",
                "android:inset" to "encart des quatre côtés (API 23)",
                "android:visible" to "visibilité initiale",
            ),
            "bitmap" to listOf(
                "android:src" to "bitmap source",
                "android:gravity" to "positionnement",
                "android:tileMode" to "tuilage : clamp, repeat, mirror",
                "android:tileModeX" to "tuilage horizontal (API 26)",
                "android:tileModeY" to "tuilage vertical (API 26)",
                "android:antialias" to "anticrénelage",
                "android:filter" to "filtrage bitmap",
                "android:dither" to "tramage",
                "android:mipMap" to "mipmap",
            ),
            "clip" to listOf(
                "android:drawable" to "drawable enfant",
                "android:clipOrientation" to "sens du rognage",
                "android:gravity" to "côté du rognage",
            ),
            "scale" to listOf(
                "android:drawable" to "drawable enfant",
                "android:scaleWidth" to "largeur mise à l'échelle",
                "android:scaleHeight" to "hauteur mise à l'échelle",
                "android:scaleGravity" to "ancrage de l'échelle",
            ),
            "rotate" to listOf(
                "android:drawable" to "drawable enfant",
                "android:fromDegrees" to "angle de départ",
                "android:toDegrees" to "angle de fin",
                "android:pivotX" to "pivot X",
                "android:pivotY" to "pivot Y",
                "android:visible" to "visibilité initiale",
            ),
            "animation-list" to listOf(
                "android:oneshot" to "jouer une seule fois",
            ),
            "level-list" to emptyList(),
            "transition" to emptyList(),
            "selector" to emptyList(),
            "layer-list" to emptyList(),
            "animated-vector" to listOf(
                "android:drawable" to "vector animé",
            ),
            "target" to listOf(
                "android:animation" to "animation (animator)",
                "android:name" to "nom du path/group visé",
            ),
        )

    /** Attributs d'un `item` SELON son parent (états, calques, frames). */
    private fun attributsItem(parent: String?): List<Pair<String, String>> =
        when (parent) {
            "selector" -> listOf(
                "android:drawable" to "drawable de cet état",
                "android:state_pressed" to "état pressé",
                "android:state_focused" to "état focalisé",
                "android:state_selected" to "état sélectionné",
                "android:state_checked" to "état coché",
                "android:state_checkable" to "état cochable",
                "android:state_enabled" to "état activé",
                "android:state_activated" to "état activé persistant",
                "android:state_hovered" to "état survolé",
                "android:state_window_focused" to "fenêtre focalisée",
            )

            "layer-list" -> listOf(
                "android:drawable" to "drawable du calque",
                "android:id" to "identifiant du calque",
                "android:left" to "décalage gauche",
                "android:top" to "décalage haut",
                "android:right" to "décalage droit",
                "android:bottom" to "décalage bas",
                "android:width" to "largeur du calque",
                "android:height" to "hauteur du calque",
                "android:gravity" to "ancrage du calque",
            )

            "animation-list" -> listOf(
                "android:drawable" to "frame de l'animation",
                "android:duration" to "durée de la frame (ms)",
            )

            "level-list" -> listOf(
                "android:drawable" to "drawable du niveau",
                "android:minLevel" to "niveau minimal",
                "android:maxLevel" to "niveau maximal",
            )

            else -> listOf("android:drawable" to "drawable enfant")
        }

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "shape" to listOf("rectangle", "oval", "line", "ring"),
            "type" to listOf("linear", "radial", "sweep"),
            "tileMode" to listOf("disabled", "clamp", "repeat", "mirror"),
            "tileModeX" to listOf("disabled", "clamp", "repeat", "mirror"),
            "tileModeY" to listOf("disabled", "clamp", "repeat", "mirror"),
            "clipOrientation" to listOf("horizontal", "vertical"),
            "fillType" to listOf("nonZero", "evenOdd"),
            "strokeLineCap" to listOf("butt", "round", "square"),
            "strokeLineJoin" to listOf("miter", "round", "bevel"),
            "useLevel" to listOf("true", "false"),
            "oneshot" to listOf("true", "false"),
            "antialias" to listOf("true", "false"),
            "filter" to listOf("true", "false"),
            "dither" to listOf("true", "false"),
            "mipMap" to listOf("true", "false"),
            "autoMirrored" to listOf("true", "false"),
            "visible" to listOf("true", "false"),
            "alpha" to listOf("true", "false"),
        )

    /**
     * Complétion du NOM DE BALISE — racines sans parent, ENFANTS du
     * parent connu (X4 : parent inconnu = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> BALISES_RACINES.keys.toList()

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
                        detail = BALISES_RACINES[nom] ?: DETAILS_ENFANTS[nom],
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
     * Complétion du NOM D'ATTRIBUT — PAR BALISE (parent-conscient pour
     * `item`), déclarations xmlns: en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, balise ou élément
     * inconnu) — chacun porte sa décision de silence X4.
     */
    @Suppress("ReturnCount")
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        if (CompletionXml.frappeDeDeclarationXmlns(prefixe)) {
            return CompletionXml.attributsXmlns(prefixe)
        }
        val element = contexte.element ?: return emptyList()
        val candidats =
            if (element.nom == "item" && element.parent != null) {
                attributsItem(element.parent!!.nom)
            } else {
                ATTRIBUTS[element.nom] ?: return emptyList()
            }
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

    /**
     * Complétion de VALEUR — énumérations des attributs connus (le
     * contributeur VALUES général apporte les références `@…` et les
     * énumérations du catalogue : la SESSION fusionne et dédoublonne).
     */
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
