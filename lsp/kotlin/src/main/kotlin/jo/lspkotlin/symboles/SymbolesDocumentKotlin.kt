package jo.lspkotlin.symboles

import jo.lspkotlin.api.GenreSymboleKotlin
import jo.lspkotlin.api.SymboleDocumentKotlin
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtEnumEntry
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.KtObjectDeclaration
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.KtTypeAlias
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Génération de la liste `textDocument/documentSymbol` pour Kotlin
 * (S1 du plan LSP_KOTLIN.md §3.2) — miroir AST → DocumentSymbols
 * hiérarchiques.
 *
 * Marche l'arbre des [KtDeclaration] via les accesseurs existants
 * (`KtFile.declarations`, `KtClass.membres`, et nos extensions locales
 * pour `KtObjectDeclaration.membres`) : l'arbre LSP est strictement
 * l'arbre AST, les enfants d'un symbole sont exactement les
 * déclarations contenues dans son corps.
 *
 * Plages :
 * - selectionRange (debutNom/finNom) = le JETON IDENTIFIER du nom.
 * - range (debutTotal/finTotal) = la production syntaxique complète
 *   (du `class`/`fun`/`val` au `}` fermant, ou `;`/newline pour les
 *   propriétés top-level). Vient de [KtElement.debut] / [KtElement.fin].
 *
 * Détail : la signature courte (`(a: Int): String`, `: String`,
 * `: Base`). Tirée des accesseurs de [KtDeclaration] — `null` si non
 * pertinent.
 *
 * Tolérance (D3) : un document vide / malformé rend une liste VIDE
 * (jamais d'exception — un arbre vide a zéro déclarations top-level).
 */
internal object SymbolesDocumentKotlin {
    /**
     * Génère la liste des symboles de PREMIER NIVEAU d'un fichier
     * Kotlin (les déclarations portées par [KtFile.declarations] —
     * classes, fonctions, propriétés, objets, typealias).
     */
    internal fun depuis(fichier: KtFile): List<SymboleDocumentKotlin> =
        fichier.declarations().map { declaration -> convertir(declaration) }

    /**
     * Convertit une déclaration Kotlin en symbole LSP — la récursion
     * suit les enfants naturels de chaque type :
     * - [KtClass] → membres (fonctions, propriétés, classes imbriquées).
     * - [KtObjectDeclaration] → membres (idem).
     * - [KtFunction] → pas d'enfants nommés (le corps est opaque).
     * - [KtEnumEntry] → pas d'enfants (les énumérations sont plates).
     * - [KtProperty] / [KtTypeAlias] → pas d'enfants.
     */
    private fun convertir(declaration: KtDeclaration): SymboleDocumentKotlin {
        val nomJeton = declaration.nomJeton()
        val nom = nomJeton?.texte?.toString() ?: "<anonyme>"
        val debutNom = nomJeton?.debut ?: declaration.debut
        val finNom = nomJeton?.fin ?: declaration.debut
        val (genre, enfants) = genreEtEnfants(declaration)
        val detail = detailDe(declaration)
        return SymboleDocumentKotlin(
            nom = nom,
            detail = detail,
            genre = genre,
            debutNom = debutNom,
            finNom = finNom,
            debutTotal = declaration.debut,
            finTotal = declaration.fin,
            enfants = enfants.map { enfant -> convertir(enfant) },
        )
    }

    /**
     * Genre LSP + enfants naturels d'une déclaration.
     *
     * - [KtClass] : CLASSE ou INTERFACE selon [KtClass.estInterface] ;
     *   enfants = [KtClass.membres].
     * - [KtObjectDeclaration] : OBJET ; enfants = membres (compagnon ou non).
     * - [KtFunction] : FONCTION ; pas d'enfants (le corps est opaque).
     * - [KtProperty] : PROPRIETE ; pas d'enfants.
     * - [KtEnumEntry] : ENTREE_ENUM ; pas d'enfants.
     * - [KtTypeAlias] : ALIAS ; pas d'enfants.
     */
    private fun genreEtEnfants(declaration: KtDeclaration): Pair<GenreSymboleKotlin, List<KtDeclaration>> =
        when (declaration) {
            is KtClass -> {
                val genre = if (declaration.estInterface()) GenreSymboleKotlin.INTERFACE else GenreSymboleKotlin.CLASSE
                Pair(genre, declaration.membres())
            }
            is KtObjectDeclaration -> Pair(GenreSymboleKotlin.OBJET, declaration.membres())
            is KtFunction -> Pair(GenreSymboleKotlin.FONCTION, emptyList())
            is KtProperty -> Pair(GenreSymboleKotlin.PROPRIETE, emptyList())
            is KtEnumEntry -> Pair(GenreSymboleKotlin.ENTREE_ENUM, emptyList())
            is KtTypeAlias -> Pair(GenreSymboleKotlin.ALIAS, emptyList())
            else -> Pair(GenreSymboleKotlin.OBJET, emptyList())
        }

    /**
     * Détail (signature courte) d'une déclaration :
     * - fonction : `(a: Int, b: String): Boolean` (paramètres + retour).
     * - propriété : `: String` (type déclaré).
     * - classe : `: Base` (premier super-type), ou `null` si aucun.
     * - enum entry : `null`.
     * - typealias : `null` (le type sous-jacent n'est pas exposé par la façade).
     */
    private fun detailDe(declaration: KtDeclaration): String? =
        when (declaration) {
            is KtFunction -> detailFonction(declaration)
            is KtProperty -> declaration.type()?.let { ": $it" }
            is KtClass -> declaration.superTypes().firstOrNull()?.let { ": $it" }
            else -> null
        }

    /** Détail d'une fonction : `(a: Int): String` (null si sans paramètres et sans retour). */
    private fun detailFonction(fonction: KtFunction): String? {
        val params = fonction.parametres().mapNotNull { parametre ->
            val nomParam = parametre.nom()
            val typeParam = parametre.type()
            if (nomParam != null && typeParam != null) "$nomParam: $typeParam" else null
        }.joinToString(", ")
        val retour = fonction.typeRetour()
        return when {
            retour == null && params.isEmpty() -> null
            retour == null -> "($params)"
            params.isEmpty() -> ": $retour"
            else -> "($params): $retour"
        }
    }
}

/**
 * Extension privée : membres d'un objet (compagnon ou non) — miroir de
 * `KtClass.membres()` mais pour `KtObjectDeclaration` qui n'expose pas
 * encore l'accesseur (le corps `CLASS_BODY` est identique).
 */
private fun KtObjectDeclaration.membres(): List<KtDeclaration> =
    noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.CLASS_BODY }
        ?.sousNoeuds?.mapNotNull { enfant ->
            when (enfant.type) {
                KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION, KtNodeTypes.FUN,
                KtNodeTypes.PROPERTY, KtNodeTypes.TYPEALIAS, KtNodeTypes.ENUM_ENTRY,
                KtNodeTypes.SECONDARY_CONSTRUCTOR, KtNodeTypes.CLASS_INITIALIZER,
                -> jo.lspkotlin.parse.kt.KtElement.depuis(enfant) as? KtDeclaration
                else -> null
            }
        } ?: emptyList()
