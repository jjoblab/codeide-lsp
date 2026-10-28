package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.api.CompletionItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intelligence contextuelle de la complétion (v1.16.0) — la complétion
 * sait OÙ elle est : la position grammaticale (extends / implements /
 * throws / catch / new / annotation) filtre le légal, la position de
 * membre propose des stubs d'override, la position de nom de déclaration
 * suggère des noms, les labels d'un switch sur enum proposent les
 * constantes.
 *
 * @author jo@Dev
 */
class CompletionContextIntelligenceTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        Path src = Files.createDirectories(
                tmp.resolve("app/src/main/java/demo"));
        main = src.resolve("Main.java");
        engine = new EcjCompilerEngine(
                List.of(src.toFile()), List.of(androidJar), "17");
    }

    // ═══ Position grammaticale : extends / implements ═══

    @Test
    void extendsOfAClassExcludesInterfaces() throws Exception {
        // `class C extends Runn|` — Runnable est une INTERFACE : illégal
        // après extends d'une classe → filtré.
        String typed = """
                package demo;

                public class Main extends Runn {
                }
                """;
        int caret = typed.indexOf("Runn") + "Runn".length();
        List<String> labels = labels(complete(typed, caret));
        assertFalse(labels.contains("Runnable"),
                "une interface n'est pas légale après extends d'une classe : "
                        + labels);
    }

    @Test
    void implementsProposesInterfacesOnly() throws Exception {
        // `class C implements Runn|` — Runnable est légal ici.
        String typed = """
                package demo;

                public class Main implements Runn {
                }
                """;
        int caret = typed.indexOf("Runn") + "Runn".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("Runnable"),
                "Runnable est légal après implements : " + labels);
    }

    // ═══ Position grammaticale : throws / catch ═══

    @Test
    void throwsClauseProposesThrowablesOnly() throws Exception {
        // `void m() throws Ex|` — Exception oui (Throwable), Executor non.
        String typed = """
                package demo;

                public class Main {
                    void m() throws Ex {
                    }
                }
                """;
        int caret = typed.indexOf("throws Ex") + "throws Ex".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("Exception"),
                "Exception est légale dans une clause throws : " + labels);
        assertFalse(labels.contains("Executor"),
                "Executor n'est pas un Throwable : " + labels);
    }

    @Test
    void catchClauseProposesThrowablesOnly() throws Exception {
        // `catch (Ex| e)` — même filtre que throws.
        String typed = """
                package demo;

                public class Main {
                    void m() {
                        try {
                            int x = 1;
                        } catch (Ex e) {
                        }
                    }
                }
                """;
        int caret = typed.indexOf("catch (Ex") + "catch (Ex".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("Exception"),
                "Exception est légale dans un catch : " + labels);
        assertFalse(labels.contains("Executor"),
                "Executor n'est pas un Throwable : " + labels);
    }

    // ═══ Position grammaticale : new / annotation ═══

    @Test
    void newExcludesInterfacesAndAbstractClasses() throws Exception {
        // `new ArrayLi|` — ArrayList concret oui ; la forme `new Runn|`
        // ne doit PAS proposer l'interface Runnable.
        String typed = """
                package demo;

                public class Main {
                    void m() {
                        Object x = new ArrayLi();
                    }
                }
                """;
        int caret = typed.indexOf("ArrayLi") + "ArrayLi".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("ArrayList"),
                "ArrayList est instanciable : " + labels);
        assertFalse(labels.contains("AbstractList"),
                "AbstractList est abstrait — pas instanciable : " + labels);

        String typedIface = typed.replace("ArrayLi", "Runn");
        int caretIface = typedIface.indexOf("Runn") + "Runn".length();
        List<String> labelsIface = labels(complete(typedIface, caretIface));
        assertFalse(labelsIface.contains("Runnable"),
                "une interface n'est pas instanciable : " + labelsIface);
    }

    @Test
    void annotationPositionProposesAnnotationsOnly() throws Exception {
        // `@Func|` — FunctionalInterface oui, l'interface Function non.
        String typed = """
                package demo;

                @Func
                public class Main {
                }
                """;
        int caret = typed.indexOf("@Func") + "@Func".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("FunctionalInterface"),
                "FunctionalInterface est une annotation : " + labels);
        assertFalse(labels.contains("Function"),
                "Function est une interface, pas une annotation : " + labels);
    }

    // ═══ Position de membre : stubs d'override ═══

    @Test
    void memberPositionProposesOverrideStubs() throws Exception {
        // `toStr|` dans le corps d'une classe → stub toString() complet.
        String typed = """
                package demo;

                public class Main {
                    toStr
                }
                """;
        int caret = typed.indexOf("toStr") + "toStr".length();
        List<CompletionItem> items = complete(typed, caret);
        assertNotNull(items);
        CompletionItem toString = null;
        for (CompletionItem it : items) {
            if (it.label().startsWith("toString(")) {
                toString = it;
                break;
            }
        }
        assertNotNull(toString,
                "un stub toString() est attendu : " + labels(items));
        assertTrue(toString.isSnippet(), "le stub est un snippet");
        String text = toString.insertText();
        assertTrue(text.contains("@Override"), "avec @Override : " + text);
        assertTrue(text.contains("public String toString()"),
                "signature complète : " + text);
        assertTrue(text.contains("super.toString();"),
                "corps super par défaut : " + text);
        assertTrue(text.contains("$0"), "arrêt final du snippet : " + text);
    }

    @Test
    void overrideStubOfInterfaceMethodThrowsInsteadOfSuper() throws Exception {
        // Une classe qui implémente une interface : le stub d'une méthode
        // d'interface n'appelle PAS super (illégal) — il lève.
        String typed = """
                package demo;

                public class Main implements Runnable {
                    ru
                }
                """;
        int caret = typed.indexOf("ru") + "ru".length();
        List<CompletionItem> items = complete(typed, caret);
        CompletionItem run = null;
        for (CompletionItem it : items) {
            if (it.label().startsWith("run(")) {
                run = it;
                break;
            }
        }
        assertNotNull(run, "stub run() attendu : " + labels(items));
        assertTrue(run.insertText().contains(
                        "throw new UnsupportedOperationException();"),
                "corps d'interface = throw, pas super : " + run.insertText());
    }

    // ═══ Position de nom de déclaration : suggestions de noms ═══

    @Test
    void declarationNameSuggestsNamesFromType() throws Exception {
        // `HttpClient ` (locale, nom vide) → httpClient, client, h.
        // Chaîne explicite : un bloc de texte élimine les espaces
        // de fin de ligne, et le caret DOIT être après l'espace
        // (le marqueur se retrouve en position de NOM).
        String typed = "package demo;\n\npublic class Main {\n"
                + "    void m() {\n        HttpClient \n    }\n}\n";
        int caret = typed.indexOf("HttpClient ") + "HttpClient ".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("httpClient"),
                "suffixe complet lowerFirst attendu : " + labels);
        assertTrue(labels.contains("client"),
                "suffixe court attendu : " + labels);
    }

    @Test
    void fieldDeclarationNameSuggestsNamesFromType() throws Exception {
        // `private Button ` au niveau classe → button, b. Chaîne
        // explicite pour l'espace final (cf. test précédent).
        String typed = "package demo;\n\npublic class Main {\n"
                + "    private Button \n}\n";
        int caret = typed.indexOf("Button ") + "Button ".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("button"),
                "button attendu pour un champ de type Button : " + labels);
    }

    // ═══ Labels de switch sur enum ═══

    @Test
    void caseLabelProposesEnumConstants() throws Exception {
        // `case RE|` dans un switch sur Color → RED, pas les membres
        // génériques seulement.
        String typed = """
                package demo;

                public class Main {
                    enum Color { RED, GREEN, BLUE }

                    String name(Color c) {
                        switch (c) {
                            case RE:
                                return "r";
                        }
                        return "";
                    }
                }
                """;
        int caret = typed.indexOf("case RE") + "case RE".length();
        List<CompletionItem> items = complete(typed, caret);
        List<String> labels = labels(items);
        assertTrue(labels.contains("RED"),
                "la constante RED de l'enum du switch est attendue : "
                        + labels);
        boolean redIsEnum = false;
        for (CompletionItem it : items) {
            if ("RED".equals(it.label()) && it.kind() == CompletionItem.Kind.ENUM) {
                redIsEnum = true;
            }
        }
        assertTrue(redIsEnum, "RED voyage avec le kind ENUM : " + labels);
    }

    // ═══ Valeurs d'enum dans les annotations ═══

    @Test
    void annotationEnumValueProposesConstants() throws Exception {
        // `@Check(severity = |` — l'attribut severity est de type Level
        // (enum) → LOW, MEDIUM, HIGH.
        String typed = """
                package demo;

                public class Main {
                    public enum Level { LOW, MEDIUM, HIGH }

                    public @interface Check {
                        Level severity();
                    }

                    public @interface Single {
                        Level value();
                    }

                    @Check(severity = )
                    void m() {
                    }
                }
                """;
        int caret = typed.indexOf("severity = ") + "severity = ".length();
        List<String> labels = labels(complete(typed, caret));
        assertTrue(labels.contains("LOW"),
                "LOW (valeur de l'attribut enum) attendue : " + labels);
        assertTrue(labels.contains("MEDIUM"),
                "MEDIUM attendue : " + labels);

        // Forme mono-valeur : `@Single(LE|` → LOW (l'attribut implicite
        // « value » est l'enum Level — la forme mono-valeur exige un
        // attribut nommé value, JLS 9.7.3).
        String single = typed.replace("@Check(severity = )",
                "@Single(LO)");
        int singleCaret = single.indexOf("@Single(LO") + "@Single(LO".length();
        List<String> singleLabels = labels(complete(single, singleCaret));
        assertTrue(singleLabels.contains("LOW"),
                "LOW via la forme mono-valeur : " + singleLabels);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static List<CompletionItem> complete(String source, int caret)
            throws Exception {
        Files.write(main, source.getBytes(StandardCharsets.UTF_8));
        EcjCompletionEngine ce = new EcjCompletionEngine(engine);
        return ce.complete(main.toString(), source, caret);
    }

    private static List<String> labels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) {
            out.add(it.label());
        }
        return out;
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/opt/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-35/android.jar",
                System.getenv("ANDROID_HOME") == null ? null
                        : System.getenv("ANDROID_HOME")
                                + "/platforms/android-34/android.jar",
        };
        for (String c : candidates) {
            if (c == null) continue;
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
