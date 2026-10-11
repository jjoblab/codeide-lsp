package jo.lspjava.jdt;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.api.CompletionItem;

/**
 * Diagnostic du filtre subpackage (« ni plus ni moins »).
 *
 * <p>Dump EXACT de ce que propose le moteur pour taper {@code import android.},
 * {@code import android.c}, {@code import j}, {@code import java.util.f}…
 * afin de comparer aux règles d'or de packageChildren (projet de référence) :</p>
 * <ol>
 *   <li>enfants DIRECTS uniquement (premier segment après q.)</li>
 *   <li>dédoublonnés par segment</li>
 *   <li>préfixe vide liste quand même les sous-paquets</li>
 *   <li>types DIRECTEMENT dans q, sans auto-import</li>
 *   <li>insertion des packages = segment simple</li>
 * </ol>
 */
class SubpackageDiagnosticTest {

    @TempDir
    static Path tmp;

    static JdtCompilerEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");
        Path src = Files.createDirectories(tmp.resolve("src/com/example/e"));
        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        engine = new JdtCompilerEngine(roots, List.of(androidJar), "17");
    }

    private List<CompletionItem> complete(String src, int caret) {
        return new JdtCompletionEngine(engine).complete("/src/com/example/e/Main.java", src, caret);
    }

    private void dump(String title, String head, String typed) {
        String src = "package com.example.e;\n\n" + head + typed
                + "\n\npublic class Main {\n}\n";
        int caret = ("package com.example.e;\n\n" + head).length() + typed.length();
        List<CompletionItem> items = complete(src, caret);
        System.out.println("════ " + title + " ════");
        if (items == null) {
            System.out.println("  (null)");
            return;
        }
        System.out.println("  " + items.size() + " items");
        int shown = 0;
        for (CompletionItem it : items) {
            System.out.println("   - [" + it.kind() + "] '" + it.label()
                    + "' insert='" + it.insertText() + "' detail='"
                    + it.detail() + "' rel=" + it.relevance()
                    + (it.autoImportFqn() != null ? " IMPORT:" + it.autoImportFqn() : ""));
            if (++shown > 120) { System.out.println("   …"); break; }
        }
    }

    @Test
    void dumpImportAndroid() {
        dump("import android.", "import android.", "");
    }

    @Test
    void dumpImportAndroidC() {
        dump("import android.c", "import android.c", "");
    }

    @Test
    void dumpImportJ() {
        dump("import j", "import j", "");
    }

    @Test
    void dumpImportJavaUtilF() {
        dump("import java.util.f", "import java.util.f", "");
    }

    @Test
    void dumpImportJavaUtilDot() {
        dump("import java.util.", "import java.util.", "");
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
