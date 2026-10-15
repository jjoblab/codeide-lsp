package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jo.lspjava.engine.CompilationEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du LOT E (v0.97.0) — moteur PAR module (docs/LSP_JAVA.md §15.7) :
 * {@link CompilationEnvironment} porte une carte de contextes par module
 * (clé : chemin Gradle), chaque contexte dérive SON
 * {@link EcjCompilerEngine} — SON verrou, SES caches.
 *
 * <p>Ce qui doit devenir VRAI (et que ces tests éprouvent) :</p>
 * <ul>
 *   <li>le verrou est PAR MOTEUR : le monitor du module A tenu ne bloque
 *       plus le diagnostic du module B (avant le lot E, UN moteur
 *       partagé — tout se sérialisait) ;</li>
 *   <li>l'invalidation est PÉRIMÈTRE MODULE : remplacer le contexte de A
 *       (sync Gradle de A) dispose le moteur de A SEUL — le moteur de B
 *       et son cache de parse vivent ;</li>
 *   <li>les sémantiques replaceAll sont conservées par module : no-op
 *       système sur un sync redondant, notification des auditeurs sur
 *       un remplacement effectif ;</li>
 *   <li>les champs PARTAGÉS (index disque) invalident TOUS les moteurs ;</li>
 *   <li>{@code dispose()} libère les moteurs de tous les contextes ;</li>
 *   <li>la clé vide/null vise le contexte DÉFAUT (redirection documentée).</li>
 * </ul>
 *
 * @author jo@Dev
 */
class MoteurParModuleTest {

    /** Source Java valide (zéro diagnostic d'erreur). */
    private static final String MAIN_OK = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    String message = "Hello, " + args.length;
                    System.out.println(message);
                }
            }
            """;

    /** Source fautive (au moins une erreur de type). */
    private static final String MAIN_ERR = """
            package demo;

            public class Main {
                public void run() {
                    String s = 42;
                    System.out.println(s);
                }
            }
            """;

    @TempDir
    static Path tmp;

    /** Un répertoire source EXISTANT (passe le filtre de replaceAll). */
    private static java.io.File dirSource(String nom) throws Exception {
        return Files.createDirectories(tmp.resolve(nom)).toFile();
    }

    private static long erreurs(List<jo.lspjava.api.Diagnostic> diags) {
        return diags.stream()
                .filter(d -> d.severity() == jo.lspjava.api.Severity.ERROR)
                .count();
    }

    /** Chauffe le moteur (construction paresseuse de l'environnement ecj). */
    private static void chauffer(EcjCompilerEngine m) {
        m.diagnose("/demo/Main.java", MAIN_OK);
    }

    // ─── Verrou PAR moteur : A tenu ne bloque pas B ───────────────────

    /**
     * Le monitor du moteur du module A est TENU 3 s ; le diagnostic du
     * module B (même {@link CompilationEnvironment}) doit passer —
     * {@code diagnose} est {@code synchronized} SUR le moteur : avec un
     * moteur unique (avant le lot E) cet appel attendrait la libération
     * et le délai de 2 s expirerait. Déterministe : seul le partage du
     * monitor peut faire échouer.
     */
    @Test
    void leVerrouDuModuleANeBloquePlusLeModuleB() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        EcjCompilerEngine a = env.jdt(":app");
        EcjCompilerEngine b = env.jdt(":lib");
        assertNotSame(a, b, "deux modules = deux moteurs distincts");
        chauffer(a);
        chauffer(b);

        CountDownLatch verrouAPris = new CountDownLatch(1);
        CountDownLatch liberation = new CountDownLatch(1);
        Thread titulaire = new Thread(() -> {
            // Même monitor que les services du moteur (méthodes
            // synchronized sur l'instance) — celui du module A SEUL.
            synchronized (a) {
                verrouAPris.countDown();
                try {
                    liberation.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "titulaire-monitor-a");
        titulaire.start();
        assertTrue(verrouAPris.await(5, TimeUnit.SECONDS),
                "le titulaire doit prendre le monitor de A");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<jo.lspjava.api.Diagnostic>> diagnosticB =
                    pool.submit(() -> b.diagnose("/demo/Main.java", MAIN_ERR));
            // Avec un moteur partagé, cet appel resterait bloqué sur le
            // monitor de A (tenu 3 s) et le délai expirerait.
            List<jo.lspjava.api.Diagnostic> diags =
                    diagnosticB.get(2, TimeUnit.SECONDS);
            assertTrue(erreurs(diags) >= 1,
                    "le diagnostic de B doit rester correct : " + diags);
        } finally {
            liberation.countDown();
            titulaire.join(5_000);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // ─── Invalidation de périmètre MODULE (isolation des caches) ──────

    /**
     * Le remplacement du contexte de «&nbsp;:a&nbsp;» dispose le moteur
     * de «&nbsp;:a&nbsp;» SEUL : nouveau moteur (cache de parse vide),
     * pendant que le moteur de «&nbsp;:b&nbsp;» RESTE la même instance
     * avec son cache de parse rempli (le cache de B ne se vide pas quand
     * A re-sync).
     */
    @Test
    void invaliderAVideLeMoteurDeASeulement() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.remplacerContexteModule(":a", List.of(dirSource("src-a")),
                Collections.emptyList());
        env.remplacerContexteModule(":b", List.of(dirSource("src-b")),
                Collections.emptyList());

        EcjCompilerEngine a1 = env.jdt(":a");
        a1.diagnose("/demo/A.java", MAIN_OK);
        assertTrue(a1.parseCacheSize() >= 1, "cache de A rempli");
        EcjCompilerEngine b1 = env.jdt(":b");
        b1.diagnose("/demo/B.java", MAIN_OK);
        assertTrue(b1.parseCacheSize() >= 1, "cache de B rempli");

        // Sync Gradle de A SEUL : les listes changent (autre racine).
        env.remplacerContexteModule(":a", List.of(dirSource("src-a-2")),
                Collections.emptyList());

        EcjCompilerEngine a2 = env.jdt(":a");
        assertNotSame(a1, a2, "le moteur de A doit être reconstruit");
        assertEquals(0, a2.parseCacheSize(), "cache de A vide après re-sync");
        assertSame(b1, env.jdt(":b"),
                "le moteur de B ne doit PAS être invalidé");
        assertTrue(env.jdt(":b").parseCacheSize() >= 1,
                "le cache de B vit toujours");
        env.dispose();
    }

    // ─── Sémantiques replaceAll, par module ───────────────────────────

    /** Sync redondant (mêmes listes) : no-op — même moteur, zéro notification. */
    @Test
    void remplacementRedondantEstUnNoOpSysteme() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        List<java.io.File> sources = List.of(dirSource("src-stable"));
        env.remplacerContexteModule(":app", sources, Collections.emptyList());
        EcjCompilerEngine avant = env.jdt(":app");

        AtomicInteger notifications = new AtomicInteger();
        env.addContextListener(notifications::incrementAndGet);

        env.remplacerContexteModule(":app", sources, Collections.emptyList());
        assertSame(avant, env.jdt(":app"),
                "un sync redondant ne doit pas invalider le moteur");
        assertEquals(0, notifications.get(),
                "un sync redondant ne doit pas notifier");
        env.dispose();
    }

    /** Remplacement effectif : moteur invalidé + auditeurs notifiés UNE fois. */
    @Test
    void remplacementEffectifInvalideEtNotifie() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.remplacerContexteModule(":app",
                List.of(dirSource("src-avant")), Collections.emptyList());
        EcjCompilerEngine avant = env.jdt(":app");

        AtomicInteger notifications = new AtomicInteger();
        env.addContextListener(notifications::incrementAndGet);

        env.remplacerContexteModule(":app",
                List.of(dirSource("src-apres")), Collections.emptyList());
        EcjCompilerEngine apres = env.jdt(":app");
        assertNotSame(avant, apres, "le moteur doit être reconstruit");
        assertEquals(1, notifications.get(),
                "un remplacement effectif notifie exactement une fois");
        env.dispose();
    }

    // ─── Compliance PAR projet (lot S1-a, protocole v10) ──────────────

    /** Source Java 8 valide — mais `var` (Java 10+) et record (Java 16+). */
    private static final String VAR_ET_RECORD = """
            package demo;

            public class Main {
                record Point(int x, int y) {}

                public static void main(String[] args) {
                    var message = "Hello, " + args.length;
                    System.out.println(message);
                }
            }
            """;

    /** Source Java 21 : record + switch à motifs (criterion S1-a). */
    private static final String RECORD_ET_SWITCH = """
            package demo;

            public class Main {
                record Point(int x, int y) {}

                static String decrire(Object o) {
                    return switch (o) {
                        case Point p -> "point " + p.x();
                        case Integer i -> "entier " + i;
                        default -> "autre";
                    };
                }
            }
            """;

    /**
     * Critère d'acceptation S1-a (fixture multi-niveaux) : le MÊME source
     * `var`+record est REFUSÉ sous un module « 8 » (var introduit en 10,
     * records en 16) et CONFORME sous un module « 21 » — deux contextes du
     * MÊME {@link CompilationEnvironment}, deux niveaux.
     */
    @Test
    void laComplianceParModuleDiagnostiqueSelonLeNiveauDuProjet() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.remplacerContexteModule(":vieux", List.of(dirSource("src-vieux")),
                Collections.emptyList(), "8");
        env.remplacerContexteModule(":moderne", List.of(dirSource("src-moderne")),
                Collections.emptyList(), "21");

        // Le niveau est posé tel que demandé (observabilité S1-a).
        assertEquals("8", env.complianceModule(":vieux"));
        assertEquals("21", env.complianceModule(":moderne"));
        assertEquals("8", env.jdt(":vieux").complianceDemandee());
        assertEquals("21", env.jdt(":moderne").complianceDemandee());

        // var + record sous « 8 » : refusés (erreurs de niveau).
        List<jo.lspjava.api.Diagnostic> diags8 =
                env.jdt(":vieux").diagnose("/demo/Main.java", VAR_ET_RECORD);
        assertTrue(erreurs(diags8) >= 1,
                "var/record doit être refusé sous compliance 8 : " + diags8);

        // switch à motifs + record sous « 21 » : conformes (zéro erreur).
        List<jo.lspjava.api.Diagnostic> diags21 =
                env.jdt(":moderne").diagnose("/demo/Main.java", RECORD_ET_SWITCH);
        assertEquals(0, erreurs(diags21),
                "records/switch conformes sous compliance 21 : " + diags21);

        // Le MÊME moteur « 21 » accepte aussi var (le défaut 17+ l'a).
        assertEquals(0, erreurs(env.jdt(":moderne").diagnose("/demo/Main.java", VAR_ET_RECORD)),
                "var conforme sous compliance 21");
        env.dispose();
    }

    /**
     * Sémantiques S1-a du niveau : posé → conservé par la surcharge à 3
     * arguments (un appelant qui ignore le niveau ne le réinitialise pas),
     * {@code null} explicite (surcharge à 4) → RETOUR au défaut ; un
     * changement de niveau SEUL invalide le moteur et notifie les
     * auditeurs ; un sync redondant (listes ET niveau) reste un no-op.
     */
    @Test
    void semantiquesDuNiveauJavaParModule() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.remplacerContexteModule(":app", List.of(dirSource("src-niveau")),
                Collections.emptyList(), "8");
        EcjCompilerEngine avant = env.jdt(":app");

        AtomicInteger notifications = new AtomicInteger();
        env.addContextListener(notifications::incrementAndGet);

        // Surcharge à 3 arguments : le niveau POSÉ est conservé.
        env.remplacerContexteModule(":app", List.of(dirSource("src-niveau")),
                Collections.emptyList());
        assertEquals("8", env.complianceModule(":app"),
                "l'appel ignorant du niveau ne doit pas le réinitialiser");
        assertSame(avant, env.jdt(":app"), "sync redondant (listes+niveau) : no-op");
        assertEquals(0, notifications.get(), "aucune notification sur no-op");

        // Changement de niveau SEUL (mêmes listes) : moteur invalidé +
        // notification — les diagnostics de niveau se mettent à jour.
        env.remplacerContexteModule(":app", List.of(dirSource("src-niveau")),
                Collections.emptyList(), "21");
        assertNotSame(avant, env.jdt(":app"), "changement de niveau : moteur reconstruit");
        assertEquals(1, notifications.get(), "un changement effectif notifie une fois");
        assertEquals("21", env.jdt(":app").complianceDemandee());

        // null explicite : retour au défaut de l'environnement (« 17 »).
        env.remplacerContexteModule(":app", List.of(dirSource("src-niveau")),
                Collections.emptyList(), null);
        assertEquals(null, env.complianceModule(":app"), "retour au défaut");
        assertEquals(2, notifications.get());

        // Le moteur du module retombe sur le niveau de l'environnement
        // (défaut constructeur « 17 » — la chaîne du défaut est ce que le
        // moteur reçoit : un héritage, pas une absence).
        assertEquals("17", env.jdt(":app").complianceDemandee(),
                "moteur reconstruit avec le niveau hérité du défaut");
        env.dispose();
    }

    // ─── Champs partagés : invalidation de TOUS les moteurs ───────────

    /**
     * L'index disque est PARTAGÉ (une plateforme par projet) : l'attacher
     * invalide le moteur du défaut ET celui de chaque module — chacun est
     * construit avec CETTE référence.
     */
    @Test
    void attacherLIndexInvalideDefautEtModules() {
        CompilationEnvironment env = new CompilationEnvironment();
        EcjCompilerEngine defaut1 = env.jdt();
        EcjCompilerEngine app1 = env.jdt(":app");

        env.setIndexNoms(new FauxIndexNomsVide());

        assertNotSame(defaut1, env.jdt(), "moteur défaut invalidé");
        assertNotSame(app1, env.jdt(":app"), "moteur du module invalidé");
        // Idempotence conservée : ré-attacher la MÊME instance ne
        // re-invalide pas.
        EcjCompilerEngine defaut2 = env.jdt();
        EcjCompilerEngine app2 = env.jdt(":app");
        env.setIndexNoms(env.indexNoms());
        assertSame(defaut2, env.jdt());
        assertSame(app2, env.jdt(":app"));
        env.dispose();
    }

    // ─── dispose() : TOUS les moteurs ─────────────────────────────────

    /** shutdown() libère les ressources du défaut ET de chaque module. */
    @Test
    void disposeLibereDefautEtModules() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        env.remplacerContexteModule(":app",
                List.of(dirSource("src-dispose")), Collections.emptyList());
        EcjCompilerEngine defaut1 = env.jdt();
        EcjCompilerEngine app1 = env.jdt(":app");
        assertTrue(env.isEcjInitialized());
        assertTrue(env.isEcjInitialized(":app"));
        assertFalse(env.isEcjInitialized(":jamais-construit"));

        env.dispose();

        assertFalse(env.isEcjInitialized(), "défaut libéré");
        assertFalse(env.isEcjInitialized(":app"), "module libéré");
        assertNotSame(defaut1, env.jdt(), "défaut reconstruit après dispose");
        assertNotSame(app1, env.jdt(":app"), "module reconstruit");
    }

    // ─── Clé vide/null : redirection vers le contexte DÉFAUT ─────────

    /** jdt(null)/jdt("") visent le défaut ; remplacerContexteModule("") aussi. */
    @Test
    void cleVideOuNullViseLeContexteDefaut() throws Exception {
        CompilationEnvironment env = new CompilationEnvironment();
        assertSame(env.jdt(), env.jdt(null));
        assertSame(env.jdt(), env.jdt(""));

        EcjCompilerEngine defaut1 = env.jdt();
        // Redirection qualifiée → défaut : sémantiques replaceAll.
        env.remplacerContexteModule("", List.of(dirSource("src-defaut")),
                Collections.emptyList());
        env.remplacerContexteModule(null, List.of(dirSource("src-defaut")),
                Collections.emptyList());
        assertNotSame(defaut1, env.jdt(),
                "la voie qualifiée vide/null remplace le contexte défaut");
        env.dispose();
    }

    // ─── Concurrence bi-module (reprise du test existant) ─────────────

    /**
     * Reprise bi-module de {@code EcjCompilerEngineConcurrencyTest} : 4
     * threads sur le moteur DÉFAUT + 4 threads sur le moteur de
     * «&nbsp;:app&nbsp;», mix diagnostics/résolutions/hovers
     * simultanés — aucune exception, résultats cohérents SUR CHAQUE
     * moteur (MAIN_OK : 0 erreur, MAIN_ERR : au moins 1).
     */
    @Test
    void mixConcurrentSurDeuxMoteursDistincts() throws Throwable {
        CompilationEnvironment env = new CompilationEnvironment();
        EcjCompilerEngine defaut = env.jdt();
        EcjCompilerEngine app = env.jdt(":app");
        assertNotSame(defaut, app);
        chauffer(defaut);
        chauffer(app);

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
                // Moitié sur le défaut, moitié sur :app — deux moteurs,
                // deux verrous, mêmes caches INDÉPENDANTS.
                final EcjCompilerEngine cible = idx % 2 == 0 ? defaut : app;
                futures.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) { return; }
                    for (int it = 0; it < iterations; it++) {
                        try {
                            String src = (idx + it) % 2 == 0 ? MAIN_OK : MAIN_ERR;
                            var diags = cible.diagnose("/demo/Main.java", src);
                            var cud = cible.resolveUnit("/demo/Main.java", src, false);
                            cible.hoverAt("/demo/Main.java", src, 60);

                            if (src == MAIN_OK && erreurs(diags) != 0) {
                                firstFailure.compareAndSet(null, new AssertionError(
                                        "MAIN_OK a produit " + erreurs(diags)
                                                + " erreurs à it=" + it));
                            }
                            if (src == MAIN_ERR && erreurs(diags) == 0) {
                                firstFailure.compareAndSet(null, new AssertionError(
                                        "MAIN_ERR n'a pas produit d'erreur à it=" + it));
                            }
                            if (cud == null) {
                                firstFailure.compareAndSet(null, new AssertionError(
                                        "resolveUnit null (cache corrompu ?)"));
                            }
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
                    "Aucune exception attendue sous concurrence ; reçue : "
                            + firstFailure.get());
            Throwable failure = firstFailure.get();
            if (failure != null) {
                throw failure;
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            env.dispose();
        }
    }

    // ─── Index vide (champ partagé — test d'invalidation seule) ───────

    /** {@link jo.lspjava.api.IndexNoms} vide : jamais prêt, aucune entrée. */
    private static final class FauxIndexNomsVide
            implements jo.lspjava.api.IndexNoms {

        @Override
        public boolean estPret() { return false; }

        @Override
        public void ajouterObservateurPret(Runnable observateur) {
            // Jamais prêt : l'observateur n'est pas notifié.
        }

        @Override
        public java.nio.file.Path jarDe(String fqcn) { return null; }

        @Override
        public Map<String, List<String>> nomsParPrefixe(String prefixe, int limite) {
            return Collections.emptyMap();
        }

        @Override
        public boolean estPaquet(String nom) { return false; }

        @Override
        public List<String> sousPaquetsDirects(String nom) {
            return Collections.emptyList();
        }

        @Override
        public List<String> typesDansPaquet(String nom) {
            return Collections.emptyList();
        }

        @Override
        public List<String> membresStatiques(String fqcn) {
            return Collections.emptyList();
        }
    }
}
