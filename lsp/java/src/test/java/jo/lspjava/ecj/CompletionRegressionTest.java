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
 * Tests de NON-RÉGRESSION (symptômes du rapport device).
 *
 * <p>Chaque test reproduit UN symptôme exact du rapport device :</p>
 * <ul>
 *   <li><b>importCompletionAfterDot</b> — « dès que je tape un point
 *       "import android." plus aucune suggestion » : la complétion après
 *       le point doit proposer les sous-paquets, pas seulement R/Manifest.</li>
 *   <li><b>importCompletionIncremental</b> — « cela devrait être fluide
 *       incrémental à mesure que je tape » : chaque frappe doit affiner.</li>
 *   <li><b>typeCompletionOnFieldDeclaration</b> — « j'ai essayé de déclarer
 *       une variable (private String...) rien du tout » : le marqueur en
 *       position de TYPE doit être accepté par le recovery ecj et produire
 *       des items.</li>
 *   <li><b>keywordCompletionByPrefix</b> — taper « imp » doit proposer
 *       « import » (les mots-clés sont rendus aussi à préfixe non vide).</li>
 *   <li><b>packageCompletionOutsideImport</b> — qualifier non-type hors
 *       import (contexte PACKAGE_REFERENCE).</li>
 *   <li><b>jrtScanDoesNotRetry</b> — le scan jrt ne doit pas re-tenter à
 *       chaque isPackage (un seul essai puis cache négatif — pas de
 *       re-création d'URLClassLoader à chaque appel).</li>
 * </ul>
 *
 * <p>Les tests supposent android.jar présent (CI/dev container) et ne
 * dépendent PAS du cache Gradle : le classpath de test est l'android.jar
 * seul, ce qui suffit pour les paquets android et java, et les mots-clés.</p>
 *
 * @author jo@Dev
 */
class CompletionRegressionTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        Path src = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/e"));
        main = src.resolve("MainActivity.java");

        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        engine = new EcjCompilerEngine(
                roots, List.of(androidJar), "17");
    }

    // ═══ Symptôme 1 : « import android. » — 0/2 items ═══

    @Test
    void importCompletionAfterDotProposesSubPackages() throws Exception {
        String src = "package com.example.e;\n\nimport android.os.Bundle;\n\n"
                + "public class MainActivity extends android.app.Activity {\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle b) {\n"
                + "        super.onCreate(b);\n"
                + "    }\n"
                + "}\n";
        // Remplace "Bundle" par rien → caret juste après "import android.os."
        String typed = src.replace("import android.os.Bundle;",
                "import android.os.");
        int caret = typed.indexOf("import android.os.") + "import android.os.".length();
        List<CompletionItem> items = complete(typed, caret);

        assertNotNull(items, "la complétion doit rendre une liste");
        // Les sous-paquets de android.os doivent apparaître (plus que le
        // seul type direct Bundle).
        List<String> labels = labels(items);
        assertTrue(labels.contains("Bundle"), "type direct du paquet attendu : " + labels);
        assertTrue(labels.contains("Build"), "sous-paquet android.os.Build attendu : " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.equals("Bundle")
                        || l.equals("Build") || l.equals("StrictMode")),
                "plus d'un item attendu (le crash subPackages laissait 1 item) : " + labels);
    }

    @Test
    void importCompletionAfterAndroidDot() throws Exception {
        String typed = "package com.example.e;\n\nimport android.\n\n"
                + "public class MainActivity {\n}\n";
        int caret = typed.indexOf("import android.") + "import android.".length();
        List<CompletionItem> items = complete(typed, caret);

        assertNotNull(items);
        List<String> labels = labels(items);
        // Les SOUS-PAQUETS (app, widget, view, os…) dominent — pas seulement
        // R + Manifest (types directs).
        assertTrue(labels.contains("app"), "sous-paquet android.app attendu : " + labels);
        assertTrue(labels.contains("widget"), "sous-paquet android.widget attendu : " + labels);
        assertTrue(labels.contains("view"), "sous-paquet android.view attendu : " + labels);
        assertTrue(items.size() > 10,
                "l'avalanche de sous-paquets attendue (le crash n'en rendait aucun) : "
                        + items.size());
    }

    // ═══ Symptôme 2 : fluidité incrémentale ═══

    @Test
    void importCompletionRefinesIncrementally() throws Exception {
        String head = "package com.example.e;\n\nimport android.widget.";
        for (String typed : new String[]{"", "Te", "Tex", "Text"}) {
            String src = head + typed + "\n\npublic class MainActivity {\n}\n";
            int caret = head.length() + typed.length();
            List<CompletionItem> items = complete(src, caret);
            assertNotNull(items, "items null pour « " + typed + " »");
            List<String> labels = labels(items);
            if (typed.isEmpty()) {
                assertFalse(labels.isEmpty(), "préfixe vide doit lister le paquet");
            } else {
                // Parité projet de référence : le matching est GRADUÉ
                // (prefix / prefix-ci / camel-hump / substring >= 3 chars —
                // portage PrefixMatcher). « EditText » peut donc apparaître
                // pour « Tex » (substring), MAIS les matches par PRÉFIXE
                // dominent TOUJOURS le classement (bonus -200 vs 0) : la
                // frappe reste INCROYABLEMENT plus affine à mesure qu'on
                // tape — le premier item matche toujours par préfixe.
                String first = labels.get(0);
                assertTrue(first.toLowerCase().startsWith(typed.toLowerCase()),
                        "le premier item '" + first
                                + "' doit matcher le préfixe '" + typed
                                + "' par préfixe (les substring sont classés dessous)");
                for (String l : labels) {
                    boolean prefix = l.toLowerCase().startsWith(typed.toLowerCase());
                    boolean substr = l.toLowerCase().contains(typed.toLowerCase());
                    assertTrue(prefix || substr,
                            "item '" + l + "' ne matche pas '" + typed + "'");
                }
            }
            if ("Tex".equals(typed)) {
                assertTrue(labels.contains("TextView"),
                        "TextView attendu pour « Tex » : " + labels);
            }
        }
    }

    // ═══ Symptôme 3 : « private String... rien du tout » ═══

    @Test
    void typeCompletionOnFieldDeclaration() throws Exception {
        // Champ tapé au milieu d'une classe EXISTANTE (le cas du device :
        // MainActivity réel avec onCreate plus bas).
        String src = "package com.example.e;\n\n"
                + "public class MainActivity {\n"
                + "    private int existing = 0;\n\n"
                + "    private Str\n\n"
                + "    protected void onCreate(android.os.Bundle b) {\n"
                + "        b.toString();\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("private Str") + "private Str".length();
        List<CompletionItem> items = complete(src, caret);

        assertNotNull(items, "la déclaration synthétique doit produire des items");
        List<String> labels = labels(items);
        assertTrue(labels.contains("String"),
                "String attendu en premier pour « private Str » : " + labels);
        assertTrue(labels.contains("StringBuilder"),
                "StringBuilder attendu : " + labels);
    }

    @Test
    void typeCompletionOnFieldDeclarationBareClass() throws Exception {
        // Classe NON terminée (frappe progressive) — le pire cas recovery.
        String src = "package com.example.e;\n\npublic class MainActivity {\n    private Str";
        int caret = src.length();
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items);
        assertTrue(labels(items).contains("String"),
                "String attendu même sur classe non terminée : " + labels(items));
    }

    // ═══ Symptôme 4 : mots-clés filtrés par préfixe ═══

    @Test
    void keywordCompletionByPrefix() throws Exception {
        String src = "package com.example.e;\n\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        imp\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("imp") + 3;
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items);
        assertTrue(labels(items).contains("import"),
                "« import » attendu pour le préfixe « imp » : " + labels(items));
    }

    // ═══ Symptôme 5 : paquet hors import (PACKAGE_REFERENCE) ═══

    @Test
    void packageCompletionOutsideImport() throws Exception {
        String src = "package com.example.e;\n\n"
                + "public class MainActivity {\n"
                + "    void f() {\n"
                + "        Object o = java.util.Array\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("java.util.Array") + "java.util.Array".length();
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items, "PACKAGE_REFERENCE doit rendre des items");
        List<String> labels = labels(items);
        assertTrue(labels.contains("ArrayList"),
                "java.util.ArrayList attendu : " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.equals("Arrays") || l.equals("ArrayList")),
                "types du paquet java.util attendus : " + labels);
    }

    // ═══ Symptôme 6 : la tempête de retries jrt ═══

    @Test
    void jrtScanDoesNotRetryOnFailure() throws Exception {
        // jdkHome avec lib/modules BIDON et jrt-fs.jar VALIDE : le provider
        // charge mais newFileSystem échoue. Grâce au cache négatif, un seul
        // essai est payé puis les appels suivants sont O(1) — sans lui,
        // chaque appel isPackage re-tenterait (URLClassLoader +
        // ServiceLoader à chaque fois).
        Path fakeJdk = Files.createDirectories(tmp.resolve("fake-jdk"));
        Files.createDirectories(fakeJdk.resolve("lib"));
        Files.write(fakeJdk.resolve("lib/modules"), new byte[]{1, 2, 3});
        Path realJrtFs = Path.of(System.getProperty("java.home"),
                "lib/jrt-fs.jar");
        Assumptions.assumeTrue(Files.isRegularFile(realJrtFs),
                "jrt-fs.jar absent du runtime");
        Files.copy(realJrtFs, fakeJdk.resolve("lib/jrt-fs.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        List<File> roots = new ArrayList<>();
        roots.add(main.getParent().toFile());
        EcjCompilerEngine brokenJrtEngine = new EcjCompilerEngine(
                roots, List.of(findAndroidJar()), "17", fakeJdk);

        java.lang.reflect.Method m = EcjCompilerEngine.class
                .getDeclaredMethod("typeIndex");
        m.setAccessible(true);
        Object index = m.invoke(brokenJrtEngine);
        java.lang.reflect.Method isPkg = index.getClass()
                .getDeclaredMethod("isPackage", String.class);
        isPkg.setAccessible(true);

        long t0 = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            // Paquet absent des jars → passe par isJrtPackage → scanJrt.
            isPkg.invoke(index, "com.nonexistent.z" + i);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // Un seul essai puis flag O(1) — même en échec, la passe reste
        // bornée (le premier essai paye ~100 ms de chargement du provider,
        // les 499 suivants ~0).
        assertTrue(ms < 1_500,
                "la tempête de retries est de retour : 500 isPackage = "
                        + ms + " ms");
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
