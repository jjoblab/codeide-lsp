package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du renommage sûr ecJ ({@link EcjRenameEngine}).
 *
 * <p>Valide le cœur du renommage structurel : identification de cible par
 * bindings, collecte des références (déclaration + usages simples et
 * qualifiés + imports), non-renommage des homonymes, balayage
 * multi-fichiers des racines sources.</p>
 *
 * @author jo@Dev
 */
class EcjRenameEngineTest {

    @TempDir
    Path tmp;

    private EcjCompilerEngine newEngine(Path... sourceRoots) {
        List<File> roots = new java.util.ArrayList<>();
        for (Path p : sourceRoots) roots.add(p.toFile());
        return new EcjCompilerEngine(roots, Collections.<File>emptyList(), "17");
    }

    private Path rootWith(String relPath, String content) throws Exception {
        Path root = tmp.resolve("src" + System.nanoTime());
        Path f = root.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return root;
    }

    // ─── Cibles ────────────────────────────────────────────────────────

    @Test
    void targetAtOnMethodDeclaration() {
        String source = """
                package demo;

                public class Main {
                    public String greet(String name) {
                        return "Hello, " + name;
                    }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("greet"));
        assertNotNull(target);
        assertEquals("greet", target.oldName);
        assertEquals("method", target.kind);
        assertTrue(target.bindingKey.startsWith("method:demo.Main.greet("),
                "clé structurelle : " + target.bindingKey);
    }

    @Test
    void targetAtOnField() {
        String source = """
                package demo;

                public class Main {
                    private int counter = 0;
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("counter"));
        assertNotNull(target);
        assertEquals("field", target.kind);
        assertEquals("field:demo.Main.counter", target.bindingKey);
    }

    @Test
    void targetAtOnLocalIsFileLocal() {
        String source = """
                package demo;

                public class Main {
                    public void run() {
                        int total = 42;
                        System.out.println(total);
                    }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("total"));
        assertNotNull(target);
        assertTrue(target.fileLocal, "une locale est file-local");
    }

    @Test
    void targetAtOnType() {
        String source = """
                package demo;

                public class Main {
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("Main"));
        assertNotNull(target);
        assertTrue(target.isType);
        assertEquals("type:demo.Main", target.bindingKey);
    }

    @Test
    void targetAtOutsideNameIsNull() {
        String source = """
                package demo;

                public class Main {
                    public void run() { }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        assertNull(rename.targetAt("/demo/Main.java", source, 0));
    }

    // ─── Références ────────────────────────────────────────────────────

    @Test
    void referencesIncludeDeclarationAndUses() {
        String source = """
                package demo;

                public class Main {
                    private int counter = 0;

                    public int next() {
                        counter = counter + 1;
                        return counter;
                    }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("counter"));
        List<EcjRenameEngine.Occurrence> refs =
                rename.referencesIn("/demo/Main.java", source, target);
        // Déclaration + 3 usages (next) = 4.
        assertEquals(4, refs.size(), "déclaration + 3 usages : " + refs.size());
        // Chaque occurrence couvre exactement "counter".
        for (var r : refs) {
            assertEquals("counter", source.substring(r.start, r.end));
        }
    }

    @Test
    void homonymMethodOfDifferentClassIsNotRenamed() {
        // Deux classes avec une méthode get() homonyme — le renommage de
        // l'une ne doit PAS toucher les références de l'autre.
        String source = """
                package demo;

                public class Main {
                    public int get() { return 1; }

                    public int run(Other other) {
                        return get() + other.get();
                    }
                }

                class Other {
                    public int get() { return 2; }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        // Cible : le get() de Main (la DÉCLARATION dans Main).
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("get()"));
        assertNotNull(target);
        assertEquals("method:demo.Main.get()", target.bindingKey);
        List<EcjRenameEngine.Occurrence> refs =
                rename.referencesIn("/demo/Main.java", source, target);
        // Déclaration de Main.get + l'appel nu get() — PAS other.get().
        int otherCallStart = source.indexOf("other.get()") + "other.".length();
        boolean otherCallRetouched = refs.stream()
                .anyMatch(r -> r.start == otherCallStart);
        assertTrue(refs.size() == 2 && !otherCallRetouched,
                "déclaration + appel nu uniquement (other.get() exclu) : "
                        + refs.size() + " refs");
    }

    @Test
    void qualifiedFieldReferenceIsCaught() {
        String source = """
                package demo;

                public class Main {
                    public int run(Config config) {
                        return config.timeout;
                    }
                }

                class Config {
                    public int timeout = 30;
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("timeout"));
        assertNotNull(target);
        assertEquals("field:demo.Config.timeout", target.bindingKey);
        List<EcjRenameEngine.Occurrence> refs =
                rename.referencesIn("/demo/Main.java", source, target);
        assertEquals(2, refs.size(), "déclaration + usage qualifié : " + refs.size());
    }

    @Test
    void typeRenameIncludesDeclarationConstructorAndImports() {
        String source = """
                package demo;

                import demo.Helper;

                public class Main extends Helper {
                    public Main() { }
                }
                """;
        // La cible est Helper (importé) — l'import doit être retouché.
        // On résout avec une racine source qui contient Helper.
        try {
            Path root = rootWith("demo/Helper.java", """
                    package demo;

                    public class Helper {
                    }
                    """);
            Files.writeString(root.resolve("demo/Main.java"), source);

            EcjCompilerEngine engine = newEngine(root);
            EcjRenameEngine rename = new EcjRenameEngine(engine);
            String mainPath = root.resolve("demo/Main.java").toString();
            var target = rename.targetAt(mainPath, source,
                    source.indexOf("Helper"));
            assertNotNull(target, "Helper doit être une cible (type)");
            List<EcjRenameEngine.Occurrence> refs =
                    rename.referencesIn(mainPath, source, target);
            // import demo.Helper + extends Helper = 2 occurrences.
            assertTrue(refs.size() >= 2,
                    "import + extends attendus : " + refs.size());
            boolean importTouched = refs.stream().anyMatch(r -> {
                int lineStart = source.lastIndexOf('\n', r.start) + 1;
                String line = source.substring(lineStart,
                        Math.min(source.length(),
                                source.indexOf('\n', lineStart) < 0
                                        ? source.length()
                                        : source.indexOf('\n', lineStart)));
                return line.trim().startsWith("import");
            });
            assertTrue(importTouched, "l'import doit être retouché : " + refs);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void constructorDeclarationCountsForTypeRename() {
        String source = """
                package demo;

                public class Main {
                    public Main() { }
                }
                """;
        EcjRenameEngine rename = new EcjRenameEngine(newEngine());
        // Cible via le nom du CONSTRUCTEUR (cas spécial projet tiers de référence).
        var target = rename.targetAt("/demo/Main.java", source,
                source.indexOf("Main()"));
        assertNotNull(target, "le constructeur renomme la classe");
        assertTrue(target.isType);
        List<EcjRenameEngine.Occurrence> refs =
                rename.referencesIn("/demo/Main.java", source, target);
        // class Main + Main() = 2.
        assertEquals(2, refs.size(), "classe + constructeur : " + refs.size());
    }

    // ─── Multi-fichiers ────────────────────────────────────────────────

    @Test
    void projectRenameSweepsSourceRoots() throws Exception {
        Path root = rootWith("demo/User.java", """
                package demo;

                public class User {
                    public String name = "";

                    public String getName() {
                        return name;
                    }
                }
                """);
        Files.writeString(root.resolve("demo/Printer.java"), """
                package demo;

                public class Printer {
                    public String print(User user) {
                        return "Hi " + user.name;
                    }
                }
                """);
        // Un fichier SANS le nom — ne doit pas être résolu/retouché.
        Files.writeString(root.resolve("demo/Unrelated.java"), """
                package demo;

                public class Unrelated {
                    public String name() { return "x"; }
                }
                """);
        // Homonyme dans une AUTRE classe — ne doit PAS être retouché.
        Files.writeString(root.resolve("demo/Order.java"), """
                package demo;

                public class Order {
                    public String name = "order";
                }
                """);

        String userPath = root.resolve("demo/User.java").toString();
        String userSource = Files.readString(
                java.nio.file.Paths.get(userPath));

        EcjCompilerEngine engine = newEngine(root);
        EcjRenameEngine rename = new EcjRenameEngine(engine);
        var target = rename.targetAt(userPath, userSource,
                userSource.indexOf("name"));
        assertNotNull(target);
        assertEquals("field:demo.User.name", target.bindingKey);

        Map<String, List<EcjRenameEngine.Occurrence>> byFile =
                rename.renameProject(userPath, userSource, target);

        // User.java (déclaration + getter) et Printer.java (usage qualifié)
        // retouchés ; Order.name (autre classe) et Unrelated NON.
        assertNotNull(byFile.get(userPath));
        assertEquals(2, byFile.get(userPath).size(),
                "déclaration + return name : " + byFile.get(userPath).size());

        String printerPath = root.resolve("demo/Printer.java").toString();
        List<EcjRenameEngine.Occurrence> printerRefs = byFile.get(printerPath);
        assertNotNull(printerRefs, "Printer.java doit être retouché (user.name)");
        assertEquals(1, printerRefs.size());

        String orderPath = root.resolve("demo/Order.java").toString();
        assertNull(byFile.get(orderPath),
                "Order.name est un AUTRE champ — homonyme non retouché");
    }

    @Test
    void fileLocalRenameSkipsProjectSweep() throws Exception {
        Path root = rootWith("demo/Main.java", """
                package demo;

                public class Main {
                    public void run() {
                        int total = 42;
                        System.out.println(total);
                    }
                }
                """);
        String path = root.resolve("demo/Main.java").toString();
        String source = Files.readString(java.nio.file.Paths.get(path));

        EcjCompilerEngine engine = newEngine(root);
        EcjRenameEngine rename = new EcjRenameEngine(engine);
        var target = rename.targetAt(path, source, source.indexOf("total"));
        assertTrue(target.fileLocal);

        Map<String, List<EcjRenameEngine.Occurrence>> byFile =
                rename.renameProject(path, source, target);
        // Une seule entrée : le fichier courant.
        assertEquals(1, byFile.size(), "file-local → pas de balayage projet");
        assertEquals(2, byFile.get(path).size(), "déclaration + usage");
    }
}
