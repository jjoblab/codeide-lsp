package jo.lspkotlin.parse.kt

import com.intellij.platform.syntax.SyntaxElementType
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Navigation des EXPRESSIONS sur l'arbre reconstruit (K3,
 * LSP_KOTLIN.md §4.3 « résolution ») — la façade des déclarations vit
 * dans `KtElements.kt`, celle-ci porte le versant expression : élément
 * au curseur, chaîne des parents, récepteur/sélecteur des expressions
 * qualifiées, nom de l'appelé, littéraux.
 *
 * Toutes les lectures sont conservatrices (D3) : introuvable vaut
 * `null`/vide, jamais de devinette — le résolveur sous-rapporte plutôt
 * que de résoudre faux.
 */

/** Types de nœuds qui sont des EXPRESSIONS (initialiseurs, corps, arguments). */
internal val TYPES_EXPRESSION: Set<SyntaxElementType> =
    setOf(
        KtNodeTypes.REFERENCE_EXPRESSION,
        KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
        KtNodeTypes.SAFE_ACCESS_EXPRESSION,
        KtNodeTypes.CALL_EXPRESSION,
        KtNodeTypes.ARRAY_ACCESS_EXPRESSION,
        KtNodeTypes.BINARY_EXPRESSION,
        KtNodeTypes.BINARY_WITH_TYPE,
        KtNodeTypes.PREFIX_EXPRESSION,
        KtNodeTypes.POSTFIX_EXPRESSION,
        KtNodeTypes.PARENTHESIZED,
        KtNodeTypes.NULL,
        KtNodeTypes.BOOLEAN_CONSTANT,
        KtNodeTypes.INTEGER_CONSTANT,
        KtNodeTypes.FLOAT_CONSTANT,
        KtNodeTypes.CHARACTER_CONSTANT,
        KtNodeTypes.STRING_TEMPLATE,
        KtNodeTypes.COLLECTION_LITERAL_EXPRESSION,
        KtNodeTypes.THIS_EXPRESSION,
        KtNodeTypes.SUPER_EXPRESSION,
        KtNodeTypes.IF,
        KtNodeTypes.WHEN,
        KtNodeTypes.TRY,
        KtNodeTypes.FOR,
        KtNodeTypes.WHILE,
        KtNodeTypes.DO_WHILE,
        KtNodeTypes.RETURN,
        KtNodeTypes.THROW,
        KtNodeTypes.BREAK,
        KtNodeTypes.CONTINUE,
        KtNodeTypes.LAMBDA_EXPRESSION,
        KtNodeTypes.FUNCTION_LITERAL,
        KtNodeTypes.OBJECT_LITERAL,
        KtNodeTypes.DESTRUCTURING_DECLARATION,
        KtNodeTypes.IS_EXPRESSION,
        KtNodeTypes.LABELED_EXPRESSION,
        KtNodeTypes.ANNOTATED_EXPRESSION,
        KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION,
        KtNodeTypes.CLASS_LITERAL_EXPRESSION,
    )

/** Le nœud est-il une EXPRESSION (initialiseur de propriété, argument…) ? */
internal fun estExpression(type: SyntaxElementType): Boolean = type in TYPES_EXPRESSION

/**
 * Chaîne des ANCÊTRES contenant [decalage], de la racine au PLUS PROFOND
 * (le dernier est l'élément au curseur — jeton exclu : seuls les NŒUDS
 * comptent, les jetons ne portent pas de sémantique structurelle).
 *
 * @param racine racine du document (KT_FILE / SCRIPT).
 * @param decalage décalage du curseur (caractère exclusif ou inclusif —
 *        un curseur en FIN d'identifiant touche le jeton précédent).
 * @return la liste ordonnée racine→feuille ; VIDE si l'offset sort de
 *         l'arbre.
 *
 * Exemption detekt ciblée (règle 16) : NestedBlockDepth — la descente
 * garde-niche-enfants (nœud vide traversé, enfant suivant porteur) est
 * la STRUCTURE même de l'algorithme documentée à l'ADR 0218 (préférence
 * d'enfant NON VIDE — la directive de paquet vide ne capture plus la
 * descente à l'offset 0).
 */
@Suppress("NestedBlockDepth")
public fun chaineAncetresA(
    racine: NoeudSyntaxique,
    decalage: Int,
): List<NoeudSyntaxique> {
    val chaine = mutableListOf<NoeudSyntaxique>()
    var courant: NoeudSyntaxique = racine
    chaine.add(courant)
    var descente = true
    while (descente) {
        descente = false
        // ADR 0218 : un enfant de LARGEUR NULLE (la PACKAGE_DIRECTIVE vide
        // et l'IMPORT_LIST vide sont TOUJOURS présents — découverte de
        // parité K2) ne doit JAMAIS capturer la descente quand un enfant
        // RÉEL contient le même décalage : à l'offset 0 d'un fichier SANS
        // paquet (`fun …` en première ligne), la directive vide [0,0]
        // gagnait et la chaîne mourait SANS jamais voir la déclaration —
        // survol/références/renommage muets au tout début du fichier.
        // Priorité : le premier enfant NON VIDE contenant le décalage ;
        // un enfant vide ne sert que s'il est le SEUL contenant.
        var videChoisi: NoeudSyntaxique? = null
        for (enfant in courant.enfants) {
            if (enfant is NoeudSyntaxique && enfant.debut <= decalage && decalage <= enfant.fin) {
                if (enfant.fin > enfant.debut) {
                    chaine.add(enfant)
                    courant = enfant
                    descente = true
                    break
                }
                if (videChoisi == null) videChoisi = enfant
            }
        }
        if (!descente && videChoisi != null) {
            chaine.add(videChoisi)
            courant = videChoisi
            descente = true
        }
    }
    return chaine
}

/**
 * Nom d'une REFERENCE_EXPRESSION (`foo`, `TextView`) — le texte du
 * premier jeton IDENTIFIER descendant. Null pour un sélecteur vide ou
 * une référence qualifiée complexe en édition.
 */
public fun nomReference(noeud: NoeudSyntaxique): String? =
    noeud.jetonsDescendants()
        .firstOrNull { it.type == KtTokens.IDENTIFIER }
        ?.texte
        ?.toString()

/**
 * Jeton du nom d'une référence (plage exacte — surlignage/définition).
 */
public fun jetonNomReference(noeud: NoeudSyntaxique): JetonSyntaxique? =
    noeud.jetonsDescendants().firstOrNull { it.type == KtTokens.IDENTIFIER }

/**
 * Récepteur d'une expression qualifiée (`a.b`, `a?.b`) : le PREMIER
 * enfant nœud (le texte entre le début et le `.`). Null si la
 * structure est en édition (récepteur vide).
 */
public fun recepteurQualifie(noeud: NoeudSyntaxique): NoeudSyntaxique? =
    noeud.sousNoeuds.firstOrNull()

/**
 * Sélecteur d'une expression qualifiée : le DERNIER enfant nœud (`b`,
 * `b()` — CALL_EXPRESSION le plus souvent). Null si absent.
 */
public fun selecteurQualifie(noeud: NoeudSyntaxique): NoeudSyntaxique? =
    noeud.sousNoeuds.lastOrNull()

/**
 * Nom de l'APPELÉ d'un CALL_EXPRESSION (`TextView(…)` → `TextView`) —
 * le nom de référence du callee (premier enfant REFERENCE_EXPRESSION,
 * ou le callee d'une expression qualifiée `a.b(…)`).
 */
public fun nomAppele(noeud: NoeudSyntaxique): String? {
    // callee = premier enfant nœud ; une référence simple OU qualifiée.
    val callee = noeud.sousNoeuds.firstOrNull() ?: return null
    return if (callee.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
        callee.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION
    ) {
        // a.b(…) : le nom est celui du SÉLECTEUR de a.b.
        selecteurQualifie(callee)?.let { nomReference(it) }
    } else {
        nomReference(callee)
    }
}

/**
 * Nom d'un TYPE écrit dans une USER_TYPE / référence de type
 * (`TextView`, `List` de `List<String>`) — premier IDENTIFIER du
 * sous-arbre.
 */
public fun nomTypeEcrit(noeud: NoeudSyntaxique): String? = nomReference(noeud)

/**
 * Genre de littéral d'un nœud constante (pour le typage conservateur des
 * initialiseurs — le résolveur en déduit `Int`, `String`…).
 */
public enum class GenreLitteral {
    /** Nombre entier (`1`, `0x2A`). */
    ENTIER,

    /** Nombre à virgule (`1.5f`, `2.0`). */
    DECIMAL,

    /** Booléen (`true`, `false`). */
    BOOLEEN,

    /** Chaîne de caractères (gabarit compris). */
    CHAINE,

    /** Caractère (`'a'`). */
    CARACTERE,

    /** `null`. */
    NUL,
}

/**
 * Genre de littéral d'un nœud constante, ou null si le nœud n'en est
 * pas un.
 */
public fun genreLitteral(noeud: NoeudSyntaxique): GenreLitteral? =
    when (noeud.type) {
        KtNodeTypes.INTEGER_CONSTANT -> GenreLitteral.ENTIER
        KtNodeTypes.FLOAT_CONSTANT -> GenreLitteral.DECIMAL
        KtNodeTypes.BOOLEAN_CONSTANT -> GenreLitteral.BOOLEEN
        KtNodeTypes.STRING_TEMPLATE -> GenreLitteral.CHAINE
        KtNodeTypes.CHARACTER_CONSTANT -> GenreLitteral.CARACTERE
        KtNodeTypes.NULL -> GenreLitteral.NUL
        else -> null
    }
