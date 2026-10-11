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
        // Rien de spécifique à faire — lspjava n'a pas de configuration
        // dynamique pour l'instant (formatting, imports, etc. sont hardcoded).
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
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
        // Log si un executeCommand est reçu (pour diagnostic).
        // lspjava ne supporte aucune commande pour l'instant, mais au moins
        // on trace l'appel au lieu de retourner null silencieusement.
        if (params != null && params.getCommand() != null) {
            LOG.fine("executeCommand received: " + params.getCommand()
                    + " (not supported, returning null)");
        }
        return CompletableFuture.completedFuture(null);
    }
}
