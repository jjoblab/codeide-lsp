package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.completion.CompletionXml
import jo.lspxml.context.Contexte
import jo.lspxml.context.ContexteCurseur
import jo.lspxml.parse.ParseurXml
import jo.lspxml.rules.GenreDocument
import jo.lspxml.rules.ReglesStructure
import java.util.concurrent.ConcurrentHashMap

/**
 * Implémentation de [jo.lspxml.api.SessionLangageXml] (XE1,
 * LSP_XML.md §4.1 « session ») — documents qui entrent, diagnostics
 * neutres qui sortent ; le moteur ne connaît pas LSP4J, l'hôte ne
 * connaît pas le parseur (X2).
 *
 * Tolérance (X1) : aucune méthode ne lève sur du texte invalide — le
 * parseur est tolérant par construction, les erreurs voyagent comme
 * diagnostics. Réouvrir un document ouvert le REMPLACE ; modifier un
 * document inconnu équivaut à l'ouvrir ; fermer un document inconnu est
 * sans effet.
 *
 * Performance (cadrage §6) : la re-parse COMPLÈTE est le budget
 * incrémental à l'échelle visée (< 10 ms pour 1 500 lignes, un seul
 * passage mémoire bornée par l'arbre) ; les diagnostics ne sont JAMAIS
 * mis en cache (le coût est un parcours d'arbre, la source est là).
 *
 * @param parseur parseur tolérant — PARTAGÉ par les sessions du moteur
 *        (sans état entre deux appels, sûr par construction).
 * @param catalogues cache de catalogues de plateforme PARTAGÉ par le
 *        moteur (XE2 : chargé une fois par plateforme, §4.2) — la
 *        session y consulte la plateforme de SON classpath.
 */
internal class SessionLangageXmlImpl(
    private val parseur: ParseurXml,
    private val catalogues: CataloguesPartages = CataloguesPartages(),
) : jo.lspxml.api.SessionLangageXml {
    /** Documents ouverts : URI → dernier texte + DOM. */
    private val documents = ConcurrentHashMap<String, DocumentOuvert>()

    /** Classpath courant (XE2/XE3 le consomment ; XE1 le garde). */
    @Volatile
    private var classpath: ClasspathXml? = null

    @Volatile
    private var fermee = false

    override val nombreDocuments: Int get() = documents.size

    override fun ouvrirDocument(
        uri: String,
        texte: CharSequence,
    ) {
        if (fermee) return
        documents[uri] = DocumentOuvert(texte.toString(), parseur.analyser(texte))
    }

    override fun modifierDocument(
        uri: String,
        texte: CharSequence,
    ) {
        // Modifier un document inconnu ÉQUIVAUT à l'ouvrir (contrat de
        // la session, l'hôte LSP4J ne garantit pas l'ordre).
        ouvrirDocument(uri, texte)
    }

    override fun fermerDocument(uri: String) {
        documents.remove(uri)
    }

    override fun diagnostics(uri: String): List<DiagnosticXml> {
        val document = documents[uri] ?: return emptyList()
        val genre = GenreDocument.depuisUri(uri)
        val diagnostics = mutableListOf<DiagnosticXml>()
        // Recouvrements du parseur : la SYNTAXE, gravité ERREUR.
        document.document.recuperations.forEach { recuperation ->
            diagnostics.add(
                DiagnosticXml(
                    code = recuperation.code,
                    severite = SeveriteXml.ERREUR,
                    message = recuperation.message,
                    debut = recuperation.plage.debut,
                    fin = recuperation.plage.fin,
                ),
            )
        }
        diagnostics.addAll(ReglesStructure.diagnostiquer(document.document, genre))
        // Publication : ordre du document (stable — le recouvrement
        // précède les règles au même décalage par construction du
        // parcours ; même décalage = même cause).
        return diagnostics.sortedBy { it.debut }
    }

    override fun definirClasspath(classpath: ClasspathXml) {
        // Tolérant : après close c'est un NO-OP ; un appel identique
        // est un no-op chaud (le catalogue XE2 se charge par empreinte).
        if (fermee) return
        this.classpath = classpath
    }

    /**
     * Classpath courant (SONDAGE de test — XE2/XE3 le consommeront
     * pour le catalogue plateforme et l'index ressources).
     */
    internal fun classpathCourant(): ClasspathXml? = classpath

    /**
     * Complétion à [decalage] (XE2) — le scanner de contexte tranche la
     * ZONE, les contributeurs fusionnent : balises (catalogue
     * plateforme) et attributs (styleables de la chaîne + layout du
     * parent + tools:). XE2 couvre le genre LAYOUT seul (values et
     * manifest suivent XE4) — les autres genres sous-rapportent (X4).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
     * sont des RÉGIMES distincts (session fermée, genre hors périmètre
     * XE2, document inconnu) — chacun porte sa décision de silence.
     */
    @Suppress("ReturnCount")
    override fun completion(
        uri: String,
        decalage: Int,
    ): List<PropositionCompletionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val genre = GenreDocument.depuisUri(uri)
        if (genre != GenreDocument.LAYOUT) return emptyList()
        val catalogue = catalogues.catalogue(classpath?.dossierPlateforme)
        return when (val contexte = ContexteCurseur.analyser(document.texte, decalage, document.document)) {
            is Contexte.NomDeBalise -> CompletionXml.balises(contexte, catalogue)

            is Contexte.NomAttribut -> CompletionXml.attributs(contexte, catalogue)

            else -> emptyList()
        }
    }

    override fun close() {
        if (fermee) return
        fermee = true
        documents.clear()
        classpath = null
    }

    /** Document ouvert : texte intégral + DOM analysé. */
    private class DocumentOuvert(
        val texte: String,
        val document: jo.lspxml.parse.DocumentXml,
    )
}
