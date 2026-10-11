package jo.lspjava.jdt;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Variante HAUTE FIDÉLITÉ du repro device : le VRAI classpath complet
 * (91 jars + boot, export model.json réel du projet CodeIDE) avec les
 * conditions EXACTES du device :
 *
 * <ul>
 *   <li>sourceRoots = [racine projet, app/src/main/java] — la racine
 *       projet EN PREMIER (comme {@code createConnectionProvider} qui
 *       ajoute le workingDir avant les dirs du modèle) ;</li>
 *   <li>classpath complet de 91+2 jars réels (AGP transforms, R.jar,
 *       androidx…) — si le moteur résout, MainActivity doit être PROPRE
 *       (0 erreur hors R et package-mismatch). Si le device montre 31
 *       diagnostics, ce test doit les reproduire ou les expliquer.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class RealClasspathDeviceReproTest {

    private static final String SOURCE =
            "package com.example.r;\n"
            + "\n"
            + "import android.os.Bundle;\n"
            + "\n"
            + "import androidx.appcompat.app.AppCompatActivity;\n"
            + "\n"
            + "public class MainActivity extends AppCompatActivity {\n"
            + "    @Override\n"
            + "    protected void onCreate(Bundle savedInstanceState) {\n"
            + "        super.onCreate(savedInstanceState);\n"
            + "        setContentView(R.layout.activity_main);\n"
            + "    }\n"
            + "}\n";

    /** Export réel de la session précédente (91 jars + boot + sourceSets). */
    private static final String MODEL_JSON =
            "/home/z/my-project/codeide/CodeIDE_v0.1.0.55-v2.26"
                    + "/app/build/codeide/model.json";

    @TempDir
    Path tmp;

    @Test
    void diagnoseWithRealFullClasspathDeviceConditions() throws Exception {
        File modelFile = new File(MODEL_JSON);
        Assumptions.assumeTrue(modelFile.isFile(),
                "model.json réel absent — repro ignoré");

        // Parse minimal du model.json (org.json indisponible ici : parsing
        // ad hoc des paths de compileClasspath/bootClasspath/javaDirs).
        String json = new String(Files.readAllBytes(modelFile.toPath()),
                StandardCharsets.UTF_8);
        List<File> jars = new ArrayList<>();
        List<File> dirs = new ArrayList<>();

        // compileClasspath (objets {"path":...}) + bootClasspath (array de
        // strings simples) — comme WorkspaceModel.fromProjectModel.
        java.util.regex.Pattern pathPat = java.util.regex.Pattern.compile(
                "\"path\"\\s*:\\s*\"([^\"]+)\"");
        for (String section : new String[]{"compileClasspath"}) {
            int i = json.indexOf("\"" + section + "\"");
            if (i < 0) continue;
            int end = json.indexOf("\"runtimeClasspath\"", i) > 0
                    ? json.indexOf("\"runtimeClasspath\"", i)
                    : json.length();
            if (end < 0) end = json.length();
            String block = json.substring(i, end);
            java.util.regex.Matcher m = pathPat.matcher(block);
            while (m.find()) {
                String p = m.group(1);
                File f = new File(p);
                if (f.isFile() && p.endsWith(".jar") && !jars.contains(f)) {
                    jars.add(f);
                }
            }
        }
        // bootClasspath : ["path1","path2"] — strings nus.
        {
            int i = json.indexOf("\"bootClasspath\"");
            if (i >= 0) {
                int s = json.indexOf('[', i);
                int e = json.indexOf(']', s);
                if (s > 0 && e > s) {
                    for (String q : json.substring(s + 1, e).split(",")) {
                        String d = q.trim().replaceAll("^\"|\"$", "");
                        if (!d.isEmpty()) {
                            File f = new File(d);
                            if (f.isFile() && !jars.contains(f)) jars.add(f);
                        }
                    }
                }
            }
        }

        // javaDirs du sourceSet main seulement (le device n'en a qu'un qui
        // existe) — on prend TOUS les javaDirs existants comme fromProjectModel.
        int i = json.indexOf("\"sourceSets\"");
        if (i >= 0) {
            String block = json.substring(i);
            int pos = 0;
            while (true) {
                int s = block.indexOf("\"javaDirs\": [", pos);
                if (s < 0) break;
                int e = block.indexOf(']', s);
                if (e < 0) break;
                String arr = block.substring(s + 13, e);
                for (String q : arr.split(",")) {
                    String d = q.trim().replaceAll("^\"|\"$", "");
                    if (!d.isEmpty()) {
                        File f = new File(d);
                        if (f.isDirectory() && !dirs.contains(f)) dirs.add(f);
                    }
                }
                pos = e;
            }
        }

        System.out.println("[repro] jars réels: " + jars.size()
                + " (existant filtrés), javaDirs existants: " + dirs.size());
        assertTrue(jars.size() >= 50,
                "classpath réel attendu (>=50 jars), obtenu " + jars.size());

        // Structure device : <root>/app/src/main/java/com/example/r/
        Path projectRoot = tmp.resolve("Rifk");
        Path srcMain = projectRoot.resolve("app/src/main/java");
        Path pkgDir = srcMain.resolve("com/example/r");
        Files.createDirectories(pkgDir);
        Path mainActivity = pkgDir.resolve("MainActivity.java");
        Files.write(mainActivity, SOURCE.getBytes(StandardCharsets.UTF_8));

        // ═══ ORDRE DEVICE : racine projet d'abord ═══
        // (L'app ajoute le workingDir APRÈS les dirs du modèle, mais le
        // moteur DOIT rester correct même dans l'ordre racine-projet
        // d'abord — fqcnFor longest-match — d'où ce test.)
        List<File> deviceRoots = new ArrayList<>();
        deviceRoots.add(projectRoot.toFile());
        deviceRoots.add(srcMain.toFile());

        JdtCompilerEngine engine = new JdtCompilerEngine(
                deviceRoots, jars, "17",
                java.nio.file.Paths.get("/home/z/my-project/opt/jdk-17"));

        // ═══ RÉGRESSION off-by-one ═══
        // Le classpath contient 93 jars dont 2 écartés à l'indexation
        // (lifecycle-viewmodel + kotlin-stdlib-common, 0 .class) AVANT le
        // android.jar du bootClasspath (lazy, en fin de liste) —
        // android/os/Bundle.class doit être cherché dans le BON jar
        // malgré cet écart.
        JdtCompilerEnvironment idx = engine.typeIndex();
        assertNotNull(idx.find("android.os.Bundle"),
                "REGRESSION off-by-one : android.os.Bundle doit résoudre "
                        + "malgré les 2 jars écartés avant android.jar");
        assertNotNull(idx.find("androidx.appcompat.app.AppCompatActivity"),
                "AppCompatActivity doit résoudre (appcompat indexé)");
        assertNotNull(idx.find("java.lang.Object"),
                "java.lang.Object doit résoudre via l'image jrt");
        System.out.println("[repro] sondes: Bundle=OK, AppCompatActivity=OK, "
                + "java.lang.Object=OK");

        long t0 = System.currentTimeMillis();
        List<Diagnostic> diags;
        try {
            diags = engine.diagnose(mainActivity.toString(), SOURCE);
        } catch (Throwable e) {
            System.out.println("[repro] diagnose EXCEPTION " + e);
            throw new RuntimeException(e);
        }
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[repro] diagnostics: " + diags.size()
                + " en " + elapsed + " ms");
        for (Diagnostic d : diags) {
            int line = 0;
            for (int k = 0; k < Math.min(d.range().startOffset(),
                    SOURCE.length()); k++) {
                if (SOURCE.charAt(k) == '\n') line++;
            }
            System.out.println("   L" + line + " [" + d.severity() + "] "
                    + d.message().replace('\n', ' '));
        }

        // ═══ RÉGRESSIONS dures ═══
        // 1. Le classpath Android DOIT être consommé : plus aucune erreur
        //    « cannot be resolved » sur android.*/appcompat (le seul reliquat
        //    autorisé : R avant premier build — c'est le downgrade honnête).
        for (Diagnostic d : diags) {
            String msg = d.message() == null ? "" : d.message();
            assertFalse(msg.contains("android.os.Bundle cannot be resolved"),
                    "REGRESSION off-by-one : " + msg);
            assertFalse(msg.contains(
                    "androidx.appcompat.app.AppCompatActivity cannot be resolved"),
                    "REGRESSION off-by-one : " + msg);
            assertFalse(msg.contains("does not match the expected package"),
                    "REGRESSION FQCN (racine projet en premier) : " + msg);
        }
        // 2. Le bruit total doit s'effondrer : ≤ 2 diagnostics (R seul,
        //    éventuellement + sa cascade directe) — un mur de ~31
        //    diagnostics serait le symptôme du bug.
        assertTrue(diags.size() <= 2,
                "un MainActivity propre avec classpath complet ne doit plus "
                        + "produire un mur de diagnostics : " + diags.size());
    }
}
