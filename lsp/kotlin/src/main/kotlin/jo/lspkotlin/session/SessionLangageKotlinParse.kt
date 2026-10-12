package jo.lspkotlin.session

import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.diagnostics.DiagnosticsSyntaxiques
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.ParseurKotlin

/**
 * Session de langue du moteur vendoré (K2, LSP_KOTLIN.md §4.1
 * « session ») — dépositaire des documents ouverts : chaque didOpen/
 * didChange REPARSE intégralement (la synchronisation LSP est complète à
 * K2 ; le re-parse de sous-arbre est un sujet postérieur), chaque
 * consultation relit l'arbre.
 *
 * Le parse est celui du [ParseurKotlin] vendoré (tolérant : AUCUNE entrée
 * ne lève — cf. sa KDoc) ; les diagnostics sortent de
 * [DiagnosticsSyntaxiques] (purs, conservateurs D3).
 *
 * Tolérance de fin de vie (protocole LSP : des notifications peuvent
 * encore arriver entre shutdown et exit) : après [close], les mutations
 * sont des no-ops silencieux, [diagnostics] rend une liste vide,
 * [nombreDocuments] vaut 0 — la session ne retient RIEN (test de fuite).
 *
 * Concurrence : verrou interne — les callbacks LSP4J de l'hôte arrivent
 * sur son pool ; les opérations sont courtes (parse complet d'un
 * document), la sérialisation est invisible.
 */
internal class SessionLangageKotlinParse(
    private val parseur: ParseurKotlin,
) : SessionLangageKotlin {
    /** Verrou de l'état (documents + fermeture). */
    private val verrou = Any()

    /** Documents ouverts : uri → arbre du dernier texte connu. */
    private val documents = mutableMapOf<String, DocumentOuvert>()

    /** Fermée (shutdown/exit, fermeture de la façade hôte) ? */
    private var fermee = false

    /** Nombre de documents ouverts (observabilité, test de fuite). */
    override val nombreDocuments: Int
        get() = synchronized(verrou) { documents.size }

    override fun ouvrirDocument(
        uri: String,
        texte: CharSequence,
        script: Boolean,
    ) {
        synchronized(verrou) {
            if (fermee) return
            documents[uri] = DocumentOuvert(parseur.analyser(texte, script), script)
        }
    }

    override fun modifierDocument(
        uri: String,
        texte: CharSequence,
        script: Boolean,
    ) {
        // Tolérant : un didChange sans didOpen préalable équivaut à une
        // ouverture (l'hôte ordonnance, le moteur ne s'y fie pas).
        ouvrirDocument(uri, texte, script)
    }

    override fun fermerDocument(uri: String) {
        synchronized(verrou) {
            documents.remove(uri)
        }
    }

    override fun diagnostics(uri: String): List<DiagnosticKotlin> =
        synchronized(verrou) {
            val document = documents[uri] ?: return emptyList()
            DiagnosticsSyntaxiques.calculer(document.arbre)
        }

    override fun close() {
        synchronized(verrou) {
            fermee = true
            documents.clear()
        }
    }

    /** Document ouvert : arbre du dernier texte connu (+ mode script). */
    private class DocumentOuvert(
        public val arbre: ArbreSyntaxiqueKotlin,
        public val script: Boolean,
    )
}
