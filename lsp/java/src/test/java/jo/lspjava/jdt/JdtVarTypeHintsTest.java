package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des hints de TYPE des déclarations {@code var} par bindings ecj
 * ({@link JdtInlayHints#typeHintsFor}) : le type RÉEL de l'initialiseur
 * (appel de méthode, enhanced-for, génériques), ancré à la fin du NOM.
 *
 * @author jo@Dev
 */
class JdtVarTypeHintsTest {

    private static List<JdtInlayHints.Hint> hintsOf(String source) {
        JdtCompilerEngine engine = new JdtCompilerEngine(
                Collections.emptyList(), Collections.emptyList(), "17");
        return new JdtInlayHints(engine).typeHintsFor("/demo/Main.java", source);
    }

    /**
     * Le hint ancré juste après la DÉCLARATION {@code var <name>} (recherche
     * le motif de déclaration, pas la première occurrence du nom).
     */
    private static JdtInlayHints.Hint hintAfter(String source, String name) {
        int decl = source.indexOf("var " + name);
        assertTrue(decl >= 0, "déclaration « var " + name + " » introuvable");
        int nameEnd = decl + 4 + name.length();
        for (JdtInlayHints.Hint h : hintsOf(source)) {
            if (h.offset == nameEnd) return h;
        }
        return null;
    }

    private static String src(String body) {
        return "package demo;\n\nimport java.util.List;\n\n"
                + "public class Main {\n" + body + "}\n";
    }

    @Test
    void complexInitializerGetsRealType() {
        String source = src("""
                    void run() {
                        var s = String.valueOf(42);
                    }
                """);
        // Le binding donne le type réel de l'initialiseur (ici un appel
        // de méthode) : String.
        JdtInlayHints.Hint h = hintAfter(source, "s");
        assertNotNull(h, "hint attendu pour var s (appel de méthode)");
        assertEquals(": String", h.label);
    }

    @Test
    void genericsPreserved() {
        String source = src("""
                    void run(List<String> items) {
                        var list = items;
                    }
                """);
        JdtInlayHints.Hint h = hintAfter(source, "list");
        assertNotNull(h);
        assertEquals(": List<String>", h.label);
    }

    @Test
    void enhancedForVarHinted() {
        String source = src("""
                    void run(List<String> items) {
                        for (var it : items) {
                            System.out.println(it);
                        }
                    }
                """);
        JdtInlayHints.Hint h = hintAfter(source, "it");
        assertNotNull(h, "hint attendu pour le var du enhanced-for");
        assertEquals(": String", h.label);
    }

    @Test
    void typedDeclarationNotHinted() {
        String source = src("""
                    void run() {
                        int plain = 1;
                        String s = "x";
                    }
                """);
        var hints = hintsOf(source);
        assertTrue(hints.isEmpty(),
                "aucun hint pour les déclarations typées : " + hints);
    }

    @Test
    void varInStringLiteralNotHinted() {
        // « var » dans une chaîne n'est pas une déclaration.
        String source = src("""
                    void run() {
                        String s = "var x = 1;";
                    }
                """);
        assertTrue(hintsOf(source).isEmpty(),
                "var dans une chaîne : pas de hint");
    }

    @Test
    void anchorIsAtNameEnd() {
        String source = src("""
                    void run() {
                        var greeting = "hi";
                    }
                """);
        JdtInlayHints.Hint h = hintAfter(source, "greeting");
        assertNotNull(h);
        // L'ancre est JUSTE après le nom, AVANT le « = ».
        assertEquals(" =", source.substring(h.offset, h.offset + 2));
        assertEquals(": String", h.label);
    }
}
