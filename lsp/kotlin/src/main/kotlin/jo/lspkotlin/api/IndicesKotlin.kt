package jo.lspkotlin.api

/**
 * Types NEUTRES des indices en ligne (`textDocument/inlayHint`, L2 —
 * plan 1.3.0) : annotations fantômes rendues par l'éditeur en texte
 * estompé — noms de paramètres aux appels, types certains aux
 * déclarations sans type écrit.
 */

/**
 * Genre d'un indice en ligne — pilote le rendu côté éditeur (le label
 * d'un nom de paramètre se termine par `:`, celui d'un type commence
 * par `:` ; l'hôte ajoute les séparateurs, le moteur rend le TEXTE
 * SEUL).
 */
public enum class GenreIndiceKotlin {
    /** Nom de paramètre d'un appel positionnel (`nom =` avant l'argument). */
    PARAMETRE,

    /** Type CERTAIN d'une déclaration sans type écrit (`: Int` après le nom). */
    TYPE,
}

/**
 * Un indice en ligne — décalages dans le texte du document, position
 * et libellé PRÊTS À AFFICHER (l'hôte ajoute les séparateurs de rendu
 * selon le [genre], la position LSP est convertie par ses soins).
 *
 * @property debut décalage de début du point d'ancrage (inclus) —
 *           l'argument d'un appel (le label se rend AVANT lui), le nom
 *           d'une déclaration (le label se rend APRÈS lui).
 * @property fin décalage de fin du point d'ancrage (exclusif).
 * @property libelle texte de l'indice (`nom`, `Int`) — SANS séparateur.
 * @property genre nature de l'indice (paramètre / type).
 */
public data class IndiceEnLigneKotlin(
    public val debut: Int,
    public val fin: Int,
    public val libelle: String,
    public val genre: GenreIndiceKotlin,
)
