package jo.codeide.lsp.host

import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Serveur LSP4J du moteur Kotlin (K2, LSP_KOTLIN.md §4.1 « session », §4.4
 * protocole) — adaptateur MINCE : le protocole vit ici, la langue vit dans
 * la [SessionLangageKotlin] du moteur (types neutres, D6 : le moteur ne
 * connaît pas LSP4J, l'hôte ne connaît pas la grammaire).
 *
 * Cycle de vie : une instance par CONNEXION (le supplier de la définition
 * cel-lsp fabrique, [SessionLspKotlin.serveur]) — initialize → initialized
 * → didOpen/didChange/didClose → shutdown → exit. Le client est injecté
 * par le transport in-process (cel-lsp appelle `connect` par réflexion,
 * même contrat que `JavaLanguageServer`).
 *
 * Capacités déclarées (honnêtes, LSP 3.17) : synchronisation de document
 * COMPLÈTE + publication de diagnostics — RIEN d'autre à K2 (la complétion,
 * le hover et la définition arrivent K3 ; l'éditeur cèle sur les
 * capacités, les méthodes LSP4J non surchargées restent les défauts).
 *
 * Les notifications tardives (entre shutdown et exit) sont tolérées : la
 * session moteur les ignore déjà (contrat K2), ce serveur en fait autant.
 *
 * @param session la session moteur de CETTE connexion (fermée à
 *        shutdown/exit — jamais avant).
 * @param versionServeur version du moteur (serverInfo, traçabilité).
 * @param aLArret rappel de décompte à l'arrêt du serveur (la façade
 *        [SessionLspKotlin] retire la session des vivantes — contrat
 *        « 0 ressource orpheline »).
 */
@Suppress("TooManyFunctions")
internal class KotlinLanguageServer(
    private val session: SessionLangageKotlin,
    private val versionServeur: String,
    private val aLArret: () -> Unit = {},
) : LanguageServer {
    /** Client distant (injecté par le transport). */
    @Volatile
    private var client: LanguageClient? = null

    /** État du cycle LSP (garde des journaux, pas de rejets durs K2). */
    @Volatile
    private var etat: EtatCycle = EtatCycle.NON_INITIALISE

    /** Textes LSP courants (offsets moteur → positions LSP, versions). */
    private val documents = ConcurrentHashMap<String, DocumentLsp>()

    /** Service de documents (didOpen/didChange/didClose/didSave). */
    private val serviceDocuments: TextDocumentService = ServiceDocuments()

    /** Service d'espace de travail (notifications vides à K2). */
    private val serviceEspace: WorkspaceService = ServiceEspace()

    /** Connecté par le transport in-process (réflexion cel-lsp). */
    public fun connect(client: LanguageClient) {
        this.client = client
    }

    // ── LanguageServer ──────────────────────────────────────────────────

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val dossiers = params.workspaceFolders?.size ?: 0
        LOGGER.info { "initialize: workspaceFolders=$dossiers (K2, mono-espace)" }
        etat = EtatCycle.INITIALISE
        val capacites = ServerCapabilities()
        capacites.setTextDocumentSync(TextDocumentSyncKind.Full)
        val result = InitializeResult(capacites)
        val info = ServerInfo()
        info.name = NOM_SERVEUR
        info.version = versionServeur
        result.serverInfo = info
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized(params: org.eclipse.lsp4j.InitializedParams) {
        LOGGER.fine { "initialized" }
    }

    override fun shutdown(): CompletableFuture<Any> {
        LOGGER.info { "shutdown — fermeture de la session moteur" }
        etat = EtatCycle.ARRETE
        session.close()
        aLArret()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        LOGGER.info { "exit" }
        etat = EtatCycle.SORTI
        // Ceinture et bretelles : exit sans shutdown (client brutal) —
        // session.close() est idempotente, aLArret() aussi (no-op si déjà
        // retirée).
        session.close()
        aLArret()
    }

    override fun getTextDocumentService(): TextDocumentService = serviceDocuments

    override fun getWorkspaceService(): WorkspaceService = serviceEspace

    // ── Publication ─────────────────────────────────────────────────────

    /**
     * Publie les diagnostics du document [uri] : traduction offsets →
     * positions LSP (UTF-16), plages VIDES élargies d'un caractère quand
     * le texte le permet (un marqueur en fin de texte doit se VOIR), puis
     * push LSP au client.
     */
    private fun publier(uri: String) {
        val clientCourant = client ?: return
        val document = documents[uri]
        val diagnostics =
            document?.let { traduire(session.diagnostics(uri), it.texte) } ?: emptyList()
        val params = PublishDiagnosticsParams(uri, diagnostics)
        document?.version?.let { params.version = it }
        try {
            clientCourant.publishDiagnostics(params)
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "publication diagnostics échouée ($uri)", t)
        }
    }

    /** Traduit les diagnostics neutres en diagnostics LSP. */
    private fun traduire(
        diagnostics: List<DiagnosticKotlin>,
        texte: String,
    ): List<Diagnostic> =
        diagnostics.map { diagnostic ->
            val debut = positionDe(diagnostic.debut, texte)
            val fin = positionDe(diagnostic.fin, texte)
            val plage =
                if (fin == debut && diagnostic.fin < texte.length) {
                    Range(debut, positionDe(diagnostic.fin + 1, texte))
                } else {
                    Range(debut, fin)
                }
            Diagnostic(
                plage,
                diagnostic.message,
                severiteLspDe(diagnostic.severite),
                NOM_SERVEUR,
                diagnostic.code,
            )
        }

    /** Position LSP (ligne, colonne UTF-16) d'un décalage du texte. */
    private fun positionDe(
        decalage: Int,
        texte: String,
    ): Position {
        val borne = decalage.coerceIn(0, texte.length)
        var ligne = 0
        var debutLigne = 0
        for (index in 0 until borne) {
            if (texte[index] == '\n') {
                ligne += 1
                debutLigne = index + 1
            }
        }
        return Position(ligne, borne - debutLigne)
    }

    /** Gravité LSP d'une gravité neutre. */
    private fun severiteLspDe(severite: SeveriteKotlin): DiagnosticSeverity =
        when (severite) {
            SeveriteKotlin.ERREUR -> DiagnosticSeverity.Error
            SeveriteKotlin.AVERTISSEMENT -> DiagnosticSeverity.Warning
        }

    // ── Services ────────────────────────────────────────────────────────

    /** Document LSP courant : texte (pour les positions) + version. */
    private class DocumentLsp(
        public val texte: String,
        public val version: Int?,
    )

    /** État du cycle LSP du serveur. */
    private enum class EtatCycle {
        NON_INITIALISE,
        INITIALISE,
        ARRETE,
        SORTI,
    }

    /**
     * Service de documents : didOpen/didChange/didClose branchés sur la
     * session moteur, publication après chaque frappe ; didSave sans
     * effet (les diagnostics vivent au fil de l'eau). Les requêtes
     * (complétion, hover…) héritent des DÉFAUTS LSP4J — non déclarées
     * dans les capacités, l'éditeur ne les émet pas.
     */
    private inner class ServiceDocuments : TextDocumentService {
        override fun didOpen(params: DidOpenTextDocumentParams) {
            val document = params.textDocument
            val uri = document.uri
            documents[uri] = DocumentLsp(document.text, document.version)
            session.ouvrirDocument(uri, document.text, estScript(uri))
            publier(uri)
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            val uri = params.textDocument.uri
            val version = params.textDocument.version
            val courant = documents[uri]
            // Version obsolète (frappe arrivée en désordre) : ignorée —
            // la dernière gagne, comme la validation atomique de lsp:java.
            if (version != null && courant?.version != null && version < courant.version!!) return
            var texte = courant?.texte ?: ""
            for (changement in params.contentChanges) {
                val plage = changement.range
                texte =
                    if (plage == null) {
                        // Synchronisation complète (capacité déclarée).
                        changement.text
                    } else {
                        // Changement par plage (client non conforme) :
                        // appliqué au texte courant, jammer d'exception.
                        appliquerPlage(texte, plage, changement.text)
                    }
            }
            documents[uri] = DocumentLsp(texte, version)
            session.modifierDocument(uri, texte, estScript(uri))
            publier(uri)
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            val uri = params.textDocument.uri
            documents.remove(uri)
            session.fermerDocument(uri)
            // Vidange LSP : les squiggles partent avec le document.
            try {
                client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "vidange diagnostics échouée ($uri)", t)
            }
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            // Rien : les diagnostics vivent au didChange.
        }

        /** Extension de script (`.kts`) → mode script du parseur. */
        private fun estScript(uri: String): Boolean = uri.endsWith(".kts", ignoreCase = true)

        /** Applique un changement par plage (client non conforme, robustesse). */
        private fun appliquerPlage(
            texte: String,
            plage: Range,
            remplacement: String,
        ): String {
            val debut = decalageDe(plage.start, texte)
            val fin = decalageDe(plage.end, texte)
            return StringBuilder(texte).replace(debut, fin.coerceAtLeast(debut), remplacement).toString()
        }

        /** Décalage d'une position LSP dans le texte (best effort borné). */
        private fun decalageDe(
            position: Position,
            texte: String,
        ): Int {
            var decalage = 0
            var ligne = 0
            while (ligne < position.line && decalage < texte.length) {
                if (texte[decalage] == '\n') ligne += 1
                decalage += 1
            }
            return (decalage + position.character).coerceIn(0, texte.length)
        }
    }

    /** Service d'espace : notifications acceptées, sans effet à K2. */
    private inner class ServiceEspace : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            // K2 : aucune configuration d'espace consommée (documentation
            // future : langage du projet).
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            // K2 : les documents ouverts vivent au didChange, pas aux
            // événements de fichiers.
        }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(KotlinLanguageServer::class.java.name)

        /** Nom du serveur (serverInfo, source des diagnostics). */
        private const val NOM_SERVEUR: String = "codeide-kotlin"
    }
}
