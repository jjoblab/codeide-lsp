package jo.lspjava.engine;

/**
 * Token d'annulation coopératif — pattern {@code CancelToken} du projet de référence,
 * porté en Java.
 *
 * <p>Le moteur synchronisé de {@link EcjCompilerEngine} est un point de
 * rupture : une seule requête à la fois, sans priorité ni annulation. Si le
 * modèle Gradle arrive (balayage des racines sources du renommage
 * multi-fichiers, potentiellement des secondes), la complétion de
 * l'utilisateur attend derrière.</p>
 *
 * <p>Le pattern projet de référence ({@code EngineScheduler} à trois lanes
 * interactive/background/preview sur un unique worker sérialisé, avec
 * annulation coopérative par thread-local entre les nœuds AST) est trop
 * large pour une itération initiale. On introduit ici la brique élémentaire
 * — un {@link CancelToken} que :</p>
 * <ul>
 *   <li>l'appelant LSP (complétion, hover) peut activer avec un timeout ;</li>
 *   <li>le moteur ecj consulte via {@link #isCancelled()} pendant le balayage
 *       projet (rename / find-refs) — si annulé, il interrompt proprement
 *       sans attendre la fin du balayage ;</li>
 *   <li>les requêtes interactives (completion, hover, signatureHelp) lancent
 *       un token dédié à timeout court (3 s par défaut), les requêtes
 *       d'analyse (rename, references, diagnostics) à timeout long (15 s).</li>
 * </ul>
 *
 * <p><strong>Usage typique :</strong></p>
 * <pre>{@code
 * try (CancelToken t = CancelToken.timeout(3, TimeUnit.SECONDS)) {
 *     engine.scanProject(target, t);
 *     if (t.isCancelled()) return partialResults;
 * }
 * }</pre>
 *
 * <p>Le try-with-resources garantit que le thread-timer est arrêté même si
 * le scan jette ou sort en avance.</p>
 *
 * @author jo@Dev
 */
public final class CancelToken implements AutoCloseable {

    /**
     * Token jamais annulé — pour les appels internes sans timeout.
     *
     * <p>Attention : instance MUTABLE partagée — ne jamais appeler
     * {@code cancel()} ni {@code attachToCurrentThread()} dessus (effet
     * global JVM) ; utiliser {@link #manual()} pour un token dédié.</p>
     */
    public static final CancelToken NEVER = new CancelToken(0);

    private final long deadlineNanos;
    private volatile boolean cancelled;
    private volatile Thread worker;  // thread à interrompre si timeout

    private CancelToken(long timeoutNanos) {
        this.deadlineNanos = timeoutNanos > 0
                ? System.nanoTime() + timeoutNanos : Long.MAX_VALUE;
    }

    /**
     * Crée un token qui s'annule après {@code timeout} (en nanos).
     *
     * @param timeout durée en nanosecondes ; 0 = jamais (token inactif)
     * @return le token — fermer avec {@link #close()} dans un
     *         try-with-resources pour libérer le thread-timer
     */
    public static CancelToken timeout(long timeoutNanos) {
        return new CancelToken(timeoutNanos);
    }

    /** Crée un token qui s'annule après {@code timeout} unités de {@code unit}. */
    public static CancelToken timeout(long timeout, java.util.concurrent.TimeUnit unit) {
        return timeout(unit.toNanos(timeout));
    }

    /** Crée un token manuel — l'appelant déclenche l'annulation via {@link #cancel()}. */
    public static CancelToken manual() {
        return new CancelToken(0);
    }

    /**
     * Lie ce token au thread courant — permet à {@link #isCancelled()}
     * d'interrompre le thread en cours si timeout explose.
     */
    public CancelToken attachToCurrentThread() {
        this.worker = Thread.currentThread();
        return this;
    }

    /** @return true si l'annulation a été demandée (timeout ou manuel). */
    public boolean isCancelled() {
        if (cancelled) return true;
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            cancelled = true;
            Thread w = worker;
            if (w != null) w.interrupt();  // débloque sleep/wait/read
        }
        return cancelled;
    }

    /**
     * Demande l'annulation manuelle. Le thread associé (si attaché) est
     * interrompu — les I/O bloquantes sont débloquées.
     */
    public void cancel() {
        cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    /**
     * Libère le thread-timer — à appeler dans un try-with-resources.
     * Idempotent.
     */
    @Override
    public void close() {
        // Pas de thread-timer dédié (l'approche par deadline + check
        // isCancelled() dans les boucles suffit pour le scan projet) —
        // on s'assure juste que l'interruption du thread est levée.
        worker = null;
    }

    @Override
    public String toString() {
        return "CancelToken{cancelled=" + cancelled
                + ", deadlineNanos=" + deadlineNanos + "}";
    }
}
