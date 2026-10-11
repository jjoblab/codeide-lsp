package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du calcul des régions de folding ({@link EcjCodeFolder}) —
 * parité {@code EcjCodeFolder} du projet de référence : groupe d'imports replié
 * par défaut, intérieur des accolades (les {}/() restent visibles),
 * commentaires de bloc multi-lignes, RIEN pour les paires sur une seule
 * ligne ni les accolades dans chaînes/commentaires/text-blocks.
 *
 * @author jo@Dev
 */
class EcjCodeFolderTest {

    private static EcjCodeFolder folder() {
        EcjCompilerEngine engine = new EcjCompilerEngine(
                Collections.emptyList(), Collections.emptyList(), "17");
        return new EcjCodeFolder(engine);
    }

    private static List<EcjCodeFolder.Fold> foldsOf(String source) {
        return folder().foldsFor("/demo/Main.java", source);
    }

    /** La fold du genre donné dont le texte de placeholder correspond. */
    private static EcjCodeFolder.Fold find(List<EcjCodeFolder.Fold> folds,
                                            String kind, String placeholder) {
        for (EcjCodeFolder.Fold f : folds) {
            if (kind.equals(f.kind)
                    && (placeholder == null || placeholder.equals(f.placeholder))) {
                return f;
            }
        }
        return null;
    }

    private static String src(String body) {
        return "package demo;\n\n" + body;
    }

    // ── Imports ─────────────────────────────────────────────────────────

    @Test
    void importGroupFoldedCollapsedByDefault() {
        String source = src("""
                import java.util.List;
                import java.util.Map;

                public class Main {
                    void run() {
                    }
                }
                """);
        List<EcjCodeFolder.Fold> folds = foldsOf(source);
        EcjCodeFolder.Fold imports = find(folds, "imports", "import ...");
        assertNotNull(imports, "groupe d'imports attendu : " + folds);
        assertTrue(imports.collapsedByDefault,
                "le groupe d'imports est replié PAR DÉFAUT (projet de référence)");
        // La région démarre au « import » du premier et se termine JUSTE
        // APRÈS le « ; » du DERNIER import.
        int lastImportSemi = source.indexOf(";",
                source.indexOf("import java.util.Map"));
        assertEquals(source.indexOf("import java.util.List"), imports.start);
        assertEquals(lastImportSemi + 1, imports.end);
    }

    @Test
    void noImportFoldWithoutImports() {
        List<EcjCodeFolder.Fold> folds = foldsOf(src("""
                public class Main {
                }
                """));
        assertEquals(null, find(folds, "imports", null));
    }

    // ── Accolades ───────────────────────────────────────────────────────

    @Test
    void bracePairsFoldInteriorOnly() {
        String source = src("""
                public class Main {
                    void run() {
                        System.out.println("x");
                    }
                }
                """);
        List<EcjCodeFolder.Fold> folds = foldsOf(source);
        // Deux paires : type + méthode — la région couvre l'INTÉRIEUR
        // (juste après { jusqu'à la } fermante, exclue).
        EcjCodeFolder.Fold type = foldContaining(folds,
                source.indexOf("void run()"));
        assertNotNull(type);
        assertEquals(source.indexOf('{') + 1, type.start);
        assertEquals(source.lastIndexOf('}'), type.end);
        assertEquals("block", type.kind);
        assertFalse(type.collapsedByDefault);

        int methodOpen = source.indexOf('{', source.indexOf("run()"));
        EcjCodeFolder.Fold method = foldContaining(folds, methodOpen + 1);
        assertNotNull(method, "fold du corps de méthode attendu");
        assertEquals(methodOpen + 1, method.start);
        int methodClose = source.indexOf('}', methodOpen);
        assertEquals(methodClose, method.end);
    }

    @Test
    void singleLineBracesNotFolded() {
        List<EcjCodeFolder.Fold> folds = foldsOf(src("""
                public class Main { void run() { int x = 1; } }
                """));
        assertTrue(folds.isEmpty(), "aucune fold multi-ligne attendue : " + folds);
    }

    @Test
    void bracesInStringsAndCommentsIgnored() {
        // Text-block construit à l'exécution (pas de « \"\"\" » imbriqué
        // dans le text-block du test lui-même).
        String tb = "\"\"\"\n" + "text { block } content\n" + "\"\"\"";
        String source = src("""
                public class Main {
                    String s = "{ not a fold";
                    /* { comment brace
                       spanning lines } */
                    void run() {
                        String t = TB_PLACEHOLDER;
                        System.out.println(s + t);
                    }
                }
                """.replace("TB_PLACEHOLDER", tb));
        List<EcjCodeFolder.Fold> folds = foldsOf(source);
        // La fold du commentaire existe ; l'accolade DANS la chaîne n'ouvre
        // aucune paire : aucune fold ne démarre juste après elle (le corps
        // de classe qui CONTIENT la ligne est légitime).
        EcjCodeFolder.Fold comment = find(folds, "comment", "/*…*/");
        assertNotNull(comment, "fold de commentaire attendue : " + folds);
        int braceInString = source.indexOf("{ not a fold");
        for (EcjCodeFolder.Fold f : folds) {
            assertTrue(f.start != braceInString + 1,
                    "l'accolade dans la chaîne a ouvert une fold : " + f.start);
        }
        // Le text-block contient « { block } » — aucune fold ne doit se
        // fermer sur l'accolade INTERNE du text-block.
        int tbOpen = source.indexOf("\"\"\"");
        for (EcjCodeFolder.Fold f : folds) {
            if ("block".equals(f.kind)) {
                assertTrue(f.end != source.indexOf('}', tbOpen),
                        "une fold se ferme sur l'accolade du text-block");
            }
        }
    }

    @Test
    void controlFlowAndLambdaBlocksFold() {
        String source = src("""
                public class Main {
                    void run() {
                        if (System.currentTimeMillis() > 0) {
                            System.out.println("a");
                            System.out.println("b");
                        }
                        Runnable r = () -> {
                            System.out.println("c");
                            System.out.println("d");
                        };
                    }
                }
                """);
        List<EcjCodeFolder.Fold> folds = foldsOf(source);
        int ifOpen = source.indexOf('{', source.indexOf("if ("));
        assertNotNull(foldContaining(folds, ifOpen + 1), "fold du bloc if");
        int lambdaOpen = source.indexOf("{", source.indexOf("() ->"));
        assertNotNull(foldContaining(folds, lambdaOpen + 1), "fold du lambda");
    }

    // ── Commentaires ────────────────────────────────────────────────────

    @Test
    void blockAndJavadocCommentsFold() {
        String source = src("""
                /** Javadoc
                 * multi-ligne
                 */
                public class Main {
                    /* bloc
                       multi-ligne */
                    void run() {
                    }
                }
                """);
        List<EcjCodeFolder.Fold> folds = foldsOf(source);
        EcjCodeFolder.Fold javadoc = find(folds, "comment", "/**…*/");
        assertNotNull(javadoc, "fold javadoc attendue : " + folds);
        assertEquals(source.indexOf("/**"), javadoc.start);
        assertEquals(source.indexOf("*/") + 2, javadoc.end);
        EcjCodeFolder.Fold block = find(folds, "comment", "/*…*/");
        assertNotNull(block, "fold commentaire de bloc attendue");
        int blockStart = source.indexOf("/*", javadoc.end);
        assertEquals(blockStart, block.start);
    }

    @Test
    void lineCommentsNeverFold() {
        List<EcjCodeFolder.Fold> folds = foldsOf(src("""
                // ligne 1
                // ligne 2
                public class Main {
                }
                """));
        assertEquals(null, find(folds, "comment", null));
    }

    /** La fold (kind=block) dont la plage contient offset. */
    private static EcjCodeFolder.Fold foldContaining(List<EcjCodeFolder.Fold> folds,
                                                      int offset) {
        EcjCodeFolder.Fold best = null;
        for (EcjCodeFolder.Fold f : folds) {
            if (f.start <= offset && offset < f.end && "block".equals(f.kind)) {
                if (best == null || (f.end - f.start) < (best.end - best.start)) {
                    best = f; // la plus INTERNE
                }
            }
        }
        return best;
    }
}
