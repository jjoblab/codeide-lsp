package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des inlay hints paramètres ({@link JdtInlayHints}) et de l'aide de
 * signature ({@link JdtSignatureHelp}) — bindings ecj.
 *
 * @author jo@Dev
 */
class JdtInlayAndSignatureTest {

    @TempDir
    Path tmp;

    private JdtCompilerEngine newEngine(Path... roots) {
        List<File> files = new java.util.ArrayList<>();
        for (Path p : roots) files.add(p.toFile());
        return new JdtCompilerEngine(files, Collections.<File>emptyList(), "17");
    }

    private Path rootWith(String rel, String content) throws Exception {
        Path root = tmp.resolve("src" + System.nanoTime());
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return root;
    }

    // ─── Inlay hints (noms de paramètres) ──────────────────────────────

    @Test
    void hintsForLocalMethodCall() throws Exception {
        String source = """
                package demo;

                public class Main {
                    public String greet(String name, int times) {
                        return name + times;
                    }

                    public void run() {
                        greet("world", 3);
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var hints = new JdtInlayHints(engine).hintsFor("/demo/Main.java", source);
        // Un hint par argument : name: avant "world", times: avant 3.
        assertEquals(2, hints.size(), "deux hints attendus : " + hints);
        assertEquals("name:", hints.get(0).label);
        assertTrue(source.startsWith("\"world\"", hints.get(0).offset),
                "le hint précède l'argument : " + hints.get(0).offset);
        assertEquals("times:", hints.get(1).label);
    }

    @Test
    void hintsForMethodDeclaredInAnotherFile() throws Exception {
        Path root = rootWith("demo/Helper.java", """
                package demo;

                public class Helper {
                    public int add(int first, int second) {
                        return first + second;
                    }
                }
                """);
        Files.writeString(root.resolve("demo/Main.java"), """
                package demo;

                public class Main {
                    public void run() {
                        Helper h = new Helper();
                        h.add(10, 20);
                    }
                }
                """);
        String mainPath = root.resolve("demo/Main.java").toString();
        String source = Files.readString(java.nio.file.Paths.get(mainPath));

        JdtCompilerEngine engine = newEngine(root);
        var hints = new JdtInlayHints(engine).hintsFor(mainPath, source);
        // La déclaration vit dans Helper.java — les noms viennent de là.
        assertEquals(2, hints.size(), "deux hints (first:, second:) : " + hints);
        assertEquals("first:", hints.get(0).label);
        assertEquals("second:", hints.get(1).label);
    }

    @Test
    void noHintsForBinaryMethod() throws Exception {
        // String.substring(int, int) est BINAIRE (jrt) — pas de noms
        // disponibles, donc aucun hint (jamais de faux noms).
        String source = """
                package demo;

                public class Main {
                    public String run(String s) {
                        return s.substring(1, 4);
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var hints = new JdtInlayHints(engine).hintsFor("/demo/Main.java", source);
        assertTrue(hints.isEmpty(),
                "méthode binaire → aucun hint : " + hints);
    }

    @Test
    void overloadResolutionPicksRightParameterNames() throws Exception {
        String source = """
                package demo;

                public class Main {
                    public int area(int size) { return size * size; }
                    public int area(int width, int height) { return width * height; }

                    public void run() {
                        area(2, 3);
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var hints = new JdtInlayHints(engine).hintsFor("/demo/Main.java", source);
        // L'appel à 2 arguments résout vers area(int, int) → width:, height:.
        assertEquals(2, hints.size());
        assertEquals("width:", hints.get(0).label);
        assertEquals("height:", hints.get(1).label);
    }

    // ─── Signature help ────────────────────────────────────────────────

    @Test
    void signatureHelpListsBinaryOverloads() {
        // StringBuilder.append a ~15 surcharges — le helper doit les
        // énumérer depuis les bindings (chemin binaire, jrt).
        String source = """
                package demo;

                public class Main {
                    public void run(StringBuilder sb) {
                        sb.append("hello");
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var helper = new JdtSignatureHelp(engine);
        int caret = source.indexOf("\"hello\"");
        var result = helper.signatureHelpAt("/demo/Main.java", source, caret);
        assertNotNull(result, "l'appel doit être trouvé");
        assertTrue(result.signatures.size() >= 5,
                "append a de nombreuses surcharges : " + result.signatures.size());
        assertTrue(result.signatures.stream().anyMatch(s ->
                        s.label.contains(" append(")),
                "les labels portent le sélecteur append( : "
                        + result.signatures.get(0).label);
        assertEquals(0, result.activeParameter,
                "le caret est dans le premier argument");
    }

    @Test
    void signatureHelpCountsActiveParameter() {
        String source = """
                package demo;

                public class Main {
                    public void run(StringBuilder sb) {
                        sb.append("a").append('b');
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var helper = new JdtSignatureHelp(engine);
        // Caret dans le 2e appel (append('b')), avant l'argument.
        int caret = source.indexOf("'b'");
        var result = helper.signatureHelpAt("/demo/Main.java", source, caret);
        assertNotNull(result);
        assertEquals(0, result.activeParameter);
    }

    @Test
    void signatureHelpSourceParameterNames() throws Exception {
        Path root = rootWith("demo/Helper.java", """
                package demo;

                public class Helper {
                    public int add(int first, int second) {
                        return first + second;
                    }
                }
                """);
        Files.writeString(root.resolve("demo/Main.java"), """
                package demo;

                public class Main {
                    public void run() {
                        Helper h = new Helper();
                        h.add(1, 2);
                    }
                }
                """);
        String mainPath = root.resolve("demo/Main.java").toString();
        String source = Files.readString(java.nio.file.Paths.get(mainPath));

        JdtCompilerEngine engine = newEngine(root);
        var helper = new JdtSignatureHelp(engine);
        int caret = source.indexOf("1, 2");
        var result = helper.signatureHelpAt(mainPath, source, caret);
        assertNotNull(result);
        assertEquals(1, result.signatures.size());
        var sig = result.signatures.get(0);
        assertTrue(sig.label.contains("first") && sig.label.contains("second"),
                "noms de la déclaration source : " + sig.label);
        assertEquals(List.of("first", "second"), sig.parameterNames);
    }

    @Test
    void signatureHelpNullOutsideCall() {
        String source = """
                package demo;

                public class Main {
                    public void run() {
                        int x = 1;
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        var helper = new JdtSignatureHelp(engine);
        assertNull(helper.signatureHelpAt("/demo/Main.java", source,
                source.indexOf("int x")));
    }

    @Test
    void activeParameterCountsCommasNotInStrings() {
        // countActiveParameter : virgule DANS une chaîne ≠ séparateur.
        String source = "run(a, \"x,y\", c);";
        // Caret après "x,y" (3e argument, index 2).
        int caret = source.indexOf(", c");
        assertEquals(1, JdtSignatureHelp.countActiveParameter(source, 0, caret),
                "virgule dans la chaîne ignorée — index 1");
    }
}
