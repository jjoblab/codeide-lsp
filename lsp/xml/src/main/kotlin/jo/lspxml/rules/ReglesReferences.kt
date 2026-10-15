package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement
import jo.lspxml.resolution.ReferencesXml
import jo.lspxml.resources.IndexRessources

/**
 * Règle `xml.referenceNonResolue` (XE3, LSP_XML.md §4.4 — « référence
 * `@color/…`/`@string/…` non résolue (projet + framework), jamais
 * pendant que l'index est froid — garde dumb mode ») : une COUTURE
 * (elle interroge l'index projet et le catalogue plateforme), pas une
 * règle pure du DOM.
 *
 * Garde X4 : `@+id/…` DÉCLARE (jamais signalé) ; `@android:…` n'est
 * signalé que si le catalogue PROUVE l'absence (public-final.xml) ;
 * index froid (aucune racine scannée) → SILENCE TOTAL.
 */
internal object ReglesReferences {
    /**
     * Diagnostique les références non résolues d'un document —
     * l'ordre est celui du parcours ; la SESSION trie par position.
     */
    internal fun diagnostiquer(
        document: DocumentXml,
        index: IndexRessources,
        catalogue: CataloguePlateforme?,
    ): List<DiagnosticXml> {
        if (!index.estChaud) return emptyList()
        val diagnostics = mutableListOf<DiagnosticXml>()
        for (racine in document.racines) {
            sonder(racine, index, catalogue, diagnostics)
        }
        return diagnostics
    }

    /** Sonne un élément : chaque valeur de référence est éprouvée.
     *  XP2 : les attributs `tools:*` et `xmlns:*` sont IGNORÉS — aapt
     *  ne les traite pas comme des références réelles (ils servent au
     *  design-time uniquement). */
    private fun sonder(
        element: XmlElement,
        index: IndexRessources,
        catalogue: CataloguePlateforme?,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        for (attribut in element.attributs) {
            // XP2 : ignorer les attributs tools:* et xmlns:* — ils ne
            // sont pas des références réelles pour aapt.
            if (attribut.prefixe == "tools" || attribut.prefixe == "xmlns") continue
            val reference = referenceDEntree(attribut)
            if (reference != null && !ReferencesXml.estResolue(reference, index, catalogue)) {
                diagnostics.add(
                    DiagnosticXml(
                        code = CodesDiagnosticsXml.REFERENCE_NON_RESOLUE,
                        // XP1 : downgrade en WARNING tant que les
                        // ressources de dépendances (AAR) ne sont pas
                        // indexées — un ERROR sur @style/Theme.Material3
                        // est un faux positif.
                        severite = SeveriteXml.AVERTISSEMENT,
                        message =
                            "référence « ${reference.type}/${reference.nom} » " +
                                "introuvable (projet et framework)",
                        debut = reference.plageNom.debut,
                        fin = reference.plageNom.fin,
                    ),
                )
            }
        }
        for (enfant in element.enfants) {
            sonder(enfant, index, catalogue, diagnostics)
        }
    }

    /** Référence PORTÉE par un attribut (forme @ ou ?), sinon `null`. */
    private fun referenceDEntree(attribut: jo.lspxml.parse.XmlAttribut): ReferencesXml.Reference? {
        val valeur = attribut.valeur
        val plage = attribut.plageValeur
        val candidate = valeur != null && plage != null && estFormeReference(valeur)
        return if (candidate) {
            ReferencesXml.referenceSous(valeur!!, 0, plage!!)
        } else {
            null
        }
    }

    /** L'attribut porte-t-il une FORME de référence (`@…` / `?…`) ? */
    private fun estFormeReference(valeur: String): Boolean =
        valeur.startsWith("@") || valeur.startsWith("?")
}
