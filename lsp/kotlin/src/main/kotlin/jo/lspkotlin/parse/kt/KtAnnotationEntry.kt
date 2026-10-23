package jo.lspkotlin.parse.kt

import jo.lspkotlin.parse.NoeudSyntaxique
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Façade des ANNOTATIONS d'une déclaration (preview Compose C1 —
 * découverte des `@Preview`/`@Composable`) : lecture des entrées
 * d'annotation posées sur une [KtDeclaration] — nom TEL QU'ÉCRIT
 * (`Preview`, `androidx.compose.ui.tooling.Preview` — la résolution en
 * FQCN appartient au consommateur, qui connaît les imports du fichier)
 * et arguments (NOMMÉS ou positionnels) avec la PLAGE du nœud de valeur
 * — le texte de la valeur se tranche dans la source par l'appelant
 * (chaîne de gabarit comprise), jamais réassemblé depuis les jetons.
 *
 * Structure parsée (KotlinParsing.parseAnnotation) :
 * `ANNOTATION_ENTRY → [AT] [ANNOTATION_TARGET] CONSTRUCTOR_CALLEE
 * (TYPE_REFERENCE) [VALUE_ARGUMENT_LIST]` — la forme multi-entrées
 * `ANNOTATION` (`@[A, B]`, dialecte java) est traversée aussi.
 *
 * Conservateur (D3) : nom introuvable → `null` ; argument sans
 * expression → plages `debutValeur == finValeur` (l'appelant rend une
 * valeur vide).
 */
public class KtAnnotationEntry(
    override val noeud: NoeudSyntaxique,
) : KtElement {
    /**
     * Nom de l'annotation TEL QU'ÉCRIT (`Preview`, `Composable`,
     * `androidx.compose.ui.tooling.Preview`) — le texte du
     * CONSTRUCTOR_CALLEE, blancs exclus. `null` si la tête est vide ou en
     * cours d'édition (`@` seul).
     */
    public fun nomEcrit(): String? {
        val callee = premierEnfantParType(KtNodeTypes.CONSTRUCTOR_CALLEE)
        if (callee != null) return typeTexteDepuis(callee.noeud)
        // Formes dégradées : TYPE_REFERENCE nue, ou jetons d'identifiants
        // AVANT la liste d'arguments (édition en cours).
        val coupure =
            premierEnfantParType(KtNodeTypes.VALUE_ARGUMENT_LIST)?.debut
                ?: noeud.fin
        return jetonsDescendants()
            .takeWhile { it.debut < coupure }
            .filter { it.type == KtTokens.IDENTIFIER || it.type == KtTokens.DOT }
            .joinToString("") { it.texte.toString() }
            .ifBlank { null }
    }

    /**
     * Arguments de l'annotation (`@Preview(widthDp = 100, "x")`),
     * dans l'ordre écrit. Chaque argument porte son NOM si nommé
     * (`widthDp`) et la PLAGE de son nœud de valeur — l'appelant tranche
     * `texte.substring(debutValeur, finValeur)` pour la valeur EXACTE
     * (guillemets compris pour une chaîne).
     */
    public fun arguments(): List<ArgumentAnnotationKt> {
        val liste = premierEnfantParType(KtNodeTypes.VALUE_ARGUMENT_LIST) ?: return emptyList()
        return liste.noeud.sousNoeuds
            .filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
            .map { argument ->
                val nom =
                    argument.sousNoeuds
                        .firstOrNull { enfant -> enfant.type == KtNodeTypes.VALUE_ARGUMENT_NAME }
                        ?.jetonsDescendants()
                        ?.firstOrNull { it.type == KtTokens.IDENTIFIER }
                        ?.texte
                        ?.toString()
                // Le nœud de valeur : premier enfant qui n'est pas le NOM
                // (`widthDp = 100` → INTEGER_CONSTANT ; `"x"` → STRING_TEMPLATE).
                val valeur =
                    argument.sousNoeuds.firstOrNull { enfant ->
                        enfant.type != KtNodeTypes.VALUE_ARGUMENT_NAME
                    }
                ArgumentAnnotationKt(
                    nom = nom,
                    debutValeur = valeur?.debut ?: argument.debut,
                    finValeur = valeur?.fin ?: argument.debut,
                )
            }
    }

    /**
     * Un argument d'annotation : nom (si nommé) + plage du nœud de
     * valeur dans la source (plage VIDE si l'argument n'a pas encore
     * d'expression — édition en cours).
     *
     * @property nom nom écrit AVANT `=` (`widthDp`), `null` pour un
     *           argument positionnel.
     * @property debutValeur début INCLUSIF du nœud de valeur.
     * @property finValeur fin EXCLUSIVE du nœud de valeur.
     */
    public data class ArgumentAnnotationKt(
        public val nom: String?,
        public val debutValeur: Int,
        public val finValeur: Int,
    )
}

/**
 * Annotations posées sur une [KtDeclaration] (preview Compose C1) —
 * les entrées vivent dans le MODIFIER_LIST enfant direct de la
 * déclaration (comportement du compilateur : annotations et
 * modificateurs partagent le même conteneur) ; la forme multi-entrées
 * `ANNOTATION` est traversée. Les annotations de FICHIER
 * (`@file:JvmName`, FILE_ANNOTATION_LIST) n'en font PAS partie.
 *
 * Ordre du document conservé.
 */
public fun KtDeclaration.annotations(): List<KtAnnotationEntry> {
    val modificateurs = premierEnfantParType(KtNodeTypes.MODIFIER_LIST) ?: return emptyList()
    return modificateurs.noeud.sousNoeuds
        .flatMap { enfant ->
            when (enfant.type) {
                KtNodeTypes.ANNOTATION_ENTRY -> listOf(enfant)
                // `@[A, B]` : les entrées sont les petits-enfants.
                KtNodeTypes.ANNOTATION -> enfant.sousNoeuds.filter { it.type == KtNodeTypes.ANNOTATION_ENTRY }
                else -> emptyList()
            }
        }
        .map { KtAnnotationEntry(it) }
}
