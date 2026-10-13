package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.api.VueNomsXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.completion.CompletionXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * Règle `xml.tagInconnu` (XE4, LSP_XML.md §3 X4 — la couture
 * CONSERVATRICE par construction) : une balise de classe inconnue
 * n'est signalée que si le CATALOGUE plateforme, la VUE DE NOMS du
 * classpath (projet compris — l'index fusion porte les classes du
 * projet) et le DOM sont TOUS d'accord pour dire qu'elle n'existe pas.
 *
 * Garde X4 : vue de noms absente (index froid) ou catalogue absent →
 * SILENCE — jamais de « classe inconnue » à chaud ; les balises
 * SPÉCIALES du layout et les balises pleinement QUALIFIÉES inconnues
 * du classpath restent couvertes par la vue de noms.
 */
internal object ReglesTagInconnu {
    /**
     * Diagnostique les balises inconnues d'un document — ordre du
     * parcours ; la SESSION trie par position.
     *
     * @param document DOM du layout.
     * @param catalogue catalogue plateforme (balises connues du SDK).
     * @param vueNoms vue de noms du classpath (`null` = garde active).
     */
    internal fun diagnostiquer(
        document: DocumentXml,
        catalogue: CataloguePlateforme?,
        vueNoms: VueNomsXml?,
    ): List<DiagnosticXml> {
        if (catalogue == null || vueNoms == null) return emptyList()
        val diagnostics = mutableListOf<DiagnosticXml>()
        for (racine in document.racines) {
            sonder(racine, catalogue, vueNoms, diagnostics)
        }
        return diagnostics
    }

    /** Sonne un élément (récursivement) : balise de classe inconnue ? */
    private fun sonder(
        element: XmlElement,
        catalogue: CataloguePlateforme,
        vueNoms: VueNomsXml,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val candidate = estBaliseDeClasse(element)
        if (candidate && !connue(element, catalogue, vueNoms)) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.TAG_INCONNU,
                    severite = SeveriteXml.ERREUR,
                    message = "balise « ${element.nom} » : classe introuvable (SDK et classpath)",
                    debut = element.plageNom.debut,
                    fin = element.plageNom.fin,
                ),
            )
        }
        for (enfant in element.enfants) {
            sonder(enfant, catalogue, vueNoms, diagnostics)
        }
    }

    /** Balise CANDIDATE à la vérification de classe (pas une spéciale). */
    private fun estBaliseDeClasse(element: XmlElement): Boolean =
        element.nom !in CompletionXml.BALISES_SPECIALES_NOMS && !element.nom.contains('.')

    /**
     * La balise est-elle CONNUE (catalogue par nom simple, ou vue de
     * noms — une classe du classpath portant ce nom simple existe) ?
     */
    private fun connue(
        element: XmlElement,
        catalogue: CataloguePlateforme,
        vueNoms: VueNomsXml,
    ): Boolean =
        catalogue.chaineDe(element.nom) != null ||
            vueNoms.nomsCommencantPar(element.nom, LIMITE_PROBE).isNotEmpty()

    /** Une seule réponse suffit (garde minimale de la couture). */
    private const val LIMITE_PROBE: Int = 1
}
