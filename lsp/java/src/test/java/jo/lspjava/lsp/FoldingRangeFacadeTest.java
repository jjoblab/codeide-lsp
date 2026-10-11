package jo.lspjava.lsp;

import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests BOUT-EN-BOUT des handlers à travers la façade LSP réelle
 * ({@link JavaTextDocumentService}) : {@code foldingRange} (imports
 * repliés par défaut + intérieur des accolades + commentaires) et les hints
 * de type {@code var} par bindings dans {@code inlayHint}.
 *
 * @author jo@Dev
 */
class FoldingRangeFacadeTest {

    @TempDir
    static Path tmp;

    static JavaTextDocumentService service;
    static Path srcRoot;

    static String findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar",
                System.getenv("ANDROID_SDK_ROOT") + "/platforms/android-34/android.jar"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        File platforms = new File("/home/z/tools/android-sdk/platforms");
        File[] dirs = platforms.isDirectory() ? platforms.listFiles() : null;
        if (dirs != null) {
            for (File p : dirs) {
                File jar = new File(p, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    @BeforeAll
    static void setUp() throws Exception {
        String androidJar = findAndroidJar();
        assumeTrue(androidJar != null, "android.jar absent");
        srcRoot = Files.createDirectories(tmp.resolve("src/demo"));
        CompilationEnvironment env = new CompilationEnvironment(
                List.of(srcRoot.getParent().toFile()),
                List.of(new File(androidJar)), "17");
        service = new JavaTextDocumentService(env, null);
    }

    private static String open(String fileName, String source) {
        Path file = srcRoot.resolve(fileName);
        try {
            Files.writeString(file, source);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String uri = "file://" + file;
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, source)));
        return uri;
    }

    private static List<org.eclipse.lsp4j.FoldingRange> foldingRanges(String uri) {
        try {
            return service.foldingRange(new FoldingRangeRequestParams(
                    new TextDocumentIdentifier(uri))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── foldingRange ───────────────────────────────────────────────────

    @Test
    void foldingRange_importsBlockCommentAndBodies() throws Exception {
        String source = """
                package demo;

                import java.util.List;
                import java.util.Map;

                /**
                 * Doc
                 */
                public class Main {
                    void run() {
                        System.out.println("x");
                    }
                }
                """;
        String uri = open("Main.java", source);
        var ranges = foldingRanges(uri);
        // imports (replié par défaut) + javadoc + corps de classe + corps
        // de méthode.
        assertTrue(ranges.size() >= 4, "au moins 4 folds : " + ranges);

        org.eclipse.lsp4j.FoldingRange imports = rangeOfKind(ranges, "imports");
        assertNotNull(imports, "fold imports attendue");
        assertEquals(2, imports.getStartLine());
        assertEquals(3, imports.getEndLine());
        assertEquals("import ...", imports.getCollapsedText());

        org.eclipse.lsp4j.FoldingRange comment = rangeOfKind(ranges, "comment");
        assertNotNull(comment, "fold javadoc attendue");
        assertEquals(5, comment.getStartLine());

        // Corps de méthode : ligne « void run() { » = 9, la } fermante = 11.
        org.eclipse.lsp4j.FoldingRange method = null;
        for (org.eclipse.lsp4j.FoldingRange r : ranges) {
            if ("block".equals(r.getKind()) && r.getStartLine() == 9) {
                method = r;
            }
        }
        assertNotNull(method, "fold du corps de run() : " + ranges);
        assertEquals(11, method.getEndLine());
        // Précision CARACTÈRE : la fold démarre JUSTE APRÈS le « { »
        // (col 15 sur «    void run() { ») et se termine SUR la « } » (col 4).
        assertEquals(Integer.valueOf(16), method.getStartCharacter());
        assertEquals(Integer.valueOf(4), method.getEndCharacter());
    }

    private static org.eclipse.lsp4j.FoldingRange rangeOfKind(
            List<org.eclipse.lsp4j.FoldingRange> ranges, String kind) {
        for (org.eclipse.lsp4j.FoldingRange r : ranges) {
            if (kind.equals(r.getKind())) return r;
        }
        return null;
    }

    // ── inlayHint : var par bindings ───────────────────────────────────

    @Test
    void inlayHint_varTypeFromBindings() throws Exception {
        String source = """
                package demo;

                public class Vars {
                    void run() {
                        var s = String.valueOf(1);
                    }
                }
                """;
        String uri = open("Vars.java", source);
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setRange(new Range(new Position(0, 0),
                new Position(20, 0)));
        var hints = service.inlayHint(params).get();
        // Le hint « : String » sur le var — l'inférence d'un appel de
        // méthode passe par les bindings ecj.
        org.eclipse.lsp4j.InlayHint varHint = null;
        for (org.eclipse.lsp4j.InlayHint h : hints) {
            if (h.getLabel() != null && h.getLabel().isLeft()
                    && ": String".equals(h.getLabel().getLeft())) {
                varHint = h;
            }
        }
        assertNotNull(varHint, "hint « : String » attendu : " + hints);
        assertEquals(org.eclipse.lsp4j.InlayHintKind.Type, varHint.getKind());
        // Position : fin du nom « s » (ligne 4 : «        var s = … », le
        // nom en col 12 → le hint s'ancre col 13, avant le « = »).
        assertEquals(4, varHint.getPosition().getLine());
        assertEquals(13, varHint.getPosition().getCharacter());
    }
}
