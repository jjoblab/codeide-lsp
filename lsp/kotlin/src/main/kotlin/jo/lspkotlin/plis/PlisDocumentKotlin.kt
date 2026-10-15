package jo.lspkotlin.plis

import jo.lspkotlin.api.PlagePliableKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Génération de la liste `textDocument/foldingRange` pour Kotlin (S2 —
 * plan de stabilisation LSP §3.2) : les régions REPLIABLES du document.
 *
 * Règle unique pour les productions : un nœud PLIABLE est plié de sa
 * PREMIÈRE accolade ouvrante à sa DERNIÈRE accolade fermante (jamais
 * du début du nœud — un pli qui masque l'en-tête `fun f()` ou
 * `when (x)` serait du bruit). Types pliés :
 * - [KtNodeTypes.CLASS_BODY] — corps de classe/objet/compagnon/enum.
 * - [KtNodeTypes.BLOCK] — bloc d'instructions (corps de fonction,
 *   corps d'initialiseur, branche de contrôle).
 * - [KtNodeTypes.BODY] — corps d'une structure de contrôle (if/else/
 *   for/while/try). Le nœud imbriqué BLOCK couvre les MÊMES accolades :
 *   la déduplication par plage fait l'un des deux (le premier émis).
 * - [KtNodeTypes.LAMBDA_EXPRESSION] — lambda (`FUNCTION_LITERAL`
 *   couvre les mêmes accolades, dédupliqué).
 * - [KtNodeTypes.WHEN] — le bloc d'un `when` (accolades seules).
 * - [KtNodeTypes.IMPORT_LIST] — le bloc d'imports ENTIER (du premier
 *   `import` au dernier, patron JDT.LS/IntelliJ).
 * Règle pour les jetons : un commentaire multi-ligne ([KtTokens.DOC_COMMENT]
 * KDoc ou [KtTokens.BLOCK_COMMENT]) est pliable — les EOL isolées et
 * les commentaires mono-ligne ne le sont PAS (l'hôte filtre les plis
 * mono-ligne, le moteur ne connaît pas les lignes).
 *
 * Tolérance (D3) : un document vide/malformé rend une liste VIDE
 * (jamais d'exception) — un nœud sans PAIRE d'accolades (édition en
 * cours) ne produit RIEN ; les nœuds d'erreur ne sont pas exclus (le
 * pliage suit la structure écrite, pas la validité).
 */
internal object PlisDocumentKotlin {
    /** Productions pliables par la règle d'accolades. */
    private val NOEUDS_ACOLADES: Set<com.intellij.platform.syntax.SyntaxElementType> =
        setOf(
            KtNodeTypes.CLASS_BODY,
            KtNodeTypes.BLOCK,
            KtNodeTypes.BODY,
            KtNodeTypes.LAMBDA_EXPRESSION,
            KtNodeTypes.WHEN,
        )

    /**
     * Plages pliables du document, en ORDRE DU DOCUMENT (profondeur
     * d'abord — le conteneur précède ses membres), DÉDUPLIQUÉES (BODY
     * et son BLOCK imbriqué portent les mêmes accolades : une seule
     * plage).
     */
    internal fun depuis(racine: NoeudSyntaxique): List<PlagePliableKotlin> {
        val vues = LinkedHashSet<Pair<Int, Int>>()
        collecter(racine, vues)
        return vues.map { (debut, fin) -> PlagePliableKotlin(debut, fin) }
    }

    /** Marche récursive : le nœud (jetons compris), puis ses enfants. */
    private fun collecter(
        noeud: NoeudSyntaxique,
        vues: LinkedHashSet<Pair<Int, Int>>,
    ) {
        quand(noeud, vues)
        noeud.enfants.forEach { enfant ->
            when (enfant) {
                is JetonSyntaxique -> commentaire(enfant, vues)
                is NoeudSyntaxique -> collecter(enfant, vues)
            }
        }
    }

    /** Émet la plage d'un nœud pliable (accolades ou imports), sinon rien. */
    private fun quand(
        noeud: NoeudSyntaxique,
        vues: LinkedHashSet<Pair<Int, Int>>,
    ) {
        when (noeud.type) {
            KtNodeTypes.IMPORT_LIST -> plageImports(noeud)?.let { vues.add(it) }

            in NOEUDS_ACOLADES -> plageAccolades(noeud)?.let { vues.add(it) }

            else -> {
                // Production non pliable (expression, déclaration,
                // liste de paramètres…) : rien à replier.
            }
        }
    }

    /** Émet la plage d'un commentaire multi-ligne (KDoc / bloc). */
    private fun commentaire(
        jeton: JetonSyntaxique,
        vues: LinkedHashSet<Pair<Int, Int>>,
    ) {
        when (jeton.type) {
            KtTokens.DOC_COMMENT, KtTokens.BLOCK_COMMENT -> vues.add(Pair(jeton.debut, jeton.fin))

            else -> {
                // EOL/SHEBANG : jamais pliables (une ligne).
            }
        }
    }

    /**
     * Plage d'un bloc d'imports : du DÉBUT de la première directive à
     * la FIN de la dernière (l'IMPORT_LIST peut être vide ou en
     * édition : sans directive, rien).
     */
    private fun plageImports(noeud: NoeudSyntaxique): Pair<Int, Int>? {
        val directives = noeud.sousNoeuds.filter { it.type == KtNodeTypes.IMPORT_DIRECTIVE }
        val premiere = directives.firstOrNull() ?: return null
        val derniere = directives.last()
        return Pair(premiere.debut, derniere.fin)
    }

    /**
     * Plage d'un bloc à accolades : première accolade ouvrante du
     * sous-arbre → dernière fermante. Sans PAIRE (édition en cours,
     * nœud dégénéré) : rien — un pli doit replier QUELQUE CHOSE.
     */
    private fun plageAccolades(noeud: NoeudSyntaxique): Pair<Int, Int>? {
        val jetons = noeud.jetonsDescendants()
        val ouvrante = jetons.firstOrNull { it.type == KtTokens.LBRACE }
        val fermante = jetons.lastOrNull { it.type == KtTokens.RBRACE }
        val plage =
            if (ouvrante != null && fermante != null && fermante.fin > ouvrante.fin) {
                Pair(ouvrante.debut, fermante.fin)
            } else {
                null
            }
        return plage
    }
}
