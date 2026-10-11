package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de concurrence pour {@link EcjCompilerEngine}.
 *
 * <p>La méthode pivot {@code resolveUnit()} lit/écrit le
 * {@link EcjParseCache} non thread-safe et ouvre les jars partagés
 * du {@link EcjCompilerEnvironment} ; de nombreux points d'entrée LSP
 * (complétion, signature help, inlay hints, rename, import organizer,
 * document symbol) y passent, exposant le moteur à une course réelle sur
 * le pool LSP4J ({@code Executors.newCachedThreadPool()}).</p>
 *
 * <p>Ce test reproduit la charge concurrente (2+ threads soumettant des
 * diagnostics + hovers + résolutions simultanées sur le même moteur) et
 * valide qu'aucune exception n'est levée, que le cache n'est pas corrompu
 * et que les résultats sont cohérents.</p>
 *
 * @author jo@Dev
 */
class EcjCompilerEngineConcurrencyTest {

    /** Source Java valide utilisée pour stresser le moteur. */
    private static final String MAIN_OK = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    String message = "Hello, " + args.length;
                    System.out.println(message);
                }
            }
            """;

    /** Source avec erreur — l'absence de diagnostics changerait selon la course. */
    private static final String MAIN_ERR = """
            package demo;

            public class Main {
                public void run() {
                    String s = 42;
                    System.out.println(s);
                }
            }
            """;

    private EcjCompilerEngine newEngine() {
        return new EcjCompilerEngine(
                Collections.<java.io.File>emptyList(),
                Collections.<java.io.File>emptyList(),
                "17");
    }

    /**
     * Soumet en parallèle un mix de diagnostics + resolveUnit + hover sur le
     * MÊME moteur partagé (situation réelle du serveur LSP multi-thread).
     *
     * <p>Assertion : aucune exception, le nombre de diagnostics sur MAIN_ERR
     * est stable à travers tous les appels (le cache ne renvoie pas
     * silencieusement un résultat d'une autre frappe).</p>
     */
    @Test
    void concurrentMixOfOperationsDoesNotCorruptCache() throws Throwable {
        EcjCompilerEngine engine = newEngine();
        int threads = 8;
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) { return; }
                    for (int it = 0; it < iterations; it++) {
                        try {
                            // Alternance MAIN_OK (0 erreur) / MAIN_ERR (≥1 erreur).
                            String src = (idx + it) % 2 == 0 ? MAIN_OK : MAIN_ERR;
                            var diags = engine.diagnose("/demo/Main.java", src);

                            // Résolution directe via resolveUnit.
                            var cud = engine.resolveUnit("/demo/Main.java", src, false);

                            // Hover — un autre point d'entrée du moteur.
                            engine.hoverAt("/demo/Main.java", src, 60);

                            // Cohérence : MAIN_OK ne doit JAMAIS produire d'erreur.
                            if (src == MAIN_OK) {
                                long errCount = diags.stream()
                                        .filter(d -> d.severity() == jo.lspjava.api.Severity.ERROR)
                                        .count();
                                if (errCount != 0) {
                                    firstFailure.compareAndSet(null,
                                            new AssertionError(
                                                    "MAIN_OK a produit " + errCount
                                                            + " erreurs à it=" + it
                                                            + " (cache corrompu ?)"));
                                }
                            }
                            // MAIN_ERR doit toujours produire ≥1 erreur.
                            if (src == MAIN_ERR) {
                                long errCount = diags.stream()
                                        .filter(d -> d.severity() == jo.lspjava.api.Severity.ERROR)
                                        .count();
                                if (errCount == 0) {
                                    firstFailure.compareAndSet(null,
                                            new AssertionError(
                                                    "MAIN_ERR n'a pas produit d'erreur à it=" + it
                                                            + " (cache corrompu ?)"));
                                }
                            }
                            assertNotNull(cud, "resolveUnit ne doit pas retourner null");
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                            firstFailure.compareAndSet(null, t);
                        }
                    }
                }));
            }

            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            assertEquals(0, errors.get(),
                    "Aucune exception attendue sous concurrence ; reçue : " + firstFailure.get());
            Throwable failure = firstFailure.get();
            if (failure != null) {
                throw failure;
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "Le pool de threads doit se terminer proprement");
        }
    }

    /**
     * Stress test dédié à {@code resolveUnit()}. Deux threads appellent
     * resolveUnit en boucle sur des sources différentes (clés de cache
     * distinctes) — l'invalidation mutuelle du {@link EcjParseCache}
     * et les accès concurrents à {@code entry} volatile non thread-safe
     * ne doivent pas provoquer de crash.
     */
    @Test
    void twoThreadsResolveDifferentSourcesConcurrentlyWithoutCrash() throws Throwable {
        EcjCompilerEngine engine = newEngine();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();

            // Thread A : MAIN_OK en boucle
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { return 0; }
                int nonNull = 0;
                for (int i = 0; i < 20; i++) {
                    var cud = engine.resolveUnit("/demo/A.java", MAIN_OK, false);
                    if (cud != null) nonNull++;
                }
                return nonNull;
            }));

            // Thread B : MAIN_ERR en boucle (clé de cache différente —> invalidation)
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) { return 0; }
                int nonNull = 0;
                for (int i = 0; i < 20; i++) {
                    var cud = engine.resolveUnit("/demo/B.java", MAIN_ERR, false);
                    if (cud != null) nonNull++;
                }
                return nonNull;
            }));

            start.countDown();
            for (Future<Integer> f : futures) {
                Integer nonNull = f.get(30, TimeUnit.SECONDS);
                assertNotNull(nonNull);
                assertTrue(nonNull > 0, "Au moins une résolution doit réussir");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
