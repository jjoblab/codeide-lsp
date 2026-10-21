package jo.lspkotlin.resolve

import jo.lspkotlin.api.GenreIndiceKotlin
import jo.lspkotlin.api.IndiceEnLigneKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtProperty
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Indices en ligne (`textDocument/inlayHint`, L2 — plan 1.3.0) :
 * annotations fantômes en texte estompé — noms de PARAMÈTRES aux
 * appels positionnels, types CERTAINS aux déclarations sans type
 * écrit.
 *
 * ## Noms de paramètres (preuve par la résolution)
 * Un appel ne rend des indices QUE si la candidate est PROUVÉE par
 * la chaîne de l'aide à la signature ([SignaturesAppels.nomsParametres]
 * — même conservatisme : sans candidate d'arité compatible, AUCUN
 * indice) ; un argument NOMMÉ porte déjà son nom (jamais d'indice),
 * et seuls les arguments POSITIONNELS en tête sont numérotés (les
 * nommés ne consomment pas de position).
 *
 * ## Types certains (jamais devinés)
 * Une déclaration sans type écrit ne rend un indice QUE si son
 * initialiseur porte un type par LUI-MÊME : littéral canonique
 * (`42` → `Int`, `1.5` → `Double`, `1.5f` → `Float`, `true` →
 * `Boolean`, `'c'` → `Char`, `"…"` → `String`, `1L` → `Long`) ou
 * constructeur RÉSOLU en classe du projet par la chaîne de navigation
 * de types ([TypesKotlin]). Un suffixe non modélisé (`u`, `UL`), une
 * expression composée, une référence : AUCUN indice (D3).
 */
internal object IndicesKotlin {
    /** Jetons blancs (filtre des littéraux). */
    private val blancs =
        setOf(KtTokens.WHITE_SPACE, KtTokens.EOL_OR_SEMICOLON, KtTokens.SEMICOLON)

    /**
     * Contexte d'indices : les DEUX contextes de résolution du moteur
     * (signatures pour les appels, interrogations pour les types) —
     * la session les construit sur le MÊME état (nommage, résolveur,
     * déclarations projet, index, vue).
     */
    internal data class Contexte(
        val signatures: SignaturesAppels.Contexte,
        val interrogations: InterrogationsSymboles.Contexte,
    )

    /**
     * Indices de la plage [debutPlage, finPlage) — ordonnés par
     * position ; liste VIDE pour une plage vide.
     */
    internal fun indices(
        racine: NoeudSyntaxique,
        uri: String,
        debutPlage: Int,
        finPlage: Int,
        contexte: Contexte,
    ): List<IndiceEnLigneKotlin> {
        if (debutPlage >= finPlage) return emptyList()
        val resultat = mutableListOf<IndiceEnLigneKotlin>()
        parcourir(racine) { noeud ->
            if (noeud.debut < finPlage && noeud.fin > debutPlage) {
                when (noeud.type) {
                    KtNodeTypes.CALL_EXPRESSION ->
                        resultat.addAll(indicesAppel(racine, noeud, contexte.signatures))
                    KtNodeTypes.PROPERTY ->
                        resultat.addAll(indiceType(racine, noeud, uri, contexte.interrogations))
                    else -> {}
                }
            }
        }
        return resultat.sortedBy { indice -> indice.debut }
    }

    /** Parcours récursif des nœuds de l'arbre. */
    private fun parcourir(
        noeud: NoeudSyntaxique,
        visite: (NoeudSyntaxique) -> Unit,
    ) {
        visite(noeud)
        noeud.sousNoeuds.forEach { parcourir(it, visite) }
    }

    /**
     * Indices de noms de paramètres d'un appel positionnel prouvé.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — le break des arguments NOMMÉS et celui de l'épuisement des
     * positions sont deux issues distinctes du contrat positionnel.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun indicesAppel(
        racine: NoeudSyntaxique,
        appel: NoeudSyntaxique,
        contexteSignatures: SignaturesAppels.Contexte,
    ): List<IndiceEnLigneKotlin> {
        // Seuls les PARAMÈTRES sont portés par l'appel — l'indice de
        // TYPE d'une initialisation constructeur vit sur la DÉCLARATION
        // (indiceType ci-dessous), pas sur l'appel.
        val noms = SignaturesAppels.nomsParametres(racine, appel, contexteSignatures) ?: return emptyList()
        val resultat = mutableListOf<IndiceEnLigneKotlin>()
        var position = 0
        for (argument in SignaturesAppels.argumentsOrdinaires(appel)) {
            // Un argument NOMMÉ porte déjà son nom — et clôt la liste
            // des positionnels (ils précèdent les nommés en Kotlin).
            if (estNomme(argument)) break
            val nom = noms.getOrNull(position) ?: break
            if (nom.isNotEmpty()) {
                resultat.add(IndiceEnLigneKotlin(argument.debut, argument.fin, nom, GenreIndiceKotlin.PARAMETRE))
            }
            position += 1
        }
        return resultat
    }

    /** Un argument est-il NOMMÉ (`nom = valeur`) ? */
    private fun estNomme(argument: NoeudSyntaxique): Boolean {
        val jetons = argument.jetonsDescendants().filter { it.type !in blancs }
        return jetons.size >= 2 &&
            jetons[0].type == KtTokens.IDENTIFIER &&
            jetons[1].type == KtTokens.EQ
    }

    /**
     * Indice de TYPE d'une déclaration sans type écrit — seulement si
     * l'initialiseur porte le type par lui-même (littéral canonique
     * ou constructeur prouvé).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (type écrit, nom, initialiseur, type certain) est une ISSUE
     * distincte du conservatisme D3.
     */
    @Suppress("ReturnCount")
    private fun indiceType(
        racine: NoeudSyntaxique,
        proprieteNoeud: NoeudSyntaxique,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): List<IndiceEnLigneKotlin> {
        val propriete = KtProperty(proprieteNoeud)
        // Type écrit : l'indice n'apporterait rien (déjà visible).
        if (propriete.type() != null) return emptyList()
        val nom = propriete.nomJeton() ?: return emptyList()
        val initialisateur = propriete.initialisateur()?.noeud ?: return emptyList()
        val libelle = typeCertain(racine, initialisateur, uri, contexte) ?: return emptyList()
        return listOf(
            IndiceEnLigneKotlin(nom.debut, nom.fin, libelle, GenreIndiceKotlin.TYPE),
        )
    }

    /**
     * Type CERTAIN porté par l'initialiseur LUI-MÊME — littéral
     * canonique ou constructeur résolu ; `null` sinon (jamais deviné).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (littéral, appel, preuve de constructeur) est une ISSUE
     * distincte (rien n'est deviné).
     */
    @Suppress("ReturnCount")
    private fun typeCertain(
        racine: NoeudSyntaxique,
        initialisateur: NoeudSyntaxique,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): String? {
        typeLitteral(initialisateur)?.let { return it }
        // Constructeur : la classe construite, PROUVÉE par la chaîne de
        // navigation de types (un appel dont le nom ne résout pas en
        // classe du projet ne rend AUCUN indice).
        if (initialisateur.type == KtNodeTypes.CALL_EXPRESSION) {
            val cible =
                TypesKotlin.typeEnContexte(racine, initialisateur.debut, uri, contexte) ?: return null
            return cible.nom
        }
        return null
    }

    /**
     * Type d'un littéral CANONIQUE seul — `null` pour toute autre
     * forme.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (vide, chaîne, jeton unique, par type de littéral) est une
     * ISSUE distincte du catalogue.
     */
    @Suppress("ReturnCount")
    private fun typeLitteral(initialisateur: NoeudSyntaxique): String? {
        val jetons = initialisateur.jetonsDescendants().filter { it.type !in blancs }
        if (jetons.isEmpty()) return null
        // Chaîne (le délimiteur ouvrant en tête) : String, template
        // compris — le type d'une chaîne est TOUJOURS String.
        if (jetons.first().type == KtTokens.OPEN_QUOTE) return "String"
        if (jetons.size != 1) return null
        val jeton = jetons.single()
        val texte = jeton.texte.toString()
        return when (jeton.type) {
            KtTokens.INTEGER_LITERAL -> typeEntier(texte)
            KtTokens.FLOAT_LITERAL ->
                if (texte.endsWith("f") || texte.endsWith("F")) "Float" else "Double"
            KtTokens.TRUE_KEYWORD, KtTokens.FALSE_KEYWORD -> "Boolean"
            KtTokens.CHARACTER_LITERAL -> "Char"
            else -> null
        }
    }

    /**
     * Type d'un littéral entier : `1` → Int, `1L` → Long ; les
     * suffixes NON SIGNÉS (`u`, `UL`…) ne sont pas modélisés → `null`
     * (D3 — jamais deviné).
     */
    private fun typeEntier(texte: String): String? =
        when {
            texte.endsWith("uL") || texte.endsWith("UL") ||
                texte.endsWith("u") || texte.endsWith("U") -> null
            texte.endsWith("L") -> "Long"
            else -> "Int"
        }
}
