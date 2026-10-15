package jo.lspjava.lsp;

import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Service de workspace LSP pour Java.
 * Gère les événements de workspace (changement de configuration, fichiers).
 *
 * <p>{@code didChangeWatchedFiles} n'est pas un no-op : quand un fichier
 * .java est modifié hors de l'éditeur (git pull, édition externe), on
 * invalide le cache de parse pour que les prochaines requêtes LSP
 * rechargent le fichier depuis le disque.</p>
 *
 * @author jo@Dev
 */
public final class JavaWorkspaceService implements WorkspaceService {

    private static final Logger LOG = Logger.getLogger("JavaWorkspaceService");

    private final CompilationEnvironment environment;
    private final JavaLanguageServer server;

    /** Constructeur historique (sans environnement) — didChangeWatchedFiles
     *  reste un no-op. */
    public JavaWorkspaceService() {
        this.environment = null;
        this.server = null;
    }

    /** Constructeur avec environnement pour permettre
     *  l'invalidation du cache sur didChangeWatchedFiles. */
    public JavaWorkspaceService(CompilationEnvironment environment, JavaLanguageServer server) {
        this.environment = environment;
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // Garde lifecycle (I2) : notification ignorée hors fenêtre INITIALIZED
        // (spec 3.17 — les notifications ne sont pas rejetées en erreur).
        if (!notificationsAcceptees("didChangeConfiguration")) return;
        // Rien de spécifique à faire — lspjava n'a pas de configuration
        // dynamique pour l'instant (formatting, imports, etc. sont hardcoded).
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // Garde lifecycle (I2) : notification ignorée hors fenêtre INITIALIZED.
        if (!notificationsAcceptees("didChangeWatchedFiles")) return;
        // Invalide le cache de parse pour les fichiers .java modifiés hors
        // de l'éditeur — sans cette invalidation, les modifications
        // externes ne seraient pas prises en compte jusqu'à un redémarrage.
        if (environment == null || params == null || params.getChanges() == null) {
            return;
        }
        for (FileEvent event : params.getChanges()) {
            if (event == null || event.getUri() == null) continue;
            String uri = event.getUri();
            // Filtre : ne traite que les fichiers .java.
            if (!uri.endsWith(".java")) continue;
            try {
                String filePath = uri.startsWith("file://")
                        ? java.net.URLDecoder.decode(uri.substring(7),
                                java.nio.charset.StandardCharsets.UTF_8)
                        : uri;
                // Invalide le cache de parse si le moteur est initialisé.
                if (environment.isEcjInitialized()) {
                    environment.jdt().invalidateParseCache(filePath);
                    LOG.fine("didChangeWatchedFiles: invalidated parse cache for " + filePath);
                }
            } catch (Throwable t) {
                if (server != null) {
                    server.logWarning("didChangeWatchedFiles failed for " + uri, t);
                } else {
                    LOG.warning("didChangeWatchedFiles failed for " + uri + ": " + t);
                }
            }
        }
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        // Garde lifecycle (I2) : requête rejetée ServerNotInitialized /
        // InvalidRequest hors fenêtre INITIALIZED (LSP4J préserve le code
        // de ResponseErrorException dans la réponse d'erreur).
        verifierLifecycle("executeCommand");
        // Log si un executeCommand est reçu (pour diagnostic).
        // lspjava ne supporte aucune commande pour l'instant, mais au moins
        // on trace l'appel au lieu de retourner null silencieusement.
        if (params != null && params.getCommand() != null) {
            LOG.fine("executeCommand received: " + params.getCommand()
                    + " (not supported, returning null)");
        }
        return CompletableFuture.completedFuture(null);
    }

    // ── Garde lifecycle centralisée (I2) ─────────────────────────────
    /**
     * Miroir exact de la garde de {@link JavaTextDocumentService} — les
     * deux services partagent le contrat lifecycle du serveur. Le
     * constructeur historique sans serveur ({@code server == null})
     * court-circuite la garde (façade de test).
     */
    private void verifierLifecycle(String methode) {
        if (server == null) return;
        JavaLanguageServer.LifecycleState etat = server.lifecycleState();
        if (etat == JavaLanguageServer.LifecycleState.INITIALIZED) return;
        boolean avantInitialisation = etat == JavaLanguageServer.LifecycleState.UNINITIALIZED
                || etat == JavaLanguageServer.LifecycleState.INITIALIZING;
        int code = avantInitialisation
                ? org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ServerNotInitialized.getValue()
                : org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest.getValue();
        String message = avantInitialisation
                ? "Requête " + methode + " reçue avant initialized (état=" + etat + ") — serveur non initialisé."
                : "Requête " + methode + " reçue après shutdown (état=" + etat + ") — serveur arrêté.";
        throw new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(code, message, null));
    }

    /** Miroir du filtre de notifications de {@link JavaTextDocumentService}. */
    private boolean notificationsAcceptees(String methode) {
        if (server == null) return true;
        JavaLanguageServer.LifecycleState etat = server.lifecycleState();
        if (etat == JavaLanguageServer.LifecycleState.INITIALIZED) return true;
        LOG.fine("notification " + methode + " ignorée (état=" + etat + ")");
        return false;
    }
}
