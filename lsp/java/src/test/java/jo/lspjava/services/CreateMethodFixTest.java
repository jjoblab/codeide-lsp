package jo.lspjava.services;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;
import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du quick-fix « Créer la méthode » (UNDEFINED_METHOD) — portage du
 * {@code CreateMethodFromUsageQuickFixProvider} du projet tiers de référence : stub
 * {@code private [static]} après la méthode englobante, types des paramètres
 * depuis les arguments STRUCTURÉS ecj, retour void/Object selon le contexte
 * d'appel, UnsupportedOperationException dans le corps.
 *
 * <p>Les diagnostics viennent du VRAI moteur ecj (diagnose) — plage et
 * arguments réels, pas synthétiques.</p>
 *
 * @author jo@Dev
 */
class CreateMethodFixTest {

    @TempDir
    static Path tmp;

    static CodeActionService service;
    static CompilationEnvironment env;

    @BeforeAll
    static void setUp() throws Exception {
        String androidJar = CodeActionServiceTest.findAndroidJar();
        assumeTrue(androidJar != null, "android.jar absent");
        Path src = Files.createDirectories(tmp.resolve("src"));
        env = new CompilationEnvironment(
                List.of(src.toFile()), List.of(new File(androidJar)), "17");
        service = new CodeActionService(env);
    }

    /** Diagnostique le fichier et retourne LE diagnostic UndefinedMethod. */
    private static Diagnostic undefinedMethodDiag(String path, String source)
            throws Throwable {
        for (Diagnostic d : env.jdt().diagnose(path, source)) {
            if (("ecj:" + IProblem.UndefinedMethod).equals(d.code())) {
                return d;
            }
        }
        return null;
    }

    private static List<CodeActionService.Edit> createMethodEdits(
            String path, String source) throws Throwable {
        Diagnostic d = undefinedMethodDiag(path, source);
        assertNotNull(d, "le diagnostic UndefinedMethod doit exister");
        for (CodeActionService.CodeAction a : service.actionsFor(d, source, path)) {
            if ("quickfix.create.method".equals(a.kind())) return a.edits();
        }
        return null;
    }

    private static String apply(String source, List<CodeActionService.Edit> edits) {
        List<CodeActionService.Edit> sorted = new java.util.ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder sb = new StringBuilder(source);
        for (CodeActionService.Edit e : sorted) {
            sb.replace(e.start(), e.end(), e.text());
        }
        return sb.toString();
    }

    @Test
    void statementCallCreatesVoidPrivateMethod() throws Throwable {
        Path file = tmp.resolve("src/demo/Main.java");
        Files.createDirectories(file.getParent());
        String source = """
                package demo;

                public class Main {
                    void run() {
                        compute(1, "x");
                    }
                }
                """;
        Files.writeString(file, source);
        var edits = createMethodEdits(file.toString(), source);
        assertNotNull(edits, "quick-fix Create method attendu");
        String result = apply(source, edits);
        // void : l'appel EST l'instruction ; private ; body TODO.
        assertTrue(result.contains("private void compute(int arg0, String arg1) {"),
                "stub attendu : " + result);
        assertTrue(result.contains(
                "throw new UnsupportedOperationException(\"TODO: compute\");"),
                "corps TODO attendu : " + result);
        // Le stub est inséré APRÈS la méthode englobante run().
        int runEnd = result.indexOf('}', result.indexOf("compute(1, \"x\")"));
        assertTrue(result.indexOf("private void compute") > runEnd,
                "insertion après run() : " + result);
        // Le fichier corrigé compile : plus d'UndefinedMethod.
        assertEquals(0, countUndefinedMethod(file.toString(), result));
    }

    @Test
    void assignmentCallCreatesObjectReturningMethod() throws Throwable {
        Path file = tmp.resolve("src/demo/Assign.java");
        Files.createDirectories(file.getParent());
        String source = """
                package demo;

                public class Assign {
                    void run() {
                        int r = compute(1);
                    }
                }
                """;
        Files.writeString(file, source);
        var edits = createMethodEdits(file.toString(), source);
        assertNotNull(edits);
        String result = apply(source, edits);
        // L'appel est une expression (affectation) → Object (projet tiers de référence).
        assertTrue(result.contains("private Object compute(int arg0) {"),
                "retour Object attendu : " + result);
    }

    @Test
    void staticCallSiteCreatesStaticMethod() throws Throwable {
        Path file = tmp.resolve("src/demo/Stat.java");
        Files.createDirectories(file.getParent());
        String source = """
                package demo;

                public class Stat {
                    static void run() {
                        compute(1);
                    }
                }
                """;
        Files.writeString(file, source);
        var edits = createMethodEdits(file.toString(), source);
        assertNotNull(edits);
        String result = apply(source, edits);
        assertTrue(result.contains("private static void compute(int arg0) {"),
                "private static attendu : " + result);
    }

    @Test
    void qualifiedCallOnOtherTypeNotFixed() throws Throwable {
        Path file = tmp.resolve("src/demo/Other.java");
        Files.createDirectories(file.getParent());
        // L'appel cible java.lang.String (owner ≠ type englobant) — projet tiers de référence
        // ne crée PAS la méthode dans un autre fichier/type.
        String source = """
                package demo;

                public class Other {
                    void run() {
                        "abc".noSuchMethod(1);
                    }
                }
                """;
        Files.writeString(file, source);
        Diagnostic d = undefinedMethodDiag(file.toString(), source);
        if (d != null) {
            assertNull(createMethodEdits(file.toString(), source),
                    "pas de fix pour un appel sur un AUTRE type");
        }
    }

    @Test
    void fqnParamsShortenedWithImportEdit() throws Throwable {
        Path file = tmp.resolve("src/demo/Fqn.java");
        Files.createDirectories(file.getParent());
        String source = """
                package demo;

                public class Fqn {
                    void run(java.util.List<String> items) {
                        process(items);
                    }
                }
                """;
        Files.writeString(file, source);
        var edits = createMethodEdits(file.toString(), source);
        assertNotNull(edits);
        String result = apply(source, edits);
        // Le paramètre FQN est RACCOURCI (List<String>) + import ajouté.
        assertTrue(result.contains("process(List<String> arg0)"),
                "paramètre raccourci attendu : " + result);
        assertTrue(result.contains("import java.util.List;"),
                "import attendu : " + result);
    }

    private static int countUndefinedMethod(String path, String source)
            throws Throwable {
        int n = 0;
        for (Diagnostic d : env.jdt().diagnose(path, source)) {
            if (("ecj:" + IProblem.UndefinedMethod).equals(d.code())) n++;
        }
        return n;
    }
}
