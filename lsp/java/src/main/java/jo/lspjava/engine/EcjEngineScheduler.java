package jo.lspjava.engine;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ordonnanceur à lanes sérialisées pour toutes les requêtes qui accèdent au
 * moteur ecj — le point de passage unique des requêtes LSP vers le moteur.
 *
 * <p><strong>Motivation :</strong> le moteur {@code EcjCompilerEngine}
 * exposait historiquement un verrouillage partiel — quelques méthodes
 * historiques étaient {@code synchronized} tandis que la méthode pivot
 * {@code resolveUnit()} et ses appelants (complétion, signature help,
 * inlay hints, rename, import organizer, document symbol) bypassaient le
 * verrou. Sur le pool LSP4J ({@code Executors.newCachedThreadPool()}), un
 * {@code didChange} (verrouillé) et une {@code textDocument/completion}
 * (non verrouillé) à quelques ms d'écart s'exécutaient sur deux threads
 * distincts, en même temps, contre le cache {@code EcjParseCache}
 * (non thread-safe) et les jars partagés du {@code EcjCompilerEnvironment}.</p>
 *
 * <p><strong>Solution :</strong> des workers sérialisés — plus de course
 * possible sur le cache et les jars, par construction, pas par discipline
 * de verrouillage. Trois files d'attente :</p>
 *
 * <ul>
 *   <li><b>{@code submitInteractive(filePath, task)}</b> — hover, complétion,
 *       signature help, inlay hints, document symbol, go-to-definition.
 *       Si une nouvelle requête interactive arrive pour le <em>même fichier</em>
 *       avant que la précédente n'ait commencé, celle-ci est retirée de la
 *       file plutôt qu'exécutée — elle est de toute façon obsolète.
 *       Timeout court ({@link #INTERACTIVE_TIMEOUT_MS}, ≈3 s).</li>
 *   <li><b>{@code submitAnalysis(task)}</b> — renommage, find-references.
 *       Timeout long ({@link #ANALYSIS_TIMEOUT_MS}, ≈15 s), pas de
 *       suppression par supersession (on ne coupe pas un renommage en cours).</li>
 *   <li><b>{@code submitDiagnostics(filePath, task)}</b> — diagnostics,
 *       avec supersession par fichier (identique à l'interactive).</li>
 * </ul>
 *
 * <p>C'est une extension du {@link CancelToken} déjà présent — pas un second
 * mécanisme de concurrence en plus.</p>
 *
 * <p><strong>Cohérence avec le verrou intrinsèque de
 * {@code EcjCompilerEngine.resolveUnit()} :</strong> {@code resolveUnit()}
 * est elle-même {@code synchronized}. Le scheduler est une surcouche qui
 * apporte l'annulation par supersession et le timeout explicite côté
 * appelant ; même sans scheduler, le verrou intrinsèque garantit déjà
 * l'exclusion mutuelle. Le scheduler reste utile pour la suppression des
 * requêtes obsolètes, qui n'est pas soluble par un verrou seul.</p>
 *
 * <p><strong>Thread-safety :</strong> toutes les méthodes publiques sont
 * safe depuis plusieurs threads. Les workers sont créés à la construction.</p>
 *
 * @author jo@Dev
 */
public final class EcjEngineScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("EcjEngineScheduler");

    /** Timeout des requêtes interactives (hover, completion, etc.) — ~3 s. */
    public static final long INTERACTIVE_TIMEOUT_MS = 3_000L;

    /** Timeout des requêtes d'analyse (diagnostics, rename, find-refs) — ~15 s. */
    public static final long ANALYSIS_TIMEOUT_MS = 15_000L;

    /**
     * File interactive — une seule requête en file d'attente par fichier.
     * Quand une nouvelle requête interactive arrive pour un fichier dont une
     * requête est encore en attente (non encore prise par le worker), la
     * précédente est annulée (supersession).
     */
    private final ConcurrentMap<String, FutureTaskNode<?>> interactivePending =
            new ConcurrentHashMap<>();

    /**
     * File DIAGNOSTICS avec une seule requête en attente par fichier
     * (supersession identique à l'interactive). Sans drain dédié, les
     * diagnoses s'empileraient derrière un rename/find-refs longs et
     * bloqueraient inversement la complétion par le verrou
     * {@code resolveUnit}.
     */
    private final ConcurrentMap<String, FutureTaskNode<?>> diagnosticsPending =
            new ConcurrentHashMap<>();

    /** Lane INTERACTIVE — worker sérialisé propre (hover, complétion…). */
    private final ExecutorService interactiveWorker;

    /** Lane ANALYSIS — worker sérialisé propre (rename, find-refs). */
    private final ExecutorService analysisWorker;

    /** Lane DIAGNOSTICS — worker sérialisé propre + supersession par clé. */
    private final ExecutorService diagnosticsWorker;

    /**
     * Pool de watchdogs partagé — ScheduledExecutorService mono-thread qui
     * planifie les watchdogs via schedule(). Les ScheduledFuture retournés
     * sont cancelés si la tâche finit avant le timeout — zéro création de
     * thread par tâche (un new Thread() par tâche représenterait ~240
     * threads/min en frappe rapide), juste une entrée dans la queue.
     *
     * <p>Note : initialisé avec un nom fixe car {@code name} (final) est
     * assigné dans le constructeur, après l'init des champs d'instance. Le
     * nom du thread watchdog n'a pas besoin d'être paramétrable.</p>
     */
    private final java.util.concurrent.ScheduledExecutorService watchdogPool =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jdt-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Compteur de tâches — pour générer des noms uniques dans les logs. */
    private final AtomicLong taskCounter = new AtomicLong();

    /** Statistiques pour le monitoring — pas exposé hors du scheduler. */
    private final AtomicLong supersededCount = new AtomicLong();
    private final AtomicLong timeoutCount = new AtomicLong();

    /** Nom du scheduler (pour logs) — utile quand plusieurs serveurs coexistent. */
    private final String name;

    public EcjEngineScheduler() {
        this("default");
    }

    public EcjEngineScheduler(String name) {
        this.name = name;
        final String prefix = "jdt-engine-" + name;
        // Factory de threads inline — évite une dépendance sur le
        // :tooling:server du projet Android. Daemon pour ne pas bloquer
        // l'arrêt JVM.
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };
        // Trois lanes séparées : le verrou intrinsèque de resolveUnit()
        // garantit toujours l'exclusion mutuelle au niveau moteur ; ce qui
        // change, c'est qu'une complétion n'attend plus derrière les
        // rename/find-refs/diagnoses en file — elle ne subit plus que le
        // travail réellement en cours.
        this.interactiveWorker = Executors.newSingleThreadExecutor(tf);
        this.analysisWorker = Executors.newSingleThreadExecutor(tf);
        this.diagnosticsWorker = Executors.newSingleThreadExecutor(tf);
    }

    /**
     * Soumet une tâche interactive (hover, completion, signature help,
     * inlay hints, document symbol, go-to-definition) sur le worker unique.
     *
     * <p>Si une autre tâche interactive pour le MÊME fichier est encore en
     * attente (pas encore démarrée), elle est annulée — la nouvelle requête
     * la supersede. C'est ce mécanisme qui empêche l'empilement de
     * complétions obsolètes sur une même frappe rapide.</p>
     *
     * @param filePath chemin du fichier focal — clé de supersession
     * @param task     la tâche à exécuter sur le worker unique
     * @param <T>      type de retour
     * @return un {@link Future} permettant l'annulation manuelle et le timeout
     *         explicite côté appelant
     */
    public <T> Future<T> submitInteractive(String filePath, Callable<T> task) {
        long taskId = taskCounter.incrementAndGet();
        long deadline = System.currentTimeMillis() + INTERACTIVE_TIMEOUT_MS;
        FutureTaskNode<T> node = new FutureTaskNode<>(
                "interactive-" + taskId + "-" + filePath, task, deadline, this);

        // Supersession : si une requête interactive pour le même fichier est
        // encore en attente (non démarrée), l'annuler et la remplacer.
        FutureTaskNode<?> prev = interactivePending.put(filePath, node);
        if (prev != null && prev.cancel(false)) {
            long count = supersededCount.incrementAndGet();
            LOG.log(Level.FINE, "[{0}] superseded interactive task {1} (#{2})",
                    new Object[]{name, prev.name, count});
        }

        // Lane INTERACTIVE dédiée.
        interactiveWorker.execute(() -> {
            try {
                // Vérifier qu'on n'a pas été superseded entre submit et exécution.
                if (node.isCancelled()) return;
                // Retirer de la map des pending dès qu'on démarre.
                interactivePending.remove(filePath, node);
                node.run();
            } catch (Throwable t) {
                // Une tâche jetée — pas de crash du worker.
                LOG.log(Level.WARNING,
                        "[" + name + "] interactive task " + node.name + " threw", t);
            }
        });
        return node.future;
    }

    /**
     * Soumet une tâche d'analyse (rename, find-refs) sur la lane ANALYSIS.
     * Pas de supersession — un renommage ne doit pas être coupé en cours
     * par une nouvelle demande. Worker sérialisé propre à cette lane : un
     * rename long ne fait pas la queue devant la complétion (et inversement).
     */
    public <T> Future<T> submitAnalysis(Callable<T> task) {
        long taskId = taskCounter.incrementAndGet();
        long deadline = System.currentTimeMillis() + ANALYSIS_TIMEOUT_MS;
        FutureTaskNode<T> node = new FutureTaskNode<>(
                "analysis-" + taskId, task, deadline, this);

        analysisWorker.execute(() -> {
            try {
                node.run();
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "[" + name + "] analysis task " + node.name + " threw", t);
            }
        });
        return node.future;
    }

    /**
     * Soumet un diagnostic sur la lane éponyme — avec supersession par
     * fichier (identique à l'interactive) : quand les didChange s'enchaînent,
     * seul le dernier diagnose par fichier reste en file, ce qui draine le
     * spam sans jamais empiler des parses complets.
     */
    public <T> Future<T> submitDiagnostics(String filePath, Callable<T> task) {
        long taskId = taskCounter.incrementAndGet();
        long deadline = System.currentTimeMillis() + ANALYSIS_TIMEOUT_MS;
        FutureTaskNode<T> node = new FutureTaskNode<>(
                "diagnostics-" + taskId + "-" + filePath, task, deadline, this);

        FutureTaskNode<?> prev = diagnosticsPending.put(filePath, node);
        if (prev != null && prev.cancel(false)) {
            long count = supersededCount.incrementAndGet();
            LOG.log(Level.FINE, "[{0}] superseded diagnostics task {1} (#{2})",
                    new Object[]{name, prev.name, count});
        }

        diagnosticsWorker.execute(() -> {
            try {
                if (node.isCancelled()) return;
                diagnosticsPending.remove(filePath, node);
                node.run();
            } catch (Throwable t) {
                LOG.log(Level.WARNING,
                        "[" + name + "] diagnostics task " + node.name + " threw", t);
            }
        });
        return node.future;
    }

    /** @return le nombre de tâches interactives superseded depuis le démarrage. */
    public long supersededCount() {
        return supersededCount.get();
    }

    /** @return le nombre de tâches timed out depuis le démarrage. */
    public long timeoutCount() {
        return timeoutCount.get();
    }

    /** Incrémente le compteur de timeout (appelé par le watchdog interne). */
    void noteTimeout() {
        timeoutCount.incrementAndGet();
    }

    /**
     * Planifie un watchdog sur le ScheduledExecutorService partagé. Appelé
     * par FutureTaskNode.run() (classe static nested qui ne peut pas
     * référencer watchdogPool directement).
     *
     * @param task    l'action à exécuter au timeout
     * @param delayMs le délai avant exécution
     * @return le ScheduledFuture (à cancel si la tâche finit avant)
     */
    java.util.concurrent.ScheduledFuture<?> scheduleWatchdog(Runnable task, long delayMs) {
        return watchdogPool.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Arrête les trois lanes. Les tâches en cours sont interrompues.
     * Idempotent.
     */
    public void shutdown() {
        interactiveWorker.shutdownNow();
        analysisWorker.shutdownNow();
        diagnosticsWorker.shutdownNow();
        watchdogPool.shutdownNow();
        boolean terminated = false;
        try {
            terminated = interactiveWorker.awaitTermination(2, TimeUnit.SECONDS)
                    & analysisWorker.awaitTermination(2, TimeUnit.SECONDS)
                    & diagnosticsWorker.awaitTermination(2, TimeUnit.SECONDS)
                    & watchdogPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            LOG.warning("[" + name + "] workers did not terminate in 2s");
        }
    }

    /** Alias de {@link #shutdown()} — permet le try-with-resources. */
    @Override
    public void close() {
        shutdown();
    }

    // ─── Internal node ─────────────────────────────────────────────

    /**
     * Noeud interne représentant une tâche soumise, son Future annulable, et
     * son échéance de timeout.
     */
    private static final class FutureTaskNode<T> {
        final String name;
        final Callable<T> task;
        final long deadlineMs;
        final EcjEngineScheduler owner;
        final AtomicReference<Thread> runningThread = new AtomicReference<>();

        final Future<T> future;

        FutureTaskNode(String name, Callable<T> task, long deadlineMs,
                       EcjEngineScheduler owner) {
            this.name = name;
            this.task = task;
            this.deadlineMs = deadlineMs;
            this.owner = owner;
            this.future = new FutureImpl();
        }

        boolean isCancelled() {
            return future.isCancelled();
        }

        boolean cancel(boolean mayInterrupt) {
            return future.cancel(mayInterrupt);
        }

        void run() throws Exception {
            // Re-vérifier isCancelled() au début de run() pour éviter
            // d'exécuter une tâche superseded entre le check du worker et
            // ici. Sans ce check, une tâche superseded pendant l'exécution
            // serait comptée à la fois dans completed (par la lambda) et
            // dans superseded (par Future.get() qui lève
            // CancellationException).
            if (((FutureImpl) future).isCancelled()) {
                return;
            }
            Thread current = Thread.currentThread();
            runningThread.set(current);
            FutureImpl impl = (FutureImpl) future;
            // Watchdog via ScheduledExecutorService partagé — le
            // ScheduledFuture retourné est cancelé dans le finally si la
            // tâche finit avant le timeout.
            long remaining = deadlineMs - System.currentTimeMillis();
            java.util.concurrent.ScheduledFuture<?> watch = owner.scheduleWatchdog(() -> {
                if (!impl.isDone()) {
                    owner.noteTimeout();
                    impl.cancel(true);
                    LOG.log(Level.FINE, "Task {0} timed out, cancelling", name);
                }
            }, Math.max(0, remaining));
            try {
                T r = task.call();
                impl.completeOk(r);
            } catch (Throwable t) {
                if (impl.isCancelled()) {
                    return;  // Superseded ou timed out — pas une erreur à propager.
                }
                impl.completeWithError(t);
            } finally {
                // Cancel le watchdog planifié (pas besoin d'interrupt, le
                // run() du watchdog vérifie impl.isDone()).
                watch.cancel(false);
                runningThread.set(null);
                // Réinitialiser l'interruption résiduelle pour ne pas polluer
                // le worker thread sur la tâche suivante.
                Thread.interrupted();
            }
        }

        /**
         * Implémentation de Future pour cette tâche.
         *
         * <p>Atomicité via {@code AtomicReference<State>} : sans elle,
         * cancel() et completeOk() peuvent s'exécuter en parallèle et le
         * résultat serait perdu (le client recevrait une
         * CancellationException alors que la tâche a réussi).</p>
         *
         * <p>Note : {@code result}/{@code error} sont écrits AVANT le CAS
         * de transition d'état — un lecteur qui observe COMPLETED est
         * garanti de voir la valeur (les champs sont volatils et le CAS
         * établit un ordre happens-before).</p>
         */
        private final class FutureImpl implements Future<T> {
            private enum State { PENDING, COMPLETED, CANCELLED }
            private final java.util.concurrent.atomic.AtomicReference<State> state =
                    new java.util.concurrent.atomic.AtomicReference<>(State.PENDING);
            private volatile T result;    // lu après COMPLETED
            private volatile Throwable error;  // lu après COMPLETED

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                // CAS atomique PENDING → CANCELLED.
                if (!state.compareAndSet(State.PENDING, State.CANCELLED)) {
                    return false;  // déjà COMPLETED ou CANCELLED
                }
                if (mayInterruptIfRunning) {
                    Thread t = runningThread.get();
                    if (t != null) t.interrupt();
                }
                synchronized (this) { notifyAll(); }
                return true;
            }

            @Override public boolean isCancelled() { return state.get() == State.CANCELLED; }
            @Override public boolean isDone() { return state.get() != State.PENDING; }

            @Override
            public T get() throws InterruptedException,
                    java.util.concurrent.ExecutionException {
                try {
                    return get(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    throw new java.util.concurrent.ExecutionException(e);
                }
            }

            @Override
            public T get(long timeout, TimeUnit unit)
                    throws InterruptedException,
                    java.util.concurrent.ExecutionException,
                    TimeoutException {
                long end = System.nanoTime() + unit.toNanos(timeout);
                synchronized (this) {
                    while (state.get() == State.PENDING) {
                        long remaining = end - System.nanoTime();
                        if (remaining <= 0) throw new TimeoutException(name);
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    }
                    State s = state.get();
                    if (s == State.CANCELLED) throw new CancellationException(name);
                    if (error != null)
                        throw new java.util.concurrent.ExecutionException(error);
                    return result;
                }
            }

            void completeOk(T r) {
                // Écrire le résultat AVANT le CAS : un lecteur qui observe
                // COMPLETED doit voir une valeur non nulle. Si cancel() a
                // gagné la race (CAS échoué), on ne fait rien — le client a
                // déjà reçu CancellationException.
                result = r;
                if (!state.compareAndSet(State.PENDING, State.COMPLETED)) {
                    return;  // déjà CANCELLED
                }
                synchronized (this) { notifyAll(); }
            }

            void completeWithError(Throwable t) {
                // Même discipline que completeOk : écriture avant CAS.
                error = t;
                if (!state.compareAndSet(State.PENDING, State.COMPLETED)) {
                    return;  // déjà CANCELLED
                }
                synchronized (this) { notifyAll(); }
            }
        }
    }
}
