package jo.lspjava.lsp;

import jo.lspjava.api.NavigationTarget;
import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests BOUT-EN-BOUT à travers la façade LSP réelle
 * ({@link JavaTextDocumentService}) : {@code textDocument/implementation}
 * (GO TO « Implementations » — les héritiers DIRECTS du type en contexte,
 * MULTI-FICHIERS via le balayage des racines sources) et
 * {@code textDocument/superDefinition} (GO TO « Super » — le membre
 * outrepassé dans chaque supertype, sinon les supertypes DIRECTS).
 *
 * <p>Fixtures multi-fichiers sous une racine source unique ({@code tmp/src})
 * : Base/ImplA/ImplB (héritage classe), Greeter/EnGreeter (interface), Nest
 * (sous-classe imbriquée même fichier), Solo (sans descendance), MyThread
 * (supertype BINAIRE java.lang.Thread — aucune source navigable).</p>
 *
 * @author jo@Dev
 */
class NavigationFacadeTest {

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
        srcRoot = Files.createDirectories(tmp.resolve("src"));
        CompilationEnvironment env = new CompilationEnvironment(
                List.of(srcRoot.toFile()),
                List.of(new File(androidJar)), "17");
        service = new JavaTextDocumentService(env, null);
    }

    private static String open(String relPath, String source) {
        Path file = srcRoot.resolve(relPath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String uri = "file://" + file.toAbsolutePath();
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, source)));
        return uri;
    }

    private static int offsetOf(String source, String needle, int bias) {
        return source.indexOf(needle) + bias;
    }

    private static Position positionOf(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }

    private static List<Location> implementation(String uri, String source, int offset) {
        try {
            ImplementationParams params = new ImplementationParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    service.implementation(params).get();
            if (result == null) return List.of();
            return List.copyOf(result.getLeft());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<NavigationTarget> superDefinition(String uri, String source, int offset) {
        try {
            TextDocumentPositionParams params = new TextDocumentPositionParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            List<NavigationTarget> result = service.superDefinition(params).get();
            return result == null ? List.of() : result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Fixtures
    // ══════════════════════════════════════════════════════════════

    static final String BASE = """
            package demo;

            public class Base {
                int counter;

                void run() {
                    counter++;
                }
            }
            """;

    static final String IMPL_A = """
            package demo;

            public class ImplA extends Base {
                int tag;

                @Override
                void run() {
                    counter = 1;
                }
            }
            """;

    static final String IMPL_B = """
            package demo;

            public class ImplB extends Base {
                @Override
                void run() {
                    counter = 2;
                }
            }
            """;

    static final String MAIN = """
            package demo;

            public class Main {
                void use(Base b) {
                    b.run();
                }
            }
            """;

    static final String GREETER = """
            package demo;

            public interface Greeter {
                void greet();
            }
            """;

    static final String EN_GREETER = """
            package demo;

            public class EnGreeter implements Greeter {
                public void greet() {
                }
            }
            """;

    static final String NEST = """
            package demo;

            public class Nest {
                class Inner extends Nest {
                }

                int tag;
            }
            """;

    static final String SOLO = """
            package demo;

            public class Solo {
                int tag;
            }
            """;

    static final String MY_THREAD = """
            package demo;

            public class MyThread extends Thread {
                int tag;
            }
            """;

    // ══════════════════════════════════════════════════════════════
    // textDocument/implementation
    // ══════════════════════════════════════════════════════════════

    @Test
    void implementation_typeReference_findsCrossFileSubtypes() {
        String base = open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        open("demo/ImplB.java", IMPL_B);
        String main = open("demo/Main.java", MAIN);
        open("demo/Greeter.java", GREETER);
        open("demo/EnGreeter.java", EN_GREETER);
        open("demo/Nest.java", NEST);
        open("demo/Solo.java", SOLO);
        open("demo/MyThread.java", MY_THREAD);

        // Caret sur « Base » (référence de type) dans Main.use(Base b).
        int offset = offsetOf(MAIN, "Base b)", 0);
        List<Location> targets = implementation(main, MAIN, offset);
        assertEquals(2, targets.size(), "ImplA + ImplB : " + targets);

        // Les deux cibles vivent dans LEURS fichiers, sur le NOM de classe.
        String implASource = IMPL_A;
        Position expectedA = positionOf(implASource,
                offsetOf(implASource, "class ImplA", "class ".length()));
        String implBSource = IMPL_B;
        Position expectedB = positionOf(implBSource,
                offsetOf(implBSource, "class ImplB", "class ".length()));
        boolean foundA = false;
        boolean foundB = false;
        for (Location loc : targets) {
            if (loc.getUri().endsWith("ImplA.java")) {
                assertEquals(expectedA.getLine(), loc.getRange().getStart().getLine(),
                        "ligne du nom ImplA");
                assertEquals(expectedA.getCharacter(), loc.getRange().getStart().getCharacter(),
                        "colonne du nom ImplA");
                foundA = true;
            } else if (loc.getUri().endsWith("ImplB.java")) {
                assertEquals(expectedB.getLine(), loc.getRange().getStart().getLine(),
                        "ligne du nom ImplB");
                assertEquals(expectedB.getCharacter(), loc.getRange().getStart().getCharacter(),
                        "colonne du nom ImplB");
                foundB = true;
            }
        }
        assertTrue(foundA, "cible ImplA.java présente");
        assertTrue(foundB, "cible ImplB.java présente");
    }

    @Test
    void implementation_insideClass_findsSubtypes() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        open("demo/ImplB.java", IMPL_B);
        String base = open("demo/Base.java", BASE);

        // Caret sur le champ « counter » DANS Base → contexte = classe
        // englobante Base (parité contextTypeFqn du projet tiers de référence).
        int offset = offsetOf(BASE, "counter;", 0);
        List<Location> targets = implementation(base, BASE, offset);
        assertEquals(2, targets.size(), "les sous-classes de la classe englobante : "
                + targets);
    }

    @Test
    void implementation_noSubclass_yieldsEmpty() {
        String solo = open("demo/Solo.java", SOLO);
        // Caret sur le champ « tag » → contexte Solo, aucune sous-classe.
        int offset = offsetOf(SOLO, "tag;", 0);
        List<Location> targets = implementation(solo, SOLO, offset);
        assertTrue(targets.isEmpty(), "aucun héritier : " + targets);
    }

    @Test
    void implementation_interfaceImplementors() {
        open("demo/Greeter.java", GREETER);
        String en = open("demo/EnGreeter.java", EN_GREETER);
        // Caret sur « Greeter » dans la clause implements.
        int offset = offsetOf(EN_GREETER, "implements Greeter", "implements ".length());
        List<Location> targets = implementation(en, EN_GREETER, offset);
        assertEquals(1, targets.size(), "EnGreeter implémente Greeter : " + targets);
        assertTrue(targets.get(0).getUri().endsWith("EnGreeter.java"),
                "la cible est la classe implémentante");
        Position expected = positionOf(EN_GREETER,
                offsetOf(EN_GREETER, "class EnGreeter", "class ".length()));
        assertEquals(expected.getCharacter(),
                targets.get(0).getRange().getStart().getCharacter(),
                "colonne du nom EnGreeter");
    }

    @Test
    void implementation_nestedSubclass_sameFile() {
        String nest = open("demo/Nest.java", NEST);
        // Caret sur le champ « tag » → contexte Nest → Inner (imbriquée,
        // MÊME fichier) est une héritière directe.
        int offset = offsetOf(NEST, "tag;", 0);
        List<Location> targets = implementation(nest, NEST, offset);
        assertEquals(1, targets.size(), "la sous-classe imbriquée : " + targets);
        Position expected = positionOf(NEST,
                offsetOf(NEST, "class Inner", "class ".length()));
        assertEquals(expected.getCharacter(),
                targets.get(0).getRange().getStart().getCharacter(),
                "colonne du nom Inner");
    }

    // ══════════════════════════════════════════════════════════════
    // textDocument/superDefinition
    // ══════════════════════════════════════════════════════════════

    @Test
    void super_onOverridingMethod_findsBaseMethod() {
        open("demo/Base.java", BASE);
        String implA = open("demo/ImplA.java", IMPL_A);

        // Caret sur « run » (la déclaration OUTREPASSANTE dans ImplA).
        int offset = offsetOf(IMPL_A, "void run()", "void ".length());
        List<NavigationTarget> targets = superDefinition(implA, IMPL_A, offset);
        assertEquals(1, targets.size(), "run() de Base : " + targets);
        NavigationTarget hit = targets.get(0);
        assertTrue(hit.path.endsWith("Base.java"), "cible dans Base.java : " + hit.path);
        assertEquals("run", BASE.substring(hit.start, hit.end),
                "la plage désigne le NOM run dans Base.java");
        assertEquals("run  ·  Base", hit.label, "libellé picker projet tiers de référence");
    }

    @Test
    void super_onTypeInsideImpl_findsBaseClass() {
        open("demo/Base.java", BASE);
        String implA = open("demo/ImplA.java", IMPL_A);

        // Caret sur le champ « tag » (pas un membre outrepassant) → les
        // supertypes DIRECTS du type en contexte (ImplA → Base).
        int offset = offsetOf(IMPL_A, "tag;", 0);
        List<NavigationTarget> targets = superDefinition(implA, IMPL_A, offset);
        assertEquals(1, targets.size(), "le supertype direct : " + targets);
        NavigationTarget hit = targets.get(0);
        assertTrue(hit.path.endsWith("Base.java"), "cible dans Base.java");
        assertEquals("Base", BASE.substring(hit.start, hit.end),
                "la plage désigne le NOM Base");
        assertEquals("Base  ·  demo", hit.label, "libellé picker « Simple  ·  pkg »");
    }

    @Test
    void super_binarySupertype_yieldsEmpty() {
        String myThread = open("demo/MyThread.java", MY_THREAD);
        // Caret sur « tag » → supertype direct = java.lang.Thread (BINAIRE,
        // aucune source projet) → aucune cible.
        int offset = offsetOf(MY_THREAD, "tag;", 0);
        List<NavigationTarget> targets = superDefinition(myThread, MY_THREAD, offset);
        assertTrue(targets.isEmpty(), "un supertype binaire n'a pas de source : " + targets);
    }

    @Test
    void super_overriddenViaInvocation_findsBaseMethod() {
        open("demo/Base.java", BASE);
        open("demo/ImplA.java", IMPL_A);
        open("demo/ImplB.java", IMPL_B);
        String main = open("demo/Main.java", MAIN);

        // Caret sur « run » dans l'INVOCATION b.run() — le binding est la
        // méthode la plus spécifique (héritée de Base) : le chemin membre
        // ne trouve rien AU-DESSUS de Base → retombée sur les supertypes
        // du type EN CONTEXTE (la classe englobante Main — Object) → vide.
        int offset = offsetOf(MAIN, "b.run()", 2);
        List<NavigationTarget> targets = superDefinition(main, MAIN, offset);
        assertTrue(targets.isEmpty(),
                "aucun supertype SOURCE au-dessus de Main : " + targets);
    }

    @Test
    void implementation_interfaceMethodReference_findsImplementor() {
        open("demo/Greeter.java", GREETER);
        open("demo/EnGreeter.java", EN_GREETER);
        // Le caret sur « Greeter » dans SA PROPRE déclaration → les
        // implémentations de l'interface.
        String greeter = open("demo/Greeter.java", GREETER);
        int offset = offsetOf(GREETER, "interface Greeter", "interface ".length());
        List<Location> targets = implementation(greeter, GREETER, offset);
        assertEquals(1, targets.size(), "EnGreeter : " + targets);
        assertTrue(targets.get(0).getUri().endsWith("EnGreeter.java"));
    }
}
