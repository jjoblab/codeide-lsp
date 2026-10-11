package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import jo.lspjava.api.CompletionItem;
import jo.lspjava.api.TextRange;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests : hover + définitions + complétion sur les bindings ecj
 * (in-process). La plateforme vient de l'image jrt (chemin desktop).
 *
 * @author jo@Dev
 */
class JdtHoverDefinitionCompletionTest {

    private static final String SOURCE = """
            package demo;

            public class Main {
                private int counter = 0;

                public String greet(String name) {
                    String message = "Hello, " + name;
                    counter = counter + 1;
                    return message;
                }

                public int getCounter() {
                    return counter;
                }
            }
            """;

    private JdtCompilerEngine newEngine() {
        return new JdtCompilerEngine(
                Collections.<File>emptyList(),
                Collections.<File>emptyList(),
                "17");
    }

    // ─── Hover ───────────────────────────────────────────────────────

    @Test
    void hoverOnMethodDeclaration() {
        JdtCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("greet");
        String hover = engine.hoverAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(hover, "hover attendu sur greet");
        assertTrue(hover.contains("greet"), "le hover doit nommer la méthode : " + hover);
        assertTrue(hover.contains("String"),
                "le hover doit montrer le type de retour : " + hover);
    }

    @Test
    void hoverOnFieldReference() {
        JdtCompilerEngine engine = newEngine();
        // Hover sur "counter" dans l'incrémentation (une référence, pas la déclaration).
        int offset = SOURCE.indexOf("counter + 1");
        String hover = engine.hoverAt("/demo/Main.java", SOURCE, offset);
        assertNotNull(hover, "hover attendu sur counter");
        assertTrue(hover.contains("int"), "le hover doit montrer le type int : " + hover);
        assertTrue(hover.contains("counter"), "le hover doit nommer le champ : " + hover);
    }

    @Test
    void hoverOnStringMethodCall() {
        JdtCompilerEngine engine = newEngine();
        // "Hello, " + name — hover sur la concaténation ne donne rien
        // d'exploitable ; on teste plutôt un vrai appel : length().
        String source = """
                package demo;

                public class Main {
                    public int size(String s) {
                        return s.length();
                    }
                }
                """;
        int offset = source.indexOf("length");
        String hover = engine.hoverAt("/demo/Main.java", source, offset);
        assertNotNull(hover, "hover attendu sur length()");
        assertTrue(hover.contains("length"), "le hover doit nommer length : " + hover);
        assertTrue(hover.contains("int"),
                "String.length() retourne int : " + hover);
    }

    @Test
    void hoverOutsideAnyNode() {
        JdtCompilerEngine engine = newEngine();
        // Offset 0 = début du package — pas de binding hoverable.
        String hover = engine.hoverAt("/demo/Main.java", SOURCE, 0);
        assertNull(hover, "pas de hover attendu au tout début");
    }

    // ─── Définitions ─────────────────────────────────────────────────

    @Test
    void definitionOfLocalVariable() {
        JdtCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("return message");
        TextRange range = engine.findDefinition("/demo/Main.java", SOURCE,
                offset + "return ".length());
        assertNotNull(range, "définition attendue pour message");
        // La plage doit couvrir le "message" de la DÉCLARATION.
        int declIdx = SOURCE.indexOf("String message");
        assertTrue(range.startOffset() >= declIdx && range.endOffset() <= declIdx + 50,
                "la définition doit être la déclaration : " + range);
    }

    @Test
    void definitionOfField() {
        JdtCompilerEngine engine = newEngine();
        int offset = SOURCE.indexOf("counter + 1");
        TextRange range = engine.findDefinition("/demo/Main.java", SOURCE, offset);
        assertNotNull(range, "définition attendue pour counter");
        int declIdx = SOURCE.indexOf("private int counter");
        assertTrue(range.startOffset() >= declIdx
                        && range.startOffset() < declIdx + 25,
                "la définition doit être le champ déclaré : " + range);
    }

    @Test
    void definitionOfMethodCall() {
        String source = """
                package demo;

                public class Main {
                    public String build(String name) {
                        return decorate(name);
                    }

                    private String decorate(String s) {
                        return "[" + s + "]";
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        int offset = source.indexOf("decorate(name)");
        TextRange range = engine.findDefinition("/demo/Main.java", source, offset);
        assertNotNull(range, "définition attendue pour decorate");
        int declIdx = source.indexOf("private String decorate");
        assertTrue(range.startOffset() >= declIdx && range.startOffset() < declIdx + 30,
                "la définition doit être la méthode déclarée : " + range);
    }

    // ─── Complétion ──────────────────────────────────────────────────

    @Test
    void memberCompletionOnStringVariable() {
        String source = """
                package demo;

                public class Main {
                    public int test(String s) {
                        return s.le
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("s.le") + "s.le".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items, "la complétion doit être résolue (non-null)");
        assertTrue(items.stream().anyMatch(i -> "length".equals(i.label())),
                "length() doit être proposé sur un String : " + items);
    }

    @Test
    void memberCompletionIncludesInheritedMembers() {
        // Préfixe vide (juste le point) — les membres hérités de la
        // hiérarchie String (CharSequence/Comparable/Object) apparaissent.
        String source = """
                package demo;

                public class Main {
                    public int test(String s) {
                        return s.
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("s.") + "s.".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "isEmpty".equals(i.label())),
                "isEmpty() doit être proposé : " + items);
        assertTrue(items.stream().anyMatch(i -> "charAt".equals(i.label())),
                "charAt() doit être proposé : " + items);
        assertTrue(items.stream().anyMatch(i -> "equals".equals(i.label())),
                "equals() (de Object) doit être proposé — héritage : " + items);
    }

    @Test
    void memberCompletionOnOwnField() {
        String source = """
                package demo;

                public class Main {
                    private String name = "x";

                    public int test() {
                        return name.le
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("name.le") + "name.le".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "length".equals(i.label())),
                "length() doit être proposé sur le champ String : " + items);
    }

    @Test
    void memberCompletionStaticQualifier() {
        String source = """
                package demo;

                public class Main {
                    public String test() {
                        return String.va
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("String.va") + "String.va".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "valueOf".equals(i.label())),
                "String.valueOf doit être proposé (statique) : " + items);
        // Pas de méthode d'instance sur un qualifier de type.
        assertTrue(items.stream().noneMatch(i -> "charAt".equals(i.label())),
                "charAt (instance) ne doit PAS être proposé sur le type String : "
                        + items);
    }

    @Test
    void nameCompletionProposesLocals() {
        String source = """
                package demo;

                public class Main {
                    public int test() {
                        int total = 42;
                        return tot
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("return tot") + "return tot".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "total".equals(i.label())),
                "la locale 'total' doit être proposée : " + items);
    }

    @Test
    void nameCompletionProposesEnclosingMembers() {
        String source = """
                package demo;

                public class Main {
                    private int counter = 0;

                    public int test() {
                        return cou
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("return cou") + "return cou".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "counter".equals(i.label())),
                "le champ 'counter' doit être proposé : " + items);
    }

    @Test
    void memberCompletionFiltersByPrefix() {
        String source = """
                package demo;

                public class Main {
                    public int test(String s) {
                        return s.is
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("s.is") + "s.is".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().allMatch(i -> i.label().startsWith("is")),
                "tous les items doivent matcher le préfixe 'is' : " + items);
    }

    @Test
    void completionOnBrokenCodeStillWorks() {
        // Code cassé autour du caret — la récupération d'ecj doit quand même
        // résoudre le contexte de complétion.
        String source = """
                package demo;

                public class Main {
                    public int test(String s) {
                        int broken = undefinedThing;
                        return s.le
                    }
                }
                """;
        JdtCompilerEngine engine = newEngine();
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);
        int offset = source.indexOf("s.le") + "s.le".length();
        List<CompletionItem> items = completion.complete("/demo/Main.java",
                source, offset);
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "length".equals(i.label())),
                "length() doit être proposé malgré le code cassé : " + items);
    }
}
