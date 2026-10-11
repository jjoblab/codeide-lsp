package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du moteur ecj in-process ({@link JdtCompilerEngine}).
 *
 * <p>Ces tests tournent sur la JVM desktop et consomment le jar ecJ PATCHÉ
 * (tâche {@code patchEcjForArt}) — ils valident donc AUSSI indirectement que
 * les shims ({@code RuntimeVersion}, {@code InputStreamCompat}) satisfont
 * les références réécrites : le Parser ecJ passe par
 * {@code InputStream.readAllBytes} (réécrit vers le shim) pour charger ses
 * tables dès le premier resolve.</p>
 *
 * <p>La plateforme ({@code java.lang.Object}, {@code String}…) vient de
 * l'image jrt du JDK courant — le chemin desktop de l'index partagé.</p>
 *
 * @author jo@Dev
 */
class JdtCompilerEngineTest {

    private static final String MAIN_OK = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    String message = "Hello, " + args.length;
                    System.out.println(message);
                }
            }
            """;

    private JdtCompilerEngine newEngine() {
        return new JdtCompilerEngine(
                Collections.<File>emptyList(),
                Collections.<File>emptyList(),
                "17");
    }

    @Test
    void engineIsUsableOnDesktopViaJrt() {
        assertTrue(newEngine().isLikelyUsable(),
                "Sur JVM desktop, l'image jrt doit rendre le moteur utilisable");
    }

    @Test
    void validCodeProducesNoErrors() throws Throwable {
        JdtCompilerEngine engine = newEngine();
        List<Diagnostic> diags = engine.diagnose("/demo/Main.java", MAIN_OK);
        long errors = diags.stream().filter(d -> d.severity() == Severity.ERROR).count();
        assertTrue(errors == 0,
                "Code valide — attendu 0 erreur, reçu : " + diags);
    }

    @Test
    void undefinedVariableIsReported() throws Throwable {
        String source = """
                package demo;

                public class Main {
                    public void run() {
                        int x = undefinedVariable;
                        System.out.println(x);
                    }
                }
                """;
        List<Diagnostic> diags = newEngine().diagnose("/demo/Main.java", source);
        assertTrue(diags.stream().anyMatch(d ->
                        d.severity() == Severity.ERROR
                                && d.message() != null
                                && d.message().contains("undefinedVariable")),
                "Attendu une erreur sur undefinedVariable, reçu : " + diags);
    }

    @Test
    void typeMismatchIsReported() throws Throwable {
        String source = """
                package demo;

                public class Main {
                    public void run() {
                        String s = 42;
                        System.out.println(s);
                    }
                }
                """;
        List<Diagnostic> diags = newEngine().diagnose("/demo/Main.java", source);
        assertTrue(diags.stream().anyMatch(d ->
                        d.severity() == Severity.ERROR
                                && d.message() != null
                                && d.message().toLowerCase().contains("mismatch")),
                "Attendu une erreur de type mismatch, reçu : " + diags);
    }

    @Test
    void voidMethodReturnIsReported() throws Throwable {
        String source = """
                package demo;

                public class Main {
                    public void run() {
                        return 42;
                    }
                }
                """;
        List<Diagnostic> diags = newEngine().diagnose("/demo/Main.java", source);
        assertTrue(diags.stream().anyMatch(d ->
                        d.severity() == Severity.ERROR
                                && d.message() != null
                                && d.message().toLowerCase().contains("void")),
                "Attendu l'erreur « Void methods cannot return a value », reçu : " + diags);
    }

    @Test
    void brokenCodeStillYieldsLocatedDiagnostics() throws Throwable {
        // Code à moitié tapé — la récupération d'erreurs d'ecj doit produire
        // des diagnostics LOCALISÉS (range non triviale), pas un crash.
        String source = "package demo;\npublic class Main {\n  void m() { if (x.\n";
        List<Diagnostic> diags = newEngine().diagnose("/demo/Main.java", source);
        assertFalse(diags.isEmpty(), "Du code cassé doit produire des diagnostics");
        for (Diagnostic d : diags) {
            assertNotNull(d.range());
            assertTrue(d.range().endOffset() > d.range().startOffset()
                            || d.range().endOffset() == d.range().startOffset() + 1,
                    "Range localisée attendue : " + d);
        }
    }

    @Test
    void crossFileResolutionFromSourceRoot() throws Throwable {
        // Une racine source contenant un Helper — la résolution doit le voir
        // SANS build préalable (pattern ModuleCompilationContext du projet de référence).
        Path root = Files.createTempDirectory("lspjava-src");
        Path pkgDir = root.resolve("demo");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Helper.java"), """
                package demo;

                public class Helper {
                    public static final int ANSWER = 42;
                }
                """);

        JdtCompilerEngine engine = new JdtCompilerEngine(
                List.of(root.toFile()),
                Collections.<File>emptyList(),
                "17");
        List<Diagnostic> diags = engine.diagnose(
                root.resolve("demo").resolve("Main.java").toString(),
                """
                package demo;

                public class Main {
                    public int answer() {
                        return Helper.ANSWER;
                    }
                }
                """);
        long errors = diags.stream().filter(d -> d.severity() == Severity.ERROR).count();
        assertTrue(errors == 0,
                "Helper doit résoudre depuis la racine source, reçu : " + diags);
    }

    @Test
    void jarClasspathResolvesTypes() throws Throwable {
        // Utilise le jar JUnit du classpath de test comme dépendance —
        // la résolution binaire doit voir org.junit.jupiter.api.Test.
        Path junitJar = Path.of(org.junit.jupiter.api.Test.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(junitJar.toString().endsWith(".jar"), "jar attendu : " + junitJar);

        JdtCompilerEngine engine = new JdtCompilerEngine(
                Collections.<File>emptyList(),
                List.of(junitJar.toFile()),
                "17");
        List<Diagnostic> diags = engine.diagnose("/demo/Main.java", """
                package demo;

                public class Main {
                    @org.junit.jupiter.api.Test
                    public void run() {
                    }
                }
                """);
        long errors = diags.stream().filter(d -> d.severity() == Severity.ERROR).count();
        assertTrue(errors == 0,
                "org.junit.jupiter.api.Test doit résoudre depuis le jar, reçu : " + diags);
    }
}
