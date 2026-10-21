package jo.lspjava.services;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;
import jo.lspjava.api.TextRange;
import jo.lspjava.engine.CompilationEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des quick-fixes à ÉDITS RÉELS ({@link CodeActionService}).
 *
 * <p>Ces tests verrouillent le contrat : chaque action porte des édits
 * concrètes sur le texte courant — portages des quick-fixes du projet tiers de référence
 * (JavaActions.kt, JavaCompilerFixes.kt) — appliquer « Entourer avec
 * try-catch », « Déclarer throws », « Insérer un cast » ou « Supprimer
 * l'import inutilisé » modifie réellement le texte.</p>
 *
 * @author jo@Dev
 */
class CodeActionServiceTest {

    @TempDir
    static Path tmp;

    static CodeActionService serviceWithIndex;
    static CodeActionService textOnlyService;

    static String findAndroidJar() {
        // Même heuristique que SubpackageGoldenRulesTest.
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar",
                System.getenv("ANDROID_SDK_ROOT") + "/platforms/android-34/android.jar"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        File local = new File("/home/z/tools/android-sdk");
        File[] platforms = local.isDirectory()
                ? new File(local, "platforms").listFiles() : null;
        if (platforms != null) {
            for (File p : platforms) {
                File jar = new File(p, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    @BeforeAll
    static void setUp() throws Exception {
        // Service TEXTE pur (sans environnement) — fixes non-index.
        textOnlyService = new CodeActionService(null);

        // Service avec INDEX réel (jrt du JDK courant + android.jar si
        // présent) — fixes « Importer X ».
        String androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");
        Path src = Files.createDirectories(tmp.resolve("src"));
        CompilationEnvironment env = new CompilationEnvironment(
                List.of(src.toFile()), List.of(new File(androidJar)), "17");
        serviceWithIndex = new CodeActionService(env);
    }

    // ── Aides ──────────────────────────────────────────────────────────

    private static Diagnostic diag(int problemId, String source,
                                   int start, int end, String... args) {
        return new Diagnostic(
                new TextRange(start, end), Severity.ERROR,
                "synthetic problem", "ecj:" + problemId, "test",
                List.of(args));
    }

    private static List<CodeActionService.Edit> firstEdits(
            List<CodeActionService.CodeAction> actions, String kind) {
        for (CodeActionService.CodeAction a : actions) {
            if (a.kind().equals(kind)) return a.edits();
        }
        return null;
    }

    /** Applique les édits sur le texte (dans l'ordre décroissant). */
    private static String apply(String source, List<CodeActionService.Edit> edits) {
        List<CodeActionService.Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder sb = new StringBuilder(source);
        for (CodeActionService.Edit e : sorted) {
            sb.replace(e.start(), e.end(), e.text());
        }
        return sb.toString();
    }

    // ── UNUSED_IMPORT : suppression de la ligne entière ────────────────

    @Test
    void unusedImportDeletesWholeLine() {
        String src = "package com.example;\n"
                + "import java.util.List;\n"
                + "import java.util.ArrayList;\n"
                + "\n"
                + "public class Main {\n}\n";
        int start = src.indexOf("java.util.List");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnusedImport, src, start, start + 14), src);
        List<CodeActionService.Edit> edits = firstEdits(actions, "quickfix.remove.import");
        assertNotNull(edits, "le fix doit exister");
        assertEquals(1, edits.size());
        String out = apply(src, edits);
        assertFalse(out.contains("import java.util.List;"),
                "la ligne d'import doit disparaître");
        assertTrue(out.contains("import java.util.ArrayList;"),
                "l'autre import doit survivre");
    }

    @Test
    void unusedImportNotOfferedOutsideImportLine() {
        String src = "package com.example;\n"
                + "public class Main {\n"
                + "    int List = 1;\n"
                + "}\n";
        int start = src.indexOf("int List");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnusedImport, src, start, start + 8), src);
        assertTrue(firstEdits(actions, "quickfix.remove.import") == null,
                "pas de suppression hors d'une ligne import");
    }

    // ── MISSING_SEMI : « ; » en FIN de ligne ───────────────────────────

    @Test
    void missingSemiInsertedAtLineEnd() {
        String src = "public class Main {\n"
                + "    int x = 5\n"
                + "}\n";
        int at = src.indexOf("5");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.MissingSemiColon, src, at, at + 1), src);
        List<CodeActionService.Edit> edits = firstEdits(actions, "quickfix.semicolon");
        assertNotNull(edits);
        String out = apply(src, edits);
        assertTrue(out.contains("int x = 5;"),
                "le ; doit se retrouver en fin d'instruction : " + out);
    }

    // ── UNUSED_DECLARATION : suppression prudente ──────────────────────

    @Test
    void unusedLocalDeclarationDeleted() {
        String src = "public class Main {\n"
                + "    void m() {\n"
                + "        int unused = 5;\n"
                + "        System.out.println(1);\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("unused");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.LocalVariableIsNeverUsed, src, start, start + 6), src);
        List<CodeActionService.Edit> edits = firstEdits(actions, "quickfix.remove.unused");
        assertNotNull(edits);
        String out = apply(src, edits);
        assertFalse(out.contains("int unused"),
                "la déclaration doit disparaître : " + out);
        assertTrue(out.contains("System.out.println(1);"),
                "l'instruction suivante doit survivre");
    }

    @Test
    void multiFragmentDeclarationIsLeftAlone() {
        // projet tiers de référence : « int a, b; » — risque de variable sœur → pas de fix.
        String src = "public class Main {\n"
                + "    void m() {\n"
                + "        int a = 1, b = 2;\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("b = 2");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.LocalVariableIsNeverUsed, src, start, start + 1), src);
        assertTrue(firstEdits(actions, "quickfix.remove.unused") == null,
                "une déclaration multi-fragments ne doit pas être supprimée");
    }

    @Test
    void parameterIsLeftAlone() {
        // Un paramètre de méthode : la plage est précédée de '(' ou ','.
        String src = "public class Main {\n"
                + "    void m(int param) {\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("param");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.ArgumentIsNeverUsed, src, start, start + 5), src);
        assertTrue(firstEdits(actions, "quickfix.remove.unused") == null,
                "un paramètre ne doit pas être supprimé");
    }

    // ── UNCAUGHT_EXCEPTION : try-catch + throws ────────────────────────

    @Test
    void tryCatchWrapsStatementWithExactException() {
        String src = "import java.io.File;\n"
                + "public class Main {\n"
                + "    void m() throws RuntimeException {\n"
                + "        File f = new File(\"x\");\n"
                + "        new java.io.FileInputStream(f);\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("new java.io.FileInputStream");
        int end = start + "new java.io.FileInputStream(f)".length();
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnhandledException, src, start, end,
                        "java.io.FileNotFoundException"), src);

        List<CodeActionService.Edit> tryEdits = firstEdits(actions, "quickfix.surround.trycatch");
        assertNotNull(tryEdits);
        String out = apply(src, tryEdits);
        assertTrue(out.contains("try {"), "un bloc try doit apparaître");
        assertTrue(out.contains("} catch (FileNotFoundException e) {"),
                "le catch doit cibler le type EXACT de l'argument structuré : " + out);
        assertTrue(out.contains("import java.io.FileNotFoundException;"),
                "l'import de l'exception (hors java.lang) doit être ajouté : " + out);
        // L'instruction englobante reste DANS le try.
        assertTrue(out.contains("new java.io.FileInputStream(f);"));
    }

    @Test
    void throwsCreatedAfterParameterList() {
        String src = "public class Main {\n"
                + "    void m(int a) {\n"
                + "        Thread.sleep(10);\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("Thread.sleep");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnhandledException, src, start,
                        start + 5, "java.lang.InterruptedException"), src);
        List<CodeActionService.Edit> throwsEdits = firstEdits(actions, "quickfix.declare.throws");
        assertNotNull(throwsEdits);
        String out = apply(src, throwsEdits);
        assertTrue(out.contains("void m(int a) throws InterruptedException"),
                "la clause throws doit suivre la liste de paramètres : " + out);
    }

    @Test
    void throwsAppendedToExistingClause() {
        String src = "public class Main {\n"
                + "    void m() throws java.io.IOException {\n"
                + "        Thread.sleep(10);\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("Thread.sleep");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnhandledException, src, start,
                        start + 5, "java.lang.InterruptedException"), src);
        List<CodeActionService.Edit> throwsEdits = firstEdits(actions, "quickfix.declare.throws");
        assertNotNull(throwsEdits);
        String out = apply(src, throwsEdits);
        assertTrue(out.contains("throws java.io.IOException, InterruptedException"),
                "la nouvelle exception doit s'ajouter À la clause existante : " + out);
    }

    @Test
    void throwsFindsMethodInsideControlBlocks() {
        // Le diagnostic est dans un if DANS la méthode — le scan d'accolades
        // doit traverser le bloc de contrôle pour trouver l'en-tête.
        String src = "public class Main {\n"
                + "    void m() {\n"
                + "        if (true) {\n"
                + "            Thread.sleep(10);\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("Thread.sleep");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UnhandledException, src, start,
                        start + 5, "java.lang.InterruptedException"), src);
        List<CodeActionService.Edit> throwsEdits = firstEdits(actions, "quickfix.declare.throws");
        assertNotNull(throwsEdits, "la méthode englobante doit être trouvée à travers le if");
        String out = apply(src, throwsEdits);
        assertTrue(out.contains("void m() throws InterruptedException"),
                "le throws doit atterrir sur m(), pas sur le if : " + out);
    }

    // ── TYPE_MISMATCH : cast vers le type ATTENDU (args[1]) ────────────

    @Test
    void castUsesExpectedType() {
        // Message ecj : « cannot convert from {0} to {1} » → caster vers args[1].
        String src = "public class Main {\n"
                + "    void m(Object o) {\n"
                + "        String s = o;\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("o;");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.TypeMismatch, src, start, start + 1,
                        "java.lang.Object", "java.lang.String"), src);
        List<CodeActionService.Edit> castEdits = firstEdits(actions, "quickfix.cast");
        assertNotNull(castEdits);
        String out = apply(src, castEdits);
        assertTrue(out.contains("(String) o"),
                "le cast doit cibler le type ATTENDU (args[1]) : " + out);
    }

    // ── UNRESOLVED_REFERENCE : « Importer X (fqn) » depuis l'index ─────

    @Test
    void unresolvedTypeOffersImportCandidates() {
        Assumptions.assumeTrue(serviceWithIndex != null, "index indisponible");
        String src = "package com.example;\n"
                + "public class Main {\n"
                + "    void m() {\n"
                + "        List list = null;\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("List");
        List<CodeActionService.CodeAction> actions = serviceWithIndex.actionsFor(
                diag(IProblem.UndefinedType, src, start, start + 4, "List"), src);
        List<String> titles = new ArrayList<>();
        for (CodeActionService.CodeAction a : actions) {
            if (a.kind().equals("quickfix.import.fqn")) titles.add(a.title());
        }
        assertFalse(titles.isEmpty(), "au moins un candidat d'import (java.util.List)");
        assertTrue(titles.stream().anyMatch(t -> t.contains("java.util.List")),
                "java.util.List doit être proposé : " + titles);
        // Chaque action porte une VRAIE édition d'import.
        for (CodeActionService.CodeAction a : actions) {
            if (!a.kind().equals("quickfix.import.fqn")) continue;
            assertEquals(1, a.edits().size());
            CodeActionService.Edit e = a.edits().get(0);
            assertTrue(e.text().startsWith("import "));
            assertTrue(e.text().endsWith(";\n"));
            assertEquals(e.start(), e.end(), "insertion pure");
        }
    }

    @Test
    void unresolvedLowercaseNameGetsNoImportFix() {
        // Règle projet tiers de référence : seuls les noms ressemblant à des TYPES
        // (initiale majuscule) reçoivent des candidats d'import.
        Assumptions.assumeTrue(serviceWithIndex != null, "index indisponible");
        String src = "public class Main {\n"
                + "    void m() {\n"
                + "        foo();\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("foo");
        List<CodeActionService.CodeAction> actions = serviceWithIndex.actionsFor(
                diag(IProblem.UndefinedMethod, src, start, start + 3, "foo"), src);
        assertTrue(firstEdits(actions, "quickfix.import.fqn") == null,
                "un nom de méthode non résolu ne reçoit pas de fix d'import");
    }

    @Test
    void importFixSkipsAlreadyImportedAndJavaLang() {
        Assumptions.assumeTrue(serviceWithIndex != null, "index indisponible");
        // String est java.lang → jamais proposé ; List déjà importé → le
        // candidat java.util.List est exclu (un autre « List » du classpath,
        // p.ex. java.awt.List, reste légitimement proposable).
        String src = "package com.example;\n"
                + "import java.util.List;\n"
                + "public class Main {\n"
                + "    void m() {\n"
                + "        List list;\n"
                + "        String s;\n"
                + "    }\n"
                + "}\n";
        int listAt = src.indexOf("List list");
        int strAt = src.indexOf("String s");
        List<CodeActionService.CodeAction> forList = serviceWithIndex.actionsFor(
                diag(IProblem.UndefinedType, src, listAt, listAt + 4, "List"), src);
        for (CodeActionService.CodeAction a : forList) {
            if (!a.kind().equals("quickfix.import.fqn")) continue;
            assertFalse(a.title().contains("java.util.List"),
                    "List déjà importé → ce candidat doit être exclu : " + a.title());
        }

        List<CodeActionService.CodeAction> forString = serviceWithIndex.actionsFor(
                diag(IProblem.UndefinedType, src, strAt, strAt + 6, "String"), src);
        assertTrue(firstEdits(forString, "quickfix.import.fqn") == null,
                "java.lang.String → jamais proposé");
    }

    // ── Contrat général : aucune action sans édits ─────────────────────

    @Test
    void noActionWithoutRealEdits() {
        String src = "public class Main {\n"
                + "    void m() {\n"
                + "        foo();\n"
                + "    }\n"
                + "}\n";
        int start = src.indexOf("foo");
        List<CodeActionService.CodeAction> actions = textOnlyService.actionsFor(
                diag(IProblem.UndefinedMethod, src, start, start + 3, "foo"), src);
        for (CodeActionService.CodeAction a : actions) {
            assertNotNull(a.edits());
            assertFalse(a.edits().isEmpty(),
                    "contrat : « " + a.title() + " » doit porter des édits réels");
        }
    }
}
