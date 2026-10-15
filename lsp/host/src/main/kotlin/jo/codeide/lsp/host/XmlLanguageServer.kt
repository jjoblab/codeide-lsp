package jo.codeide.lsp.host

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
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
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
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
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

        /** v0.134.1 — XP16 : seuil au-delà duquel la complétion est marquée
         *  isIncomplete=true (le moteur limite à 60 propositions). */
        private const val SEUIL_COMPLETION_INCOMPLETE: Int = 60
    }
}
