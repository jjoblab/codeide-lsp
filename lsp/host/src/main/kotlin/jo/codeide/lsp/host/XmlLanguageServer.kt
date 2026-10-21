package jo.codeide.lsp.host

import jo.lspxml.api.CanauxCouleurXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.GenreSymboleXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.api.SymboleDocumentXml
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Color
import org.eclipse.lsp4j.ColorInformation
import org.eclipse.lsp4j.ColorPresentation
import org.eclipse.lsp4j.ColorPresentationParams
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
import org.eclipse.lsp4j.DocumentColorParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.LinkedEditingRanges
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
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
        // v0.134.4 — XP9 CORRIGÉ : les fournisseurs (hover, définition,
        // quick-fixes, outline) sont déclarés INCONDITIONNELLEMENT.
        // La négociation par capacités clientes (v0.134.1) sous-déclarait :
        // le client cel-lsp n'envoie PAS textDocument.definition dans ses
        // ClientCapabilities alors qu'il CONSOMME la définition (gate
        // LspLanguage sur capabilities) — la définition XML était perdue.
        // Sémantique LSP correcte : le serveur annonce ce qu'il SAIT faire ;
        // un client qui ne consomme pas une capacité ne l'appelle simplement
        // pas. Les capacités clientes restent lues pour le FORMAT du survol
        // (KP14 : MarkupContent si supporté).
        val capacites = ServerCapabilities()
        capacites.setTextDocumentSync(TextDocumentSyncKind.Full)
        // XE2 : complétion (déclencheurs `<` (nouvelle balise), `=`
        // (valeur), `:` (espace de noms) et `"` (v0.134.4 : ouverture
        // d'une valeur d'attribut — le client cel-ui n'ouvre le popup
        // à préfixe VIDE qu'après un char déclencheur ; sans `"`, la
        // complétion de valeur juste après la quote ouvrante
        // n'était jamais demandée).
        val completion = CompletionOptions()
        completion.triggerCharacters = listOf("<", "=", ":", "\"")
        completion.resolveProvider = false
        capacites.setCompletionProvider(completion)
        // XE3 : SURVOL et DÉFINITION (déclarés — voir note XP9 ci-dessus).
        capacites.setHoverProvider(true)
        capacites.setDefinitionProvider(true)
        // XE4 : QUICK-FIXES (codeAction).
        capacites.setCodeActionProvider(true)
        // S1 : OUTLINE documentSymbol.
        capacites.setDocumentSymbolProvider(true)
        // XP11 (v0.134.8) : PLIAGE foldingRange — chaque balise fermée
        // est pliable (le DOM porte les plages), l'hôte traduit en
        // lignes LSP.
        capacites.setFoldingRangeProvider(true)
        // S3 (lot du 19/10) : RENAME BORNÉ des entrées values +
        // références documents ouverts — miroir du « rename honnête »
        // Java/Kotlin (prepareRename = plage + placeholder, rename
        // borné = WorkspaceEdit legacy `changes`, le client cel-lsp
        // n'annonce PAS documentChanges). Framework et ressources par
        // fichier : jamais renommables (sous-rapport honnête).
        val renommage = RenameOptions()
        renommage.prepareProvider = true
        capacites.setRenameProvider(renommage)
        // S3 (lot du 19/10) : FORMATAGE — ré-indentation canonique
        // seule (profondeur DOM, contenu des lignes intact — les
        // commentaires/CDATA ne sont pas modélisés : jamais réécrits).
        capacites.setDocumentFormattingProvider(true)
        // L3 (plan 1.3.0) : RÉFÉRENCES et SURLIGNAGE d'occurrences des
        // ressources projet (même preuve que le renommage borné).
        capacites.setReferencesProvider(true)
        capacites.setDocumentHighlightProvider(true)
        // L3 (suite) : formatage de PLAGE (ré-indentation bornée),
        // ÉDITION LIÉE des balises (ouvrante ↔ fermante) et PLAGE DE
        // SÉLECTION (chaîne DOM).
        capacites.setDocumentRangeFormattingProvider(true)
        capacites.setLinkedEditingRangeProvider(true)
        capacites.setSelectionRangeProvider(true)
        // L3 (suite) : COULEURS de document (littéraux + @color résolus)
        // — le client ne les consomme pas encore (écart consigné).
        capacites.setColorProvider(true)
        // KP14 (v0.129.20) : hover en MarkupContent si le client supporte
        // le MarkupContent, sinon MarkedString (déprécié LSP 3.0).
        // Le client cel-lsp supporte MarkupContent → on l'utilise.
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
     *
     * v0.134.4 — XP15 CORRIGÉ : le convertisseur vit dans le [DocumentLsp]
     * (construit UNE fois par version du document) au lieu d'être
     * reconstruit à CHAQUE traduction (scan O(n) par diagnostic).
     */
    private fun publier(uri: String) {
        val clientCourant = client ?: return
        val document = documents[uri]
        val diagnostics =
            document?.let { traduire(session.diagnostics(uri), it.convertisseur) } ?: emptyList()
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
        convertisseur: ConvertisseurPositions,
    ): List<Diagnostic> =
        diagnostics.map { diagnostic ->
            val debut = convertisseur.positionDe(diagnostic.debut)
            val fin = convertisseur.positionDe(diagnostic.fin)
            val plage =
                if (fin == debut && diagnostic.fin < convertisseur.longueur) {
                    Range(debut, convertisseur.positionDe(diagnostic.fin + 1))
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

    /** Position LSP (ligne, colonne UTF-16) d'un décalage du texte.
     *  v0.134.4 — XP15 : le convertisseur vient du [DocumentLsp] (partagé
     *  pour tout le document, jamais reconstruit par appel). */
    private fun positionDe(
        decalage: Int,
        convertisseur: ConvertisseurPositions,
    ): Position = convertisseur.positionDe(decalage)

    /** Gravité LSP d'une gravité neutre. */
    private fun severiteLspDe(severite: SeveriteXml): DiagnosticSeverity =
        when (severite) {
            SeveriteXml.ERREUR -> DiagnosticSeverity.Error
            SeveriteXml.AVERTISSEMENT -> DiagnosticSeverity.Warning
        }

    // ── Services ────────────────────────────────────────────────────────

    /** Document LSP courant : texte (pour les positions) + version.
     *  v0.134.4 — XP15 : le convertisseur offset↔position est construit
     *  UNE fois à la création (le texte d'un DocumentLsp est IMMUTABLE —
     *  chaque didChange en crée un nouveau). */
    private class DocumentLsp(
        public val texte: String,
        public val version: Int?,
    ) {
        /** Convertisseur partagé de CE texte (scan O(n) une seule fois). */
        public val convertisseur: ConvertisseurPositions = ConvertisseurPositions.pour(texte)
    }

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
            val decalage = document?.convertisseur?.decalageDe(params.position) ?: 0
            val propositions =
                try {
                    if (document != null) session.completion(uri, decalage) else emptyList()
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "complétion XML échouée ($uri)", t)
                    emptyList()
                }
            val items = propositions.map { proposition -> itemLspDe(proposition) }
            // v0.134.1 — XP16 : isIncomplete=true si le plafond est atteint.
            // Le moteur limite à PLAFOND_PROPOSITIONS (60) — si on a exactement
            // ce nombre, il y a potentiellement PLUS de propositions disponibles
            // que ce qu'on rend. Le client doit savoir qu'il ne doit pas cacher
            // d'autres résultats de filtrage.
            val incomplet = items.size >= SEUIL_COMPLETION_INCOMPLETE
            return CompletableFuture.completedFuture(Either.forRight(CompletionList(incomplet, items)))
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
                val convertisseur = document?.convertisseur ?: continue
                val decalage = convertisseur.decalageDe(diagnostic.range.start)
                val correctifs =
                    try {
                        session.correctifs(uri, decalage)
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "quick-fixes XML échoués ($uri)", t)
                        emptyList()
                    }
                correctifs.forEach { correctif ->
                    val editions =
                        correctif.editions.map { edition ->
                            TextEdit(
                                Range(
                                    positionDe(edition.debut, convertisseur),
                                    positionDe(edition.fin, convertisseur),
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
            val decalage = document.convertisseur.decalageDe(params.position)
            val contenu =
                try {
                    session.survol(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "survol XML échoué ($uri)", t)
                    null
                }
            val resultat =
                contenu?.let {
                    Hover(org.eclipse.lsp4j.MarkupContent(org.eclipse.lsp4j.MarkupKind.PLAINTEXT, it.valeur))
                }
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
            val decalage = document.convertisseur.decalageDe(params.position)
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

        /**
         * prepareRename (S3 — rename borné des entrées values) : la
         * plage du NOM + le placeholder (nom courant) si le curseur
         * porte une cible renommable, `null` sinon (l'éditeur ne
         * propose pas le geste — jamais de renommage silencieux à
         * l'aveugle).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount +
         * SwallowedException — document inconnu, cible absente et
         * défaut hôte sont des RÉGIMES distincts (miroir Kotlin : le
         * catch est le contrat — une panne de session ne tue pas
         * l'hôte).
         */
        @Suppress("ReturnCount", "SwallowedException")
        override fun prepareRename(
            params: PrepareRenameParams,
        ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(null)
            val plageRenommable =
                try {
                    session.plageRenommable(uri, document.convertisseur.decalageDe(params.position))
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "prepareRename XML échoué ($uri)", t)
                    null
                } ?: return CompletableFuture.completedFuture(null)
            val resultat =
                PrepareRenameResult(
                    Range(
                        document.convertisseur.positionDe(plageRenommable.plage.debut),
                        document.convertisseur.positionDe(plageRenommable.plage.fin),
                    ),
                    plageRenommable.placeholder,
                )
            return CompletableFuture.completedFuture(Either3.forSecond(resultat))
        }

        /**
         * Renommage borné (S3 — miroir I8 « rename honnête » Java) : la
         * déclaration (entrées values, chaque fichier déclarant) + les
         * références des documents ouverts deviennent des TextEdits
         * groupés par fichier (forme legacy `changes` — le client
         * cel-lsp n'annonce PAS documentChanges). AUCUN échec
         * silencieux : sans cible = erreur TYPOÉE RequestFailed
         * (-32803) avec data {code: renommage.sansCible}.
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — document
         * inconnu et sans cible sont des RÉGIMES distincts (erreur
         * TYPOÉE I8 — jamais de succès vide silencieux).
         */
        @Suppress("ReturnCount")
        override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
            val uri = params.textDocument.uri
            val document =
                documents[uri]
                    ?: return futureEnEchec(sansCible(uri, params.position))
            val emplacements =
                try {
                    session.renommage(uri, document.convertisseur.decalageDe(params.position))
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "rename XML échoué ($uri)", t)
                    throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.value,
                            "Renommage échoué : $t",
                            null,
                        ),
                    )
                }
            if (emplacements.isEmpty()) {
                return futureEnEchec(sansCible(uri, params.position))
            }
            return CompletableFuture.completedFuture(editionsRenommage(emplacements, params.newName))
        }

        /**
         * Références (L3) : occurrences PROUVÉES de la ressource sous
         * le curseur — déclarations de l'index puis références des
         * documents ouverts, traduites en Locations LSP (même
         * mécanique que la définition). Liste VIDE sans cible prouvée
         * (référence framework, curseur hors ressource).
         */
        override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val decalage = document.convertisseur.decalageDe(params.position)
            val emplacements =
                try {
                    session.references(uri, decalage, params.context.isIncludeDeclaration)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "references XML échoué ($uri)", t)
                    emptyList()
                }
            val locations =
                emplacements.mapNotNull { emplacement ->
                    locationLspDe(emplacement)
                }
            return CompletableFuture.completedFuture(locations)
        }

        /**
         * Surlignage d'occurrences (L3) : décalages neutres →
         * DocumentHighlight LSP (déclarante = Write, références =
         * Read) du document COURANT seul. Liste VIDE sans cible.
         */
        override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val decalage = document.convertisseur.decalageDe(params.position)
            val surlignages =
                try {
                    session.surlignagesDocument(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "documentHighlight XML échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                surlignages.map { surlignage ->
                    DocumentHighlight(
                        Range(
                            document.convertisseur.positionDe(surlignage.debut),
                            document.convertisseur.positionDe(surlignage.fin),
                        ),
                        if (surlignage.genre == jo.lspxml.api.GenreSurlignageXml.ECRITURE) {
                            DocumentHighlightKind.Write
                        } else {
                            DocumentHighlightKind.Read
                        },
                    )
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Formatage de PLAGE (L3) : mêmes règles que [formatting],
         * restreintes aux lignes qui INTERSECTENT la plage — les
         * éditions ne sortent JAMAIS de la plage demandée (une édition
         * hors plage décalerait le buffer du client). Document cassé :
         * liste VIDE (succès sans édition, jamais d'exception).
         */
        override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val taille = params.options?.takeIf { it.tabSize > 0 }?.tabSize ?: INDENTATION_DEFAUT
            val debut = document.convertisseur.decalageDe(params.range.start)
            val fin = document.convertisseur.decalageDe(params.range.end)
            val editions =
                try {
                    session.formatter(uri, debut, fin, taille)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "rangeFormatting XML échoué ($uri)", t)
                    emptyList()
                }
            val edits =
                editions.map { edition ->
                    TextEdit(
                        Range(
                            document.convertisseur.positionDe(edition.debut),
                            document.convertisseur.positionDe(edition.fin),
                        ),
                        edition.texte,
                    )
                }
            return CompletableFuture.completedFuture(edits)
        }

        /**
         * Édition liée des balises (L3) : le nom de l'ouvrante ↔ le nom
         * de la fermante du même élément. `null` hors nom de balise
         * fermée (la spec : réponse null = pas de liaison — jamais
         * d'invention).
         */
        @Suppress("ReturnCount")
        override fun linkedEditingRange(params: LinkedEditingRangeParams): CompletableFuture<LinkedEditingRanges> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(null)
            val decalage = document.convertisseur.decalageDe(params.position)
            val plages =
                try {
                    session.plagesLiees(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "linkedEditingRange échoué ($uri)", t)
                    null
                } ?: return CompletableFuture.completedFuture(null)
            val plagesLieesLsp =
                LinkedEditingRanges(
                    listOf(
                        Range(
                            document.convertisseur.positionDe(plages.ouvrante.debut),
                            document.convertisseur.positionDe(plages.ouvrante.fin),
                        ),
                        Range(
                            document.convertisseur.positionDe(plages.fermante.debut),
                            document.convertisseur.positionDe(plages.fermante.fin),
                        ),
                    ),
                )
            return CompletableFuture.completedFuture(plagesLieesLsp)
        }

        /**
         * Chaînes de sélection (L3) : pour CHAQUE position demandée,
         * la chaîne des nœuds englobants (du plus profond — attribut —
         * au fichier entier), SelectionRanges LSP imbriqués. L'ordre
         * de la requête est conservé.
         */
        override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val chaines =
                try {
                    session.selections(
                        uri,
                        params.positions.map { position ->
                            document.convertisseur.decalageDe(position)
                        },
                    )
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "selectionRange XML échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(
                chaines.map { chaine ->
                    val plages =
                        chaine.plages.map { plage ->
                            Range(
                                document.convertisseur.positionDe(plage.debut),
                                document.convertisseur.positionDe(plage.fin),
                            )
                        }
                    var resultat = SelectionRange(plages.last(), null as SelectionRange?)
                    for (indice in plages.indices.reversed().drop(1)) {
                        resultat = SelectionRange(plages[indice], resultat)
                    }
                    resultat
                },
            )
        }

        /**
         * Couleurs du document (L3) : littéraux `#RGB`/`#ARGB`/
         * `#RRGGBB`/`#AARRGGBB` et références `@color/…` résolues —
         * ColorInformation LSP (plage + canaux 0..1). Liste VIDE
         * pour un document inconnu (jamais d'exception).
         */
        override fun documentColor(params: DocumentColorParams): CompletableFuture<List<ColorInformation>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val couleurs =
                try {
                    session.couleurs(uri)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "documentColor échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                couleurs.map { couleur ->
                    ColorInformation(
                        Range(
                            document.convertisseur.positionDe(couleur.debut),
                            document.convertisseur.positionDe(couleur.fin),
                        ),
                        Color(couleur.rouge, couleur.vert, couleur.bleu, couleur.alpha),
                    )
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Présentation d'une couleur (L3) : le libellé canonique et
         * l'édition de remplacement de la plage d'origine. Liste VIDE
         * sans couleur sur la plage (l'éditeur garde sa forme).
         */
        override fun colorPresentation(params: ColorPresentationParams): CompletableFuture<List<ColorPresentation>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val debut = document.convertisseur.decalageDe(params.range.start)
            val fin = document.convertisseur.decalageDe(params.range.end)
            val presentations =
                try {
                    session.presentationCouleur(
                        uri,
                        debut,
                        fin,
                        CanauxCouleurXml(
                            rouge = params.color.red,
                            vert = params.color.green,
                            bleu = params.color.blue,
                            alpha = params.color.alpha,
                        ),
                    )
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "colorPresentation échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                presentations.map { presentation ->
                    val rendu = ColorPresentation(presentation.libelle)
                    rendu.textEdit =
                        TextEdit(
                            Range(
                                document.convertisseur.positionDe(presentation.debut),
                                document.convertisseur.positionDe(presentation.fin),
                            ),
                            presentation.libelle,
                        )
                    rendu
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Formatage (S3) : les éditions de ré-indentation du moteur
         * (décalages) deviennent des TextEdits LSP sur le document
         * courant (convertisseur partagé du DocumentLsp — XP15).
         * Refus du moteur (document cassé) = liste VIDE (succès sans
         * édition : le document reste tel quel, jamais d'exception).
         */
        override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val editions =
                try {
                    session.formatter(uri, optionsIndentation(params))
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "formatting XML échoué ($uri)", t)
                    emptyList()
                }
            val edits =
                editions.map { edition ->
                    TextEdit(
                        Range(
                            document.convertisseur.positionDe(edition.debut),
                            document.convertisseur.positionDe(edition.fin),
                        ),
                        edition.texte,
                    )
                }
            return CompletableFuture.completedFuture(edits)
        }

        /** Taille d'indentation demandée (options du client, défaut 4). */
        private fun optionsIndentation(params: DocumentFormattingParams): Int =
            params.options?.takeIf { it.tabSize > 0 }?.tabSize ?: INDENTATION_DEFAUT

        /** Erreur TYPOÉE « sans cible » (miroir Java/Kotlin — data FR). */
        private fun sansCible(
            uri: String,
            position: Position,
        ): org.eclipse.lsp4j.jsonrpc.ResponseErrorException =
            org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.value,
                    "Aucune ressource renommable à la position du curseur.",
                    java.util.Map.of(
                        "code",
                        "renommage.sansCible",
                        "uri",
                        uri,
                        "line",
                        position.line,
                        "character",
                        position.character,
                    ),
                ),
            )

        /** Future en échec, compatible API 26 (miroir Kotlin). */
        private fun <T> futureEnEchec(erreur: Throwable): CompletableFuture<T> =
            CompletableFuture<T>().apply { completeExceptionally(erreur) }

        /**
         * Emplacements neutres → WorkspaceEdit legacy `changes` : le
         * texte cible vient du document ouvert (version vive) ou du
         * disque (fichier fermé — le déclarant n'est pas forcément
         * ouvert) ; illisible = emplacement OMIS (jamais une édition
         * fausse — miroir locationLspDe).
         *
         * Exemption detekt ciblée (règle 16) : SwallowedException — la
         * lecture disque best-effort EST le contrat (fichier illisible
         * = occurrence OMISE, jamais une édition fausse).
         */
        @Suppress("SwallowedException")
        private fun editionsRenommage(
            emplacements: List<jo.lspxml.api.EmplacementXml>,
            nouveauNom: String,
        ): WorkspaceEdit {
            val changes = LinkedHashMap<String, List<TextEdit>>()
            for (emplacement in emplacements) {
                val texteFichier =
                    documents[emplacement.uri]?.texte
                        ?: try {
                            java.io.File(java.net.URI(emplacement.uri)).readText()
                        } catch (t: Throwable) {
                            null
                        }
                        ?: continue // illisible : occurrence OMISE
                val convertisseur = ConvertisseurPositions.pour(texteFichier)
                val edit =
                    TextEdit(
                        Range(
                            convertisseur.positionDe(emplacement.debut),
                            convertisseur.positionDe(emplacement.fin),
                        ),
                        nouveauNom,
                    )
                changes[emplacement.uri] = (changes[emplacement.uri] ?: emptyList()) + edit
            }
            return WorkspaceEdit(changes)
        }

        /**
         * Outline (S1) : `textDocument/documentSymbol` — la session
         * rend l'arbre DOM neutre, l'hôte traduit en `DocumentSymbol`
         * LSP (kinds, ranges UTF-16, enfants imbriqués). Liste VIDE
         * pour un document inconnu ou fermé (jamais d'exception).
         */
        override fun documentSymbol(
            params: DocumentSymbolParams,
        ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val symboles =
                try {
                    session.symbolesDocument(uri)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "documentSymbol XML échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                symboles.map { symbole ->
                    Either.forRight<SymbolInformation, DocumentSymbol>(symboleLspDe(symbole, document.convertisseur))
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /** Version vide typée de la réponse définition. */
        private fun emptyLeft(): Either<List<Location>, List<org.eclipse.lsp4j.LocationLink>> =
            Either.forLeft(emptyList())

        /**
         * Pliage (XP11) : `textDocument/foldingRange` — la session rend
         * les plages DOM neutres (décalages), l'hôte traduit en lignes
         * LSP. Une plage mono-ligne (ouvrante et fermante sur la même
         * ligne) N'EST PAS émise : un pli qui ne replie rien n'est que
         * du bruit pour le client (choix documenté, testé). Document
         * inconnu/fermé : liste VIDE (jamais d'exception).
         */
        override fun foldingRange(
            params: org.eclipse.lsp4j.FoldingRangeRequestParams,
        ): CompletableFuture<List<org.eclipse.lsp4j.FoldingRange>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val plages =
                try {
                    session.plagesPliables(uri)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "foldingRange XML échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                plages.mapNotNull { plage ->
                    val debut = document.convertisseur.positionDe(plage.debut)
                    val fin = document.convertisseur.positionDe(plage.fin)
                    if (fin.line <= debut.line) {
                        return@mapNotNull null
                    }
                    org.eclipse.lsp4j.FoldingRange(debut.line, fin.line)
                }
            return CompletableFuture.completedFuture(resultat)
        }

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
            // v0.134.4 — XP15 : convertisseur local au texte cible (une
            // lecture = une construction, le fichier cible n'a pas de
            // DocumentLsp côté hôte).
            val convertisseur = ConvertisseurPositions.pour(texte)
            return Location(
                emplacement.uri,
                Range(convertisseur.positionDe(emplacement.debut), convertisseur.positionDe(emplacement.fin)),
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

        /**
         * Symbole neutre → DocumentSymbol LSP : nom, détail, kind,
         * plage (balise complète) et selectionRange (nom seul), enfants
         * récursifs. La conversion offset → Position UTF-16 est partagée
         * avec le reste du serveur (decalageDe / positionDe).
         */
        private fun symboleLspDe(
            symbole: SymboleDocumentXml,
            convertisseur: ConvertisseurPositions,
        ): DocumentSymbol {
            val plage =
                Range(convertisseur.positionDe(symbole.debutSelection), convertisseur.positionDe(symbole.finSelection))
            val selection =
                Range(convertisseur.positionDe(symbole.debutPlage), convertisseur.positionDe(symbole.finPlage))
            val enfants = symbole.enfants.map { enfant -> symboleLspDe(enfant, convertisseur) }
            val sym =
                DocumentSymbol(
                    // name =
                    symbole.nom,
                    // kind =
                    kindLspDe(symbole.genre),
                    // range =
                    plage,
                    // selectionRange =
                    selection,
                )
            symbole.detail?.let { sym.detail = it }
            if (enfants.isNotEmpty()) sym.children = enfants
            return sym
        }

        /** Genre neutre → SymbolKind LSP. */
        private fun kindLspDe(genre: GenreSymboleXml): SymbolKind =
            when (genre) {
                GenreSymboleXml.RACINE -> SymbolKind.Module
                GenreSymboleXml.VUE -> SymbolKind.Class
                GenreSymboleXml.CONTENEUR -> SymbolKind.Class
                GenreSymboleXml.INCLUSION -> SymbolKind.Object
                GenreSymboleXml.ENTREE_VALEURS -> SymbolKind.Field
                GenreSymboleXml.STYLE -> SymbolKind.Struct
                GenreSymboleXml.ITEM -> SymbolKind.Object
                GenreSymboleXml.AUTRE -> SymbolKind.Object
            }

        /** Applique un changement par plage (client non conforme, robustesse). */
        private fun appliquerPlage(
            texte: String,
            plage: Range,
            remplacement: String,
        ): String {
            val convertisseur = ConvertisseurPositions.pour(texte)
            val debut = convertisseur.decalageDe(plage.start)
            val fin = convertisseur.decalageDe(plage.end)
            return StringBuilder(texte).replace(debut, fin.coerceAtLeast(debut), remplacement).toString()
        }
    }

    /** Service d'espace : notifications acceptées, sans effet à XE1. */
    private inner class ServiceEspace : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            // XE1 : aucune configuration d'espace consommée.
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            // XP8 + lot L1 : RAFRAÎCHIR l'index des ressources (le scan
            // suit les événements — une ressource créée/modifiée hors
            // éditeur change la résolution des références), PUIS
            // republier les diagnostics des documents ouverts.
            try {
                session.rafraichirSources()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "rafraîchissement des ressources échoué (ignoré)", t)
            }
            for (uri in documents.keys) {
                publier(uri)
            }
        }
    }

    private companion object {
        /** Taille d'indentation par défaut (convention Android). */
        private const val INDENTATION_DEFAUT = 4

        private val LOGGER: Logger = Logger.getLogger(XmlLanguageServer::class.java.name)

        /** Nom du serveur (serverInfo, source des diagnostics). */
        private const val NOM_SERVEUR: String = "codeide-xml"

        /** Préfixe des codes de diagnostics QUICK-FIXABLES du moteur. */
        private const val PREFIXE_CODE: String = "xml."

        /** v0.134.1 — XP16 : seuil au-delà duquel la complétion est marquée
         *  isIncomplete=true (le moteur limite à 60 propositions). */
        private const val SEUIL_COMPLETION_INCOMPLETE: Int = 60
    }
}
