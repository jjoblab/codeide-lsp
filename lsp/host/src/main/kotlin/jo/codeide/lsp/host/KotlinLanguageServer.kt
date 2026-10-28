package jo.codeide.lsp.host

import jo.lspjava.api.NavigationTarget
import jo.lspkotlin.api.AideSignatureKotlin
import jo.lspkotlin.api.ChaineSelectionKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.GenreIndiceKotlin
import jo.lspkotlin.api.GenreSymboleKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.api.SymboleDocumentKotlin
import jo.lspkotlin.api.TagDiagnosticKotlin
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
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ParameterInformation
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
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Service de documents étendu par le GO TO « Super » Kotlin (ADR
 * 0210) — MÉTHODE LSP PERSONNALISÉE `textDocument/superDefinition`,
 * miroir exact de [jo.lspjava.lsp.NavTextDocumentService] du LSP Java
 * (même nom, mêmes paramètres `TextDocumentPositionParams`, même DTO
 * [NavigationTarget] : le client cel-lsp consomme les DEUX serveurs par
 * le même câble — il appelle la méthode PAR SON NOM via
 * `Launcher.getRemoteEndpoint().request(…)` et son miroir DTO porte
 * les mêmes noms de champs Gson).
 *
 * Le PIÈGE LSP4J des méthodes custom (cf. NavTextDocumentService) : la
 * carte de DÉSÉRIALISATION des paramètres suit les délégués
 * `@JsonDelegate` via le type de retour DÉCLARÉ de
 * `getTextDocumentService()` — SANS la redéclaration covariante
 * annotée, les paramètres arriveraient en LinkedTreeMap brut
 * (`argument type mismatch`).
 */
internal interface ServiceDocumentsNavKotlin : TextDocumentService {
    /**
     * Les cibles Super au caret : pour un membre outrepassant, le
     * même-nommé dans chaque supertype projet du type déclarant
     * (libellé `« name  ·  Super »`) ; sinon les supertypes DIRECTS
     * du type en contexte (libellé `« Simple  ·  pkg »`).
     *
     * @param params le document + la position (forme standard LSP)
     * @return les cibles (`NavigationTarget` : chemin ABSOLU, plage du
     *         nom en OFFSETS, libellé picker) — vide quand rien ne
     *         s'applique (l'option Super n'apparaît pas)
     */
    @JsonRequest("textDocument/superDefinition")
    fun superDefinition(params: TextDocumentPositionParams): CompletableFuture<List<NavigationTarget>>
}

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

    /** Service de documents (didOpen/didChange/didClose/didSave + requêtes). */
    private val serviceDocuments: ServiceDocumentsNavKotlin = ServiceDocuments()

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
        // K3 (LSP_KOTLIN §4.4) : complétion (déclencheur « . » — le
        // membre est LE régime), survol, définition. K4 : QUICK-FIXES
        // d'import (codeAction — un correctif par FQN candidate du
        // diagnostic kt.unresolved). Honnête : rien d'autre n'est
        // déclaré (les méthodes non surchargées de LSP4J restent les
        // défauts).
        val completion = CompletionOptions()
        completion.triggerCharacters = listOf(".", ":") // KP12: ":" pour les imports qualifiés
        completion.resolveProvider = false
        capacites.setCompletionProvider(completion)
        capacites.setHoverProvider(true)
        capacites.setDefinitionProvider(true)
        capacites.setCodeActionProvider(true)
        // S1 : OUTLINE documentSymbol — miroir AST → DocumentSymbols
        // hiérarchiques (CLASSE / FONCTION / PROPRIETE / OBJET…).
        capacites.setDocumentSymbolProvider(true)
        // S2 (v0.134.9) : PLIAGE foldingRange — corps à accolades,
        // liste d'imports, commentaires multi-lignes (miroir XP11 XML).
        capacites.setFoldingRangeProvider(true)
        // S1 (v0.134.14) : AIDE À LA SIGNATURE — appel englobant le
        // curseur, paramètre actif (déclencheurs « ( » et « , » —
        // même convention que le LSP Java).
        val signature = SignatureHelpOptions(listOf("(", ","))
        capacites.setSignatureHelpProvider(signature)
        // S2 (v0.134.15) : OCCURRENCES — references (find usages),
        // documentHighlight (surlignage des occurrences du document),
        // rename + prepareRename (rename borné, miroir du « rename
        // honnête » du LSP Java — le client cel-lsp consomme la forme
        // legacy `changes` : il n'annonce PAS documentChanges).
        capacites.setReferencesProvider(true)
        capacites.setDocumentHighlightProvider(true)
        val renommage = RenameOptions()
        renommage.prepareProvider = true
        capacites.setRenameProvider(renommage)
        // L2 (plan 1.3.0) : FORMATAGE borné (document entier + plage),
        // DÉFINITION DE TYPE et IMPLÉMENTATIONS (preuve par FQCN),
        // INDICES EN LIGNE, SYMBOLES D'ESPACE et CHAÎNES DE SÉLECTION.
        // Jetons sémantiques : NON annoncés — la distinction qu'ils
        // portent n'est pas fiable au niveau du parseur (honnêteté D3,
        // la matrice de capacités consigne l'écart).
        capacites.setDocumentFormattingProvider(true)
        capacites.setDocumentRangeFormattingProvider(true)
        capacites.setTypeDefinitionProvider(true)
        capacites.setImplementationProvider(true)
        capacites.setInlayHintProvider(true)
        capacites.setWorkspaceSymbolProvider(true)
        capacites.setSelectionRangeProvider(true)
        // ADR 0210 : GO TO « Super » — méthode LSP personnalisée
        // textDocument/superDefinition (AUCUN équivalent standard),
        // annoncée via la capability experimental (pattern jdt.ls,
        // miroir du ServerCapabilitiesProvider Java : le client la
        // recherche sous la clé « superDefinitionProvider » ; un
        // serveur tiers qui ne la connaît pas ne l'annonce pas et
        // l'option Super n'apparaît pas).
        capacites.setExperimental(mapOf("superDefinitionProvider" to true))
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

    /**
     * Redéclaration COVARIANTE annotée `@JsonDelegate` (ADR 0210) : le
     * type de retour DÉCLARÉ porte la méthode personnalisée
     * `textDocument/superDefinition` dans la carte de désérialisation
     * (miroir du `NavLanguageServer` Java — le dispatch, lui, traverse
     * les interfaces de l'objet retourné).
     */
    @JsonDelegate
    override fun getTextDocumentService(): ServiceDocumentsNavKotlin = serviceDocuments

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
            document?.let { traduire(uri, session.diagnostics(uri), it.convertisseur) } ?: emptyList()
        val params = PublishDiagnosticsParams(uri, diagnostics)
        document?.version?.let { params.version = it }
        try {
            clientCourant.publishDiagnostics(params)
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "publication diagnostics échouée ($uri)", t)
        }
    }

    /**
     * REPUBLIE les documents OUVERTS (ADR 0211/0213 — demandes D1/D3 de
     * CodeIDE) : appelé par la façade quand la passe d'indexation moteur
     * ABOUTIT — les diagnostics publiés pendant la fenêtre de
     * sous-lecture (index non encore couvert : SILENCE conservateur sur
     * le classpath, jamais de faux `kt.unresolved`) deviennent VRAIS dès
     * que le snapshot est publié. Miroir de la republication des
     * serveurs Java au didChangeWatchedFiles — le client reçoit la
     * vérité DÈS qu'elle existe.
     */
    internal fun republierDocumentsOuverts() {
        documents.keys.forEach { publier(it) }
    }

    /** Traduit les diagnostics neutres en diagnostics LSP.
     *  v0.134.4 — KP13 CORRIGÉ : convertisseur partagé du [DocumentLsp]
     *  (une construction par version, plus un scan O(n) par diagnostic).
     *  v0.134.7 — KP15 : TAGS (Unnecessary) + INFORMATIONS LIÉES (l'URI
     *  du document est fourni ici — le moteur ne connaît pas le sien). */
    private fun traduire(
        uri: String,
        diagnostics: List<DiagnosticKotlin>,
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
            ).apply {
                if (diagnostic.tags.isNotEmpty()) {
                    tags = diagnostic.tags.map { tagLspDe(it) }
                }
                if (diagnostic.informationsLiees.isNotEmpty()) {
                    relatedInformation =
                        diagnostic.informationsLiees.map { liee ->
                            DiagnosticRelatedInformation(
                                Location(
                                    uri,
                                    Range(
                                        convertisseur.positionDe(liee.debut),
                                        convertisseur.positionDe(liee.fin),
                                    ),
                                ),
                                liee.message,
                            )
                        }
                }
            }
        }

    /** Étiquette LSP d'une étiquette neutre (KP15). */
    private fun tagLspDe(tag: TagDiagnosticKotlin): DiagnosticTag =
        when (tag) {
            TagDiagnosticKotlin.INUTILE -> DiagnosticTag.Unnecessary
        }

    /** Position LSP (ligne, colonne UTF-16) d'un décalage du texte.
     *  v0.134.0 — KP13 : ConvertisseurPositions (O(log n) au lieu de O(n)). */
    private fun positionDe(
        decalage: Int,
        texte: String,
    ): Position = ConvertisseurPositions.pour(texte).positionDe(decalage)

    /** Gravité LSP d'une gravité neutre. */
    private fun severiteLspDe(severite: SeveriteKotlin): DiagnosticSeverity =
        when (severite) {
            SeveriteKotlin.ERREUR -> DiagnosticSeverity.Error
            SeveriteKotlin.AVERTISSEMENT -> DiagnosticSeverity.Warning
        }

    // ── Services ────────────────────────────────────────────────────────

    /** Document LSP courant : texte (pour les positions) + version.
     *  v0.134.4 — KP13 : convertisseur offset↔position construit UNE fois
     *  par version (le texte d'un DocumentLsp est IMMUTABLE). */
    private class DocumentLsp(
        public val texte: String,
        public val version: Int?,
    ) {
        /** Convertisseur partagé de CE texte (scan O(n) une seule fois). */
        public val convertisseur: ConvertisseurPositions = ConvertisseurPositions.pour(texte)
    }

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
     *
     * Exemption detekt ciblée (règle 16) : LargeClass — le service
     * IMPLÉMENTE le contrat TextDocumentService (une méthode par
     * requête LSP, toutes miroir mince de la session moteur) ; le
     * GO TO « Super » (ADR 0210) y ajoute la méthode personnalisée du
     * même aplomb. Le compte épouse le CONTRAT, pas une dérive.
     */
    @Suppress("LargeClass")
    private inner class ServiceDocuments : ServiceDocumentsNavKotlin {
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
            // KP2+KP9 : didSave republice les diagnostics — utile après
            // un push de classpath qui aurait changé la résolution.
            publier(params.textDocument.uri)
        }

        // ── Requêtes K3 (complétion, survol, définition) ──────────────

        /**
         * Complétion au curseur : traduction position→offset, moteur,
         * traduction des propositions neutres en CompletionItems (kind,
         * détail, sortText — le tri total voyage dans la clé, motif du
         * LSP Java), auto-import en additionalTextEdits quand le
         * candidat porte un FQN hors de portée.
         */
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
                    LOGGER.log(Level.WARNING, "complétion échouée ($uri)", t)
                    emptyList()
                }
            val items = propositions.map { proposition -> itemLspDe(proposition, texte) }
            return CompletableFuture.completedFuture(Either.forRight(CompletionList(true, items)))
        }

        /**
         * Survol : signature en PLAINTEXT, ou MARKDOWN quand le moteur
         * a attaché la KDoc de la déclaration (S2 — bloc de code
         * Kotlin + documentation, même patron que les serveurs tiers de référence).
         */
        override fun hover(params: HoverParams): CompletableFuture<Hover?> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(null)
            val decalage = decalageDe(params.position, document.texte)
            val contenu =
                try {
                    session.survol(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "survol échoué ($uri)", t)
                    null
                }
            val resultat =
                contenu?.let {
                    if (it.documentation.isNullOrBlank()) {
                        Hover(org.eclipse.lsp4j.MarkupContent(org.eclipse.lsp4j.MarkupKind.PLAINTEXT, it.valeur))
                    } else {
                        Hover(
                            org.eclipse.lsp4j.MarkupContent(
                                org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                                "```kotlin\n${it.valeur}\n```\n\n${it.documentation}",
                            ),
                        )
                    }
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Aide à la signature (S1, miroir Java `signatureHelp`) : la
         * session rend les candidates neutres (étiquettes prêtes à
         * afficher — le client ne lit que la forme STRING des
         * libellés), l'hôte traduit en `SignatureHelp` LSP. Aide
         * absente ou document inconnu : `SignatureHelp` VIDE (jamais
         * d'exception — l'éditeur retombe sur son silence).
         */
        override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(SignatureHelp())
            val decalage = decalageDe(params.position, document.texte)
            val aide =
                try {
                    session.signature(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "signatureHelp échoué ($uri)", t)
                    null
                }
            return CompletableFuture.completedFuture(aide?.let(::aideSignatureLspDe) ?: SignatureHelp())
        }

        /**
         * Définition : emplacements neutres (offsets) traduits en
         * Locations LSP (URI + plage) — la cible s'ouvre par le chemin
         * FUSE courant, même mécanique que le LSP Java.
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
                    LOGGER.log(Level.WARNING, "définition échouée ($uri)", t)
                    emptyList()
                }
            val locations =
                emplacements.mapNotNull { emplacement ->
                    locationLspDe(emplacement, uri)
                }
            return CompletableFuture.completedFuture(Either.forLeft(locations))
        }

        /**
         * Outline (S1) : `textDocument/documentSymbol` — la session
         * rend l'arbre AST neutre, l'hôte traduit en `DocumentSymbol`
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
                    LOGGER.log(Level.WARNING, "documentSymbol échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                symboles.map { symbole ->
                    Either.forRight<SymbolInformation, DocumentSymbol>(symboleLspDe(symbole, document.texte))
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Pliage (S2, miroir XP11 XML) : `textDocument/foldingRange` —
         * la session rend les plages d'arbre neutres (décalages), l'hôte
         * traduit en lignes LSP. Une plage mono-ligne (ouvrante et
         * fermante sur la même ligne) N'EST PAS émise : un pli qui ne
         * replie rien n'est que du bruit pour le client (choix
         * documenté, testé). Document inconnu/fermé : liste VIDE.
         */
        override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val plages =
                try {
                    session.plagesPliables(uri)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "foldingRange Kotlin échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                plages.mapNotNull { plage ->
                    val debut = document.convertisseur.positionDe(plage.debut)
                    val fin = document.convertisseur.positionDe(plage.fin)
                    if (fin.line <= debut.line) {
                        return@mapNotNull null
                    }
                    FoldingRange(debut.line, fin.line)
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Références (S2) : occurrences du symbole au curseur dans les
         * sources du projet — emplacements neutres traduits en Locations
         * LSP (même mécanique que la définition). Liste VIDE sans cible
         * prouvée (conservatisme D3, jamais d'exception).
         */
        override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val decalage = decalageDe(params.position, document.texte)
            val emplacements =
                try {
                    session.references(uri, decalage, params.context.isIncludeDeclaration)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "references échoué ($uri)", t)
                    emptyList()
                }
            val locations =
                emplacements.mapNotNull { emplacement ->
                    locationLspDe(emplacement, uri)
                }
            return CompletableFuture.completedFuture(locations)
        }

        /**
         * Surlignage des occurrences (S2) : décalages neutres →
         * DocumentHighlight LSP (déclaration = Write, références = Read).
         * Liste VIDE sans cible prouvée ou document inconnu.
         */
        override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val decalage = decalageDe(params.position, document.texte)
            val surlignages =
                try {
                    session.surlignagesDocument(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "documentHighlight échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                surlignages.map { surlignage ->
                    DocumentHighlight(
                        Range(
                            document.convertisseur.positionDe(surlignage.debut),
                            document.convertisseur.positionDe(surlignage.fin),
                        ),
                        genreSurlignageLspDe(surlignage.genre),
                    )
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /** Genre neutre → DocumentHighlightKind LSP. */
        private fun genreSurlignageLspDe(genre: jo.lspkotlin.api.GenreSurlignageKotlin): DocumentHighlightKind =
            when (genre) {
                jo.lspkotlin.api.GenreSurlignageKotlin.LECTURE -> DocumentHighlightKind.Read
                jo.lspkotlin.api.GenreSurlignageKotlin.ECRITURE -> DocumentHighlightKind.Write
            }

        /**
         * Formatage borné (L2, plan 1.3.0) : les éditions de
         * ré-indentation du moteur (décalages) deviennent des TextEdits
         * LSP (convertisseur partagé du document). Refus du moteur
         * (document en erreur) = liste VIDE (le document reste tel
         * quel, jamais d'exception).
         */
        override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val taille = params.options?.takeIf { it.tabSize > 0 }?.tabSize ?: INDENTATION_DEFAUT
            val editions =
                try {
                    session.formatage(uri, taille)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "formatting Kotlin échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(editionsTexte(editions, document))
        }

        /**
         * Formatage borné d'une PLAGE (L2) : mêmes règles que
         * [formatting], restreintes aux lignes qui intersectent la
         * plage demandée — les éditions ne sortent JAMAIS de la plage
         * (une édition hors plage décalerait le buffer du client).
         */
        override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val taille = params.options?.takeIf { it.tabSize > 0 }?.tabSize ?: INDENTATION_DEFAUT
            val debut = decalageDe(params.range.start, document.texte)
            val fin = decalageDe(params.range.end, document.texte)
            val editions =
                try {
                    session.formatage(uri, debut, fin, taille)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "rangeFormatting Kotlin échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(editionsTexte(editions, document))
        }

        /** Éditions neutres (décalages) → TextEdits LSP. */
        private fun editionsTexte(
            editions: List<jo.lspkotlin.api.EditionSourceKotlin>,
            document: DocumentLsp,
        ): List<TextEdit> =
            editions.map { edition ->
                TextEdit(
                    Range(
                        document.convertisseur.positionDe(edition.debut),
                        document.convertisseur.positionDe(edition.fin),
                    ),
                    edition.texte,
                )
            }

        /**
         * Définition de TYPE (L2) : emplacements de la déclaration du
         * type du symbole visé — LocationLinks vers le fichier cible
         * (même mécanique que la définition : URI + plage du nom).
         */
        override fun typeDefinition(
            params: TypeDefinitionParams,
        ): CompletableFuture<Either<List<org.eclipse.lsp4j.Location>, List<org.eclipse.lsp4j.LocationLink>>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyLeft())
            val decalage = decalageDe(params.position, document.texte)
            val emplacements =
                try {
                    session.definitionsType(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "typeDefinition échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(
                Either.forLeft(emplacements.mapNotNull { emplacement -> locationLspDe(emplacement, uri) }),
            )
        }

        /**
         * Implémentations (L2) : héritiers PROUVÉS du type en contexte
         * (preuve par FQCN côté serveur) — Locations LSP.
         */
        override fun implementation(
            params: ImplementationParams,
        ): CompletableFuture<Either<List<org.eclipse.lsp4j.Location>, List<org.eclipse.lsp4j.LocationLink>>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyLeft())
            val decalage = decalageDe(params.position, document.texte)
            val emplacements =
                try {
                    session.implementations(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "implementation échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(
                Either.forLeft(emplacements.mapNotNull { emplacement -> locationLspDe(emplacement, uri) }),
            )
        }

        /**
         * GO TO « Super » (ADR 0210, `textDocument/superDefinition` —
         * méthode personnalisée, miroir du LSP Java) : les cibles du
         * moteur ([SessionLangageKotlin.superDefinition]) traduites en
         * [NavigationTarget] — chemin ABSOLU + plage du nom en OFFSETS
         * + libellé picker (aucun round-trip Position↔offset à risquer
         * côté client, même contrat que le serveur Java). Liste VIDE
         * quand rien ne s'applique (l'option Super n'apparaît pas).
         */
        override fun superDefinition(params: TextDocumentPositionParams): CompletableFuture<List<NavigationTarget>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val decalage = decalageDe(params.position, document.texte)
            val cibles =
                try {
                    session.superDefinition(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "superDefinition échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(
                cibles.map { cible ->
                    NavigationTarget(cheminAbsoluDe(cible.uri), cible.debutNom, cible.finNom, cible.libelle)
                },
            )
        }

        /**
         * Indices en ligne (L2) : annotations fantômes du moteur
         * (noms de paramètres, types certains) — libellé PRÊT À
         * AFFICHER, l'hôte ajoute les séparateurs de rendu selon le
         * genre (`nom =` / `: Type`), la position LSP est convertie
         * par ses soins. Liste VIDE pour un document inconnu.
         */
        override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val debut = decalageDe(params.range.start, document.texte)
            val fin = decalageDe(params.range.end, document.texte)
            val indices =
                try {
                    session.indices(uri, debut, fin)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "inlayHint échoué ($uri)", t)
                    emptyList()
                }
            val resultat =
                indices.map { indice ->
                    val texte =
                        when (indice.genre) {
                            GenreIndiceKotlin.PARAMETRE -> indice.libelle + " ="
                            GenreIndiceKotlin.TYPE -> ": " + indice.libelle
                        }
                    val indiceLsp = InlayHint()
                    indiceLsp.position = document.convertisseur.positionDe(indice.debut)
                    indiceLsp.label = Either.forLeft(texte)
                    indiceLsp.kind =
                        if (indice.genre == GenreIndiceKotlin.PARAMETRE) InlayHintKind.Parameter else InlayHintKind.Type
                    indiceLsp
                }
            return CompletableFuture.completedFuture(resultat)
        }

        /**
         * Chaînes de sélection (L2) : pour CHAQUE position demandée,
         * la chaîne des nœuds englobants (du plus profond au fichier
         * entier) — SelectionRanges LSP imbriqués (parent = maillon
         * suivant). L'ordre de la requête est conservé.
         */
        override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> {
            val uri = params.textDocument.uri
            val document = documents[uri] ?: return CompletableFuture.completedFuture(emptyList())
            val chaines =
                try {
                    session.selections(uri, params.positions.map { position -> decalageDe(position, document.texte) })
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "selectionRange échoué ($uri)", t)
                    emptyList()
                }
            return CompletableFuture.completedFuture(chaines.map { chaine -> selectionLspDe(chaine, document) })
        }

        /** Chaîne neutre → SelectionRange LSP (parent = maillon suivant). */
        private fun selectionLspDe(
            chaine: ChaineSelectionKotlin,
            document: DocumentLsp,
        ): SelectionRange {
            val plages =
                chaine.plages.map { plage ->
                    Range(
                        document.convertisseur.positionDe(plage.debut),
                        document.convertisseur.positionDe(plage.fin),
                    )
                }
            var resultat = SelectionRange(plages.last(), null as SelectionRange?)
            for (index in plages.indices.reversed().drop(1)) {
                resultat = SelectionRange(plages[index], resultat)
            }
            return resultat
        }

        /**
         * Pré-validation du renommage (S2 — miroir S0-b Java) : la
         * plage EXACTE du nom + le placeholder (nom courant) — le caret
         * AU MILIEU du nom est couvert (plage du jeton, pas heuristique
         * findWord). Non renommable → null (la boîte de dialogue client
         * refuse proprement, sans erreur).
         */
        override fun prepareRename(
            params: PrepareRenameParams,
        ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
            val uri = params.textDocument.uri
            val document = documents[uri]
            val plage =
                document?.let { doc ->
                    try {
                        session.plageRenommable(uri, decalageDe(params.position, doc.texte))
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "prepareRename échoué ($uri)", t)
                        null
                    }
                }
            if (plage == null) return CompletableFuture.completedFuture(null)
            val resultat =
                PrepareRenameResult(
                    Range(
                        document!!.convertisseur.positionDe(plage.debut),
                        document.convertisseur.positionDe(plage.fin),
                    ),
                    plage.placeholder,
                )
            return CompletableFuture.completedFuture(Either3.forSecond(resultat))
        }

        /**
         * Renommage borné (S2 — miroir I8 « rename honnête » Java) : les
         * occurrences PROUVÉES (déclaration + références + imports du
         * FQCN) deviennent des TextEdits groupés par fichier. AUCUN
         * échec silencieux — sans cible = erreur TYPOÉE RequestFailed
         * (-32803) avec data {code: renommage.sansCible} ; les autres
         * défaillances relancent, jamais un WorkspaceEdit vide.
         *
         * Forme legacy `changes` : le client cel-lsp actuel n'annonce
         * PAS WorkspaceEditCapabilities.documentChanges (vérifié par
         * décompilation, lot S0-b) — la spec impose cette forme pour un
         * client non déclarant, il la consomme (getChanges).
         */
        override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
            val uri = params.textDocument.uri
            val document = documents[uri]
            val emplacements =
                document?.let { doc ->
                    try {
                        session.renommage(uri, decalageDe(params.position, doc.texte))
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "rename échoué ($uri)", t)
                        throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.value,
                                "Renommage échoué : $t",
                                null,
                            ),
                        )
                    }
                } ?: emptyList()
            if (emplacements.isEmpty()) {
                return futureEnEchec(
                    org.eclipse.lsp4j.jsonrpc
                        .ResponseErrorException(sansCible(uri, params.position)),
                )
            }
            return CompletableFuture.completedFuture(editionsRenommage(emplacements, params.newName))
        }

        /**
         * Future en échec, compatible API 26 —
         * `CompletableFuture.failedFuture` n'existe qu'à partir de l'API
         * 31 : la construction + [CompletableFuture.completeExceptionally]
         * fait le même travail sur tout l'historique supporté (miroir du
         * call-out Java, lu par le même client).
         */
        private fun <T> futureEnEchec(erreur: Throwable): CompletableFuture<T> =
            CompletableFuture<T>().apply { completeExceptionally(erreur) }

        /**
         * Occurrences prouvées → WorkspaceEdit legacy `changes` : les
         * textes viennent des documents ouverts (version vive) ou du
         * disque (fichiers fermés) ; illisible = occurrences OMISES
         * (jamais une édition fausse).
         *
         * Exemption detekt ciblée (règle 16) : SwallowedException — la
         * lecture disque best-effort EST le contrat (miroir
         * locationLspDe, D3 : fichier illisible = emplacement omis).
         */
        @Suppress("SwallowedException")
        private fun editionsRenommage(
            emplacements: List<jo.lspkotlin.api.EmplacementKotlin>,
            nouveauNom: String,
        ): WorkspaceEdit {
            val changes = LinkedHashMap<String, List<TextEdit>>()
            for (emplacement in emplacements) {
                val texteFichier =
                    if (documents.containsKey(emplacement.uri)) {
                        documents[emplacement.uri]?.texte
                    } else {
                        try {
                            File(java.net.URI(emplacement.uri)).readText()
                        } catch (t: Throwable) {
                            null // fichier illisible — occurrences omises (D3)
                        }
                    }
                if (texteFichier == null) continue
                val convertisseur = ConvertisseurPositions.pour(texteFichier)
                val edition =
                    TextEdit(
                        Range(
                            convertisseur.positionDe(emplacement.debut),
                            convertisseur.positionDe(emplacement.fin),
                        ),
                        nouveauNom,
                    )
                // occurrences ordonnées par fichier, début croissant —
                // un même URI arrive en bloc contigu (session trie).
                changes[emplacement.uri] = (changes[emplacement.uri] ?: emptyList()) + edition
            }
            val workspaceEdit = WorkspaceEdit()
            workspaceEdit.changes = changes
            return workspaceEdit
        }

        /** Erreur typée « aucun symbole renommable » (leçon I8 Java). */
        private fun sansCible(
            uri: String,
            position: Position,
        ): org.eclipse.lsp4j.jsonrpc.messages.ResponseError =
            org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.value,
                "Aucun symbole renommable à la position du curseur.",
                mapOf(
                    "code" to "renommage.sansCible",
                    "uri" to uri,
                    "line" to position.line,
                    "character" to position.character,
                ),
            )

        /**
         * Symbole neutre → DocumentSymbol LSP : nom, détail, kind,
         * plage (déclaration complète) et selectionRange (nom seul),
         * enfants récursifs. La conversion offset → Position UTF-16 est
         * partagée avec le reste du serveur (decalageDe / positionDe).
         */
        private fun symboleLspDe(
            symbole: SymboleDocumentKotlin,
            texte: String,
        ): DocumentSymbol {
            val plage = Range(positionDe(symbole.debutTotal, texte), positionDe(symbole.finTotal, texte))
            val selection = Range(positionDe(symbole.debutNom, texte), positionDe(symbole.finNom, texte))
            val enfants = symbole.enfants.map { enfant -> symboleLspDe(enfant, texte) }
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
        private fun kindLspDe(genre: GenreSymboleKotlin): SymbolKind =
            when (genre) {
                GenreSymboleKotlin.CLASSE -> SymbolKind.Class
                GenreSymboleKotlin.INTERFACE -> SymbolKind.Interface
                GenreSymboleKotlin.OBJET -> SymbolKind.Object
                GenreSymboleKotlin.FONCTION -> SymbolKind.Method
                GenreSymboleKotlin.PROPRIETE -> SymbolKind.Property
                GenreSymboleKotlin.ENTREE_ENUM -> SymbolKind.EnumMember
                GenreSymboleKotlin.ALIAS -> SymbolKind.TypeParameter
            }

        /** Version vide typée de la réponse définition. */
        private fun emptyLeft(): Either<List<org.eclipse.lsp4j.Location>, List<org.eclipse.lsp4j.LocationLink>> =
            Either.forLeft(emptyList())

        /**
         * Quick-fixes d'import (K4) : pour chaque diagnostic
         * `kt.unresolved` de la requête, UN CodeAction par FQN candidate
         * rendue par le moteur ([SessionLangageKotlin.importsCandidats])
         * — l'édition insère `import fqn` à l'heuristique partagée du
         * serveur (après le dernier import, sinon le package, sinon la
         * tête). Le tri du cadrage est l'ordre du classpath (le moteur
         * l'a déjà appliqué) ; le plafond borne la palette.
         */
        override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
            val uri = params.textDocument.uri
            val document = documents[uri]
            val actions = mutableListOf<CodeAction>()
            val nonResolues =
                params.context.diagnostics.filter { diagnostic ->
                    diagnostic.code?.left == CODE_NON_RESOLU
                }
            for (diagnostic in nonResolues) {
                val texte = document?.texte ?: ""
                val decalage = decalageDe(diagnostic.range.start, texte)
                val candidats =
                    try {
                        session.importsCandidats(uri, decalage)
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "quick-fixes d'import échoués ($uri)", t)
                        emptyList()
                    }
                candidats.take(PLAFOND_CORRECTIFS).forEach { fqn ->
                    val position = positionDe(decalageInsertionImport(texte), texte)
                    val action = CodeAction("Importer « $fqn »")
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diagnostic)
                    action.edit =
                        WorkspaceEdit(
                            linkedMapOf(
                                uri to listOf(TextEdit(Range(position, position), "import $fqn\n")),
                            ),
                        )
                    actions.add(action)
                }
            }
            // P1-C (ADR 0229) : « implémenter les membres manquants » de
            // la classe/objet englobant le curseur — correctif SANS
            // diagnostic (le moteur Kotlin n'a pas de diagnostic
            // d'exhaustivité d'héritage ; l'action est bornée à la
            // déclaration englobante, elle ne peut pas se tromper de
            // cible).
            actionMembresManquants(uri, document, params.range.start)?.let { actions.add(it) }
            val reponses = actions.map { action -> Either.forRight<Command, CodeAction>(action) }
            return CompletableFuture.completedFuture(reponses)
        }

        /**
         * L'action « implémenter les membres manquants » de la
         * classe/objet englobant le curseur — le moteur rend l'édition
         * CONCRÈTE (stubs indentés), l'hôte n'ajoute que le TITRE
         * (singulier/pluriel) et l'enveloppe `WorkspaceEdit`.
         * `null` quand le moteur se tait (rien ne manque, hors classe,
         * moteur en échec — jamais une action muette).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — document
         * absent, moteur silencieux et action rendue sont les TROIS
         * issues du contrat honnête (miroir du codeAction des imports).
         */
        @Suppress("ReturnCount")
        private fun actionMembresManquants(
            uri: String,
            document: DocumentLsp?,
            debutPlage: Position,
        ): CodeAction? {
            if (document == null) return null
            val decalage = decalageDe(debutPlage, document.texte)
            val correctif =
                try {
                    session.membresManquants(uri, decalage)
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "membres manquants échoués ($uri)", t)
                    null
                }
            if (correctif == null) return null
            val titre =
                if (correctif.nombre == 1) {
                    "Implémenter le membre manquant"
                } else {
                    "Implémenter les ${correctif.nombre} membres manquants"
                }
            val action = CodeAction(titre)
            action.kind = CodeActionKind.QuickFix
            action.edit =
                WorkspaceEdit(
                    linkedMapOf(
                        uri to
                            listOf(
                                TextEdit(
                                    Range(
                                        positionDe(correctif.debut, document.texte),
                                        positionDe(correctif.fin, document.texte),
                                    ),
                                    correctif.texte,
                                ),
                            ),
                    ),
                )
            return action
        }

        /** Extension de script (`.kts`) → mode script du parseur. */
        private fun estScript(uri: String): Boolean = uri.endsWith(".kts", ignoreCase = true)

        // ── Traductions K3 ────────────────────────────────────────────

        /** Proposition neutre → CompletionItem LSP (kind, détail, tri, import). */
        private fun itemLspDe(
            proposition: PropositionCompletionKotlin,
            texte: String,
        ): CompletionItem {
            val item = CompletionItem(proposition.libelle)
            item.kind = genreLspDe(proposition.genre)
            proposition.detail?.let { item.detail = it }
            // insertText ABSENT par défaut : le client insère le libellé
            // (les fonctions y portent déjà leurs parenthèses). Vague 1
            // de la complétion contextuelle : le candidat peut porter
            // SON texte d'insertion (« nom = » affiché, `nom = ` inséré
            // — miroir du moteur Java, le séparateur voyage avec le
            // candidat).
            proposition.texteInsertion?.let { insertion -> item.insertText = insertion }
            item.sortText = proposition.texteTri
            // Auto-import : le candidat porte un FQN hors de portée —
            // additionalTextEdits appliqué À L'ACCEPTATION (contrat LSP),
            // en syntaxe Kotlin (pas de point-virgule).
            val fqn = proposition.fqnImport
            if (fqn != null && texte.isNotEmpty() && item.additionalTextEdits == null) {
                val decalageImport = decalageInsertionImport(texte)
                if (decalageImport >= 0) {
                    val position = positionDe(decalageImport, texte)
                    item.additionalTextEdits = listOf(TextEdit(Range(position, position), "import $fqn\n"))
                }
            }
            return item
        }

        /** Genre neutre → CompletionItemKind (miroir du LSP Java). */
        private fun genreLspDe(genre: GenreCompletionKotlin): CompletionItemKind =
            when (genre) {
                GenreCompletionKotlin.CLASSE,
                GenreCompletionKotlin.OBJET,
                GenreCompletionKotlin.ALIAS,
                -> CompletionItemKind.Class

                GenreCompletionKotlin.INTERFACE -> CompletionItemKind.Interface

                GenreCompletionKotlin.ENUM -> CompletionItemKind.Enum

                GenreCompletionKotlin.FONCTION -> CompletionItemKind.Method

                GenreCompletionKotlin.PROPRIETE -> CompletionItemKind.Field

                GenreCompletionKotlin.VARIABLE -> CompletionItemKind.Variable

                GenreCompletionKotlin.MOT_CLE -> CompletionItemKind.Keyword

                GenreCompletionKotlin.PAQUET -> CompletionItemKind.Module
            }

        /**
         * Emplacement neutre (offsets dans le texte CIBLE) → Location
         * LSP : le document courant est en mémoire, une cible du PROJET
         * se relit sur disque (FUSE — une lecture bornée par requête) ;
         * illisible : null (la définition est omise, jamais fausse).
         *
         * SwallowedException ciblé (règle 16) : cible illisible =
         * définition OMISE (D3) — l'exception est le signal, pas une
         * perte (lecture FUSE best-effort).
         */
        @Suppress("SwallowedException")
        private fun locationLspDe(
            emplacement: EmplacementKotlin,
            uriCourant: String,
        ): Location? {
            val texteCible: String? =
                if (emplacement.uri == uriCourant) {
                    documents[uriCourant]?.texte
                } else {
                    try {
                        File(java.net.URI(emplacement.uri)).readText()
                    } catch (t: Throwable) {
                        null
                    }
                }
            val texte = texteCible ?: return null
            return Location(
                emplacement.uri,
                Range(positionDe(emplacement.debut, texte), positionDe(emplacement.fin, texte)),
            )
        }

        /**
         * Décalage d'insertion d'un nouvel import — après le DERNIER
         * import du fichier, sinon après la directive package, sinon
         * en début de fichier (heuristique textuelle, miroir Kotlin de
         * CodeActionService.importInsertOffset côté Java).
         */
        private fun decalageInsertionImport(texte: String): Int {
            var apresPackage = -1
            var apresDernierImport = -1
            var decalage = 0
            for (ligne in texte.split("\n")) {
                val coupe = ligne.trimStart()
                if (coupe.startsWith("package ")) {
                    apresPackage = decalage + ligne.length + 1
                } else if (coupe.startsWith("import ")) {
                    apresDernierImport = decalage + ligne.length + 1
                }
                decalage += ligne.length + 1
            }
            return when {
                apresDernierImport >= 0 -> apresDernierImport
                apresPackage >= 0 -> apresPackage
                else -> 0
            }
        }

        /**
         * Chemin ABSOLU d'une URI de document (`file:///a/b.kt` comme
         * `file:/a/b.kt` — [File.toURI] rend la forme À UN slash — →
         * `/a/b.kt`) — [NavigationTarget.path] du GO TO « Super »
         * (ADR 0210) porte le CHEMIN, pas l'URI (même contrat que le
         * serveur Java) ; une URI sans schéma file: passe TELLE
         * QUELLE (fixture de test — honnête, jamais une exception).
         */
        private fun cheminAbsoluDe(uri: String): String =
            when {
                uri.startsWith("file://") -> uri.removePrefix("file://")
                uri.startsWith("file:/") -> uri.removePrefix("file:")
                else -> uri
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

        /** Décalage d'une position LSP dans le texte (best effort borné).
         *  v0.134.0 — KP13 : utilise ConvertisseurPositions (O(1) au lieu de O(n)). */
        private fun decalageDe(
            position: Position,
            texte: String,
        ): Int = ConvertisseurPositions.pour(texte).decalageDe(position)

        /**
         * Traduction de l'aide neutre (S1) : étiquette →
         * `SignatureInformation`, libellés de paramètres en forme
         * STRING (le client éditeur ignore la forme plage), doc en
         * plaintext quand présente. Indices bornés : signatureActive
         * tombe sur 0 hors bornes, parametreActif reste >= 0 — un
         * index hors bornes chez le client n'aurait AUCUN surlignage.
         */
        private fun aideSignatureLspDe(aide: AideSignatureKotlin): SignatureHelp {
            val signatures =
                aide.signatures.map { candidate ->
                    val info = SignatureInformation(candidate.etiquette)
                    candidate.documentation
                        ?.takeIf { it.isNotBlank() }
                        ?.let { info.setDocumentation(Either.forLeft(it)) }
                    info.setParameters(candidate.parametres.map { ParameterInformation(it) })
                    info
                }
            return SignatureHelp(
                signatures,
                if (aide.signatureActive in signatures.indices) aide.signatureActive else 0,
                aide.parametreActif.coerceAtLeast(0),
            )
        }
    }

    /** Service d'espace : notifications acceptées + symboles (L2). */
    private inner class ServiceEspace : WorkspaceService {
        /**
         * Symboles de l'ESPACE de travail (L2, plan 1.3.0) :
         * `workspace/symbol` — la palette « aller au symbole » de
         * l'éditeur. Déclarations nommées du projet (documents ouverts
         * + racines du scan), requête CONTIENT insensible à la casse ;
         * cibles du classpath NON listées (sans source, rien à ouvrir).
         * Liste VIDE sans candidat (jamais d'exception).
         */
        override fun symbol(
            params: WorkspaceSymbolParams,
        ): CompletableFuture<Either<List<SymbolInformation>, List<org.eclipse.lsp4j.WorkspaceSymbol>>> =
            CompletableFuture.completedFuture(
                Either.forLeft(
                    try {
                        session.symbolesEspace(params.query).mapNotNull { symbole -> symboleInformationDe(symbole) }
                    } catch (t: Throwable) {
                        LOGGER.log(Level.WARNING, "workspace/symbol échoué", t)
                        emptyList()
                    },
                ),
            )

        /**
         * Symbole neutre → SymbolInformation LSP (plage = nom).
         *
         * Exemption detekt ciblée (règle 16) : SwallowedException — la
         * lecture disque best-effort EST le contrat (miroir
         * locationLspDe, D3 : fichier illisible = symbole omis).
         */
        @Suppress("SwallowedException", "DEPRECATION")
        private fun symboleInformationDe(symbole: jo.lspkotlin.api.SymboleEspaceKotlin): SymbolInformation? {
            val texte =
                try {
                    File(java.net.URI(symbole.uri)).readText()
                } catch (t: Throwable) {
                    null // fichier illisible — symbole omis (D3)
                } ?: return null
            val convertisseur = ConvertisseurPositions.pour(texte)
            val information = SymbolInformation()
            information.name = symbole.nom
            information.kind = kindEspaceLspDe(symbole.genre)
            information.location =
                Location(
                    symbole.uri,
                    Range(
                        convertisseur.positionDe(symbole.debutNom),
                        convertisseur.positionDe(symbole.finNom),
                    ),
                )
            symbole.conteneur?.let { conteneur -> information.containerName = conteneur }
            return information
        }

        /** Genre neutre → SymbolKind LSP (palette d'espace). */
        private fun kindEspaceLspDe(genre: GenreSymboleKotlin): SymbolKind =
            when (genre) {
                GenreSymboleKotlin.CLASSE -> SymbolKind.Class
                GenreSymboleKotlin.INTERFACE -> SymbolKind.Interface
                GenreSymboleKotlin.OBJET -> SymbolKind.Object
                GenreSymboleKotlin.FONCTION -> SymbolKind.Function
                GenreSymboleKotlin.PROPRIETE -> SymbolKind.Property
                GenreSymboleKotlin.ENTREE_ENUM -> SymbolKind.EnumMember
                GenreSymboleKotlin.ALIAS -> SymbolKind.TypeParameter
            }

        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            // K2 : aucune configuration d'espace consommée (documentation
            // future : langage du projet).
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            // KP10 + lot L1 : RAFRAÎCHIR les sources externes (le scan
            // des racines suit les événements — un fichier modifié hors
            // éditeur par Gradle/terminal change la résolution), PUIS
            // republier les diagnostics des documents ouverts.
            try {
                session.rafraichirSources()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "rafraîchissement des sources échoué (ignoré)", t)
            }
            for (uri in documents.keys) {
                publier(uri)
            }
        }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(KotlinLanguageServer::class.java.name)

        /** Nom du serveur (serverInfo, source des diagnostics). */
        private const val NOM_SERVEUR: String = "codeide-kotlin"

        /** Code des diagnostics ciblés par les quick-fixes d'import. */
        private const val CODE_NON_RESOLU: String = "kt.unresolved"

        /** Correctifs d'import maximum PAR diagnostic (palette bornée). */
        private const val PLAFOND_CORRECTIFS: Int = 8

        /** Indentation par défaut (options du client absentes, miroir XML). */
        private const val INDENTATION_DEFAUT: Int = 4
    }
}
