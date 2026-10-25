package jo.lspjava.lsp;

import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
                // Invalide le cache de parse du MOTEUR DU MODULE du fichier
                // (ADR 0215) — chaque moteur a SON cache.
                if (environment.isEcjInitialisePour(filePath)) {
                    environment.jdtPourFichier(filePath).invalidateParseCache(filePath);
                    LOG.fine("didChangeWatchedFiles: invalidated parse cache for " + filePath);
                }
                // v1.9.1 : Created/Deleted — les caches de PRÉSENCE source
                // (négatifs readSourceOnDisk + walk FQN) doivent tomber pour
                // qu'une classe créée résolve et apparaisse en complétion
                // sans attendre les TTL (30 s). Rare : coût nul au repos.
                if (event.getType() != null
                        && (event.getType() == FileChangeType.Created
                                || event.getType() == FileChangeType.Deleted)) {
                    for (var moteur : environment.moteursVivants()) {
                        moteur.invalidateSourcePresence();
                    }
                    LOG.fine("didChangeWatchedFiles: source presence invalidated ("
                            + event.getType() + ") " + filePath);
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

    /**
     * Recherche de symboles dans l'espace de travail
     * ({@code workspace/symbol} — S1-c du plan de stabilisation §3.1,
     * audit §8.2 « écart au serveur tiers de référence : recherche de types ») : les TYPES (classes,
     * interfaces, enums, records) portant la requête en préfixe de nom
     * simple, du SUBSTRAT gradué existant (index disque des jars +
     * types des sources projet en cache TTL + image jrt).
     *
     * <p>Convention null/vide (I9) : capacité DISPONIBLE → liste vide
     * (jamais null). Localisations honnêtes (D3) : un type de SOURCE
     * pointe son fichier (plage du nom déclarant, heuristique textuelle
     * bornée) ; un type binaire (jar/jrt) sans source ouvrable est
     * ÉCARTÉ — S3 « mode profond » (sources-jars/décompilé) le servira ;
     * jamais d'URI factice.</p>
     */
    @Override
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<
            java.util.List<? extends SymbolInformation>,
            java.util.List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        verifierLifecycle("workspace/symbol");
        String requete = params != null && params.getQuery() != null
                ? params.getQuery().trim() : "";
        if (environment == null || requete.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(java.util.List.of()));
        }
        try {
            // ADR 0215 : recherche UNIE — le DÉFAUT d'abord (construction
            // paresseuse, miroir du comportement historique : il peut
            // être nourri par replaceAll sans apparaître dans la map des
            // modules), puis les moteurs de MODULES VIVANTS (jamais
            // construits pour une sonde) ; plafond global réparti,
            // dédupliqué.
            java.util.List<jo.lspjava.ecj.EcjCompilerEngine> moteurs =
                    new java.util.ArrayList<>();
            moteurs.add(environment.jdt());
            for (jo.lspjava.ecj.EcjCompilerEngine moteur : environment.moteursVivants()) {
                if (!moteurs.contains(moteur)) moteurs.add(moteur);
            }
            java.util.List<String> fqns = new java.util.ArrayList<>(PLAFOND_SYMBOLES);
            java.util.Set<String> vus = new java.util.HashSet<>();
            for (jo.lspjava.ecj.EcjCompilerEngine moteur : moteurs) {
                if (fqns.size() >= PLAFOND_SYMBOLES) break;
                for (String fqn : moteur.symbolesEspace(requete, PLAFOND_SYMBOLES - fqns.size())) {
                    if (vus.add(fqn)) {
                        fqns.add(fqn);
                        if (fqns.size() >= PLAFOND_SYMBOLES) break;
                    }
                }
            }
            java.util.List<SymbolInformation> sortie = new java.util.ArrayList<>(fqns.size());
            for (String fqn : fqns) {
                SymbolInformation symbole = symboleDe(fqn);
                if (symbole != null) {
                    sortie.add(symbole);
                }
            }
            return CompletableFuture.completedFuture(Either.forLeft(sortie));
        } catch (Throwable t) {
            if (server != null) server.logWarning("workspace/symbol failed", t);
            return CompletableFuture.completedFuture(Either.forLeft(java.util.List.of()));
        }
    }

    /** Plafond de la recherche d'espace (palette bornée, miroir du serveur tiers de référence). */
    private static final int PLAFOND_SYMBOLES = 64;

    /**
     * Symbole LSP d'un FQCN : nom simple, kind Class (le substrat
     * nomsParPrefixe ne distingue pas classe/interface/enum — under-
     * rapport honnête), conteneur = paquet, localisation SOURCE seule.
     * {@code null} pour un type binaire sans source (écarté — D3).
     */
    private SymbolInformation symboleDe(String fqn) {
        int dot = fqn.lastIndexOf('.');
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        String paquet = dot < 0 ? "" : fqn.substring(0, dot);
        java.nio.file.Path source = null;
        // ADR 0215 : source attachée du PREMIER moteur qui la connaît
        // (défaut paresseux d'abord — miroir historique — puis modules
        // vivants).
        if (environment.isEcjInitialized()) source = environment.jdt().fichierSourceDe(fqn);
        if (source == null) {
            for (jo.lspjava.ecj.EcjCompilerEngine moteur : environment.moteursVivants()) {
                source = moteur.fichierSourceDe(fqn);
                if (source != null) break;
            }
        }
        if (source == null) return null;
        Location location =
                new Location(source.toUri().toString(), plageDeclaration(source, simple));
        SymbolInformation symbole = new SymbolInformation(simple, SymbolKind.Class, location);
        if (!paquet.isEmpty()) symbole.setContainerName(paquet);
        return symbole;
    }

    /**
     * Plage du NOM déclarant dans son fichier — heuristique textuelle
     * bornée (le mot-clé {@code class|interface|enum|record|@interface}
     * suivi du nom), repli plage 0 (le client ouvre le fichier en tête
     * : sous-rapport volontaire, jamais une plage fausse).
     */
    private static Range plageDeclaration(java.nio.file.Path fichier, String nom) {
        try {
            String texte = new String(java.nio.file.Files.readAllBytes(fichier),
                    java.nio.charset.StandardCharsets.UTF_8);
            int nomIndex = -1;
            for (String motCle : new String[] {
                    "class ", "interface ", "enum ", "record ", "@interface "}) {
                int depuis = 0;
                while ((depuis = texte.indexOf(motCle, depuis)) >= 0) {
                    int candidat = motCle.length() + depuis;
                    candidat = sauterBlancs(texte, candidat);
                    if (nomCommence(texte, candidat, nom)) {
                        nomIndex = candidat;
                        break;
                    }
                    depuis += motCle.length();
                }
                if (nomIndex >= 0) break;
            }
            if (nomIndex < 0) return new Range(new Position(0, 0), new Position(0, 0));
            int ligne = 0;
            int colonne = 0;
            for (int i = 0; i < nomIndex; i++) {
                if (texte.charAt(i) == '\n') {
                    ligne++;
                    colonne = 0;
                } else {
                    colonne++;
                }
            }
            Position debut = new Position(ligne, colonne);
            return new Range(debut, new Position(ligne, colonne + nom.length()));
        } catch (Throwable t) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
    }

    /** Index du premier caractère non blanc à partir de [depuis]. */
    private static int sauterBlancs(String texte, int depuis) {
        int i = depuis;
        while (i < texte.length()
                && Character.isWhitespace(texte.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Vrai si le texte porte EXACTEMENT [nom] à [index] (borne identifiant). */
    private static boolean nomCommence(String texte, int index, String nom) {
        if (!texte.startsWith(nom, index)) return false;
        int apres = index + nom.length();
        if (apres >= texte.length()) return true;
        char suivant = texte.charAt(apres);
        return !Character.isJavaIdentifierPart(suivant);
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
