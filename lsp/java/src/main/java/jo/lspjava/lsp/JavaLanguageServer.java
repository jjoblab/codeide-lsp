package jo.lspjava.lsp;

import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Serveur LSP Java principal implémentant {@link LanguageServer}.
 *
 * <p>Point d'entrée du serveur de langage Java. Utilisé en mode in-process
 * par l'application hôte via un {@code InProcessStreamConnectionProvider} :
 * l'hôte instancie ce serveur, connecte un {@link LanguageClient} via LSP4J
 * (pipes in-process), et toutes les requêtes LSP (completion, hover,
 * definition, codeAction) transitent par le protocole JSON-RPC standard.</p>
 *
 * <p>Le serveur maintient une machine à états lifecycle explicite
 * ({@link LifecycleState}) : {@code shutdown} marque l'état
 * {@link LifecycleState#SHUTDOWN} et {@code exit} appelle {@code System.exit}
 * uniquement si le serveur a été lancé en mode standalone.</p>
 *
 * <p>Le retour covariant de {@link #getTextDocumentService()} en
 * {@link NavTextDocumentService} est indispensable pour que la carte de
 * désérialisation LSP4J connaisse la méthode custom
 * {@code textDocument/superDefinition}.</p>
 *
 * @author jo@Dev
 */
public final class JavaLanguageServer implements NavLanguageServer {

    private static final Logger LOG = Logger.getLogger("JavaLanguageServer");

    private final CompilationEnvironment environment;
    private final JavaTextDocumentService textDocumentService;
    private final JavaWorkspaceService workspaceService;

    // Immutable après applyClientCapabilities : créé une seule fois et réutilisé
    // (préserve les clientCapabilities si initialize est appelé deux fois).
    private final ServerCapabilitiesProvider capabilitiesProvider = new ServerCapabilitiesProvider();

    /** Client LSP — capturé par le launcher pour pousser les diagnostics. */
    private volatile LanguageClient client;

    // ── Machine à états lifecycle ─────────────────────────────────────
    /**
     * États du lifecycle LSP 3.17 §Lifecycle :
     * <pre>
     *   UNINITIALIZED → INITIALIZING → INITIALIZED → SHUTDOWN → EXITED
     * </pre>
     * Toute requête LSP (autre que initialize/exit) avant INITIALIZED doit
     * être rejetée (spec : {@code ServerNotInitialized}). Toute requête
     * (autre que exit) après SHUTDOWN doit être rejetée
     * (spec : {@code InvalidRequest}).
     */
    public enum LifecycleState {
        UNINITIALIZED,   // avant initialize
        INITIALIZING,    // pendant initialize (rarement observé)
        INITIALIZED,     // après initialized notification
        SHUTDOWN,        // après shutdown request
        EXITED           // après exit notification (terminal)
    }

    /** État courant du lifecycle — volatile pour la visibilité inter-threads. */
    private volatile LifecycleState lifecycleState = LifecycleState.UNINITIALIZED;

    /**
     * Si {@code true}, le serveur est lancé en mode standalone (process dédié)
     * et {@code exit()} peut appeler {@code System.exit}. Si {@code false}
     * (mode in-process, par défaut), {@code exit()} se contente de cleaner
     * les ressources — l'hôte gère le cycle de vie du process.
     */
    private volatile boolean standaloneMode = false;

    /** Retourne l'état courant du lifecycle. */
    public LifecycleState lifecycleState() { return lifecycleState; }

    /**
     * Active le mode standalone (process dédié). À appeler par le
     * {@code main()} du serveur si lancé en CLI. En mode in-process
     * (défaut), ne pas appeler.
     */
    public void setStandaloneMode(boolean standalone) {
        this.standaloneMode = standalone;
    }

    public JavaLanguageServer() {
        this.environment = new CompilationEnvironment();
        this.textDocumentService = new JavaTextDocumentService(environment, this);
        this.workspaceService = new JavaWorkspaceService(environment, this);
        registerContextListener();
    }

    /**
     * Constructeur avec classpath personnalisé (depuis Gradle Tooling).
     *
     * @param environment l'environnement de compilation (classpath + source dirs)
     */
    public JavaLanguageServer(CompilationEnvironment environment) {
        this.environment = environment;
        this.textDocumentService = new JavaTextDocumentService(environment, this);
        this.workspaceService = new JavaWorkspaceService(environment, this);
        registerContextListener();
    }

    /**
     * Live-refresh des diagnostics : dès que l'hôte pousse un nouveau
     * contexte via {@code applyWorkspaceModel → replaceAll} (sync Gradle
     * arrivé), tous les documents ouverts sont ré-analysés — les erreurs
     * périmées « class introuvable » s'effacent sans frappe utilisateur.
     */
    private void registerContextListener() {
        try {
            environment.addContextListener(() ->
                    textDocumentService.republishAllOpenDocumentsAsync());
        } catch (Throwable t) {
            LOG.warning("abonnement ContextListener échoué : " + t);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        // Transition d'état UNINITIALIZED → INITIALIZING.
        if (lifecycleState != LifecycleState.UNINITIALIZED) {
            LOG.warning("initialize appelé dans l'état " + lifecycleState + " (devrait être UNINITIALIZED)");
        }
        lifecycleState = LifecycleState.INITIALIZING;

        // Lecture de InitializeParams : workspaceFolders, clientCapabilities,
        // processId (lspjava reste mono-workspace pour le moment).
        LOG.info("initialize: rootUri=" + params.getRootUri()
                + ", processId=" + params.getProcessId()
                + ", workspaceFolders=" + (params.getWorkspaceFolders() != null ? params.getWorkspaceFolders().size() : 0)
                + " (lspjava reste mono-workspace pour le moment)");

        // Note : workspaceFolders est lu mais pas encore exploité (limitation
        // mono-workspace actuelle ; le multi-racines viendra avec la refonte
        // de CompilationEnvironment).

        // Négociation capabilities : les clientCapabilities sont transmis au
        // ServerCapabilitiesProvider (instance unique réutilisée).
        capabilitiesProvider.applyClientCapabilities(params.getCapabilities());

        InitializeResult result = new InitializeResult(capabilitiesProvider.createCapabilities());

        // serverInfo dans InitializeResult (recommandé LSP 3.17).
        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setName("lspjava");
        serverInfo.setVersion("1.2.0");
        result.setServerInfo(serverInfo);

        // Pas encore INITIALIZED — il faut attendre la notification initialized.
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        LOG.info("initialized notification received");
        // Transition INITIALIZING → INITIALIZED.
        lifecycleState = LifecycleState.INITIALIZED;
        // Pré-chauffe l'index de classpath en arrière-plan : la construction
        // démarre dès initialized(), ainsi au moment où l'utilisateur ouvre
        // un fichier l'index est déjà prêt (ou en cours de construction — le
        // didOpen attendra, mais le verrou synchronized de JdtCompilerEngine
        // n'est pas tenu pendant la construction).
        try {
            environment.jdt().preWarmIndex();
            LOG.info("[preWarmIndex] démarré en arrière-plan");
        } catch (Throwable t) {
            LOG.warning("[preWarmIndex] échec du démarrage: " + t);
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        LOG.info("shutdown");
        // Transition INITIALIZED → SHUTDOWN. La spec LSP impose que toute
        // requête autre que exit après shutdown soit rejetée ; les handlers
        // peuvent tester lifecycleState() pour cela.
        lifecycleState = LifecycleState.SHUTDOWN;

        // Libère les handles de jars, le FileSystem jrt et le ClassLoader
        // du jrt-fs.jar — sinon le GC d'ART finalise les ZipFile sans
        // close() et déclenche les warnings CloseGuard
        // ("A resource failed to call ZipFile.close") quand l'éditeur se
        // reconnecte sans arrêter le serveur précédent.
        try {
            environment.dispose();
        } catch (Throwable t) {
            LOG.warning("dispose échoué: " + t);
        }

        // Arrête les exécuteurs du service de documents pour éviter les
        // fuites de threads lors d'un redémarrage du serveur.
        try {
            textDocumentService.shutdownExecutors();
        } catch (Throwable t) {
            LOG.warning("textDocumentService.shutdownExecutors échoué: " + t);
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        LOG.info("exit");
        // Transition SHUTDOWN → EXITED. La spec LSP impose : exit code 0 si
        // shutdown a été reçu, 1 sinon.
        boolean shutdownReceived = (lifecycleState == LifecycleState.SHUTDOWN);
        lifecycleState = LifecycleState.EXITED;

        // Belt-and-suspenders : exit() peut être appelé sans shutdown() si
        // le client termine brutalement. On retente dispose() (idempotent).
        try {
            environment.dispose();
        } catch (Throwable t) {
            // Best-effort — ne pas masquer l'exit code.
            LOG.fine("exit dispose failed: " + t);
        }

        // En mode standalone, exit() termine le process. En mode in-process
        // (défaut), on ne fait PAS System.exit — l'hôte gère le cycle de vie
        // du process et System.exit tuerait l'application hôte.
        if (standaloneMode) {
            int exitCode = shutdownReceived ? 0 : 1;
            LOG.info("standalone mode: System.exit(" + exitCode + ")");
            System.exit(exitCode);
        }
        // En mode in-process, on retourne simplement — l'hôte peut détecter
        // lifecycleState() == EXITED pour décider de la suite.
    }

    /** Type de retour covariant ({@link NavTextDocumentService}). */
    @Override
    public NavTextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    // ── LanguageClient bridge ──────────────────────────────────────────

    /**
     * Appelé par le launcher LSP4J pour connecter le client distant.
     * Permet au serveur de pousser des notifications (publishDiagnostics,
     * showMessage, logMessage) vers le client.
     *
     * @param client le proxy client LSP4J
     */
    public void connect(LanguageClient client) {
        this.client = client;
        LOG.info("LanguageClient connected");
    }

    /**
     * @return le client LSP connecté, ou null si pas encore connecté.
     */
    public LanguageClient getClient() {
        return client;
    }

    /**
     * Publie les diagnostics pour une URI donnée via le LanguageClient.
     * Appelé par {@link JavaTextDocumentService} après didOpen/didChange.
     *
     * @param uri         l'URI du document
     * @param version     la version du document
     * @param diagnostics la liste des diagnostics LSP4J
     */
    public void publishDiagnostics(String uri, int version,
                                    java.util.List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        if (client == null) return;
        PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri, diagnostics);
        if (version >= 0) params.setVersion(version);
        client.publishDiagnostics(params);
    }

    // ── Accessors pour les tests et l'intégration ─────────────────────

    /** @return l'environnement de compilation */
    public CompilationEnvironment environment() { return environment; }

    // ── Helpers de logging (observabilité) ────────────────────────────

    /**
     * Trace un warning avec stack trace complète — permet aux services
     * (HoverService, NavigationService, etc.) de diagnostiquer les erreurs
     * internes d'ecj au lieu de les avaler silencieusement.
     *
     * @param message message descriptif (inclure l'URI/offset si pertinent)
     * @param t       la throwable attrapée (stack trace complète tracée)
     */
    public void logWarning(String message, Throwable t) {
        if (t instanceof jo.lspjava.engine.OperationCanceledException) {
            // Annulation — ne pas spammer les logs (cas normal).
            LOG.fine(() -> message + " [canceled]");
            return;
        }
        LOG.log(java.util.logging.Level.WARNING, message, t);
    }

    /** Log warning sans throwable. */
    public void logWarning(String message) {
        LOG.warning(message);
    }

    /** Log info pour usage interne. */
    public void logInfo(String message) {
        LOG.info(message);
    }

    // ── $/setTrace / $/logTrace ───────────────────────────────────────
    //
    // LSP4J gère automatiquement $/setTrace et $/logTrace au niveau du
    // RemoteEndpoint — ces notifications ne remontent pas à LanguageServer.
    // L'hôte peut cependant appeler setTraceValue() pour propager le trace
    // level demandé et ajuster le niveau de log Java en conséquence.

    private volatile String traceValue = org.eclipse.lsp4j.TraceValue.Off;

    /** Retourne le trace level courant. */
    public String traceValue() {
        return traceValue;
    }

    /**
     * Définit le trace level. Peut être appelé par l'hôte quand il reçoit
     * $/setTrace. Ajuste le niveau de log Java en conséquence.
     *
     * @param value le trace level ("off", "messages", "verbose")
     */
    public void setTraceValue(String value) {
        this.traceValue = value != null ? value : org.eclipse.lsp4j.TraceValue.Off;
        // Ajuste le niveau de log du logger principal.
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        if (org.eclipse.lsp4j.TraceValue.Verbose.equals(this.traceValue)) {
            root.setLevel(java.util.logging.Level.ALL);
        } else if (org.eclipse.lsp4j.TraceValue.Messages.equals(this.traceValue)) {
            root.setLevel(java.util.logging.Level.INFO);
        } else {
            root.setLevel(java.util.logging.Level.WARNING);
        }
        LOG.info("Trace level set to: " + this.traceValue);
    }
}
