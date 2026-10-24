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

/**
 * Mesure de latence de complétion à chaud — le scénario du retour
 * propriétaire « les suggestions de complétion sont devenues lentes ».
 *
 * <p>Reproduit un environnement de module RÉALISTE : plusieurs racines
 * sources (java + generated), un projet multi-classes (la classe focale
 * référence d'autres classes du module), android.jar + un R.jar.</p>
 */
class LatenceCompletionBenchmark {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        // Module réaliste : src/main/java + src/generated (2 racines),
        // 12 classes référencées + la classe focale.
        Path src = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/monip"));
        Path gen = Files.createDirectories(
                tmp.resolve("app/src/generated/java/com/example/monip"));

        for (int i = 0; i < 12; i++) {
            Files.writeString(src.resolve("Helper" + i + ".java"), """
                    package com.example.monip;
                    public class Helper%d {
                        public int valeur%d() { return %d; }
                        public String nom%d() { return "h%d"; }
                    }
                    """.formatted(i, i, i, i, i), StandardCharsets.UTF_8);
        }
        Files.writeString(src.resolve("Modele.java"), """
                package com.example.monip;
                public record Modele(String titre, int poids) {}
                """, StandardCharsets.UTF_8);
        Files.writeString(gen.resolve("ActivityMainBinding.java"), """
                package com.example.monip;
                public final class ActivityMainBinding {
                    public static ActivityMainBinding inflate(android.view.LayoutInflater i) {
                        return new ActivityMainBinding();
                    }
                    public android.widget.TextView titre;
                }
                """, StandardCharsets.UTF_8);
        main = src.resolve("MainActivity.java");

        // R.jar réaliste.
        Path rJar = PopupSpontaneReproTestHelper.buildRJar(tmp);

        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        roots.add(gen.toFile());
        engine = new EcjCompilerEngine(
                roots, List.of(androidJar, rJar.toFile()), "17");
    }

    @Test
    void latenceCompletionMembre() throws Exception {
        String source = "package com.example.monip;\n"
                + "\n"
                + "import android.os.Bundle;\n"
                + "import android.widget.TextView;\n"
                + "\n"
                + "public class MainActivity extends android.app.Activity {\n"
                + "    private Modele modele = new Modele(\"x\", 1);\n"
                + "    private Helper0 h0 = new Helper0();\n"
                + "    private Helper1 h1 = new Helper1();\n"
                + "    private TextView vue;\n"
                + "    @Override\n"
                + "    public void onCreate(Bundle b) {\n"
                + "        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());\n"
                + "        binding.titre.setText(modele.titre());\n"
                + "        int total = h0.valeur0() + h1.valeur1();\n"
                + "        setTitle(\"\" + total);\n"
                + "        String s = binding.titre.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("binding.titre.") + "binding.titre.".length();

        // Échauffement (index, caches).
        new EcjCompletionEngine(engine).complete(main.toString(), source, caret);

        // 10 mesures à chaud.
        long[] latences = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            List<CompletionItem> items =
                    new EcjCompletionEngine(engine).complete(main.toString(), source, caret);
            latences[i] = (System.nanoTime() - t0) / 1_000_000;
            if (i == 0) {
                System.out.println("items=" + (items == null ? -1 : items.size()));
            }
        }
        System.out.print("latences (ms) membre: ");
        for (long l : latences) System.out.print(l + " ");
        System.out.println();
    }

    @Test
    void latenceCompletionType() throws Exception {
        String source = "package com.example.monip;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        TextVi\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("TextVi") + "TextVi".length();

        new EcjCompletionEngine(engine).complete(main.toString(), source, caret);

        long[] latences = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            List<CompletionItem> items =
                    new EcjCompletionEngine(engine).complete(main.toString(), source, caret);
            latences[i] = (System.nanoTime() - t0) / 1_000_000;
            if (i == 0) {
                System.out.println("items type=" + (items == null ? -1 : items.size()));
            }
        }
        System.out.print("latences (ms) type: ");
        for (long l : latences) System.out.print(l + " ");
        System.out.println();
    }

    @Test
    void latenceCompletionVide() throws Exception {
        // Le pire cas du popup spontané : préfixe VIDE après espace.
        String source = "package com.example.monip;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        int x = 5;\n"
                + "        \n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("int x = 5;") + "int x = 5; ".length();

        new EcjCompletionEngine(engine).complete(main.toString(), source, caret);

        long[] latences = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            List<CompletionItem> items =
                    new EcjCompletionEngine(engine).complete(main.toString(), source, caret);
            latences[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.print("latences (ms) vide: ");
        for (long l : latences) System.out.print(l + " ");
        System.out.println();
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/android-sdk/platforms/android-37.2/android.jar",
                "/home/z/tools/android-sdk/platforms/android-37.2/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
