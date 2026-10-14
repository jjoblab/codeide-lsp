package jo.codeide.lsp.host

import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.api.SeveriteXml
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Serveur LSP4J du moteur XML (XE1, LSP_XML.md §4.1 « session », §4.2
 * pipeline) — adaptateur MINCE : le protocole vit ici, la langue vit
 * dans la [SessionLangageXml] du moteur (types neutres, X2 : le moteur
 * ne connaît pas LSP4J, l'hôte ne connaît pas le parseur).
 *
 * Cycle de vie : une instance par CONNEXION (le supplier de la définition
 * cel-lsp fabrique, [SessionLspXml.serveur]) — initialize → initialized
 * → didOpen/didChange/didClose → shutdown → exit. Le client est injecté
 * par le transport in-process (cel-lsp appelle `connect` par réflexion,
 * même contrat que `JavaLanguageServer`/`KotlinLanguageServer`).
 *
 * Capacités déclarées (honnêtes, LSP 3.17) : synchronisation de document
 * COMPLÈTE + publication de diagnostics — RIEN d'autre à XE1 (la
 * complétion arrive XE2, le survol/définition XE3 ; l'éditeur cèle sur
 * les capacités, les méthodes LSP4J non surchargées restent les défauts).
 *
 * Les notifications tardives (entre shutdown et exit) sont tolérées : la
 * session moteur les ignore déjà, ce serveur en fait autant.
 *
 * @param session la session moteur de CETTE connexion (fermée à
 *        shutdown/exit — jamais avant).
 * @param versionServeur version du moteur (serverInfo, traçabilité).
 * @param aLArret rappel de décompte à l'arrêt du serveur (la façade
 *        [SessionLspXml] retire la session des vivantes — contrat
 *        « 0 ressource orpheline »).
 */
@Suppress("TooManyFunctions")
internal class XmlLanguageServer(
    private val session: SessionLangageXml,
    private val versionServeur: String,
    private val aLArret: () -> Unit = {},
) : LanguageServer {
    /** Client distant (injecté par le transport). */
    @Volatile
    private var client: LanguageClient? = null

    /** Textes LSP courants (offsets moteur → positions LSP, versions). */
    private val documents = ConcurrentHashMap<String, DocumentLsp>()

    /** Service de documents (didOpen/didChange/didClose/didSave). */
    private val serviceDocuments: TextDocumentService = ServiceDocuments()

    /** Service d'espace de travail (notifications vides à XE1). */
    private val serviceEspace: WorkspaceService = ServiceEspace()

    /** Connecté par le transport in-process (réflexion cel-lsp). */
    public fun connect(client: LanguageClient) {
        this.client = client
    }

    // ── LanguageServer ──────────────────────────────────────────────────

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val dossiers = params.workspaceFolders?.size ?: 0
        LOGGER.info { "initialize: workspaceFolders=$dossiers (XE1, mono-espace)" }
        val capacites = ServerCapabilities()
        capacites.setTextDocumentSync(TextDocumentSyncKind.Full)
        // XE2 (LSP_XML §4.4) : complétion (déclencheurs `<` (nouvelle
        // balise), `=` (valeur) et `:` (espace de noms)) — balises du
        // catalogue plateforme, attributs de la chaîne de classes.
        // Honnête : rien d'autre n'est déclaré (le survol et la
        // définition arrivent XE3).
        val completion = CompletionOptions()
        completion.triggerCharacters = listOf("<", "=", ":")
        completion.resolveProvider = false
        capacites.setCompletionProvider(completion)
        // XE3 (LSP_XML §4.4) : SURVOL (référence @type/nom, classe de
        // balise, format d'attribut) et DÉFINITION (saut au
        // res/values déclarant). Honnête : rien d'autre.
        capacites.setHoverProvider(true)
        capacites.setDefinitionProvider(true)
        // XE4 : QUICK-FIXES (codeAction — insertion xmlns:android,
        // renommage « did you mean » des balises inconnues).
        capacites.setCodeActionProvider(true)
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
        LOGGER.info { "shutdown — fermeture de la session moteur XML" }
        session.close()
        aLArret()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        LOGGER.info { "exit" }
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
            LOGGER.log(Level.WARNING, "publication diagnostics XML échouée ($uri)", t)
        }
    }

    /** Traduit les diagnostics neutres en diagnostics LSP. */
    private fun traduire(
        diagnostics: List<DiagnosticXml>,
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
    private fun severiteLspDe(severite: SeveriteXml): DiagnosticSeverity =
        when (severite) {
            SeveriteXml.ERREUR -> DiagnosticSeverity.Error
            SeveriteXml.AVERTISSEMENT -> DiagnosticSeverity.Warning
        }

    // ── Services ────────────────────────────────────────────────────────

    /** Document LSP courant : texte (pour les positions) + version. */
    private class DocumentLsp(
        public val texte: String,
        public val version: Int?,
    )

    /**
     * Service de documents : didOpen/didChange/didClose branchés sur la
     * session moteur, publication après chaque frappe ; didSave sans
     * effet (les diagnostics vivent au fil de l'eau). La COMPLÉTION
     * (XE2) traduit position→offset, consulte le moteur, traduit les
     * propositions neutres en CompletionItems (kind, détail, sortText
     * — le tri total voyage dans la clé, motif du LSP Java/Kotlin).
     */
    private inner class ServiceDocuments : TextDocumentService {
        override fun didOpen(params: DidOpenTextDocumentParams) {
            val document = params.textDocument
            val uri = document.uri
            documents[uri] = DocumentLsp(document.text, document.version)
            session.ouvrirDocument(uri, document.text)
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
                        // appliqué au texte courant, jamais d'exception.
                        appliquerPlage(texte, plage, changement.text)
                    }
            }
            documents[uri] = DocumentLsp(texte, version)
            session.modifierDocument(uri, texte)
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
                LOGGER.log(Level.WARNING, "vidange diagnostics XML échouée ($uri)", t)
            }
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            // XP3+XP7 : didSave republice les diagnostics — utile après
            // un push de classpath (rescan des ressources).
            publier(params.textDocument.uri)
        }

        /** Complétion au curseur (XE2) — traductions seules, moteur neutre. */
        override fun completion(
            params: CompletionParams,
        ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
            val uri = params.textDocument.uri
            val document = documents[uri]
            val texte = document?.texte ?: ""
            val decalage = decalageDe(params.position, texte)
            val propositions =
                try {
                    if (document != null) session.completion(uri, decalage) else emptyList()
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "complétion XML échouée ($uri)", t)
                    emptyList()
                }
            val items = propositions.map { proposition -> itemLspDe(proposition) }
            return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, items)))
        }

        /**
         * Quick-fixes (XE4) : pour chaque diagnostic `xml.*` de la
         * requête, le moteur rend des ÉDITIONS neutres (CorrectifXml) —
         * l'hôte les traduit en CodeAction (il possède le document
         * LSP, les positions UTF-16 sont locales).
         *
         * Exemption detekt ciblée (règle 16) : LoopWithTooMany-
         * JumpStatements — les gardes du balayage (code absent, code
         * hors moteur) sont des états DISTINCTS du protocole.
         */
        @Suppress("LoopWithTooManyJumpStatements")
        override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
            val uri = params.textDocument.uri
            val document = documents[uri]
            val actions = mutableListOf<CodeAction>()
            for (diagnostic in params.context.diagnostics) {
                val code = diagnostic.code?.left ?: continue
                if (!code.startsWith(PREFIXE_CODE)) continue
                val texte = document?.texte ?: ""
                val decalage = decalageDe(diagnostic.range.start, texte)
                val correctifs =
                    try {
                        if (document != null) session.correctifs(uri, decalage) else emptyList()
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "quick-fixes XML échoués ($uri)", t)
                        emptyList()
                    }
                correctifs.forEach { correctif ->
                    val editions =
                        correctif.editions.map { edition ->
                            TextEdit(
                                Range(
                                    positionDe(edition.debut, texte),
                                    positionDe(edition.fin, texte),
                                ),
                                edition.texte,
                            )
                        }
                    val action = CodeAction(correctif.titre)
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diagnostic)
                    action.edit = WorkspaceEdit(linkedMapOf(uri to editions))
                    actions.add(action)
                }
            }
            return CompletableFuture.completedFuture(
                actions.map { action -> Either.forRight<Command, CodeAction>(action) },
            )
        }

        /** Survol (XE3) : contenu texte simple, `null` si non résolu. */
        override fun hover(params: HoverParams): CompletableFuture<Hover?> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(null)
            val decalage = decalageDe(params.position, document.texte)
            val contenu =
                try {
                    session.survol(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "survol XML échoué ($uri)", t)
                    null
                }
            val resultat = contenu?.let { Hover(listOf(Either.forLeft(it.valeur))) }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Définition (XE3) : emplacements neutres traduits en Locations
         * LSP — la cible se relit sur disque (FUSE), illisible = omise.
         */
        override fun definition(
            params: DefinitionParams,
        ): CompletableFuture<Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyLeft())
            val decalage = decalageDe(params.position, document.texte)
            val emplacements =
                try {
                    session.definitions(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "définition XML échouée ($uri)", t)
                    emptyList()
                }
            val locations =
                emplacements.mapNotNull { emplacement ->
                    locationLspDe(emplacement)
                }
            return CompletableFuture.completedFuture(Either.forLeft(locations))
        }

        /** Version vide typée de la réponse définition. */
        private fun emptyLeft(): Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>> =
            Either.forLeft(emptyList())

        /**
         * Emplacement neutre → Location LSP : la cible se relit sur
         * disque (lecture bornée par requête — FUSE).
         *
         * Exemption detekt ciblée (règle 16) : SwallowedException — une
         * cible illisible = définition OMISE (X4 : jamais fausse),
         * l'exception est le signal, pas une perte.
         */
        @Suppress("SwallowedException")
        private fun locationLspDe(emplacement: jo.lspxml.api.EmplacementXml): Location? {
            val texteCible =
                try {
                    java.io.File(java.net.URI(emplacement.uri)).readText()
                } catch (t: Throwable) {
                    null
                }
            val texte = texteCible ?: return null
            return Location(
                emplacement.uri,
                Range(positionDe(emplacement.debut, texte), positionDe(emplacement.fin, texte)),
            )
        }

        /** Proposition neutre → CompletionItem LSP (kind, détail, tri). */
        private fun itemLspDe(proposition: PropositionCompletionXml): CompletionItem {
            val item = CompletionItem(proposition.libelle)
            item.kind = genreLspDe(proposition.genre)
            proposition.detail?.let { item.detail = it }
            item.sortText = proposition.texteTri
            return item
        }

        /** Genre neutre → CompletionItemKind. */
        private fun genreLspDe(genre: GenreCompletionXml): CompletionItemKind =
            when (genre) {
                GenreCompletionXml.BALISE -> CompletionItemKind.Class

                GenreCompletionXml.ATTRIBUT -> CompletionItemKind.Field

                GenreCompletionXml.VALEUR_ENUM,
                GenreCompletionXml.VALEUR_FANION,
                -> CompletionItemKind.EnumMember

                GenreCompletionXml.REFERENCE -> CompletionItemKind.Reference

                GenreCompletionXml.ENTREE_VALEURS -> CompletionItemKind.Value

                GenreCompletionXml.STYLE -> CompletionItemKind.Struct
            }

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

    /** Service d'espace : notifications acceptées, sans effet à XE1. */
    private inner class ServiceEspace : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            // XE1 : aucune configuration d'espace consommée.
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            // XP8 : republier les diagnostics des documents ouverts —
            // un fichier externe (ressource créée/modifiée) peut
            // changer la résolution des références XML.
            for (uri in documents.keys) {
                publier(uri)
            }
        }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(XmlLanguageServer::class.java.name)

        /** Nom du serveur (serverInfo, source des diagnostics). */
        private const val NOM_SERVEUR: String = "codeide-xml"

        /** Préfixe des codes de diagnostics QUICK-FIXABLES du moteur. */
        private const val PREFIXE_CODE: String = "xml."
    }
}
