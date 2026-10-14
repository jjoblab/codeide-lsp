package jo.lspjava.lsp;

import jo.lspjava.api.DecompiledSourceParams;
import jo.lspjava.api.DecompiledSourceResult;
import jo.lspjava.api.NavigationTarget;
import jo.lspjava.api.Severity;
import jo.lspjava.api.TextRange;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.services.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service de documents texte LSP pour Java.
 *
 * <p>Implémente les requêtes LSP standard :
 * <ul>
 *   <li>{@code didOpen} / {@code didChange} / {@code didClose} — gestion du cycle de vie du document</li>
 *   <li>{@code completion} — complétion contextuelle (keywords + scope + member access)</li>
 *   <li>{@code hover} — documentation et signature</li>
 *   <li>{@code definition} — go-to-definition</li>
 *   <li>{@code typeDefinition} — go-to-type-declaration</li>
 *   <li>{@code implementation} — go-to-implementations, multi-fichiers</li>
 *   <li>{@code superDefinition} — go-to-super, méthode personnalisée,
 *       multi-fichiers (cf. {@link NavTextDocumentService})</li>
 *   <li>{@code codeAction} — quick-fixes</li>
 * </ul>
 *
 * <p>Publie les diagnostics après chaque {@code didOpen}/{@code didChange} via
 * {@link JavaLanguageServer#publishDiagnostics}. Les diagnostics sont calculés
 * par {@link DiagnosticService} et convertis au format LSP4J.</p>
 *
 * @author jo@Dev
 */
public final class JavaTextDocumentService implements NavTextDocumentService {

    private final CompilationEnvironment environment;
    private final DiagnosticService diagnosticService;
    private final HoverService hoverService;
    private final NavigationService navigationService;
    private final CodeActionService codeActionService;
    // ConcurrentHashMap pour la thread-safety : lus/écrits depuis le cached
    // pool LSP4J (un HashMap nu exposerait une race sur getOrDefault+put dans
    // didChange et un ConcurrentModificationException possible dans
    // buildOpenBufferOverlay qui itère).
    private final java.util.concurrent.ConcurrentHashMap<String, String> openDocuments = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> documentVersions = new java.util.concurrent.ConcurrentHashMap<>();

    /** Référence au serveur — pour publier les diagnostics. */
    private final JavaLanguageServer server;

    // ── Live-republish post-sync ───────────────────────────────────────
    /**
     * Exécuteur dédié à la republication des diagnostics après un remplacement
     * du contexte (replaceAll effectif suite au sync Gradle). Mono-thread,
     * daemon : les passes se sérialisent, le scheduler interne du moteur
     * garde la priorité de la frappe interactive.
     *
     * <p>Champ d'INSTANCE (pas statique) : arrêté par
     * {@link #shutdownExecutors()} au shutdown du serveur, et recréé avec
     * l'instance suivante — un champ statique shutdown définitivement
     * tuerait silencieusement la fonctionnalité au redémarrage du serveur
     * dans la même JVM (RejectedExecutionException avalée).</p>
     */
    private final java.util.concurrent.ExecutorService republishExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lsp-republish-diagnostics");
                t.setDaemon(true);
                return t;
            });
    /** Garde anti-stampede : une seule vague de republication en vol. */
    private final java.util.concurrent.atomic.AtomicBoolean republishInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ── Debounce côté serveur sur didChange ────────────────────────────
    /**
     * Debounce des diagnostics post-didChange : attend 50 ms après le dernier
     * didChange avant de publier. Évite les diagnostics inutiles en frappe
     * rapide (le debounce client n'est pas garanti par la spec LSP).
     *
     * <p>Map : filePath → ScheduledFuture (le diagnostic en attente).
     * Annulé et remplacé à chaque didChange pour le même fichier.</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> pendingDiagnostics =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Scheduler mono-thread pour les diagnostics debouncés. */
    private final java.util.concurrent.ScheduledExecutorService diagnosticsDebouncer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lsp-diagnostics-debouncer");
                t.setDaemon(true);
                return t;
            });
    /** Délai de debounce : 50 ms (court pour la réactivité, assez pour grouper la frappe). */
    private static final long DIAGNOSTICS_DEBOUNCE_MS = 50;

    public JavaTextDocumentService(CompilationEnvironment environment, JavaLanguageServer server) {
        this.environment = environment;
        this.server = server;
        this.diagnosticService = new DiagnosticService(environment);
        this.hoverService = new HoverService(environment);
        this.navigationService = new NavigationService(environment);
        // Le service d'actions reçoit l'ENVIRONNEMENT — les fixes
        // « Importer X » interrogent l'index de noms de classes (portage
        // AddImportQuickFixProvider du projet de référence).
        this.codeActionService = new CodeActionService(environment);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        openDocuments.put(uri, text);
        documentVersions.put(uri, new AtomicInteger(0));
        // Lot h4 (v0.101.0) : sans plateforme (classpath vide, jrt absent),
        // le régime instantané sert la SYNTAXE seule + les symboles du
        // fichier — une ligne honnête l'annonce une fois par document
        // (le live-republish remplacera tout au classpath suivant).
        try {
            if (!environment.jdt().isLikelyUsable() && server != null) {
                server.logInfo("[h4] plateforme absente — mode instantané : "
                        + "diagnostics de syntaxe + symboles du fichier ("
                        + uriToPath(uri) + ")");
            }
        } catch (Throwable ignore) {
            // Best-effort — le diagnostic suit son cours normal.
        }
        // Si l'index n'est pas encore prêt (pre-warming en cours), on
        // publie des diagnostics vides immédiatement pour ne pas bloquer
        // l'éditeur, puis on re-planifie via le debounce standard (50 ms)
        // pour laisser le temps à l'index de se construire — didOpen ne
        // doit jamais bloquer 2-6 s sur buildJarIndex.
        if (environment.isEcjInitialized()) {
            try {
                var jdt = environment.jdt();
                if (!jdt.isIndexReady()) {
                    // Index en cours de construction — publier vide + retry.
                    server.publishDiagnostics(uri, 0, Collections.emptyList());
                    scheduleDebouncedDiagnostics(uri, 0, text);
                    return;
                }
            } catch (Throwable ignored) {
                // Best-effort — fallback sur publishDiagnostics normal.
            }
        }
        publishDiagnostics(uri, 0, text);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        Integer versionBoxed = params.getTextDocument().getVersion();
        int newVersion = versionBoxed != null ? versionBoxed : 0;

        // Validation atomique de la version : si un didChange arrive avec une
        // version obsolète (race réseau, client buggé), on l'ignore pour éviter
        // d'écraser un état plus récent par un état plus ancien. Le set() sur
        // l'AtomicInteger existant rend le check-then-act atomique sans recréer
        // l'entrée (deux didChange concurrents ne peuvent pas passer tous deux).
        AtomicInteger existingVersion = documentVersions.get(uri);
        if (existingVersion != null && newVersion <= existingVersion.get()) {
            return; // Version obsolète — on ignore.
        }

        // compute() pour l'atomicité de la transformation incrémentale : le
        // segment est verrouillé pendant toute l'application des éditions
        // (deux didChange concurrents depuis le cached pool LSP4J).
        final int fNewVersion = newVersion;
        String current = openDocuments.compute(uri, (k, prev) -> {
            String cur = prev != null ? prev : "";
            // Application INCRÉMENTALE : chaque ContentChangeEvent porte
            // soit un Range (édition locale, cas nominal avec
            // TextDocumentSyncKind.Incremental), soit le texte complet
            // (fallback universel : clients legacy, recalculs globaux).
            for (var change : params.getContentChanges()) {
                if (change.getRange() == null) {
                    // Sync FULL (sans range) — remplace tout.
                    cur = change.getText();
                } else {
                    int start = toOffset(cur, change.getRange().getStart());
                    int end = toOffset(cur, change.getRange().getEnd());
                    // Garde-fou robustesse : un client buggé peut donner des
                    // bornes incohérentes — clamp explicite (start<0, end<start,
                    // end>length) au lieu de jeter.
                    if (start < 0 || end > cur.length() || end < start) {
                        // Log silencieux — ne pas spammer les logs sur des clients buggés.
                        // Clamp conservateur.
                        if (start < 0) start = 0;
                        if (end < start) end = start;
                        if (end > cur.length()) end = cur.length();
                    }
                    String insertion = change.getText() == null ? "" : change.getText();
                    cur = cur.substring(0, start) + insertion + cur.substring(end);
                }
            }
            return cur;
        });
        documentVersions.computeIfAbsent(uri, k -> new AtomicInteger(0)).set(fNewVersion);
        String text = openDocuments.getOrDefault(uri, "");
        // Cache de parse : le tampon a changé, l'arbre résolu en cache est
        // désormais périmé — sans cette invalidation, hover/highlight après
        // la frappe pointeraient sur les offsets de l'ancienne version.
        // Guard contre l'initialisation paresseuse de jdt() : sans lui,
        // environment.jdt() pourrait déclencher la construction synchrone
        // de EcjCompilerEngine (et donc buildJarIndex) sur le thread LSP4J.
        try {
            if (environment.isEcjInitialized()) {
                environment.jdt().invalidateParseCache(uriToPath(uri));
            }
        } catch (Throwable t) {
            // Best-effort — le cache se fera remplacer à la prochaine
            // resolveUnit. On trace plutôt que d'avaler silencieusement.
            if (server != null) server.logWarning("didChange invalidateParseCache failed for " + uri, t);
        }
        // Debounce des diagnostics : on ne publie pas immédiatement, on
        // attend 50 ms. Si un nouveau didChange arrive dans cette fenêtre,
        // le précédent est annulé et remplacé (le debounce client n'est
        // pas garanti par la spec LSP).
        scheduleDebouncedDiagnostics(uri, fNewVersion, text);
    }

    /**
     * Planifie la publication des diagnostics après debounce.
     * Annule et remplace tout diagnostic en attente pour le même fichier.
     */
    private void scheduleDebouncedDiagnostics(String uri, int version, String text) {
        // Annule le diagnostic en attente s'il existe.
        java.util.concurrent.ScheduledFuture<?> prev = pendingDiagnostics.get(uri);
        if (prev != null) {
            prev.cancel(false);
        }
        // Planifie le nouveau.
        Runnable task = () -> {
            try {
                pendingDiagnostics.remove(uri);
                publishDiagnostics(uri, version, text);
            } catch (Throwable t) {
                if (server != null) server.logWarning("debounced publishDiagnostics failed for " + uri, t);
            }
        };
        java.util.concurrent.ScheduledFuture<?> future =
                diagnosticsDebouncer.schedule(task, DIAGNOSTICS_DEBOUNCE_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
        pendingDiagnostics.put(uri, future);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(uri);
        documentVersions.remove(uri);
        // Nettoie les diagnostics du document fermé.
        server.publishDiagnostics(uri, -1, Collections.emptyList());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // didSave : le buffer vivant est déjà à jour via didChange — on
        // invalide juste le cache de parse pour forcer un re-diagnostic
        // propre. Si includeText=true, params.getText() contient le texte
        // sauvegardé (divergent du buffer si édition externe).
        if (params == null || params.getTextDocument() == null) return;
        String uri = params.getTextDocument().getUri();
        try {
            String filePath = uriToPath(uri);
            // Invalide le cache de parse pour forcer un re-diagnostic.
            if (environment.isEcjInitialized()) {
                environment.jdt().invalidateParseCache(filePath);
            }
            // Re-publie les diagnostics avec le texte sauvegardé si fourni.
            String text = params.getText();
            if (text != null) {
                String current = openDocuments.getOrDefault(uri, "");
                // Si le texte sauvegardé diffère du buffer vivant, on met à jour.
                if (!text.equals(current)) {
                    openDocuments.put(uri, text);
                }
                AtomicInteger v = documentVersions.get(uri);
                int version = v != null ? v.get() : 0;
                publishDiagnostics(uri, version, text);
            }
        } catch (Throwable t) {
            if (server != null) server.logWarning("didSave failed for " + uri, t);
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());

        // Complétion ecj — membres/portée (bindings), types du classpath
        // (index), mots-clés, imports.
        List<jo.lspjava.api.CompletionItem> items = new ArrayList<>();
        // Flag isIncomplete : doit être true si la complétion a été
        // annulée/superseded/timed out, pour que le client
        // sache qu'il doit re-demander la complétion à la prochaine frappe.
        boolean incomplete = false;
        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                // Soumet la complétion au EcjEngineScheduler.
                String filePath = uriToPath(uri);
                try {
                    var future = jdt.scheduler().submitInteractive(filePath,
                            () -> {
                                var completionEngine = new jo.lspjava.ecj.EcjCompletionEngine(jdt);
                                return completionEngine.complete(filePath, source, offset);
                            });
                    List<jo.lspjava.api.CompletionItem> jdtItems = null;
                    try {
                        jdtItems = future.get(
                                jo.lspjava.engine.EcjEngineScheduler.INTERACTIVE_TIMEOUT_MS,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        // Completion trop lente → isIncomplete=true.
                        incomplete = true;
                    } catch (java.util.concurrent.CancellationException ce) {
                        // Superseded → isIncomplete=true.
                        incomplete = true;
                    } catch (java.util.concurrent.ExecutionException ee) {
                        // La tâche a jeté → isIncomplete=true.
                        incomplete = true;
                    }
                    if (jdtItems != null) items = jdtItems;
                } catch (Throwable t) {
                    // Scheduler indisponible — fallback synchrone ci-dessous.
                    if (server != null) server.logWarning("completion scheduler failed for " + uriToPath(uri), t);
                    var completionEngine = new jo.lspjava.ecj.EcjCompletionEngine(jdt);
                    List<jo.lspjava.api.CompletionItem> jdtItems =
                            completionEngine.complete(filePath, source, offset);
                    if (jdtItems != null) items = jdtItems;
                }
            } else {
                // Lot h4 « LSP instantané » (v0.101.0) : plateforme absente
                // (avant la première sync, jrt absent) — le régime de repli
                // propose les SYMBOLES DÉCLARÉS du fichier (parse seul :
                // types, méthodes, champs, paramètres/locales de la
                // méthode englobante) et les mots-clés du langage. Aucune
                // devinette : pas un type du JDK — le régime plein les
                // apportera avec le classpath.
                try {
                    var instantanee = new jo.lspjava.ecj.EcjSyntaxCompletion(jdt);
                    items = instantanee.complete(uriToPath(uri), source, offset);
                } catch (Throwable t) {
                    if (server != null) server.logWarning("completion instantanée échouée pour " + uri, t);
                }
            }
        } catch (Throwable t) {
            // Serveur indisponible — liste vide.
            if (server != null) server.logWarning("completion handler failed for " + uri, t);
            // Servir incomplet pour que le client réessaie.
            incomplete = true;
        }
        List<CompletionItem> lspItems = new ArrayList<>();
        for (jo.lspjava.api.CompletionItem item : items) {
            lspItems.add(toLspCompletionItem(item, source));
        }

        // isIncomplete=true signale au client que la liste
        // n'est pas fiable (annulation, timeout, erreur) et qu'il doit
        // re-demander à la prochaine frappe. Le client LSP qui respecte
        // la spec cache uniquement les complétions isIncomplete=false.
        CompletionList list = new CompletionList(incomplete, lspItems);
        return CompletableFuture.completedFuture(Either.forRight(list));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        return CompletableFuture.completedFuture(item);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Passage par le scheduler interactif avec timeout 3 s — un appel
        // synchrone synchronized pourrait bloquer jusqu'à 15 s si un
        // diagnostic long tient le verrou EcjCompilerEngine.
        return submitToScheduler(filePath, () -> {
            try {
                // Chemin JDT-first (bindings ecj).
                String content = hoverService.hoverAt(filePath, source, offset);
                if (content != null) {
                    return new Hover(List.of(Either.forLeft(content)));
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("hover handler failed for " + filePath, t);
            }
            return null;
        }, null);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            try {
                // Chemin JDT-first (bindings ecj).
                TextRange range = navigationService.findDefinition(filePath, source, offset);
                if (range != null) {
                    Location location = new Location(uri, toLspRange(source, range));
                    return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(location));
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("definition handler failed for " + filePath, t);
            }
            // Fallback binaire : la cible n'est pas dans les sources du
            // projet (ex. java.util.HashMap, java.lang.Thread, une classe
            // d'un JAR du classpath). On résout le FQN du type déclarant au
            // caret, on vérifie qu'il est bien binaire (index classpath OU
            // source attachée), puis on renvoie une Location synthétique à URI
            // jdt://decompiled/<fqcn>.java. Le client appelle alors
            // textDocument/decompiledSource(fqcn) pour récupérer le texte.
            try {
                Location decompiled = binaryDefinitionLocation(filePath, source, offset, false);
                if (decompiled != null) {
                    return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(decompiled));
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("definition binary fallback failed for " + filePath, t);
            }
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
        }, Either.forLeft(List.of()));
    }

    /**
     * {@code textDocument/typeDefinition} : la déclaration du TYPE du symbole
     * au caret ({@code Foo x = …} → {@code class Foo}). Nourrit la section
     * GO TO du menu contextuel unifié (portage du NavMenu du projet de référence).
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            try {
                TextRange range = navigationService.findTypeDefinition(filePath, source, offset);
                if (range != null) {
                    Location location = new Location(uri, toLspRange(source, range));
                    return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(location));
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("typeDefinition handler failed for " + filePath, t);
            }
            // Fallback binaire.
            try {
                Location decompiled = binaryDefinitionLocation(filePath, source, offset, true);
                if (decompiled != null) {
                    return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(decompiled));
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("typeDefinition binary fallback failed for " + filePath, t);
            }
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
        }, Either.forLeft(List.of()));
    }

    /**
     * {@code textDocument/implementation} : les héritiers directs du type en
     * contexte au caret (section GO TO « Implementations » du menu contextuel
     * unifié — portage des {@code implementationTargets} du projet de référence).
     * Multi-fichiers : chaque cible est une Location dans le fichier déclarant
     * (plage du NOM convertie via le TEXTE de ce fichier, pas celui du
     * document courant) — le balayage consulte l'overlay des tampons ouverts
     * avant le disque (même pattern que le renommage).
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            try {
                java.util.Map<String, String> overlay = buildOpenBufferOverlay();
                List<NavigationTarget> hits = navigationService.findImplementations(
                        filePath, source, offset, overlay);
                if (!hits.isEmpty()) {
                    List<Location> locations = new ArrayList<>();
                    for (NavigationTarget hit : hits) {
                        String text = textForNavigation(hit.path, overlay);
                        if (text == null) continue;
                        locations.add(new Location(pathToUri(hit.path),
                                new Range(toPosition(text, hit.start),
                                        toPosition(text, hit.end))));
                    }
                    if (!locations.isEmpty()) {
                        return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(locations);
                    }
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("implementation handler failed for " + filePath, t);
            }
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
        }, Either.forLeft(List.of()));
    }

    /**
     * {@code textDocument/superDefinition} (méthode personnalisée, cf.
     * {@link NavTextDocumentService}) : les cibles GO TO « Super » au caret.
     * Le DTO transporte l'offset exact du nom dans le fichier déclarant —
     * aucun round-trip Position↔offset à risquer côté client.
     */
    @Override
    public CompletableFuture<List<NavigationTarget>> superDefinition(TextDocumentPositionParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            try {
                java.util.Map<String, String> overlay = buildOpenBufferOverlay();
                List<NavigationTarget> hits = navigationService.findSuper(
                        filePath, source, offset, overlay);
                return hits;
            } catch (Throwable t) {
                if (server != null) server.logWarning("superDefinition handler failed for " + filePath, t);
                return List.of();
            }
        }, List.of());
    }

    /**
     * {@code textDocument/decompiledSource} (méthode personnalisée, cf.
     * {@link NavTextDocumentService}) : la source
     * (attachée ou stub décompilé) d'un FQN binaire. Appelée par le client
     * ({@code :cel-lsp}) après que {@code definition}/{@code typeDefinition}
     * a renvoyé une Location à URI {@code jdt://decompiled/<fqcn>.java}.
     *
     * <p>L'appel passe par le moteur JDT in-process — pas de round-trip
     * supplémentaire : {@link jo.lspjava.ecj.EcjCompilerEngine#decompiledSource}
     * consulte d'abord la source attachée (src.zip du JDK, -sources.jar du
     * classpath, caches Gradle — fidélité maximale), puis retombe sur le
     * stub décompilé depuis le ClassFileReader binaire.</p>
     */
    @Override
    public CompletableFuture<DecompiledSourceResult> decompiledSource(DecompiledSourceParams params) {
        if (params == null) {
            return CompletableFuture.completedFuture(new DecompiledSourceResult(null));
        }
        try {
            String fqcn = params.getFqcn();
            String src = environment.jdt().decompiledSource(fqcn);
            return CompletableFuture.completedFuture(new DecompiledSourceResult(src));
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(new DecompiledSourceResult(null));
        }
    }

    /**
     * Synthétise une {@link Location} à URI
     * {@code jdt://decompiled/<fqcn-path>.java} pour le fallback binaire de
     * {@code definition} et {@code typeDefinition}.
     *
     * <p>Étapes :</p>
     * <ol>
     *   <li>extrait le FQN au caret via {@link jo.lspjava.ecj.EcjCompilerEngine#fqcnAt}
     *       (targetType=false : FQN de la classe déclarant le membre au caret ;
     *       targetType=true : FQN du TYPE du symbole au caret) ;</li>
     *   <li>vérifie que le FQN est binaire via
     *       {@link jo.lspjava.ecj.EcjCompilerEngine#isBinaryType} (index
     *       classpath OU source attachée) — sinon null (la cible est dans
     *       les sources du projet, le chemin standard JDT-first l'a déjà
     *       traitée) ;</li>
     *   <li>construit l'URI {@code jdt://decompiled/<fqcn-with-slashes>.java}
     *       avec une plage vide (0:0-0:0) — le buffer décompilé n'a pas
     *       de position cible, le caret reste en haut du fichier.</li>
     * </ol>
     *
     * @param filePath   chemin absolu du document source (sans préfixe file://)
     * @param source     texte du document source
     * @param offset     offset du caret dans {@code source}
     * @param targetType  false → FQN de la classe déclarante (définition du
     *                   membre au caret) ; true → FQN du type du symbole
     *                   (type definition)
     * @return la Location synthétique, ou null si la cible n'est pas binaire
     *         ou si le FQN est indisponible
     */
    private Location binaryDefinitionLocation(String filePath, String source,
                                               int offset, boolean targetType) {
        try {
            String fqcn = environment.jdt().fqcnAt(filePath, source, offset, targetType);
            if (fqcn == null || fqcn.isEmpty()) return null;
            if (!environment.jdt().isBinaryType(fqcn)) return null;
            String uri = "jdt://decompiled/" + fqcn.replace('.', '/') + ".java";
            // Plage vide (0:0-0:0) : le buffer décompilé n'a pas de
            // position cible — le caret reste en haut du fichier. Le client
            // peut scroller vers une position s'il le souhaite (transport
            // via LocationLink si on l'ajoute plus tard).
            return new Location(uri, new Range(new Position(0, 0), new Position(0, 0)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Le texte d'un fichier CIBLE de navigation : overlay des tampons
     * ouverts d'abord, disque ensuite (cf. {@code references}).
     */
    private String textForNavigation(String path, java.util.Map<String, String> overlay) {
        if (overlay.containsKey(path)) return overlay.get(path);
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        List<Either<Command, CodeAction>> actions = new ArrayList<>();

        // Filtre par context.only si présent : le client peut demander
        // uniquement certains kinds ("quickfix", "refactor",
        // "source.organizeImports") — sans filtre, l'éditeur afficherait
        // les actions dans la mauvaise catégorie.
        java.util.List<String> onlyKinds = (params.getContext() != null
                && params.getContext().getOnly() != null)
                ? params.getContext().getOnly() : null;
        boolean wantsOrganizeImports = onlyKinds == null
                || onlyKinds.stream().anyMatch(k ->
                        "source".equals(k) || "source.organizeImports".equals(k)
                        || k.startsWith("source."));
        boolean wantsQuickFix = onlyKinds == null
                || onlyKinds.stream().anyMatch(k ->
                        "quickfix".equals(k) || k.startsWith("quickfix."));

        // Action « Organiser les imports » — proposée en plus des
        // quick-fixes diagnostiques : calcule les imports nécessaires via
        // les bindings ecj (EcjImportOrganizer) et remplace le bloc
        // d'imports existant (trié, sans doublons).
        if (wantsOrganizeImports) {
            try {
                var jdt = environment.jdt();
                if (jdt.isLikelyUsable()) {
                    var organizer = new jo.lspjava.ecj.EcjImportOrganizer(jdt);
                    var result = organizer.organize(uriToPath(uri), source);
                    if (result != null) {
                        CodeAction ca = new CodeAction("Organiser les imports");
                        ca.setKind(CodeActionKind.SourceOrganizeImports);
                        WorkspaceEdit edit = new WorkspaceEdit();
                        Map<String, List<TextEdit>> changes = new HashMap<>();
                        Position start = toPosition(source, result.replaceStart);
                        Position end = toPosition(source, result.replaceEnd);
                        changes.put(uri, List.of(new TextEdit(
                                new Range(start, end), result.importsBlock)));
                        edit.setChanges(changes);
                        ca.setEdit(edit);
                        actions.add(Either.forRight(ca));
                    }
                }
            } catch (Throwable t) {
                // On trace plutôt que d'avaler silencieusement.
                if (server != null) server.logWarning("codeAction organizeImports failed for " + uriToPath(uri), t);
            }
        }

        // Si le client ne veut PAS de quickfixes, on skip la boucle ci-dessous.
        if (!wantsQuickFix) {
            return CompletableFuture.completedFuture(actions);
        }

        // Try-catch de protection : le CodeActionService peut jeter sur du
        // code invalide — sans cela, une « Internal error » crasherait le serveur.
        try {
            if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
                return CompletableFuture.completedFuture(actions);
            }
            for (org.eclipse.lsp4j.Diagnostic lspDiag : params.getContext().getDiagnostics()) {
                if (lspDiag == null || lspDiag.getSeverity() == null) continue;
                Severity sev;
                switch (lspDiag.getSeverity()) {
                    case Error: sev = Severity.ERROR; break;
                    case Warning: sev = Severity.WARNING; break;
                    case Information: sev = Severity.INFORMATION; break;
                    case Hint: sev = Severity.HINT; break;
                    default: sev = Severity.INFORMATION; break;
                }
                String code = lspDiag.getCode() != null ? lspDiag.getCode().getLeft() : null;
                TextRange range;
                try {
                    range = new TextRange(
                            toOffset(source, lspDiag.getRange().getStart()),
                            toOffset(source, lspDiag.getRange().getEnd())
                    );
                } catch (Exception e) {
                    continue; // skip invalid ranges
                }
                // Récupère les arguments structurés transportés dans le
                // champ data (cf. toLspDiagnostic) — permet au
                // CodeActionService de dériver des quick-fixes exacts (import
                // du bon FQN, cast du bon type) sans parser le message localisé.
                List<String> diagArgs = extractDiagnosticArgs(lspDiag.getData());
                jo.lspjava.api.Diagnostic diag = new jo.lspjava.api.Diagnostic(
                        range, sev, lspDiag.getMessage(), code,
                        lspDiag.getSource(), diagArgs);

                // Le filePath est transmis — CreateMethodFromUsage résout
                // le type/méthode englobants via l'AST ecj (cache de parse
                // partagé avec les diagnostics).
                for (var action : codeActionService.actionsFor(diag, source,
                        uriToPath(uri))) {
                    CodeAction ca = new CodeAction(action.title());
                    ca.setKind(action.kind());
                    WorkspaceEdit edit = new WorkspaceEdit();
                    Map<String, List<TextEdit>> changes = new HashMap<>();
                    // Les édits sont réels et multiples (try-catch + import,
                    // cast, suppression de ligne…) — chaque Edit interne
                    // devient un TextEdit LSP borné [start, end).
                    List<TextEdit> tes = new ArrayList<>();
                    for (var e : action.edits()) {
                        Position s = toPosition(source, Math.max(0, Math.min(e.start(), source.length())));
                        Position en = toPosition(source, Math.max(0, Math.min(e.end(), source.length())));
                        tes.add(new TextEdit(new Range(s, en), e.text()));
                    }
                    changes.put(uri, tes);
                    edit.setChanges(changes);
                    ca.setEdit(edit);
                    actions.add(Either.forRight(ca));
                }
            }
        } catch (Exception e) {
            // On trace plutôt que d'avaler silencieusement.
            if (server != null) server.logWarning("codeAction quick-fixes failed for " + uriToPath(uri), e);
        }

        return CompletableFuture.completedFuture(actions);
    }

    // ==================== Diagnostics push ====================

    /**
     * Calcule et publie les diagnostics pour un document.
     *
     * <p>Le diagnose ecj complet transite par la lane DIAGNOSTICS du
     * {@code EcjEngineScheduler} (worker dédié + supersession par fichier),
     * jamais sur le thread lecteur LSP4J — sinon les parses entreraient en
     * compétition directe avec la complétion pour le verrou
     * {@code resolveUnit} et s'empileraient derrière un rename ou un
     * find-references longs.</p>
     *
     * @param uri     l'URI du document
     * @param version la version du document
     * @param source  le texte complet du document
     */
    private void publishDiagnostics(String uri, int version, String source) {
        try {
            String filePath = uriToPath(uri);
            List<jo.lspjava.api.Diagnostic> diags = null;
            try {
                var jdt = environment.jdt();
                var future = jdt.scheduler().submitDiagnostics(filePath,
                        () -> diagnosticService.diagnose(filePath, source));
                diags = future.get(
                        jo.lspjava.engine.EcjEngineScheduler.ANALYSIS_TIMEOUT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.CancellationException ce) {
                return; // superseded par une frappe plus récente — rien à publier.
            } catch (java.util.concurrent.TimeoutException te) {
                return; // fichier pathologique — on abandonne cette passe.
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ee) {
                // Scheduler indisponible / ExecutionException — fallback
                // synchrone historique.
                diags = diagnosticService.diagnose(filePath, source);
            }
            if (diags == null) return;
            boolean downgradeRMode = false;
            try {
                downgradeRMode = !environment.jdt().hasRealRClasses();
            } catch (Throwable ignored) {
                // Moteur non instanciable — pas de downgrade (mode strict).
            }
            boolean downgradeDatabindingMode = false;
            try {
                downgradeDatabindingMode =
                        !environment.jdt().hasGeneratedDatabindingClasses();
            } catch (Throwable ignored) {
                // Moteur non instanciable — pas de downgrade (mode strict).
            }
            List<org.eclipse.lsp4j.Diagnostic> lspDiags = new ArrayList<>();
            for (jo.lspjava.api.Diagnostic d : diags) {
                org.eclipse.lsp4j.Diagnostic lsp = toLspDiagnostic(source, d);
                maybeDowngradeUnresolvedR(source, d, lsp, downgradeRMode);
                maybeDowngradeUnresolvedDatabinding(
                        d, lsp, downgradeDatabindingMode);
                lspDiags.add(lsp);
            }
            server.publishDiagnostics(uri, version, lspDiags);
        } catch (Exception ignored) {
            // Best-effort — si le parsing échoue, on ne publie rien.
        }
    }

    /**
     * Republie les diagnostics de tous les documents ouverts après que le
     * contexte de compilation a été remplacé (sync Gradle livré →
     * {@code replaceAll} effectif → notification ContextListener).
     *
     * <p>Sans cela, l'utilisateur devrait taper pour déclencher une passe
     * d'analyse voyant le nouveau classpath — entre-temps il resterait
     * entouré d'un mur d'erreurs périmées (« ces classes existent pourtant ! »).
     * Ici, chaque document ouvert est re-soumis via le pipeline standard
     * ({@link #publishDiagnostics}) ; les erreurs résolues disparaissent en
     * quelques secondes sans action utilisateur.</p>
     */
    public void republishAllOpenDocumentsAsync() {
        // Copie défensive du snapshot (itération weakly consistent de la
        // ConcurrentHashMap) pour ne pas tenir de lock pendant la passe.
        java.util.Map<String, String> snapshot = new java.util.HashMap<>(openDocuments);
        if (snapshot.isEmpty()) return;
        if (!republishInFlight.compareAndSet(false, true)) return;
        try {
            republishExecutor.execute(() -> {
                try {
                    for (java.util.Map.Entry<String, String> e : snapshot.entrySet()) {
                        AtomicInteger v = documentVersions.get(e.getKey());
                        int version = v != null ? v.get() : -1;
                        publishDiagnostics(e.getKey(), version, e.getValue());
                    }
                } finally {
                    republishInFlight.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // Exécuteur déjà arrêté (serveur en cours de shutdown) — on
            // relâche le garde et on abandonne la vague proprement.
            republishInFlight.set(false);
        }
    }

    /**
     * Arrête les exécuteurs de ce service (republish + debouncer).
     * Appelé par {@link JavaLanguageServer#shutdown()} pour éviter les
     * fuites de threads au redémarrage du serveur. Idempotent. Les
     * exécuteurs sont des champs d'instance : l'instance suivante recrée
     * les siens, la fonctionnalité survit donc à un restart dans la même JVM.
     */
    public void shutdownExecutors() {
        try {
            republishExecutor.shutdownNow();
        } catch (Throwable t) {
            // Best-effort.
        }
        try {
            diagnosticsDebouncer.shutdownNow();
        } catch (Throwable t) {
            // Best-effort.
        }
    }

    // ── Wrapper scheduler pour les handlers LSP ─────────────────────────
    /**
     * Soumet une tâche LSP au scheduler interactif avec timeout 3 s.
     *
     * <p>Sans ce wrapper, les handlers (hover, definition, typeDefinition,
     * implementation, superDefinition, codeAction, references, rename,
     * formatting, inlayHint, documentSymbol, documentHighlight) appelleraient
     * directement des méthodes synchronized sur EcjCompilerEngine,
     * attendant le verrou jusqu'à 15 s sans timeout ni annulation.</p>
     *
     * <p>Cette méthode :</p>
     * <ol>
     *   <li>soumet au scheduler interactif (supersession par fichier),</li>
     *   <li>applique un timeout de 3 s (INTERACTIVE_TIMEOUT_MS),</li>
     *   <li>retourne {@code fallback} en cas d'erreur ou d'annulation.</li>
     * </ol>
     *
     * @param filePath chemin du fichier focal (clé de supersession)
     * @param supplier la tâche à exécuter (lambda {@code () -> ...})
     * @param fallback valeur de retour en cas d'erreur/timeout/annulation
     * @param <T> type de retour
     * @return un CompletableFuture qui se complète avec le résultat ou le fallback
     */
    private <T> CompletableFuture<T> submitToScheduler(String filePath,
                                                        java.util.function.Supplier<T> supplier,
                                                        T fallback) {
        // Si le moteur n'est pas encore initialisé (cold start), on exécute
        // directement — le scheduler n'existe pas encore.
        if (!environment.isEcjInitialized()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable t) {
                if (server != null) server.logWarning("handler failed (jdt not initialized) for " + filePath, t);
                return CompletableFuture.completedFuture(fallback);
            }
        }
        try {
            var jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) {
                return CompletableFuture.completedFuture(fallback);
            }
            final java.util.concurrent.Future<T> schedulerFuture;
            try {
                schedulerFuture = jdt.scheduler().submitInteractive(filePath, () -> supplier.get());
            } catch (Throwable t) {
                if (server != null) server.logWarning("submitInteractive rejected for " + filePath, t);
                return CompletableFuture.completedFuture(fallback);
            }

            // Support de $/cancelRequest.
            // LSP4J reçoit la notification $/cancelRequest et appelle
            // future.cancel(true) sur le CompletableFuture retourné. Pour
            // propager l'annulation au scheduler, on utilise whenComplete
            // et on cancel le schedulerFuture si le CompletableFuture est
            // annulé.
            CompletableFuture<T> result = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    // Propage l'annulation au schedulerFuture.
                    try {
                        schedulerFuture.cancel(mayInterruptIfRunning);
                    } catch (Throwable ignored) {
                        // Déjà terminée ou rejetée — l'annulation suit son cours.
                    }
                    return super.cancel(mayInterruptIfRunning);
                }
            };

            // Thread de polling léger pour transférer le résultat du scheduler
            // vers le CompletableFuture. Utilise runAsync sur ForkJoinPool.
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    T value = schedulerFuture.get();
                    result.complete(value);
                } catch (java.util.concurrent.CancellationException ce) {
                    // Annulation (probablement via $/cancelRequest) — fallback.
                    result.complete(fallback);
                } catch (java.util.concurrent.ExecutionException ee) {
                    if (server != null) {
                        server.logWarning("handler task failed for " + filePath, ee.getCause());
                    }
                    result.complete(fallback);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    result.complete(fallback);
                } catch (Throwable t) {
                    if (server != null) server.logWarning("handler unexpected for " + filePath, t);
                    result.complete(fallback);
                }
            });

            // Timeout 3 s — si dépassé, on complète avec le fallback.
            return result.orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                          .exceptionally(t -> {
                              // Timeout ou autre exception — fallback.
                              try {
                                  schedulerFuture.cancel(true);
                              } catch (Throwable ignored) {
                                  // Déjà terminée ou rejetée — fallback quand même.
                              }
                              return fallback;
                          });
        } catch (Throwable t) {
            if (server != null) server.logWarning("submitToScheduler failed for " + filePath, t);
            return CompletableFuture.completedFuture(fallback);
        }
    }

    /**
     * Convertit un diagnostic {@code lspjava} en diagnostic LSP4J. */
    private org.eclipse.lsp4j.Diagnostic toLspDiagnostic(String source, jo.lspjava.api.Diagnostic d) {
        org.eclipse.lsp4j.DiagnosticSeverity sev = switch (d.severity()) {
            case ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error;
            case WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning;
            case INFORMATION -> org.eclipse.lsp4j.DiagnosticSeverity.Information;
            case HINT -> org.eclipse.lsp4j.DiagnosticSeverity.Hint;
        };
        Range range = toLspRange(source, d.range());
        org.eclipse.lsp4j.Diagnostic lspDiag = new org.eclipse.lsp4j.Diagnostic(range, d.message(), sev, d.source());
        if (d.code() != null) lspDiag.setCode(d.code());
        // Transporte les arguments structurés du problème ecj dans le
        // champ data du diagnostic LSP. L'éditeur les renvoie tels quels
        // dans la requête codeAction suivante — ce qui permet au
        // CodeActionService de brancher sur l'ID + args plutôt que sur le
        // message localisé (pattern JavaProblemCodes du projet de référence).
        if (!d.arguments().isEmpty()) {
            lspDiag.setData(new ArrayList<>(d.arguments()));
        }
        return lspDiag;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Downgrade honnête des faux « R cannot be resolved »
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Formes exactes traitées comme références-R non générées :<ul>
     *   <li>{@code R.layout.main cannot be resolved to a variable}</li>
     *   <li>{@code R cannot be resolved to a variable}</li>
     *   <li>{@code The import com.example.d.R cannot be resolved to a type}</li>
     * </ul>
     * ancrées début/fin de message — on ne touche à RIEN d'autre.
     */
    private static final java.util.regex.Pattern R_UNRESOLVED_MESSAGE =
            java.util.regex.Pattern.compile(
                    "^(?:The import )?(?:[A-Za-z_$][\\w$]*\\.)*R(?:\\.[A-Za-z_][A-Za-z0-9_]*)?"
                            + " cannot be resolved to (?:a type|a variable)$");

    /**
     * Dégrade ERROR→HINT les diagnostics causés par la classe R tant qu'AUCUN
     * artefact R réel (R.jar aapt2 / R.class indexés) n'existe dans le
     * contexte. Philosophie « zéro devinette » maintenue : on ne FABRIQUE pas
     * de symboles, on affiche honnêtement la situation réelle (le build n'a
     * encore jamais produit R) et on indique le remède. Dès que la première
     * construction génère R.jar, le flag bascule côté index et les vraies
     * erreurs réapparaissent intégralement.</p>
     */
    private void maybeDowngradeUnresolvedR(String source,
                                           jo.lspjava.api.Diagnostic d,
                                           org.eclipse.lsp4j.Diagnostic lsp,
                                           boolean downgradeMode) {
        if (!downgradeMode) return;
        if (lsp.getSeverity() != org.eclipse.lsp4j.DiagnosticSeverity.Error) {
            return;
        }
        String msg = lsp.getMessage() == null ? "" : lsp.getMessage();
        if (!R_UNRESOLVED_MESSAGE.matcher(msg).matches()) return;
        lsp.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Hint);
        lsp.setMessage(msg + " — la classe R est générée par aapt2 lors de la"
                + " PREMIÈRE construction. Lance un Build : elle apparaîtra et"
                + " cette note s'effacera seule.");
    }

    /**
     * Dégrade ERROR→HINT les imports non résolus vers le package Data
     * Binding ({@code <namespace>.databinding.*}) tant qu'AUCUNE classe
     * générée n'existe (v0.108.0, retour propriétaire — même philosophie
     * « zéro devinette » que {@link #maybeDowngradeUnresolvedR}).
     *
     * <p>Constat : les classes XxxBinding ne sont produites par AGP
     * (dataBindingGenBaseClasses, puis compilation) qu'AU BUILD — la
     * sync ne les génère PAS. Avant tout build, l'import est
     * STRUCTURELLEMENT non résoluble : l'ERROR sec « The import X
     * cannot be resolved » est exact mais incurable côté utilisateur —
     * on affiche honnêtement la situation (HINT + remède). Reconnaissance
     * par les ARGUMENTS STRUCTURÉS du problème ecj (stables entre
     * locales : arguments()[0] = nom importé, pattern JavaProblemCodes)
     * et non par le message localisé ; dès que la première classe
     * générée existe (sonde du moteur), le downgrade se coupe et les
     * vraies erreurs (faute de frappe) repassent en ERROR intégral.</p>
     */
    private void maybeDowngradeUnresolvedDatabinding(
            jo.lspjava.api.Diagnostic d,
            org.eclipse.lsp4j.Diagnostic lsp,
            boolean downgradeMode) {
        if (!downgradeMode) return;
        if (lsp.getSeverity() != org.eclipse.lsp4j.DiagnosticSeverity.Error) {
            return;
        }
        List<String> arguments = d.arguments();
        if (arguments.isEmpty()) return;
        String cible = arguments.get(0);
        // Segment .databinding obligatoire (points des deux côtés) : « mydatabinding »
        // ou un paquet homonyme tiers ne déclenchent PAS le downgrade.
        if (cible == null || !cible.contains(".databinding")) return;
        lsp.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Hint);
        lsp.setMessage(lsp.getMessage() + " — les classes Data Binding"
                + " (XxxBinding) sont générées par AGP lors de la construction"
                + " (dataBindingGenBaseClasses) : la sync seule ne les crée pas."
                + " Lance un Build : elles apparaîtront et cette note s'effacera.");
    }

    // ==================== Surlignage sémantique (semantic tokens) ====================

    /**
     * Tokens sémantiques plein document par bindings ecj — la vraie nature
     * de chaque identifiant résolu (classe, interface, enum, méthode, champ,
     * locale, paramètre), calculée par
     * {@link jo.lspjava.ecj.EcjSemanticHighlighter} puis encodée au format
     * LSP delta (5 ints par token).
     *
     * <p>Lane ANALYSIS du scheduler — ce diagnostic esthétique ne passe
     * jamais devant la complétion interactive ; un resolve en cours est
     * borné comme les autres tâches d'analyse (15 s).</p>
     */
    @Override
    public CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(
            org.eclipse.lsp4j.SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        List<Integer> data = new ArrayList<>();
        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                String filePath = uriToPath(uri);
                final List<int[]> rows;
                try {
                    var future = jdt.scheduler().submitAnalysis(() -> {
                        var hl = new jo.lspjava.ecj.EcjSemanticHighlighter(jdt);
                        List<jo.lspjava.ecj.EcjSemanticHighlighter.Span> spans =
                                hl.highlights(filePath, source);
                        List<int[]> out = new ArrayList<>(spans.size());
                        for (var sp : spans) {
                            out.add(new int[]{sp.start(), sp.length(), sp.kind()});
                        }
                        return out;
                    });
                    rows = future.get(
                            jo.lspjava.engine.EcjEngineScheduler.ANALYSIS_TIMEOUT_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.CancellationException
                        | java.util.concurrent.TimeoutException busy) {
                    // On trace plutôt que d'avaler silencieusement. Le client
                    // recevra data=[] (pas de couleurs sémantiques), ce qui est
                    // acceptable — il réessaiera au prochain didChange.
                    if (server != null) server.logWarning("semanticTokens timed out/canceled for " + uri, busy);
                    return CompletableFuture.completedFuture(
                            new org.eclipse.lsp4j.SemanticTokens());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.completedFuture(
                            new org.eclipse.lsp4j.SemanticTokens());
                } catch (java.util.concurrent.ExecutionException ee) {
                    if (server != null) server.logWarning("semanticTokens task failed for " + uri, ee.getCause());
                    return CompletableFuture.completedFuture(
                            new org.eclipse.lsp4j.SemanticTokens());
                }
                data = encodeSemanticTokens(source, rows);
            }
        } catch (Throwable ignored) {
            // Best-effort — aucune couleur sémantique cette fois.
            data = new ArrayList<>();
        }
        return CompletableFuture.completedFuture(new org.eclipse.lsp4j.SemanticTokens(data));
    }

    /**
     * Encode des spans offset-source en format LSP delta :
     * {@code [deltaLine, deltaStartChar, length, tokenType, 0]} ×N — les
     * offsets absolus sont convertis ligne/colonne sur le texte courant.
     */
    private List<Integer> encodeSemanticTokens(String source,
                                               List<int[]> startLenKind) {
        List<Integer> out = new ArrayList<>(startLenKind.size() * 5);
        int prevLine = 0;
        int prevCol = 0;
        for (int[] t : startLenKind) {
            int start = Math.max(0, Math.min(t[0], source.length()));
            int end = Math.max(start, Math.min(t[0] + t[1], source.length()));
            Position pos = toPosition(source, start);
            int line = pos.getLine();
            int col = pos.getCharacter();
            int deltaLine = line - prevLine;
            int deltaChar = deltaLine == 0 ? col - prevCol : col;
            if (deltaLine == 0 && deltaChar < 0) continue; // sécurité
            out.add(deltaLine);
            out.add(deltaChar);
            out.add(end - start);
            out.add(t[2]);
            out.add(0);  // tokenModifiers (aucun pour l'instant)
            prevLine = line;
            prevCol = col;
        }
        return out;
    }

    // ==================== Providers additionnels ====================

    @Override
    public CompletableFuture<org.eclipse.lsp4j.SignatureHelp> signatureHelp(SignatureHelpParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            // Aide de signature ecj — toutes les surcharges du type
            // receveur (binaires incluses), paramètre actif compté, noms depuis
            // les déclarations sources du projet.
            try {
                var jdt = environment.jdt();
                if (jdt.isLikelyUsable()) {
                    var helper = new jo.lspjava.ecj.EcjSignatureHelp(jdt);
                    var result = helper.signatureHelpAt(filePath, source, offset);
                    if (result != null && !result.signatures.isEmpty()) {
                        List<org.eclipse.lsp4j.SignatureInformation> sigs =
                                new ArrayList<>();
                        int active = 0;
                        for (int i = 0; i < result.signatures.size(); i++) {
                            var s = result.signatures.get(i);
                            org.eclipse.lsp4j.SignatureInformation info =
                                    new org.eclipse.lsp4j.SignatureInformation(s.label);
                            if (s.documentation != null && !s.documentation.isEmpty()) {
                                info.setDocumentation(Either.forLeft(s.documentation));
                            }
                            List<org.eclipse.lsp4j.ParameterInformation> pis =
                                    new ArrayList<>();
                            for (int p = 0; p < s.parameterNames.size(); p++) {
                                pis.add(new org.eclipse.lsp4j.ParameterInformation(
                                        s.parameterNames.get(p)));
                            }
                            info.setParameters(pis);
                            sigs.add(info);
                            if (i == result.activeSignature) active = i;
                        }
                        return new org.eclipse.lsp4j.SignatureHelp(sigs, active,
                                Math.max(0, result.activeParameter));
                    }
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("signatureHelp handler failed for " + filePath, t);
            }
            return new org.eclipse.lsp4j.SignatureHelp();
        }, new org.eclipse.lsp4j.SignatureHelp());
    }

    @Override
    public CompletableFuture<List<Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>>
            documentSymbol(org.eclipse.lsp4j.DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        String filePath = uriToPath(uri);
        List<Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> emptyResult =
                new ArrayList<>();

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            List<Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> result =
                    new ArrayList<>();
            // Structure du document via l'AST interne ecj — types,
            // méthodes, champs, avec plages hiérarchiques.
            try {
                var jdt = environment.jdt();
                var cud = jdt.resolveUnit(filePath, source, false);
                if (cud != null && cud.types != null) {
                    for (var td : cud.types) {
                        var sym = symbolOf(td, source);
                        if (sym != null) result.add(Either.forRight(sym));
                    }
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("documentSymbol handler failed for " + filePath, t);
            }
            return result;
        }, emptyResult);
    }

    /** DocumentSymbol d'un TypeDeclaration (récursif). */
    private org.eclipse.lsp4j.DocumentSymbol symbolOf(
            org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td, String source) {
        if (td.name == null) return null;
        String name = String.valueOf(td.name);
        var kind = org.eclipse.lsp4j.SymbolKind.Class;
        if (td.binding != null) {
            if (td.binding.isInterface()) kind = org.eclipse.lsp4j.SymbolKind.Interface;
            else if (td.binding.isEnum()) kind = org.eclipse.lsp4j.SymbolKind.Enum;
        }
        Range range = new Range(toPosition(source, td.declarationSourceStart),
                toPosition(source, td.declarationSourceEnd + 1));
        // Plage du nom = sélection.
        int nameStart = jo.lspjava.ecj.EcjRenameEngine.findWord(source, name,
                td.sourceStart, td.sourceEnd + 1, false);
        Range selection = nameStart >= 0
                ? new Range(toPosition(source, nameStart),
                        toPosition(source, nameStart + name.length()))
                : range;

        List<org.eclipse.lsp4j.DocumentSymbol> children = new ArrayList<>();
        if (td.methods != null) {
            for (var md : td.methods) {
                if (md == null || md.selector == null) continue;
                String mname = String.valueOf(md.selector);
                var mk = md instanceof org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration
                        ? org.eclipse.lsp4j.SymbolKind.Constructor
                        : org.eclipse.lsp4j.SymbolKind.Method;
                children.add(new org.eclipse.lsp4j.DocumentSymbol(mname, mk,
                        new Range(toPosition(source, md.declarationSourceStart),
                                toPosition(source, md.declarationSourceEnd + 1)),
                        new Range(toPosition(source, md.sourceStart),
                                toPosition(source, md.sourceEnd + 1))));
            }
        }
        if (td.fields != null) {
            for (var fd : td.fields) {
                if (fd == null || fd.name == null) continue;
                children.add(new org.eclipse.lsp4j.DocumentSymbol(
                        String.valueOf(fd.name),
                        org.eclipse.lsp4j.SymbolKind.Field,
                        new Range(toPosition(source, fd.sourceStart),
                                toPosition(source, fd.sourceEnd + 1)),
                        new Range(toPosition(source, fd.sourceStart),
                                toPosition(source, fd.sourceEnd + 1))));
            }
        }
        if (td.memberTypes != null) {
            for (var nested : td.memberTypes) {
                var child = symbolOf(nested, source);
                if (child != null) children.add(child);
            }
        }
        return new org.eclipse.lsp4j.DocumentSymbol(name, kind, range, selection,
                null, children);
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.DocumentHighlight>> documentHighlight(
            org.eclipse.lsp4j.DocumentHighlightParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            List<org.eclipse.lsp4j.DocumentHighlight> result = new ArrayList<>();
            // Occurrences du symbole au caret via les bindings ecj
            // (même identification que le renommage — homonymes exclus).
            try {
                var jdt = environment.jdt();
                if (jdt.isLikelyUsable()) {
                    var renameEngine = new jo.lspjava.ecj.EcjRenameEngine(jdt);
                    var target = renameEngine.targetAt(filePath, source, offset);
                    if (target != null) {
                        for (var occ : renameEngine.referencesIn(filePath, source, target)) {
                            result.add(new org.eclipse.lsp4j.DocumentHighlight(
                                    new Range(toPosition(source, occ.start),
                                            toPosition(source, occ.end)),
                                    org.eclipse.lsp4j.DocumentHighlightKind.Text));
                        }
                    }
                }
            } catch (Throwable t) {
                if (server != null) server.logWarning("documentHighlight handler failed for " + filePath, t);
            }
            return result;
        }, new ArrayList<org.eclipse.lsp4j.DocumentHighlight>());
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.Location>> references(
            org.eclipse.lsp4j.ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        // Déclaré avant le try pour être visible dans le catch.
        String filePath = uriToPath(uri);
        List<org.eclipse.lsp4j.Location> result = new ArrayList<>();

        // Références par bindings ecj — multi-fichiers (mêmes occurrences
        // que le renommage), homonymes exclus.
        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                var renameEngine = new jo.lspjava.ecj.EcjRenameEngine(jdt);
                var target = renameEngine.targetAt(filePath, source, offset);
                if (target != null) {
                    boolean includeDecl = params.getContext() != null
                            && params.getContext().isIncludeDeclaration();
                    // Overlay des tampons ouverts — le renommage consulte
                    // les buffers vivants (modifications non sauvegardées)
                    // avant le disque, pour qu'un frère ouvert ne soit pas
                    // renommé sur une version périmée.
                    java.util.Map<String, String> overlay = buildOpenBufferOverlay();
                    // Annulation coopérative — 15 s sur les références
                    // (lane background). Au-delà, on rend ce qu'on a déjà
                    // collecté (partial result).
                    try (var cancel = jo.lspjava.engine.CancelToken.timeout(
                            15, java.util.concurrent.TimeUnit.SECONDS)) {
                        cancel.attachToCurrentThread();
                        java.util.Map<String, List<jo.lspjava.ecj.EcjRenameEngine.Occurrence>>
                                byFile = renameEngine.renameProject(filePath, source, target, overlay, cancel);
                        for (var entry : byFile.entrySet()) {
                            String fileUri = pathToUri(entry.getKey());
                            String text = openDocuments.containsKey(fileUri)
                                    ? openDocuments.get(fileUri) : null;
                            if (text == null) {
                                try {
                                    text = new String(java.nio.file.Files.readAllBytes(
                                            java.nio.file.Paths.get(entry.getKey())),
                                            java.nio.charset.StandardCharsets.UTF_8);
                                } catch (Exception e) {
                                    continue;
                                }
                            }
                            boolean isDeclaringFile = entry.getKey().equals(filePath);
                            for (var occ : entry.getValue()) {
                                // includeDeclaration=false → exclut la déclaration
                                // (plage du jeton visé dans le fichier déclarant).
                                if (!includeDecl && isDeclaringFile
                                        && occ.start == target.declStart
                                        && occ.end == target.declEnd) {
                                    continue;
                                }
                                result.add(new org.eclipse.lsp4j.Location(fileUri,
                                        new Range(toPosition(text, occ.start),
                                                toPosition(text, occ.end))));
                            }
                        }
                    } // fin try-with-resources (cancel token)
                    if (!result.isEmpty()) {
                        return CompletableFuture.completedFuture(result);
                    }
                }
            }
        } catch (Throwable t) {
            // On trace plutôt que d'avaler silencieusement.
            if (server != null) server.logWarning("references handler failed for " + filePath, t);
        }

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(
            org.eclipse.lsp4j.RenameParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());
        String newName = params.getNewName();

        // Renommage ecj par bindings — structurellement correct
        // (homonymes ignorés, usages qualifiés/imports attrapés) et
        // multi-fichiers (balayage des racines sources du projet).
        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                var renameEngine = new jo.lspjava.ecj.EcjRenameEngine(jdt);
                String filePath = uriToPath(uri);
                var target = renameEngine.targetAt(filePath, source, offset);
                if (target != null) {
                    org.eclipse.lsp4j.WorkspaceEdit workspaceEdit =
                            new org.eclipse.lsp4j.WorkspaceEdit();
                    java.util.Map<String, List<org.eclipse.lsp4j.TextEdit>> changes =
                            new java.util.LinkedHashMap<>();
                    // Overlay des tampons ouverts — le balayage projet
                    // consulte les buffers vivants avant le disque, pour
                    // qu'un frère ouvert (modifications non sauvegardées)
                    // ne soit pas renommé sur une version périmée.
                    java.util.Map<String, String> overlay = buildOpenBufferOverlay();
                    // Le balayage projet retourne des CHEMINS réels —
                    // reconvertis en URI file:// pour le WorkspaceEdit.
                    java.util.Map<String, List<jo.lspjava.ecj.EcjRenameEngine.Occurrence>> byFile;
                    // Annulation coopérative — 15 s sur rename (lane background).
                    try (var cancel = jo.lspjava.engine.CancelToken.timeout(
                            15, java.util.concurrent.TimeUnit.SECONDS)) {
                        cancel.attachToCurrentThread();
                        byFile = renameEngine.renameProject(filePath, source, target, overlay, cancel);
                    }
                    for (var entry : byFile.entrySet()) {
                        String fileUri = pathToUri(entry.getKey());
                        List<org.eclipse.lsp4j.TextEdit> edits = new ArrayList<>();
                        String text = openDocuments.containsKey(fileUri)
                                ? openDocuments.get(fileUri) : null;
                        if (text == null) {
                            try {
                                text = new String(java.nio.file.Files.readAllBytes(
                                        java.nio.file.Paths.get(entry.getKey())),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                continue; // fichier illisible — ignoré
                            }
                        }
                        for (var occ : entry.getValue()) {
                            edits.add(new org.eclipse.lsp4j.TextEdit(
                                    new Range(toPosition(text, occ.start),
                                            toPosition(text, occ.end)),
                                    newName));
                        }
                        if (!edits.isEmpty()) {
                            changes.put(fileUri, edits);
                        }
                    }
                    workspaceEdit.setChanges(changes);
                    return CompletableFuture.completedFuture(workspaceEdit);
                }
            }
        } catch (Throwable t) {
            // On trace plutôt que d'avaler silencieusement.
            if (server != null) server.logWarning("rename handler failed for " + uriToPath(uri), t);
        }

        return CompletableFuture.completedFuture(new org.eclipse.lsp4j.WorkspaceEdit());
    }

    /**
     * prepareRename (LSP 3.17) : le client demande si le symbole à la
     * position est renommable AVANT d'ouvrir la boîte de dialogue.
     * Retourne la plage du nom + un placeholder, ou null si non renommable.
     */
    @Override
    public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either3<
            org.eclipse.lsp4j.Range,
            org.eclipse.lsp4j.PrepareRenameResult,
            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>> prepareRename(
            org.eclipse.lsp4j.PrepareRenameParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        int offset = toOffset(source, params.getPosition());

        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                var renameEngine = new jo.lspjava.ecj.EcjRenameEngine(jdt);
                String filePath = uriToPath(uri);
                var target = renameEngine.targetAt(filePath, source, offset);
                if (target != null) {
                    // Retourner la plage du nom + le nom courant comme placeholder
                    int nameStart = jo.lspjava.ecj.EcjRenameEngine.findWord(
                            source, target.oldName, offset, offset + 1, false);
                    if (nameStart < 0) nameStart = offset;
                    var range = new org.eclipse.lsp4j.Range(
                            toPosition(source, nameStart),
                            toPosition(source, nameStart + target.oldName.length()));
                    return CompletableFuture.completedFuture(
                        org.eclipse.lsp4j.jsonrpc.messages.Either3.forSecond(
                            new org.eclipse.lsp4j.PrepareRenameResult(range, target.oldName)
                        )
                    );
                }
            }
        } catch (Throwable t) {
            if (server != null) server.logWarning("prepareRename handler failed for " + uriToPath(uri), t);
        }

        // null = pas renommable à cette position (le client n'ouvre pas la boîte)
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.TextEdit>> formatting(
            org.eclipse.lsp4j.DocumentFormattingParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        String filePath = uriToPath(uri);

        // Wrapper scheduler + timeout 3 s.
        return submitToScheduler(filePath, () -> {
            // Formateur Eclipse JDT in-process — le moteur qu'utilise
            // Eclipse lui-même (jars Eclipse patchés ART). Contrat de
            // sûreté : source imparsable → aucune édition (no-op).
            try {
                String compliance = environment.complianceLevel();
                var fmt = new jo.lspjava.ecj.EcjFormatter(compliance);
                String formatted = fmt.format(source);
                if (formatted == null || formatted.equals(source)) {
                    return new ArrayList<org.eclipse.lsp4j.TextEdit>();
                }
                Range fullRange = new Range(new Position(0, 0),
                        toPosition(source, source.length()));
                List<org.eclipse.lsp4j.TextEdit> result = new ArrayList<>();
                result.add(new org.eclipse.lsp4j.TextEdit(fullRange, formatted));
                return result;
            } catch (Throwable t) {
                if (server != null) server.logWarning("formatting handler failed for " + filePath, t);
                return new ArrayList<org.eclipse.lsp4j.TextEdit>();
            }
        }, new ArrayList<org.eclipse.lsp4j.TextEdit>());
    }

    /**
     * {@code textDocument/foldingRange} : régions de folding Java (portage
     * {@code EcjCodeFolder} du projet de référence). Le groupe d'imports est
     * replié PAR DÉFAUT ({@code kind="imports"}) ; corps/blocs → région =
     * intérieur des accolades, {@code kind="block"} ; commentaires de bloc
     * multi-lignes → {@code kind="comment"}. Précision CARACTÈRE
     * (startCharacter/endCharacter) : le client reconstruit des offsets
     * exacts — le composite replié garde les accolades visibles.
     *
     * <p>Cadencé par le client (cel-lsp) comme le surlignage sémantique :
     * un tir par publication de diagnostics, lane ANALYSIS du scheduler —
     * jamais devant la complétion.</p>
     */
    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.FoldingRange>> foldingRange(
            org.eclipse.lsp4j.FoldingRangeRequestParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        List<org.eclipse.lsp4j.FoldingRange> out = new ArrayList<>();
        try {
            var jdt = environment.jdt();
            if (!jdt.isLikelyUsable()) {
                // Moteur indisponible → null (et PAS une liste vide) : le
                // client distingue « pas de folding serveur » (il garde ses
                // folds lexicaux de secours) d'un jeu VIDE légitime
                // (fichier sans construction multi-ligne).
                return CompletableFuture.completedFuture(null);
            }
            String filePath = uriToPath(uri);
            final List<jo.lspjava.ecj.EcjCodeFolder.Fold> folds;
            try {
                var future = jdt.scheduler().submitAnalysis(() ->
                        new jo.lspjava.ecj.EcjCodeFolder(jdt)
                                .foldsFor(filePath, source));
                folds = future.get(
                        jo.lspjava.engine.EcjEngineScheduler.ANALYSIS_TIMEOUT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.CancellationException
                    | java.util.concurrent.TimeoutException
                    | InterruptedException
                    | java.util.concurrent.ExecutionException busy) {
                return CompletableFuture.completedFuture(out);
            }
            for (var f : folds) {
                if (f.start < 0 || f.end <= f.start
                        || f.end > source.length()) continue;
                Position s = toPosition(source, f.start);
                Position e = toPosition(source, f.end);
                org.eclipse.lsp4j.FoldingRange r =
                        new org.eclipse.lsp4j.FoldingRange(
                                s.getLine(), e.getLine());
                r.setStartCharacter(s.getCharacter());
                r.setEndCharacter(e.getCharacter());
                r.setKind(f.kind);
                r.setCollapsedText(f.placeholder);
                out.add(r);
            }
        } catch (Throwable ignored) {
            // Best-effort — aucune région cette fois.
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.InlayHint>> inlayHint(
            org.eclipse.lsp4j.InlayHintParams params) {
        String uri = params.getTextDocument().getUri();
        String source = openDocuments.getOrDefault(uri, "");
        List<org.eclipse.lsp4j.InlayHint> hints = new ArrayList<>();

        // Hints de noms de paramètres aux call sites via les bindings
        // ecj — surcharge exacte, méthodes déclarées dans toutes les
        // sources du projet (pas seulement ce fichier), aucun faux nom
        // pour les méthodes binaires. Une seule instance EcjInlayHints :
        // le cache de parse de l'engine mutualise l'arbre résolu.
        try {
            var jdt = environment.jdt();
            if (jdt.isLikelyUsable()) {
                var inlayEngine = new jo.lspjava.ecj.EcjInlayHints(jdt);
                for (var hint : inlayEngine.hintsFor(uriToPath(uri), source)) {
                    org.eclipse.lsp4j.InlayHint h = new org.eclipse.lsp4j.InlayHint(
                            toPosition(source, hint.offset),
                            org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(hint.label));
                    h.setKind(org.eclipse.lsp4j.InlayHintKind.Parameter);
                    // Padding droit : le label « name: » précède l'argument.
                    h.setPaddingRight(true);
                    hints.add(h);
                }

                // Portage JdtInlayHintService du projet de référence : chaining
                // hints multi-lignes (type du récepteur en fin de sa ligne)
                // + types des paramètres lambda implicites (« : Type »).
                for (var hint : inlayEngine.chainedAndLambdaHintsFor(uriToPath(uri), source)) {
                    org.eclipse.lsp4j.InlayHint h = new org.eclipse.lsp4j.InlayHint(
                            toPosition(source, hint.offset),
                            org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(hint.label));
                    h.setKind(org.eclipse.lsp4j.InlayHintKind.Type);
                    // Chaining : padding gauche (le type suit le dernier
                    // caractère de l'appel récepteur) — parité
                    // InlayHint(paddingLeft = true). Lambda : collé au nom
                    // (parité typeHint sans padding).
                    if (hint.padLeft) h.setPaddingLeft(true);
                    hints.add(h);
                }
            }
        } catch (Throwable t) {
            // On trace plutôt que d'avaler silencieusement.
            if (server != null) server.logWarning("inlayHint handler failed for " + uriToPath(uri), t);
        }

        // Hints de type des `var` par bindings ecj — le type réel de
        // l'initialiseur (appels de méthode, ternaires, enhanced-for,
        // génériques), avec le scanner textuel en repli quand le moteur
        // est indisponible (l'inférence littéraux-seuls ne couvrait pas
        // un `var s = foo();`).
        boolean varHintsFromAst = false;
        try {
            var jdt2 = environment.jdt();
            if (jdt2.isLikelyUsable()) {
                for (var hint : new jo.lspjava.ecj.EcjInlayHints(jdt2)
                        .typeHintsFor(uriToPath(uri), source)) {
                    org.eclipse.lsp4j.InlayHint h = new org.eclipse.lsp4j.InlayHint(
                            toPosition(source, hint.offset),
                            org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(hint.label));
                    h.setKind(org.eclipse.lsp4j.InlayHintKind.Type);
                    // Pas de padding gauche — parité projet de référence
                    // typeHint() : le « : Type » est collé au nom
                    // (« s: String »), comme le rend buildInlayAnnotated.
                    hints.add(h);
                }
                varHintsFromAst = true;
            }
        } catch (Throwable ignored) {
            // Best-effort — le scanner textuel ci-dessous reste calculé.
        }
        if (!varHintsFromAst) {
            // Repli textuel : littéraux simples seulement (sans parser).
            // Vérifier que "var" est au début d'une déclaration (précédé
            // par whitespace, début de ligne, ou ;{}) — un simple
            // indexOf("var ") matcherait "var " dans les chaînes,
            // commentaires, ou noms de variable contenant "var".
            for (int i = source.indexOf("var "); i >= 0;
                    i = source.indexOf("var ", i + 1)) {
                // Vérifier le contexte lexical : le caractère avant
                // "var" doit être un whitespace, début de ligne, ou un
                // séparateur de statement (; { } ).
                if (i > 0) {
                    char prev = source.charAt(i - 1);
                    if (!Character.isWhitespace(prev)
                            && prev != ';' && prev != '{' && prev != '}'
                            && prev != ')') {
                        continue; // "var" dans un identifiant ou chaîne — skip
                    }
                }
                int eq = source.indexOf('=', i);
                if (eq < 0) break;
                int semi = source.indexOf(';', eq);
                if (semi < 0) semi = source.length();
                String init = source.substring(eq + 1, semi).trim();
                String type = simpleInferredType(init);
                if (type == null) continue;
                // Position : fin du nom de variable (avant le =).
                int nameEnd = eq;
                while (nameEnd > i && Character.isWhitespace(source.charAt(nameEnd - 1))) {
                    nameEnd--;
                }
                org.eclipse.lsp4j.InlayHint hint = new org.eclipse.lsp4j.InlayHint(
                        toPosition(source, nameEnd),
                        org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(": " + type));
                hint.setKind(org.eclipse.lsp4j.InlayHintKind.Type);
                hints.add(hint);
            }
        }

        return CompletableFuture.completedFuture(hints);
    }

    /**
     * Type inféré d'un initialiseur simple (hint de type des {@code var}).
     * Best-effort textuel — les cas complexes ne reçoivent pas de hint.
     */
    private static String simpleInferredType(String initializer) {
        String v = initializer.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("\"") && v.endsWith("\"")) return "String";
        if (v.startsWith("'") && v.endsWith("'")) return "char";
        if ("true".equals(v) || "false".equals(v)) return "boolean";
        if ("null".equals(v)) return "Object";
        if (v.endsWith("L") || v.endsWith("l")) {
            try { Long.parseLong(v.substring(0, v.length() - 1).replace("_", "")); return "long"; }
            catch (NumberFormatException ignored) { return null; }
        }
        try { Integer.parseInt(v.replace("_", "")); return "int"; }
        catch (NumberFormatException ignored) {
            // Pas un int — la cascade essaie float puis double.
        }
        if (v.endsWith("f") || v.endsWith("F")) {
            try { Float.parseFloat(v.substring(0, v.length() - 1).replace("_", "")); return "float"; }
            catch (NumberFormatException ignored) { return null; }
        }
        try { Double.parseDouble(v.replace("_", "")); return "double"; }
        catch (NumberFormatException ignored) {
            // Pas un littéral numérique — heuristiques suivantes (new X…).
        }
        // new X<...>(…) → X (nom simple du type instancié).
        if (v.startsWith("new ")) {
            String rest = v.substring(4).trim();
            int lt = rest.indexOf('<');
            int paren = rest.indexOf('(');
            int cut = Math.min(lt < 0 ? Integer.MAX_VALUE : lt,
                    paren < 0 ? Integer.MAX_VALUE : paren);
            if (cut == Integer.MAX_VALUE) cut = rest.length();
            String type = rest.substring(0, cut).trim();
            if (!type.isEmpty() && type.chars().allMatch(c ->
                    Character.isJavaIdentifierPart(c) || c == '.')) {
                int dot = type.lastIndexOf('.');
                return dot < 0 ? type : type.substring(dot + 1);
            }
        }
        return null;
    }


    // ==================== Méthodes utilitaires ====================

    /**
     * Convertit un {@code CompletionItem} interne en élément LSP4J.
     *
     * <p>Si le candidat porte un {@code autoImportFqn}
     * ({@link jo.lspjava.api.CompletionItem#autoImportFqn()}), on génère un
     * {@code additionalTextEdits} LSP4J avec l'insertion {@code "import <fqn>;\n"}
     * à la bonne position (après la déclaration {@code package} et les imports
     * existants). L'éditeur LSP standard (LSP4J client) applique cet
     * additionalTextEdits AU MOMENT de l'acceptation du candidat — l'utilisateur
     * voit l'import apparaître en même temps que le nom du type, sans action
     * séparée. C'est le pattern « auto-import à la validation » d'Android Studio.</p>
     */
    private CompletionItem toLspCompletionItem(jo.lspjava.api.CompletionItem item, String source) {
        CompletionItem lsp = new CompletionItem(item.label());
        // ANNOTATION : le protocole LSP n'a pas de kind dédié ;
        // elle voyage en Class mais le champ data transporte le tag
        // "annotation" — le client en déduit le badge « @ » (motif KindBadge
        // du projet de référence, leur UiCompletionKind.AnnotationType). PACKAGE
        // voyage en Module (convention Eclipse JDT.ls) → badge « p ».
        if (item.kind() == jo.lspjava.api.CompletionItem.Kind.ANNOTATION) {
            lsp.setKind(CompletionItemKind.Class);
            lsp.setData("annotation");
        } else {
            lsp.setKind(switch (item.kind()) {
                case CLASS -> CompletionItemKind.Class;
                case INTERFACE -> CompletionItemKind.Interface;
                case ENUM -> CompletionItemKind.Enum;
                case METHOD -> CompletionItemKind.Method;
                case CONSTRUCTOR -> CompletionItemKind.Constructor;
                case FIELD -> CompletionItemKind.Field;
                case VARIABLE -> CompletionItemKind.Variable;
                case PARAMETER -> CompletionItemKind.Variable;
                case KEYWORD -> CompletionItemKind.Keyword;
                case SNIPPET -> CompletionItemKind.Snippet;
                case MODULE -> CompletionItemKind.Module;
                case PACKAGE -> CompletionItemKind.Module;
                case TYPE_PARAMETER -> CompletionItemKind.TypeParameter;
                default -> CompletionItemKind.Text;
            });
        }
        lsp.setDetail(item.detail());
        lsp.setInsertText(item.insertText());
        // Templates live/postfix : le texte inséré est en syntaxe snippet
        // LSP ($0, ${1:défaut}, index liés) — le CLIENT gère la navigation
        // entre tab stops, aucun mécanisme éditeur à ajouter côté serveur.
        if (item.isSnippet()) {
            lsp.setInsertTextFormat(InsertTextFormat.Snippet);
        }
        // Éditions additionnelles des templates postfixes : suppression du
        // récepteur « expr. » + insertion d'auto-import éventuelle (offsets
        // de la source ORIGINALE, calculés côté moteur via mapBack). La
        // plage de suppression précède immédiatement la plage principale —
        // jamais de chevauchement (contrainte LSP respectée par construction).
        if (!item.additionalEdits().isEmpty() && source != null && !source.isEmpty()) {
            List<TextEdit> extraEdits = new ArrayList<>();
            for (var e : item.additionalEdits()) {
                Position s = toPosition(source, Math.max(0, e.start()));
                Position en = toPosition(source, Math.min(source.length(), e.end()));
                extraEdits.add(new TextEdit(new Range(s, en), e.newText()));
            }
            lsp.setAdditionalTextEdits(extraEdits);
        }
        // Tri total garanti côté client (portage compareBy(score,
        // name.length, name) de CompletionRanker) : un client LSP trie par
        // sortText seul ; encoder le tie-break (longueur puis nom) dans la
        // clé évite de dépendre de l'ordre d'envoi de la liste.
        lsp.setSortText(String.format("%04d", Integer.MAX_VALUE - item.relevance())
                + String.format("%03d", Math.min(999, item.label().length()))
                + item.label());
        // Auto-import — si le candidat porte un FQN à importer,
        // on dérive un additionalTextEdits avec l'insertion d'import à la
        // bonne position (après package + imports existants). LSP4J client
        // applique cet edit AU MOMENT de l'acceptation du candidat.
        String fqn = item.autoImportFqn();
        if (fqn != null && !fqn.isEmpty() && source != null && !source.isEmpty()
                && lsp.getAdditionalTextEdits() == null) {
            int importOffset = computeImportInsertionOffset(source);
            if (importOffset >= 0) {
                String importText = "import " + fqn + ";\n";
                Position pos = toPosition(source, importOffset);
                TextEdit importEdit = new TextEdit(new Range(pos, pos), importText);
                lsp.setAdditionalTextEdits(List.of(importEdit));
            }
        }
        return lsp;
    }

    /**
     * Calcule l'offset d'insertion d'un nouvel import — après la déclaration
     * {@code package} et après les imports existants, ou en début de fichier
     * si ni l'un ni l'autre.
     *
     * <p>Heuristique textuelle — on reste simple : si le fichier a un
     * {@code package}, on insère après la ligne {@code package;}. Si le
     * fichier a déjà des {@code import}, on insère après le dernier (en
     * gardant le tri alphabétique à l'avenir via l'action « Organiser les
     * imports »).</p>
     */
    private static int computeImportInsertionOffset(String source) {
        // Délègue à CodeActionService (source unique de vérité — les
        // quick-fixes « Importer X » utilisent la même règle).
        return CodeActionService.importInsertOffset(source);
    }

    private Range toLspRange(String source, TextRange range) {
        return new Range(toPosition(source, range.startOffset()), toPosition(source, range.endOffset()));
    }

    private Position toPosition(String source, int offset) {
        int line = 0, character = 0;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') { line++; character = 0; }
            else { character++; }
        }
        return new Position(line, character);
    }

    private int toOffset(String source, Position position) {
        int line = 0, character = 0;
        for (int i = 0; i < source.length(); i++) {
            if (line == position.getLine() && character == position.getCharacter()) return i;
            if (source.charAt(i) == '\n') { line++; character = 0; }
            else { character++; }
        }
        return source.length();
    }
    /**
     * URI LSP ({@code file://…}) → chemin filesystem, avec décodage URL
     * des %xx (notamment %20 pour les espaces) — sinon un chemin avec
     * espace resterait encodé, causant un mismatch entre openDocuments
     * (clé encodée) et environment (clé décodée), et hover/diagnostics
     * cassés sur tous les chemins avec caractères spéciaux.
     */
    private static String uriToPath(String uri) {
        if (uri == null) return "";
        String path;
        if (uri.startsWith("file://")) {
            path = uri.substring("file://".length());
        } else if (uri.startsWith("file:")) {
            // Certains clients envoient « file:/path » sans double slash.
            path = uri.substring("file:".length());
            if (path.startsWith("/")) {
                // OK chemin Unix absolu
            } else {
                // Chemin Windows type « file:C:/... » (rare) — on garde tel quel
            }
        } else {
            return uri;
        }
        // Décode les %xx. Utilise URLDecoder avec UTF-8 (LSP 3.17 §positionEncoding
        // recommande UTF-8 pour les nouveaux serveurs ; lspjava reste sur UTF-16
        // pour les positions mais les URIs suivent la convention RFC 3986).
        // On remplace d'abord '+' par '%2B' pour éviter que URLDecoder ne convertisse
        // les '+' en espaces (les '+' dans un path ne sont pas des espaces en URI).
        String encoded = path.replace("+", "%2B");
        try {
            return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Séquence %xx malformée — on retourne le chemin brut plutôt que de planter.
            return path;
        }
    }

    /** Chemin filesystem → URI LSP ({@code file://…}). */
    private static String pathToUri(String path) {
        if (path == null) return "";
        if (path.startsWith("file://")) return path;
        return "file://" + path;
    }

    /**
     * Construit un overlay {@code path → source} des tampons ouverts dans
     * l'éditeur (cf. {@link #openDocuments}). Utilisé par le renommage
     * multi-fichiers ({@link jo.lspjava.ecj.EcjRenameEngine#renameProject})
     * pour consulter les versions VIVANTES des fichiers frères avant le
     * disque — sinon un frère ouvert non sauvegardé est renommé sur une
     * version périmée.
     */
    private java.util.Map<String, String> buildOpenBufferOverlay() {
        if (openDocuments.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>(openDocuments.size());
        for (var entry : openDocuments.entrySet()) {
            String path = uriToPath(entry.getKey());
            if (!path.isEmpty() && entry.getValue() != null) {
                out.put(path, entry.getValue());
            }
        }
        return out;
    }

    /**
     * Extrait les arguments structurés d'un diagnostic LSP4J depuis son
     * champ {@code data}. Le champ {@code data} peut prendre plusieurs
     * formes selon l'origine du diagnostic : liste JSON transportée par
     * notre serveur (cf. {@link #toLspDiagnostic}), ou objet structural
     * venant d'un autre serveur.
     *
     * <p>On accepte :</p>
     * <ul>
     *   <li>{@code List<String>} (notre format) — renvoyé tel quel ;</li>
     *   <li>tableau JSON ({@code JsonArray}) — converti en liste ;</li>
     *   <li>{@code null} ou autre — liste vide.</li>
     * </ul>
     */
    private static List<String> extractDiagnosticArgs(Object data) {
        if (data == null) return List.of();
        if (data instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(o == null ? "" : o.toString());
            }
            return out;
        }
        // LSP4J peut désérialiser data en JsonElement — on accepte aussi
        // ce cas pour la robustesse (le round-trip JSON-RPC peut transformer
        // notre ArrayList en JsonArray côté client).
        if (data instanceof com.google.gson.JsonElement je) {
            if (je.isJsonArray()) {
                List<String> out = new ArrayList<>(je.getAsJsonArray().size());
                for (var el : je.getAsJsonArray()) {
                    out.add(el.isJsonPrimitive() ? el.getAsString() : el.toString());
                }
                return out;
            }
        }
        return List.of();
    }

}
