package jo.lspjava.ecj;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import jo.lspjava.api.IndexNoms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'intégration du BRANCHEMENT index disque (étape 3 lot C,
 * ADR 0111 §8 ; docs/LSP_JAVA.md §15.4) — le moteur vendored consomme
 * l'index Kotlin {@code :lsp:index} à travers la SPI
 * {@link jo.lspjava.api.IndexNoms}. La suite vit dans le module
 * {@code :lsp:testing} (v0.96.3 — au-dessus du moteur et de l'index,
 * pattern {@code :tooling:testing} : le cœur vendored ne dépend
 * d'AUCUN module interne, test compris, ADR 0108).
 *
 * <p><strong>Fixtures :</strong> un jar RÉEL compilé à l'exécution
 * (javac du JDK de test — aucune dépendance à android.jar ou au cache
 * Gradle : les tests tournent partout). Le même jar nourrit le moteur
 * SANS index (comportement historique) et AVEC index (branchement) —
 * la PARITÉ des résultats fait la non-régression.</p>
 *
 * <ul>
 *   <li><b>pariteCompletionEtPaquets</b> — mêmes FQCN par préfixe,
 *       mêmes sous-paquets, mêmes types directs, mêmes paquets, moteur
 *       avec et sans index (l'écart SUBSTRING documenté excepté) ;</li>
 *   <li><b>resolutionViaIndexAutoritaire</b> — à l'état PRÊT le
 *       localisateur est AUTORITATIF (jarDe), les octets se lisent par
 *       le pool de handles PAR CHEMIN — l'index mémoire n'est pas
 *       nécessaire ;</li>
 *   <li><b>reouvertureChaudeSansIndexMemoire</b> — moteur construit
 *       avec un index DÉJÀ prêt : buildJarIndex SAUTÉ (zéro entrée
 *       mémoire) et les requêtes vivent de l'index ;</li>
 *   <li><b>transitionPretVideLeLocateCache</b> — P7/P8 : un négatif
 *       mémorisé pendant la construction se purge à la transition,
 *       l'autorité passe à jarDe (faux index à transition
 *       déterministe).</li>
 * </ul>
 *
 * @author jo@Dev
 */
class IndexNomsIntegrationTest {

    @TempDir
    static Path tmp;

    /** Jar fixture : alpha.{Alpha, AlphaVoisin}, alpha.sous.Profond,
     *  beta.Beta — un champ statique public et une méthode statique
     *  publique dans Alpha (imports statiques). */
    static Path jarFixture;

    static final List<String> SOURCES = List.of(
            """
            package alpha;
            public class Alpha {
                public static int COMPTEUR = 3;
                public static String saluer() { return "salut"; }
                public int instance = 0;
            }
            """,
            """
            package alpha;
            public class AlphaVoisin {
                public void bouger() {}
            }
            """,
            """
            package alpha.sous;
            public class Profond {
                public void descendre() {}
            }
            """,
            """
            package beta;
            public class Beta {
                public void tester() {}
            }
            """);

    @BeforeAll
    static void preparerJar() throws Exception {
        JavaCompiler compilateur = ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                compilateur != null, "JDK sans compilateur (JRE)");

        // (paquet, nom de classe, source) — javac exige fichier = nom de
        // la classe publique.
        record SourceFixture(String paquet, String classe, String texte) {}
        List<SourceFixture> fixtures = List.of(
                new SourceFixture("alpha", "Alpha", SOURCES.get(0)),
                new SourceFixture("alpha", "AlphaVoisin", SOURCES.get(1)),
                new SourceFixture("alpha/sous", "Profond", SOURCES.get(2)),
                new SourceFixture("beta", "Beta", SOURCES.get(3)));

        Path sources = Files.createDirectories(tmp.resolve("src"));
        List<File> fichiers = new ArrayList<>();
        for (SourceFixture f : fixtures) {
            Path dossier = sources.resolve(f.paquet());
            Files.createDirectories(dossier);
            Path fichier = dossier.resolve(f.classe() + ".java");
            Files.writeString(fichier, f.texte(), StandardCharsets.UTF_8);
            fichiers.add(fichier.toFile());
        }
        Path sortie = Files.createDirectories(tmp.resolve("classes"));
        try (StandardJavaFileManager gestionnaire =
                     compilateur.getStandardFileManager(null, null, null)) {
            var unites = gestionnaire.getJavaFileObjectsFromFiles(fichiers);
            boolean ok = compilateur.getTask(null, gestionnaire, null,
                    List.of("-d", sortie.toString()), null, unites).call();
            assertTrue(ok, "la fixture doit compiler");
        }
        jarFixture = tmp.resolve("fixture-lot-c.jar");
        try (JarOutputStream jar = new JarOutputStream(
                new java.io.FileOutputStream(jarFixture.toFile()),
                new Manifest())) {
            ajouterDossier(jar, sortie, sortie);
        }
        assertTrue(Files.isRegularFile(jarFixture));
    }

    /** Recopie récursive des .class dans le jar (chemins relatifs). */
    private static void ajouterDossier(JarOutputStream jar, Path racine,
                                       Path dossier) throws IOException {
        try (var flux = Files.list(dossier)) {
            for (Path p : flux.sorted().toList()) {
                if (Files.isDirectory(p)) {
                    ajouterDossier(jar, racine, p);
                } else if (p.toString().endsWith(".class")) {
                    String nom = racine.relativize(p).toString()
                            .replace(File.separatorChar, '/');
                    jar.putNextEntry(new ZipEntry(nom));
                    jar.write(Files.readAllBytes(p));
                    jar.closeEntry();
                }
            }
        }
    }

    @AfterAll
    static void nettoyer() {
        // @TempDir nettoie ; rien d'autre à libérer (les moteurs de test
        // sont dispos par dispose() dans chaque cas).
    }

    // ─── Parité avec/sans index (non-régression du branchement) ───────

    @Test
    void pariteCompletionEtPaquets() throws Exception {
        EcjCompilerEngine reference = new EcjCompilerEngine(
                List.of(), List.of(jarFixture.toFile()), "17");
        AdaptateurIndexNoms index = AdaptateurIndexNoms.enConstruction(
                tmp.resolve("stockage-parite"), List.of(jarFixture));
        index.attendreFin(15_000);
        assertTrue(index.estPret(), "l'index doit être prêt");

        EcjCompilerEngine branche = new EcjCompilerEngine(
                List.of(), List.of(jarFixture.toFile()), "17", null, index);

        // Complétion de types par préfixe — l'écart SUBSTRING documenté
        // n'apparaît pas ici (« Al »/« Pro »/« Bet » matchent par préfixe).
        for (String prefixe : new String[]{"Al", "Alpha", "Pro", "Bet", "a", "A"}) {
            List<String> sans = reference.typeIndex().findTypesByPrefix(prefixe, 60);
            List<String> avec = branche.typeIndex().findTypesByPrefix(prefixe, 60);
            assertEquals(sans, avec, "préfixe « " + prefixe + " » : parité");
        }
        // Nom EXACT insensible à la casse : « pro » atteint Profond.
        assertEquals(
                reference.typeIndex().findTypesByPrefix("pro", 60),
                branche.typeIndex().findTypesByPrefix("pro", 60));
        // fqnsForSimpleName (quick-fix import).
        assertEquals(
                reference.typeIndex().fqnsForSimpleName("Alpha"),
                branche.typeIndex().fqnsForSimpleName("Alpha"));
        assertEquals(
                reference.typeIndex().fqnsForSimpleName("beta"),
                branche.typeIndex().fqnsForSimpleName("beta"));
        // Sous-paquets + types directs + paquets.
        assertEquals(reference.typeIndex().subPackages("alpha"),
                branche.typeIndex().subPackages("alpha"));
        assertEquals(reference.typeIndex().subPackages(""),
                branche.typeIndex().subPackages(""));
        assertEquals(reference.typeIndex().directTypesInPackage("alpha"),
                branche.typeIndex().directTypesInPackage("alpha"));
        assertEquals(reference.typeIndex().directTypesInPackage("alpha.sous"),
                branche.typeIndex().directTypesInPackage("alpha.sous"));
        assertTrue(branche.typeIndex().isPackage("alpha"));
        assertTrue(branche.typeIndex().isPackage("alpha.sous"));
        assertTrue(branche.typeIndex().isPackage("beta"));
        assertFalse(branche.typeIndex().isPackage("gamma"));

        // Résolution ecj : diagnose équivalente sur une source qui
        // UTILISE le jar (import + instanciation) — l'autorité de
        // localisation change la voie, pas le résultat.
        String source = """
                package exemple;
                import alpha.Alpha;
                public class Exemple {
                    Alpha a = new Alpha();
                }
                """;
        Path fichier = tmp.resolve("Exemple.java");
        Files.writeString(fichier, source, StandardCharsets.UTF_8);
        List<jo.lspjava.api.Diagnostic> dSans =
                reference.diagnose(fichier.toString(), source);
        List<jo.lspjava.api.Diagnostic> dAvec =
                branche.diagnose(fichier.toString(), source);
        assertEquals(erreurs(dSans), erreurs(dAvec),
                "diagnostics équivalents (résolution via index)");

        reference.dispose();
        branche.dispose();
        index.fermer();
    }

    // ─── Autorité à l'état PRÊT ────────────────────────────────────────

    @Test
    void resolutionViaIndexAutoritaire() throws Exception {
        AdaptateurIndexNoms index = AdaptateurIndexNoms.enConstruction(
                tmp.resolve("stockage-autorite"), List.of(jarFixture));
        index.attendreFin(15_000);
        assertTrue(index.estPret());

        // Le jar porteur rendu par l'index EST le jar fixture.
        assertEquals(jarFixture.toAbsolutePath(),
                index.jarDe("alpha.Alpha").toAbsolutePath());
        assertNull(index.jarDe("inconnu.PasLa"));

        EcjCompilerEngine branche = new EcjCompilerEngine(
                List.of(), List.of(jarFixture.toFile()), "17", null, index);
        // La lecture binaire passe par la voie AUTORITATIVE : ClassFileReader
        // obtenu SANS l'index mémoire (celui-ci existe ici — construit
        // pendant la fenêtre — mais la voie PRÊT ne l'utilise pas).
        var lecteur = branche.binaryType("alpha.Alpha");
        assertNotNull(lecteur, "binaire résolu via l'index PRÊT");
        assertNotNull(branche.binaryType("alpha.sous.Profond"));
        assertNull(branche.binaryType("inconnu.PasLa"));
        // Membres statiques publics (imports statiques) — via la SPI.
        assertTrue(index.membresStatiques("alpha.Alpha").contains("COMPTEUR"));
        assertTrue(index.membresStatiques("alpha.Alpha").contains("saluer"));

        branche.dispose();
        index.fermer();
    }

    // ─── Réouverture à chaud : index mémoire SAUTÉ ─────────────────────

    @Test
    void reouvertureChaudeSansIndexMemoire() throws Exception {
        // Premier passage : construit les segments sur disque.
        AdaptateurIndexNoms premier = AdaptateurIndexNoms.enConstruction(
                tmp.resolve("stockage-chaud"), List.of(jarFixture));
        premier.attendreFin(15_000);
        assertTrue(premier.estPret());
        premier.fermer();

        // Deuxième moteur, MÊME index (déjà prêt) : la réouverture à chaud
        // NE reconstruit PAS l'index mémoire — les requêtes vivent de
        // l'index disque (c'est la cible « ouverture index chaud »).
        AdaptateurIndexNoms index = AdaptateurIndexNoms.enConstruction(
                tmp.resolve("stockage-chaud"), List.of(jarFixture));
        index.attendreFin(15_000);
        assertTrue(index.estPret());
        // Reprise : les segments existent (le constructeur les valide
        // par leur en-tête — 10-25 ms mesurés à 250 jars à l'étape 2).

        EcjCompilerEngine branche = new EcjCompilerEngine(
                List.of(), List.of(jarFixture.toFile()), "17", null, index);
        // L'index mémoire n'a PAS été construit (buildJarIndex sauté)…
        assertEquals(0, branche.typeIndex().debugIndexedJarCount(),
                "index mémoire sauté à l'état PRÊT");
        // …mais tout fonctionne : complétion, paquets, résolution.
        List<String> al = branche.typeIndex().findTypesByPrefix("Al", 60);
        assertTrue(al.contains("alpha.Alpha"), "complétion via index : " + al);
        assertTrue(al.contains("alpha.AlphaVoisin"), "voisin : " + al);
        assertTrue(branche.typeIndex().isPackage("alpha"));
        assertEquals(List.of("sous"), branche.typeIndex().subPackages("alpha"));
        assertEquals(List.of("alpha.Alpha", "alpha.AlphaVoisin"),
                branche.typeIndex().directTypesInPackage("alpha"));
        assertNotNull(branche.binaryType("alpha.Alpha"));
        // Le moteur reste UTILISABLE (isLikelyUsable exige une source
        // binaire — l'index branché compte, cf. hasBinarySource).
        assertTrue(branche.isLikelyUsable());

        branche.dispose();
        index.fermer();
    }

    // ─── P7/P8 : purge du cache de localisation à la transition ───────

    @Test
    void transitionPretVideLeLocateCache() throws Exception {
        // Faux index à transition DÉTERMINISTE (le vrai construit en
        // quelques ms — impossible d'attacher pendant la fenêtre de
        // façon stable) : le CONTRAT est ce qui est testé, pas la vitesse.
        FauxIndexNoms faux = new FauxIndexNoms(jarFixture);

        EcjCompilerEngine moteur = new EcjCompilerEngine(
                List.of(), List.of(jarFixture.toFile()), "17", null, faux);
        // fenêtre de construction : l'index mémoire est le substrat
        // (1 jar indexé — la fixture a 4 classes).
        assertEquals(1, moteur.typeIndex().debugIndexedJarCount());

        // Un négatif mémorisé pendant la fenêtre (classe absente).
        assertNull(moteur.binaryType("inconnu.PasLa"));
        assertTrue(moteur.typeIndex().debugLocateCacheSize() >= 1,
                "le négatif doit être mémorisé pendant la construction");

        // Transition PRÊT : le cache se vide (P7/P8)…
        faux.declarerPret();
        assertEquals(0, moteur.typeIndex().debugLocateCacheSize(),
                "le cache de localisation doit être vidé à la transition");

        // …et l'autorité passe à jarDe : un type absent du FAUX index
        // n'est plus re-sondé — jarDe est appelé (compté) et le négatif
        // est définitif côté jars.
        faux.reinitialiserCompteur();
        assertNull(moteur.binaryType("inconnu.PasLa"));
        assertTrue(faux.appelsJarDe() >= 1,
                "à l'état PRÊT la sonde DOIT passer par jarDe");
        // Les classes connues de l'index faux résolvent quand même.
        assertNotNull(moteur.binaryType("alpha.Alpha"));

        moteur.dispose();
    }

    // ─── Le faux index (transition déterministe) ───────────────────────

    /** Faux {@link IndexNoms} : PRÊT déclenché manuellement, jarDe
     *  délègue au VRAI index (l'autorité de contenu reste exacte). */
    private static final class FauxIndexNoms implements IndexNoms {
        private final AdaptateurIndexNoms delegue;
        private volatile boolean pret;
        private final List<Runnable> observateurs = new ArrayList<>();
        private int appelsJarDe;

        FauxIndexNoms(Path jar) throws Exception {
            delegue = AdaptateurIndexNoms.enConstruction(
                    tmp.resolve("stockage-faux"), List.of(jar));
            delegue.attendreFin(15_000);
        }

        void declarerPret() {
            pret = true;
            for (Runnable r : new ArrayList<>(observateurs)) {
                r.run();
            }
            observateurs.clear();
        }

        void reinitialiserCompteur() {
            appelsJarDe = 0;
        }

        int appelsJarDe() {
            return appelsJarDe;
        }

        @Override
        public boolean estPret() {
            return pret;
        }

        @Override
        public void ajouterObservateurPret(Runnable observateur) {
            if (pret) {
                observateur.run();
            } else {
                observateurs.add(observateur);
            }
        }

        @Override
        public Path jarDe(String fqcn) {
            appelsJarDe++;
            return pret ? delegue.jarDe(fqcn) : null;
        }

        @Override
        public Map<String, List<String>> nomsParPrefixe(
                String prefixe, int limite) {
            return delegue.nomsParPrefixe(prefixe, limite);
        }

        @Override
        public boolean estPaquet(String nom) {
            return delegue.estPaquet(nom);
        }

        @Override
        public List<String> sousPaquetsDirects(String nom) {
            return delegue.sousPaquetsDirects(nom);
        }

        @Override
        public List<String> typesDansPaquet(String nom) {
            return delegue.typesDansPaquet(nom);
        }

        @Override
        public List<String> membresStatiques(String fqcn) {
            return delegue.membresStatiques(fqcn);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static List<String> erreurs(List<jo.lspjava.api.Diagnostic> diags) {
        List<String> out = new ArrayList<>();
        for (jo.lspjava.api.Diagnostic d : diags) {
            if (d.severity() == jo.lspjava.api.Severity.ERROR) {
                out.add(d.range() + " " + d.message());
            }
        }
        return out;
    }
}
