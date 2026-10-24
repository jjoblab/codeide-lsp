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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import jo.lspjava.api.CompletionItem;

/**
 * Reproduction du retour propriétaire : popup de complétion spontané juste
 * après une déclaration de variable / un retour à la ligne, avec des
 * suggestions « R$style / R$id / R$string ».
 */
class PopupSpontaneReproTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        // Source projet : com/example/monip/MainActivity.java
        Path src = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/monip"));
        main = src.resolve("MainActivity.java");

        // R.jar « réel » (aapt2-like) : R + R$style + R$id + R$string.
        Path rJar = buildRJar(tmp.resolve("r.jar"));

        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        engine = new EcjCompilerEngine(
                roots, List.of(androidJar, rJar.toFile()), "17");
    }

    @Test
    void popupApresDeclarationVariable() throws Exception {
        String src = "package com.example.monip;\n"
                + "\n"
                + "import android.os.Bundle;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        int x = 5;\n"
                + "        \n"
                + "    }\n"
                + "}\n";
        // Caret : juste après l'espace qui suit le « ; » de la déclaration.
        int caret = src.indexOf("int x = 5;") + "int x = 5; ".length();
        List<CompletionItem> items = complete(src, caret);
        dump("APRÈS DÉCLARATION + ESPACE", items);
    }

    @Test
    void popupApresRetourLigne() throws Exception {
        String src = "package com.example.monip;\n"
                + "\n"
                + "import android.os.Bundle;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        int x = 5;\n"
                + "        \n"
                + "    }\n"
                + "}\n";
        // Caret : sur la ligne vide (après l'indentation).
        int caret = src.indexOf("        \n") + 8;
        List<CompletionItem> items = complete(src, caret);
        dump("APRÈS RETOUR LIGNE", items);
    }

    @Test
    void popupApresEspaceDansDeclaration() throws Exception {
        String src = "package com.example.monip;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        String nom \n"
                + "    }\n"
                + "}\n";
        // Caret : après « String nom » + espace (déclaration en cours).
        int caret = src.indexOf("String nom ") + "String nom ".length();
        List<CompletionItem> items = complete(src, caret);
        dump("DANS DÉCLARATION + ESPACE", items);
    }

    @Test
    void importApresPaquetProjet() throws Exception {
        // Le contexte import sur le paquet du projet : les R$* fuient-ils ?
        String src = "package com.example.monip;\n"
                + "\n"
                + "import com.example.monip.\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        int caret = src.indexOf("import com.example.monip.")
                + "import com.example.monip.".length();
        List<CompletionItem> items = complete(src, caret);
        dump("IMPORT com.example.monip.|", items);
    }

    // ─── Harnais ─────────────────────────────────────────────────────

    private List<CompletionItem> complete(String source, int caret) {
        String[] trace = new String[1];
        List<CompletionItem> items = new EcjCompletionEngine(engine)
                .complete(main.toString(), source, caret, trace);
        System.out.println("┌── TRACE ──");
        System.out.println(trace[0]);
        System.out.println("└── " + (items == null ? -1 : items.size()) + " items");
        return items;
    }

    private static void dump(String titre, List<CompletionItem> items) {
        System.out.println("════ " + titre + " ════");
        if (items == null) {
            System.out.println("  (null)");
            return;
        }
        for (CompletionItem it : items) {
            System.out.println("  - label='" + it.label() + "' kind=" + it.kind()
                    + " detail='" + it.detail() + "' insert='" + it.insertText()
                    + "' autoImport='" + it.autoImportFqn() + "'");
        }
    }

    /** Construit un R.jar réaliste (R + classes internes R$style/R$id/R$string). */
    private static Path buildRJar(Path out) throws Exception {
        Path gen = Files.createDirectories(tmp.resolve("gen/com/example/monip"));
        Files.writeString(gen.resolve("R.java"), """
                package com.example.monip;
                public final class R {
                    public static final class style {}
                    public static final class id {}
                    public static final class string {}
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-source", "17", "-target", "17",
                "-d", tmp.resolve("r-out").toString(),
                gen.resolve("R.java").toString());
        if (rc != 0) throw new IllegalStateException("javac R.java a échoué");

        Path classes = tmp.resolve("r-out");
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(out))) {
            addJarEntry(jos, classes, "com/example/monip/R.class");
            addJarEntry(jos, classes, "com/example/monip/R$style.class");
            addJarEntry(jos, classes, "com/example/monip/R$id.class");
            addJarEntry(jos, classes, "com/example/monip/R$string.class");
        }
        return out;
    }

    private static void addJarEntry(JarOutputStream jos, Path base, String rel)
            throws Exception {
        jos.putNextEntry(new JarEntry(rel));
        Files.copy(base.resolve(rel), jos);
        jos.closeEntry();
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/android-sdk/platforms/android-37.2/android.jar",
                "/home/z/tools/android-sdk/platforms/android-37.2/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-37.2/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
