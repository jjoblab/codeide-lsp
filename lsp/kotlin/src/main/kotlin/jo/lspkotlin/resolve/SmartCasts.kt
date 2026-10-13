package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.parse.kt.typeTexteDepuis
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * SMART CASTS POSITIONNELS (K4, LSP_KOTLIN.md §4.3 « smart casts
 * positionnels : branches is/!is des formes if, when, &&/||, garde à
 * sortie précoce ») — le raffinement conservateur du type d'une
 * référence selon les conditions qui la DOMINENT syntaxiquement.
 *
 * BORNES (D3, sous-rapporte plutôt que faux) :
 *  - seules les formes DÉMONTRABLES positionnellement servent : branche
 *    THEN d'un `if` (conjonction pure), entrée de `when` SANS sujet,
 *    opérande DROIT d'un `&&`, garde `!is` à sortie précoce
 *    (return/throw) dans le bloc ENGLOBANT ;
 *  - un `||` dans une condition annule TOUTE déduction (on ne sait pas
 *    quelle alternative a mené ici) ; la branche ELSE ne profite que
 *    d'un `!is` SEUL dans la condition ;
 *  - le cast ne s'applique PAS à une référence OMBRÉE par une locale
 *    déclarée APRÈS la condition (l'ombrage est tranché par l'appelant
 *    TypageExpression, qui possède les portées) ;
 *  - `is T?` n'existe pas en Kotlin : le type écrit est non-null par
 *    construction ; les génériques (`is List<String>`) sont rabattus
 *    sur le nom de BASE (le paramétrage n'est pas modélisé à K4).
 *
 * Exemptions detekt ciblées (règle 16) : LongParameterList — le
 * CONTEXTE de résolution est FILÉ tel quel, patron TypageExpression ;
 * CyclomaticComplexMethod/ReturnCount — le when du CATALOGUE des
 * formes (THEN, ELSE, when, &&, ||, garde précoce) : une branche = une
 * ISSUE syntaxique ; LoopWithTooManyJumpStatements — les continue des
 * gardes du bloc sont le patron du parcours.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
internal object SmartCasts {
    /**
     * Cast positionnel applicable au nom [nom] utilisé à [decalage].
     *
     * @param racine racine du document porteur.
     * @param decalage position de la RÉFÉRENCE (début du nom).
     * @param nom nom de la référence castée.
     * @param contexteNom contexte de nommage (résolution du type écrit).
     * @param resolution résolveur classpath (fqcn du type écrit).
     * @return le cast (FQCN + position de la condition qui l'établit),
     *         `null` si aucune forme démontrable ne domine la position.
     */
    internal fun castPositionnel(
        racine: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): CastPositionnel? {
        val chaine = chaineAncetresA(racine, decalage)
        // Du PLUS PROFOND au plus externe : le cast le plus PROCHE de la
        // référence gagne (raffinement le plus récent).
        for (profondeur in chaine.indices.reversed()) {
            val cast = castDe(chaine[profondeur], decalage, nom, contexteNom, resolution) ?: continue
            return cast
        }
        return null
    }

    /**
     * Un cast établi par un ancêtre DIRECT de la référence.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque forme
     * (THEN, ELSE, when, &&, garde précoce) est une ISSUE syntaxique
     * distincte du même catalogue, patron des portées.
     */
    @Suppress("ReturnCount")
    private fun castDe(
        ancetre: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): CastPositionnel? {
        when (ancetre.type) {
            KtNodeTypes.IF -> {
                val condition = ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.CONDITION } ?: return null
                val alors = ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.THEN }
                val sinon = ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.ELSE }
                val dansAlors = alors != null && decalage >= alors.debut && decalage < alors.fin
                val dansSinon = sinon != null && decalage >= sinon.debut && decalage < sinon.fin
                if (dansAlors && !contientAlternative(condition)) {
                    // Conjonction pure : tout `is` positif sur le nom vaut.
                    return castDepuisFiltre(condition, nom, contexteNom, resolution, positif = true)
                }
                if (dansSinon && estSeulEstCondition(condition)) {
                    // Condition `nom !is T` SEULE : la branche else SAIT
                    // que le nom EST T.
                    return castDepuisFiltre(condition, nom, contexteNom, resolution, positif = false)
                }
                return null
            }

            KtNodeTypes.WHEN_ENTRY -> {
                // when SANS sujet : la condition de l'entrée est une
                // expression booléenne complète (`nom is T ->`).
                val condition =
                    ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.WHEN_CONDITION_EXPRESSION }
                        ?: return null
                if (contientAlternative(condition)) return null
                return castDepuisFiltre(condition, nom, contexteNom, resolution, positif = true)
            }

            KtNodeTypes.BINARY_EXPRESSION -> {
                // L'opération vit dans un nœud OPERATION_REFERENCE : les
                // opérandes sont les nœuds restants (gauche, droite).
                val operation =
                    ancetre.jetonsDescendants().firstOrNull { it.type == KtTokens.ANDAND || it.type == KtTokens.OROR }
                val operands = ancetre.sousNoeuds.filter { it.type != KtNodeTypes.OPERATION_REFERENCE }
                if (operands.size < 2) return null
                val gauche = operands.first()
                val droite = operands.last()
                return when (operation?.type) {
                    KtTokens.ANDAND ->
                        // `nom is T && <ici>` : la conjonction a établi le
                        // cast AVANT l'opérande droit.
                        if (decalage >= droite.debut && decalage < droite.fin && !contientAlternative(gauche)) {
                            castDepuisFiltre(gauche, nom, contexteNom, resolution, positif = true)
                        } else {
                            null
                        }

                    KtTokens.OROR ->
                        // `nom !is T || <ici>` : l'alternative fausse FORCE
                        // le cast dans l'opérande droit — seulement si la
                        // gauche EST la négation seule.
                        if (decalage >= droite.debut && decalage < droite.fin && estSeulEstCondition(gauche)) {
                            castDepuisFiltre(gauche, nom, contexteNom, resolution, positif = false)
                        } else {
                            null
                        }

                    else -> null
                }
            }

            KtNodeTypes.BLOCK -> {
                // Garde à sortie précoce : un `if (nom !is T) return/throw`
                // AVANT la position établit le cast pour TOUT ce qui suit.
                return castDeGardePrecoce(ancetre, decalage, nom, contexteNom, resolution)
            }

            else -> return null
        }
    }

    /**
     * Garde précoce d'un BLOC : la PREMIÈRE déclaration `if` dont la
     * condition est `nom !is T` seule et dont la branche THEN se
     * termine par return/throw, entièrement AVANT [decalage].
     */
    private fun castDeGardePrecoce(
        bloc: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): CastPositionnel? {
        for (enfant in bloc.sousNoeuds) {
            if (enfant.type != KtNodeTypes.IF) continue
            if (enfant.fin > decalage) continue
            val condition = enfant.sousNoeuds.firstOrNull { it.type == KtNodeTypes.CONDITION } ?: continue
            if (!estSeulEstCondition(condition)) continue
            val alors = enfant.sousNoeuds.firstOrNull { it.type == KtNodeTypes.THEN } ?: continue
            if (!sortDefinitivement(alors)) continue
            return castDepuisFiltre(condition, nom, contexteNom, resolution, positif = false)
        }
        return null
    }

    /** La branche THEN se termine-t-elle par return ou throw (sans retour) ? */
    private fun sortDefinitivement(alors: NoeudSyntaxique): Boolean =
        alors.jetonsDescendants().any { jeton ->
            jeton.type == KtTokens.RETURN_KEYWORD || jeton.type == KtTokens.THROW_KEYWORD
        }

    /**
     * Cast depuis les IS_EXPRESSION d'un sous-arbre condition.
     *
     * @param positif vrai : cherche `is` (le cast EST établi) ; faux :
     *        cherche `!is` (la NÉGATION de la condition l'établit).
     */
    private fun castDepuisFiltre(
        condition: NoeudSyntaxique,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        positif: Boolean,
    ): CastPositionnel? {
        var resultat: CastPositionnel? = null
        parcourirEst(condition) { expression ->
            val negation = expression.jetonsDescendants().any { it.type == KtTokens.NOT_IS }
            // `is` positif sert positif=true ; `!is` sert positif=false.
            if (negation == positif) return@parcourirEst
            val operands = operandsEst(expression, contexteNom, resolution) ?: return@parcourirEst
            if (operands.first != nom) return@parcourirEst
            // Le PLUS TARDIF gagne (raffinement le plus proche).
            resultat = CastPositionnel(operands.second, expression.debut)
        }
        return resultat
    }

    /** (nom de l'opérande gauche, FQCN du type écrit) d'une IS_EXPRESSION. */
    private fun operandsEst(
        expression: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): Pair<String, String>? {
        val gauche =
            expression.sousNoeuds.firstOrNull { it.type == KtNodeTypes.REFERENCE_EXPRESSION } ?: return null
        val nomGauche = nomReference(gauche) ?: return null
        val type = expression.sousNoeuds.firstOrNull { it.type == KtNodeTypes.TYPE_REFERENCE } ?: return null
        val texte = typeTexteDepuis(type) ?: return null
        // Génériques rabattus sur le nom de base (K4 : pas de
        // paramétrage) ; `?` impossible dans un type d'`is`.
        val nomEcrit = texte.substringBefore('<').trim().removeSuffix("?")
        if (nomEcrit.isEmpty() || nomEcrit.contains(' ')) return null
        val fqcn = resolution.fqcnDuNomEcrit(nomEcrit, contexteNom) ?: return null
        return Pair(nomGauche, fqcn)
    }

    /** La condition contient-elle une alternative (||) ? Aucune déduction alors. */
    private fun contientAlternative(condition: NoeudSyntaxique): Boolean =
        condition.jetonsDescendants().any { it.type == KtTokens.OROR }

    /** La condition est-elle une IS_EXPRESSION SEULE (pas de conjonction) ? */
    private fun estSeulEstCondition(condition: NoeudSyntaxique): Boolean {
        if (condition.jetonsDescendants().any { it.type == KtTokens.ANDAND }) return false
        return condition.sousNoeuds.any { it.type == KtNodeTypes.IS_EXPRESSION }
    }

    /** Parcours des IS_EXPRESSION d'un sous-arbre (profondeur d'abord). */
    private fun parcourirEst(racine: NoeudSyntaxique, visite: (NoeudSyntaxique) -> Unit) {
        if (racine.type == KtNodeTypes.IS_EXPRESSION) visite(racine)
        racine.sousNoeuds.forEach { parcourirEst(it, visite) }
    }

    /**
     * Un cast positionnel établi.
     *
     * @property fqcn type établi (non null par construction du `is`).
     * @property decalageCondition début de la condition qui l'établit —
     *           l'appelant s'en sert pour trancher l'OMBRAGE par une
     *           locale déclarée entre la condition et l'usage.
     */
    internal data class CastPositionnel(
        val fqcn: String,
        val decalageCondition: Int,
    )
}
