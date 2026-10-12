package jo.lspkotlin.parse.kt

import com.intellij.platform.syntax.SyntaxElementType
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Façade `Kt*` sur l'arbre syntaxique reconstruit — les noms de types et
 * d'accesseurs épient le vocabulaire du compilateur/PSI (`KtFile`,
 * `KtClass`, `nom()`, `declarations()`, …) : un contributeur qui connaît
 * le PSI de Kotlin se retrouve chez lui (leçon projet de référence, reprise par
 * LSP_KOTLIN.md D2). Périmètre K1 volontairement compact : fichier,
 * déclarations nommées, imports, package — les expressions et la
 * navigation fine arrivent avec la session (K2) et les symboles (K3).
 *
 * Toutes les lectures sont conservatrices (D3) : ce qui n'est pas trouvé
 * vaut `null` ou une liste vide — jamais de devinette.
 */
public sealed interface KtElement {
    /** Nœud sous-jacent de l'arbre reconstruit. */
    public val noeud: NoeudSyntaxique

    /** Type syntaxique de l'élément. */
    public val type: SyntaxElementType
        get() = noeud.type

    /** Décalage de début dans la source (inclus). */
    public val debut: Int
        get() = noeud.debut

    /** Décalage de fin dans la source (exclusif). */
    public val fin: Int
        get() = noeud.fin

    /** Enfants qui sont des productions, enveloppés en façade `Kt*`. */
    public fun enfants(): List<KtElement> = noeud.sousNoeuds.map { KtElement.depuis(it) }

    /** Enfants du type donné (recherche directe, pas récursive). */
    public fun enfantsParType(type: SyntaxElementType): List<KtElement> =
        noeud.sousNoeuds.filter { it.type == type }.map { KtElement.depuis(it) }

    /** Premier enfant du type donné, ou null (recherche directe). */
    public fun premierEnfantParType(type: SyntaxElementType): KtElement? =
        noeud.sousNoeuds.firstOrNull { it.type == type }?.let { KtElement.depuis(it) }

    /** Jetons feuilles immédiats de ce nœud. */
    public fun jetons(): List<JetonSyntaxique> = noeud.jetonsDirects

    /** Tous les jetons du sous-arbre, dans l'ordre du document. */
    public fun jetonsDescendants(): List<JetonSyntaxique> = noeud.jetonsDescendants()

    public companion object {
        /**
         * Enveloppe un nœud de l'arbre en élément de façade : le type
         * syntaxique choisit la classe spécialisée (CLASS → [KtClass]…),
         * tout le reste devient un [KtNoeud] générique navigable.
         */
        public fun depuis(noeud: NoeudSyntaxique): KtElement =
            when (noeud.type) {
                KtNodeTypes.KT_FILE -> KtFile(noeud)
                KtNodeTypes.PACKAGE_DIRECTIVE -> KtPackageDirective(noeud)
                KtNodeTypes.IMPORT_DIRECTIVE -> KtImportDirective(noeud)
                KtNodeTypes.CLASS -> KtClass(noeud)
                KtNodeTypes.OBJECT_DECLARATION -> KtObjectDeclaration(noeud)
                KtNodeTypes.FUN -> KtFunction(noeud)
                KtNodeTypes.PROPERTY -> KtProperty(noeud)
                KtNodeTypes.VALUE_PARAMETER -> KtParameter(noeud)
                KtNodeTypes.TYPEALIAS -> KtTypeAlias(noeud)
                KtNodeTypes.ENUM_ENTRY -> KtEnumEntry(noeud)
                else -> KtNoeud(noeud)
            }
    }
}

/** Racine d'un fichier Kotlin : package, imports, déclarations de premier niveau. */
public class KtFile(
    override val noeud: NoeudSyntaxique,
) : KtElement {
    /**
     * Directive package — TOUJOURS présente dans l'arbre (comportement du
     * compilateur : une directive vide est émise sans `package` écrit) ;
     * c'est [KtPackageDirective.nomQualifie] qui distingue l'absence
     * réelle (null).
     */
    public fun directivePackage(): KtPackageDirective? =
        premierEnfantParType(KtNodeTypes.PACKAGE_DIRECTIVE) as? KtPackageDirective

    /**
     * Imports du fichier, dans l'ordre du document. Le conteneur
     * `IMPORT_LIST` (toujours présent, parité PSI) est traversé.
     */
    public fun directivesImport(): List<KtImportDirective> {
        val conteneur =
            premierEnfantParType(KtNodeTypes.IMPORT_LIST)?.noeud ?: noeud
        return conteneur.sousNoeuds
            .filter { it.type == KtNodeTypes.IMPORT_DIRECTIVE }
            .map { KtImportDirective(it) }
    }

    /** Déclarations de premier niveau (classes, fonctions, propriétés, objets, typealias, énumérations). */
    public fun declarations(): List<KtDeclaration> =
        noeud.sousNoeuds.mapNotNull { enfant ->
            when (enfant.type) {
                KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION, KtNodeTypes.FUN,
                KtNodeTypes.PROPERTY, KtNodeTypes.TYPEALIAS, KtNodeTypes.ENUM_ENTRY,
                -> KtElement.depuis(enfant) as? KtDeclaration

                else -> null
            }
        }
}

/** Déclaration nommée (nom conservateur : null si introuvable — D3). */
public sealed interface KtDeclaration : KtElement {
    /**
     * Nom de la déclaration : premier jeton `IDENTIFIER` — enfant direct
     * d'abord (class, fun, val…), sinon descendant le plus à gauche
     * (typealias porte son nom dans sa référence de type). Null si la
     * déclaration est anonyme ou en cours d'édition.
     */
    public fun nom(): String? {
        val direct = jetons().firstOrNull { it.type == KtTokens.IDENTIFIER }
        val jeton = direct ?: jetonsDescendants().firstOrNull { it.type == KtTokens.IDENTIFIER }
        return jeton?.texte?.toString()
    }
}

/** Déclaration de classe / interface / classe d'énumération / data class. */
public class KtClass(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration {
    /** Vrai si le jeton `interface` est présent (une interface n'a pas de corps de constructeur). */
    public fun estInterface(): Boolean = jetonsDescendants().any { it.type == KtTokens.INTERFACE_KEYWORD }

    /** Corps de la classe (CLASS_BODY), ou null (en-tête en cours d'édition). */
    public fun corps(): KtNoeud? = premierEnfantParType(KtNodeTypes.CLASS_BODY) as? KtNoeud

    /** Déclarations immédiatement contenues dans le corps. */
    public fun membres(): List<KtDeclaration> =
        corps()?.noeud?.sousNoeuds?.mapNotNull { enfant ->
            when (enfant.type) {
                KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION, KtNodeTypes.FUN,
                KtNodeTypes.PROPERTY, KtNodeTypes.TYPEALIAS, KtNodeTypes.ENUM_ENTRY,
                KtNodeTypes.SECONDARY_CONSTRUCTOR, KtNodeTypes.CLASS_INITIALIZER,
                -> KtElement.depuis(enfant) as? KtDeclaration

                else -> null
            }
        } ?: emptyList()
}

/** Déclaration d'objet (`object Foo`, `companion object Bar`). */
public class KtObjectDeclaration(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration

/** Déclaration de fonction. */
public class KtFunction(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration {
    /** Liste de paramètres (VALUE_PARAMETER_LIST), ou null. */
    private fun listeParametres(): KtNoeud? = premierEnfantParType(KtNodeTypes.VALUE_PARAMETER_LIST) as? KtNoeud

    /** Paramètres de la fonction (vus comme KtParameter). */
    public fun parametres(): List<KtParameter> =
        listeParametres()?.noeud?.sousNoeuds
            ?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
            ?.map { KtParameter(it) }
            ?: emptyList()
}

/** Déclaration de propriété (`val`/`var`). */
public class KtProperty(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration

/** Paramètre de fonction ou de constructeur. */
public class KtParameter(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration

/** Alias de type (`typealias Foo = …`). */
public class KtTypeAlias(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration

/** Entrée d'énumération (`RED, GREEN`). */
public class KtEnumEntry(
    override val noeud: NoeudSyntaxique,
) : KtElement, KtDeclaration

/** Directive package — nom qualifié conservateur, null si vide/absent. */
public class KtPackageDirective(
    override val noeud: NoeudSyntaxique,
) : KtElement {
    /** Nom qualifié du package (`a.b.c`), ou null. */
    public fun nomQualifie(): String? = nomQualifieDepuisImport()
}

/** Directive import — nom qualifié + alias éventuels. */
public class KtImportDirective(
    override val noeud: NoeudSyntaxique,
) : KtElement {
    /**
     * Nom qualifié importé, sans l'alias (`import a.b.C as D` rend `a.b.c`,
     * sans le `as D`) ; le wildcard final (`import a.b.*`) est rendu tel
     * quel. Null si la directive est vide ou en cours d'édition.
     */
    public fun nomQualifie(): String? = nomQualifieDepuisImport()

    /**
     * Alias d'import (`D` pour `import a.b.C as D`), ou null. Null aussi
     * en cas de jeton `as` sans alias derrière (édition en cours — D3 :
     * sous-rapporter).
     */
    public fun alias(): String? {
        val jetons = jetonsDescendants()
        val indexAs = jetons.indexOfFirst { it.type == KtTokens.AS_KEYWORD }
        if (indexAs < 0) return null
        // Le premier jeton SIGNIFICATIF après `as` (les blancs sont des
        // jetons de l'arbre — il faut les sauter).
        val apres = jetons.drop(indexAs + 1).firstOrNull { it.type != KtTokens.WHITE_SPACE }
        return if (apres?.type == KtTokens.IDENTIFIER) apres.texte.toString() else null
    }
}

/**
 * Nœud générique : tout élément sans classe spécialisée — reste pleinement
 * navigable (enfants par type, jetons descendants, offsets).
 */
public class KtNoeud(
    override val noeud: NoeudSyntaxique,
) : KtElement

/**
 * Reconstitue un nom qualifié (`a.b.c`) depuis les jetons d'une directive
 * package/import : les `IDENTIFIER` et `DOT` sont assemblés jusqu'au
 * premier `AS_KEYWORD`/`MULT`/fin. Null si aucun identifiant.
 */
private fun KtElement.nomQualifieDepuisImport(): String? {
    val morceaux = mutableListOf<String>()
    var precedentEstIdentifiant = false
    boucleJetons@ for (jeton in jetonsDescendants()) {
        when (jeton.type) {
            KtTokens.IDENTIFIER -> {
                morceaux.add(jeton.texte.toString())
                precedentEstIdentifiant = true
            }

            KtTokens.DOT -> {
                if (precedentEstIdentifiant) {
                    morceaux.add(".")
                    precedentEstIdentifiant = false
                }
            }

            KtTokens.MUL -> {
                // Wildcard d'import : `import a.b.*` → `a.b.*`.
                morceaux.add("*")
                return morceaux.joinToString("")
            }

            // `as` / `;` : ce qui suit n'appartient plus au nom importé.
            KtTokens.AS_KEYWORD, KtTokens.SEMICOLON -> break@boucleJetons

            else -> {
                // MODIFIER_LIST, IMPORT_KEYWORD, PACKAGE_KEYWORD, blancs… : ignorés.
            }
        }
    }
    return morceaux.joinToString("").ifEmpty { null }
}
