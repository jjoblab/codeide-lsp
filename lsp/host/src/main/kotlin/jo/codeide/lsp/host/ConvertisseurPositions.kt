package jo.codeide.lsp.host

import org.eclipse.lsp4j.Position

/**
 * Convertisseur offset↔position LSP avec cache — KP13 / XP15.
 *
 * **Problème résolu** : les conversions `positionDe(offset, texte)` et
 * `decalageDe(position, texte)` parcourent le texte caractère par caractère
 * (O(n) à chaque appel). Un fichier de 1500 lignes avec 50 diagnostics =
 * 100 conversions O(n) = 150 000 itérations. Le cache pré-calcule les
 * décalages de début de ligne (O(n) une seule fois) puis répond en O(log n)
 * par recherche binaire.
 *
 * **Cache** : construit paresseusement sur le premier appel, invalidé quand
 * le texte change (didChange → nouveau document → nouveau convertisseur).
 * La structure est un tableau `lignesDebut: IntArray` où `lignesDebut[i]` =
 * décalage du premier caractère de la ligne `i`.
 *
 * **Thread-safety** : les conversions sont appelées depuis le thread LSP4J
 * (sérialisé par LSP4J). Pas de concurrence.
 */
internal class ConvertisseurPositions private constructor(
    private val texte: CharSequence,
    private val lignesDebut: IntArray,
) {
    /** Longueur du texte couvert (bornage des décalages). */
    public val longueur: Int get() = texte.length

    /**
     * Convertit un décalage en position LSP (ligne, colonne).
     *
     * Recherche binaire dans `lignesDebut` → O(log n) au lieu de O(n).
     */
    fun positionDe(decalage: Int): Position {
        val borne = decalage.coerceIn(0, texte.length)
        // Recherche binaire : trouver la dernière ligne dont le début <= borne.
        var bas = 0
        var haut = lignesDebut.size - 1
        while (bas < haut) {
            val milieu = (bas + haut + 1) ushr 1
            if (lignesDebut[milieu] <= borne) {
                bas = milieu
            } else {
                haut = milieu - 1
            }
        }
        return Position(bas, borne - lignesDebut[bas])
    }

    /**
     * Convertit une position LSP en décalage.
     *
     * Accès direct `lignesDebut[ligne]` → O(1) au lieu de O(n).
     */
    fun decalageDe(position: Position): Int {
        val ligne = position.line.coerceIn(0, lignesDebut.size - 1)
        val debutLigne = lignesDebut[ligne]
        return (debutLigne + position.character).coerceIn(0, texte.length)
    }

    public companion object {
        /**
         * Crée un convertisseur pour [texte]. Le scan des lignes se fait
         * UNE FOIS (O(n)) — les conversions suivantes sont O(log n) / O(1).
         */
        public fun pour(texte: CharSequence): ConvertisseurPositions {
            val debuts = mutableListOf(0)
            for (index in 0 until texte.length) {
                if (texte[index] == '\n') {
                    debuts.add(index + 1)
                }
            }
            return ConvertisseurPositions(texte, debuts.toIntArray())
        }
    }
}
