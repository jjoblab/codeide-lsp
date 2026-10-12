package jo.lspkotlin.parse

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.parser.prepareProduction
import java.security.MessageDigest

/**
 * Élément d'un arbre syntaxique Kotlin reconstruit : soit un jeton (feuille
 * issue du lexer), soit un nœud (production du parseur).
 *
 * L'algorithme de reconstruction depuis la liste plate de marqueurs du
 * `SyntaxTreeBuilder` est adapté des tests amont du compilateur
 * (`compiler/multiplatform-parsing/jvm/test/.../NewTestParser.kt`,
 * Apache 2.0 — cf. THIRD_PARTY_NOTICES) : marqueurs de début empilés,
 * marqueurs de fin dépilés avec leurs enfants, jetons intercalaires émis en
 * feuilles, marqueurs d'erreur dédupliqués.
 */
public sealed interface ElementSyntaxique {
    /** Type de l'élément (type de jeton ou type de nœud, vocabulaire KtNodeTypes/KtTokens). */
    public val type: SyntaxElementType

    /** Décalage de début dans le texte source (inclus). */
    public val debut: Int

    /** Décalage de fin dans le texte source (exclus). */
    public val fin: Int
}

/**
 * Jeton (feuille) : `IDENTIFIER`, `LBRACE`, `CLASS_KEYWORD`… Le texte du
 * jeton est une tranche du texte source analysé.
 */
public class JetonSyntaxique(
    override val type: SyntaxElementType,
    override val debut: Int,
    override val fin: Int,
    /** Texte exact du jeton dans la source. */
    public val texte: CharSequence,
) : ElementSyntaxique

/**
 * Nœud (production du parseur) : `KT_FILE`, `CLASS`, `FUN`… Porte ses
 * enfants (jetons et nœuds mélangés, dans l'ordre du document).
 */
public class NoeudSyntaxique(
    override val type: SyntaxElementType,
    override val debut: Int,
    override val fin: Int,
    /** Enfants dans l'ordre du document (jetons et nœuds). */
    public val enfants: List<ElementSyntaxique>,
    /**
     * Vrai si ce nœud est un marqueur d'erreur du parseur (fragment non
     * reconnu par la grammaire — édition en cours ou syntaxe fausse).
     * Philosophie D3 (LSP_KOTLIN §3) : l'arbre est TOUJOURS construit,
     * même sur du texte invalide ; les erreurs sont transportées, jamais
     * fatales.
     */
    public val marqueErreur: Boolean = false,
    /** Message d'erreur du parseur pour un nœud d'erreur, sinon null. */
    public val messageErreur: String? = null,
) : ElementSyntaxique {
    /** Enfants qui sont des nœuds (productions), pas des jetons. */
    public val sousNoeuds: List<NoeudSyntaxique>
        get() = enfants.filterIsInstance<NoeudSyntaxique>()

    /** Jetons feuilles immédiats de ce nœud. */
    public val jetonsDirects: List<JetonSyntaxique>
        get() = enfants.filterIsInstance<JetonSyntaxique>()

    /** Tous les jetons du sous-arbre, dans l'ordre du document (profondeur d'abord). */
    public fun jetonsDescendants(): List<JetonSyntaxique> {
        val resultat = mutableListOf<JetonSyntaxique>()
        fun parcourir(n: NoeudSyntaxique) {
            n.enfants.forEach { enfant ->
                when (enfant) {
                    is JetonSyntaxique -> resultat.add(enfant)
                    is NoeudSyntaxique -> parcourir(enfant)
                }
            }
        }
        parcourir(this)
        return resultat
    }
}

/**
 * Arbre syntaxique complet d'une source Kotlin, produit par
 * [ParseurKotlin]. Immuable : chaque analyse reconstruit un arbre neuf
 * (le re-parse incrémental est un sujet postérieur — K2+).
 */
public class ArbreSyntaxiqueKotlin internal constructor(
    /** Nœud racine (type `KtNodeTypes.KT_FILE` en mode fichier). */
    public val racine: NoeudSyntaxique,
    /** Nombre de jetons émis par le lexer. */
    public val nombreJetons: Int,
    /** Nombre total de marqueurs d'erreur dans l'arbre. */
    public val nombreErreurs: Int,
) {
    /**
     * Dump structurel déterministe (une ligne par élément, en profondeur
     * d'abord) : `TYPE debut fin [E]` pour les nœuds (le suffixe `[E]`
     * marque une erreur), `TYPE debut fin` pour les jetons. Base du
     * digeste de parité — la FORME seule compte, pas les textes.
     */
    public fun dumpStructurel(): String = buildString {
        fun ElementSyntaxique.ecrire(prefix: String) {
            when (this) {
                is JetonSyntaxique -> appendLine("$prefix$type $debut $fin")
                is NoeudSyntaxique -> {
                    val erreur = if (marqueErreur) " [E]" else ""
                    appendLine("$prefix$type$erreur $debut $fin")
                    enfants.forEach { it.ecrire("$prefix  ") }
                }
            }
        }
        racine.ecrire("")
    }

    /**
     * Empreinte SHA-256 du dump structurel : deux textes qui produisent le
     * même arbre (aux textes de jetons près) ont le même digeste. C'est
     * l'instrument de la suite de parité sur corpus.
     */
    public fun digeste(): String {
        val condense = MessageDigest.getInstance("SHA-256")
        condense.update(dumpStructurel().toByteArray(Charsets.UTF_8))
        return condense.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reconstruction d'un arbre depuis le builder post-parse. Adaptation des
 * tests amont Apache 2.0 (cf. [ElementSyntaxique]) : la liste des
 * productions est plate et ordonnée ; un marqueur « fait » ferme le nœud
 * courant, un marqueur de début en ouvre un, les jetons entre deux
 * marqueurs deviennent des feuilles du nœud courant.
 */
internal object ReconstructeurArbre {
    /** Construit l'arbre depuis un builder déjà consommé par le parseur Kotlin vendoré. */
    public fun construire(builder: SyntaxTreeBuilder): ArbreSyntaxiqueKotlin {
        val resultat = prepareProduction(builder)
        val etat = EtatReconstruction(resultat.tokenSequence)
        for (index in 0 until resultat.productionMarkers.size) {
            etat.traiter(resultat.productionMarkers, index)
        }
        return etat.arbre()
    }

    /**
     * État d'une reconstruction : pile des enfants en cours, index du
     * dernier jeton émis, déduplication des marqueurs d'erreur.
     */
    private class EtatReconstruction(private val jetons: com.intellij.platform.syntax.lexer.TokenList) {
        private val pile = ArrayDeque<MutableList<ElementSyntaxique>>()
        private var indexJetonPrecedent = 0
        private var dernierIndexErreur = -1
        private var nombreErreurs = 0

        init {
            pile.addLast(mutableListOf())
        }

        /** Traite la production d'index [index] : fermeture, erreur ou ouverture. */
        public fun traiter(
            productions: com.intellij.platform.syntax.parser.ProductionMarkerList,
            index: Int,
        ) {
            val production = productions.getMarker(index)
            when {
                productions.isDoneMarker(index) -> fermer(production)
                production.isErrorMarker() -> marquerErreur(production)
                else -> ouvrir(production)
            }
        }

        /** Marqueur de fin : dépile les enfants, émet les jetons restants, accroche le nœud au parent. */
        private fun fermer(production: com.intellij.platform.syntax.parser.SyntaxTreeBuilder.Production) {
            val enfantsFermes = pile.removeLast()
            val enfants: List<ElementSyntaxique>
            if (production.isCollapsed()) {
                // Production repliée : ses jetons sont absorbés, le nœud
                // est opaque (les enfants ne sont pas exposés).
                indexJetonPrecedent = production.getEndTokenIndex()
                enfants = emptyList()
            } else {
                enfantsFermes.ajouterJetons(production.getEndTokenIndex())
                enfants = enfantsFermes.toList()
            }
            pile.lastOrNull()?.add(noeudDe(production, enfants, production.isErrorMarker()))
        }

        /** Marqueur d'erreur : dédupliqués par index de jeton (parité avec l'amont). */
        private fun marquerErreur(production: com.intellij.platform.syntax.parser.SyntaxTreeBuilder.Production) {
            val indexErreur = production.getStartTokenIndex()
            val courant = pile.lastOrNull()
            if (indexErreur == dernierIndexErreur || courant == null) return
            dernierIndexErreur = indexErreur
            nombreErreurs += 1
            courant.ajouterJetons(indexErreur)
            courant.add(noeudDe(production, emptyList(), true))
        }

        /** Marqueur de début : les jetons jusqu'à lui appartiennent au parent, puis on empile. */
        private fun ouvrir(production: com.intellij.platform.syntax.parser.SyntaxTreeBuilder.Production) {
            pile.lastOrNull()?.ajouterJetons(production.getStartTokenIndex())
            pile.addLast(mutableListOf())
        }

        /** Termine : la pile doit s'être réduite à une racine unique. */
        public fun arbre(): ArbreSyntaxiqueKotlin {
            val racines = pile.removeLast()
            check(pile.isEmpty()) { "pile de productions non vide après reconstruction : ${pile.size} niveaux" }
            check(racines.size == 1) {
                "reconstruction attend une racine unique, trouve ${racines.size}"
            }
            val racine = racines.single() as NoeudSyntaxique
            return ArbreSyntaxiqueKotlin(
                racine = racine,
                nombreJetons = jetons.tokenCount,
                nombreErreurs = nombreErreurs,
            )
        }

        /**
         * Émet en feuilles les jetons non encore consommés jusqu'à
         * [dernier] (exclusif) ; les jetons vides (longueur nulle, ex.
         * DANGLING_NEWLINE) sont ignorés — l'amont fait de même.
         */
        private fun MutableList<ElementSyntaxique>.ajouterJetons(dernier: Int) {
            for (indice in indexJetonPrecedent until dernier) {
                val jeton = jetonA(indice) ?: continue
                add(jeton)
            }
            indexJetonPrecedent = dernier
        }

        /** Jeton d'index [indice], ou null si vide/absent (à ignorer). */
        private fun jetonA(indice: Int): JetonSyntaxique? {
            val type = jetons.getTokenType(indice) ?: return null
            val debut = jetons.getTokenStart(indice)
            val fin = jetons.getTokenEnd(indice)
            return if (debut == fin) {
                // Jeton vide (ex. DANGLING_NEWLINE) : ignoré, comme l'amont.
                null
            } else {
                JetonSyntaxique(
                    type = type,
                    debut = debut,
                    fin = fin,
                    texte = jetons.getTokenText(indice) ?: "",
                )
            }
        }
    }
}

/** Fabrique un nœud depuis une production fermée du builder. */
private fun noeudDe(
    production: com.intellij.platform.syntax.parser.SyntaxTreeBuilder.Production,
    enfants: List<ElementSyntaxique>,
    marqueErreur: Boolean,
): NoeudSyntaxique =
    NoeudSyntaxique(
        type = production.getNodeType(),
        debut = production.getStartOffset(),
        fin = production.getEndOffset(),
        enfants = enfants,
        marqueErreur = marqueErreur,
        messageErreur = production.getErrorMessage(),
    )
