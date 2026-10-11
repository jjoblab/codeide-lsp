package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du formateur Eclipse JDT in-process ({@link JdtFormatter}).
 *
 * <p>Ces tests tournent sur JVM desktop avec les jars Eclipse PATCHÉS
 * (tâche {@code patchEclipseForArt}) — ils valident donc AUSSI que le
 * formateur charge et s'exécute avec les shims
 * ({@code ToolFactory.createCodeFormatter} → {@code DefaultCodeFormatter}
 * → Scanner ecJ réécrit).</p>
 *
 * @author jo@Dev
 */
class JdtFormatterTest {

    private static final String MESSY = """
            package demo;

            public class Main{private int counter=0;
            public String greet(String name){String message="Hello, "+name;counter=counter+1;
                        return message;}}
            """;

    @Test
    void formatsIndentationAndSpacing() {
        JdtFormatter formatter = new JdtFormatter("17");
        String result = formatter.format(MESSY);
        assertNotNull(result, "la source doit être formattable");
        assertTrue(result.contains("public class Main {"),
                "accolade de classe espacée : " + result);
        assertTrue(result.contains("private int counter = 0;"),
                "espaces autour de = : " + result);
        assertTrue(result.contains("public String greet(String name) {"),
                "signature d'espacée : " + result);
        assertTrue(result.contains("\"Hello, \" + name"),
                "espaces autour de + : " + result);
        // Indentation 4 espaces (style Eclipse).
        assertTrue(result.contains("\n    private int counter"),
                "corps de classe indenté de 4 : " + result);
        assertTrue(result.contains("\n        String message"),
                "corps de méthode indenté de 8 : " + result);
    }

    @Test
    void googleStyleUsesTwoSpaces() {
        JdtFormatter formatter = new JdtFormatter("17", JdtFormatter.Style.GOOGLE);
        String result = formatter.format(MESSY);
        assertNotNull(result);
        assertTrue(result.contains("\n  private int counter"),
                "corps de classe indenté de 2 (Google) : " + result);
        assertTrue(result.contains("\n    String message"),
                "corps de méthode indenté de 4 (Google) : " + result);
    }

    @Test
    void formattedOutputIsStable() {
        // Idempotence : formater du code déjà formatté ne change rien.
        JdtFormatter formatter = new JdtFormatter("17");
        String once = formatter.format(MESSY);
        assertNotNull(once);
        String twice = formatter.format(once);
        assertEquals(once, twice, "le formatage doit être idempotent");
    }

    @Test
    void formatsModernSyntax() {
        // Records + text blocks (Java 17) — la conformité doit permettre
        // leur formatage.
        String source = """
                package demo;
                public record Point(int x,int y){public Point doubled(){return new Point(x*2,y*2);}}
                """;
        JdtFormatter formatter = new JdtFormatter("17");
        String result = formatter.format(source);
        assertNotNull(result, "un record Java 17 doit se formater : " + source);
        assertTrue(result.contains("public record Point(int x, int y) {"),
                "en-tête de record espacé : " + result);
    }

    @Test
    void formatRangeOnlyTouchesRange() {
        String source = """
                package demo;

                public class Main {
                    int a = 1;
                int b = 2;
                }
                """.replace("\r", "");

        JdtFormatter formatter = new JdtFormatter("17");
        // Formate uniquement la ligne de b (offset de "int b").
        int start = source.indexOf("int b");
        String result = formatter.formatRange(source, start, "int b = 2;".length());
        assertNotNull(result);
        // La ligne hors plage (a) ne doit PAS être modifiée.
        assertTrue(result.contains("    int a = 1;"),
                "la ligne hors plage reste intacte : " + result);
        assertTrue(result.contains("    int b = 2;"),
                "la ligne dans la plage est indentée : " + result);
    }

    @Test
    void unparseableSourceIsNeverMangled() {
        // Contrat de sûreté : sur une source irrécupérable, le formateur
        // retourne null OU la source inchangée — jamais une version
        // déformée. (ecj récupère parfois assez pour un no-op complet.)
        String broken = "class {{{{ nope";
        JdtFormatter formatter = new JdtFormatter("17");
        String result = formatter.format(broken);
        assertTrue(result == null || result.equals(broken),
                "null ou inchangé attendu, reçu : " + result);
    }

    @Test
    void emptySourceIsReturnedAsIs() {
        JdtFormatter formatter = new JdtFormatter("17");
        assertEquals("", formatter.format(""));
    }

    @Test
    void formatsCommentsAndJavadoc() {
        String source = """
                package demo;
                /**Javadoc    mal   espacée.*/
                public class Main{/*comment*/int x=1;}
                """;
        JdtFormatter formatter = new JdtFormatter("17");
        String result = formatter.format(source);
        assertNotNull(result);
        // F_INCLUDE_COMMENTS : la javadoc est reformattée.
        assertTrue(!result.contains("/**Javadoc    mal   espacée.*/"),
                "la javadoc doit être reformattée : " + result);
    }

    @Test
    void lineWrappingRespectsWidth() {
        // Une déclaration très longue doit être repliée à la largeur (120
        // en style Eclipse).
        String longCall = "public class Main{void m(){java.util.List<String> aVeryLongVariableName = java.util.Arrays.asList(\"one\",\"two\",\"three\",\"four\",\"five\",\"six\",\"seven\",\"eight\",\"nine\",\"ten\",\"eleven\",\"twelve\",\"thirteen\",\"fourteen\");}}"
                .replace("public class Main{", "public class Main {\n    ")
                .replace("void m(){", "void m() {\n        ")
                .replace("}}", "\n    }\n}");
        JdtFormatter formatter = new JdtFormatter("17");
        String result = formatter.format(longCall);
        assertNotNull(result);
        // Après formatage, aucune ligne ne dépasse (largement) la largeur.
        for (String line : result.split("\n", -1)) {
            assertTrue(line.length() <= 130,
                    "ligne trop longue (" + line.length() + ") : " + line);
        }
    }
}
