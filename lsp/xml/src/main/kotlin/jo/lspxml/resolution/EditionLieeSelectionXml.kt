package jo.lspxml.resolution

import jo.lspxml.api.PlageSelectionXml
import jo.lspxml.api.ChaineSelectionXml
import jo.lspxml.api.PlageLieeXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement

/**
 * ÉDITION LIÉE des balises (`textDocument/linkedEditingRange`, lot L3
 * du plan 1.3.0) et PLAGE DE SÉLECTION (`textDocument/selectionRange`,
 * lot L3) — deux navigations DOM pures.
 *
 * ## Édition liée
 * Le curseur sur le NOM (ouvrante ou fermante) d'un élément NON
 * auto-fermé rend les DEUX plages des noms : modifier l'une modifie
 * l'autre côté client. Hors nom (attribut, texte, auto-fermante) :
 * `null` — jamais de liaison inventée (D3).
 *
 * ## Plage de sélection
 * Pour une position, la chaîne des nœuds englobants du PLUS PROFOND
 * (l'attribut ou le texte sous le curseur) au FICHIER ENTIER —
 * l'expansion progressive de la sélection. Chaque maillon englobe
 * STRICTEMENT le précédent (les productions aux mêmes frontières
 * fusionnent) ; une position hors arbre rend le fichier seul.
 */
internal object EditionLieeSelectionXml {
    /**
     * Plages LIÉES du nom de balise sous [decalage] — l'ouvrante et la
     * fermante du même élément ; `null` hors nom d'élément fermé.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue du balayage (auto-fermante, fermante absente, nom
     * de fermante illisible) sont des états DISTINCTS du DOM.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun plageLiee(
        document: DocumentXml,
        decalage: Int,
    ): PlageLieeXml? {
        for (element in elementsDe(document)) {
            if (element.autoFermante || element.plageFermante == null) continue
            val nomFermante = plagesNomFermante(element) ?: continue
            // Curseur sur le NOM de l'ouvrante ou sur le nom de la fermante.
            val surOuvrante = couvre(element.plageNom, decalage)
            val surFermante = couvre(nomFermante, decalage)
            if (surOuvrante || surFermante) {
                return PlageLieeXml(
                    PlageSelectionXml(element.plageNom.debut, element.plageNom.fin),
                    PlageSelectionXml(nomFermante.debut, nomFermante.fin),
                )
            }
        }
        return null
    }

    /** Plage du NOM dans la balise fermante (`</nom>`). */
    private fun plagesNomFermante(element: XmlElement): Plage? {
        val fermante = element.plageFermante ?: return null
        // `</nom…` : le nom suit « </ » — même longueur que le nom ouvrant.
        val debut = fermante.debut + 2
        val fin = debut + element.nom.length
        return Plage(debut, fin)
    }

    /** La plage couvre-t-elle le décalage (bornes inclusives ?) — curseur DANS le nom. */
    private fun couvre(
        plage: Plage,
        decalage: Int,
    ): Boolean = decalage >= plage.debut && decalage <= plage.fin

    /**
     * Chaîne des nœuds englobants de [decalage] — du plus profond au
     * FICHIER ENTIER, strictement croissante (fusion des plages égales).
     */
    internal fun chaine(
        document: DocumentXml,
        decalage: Int,
    ): ChaineSelectionXml {
        val plages = mutableListOf<Plage>()
        // Attribut sous le curseur (le maillon le plus profond) : la
        // plage du NOM de l'attribut (plage englobante = l'élément).
        for (element in elementsDe(document)) {
            for (attribut in element.attributs) {
                if (couvre(attribut.plageNom, decalage)) {
                    plages.add(attribut.plageNom)
                    break
                }
            }
        }
        // Éléments englobants, du plus profond au premier.
        for (element in elementsDe(document)) {
            val plage = element.plageOuvrante
            if (decalage >= plage.debut && decalage <= plage.fin) {
                plages.add(plage)
            }
        }
        // Le FICHIER ENTIER ferme la chaîne.
        plages.add(Plage(0, document.longueur))
        // Fusion des frontières égales (deux productions aux mêmes
        // bornes ne font qu'un maillon) et ordre DU PLUS PROFOND AU
        // PLUS ENGINTEGRANT (début décroissant — le fichier ferme la
        // marche), contrat de ChaineSelectionXml.
        val fusionnees = mutableListOf<Plage>()
        for (plage in plages.sortedByDescending { it.debut }) {
            if (fusionnees.isEmpty() || plage != fusionnees.last()) {
                fusionnees.add(plage)
            }
        }
        return ChaineSelectionXml(fusionnees.map { PlageSelectionXml(it.debut, it.fin) })
    }

    /** Tous les éléments du document, en FLUX plat (miroir session). */
    private fun elementsDe(document: DocumentXml): Sequence<XmlElement> =
        document.racines.asSequence().flatMap { sequenceOf(it) + it.descendants().asSequence() }
}
