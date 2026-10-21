package jo.lspkotlin.resolve

import jo.lspkotlin.api.ChaineSelectionKotlin
import jo.lspkotlin.api.PlageSelectionKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA

/**
 * Chaînes de sélection (`textDocument/selectionRange`, L2 — plan
 * 1.3.0) : pour une position, la chaîne des nœuds englobants, du
 * PLUS PROFONDE au FICHIER ENTIER — l'expansion progressive de la
 * sélection de l'éditeur.
 *
 * Chaque maillon englobe STRICTEMENT le précédent : deux productions
 * aux MÊMES frontières ne font qu'un maillon (fusion — l'utilisateur
 * ne verrait qu'une expansion sans effet). Une position hors des
 * enfants de l'arbre rend la chaîne du fichier SEUL (honnêteté D3 :
 * rien n'est inventé au-delà du document).
 */
internal object SelectionsKotlin {
    /**
     * Chaîne des nœuds englobants de [decalage] — maillons du plus
     * profond au fichier entier, strictement croissants.
     */
    internal fun chaine(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): ChaineSelectionKotlin {
        val plages = mutableListOf<PlageSelectionKotlin>()
        // chaineAncetresA : du fichier (tête) au plus profond (fin) —
        // la sélection s'expand en sens INVERSE (profond → fichier).
        for (ancetre in chaineAncetresA(racine, decalage).asReversed()) {
            val plage = PlageSelectionKotlin(ancetre.debut, ancetre.fin)
            // Fusion : un maillon aux frontières DÉJÀ rendues n'ajoute
            // rien (deux productions aux mêmes bornes ne font qu'un).
            if (plages.isEmpty() || plage != plages.last()) {
                plages.add(plage)
            }
        }
        return ChaineSelectionKotlin(plages)
    }
}
