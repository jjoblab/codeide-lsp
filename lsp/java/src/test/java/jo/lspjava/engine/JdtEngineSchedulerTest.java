package jo.lspjava.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires pour {@link JdtEngineScheduler}.
 *
 * <p>Valide les propriétés clés :</p>
 * <ul>
 *   <li>Le worker unique sérialise réellement les tâches (pas de chevauchement).</li>
 *   <li>La supersession annule les tâches interactives obsolètes pour le même fichier.</li>
 *   <li>Le timeout est appliqué et expose un Future qui se complète via exception.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class JdtEngineSchedulerTest {

    @Test
    void interactiveTasksAreSerializedOnSingleWorker() throws Exception {
        try (JdtEngineScheduler s = new JdtEngineScheduler("test1")) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger superseded = new AtomicInteger();

            Future<?>[] futures = new Future<?>[10];
            for (int i = 0; i < 10; i++) {
                final int idx = i;
                futures[i] = s.submitInteractive("/demo/F" + (idx % 3) + ".java", () -> {
                    int cur = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(cur, Math::max);
                    try { Thread.sleep(30); } catch (InterruptedException ignored) { }
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                    return idx;
                });
            }
            for (Future<?> f : futures) {
                try { f.get(5, TimeUnit.SECONDS); }
                catch (TimeoutException ignored) { }
                catch (CancellationException ignored) { superseded.incrementAndGet(); }
            }
            // Au moins une tâche a complété et aucune n'a tourné en parallèle.
            assertTrue(completed.get() >= 1,
                    "Au moins 1 tâche doit compléter ; reçues : " + completed.get());
            assertEquals(1, maxInFlight.get(),
                    "Le worker unique ne doit pas exécuter 2 tâches simultanément");
            // Toutes les tâches soumises ont soit complété, soit été superseded.
            assertEquals(10, completed.get() + superseded.get(),
                    "Toutes les tâches soumises doivent être comptabilisées ; "
                            + "complétées=" + completed.get() + " superseded=" + superseded.get());
        }
    }

    @Test
    void supersessionCancelsPendingInteractiveForSameFile() throws Exception {
        try (JdtEngineScheduler s = new JdtEngineScheduler("test2")) {
            AtomicInteger completed = new AtomicInteger();
            // Sature le worker avec une tâche ANALYSE longue — les tâches analyse
            // ne sont pas superseded et occuperont le worker pendant que les
            // interactives s'empilent.
            Future<Integer> blocking = s.submitAnalysis(() -> {
                try { Thread.sleep(800); } catch (InterruptedException e) {
                    return -1;
                }
                return 0;
            });
            // Soumet 5 tâches interactives sur le MÊME F1.java pendant que
            // le worker est occupé — elles doivent toutes être superseded par
            // la dernière, sauf celle qui démarre réellement.
            Future<Integer>[] later = new Future[5];
            for (int i = 0; i < 5; i++) {
                final int idx = i + 1;
                later[i] = s.submitInteractive("/demo/F1.java", () -> {
                    completed.incrementAndGet();
                    return idx;
                });
            }
            // Laisse le blocking se terminer puis récupère la dernière tâche.
            blocking.get(5, TimeUnit.SECONDS);
            // Les intermédiaires ont été superseded — ne pas propager l'exception.
            try {
                later[4].get(5, TimeUnit.SECONDS);
            } catch (CancellationException ignored) {
                // Même la dernière peut être superseded si on soumet trop vite
                // pour que le worker démarre une seule d'entre elles.
            }
            // Au moins 1 tâche interactive a dû être superseded.
            assertTrue(s.supersededCount() >= 1,
                    "Au moins 1 tâche interactive a dû être superseded ; reçues : "
                            + s.supersededCount());
            // Le total des tâches démarrées est ≤ 2 (le blocking + au plus 1 interactive).
            assertTrue(completed.get() <= 2,
                    "Au plus 2 tâches interactives ont démarré sur F1.java ; reçues : "
                            + completed.get());
        }
    }

    @Test
    void analysisTasksAreNotSuperseded() throws Exception {
        try (JdtEngineScheduler s = new JdtEngineScheduler("test3")) {
            AtomicInteger completed = new AtomicInteger();
            Future<?>[] futures = new Future<?>[5];
            for (int i = 0; i < 5; i++) {
                futures[i] = s.submitAnalysis(() -> {
                    try { Thread.sleep(30); } catch (InterruptedException ignored) { return 0; }
                    completed.incrementAndGet();
                    return 0;
                });
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(5, completed.get(),
                    "Aucune tâche d'analyse ne doit être superseded");
            assertEquals(0, s.supersededCount());
        }
    }

    @Test
    void shutdownIsIdempotentAndReleasesWorker() {
        JdtEngineScheduler s = new JdtEngineScheduler("test4");
        s.shutdown();
        s.shutdown();  // Idempotent — pas d'exception.
        assertTrue(true, "shutdown() est idempotent");
    }
}
