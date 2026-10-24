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

    // Immutable après applyClientCapabilities : créé une seule fois et
    // réutilisé. NB (I3) : un SECOND initialize est désormais rejeté en
    // InvalidRequest (spec LSP) — le provider n'est donc écrit qu'une fois.
    private final ServerCapabilitiesProvider capabilitiesProvider = new ServerCapabilitiesProvider();

    // Version du moteur (I7) : lue depuis la ressource générée du build
    // (jo/lspjava/pack/version.properties — même convention que les packs
    // lsp:kotlin / lsp:xml). Jamais null : repli « inconnue » tracé.
    private static final String VERSION_MOTEUR = lireVersionMoteur();

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
        this(new CompilationEnvironment(), null);
    }

    /**
     * Constructeur avec classpath personnalisé (depuis Gradle Tooling).
     *
     * @param environment l'environnement de compilation (classpath + source dirs)
     */
    public JavaLanguageServer(CompilationEnvironment environment) {
        this(environment, null);
    }

    /** Rappel de la façade hôte à l'arrêt (désinscription du registre). */
    private final Runnable aLArret;

    /**
     * Constructeur complet (S1-d « watched files côté hôte ») : la façade
     * hôte s'inscrit pour être PRÉVENUE à l'arrêt du serveur
     * (shutdown/exit) — elle retire alors le serveur de son registre des
     * vivants (aucun serveur mort ne reçoit d'événements de fichiers).
     *
     * @param environment l'environnement de compilation (classpath + source dirs)
     * @param aLArret rappel de décompte à shutdown/exit (idempotent, peut
     *        être null — même contrat que KotlinLanguageServer)
     */
    public JavaLanguageServer(CompilationEnvironment environment, Runnable aLArret) {
        this.environment = environment;
        this.aLArret = aLArret != null ? aLArret : () -> { };
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
        // I3 — initialize réentrant : la spec attend une ERREUR (le client
        // ne doit initialiser qu'une fois). Un second appel est rejeté en
        // InvalidRequest — LSP4J préserve le code ResponseErrorException
        // (RemoteEndpoint.DEFAULT_EXCEPTION_HANDLER). L'ancien warning
        // re-transitionnait l'état : un client buggé pouvais ré-initialiser
        // un serveur en fin de vie sans le savoir.
        if (lifecycleState != LifecycleState.UNINITIALIZED) {
            return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest.getValue(),
                            "initialize déjà appelé (état=" + lifecycleState
                                    + ") — un serveur LSP est initialisé une seule fois.",
                            null)));
        }
        // Transition d'état UNINITIALIZED → INITIALIZING.
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

        // serverInfo dans InitializeResult (recommandé LSP 3.17) — la
        // version est celle du DÉPÔT (I7) : plusieurs builds coexistent sur
        // un appareil, une version figée rend les journaux trompeurs.
        ServerInfo serverInfo = new ServerInfo();
        serverInfo.setName("lspjava");
        serverInfo.setVersion(VERSION_MOTEUR);
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
        // didOpen attendra, mais le verrou synchronized de EcjCompilerEngine
        // n'est pas tenu pendant la construction).
        // ADR 0215 : défaut + moteurs des modules NOURRIS — le premier
        // didOpen d'un fichier de :app ne paie plus le cold start de SON
        // moteur (racines sources/jars du modèle).
        try {
            environment.prechaufferTousLesMoteurs();
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

        // S1-d : la façade hôte retire ce serveur de ses vivants.
        try {
            aLArret.run();
        } catch (Throwable t) {
            LOG.warning("aLArret (shutdown) échoué: " + t);
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
        // S1-d : désinscription côté façade (ceinture et bretelles — le
        // rappel est idempotent, un serveur déjà retiré ne l'est plus).
        try {
            aLArret.run();
        } catch (Throwable t) {
            LOG.warning("aLArret (exit) échoué: " + t);
        }
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

    /**
     * @return les clientCapabilities reçues à initialize (null avant).
     *         Consultées par les services pour la négociation fine
     *         (renommage {@code documentChanges} — I8).
     */
    public ClientCapabilities capabilitiesClient() {
        return capabilitiesProvider.clientCapabilities();
    }

    /**
     * @return la version du moteur lue depuis la ressource du build
     *         (I7) — celle annoncée dans {@code serverInfo.version}.
     */
    public static String versionMoteur() {
        return VERSION_MOTEUR;
    }

    /**
     * Lit la version depuis {@code jo/lspjava/pack/version.properties}
     * (classpath). Le repli « inconnue » est TRACÉ : le descripteur fait
     * partie du build, son absence est un défaut de packaging (même
     * discipline que les packs lsp:kotlin / lsp:xml).
     */
    private static String lireVersionMoteur() {
        try (var flux = JavaLanguageServer.class.getClassLoader()
                .getResourceAsStream("jo/lspjava/pack/version.properties")) {
            if (flux == null) {
                LOG.warning("jo/lspjava/pack/version.properties absente du classpath — version annoncée « inconnue »");
                return "inconnue";
            }
            var proprietes = new java.util.Properties();
            proprietes.load(flux);
            String v = proprietes.getProperty("version");
            if (v == null || v.isBlank()) {
                LOG.warning("jo/lspjava/pack/version.properties sans clé version — version annoncée « inconnue »");
                return "inconnue";
            }
            return v.trim();
        } catch (Throwable t) {
            LOG.warning("lecture de jo/lspjava/pack/version.properties échouée : " + t);
            return "inconnue";
        }
    }

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
