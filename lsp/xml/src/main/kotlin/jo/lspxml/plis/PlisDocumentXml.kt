package jo.lspxml.plis

import jo.lspxml.api.PlagePliableXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * Génération de la liste `textDocument/foldingRange` (XP11 — plan de
 * stabilisation LSP §3.3 S2) : chaque balise fermée est UNE plage
 * pliable, du DÉBUT de la balise ouvrante à la FIN de la fermante.
 *
 * Le DOM porte déjà tout : [XmlElement.plageOuvrante] et
 * [XmlElement.plageFermante] — aucune sémantique de genre (layout/
 * values/manifest) : TOUS les genres se plient de la même façon, le
 * XML est le langage naturellement pliable (LemMinX même patron).
 *
 * Tolérance (X1) : un document vide/malformé rend une liste VIDE
 * (jamais d'exception) ; les auto-fermantes et les éléments en édition
 * ne produisent RIEN (pas de fermante à replier) ; les fermantes
 * implicites de reprise (XP14 — le DOM porte leur plage) se plient
 * comme les explicites.
 */
internal object PlisDocumentXml {
    /**
     * Plages pliables du document, en ORDRE DU DOCUMENT (profondeur
     * d'abord — l'ouvrante précède ses enfants, chaque fermeture
     * clôt sa propre plage).
     */
    internal fun depuis(document: DocumentXml): List<PlagePliableXml> {
        val plages = mutableListOf<PlagePliableXml>()
        document.racines.forEach { racine -> visiter(racine, plages) }
        return plages
    }

    /** Marche récursive : l'élément d'abord, ses enfants ensuite. */
    private fun visiter(
        element: XmlElement,
        plages: MutableList<PlagePliableXml>,
    ) {
        element.plageFermante?.let { fermante ->
            plages += PlagePliableXml(element.plageOuvrante.debut, fermante.fin)
        }
        element.enfants.forEach { enfant -> visiter(enfant, plages) }
    }
}
