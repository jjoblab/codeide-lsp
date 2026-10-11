package jo.lspjava.lsp;

import jo.lspjava.engine.CompilationEnvironment;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
 * Tests BOUT-EN-BOUT à travers la façade LSP réelle
 * ({@link JavaTextDocumentService}) : {@code textDocument/typeDefinition}
 * (le type du symbole au caret — section GO TO du menu contextuel unifié
 * de la toolbar de sélection) et les offsets de
 * {@code textDocument/definition} sur les fichiers réduits par
 * {@code SourceFocuser} (&gt; 2000 chars — les plages doivent être rendues
 * en coordonnées de la source originale, pas en coordonnées réduites).
 *
 * @author jo@Dev
 */
class TypeDefinitionFacadeTest {

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

    private static int offsetOf(String source, String needle, int bias) {
        int idx = source.indexOf(needle);
        return idx + bias;
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

    private static List<Location> typeDefinition(String uri, String source, int offset) {
        try {
            TypeDefinitionParams params = new TypeDefinitionParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    service.typeDefinition(params).get();
            if (result == null) return List.of();
            return List.copyOf(result.getLeft());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Location> definition(String uri, String source, int offset) {
        try {
            DefinitionParams params = new DefinitionParams(
                    new TextDocumentIdentifier(uri), positionOf(source, offset));
            Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    service.definition(params).get();
            if (result == null) return List.of();
            return List.copyOf(result.getLeft());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── typeDefinition ────────────────────────────────────────────────

    @Test
    void typeDefinition_localVariableResolvesItsDeclaredType() {
        String source = """
                package demo;

                public class Holder {
                    class Item {
                        String name = "i";
                    }

                    void use() {
                        Item it = new Item();
                        it.name = "x";
                    }
                }
                """;
        String uri = open("Holder.java", source);
        // Caret sur « it » (la variable locale) → la déclaration de Item.
        int offset = offsetOf(source, "it.name", 0);
        List<Location> targets = typeDefinition(uri, source, offset);
        assertEquals(1, targets.size(), "une cible : la déclaration de Item");
        Location loc = targets.get(0);
        // L'item de l'assert : la cible est le NOM « Item » de la classe interne.
        int itemDecl = source.indexOf("class Item") + "class ".length();
        Position expected = positionOf(source, itemDecl);
        assertEquals(expected.getLine(), loc.getRange().getStart().getLine());
        assertEquals(expected.getCharacter(), loc.getRange().getStart().getCharacter());
    }

    @Test
    void typeDeclaration_onTypeReferenceResolvesItself() {
        String source = """
                package demo;

                public class Box {
                    void run() {
                        Box b = new Box();
                    }
                }
                """;
        String uri = open("Box.java", source);
        // Caret sur « Box » dans « Box b » → la déclaration de Box.
        int offset = offsetOf(source, "Box b", 1);
        List<Location> targets = typeDefinition(uri, source, offset);
        assertEquals(1, targets.size());
        int decl = source.indexOf("class Box") + "class ".length();
        Position expected = positionOf(source, decl);
        assertEquals(expected.getLine(), targets.get(0).getRange().getStart().getLine());
        assertEquals(expected.getCharacter(), targets.get(0).getRange().getStart().getCharacter());
    }

    /**
     * Comportement attendu : un type binaire (java.lang.String, JDK
     * classpath) renvoie une Location à URI synthétique
     * {@code jdt://decompiled/<fqcn>.java} — câble de bout en bout la
     * fonctionnalité « GO TO Definition sur cible binaire ». Le client
     * ({@code :cel-lsp}) appelle alors
     * {@code textDocument/decompiledSource(fqcn)} pour récupérer la source
     * (attachée ou stub décompilé) et ouvre le buffer read-only côté app.
     *
     * <p>La Location synthétique pointe vers un document virtuel
     * {@code jdt://decompiled/...} avec une plage vide (0:0-0:0) — le caret
     * reste en haut du buffer décompilé.</p>
     */
    @Test
    void typeDeclaration_binaryTypeYieldsNoTarget() {
        String source = """
                package demo;

                public class UsesString {
                    void run() {
                        String s = "x";
                    }
                }
                """;
        String uri = open("UsesString.java", source);
        // Caret sur « s » → java.lang.String : BINAIRE (classpath). Le serveur
        // renvoie une Location synthétique jdt://decompiled/java/lang/String.java
        // (fallback binaire).
        int offset = offsetOf(source, "s =", 0);
        List<Location> targets = typeDefinition(uri, source, offset);
        assertEquals(1, targets.size(),
                "un type binaire renvoie une Location jdt://decompiled/... : "
                        + targets);
        Location loc = targets.get(0);
        assertEquals("jdt://decompiled/java/lang/String.java", loc.getUri(),
                "URI synthétique jdt://decompiled/<fqcn-path>.java");
        // Plage vide (0:0-0:0) — le buffer décompilé n'a pas de position
        // cible précise ; le caret reste en haut du fichier.
        assertEquals(0, loc.getRange().getStart().getLine());
        assertEquals(0, loc.getRange().getStart().getCharacter());
        assertEquals(0, loc.getRange().getEnd().getLine());
        assertEquals(0, loc.getRange().getEnd().getCharacter());
    }

    @Test
    void typeDeclaration_intLiteralYieldsNoTarget() {
        String source = """
                package demo;

                public class Counts {
                    void run() {
                        int n = 42;
                    }
                }
                """;
        String uri = open("Counts.java", source);
        int offset = offsetOf(source, "n =", 0);
        List<Location> targets = typeDefinition(uri, source, offset);
        assertTrue(targets.isEmpty(),
                "int est un type de base — pas de déclaration : " + targets);
    }

    // ── definition : offsets sur source réduite ───────────

    /**
     * Le fichier dépasse 2000 chars : le caret dans {@code caller()} déclenche
     * la réduction du corps de {@code big()} par SourceFocuser. La définition
     * de {@code big()} est APRÈS la méthode du caret — la plage rendue doit
     * être en coordonnées de la source ORIGINALE (et non en coordonnées
     * réduites, décalées vers la gauche). Le test verrouille la position
     * EXACTE dans la source complète.
     */
    @Test
    void definition_afterFocusedMethod_landsOnExactOriginalOffset() {
        StringBuilder sb = new StringBuilder();
        sb.append("package demo;\n\npublic class BigFile {\n");
        sb.append("    void caller() {\n");
        sb.append("        big();  // <-- caret ici\n");
        sb.append("    }\n\n");
        sb.append("    void big() {\n");
        for (int i = 0; i < 120; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        String source = sb.toString();
        assertTrue(source.length() > 2000,
                "la fixture doit dépasser 2000 chars : " + source.length());

        String uri = open("BigFile.java", source);
        int caret = offsetOf(source, "big();", 1);
        List<Location> targets = definition(uri, source, caret);
        assertTrue(targets.size() >= 1, "la définition de big() doit être trouvée");
        Location loc = targets.get(0);
        // La cible : le NOM « big » de la déclaration — position exacte dans
        // la source ORIGINALE.
        int bigName = source.indexOf("void big()") + "void ".length();
        Position expected = positionOf(source, bigName);
        assertEquals(expected.getLine(), loc.getRange().getStart().getLine(),
                "ligne exacte de la déclaration de big()");
        assertEquals(expected.getCharacter(), loc.getRange().getStart().getCharacter(),
                "colonne exacte du nom big (mapBack du focuser)");
    }
}
