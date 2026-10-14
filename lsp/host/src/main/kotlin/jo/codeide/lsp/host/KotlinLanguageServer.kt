package jo.codeide.lsp.host

import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.GenreSymboleKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.api.SymboleDocumentKotlin
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
import java.io.File
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

        /** Survol : contenu texte simple (même forme que le LSP Java). */
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
                contenu?.let { Hover(listOf(Either.forLeft(it.valeur))) }
            return CompletableFuture.completedFuture(resultat)
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
            val reponses = actions.map { action -> Either.forRight<Command, CodeAction>(action) }
            return CompletableFuture.completedFuture(reponses)
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
            // insertText ABSENT : le client insère le libellé (les
            // fonctions y portent déjà leurs parenthèses).
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
            // KP10 : republier les diagnostics des documents ouverts —
            // un fichier externe (créé/modifié par Gradle, terminal,
            // autre app) peut changer la résolution. Les diagnostics
            // sont recalculés avec le classpath courant.
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
    }
}
