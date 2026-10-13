package jo.lspxml.rules

import jo.lspxml.api.DiagnosticXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement

/**
 * RÈGLES DE STRUCTURE pures sur le DOM (XE1, LSP_XML.md §4.1 « rules »,
 * décision X4 : motif projet de référence) — ORCHESTRATEUR : parcours l'arbre,
 * accumule les contextes, délègue aux catalogues PAR GENRE
 * ([ReglesGeneriques], [ReglesLayout], [ReglesValues]) ; les
 * diagnostics ne signalent que ce qu'ils SAVENT, la couture absente =
 * silence (jamais de faux positif).
 *
 * Les diagnostics de RECOUVREMENT du parseur (balise non fermée…)
 * ne sont PAS produits ici — ils vivent dans le [DocumentXml] et la
 * session les convertit (même gravité ERREUR).
 */
public object ReglesStructure {
    /**
     * Diagnostique un document pour son [genre] — parcours de l'arbre,
     * ordre du document ; la SESSION trie par position avant publication.
     */
    public fun diagnostiquer(
        document: DocumentXml,
        genre: GenreDocument,
    ): List<DiagnosticXml> {
        val diagnostics = mutableListOf<DiagnosticXml>()
        val contextes = mutableListOf<Contexte>()
        for (indice in document.racines.indices) {
            visiter(document.racines[indice], genre, diagnostics, contextes, estPremiereRacine = indice == 0)
        }
        ReglesGeneriques.regleEntitesTexte(document, diagnostics)
        // XE5 : l'exigence xmlns:android s'étend aux drawables/menus
        // (un `android:shape=` sans déclaration est la même ERREUR de
        // gonfleur) ; les ids @+id/ dupliqués gardent le layout et le
        // MENU (les ids d'items de menu sont des vrais ids).
        if (genre == GenreDocument.LAYOUT ||
            genre == GenreDocument.DRAWABLE ||
            genre == GenreDocument.MENU
        ) {
            ReglesLayout.regleXmlnsAndroid(document, diagnostics)
        }
        if (genre == GenreDocument.LAYOUT || genre == GenreDocument.MENU) {
            ReglesLayout.regleIdsDupliques(contextes, diagnostics)
        }
        if (genre == GenreDocument.VALEURS) {
            ReglesValues.regleNomsDupliques(contextes, diagnostics)
        }
        return diagnostics
    }

    /** Parcours d'un élément : règles génériques + délégation par genre. */
    private fun visiter(
        element: XmlElement,
        genre: GenreDocument,
        diagnostics: MutableList<DiagnosticXml>,
        contextes: MutableList<Contexte>,
        estPremiereRacine: Boolean,
    ) {
        ReglesGeneriques.regleAttributDuplique(element, diagnostics)
        ReglesGeneriques.regleEntitesValeurs(element, diagnostics)
        contextes.add(Contexte(element))
        when (genre) {
            GenreDocument.LAYOUT -> {
                ReglesLayout.regleTaillesManquantes(element, diagnostics)
                ReglesLayout.regleIncludeSansLayout(element, diagnostics)
                ReglesLayout.regleMergeNonRacine(element, estPremiereRacine, diagnostics)
            }

            GenreDocument.VALEURS -> ReglesValues.regleEntreeSansNom(element, diagnostics)

            GenreDocument.MANIFEST,
            GenreDocument.DRAWABLE,
            GenreDocument.MENU,
            GenreDocument.AUTRE -> Unit
        }
        element.enfants.forEach { enfant ->
            visiter(enfant, genre, diagnostics, contextes, estPremiereRacine = false)
        }
    }
}

/**
 * Contexte de règle accumulé par élément (ids de layout, entrées
 * nommées de values) — les règles de FICHIER (duplicata) consomment
 * les contextes APRÈS le parcours complet.
 */
internal class Contexte(
    val element: XmlElement,
) {
    /** Plage de l'identifiant `@+id/…` porté par l'élément (null sinon). */
    internal val plageId: Plage? =
        element.attribut("android:id")?.takeIf { it.valeur?.startsWith("@+id/") == true }?.plageValeur

    /** Nom (`name`) d'une entrée de values portée (null sinon). */
    internal val nomEntree: String? = element.attribut("name")?.valeur
}
