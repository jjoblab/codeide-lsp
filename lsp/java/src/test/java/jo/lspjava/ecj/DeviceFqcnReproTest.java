package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jo.lspjava.api.CompletionItem;
import jo.lspjava.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repro des conditions EXACTES observées sur device (log device) :
 *
 * <p>Sur mobile, {@code JavaLanguageServerDefinition.createConnectionProvider}
 * construit l'environnement avec sourceDirs = [workingDir (racine projet),
 * app/src/main/java] — LA RACINE PROJET EN PREMIER. Or {@code fqcnFor()}
 * itère les racines DANS L'ORDRE : la racine projet matche TOUT fichier du
 * projet AVANT le vrai source set → FQCN = {@code
 * app.src.main.java.com.example.r.MainActivity} (segmenté par les SLASH du
 * chemin relatif) au lieu de {@code com.example.r.MainActivity}.</p>
 *
 * <p>Symptômes device : 31-32 diagnostics sur un MainActivity de 349 chars
 * (22 sur la ligne de déclaration de classe), complétion « got 0 items »,
 * impression que « le LSP ne consomme pas les classpaths ».</p>
 *
 * <p>Ce test reproduit : diagnostics avec l'ordre device (racine projet
 * d'abord) vs l'ordre corrigé (vrais source sets d'abord). Il imprime TOUT
 * ce qu'il observe et n'échoue que sur les invariants forts.</p>
 *
 * @author jo@Dev
 */
class DeviceFqcnReproTest {

    /** Modèle du fichier device — 349 chars, classe déclarée ligne 6. */
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

    @TempDir
    Path tmp;

    private static File androidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/opt/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-34/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Extrait le premier classes.jar du premier *.aar trouvé sous group. */
    private static File extractClassesJar(String gradleCacheGroup, String group,
                                          String artifact, Path out)
            throws Exception {
        File root = new File(gradleCacheGroup, group + "/" + artifact);
        if (!root.isDirectory()) return null;
        File[] versions = root.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) return null;
        for (File version : versions) {
            File[] hashes = version.listFiles(File::isDirectory);
            if (hashes == null) continue;
            for (File hashDir : hashes) {
                File[] files = hashDir.listFiles(f -> f.getName().endsWith(".aar"));
                if (files == null || files.length == 0) continue;
                File aar = files[0];
                try (ZipFile zip = new ZipFile(aar)) {
                    Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (!"classes.jar".equals(e.getName())) continue;
                        Path target = out.resolve(artifact + "-classes.jar");
                        if (Files.exists(target)) return target.toFile();
                        try (InputStream in = zip.getInputStream(e)) {
                            Files.copy(in, target);
                        }
                        return target.toFile();
                    }
                }
            }
        }
        return null;
    }

    private static void printDiagnostics(String label, List<Diagnostic> diags,
                                         String source) {
        System.out.println("[" + label + "] diagnostics: " + diags.size());
        for (Diagnostic d : diags) {
            int line = 0;
            for (int i = 0; i < Math.min(d.range().startOffset(), source.length()); i++) {
                if (source.charAt(i) == '\n') line++;
            }
            System.out.println("   L" + line + " [" + d.severity() + "] "
                    + d.message().replace('\n', ' '));
        }
    }

    private static void printCompletion(String label, String path,
                                        String source, int caret,
                                        EcjCompilerEngine engine) {
        EcjCompletionEngine ce = new EcjCompletionEngine(engine);
        String[] trace = new String[1];
        List<CompletionItem> items;
        try {
            items = ce.complete(path, source, caret, trace);
        } catch (Exception e) {
            System.out.println("[" + label + "] completion EXCEPTION " + e);
            return;
        }
        int n = items == null ? -1 : items.size();
        System.out.println("[" + label + "] completion → "
                + (items == null ? "null" : n + " items"));
        if (trace[0] != null && !trace[0].isBlank()) {
            System.out.println("   trace: "
                    + trace[0].replace('\n', '¶'));
        }
        if (items != null) {
            for (int i = 0; i < Math.min(6, items.size()); i++) {
                System.out.println("     · " + items.get(i).label()
                        + " (" + items.get(i).kind() + ")");
            }
        }
    }

    @Test
    void reproDeviceSourceRootOrdering() throws Exception {
        String gradleCache = System.getProperty("user.home")
                + "/.gradle/caches/modules-2/files-2.1";
        Assumptions.assumeTrue(new File(gradleCache).isDirectory(),
                "cache Gradle absent — repro ignoré");

        File androidJar = androidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        Path extract = Files.createDirectories(tmp.resolve("extract"));
        File appcompat = extractClassesJar(gradleCache,
                "androidx.appcompat", "appcompat", extract);
        Assumptions.assumeTrue(appcompat != null && appcompat.isFile(),
                "appcompat AAR absent du cache");
        File core = extractClassesJar(gradleCache,
                "androidx.core", "core", extract);

        // Structure projet device : <root>/app/src/main/java/com/example/r/
        Path projectRoot = tmp.resolve("Rifk");
        Path srcMain = projectRoot.resolve("app/src/main/java");
        Path pkgDir = srcMain.resolve("com/example/r");
        Files.createDirectories(pkgDir);
        Path mainActivity = pkgDir.resolve("MainActivity.java");
        Files.write(mainActivity, SOURCE.getBytes(StandardCharsets.UTF_8));
        // La racine projet ressemble à un projet (fichiers Gradle présents).
        Files.write(projectRoot.resolve("settings.gradle"),
                "include ':app'\n".getBytes(StandardCharsets.UTF_8));

        List<File> classpathJars = new ArrayList<>();
        classpathJars.add(androidJar);
        classpathJars.add(appcompat);
        if (core != null && core.isFile()) classpathJars.add(core);

        String path = mainActivity.toString();

        // ═══ 1) ORDRE DEVICE : racine projet EN PREMIER ═══
        List<File> deviceRoots = new ArrayList<>();
        deviceRoots.add(projectRoot.toFile());      // workingDir — AJOUTÉ D'ABORD
        deviceRoots.add(srcMain.toFile());          // dir du modèle — ensuite

        EcjCompilerEngine deviceEngine = new EcjCompilerEngine(
                deviceRoots, classpathJars, "17");
        List<Diagnostic> deviceDiags;
        try {
            deviceDiags = deviceEngine.diagnose(path, SOURCE);
        } catch (Throwable e) {
            System.out.println("[device-order] diagnose EXCEPTION " + e);
            throw new RuntimeException(e);
        }
        printDiagnostics("device-order (root first)", deviceDiags, SOURCE);

        // ═══ 2) ORDRE CORRIGÉ : vrais source sets d'abord ═══
        List<File> fixedRoots = new ArrayList<>();
        fixedRoots.add(srcMain.toFile());

        EcjCompilerEngine fixedEngine = new EcjCompilerEngine(
                fixedRoots, classpathJars, "17");
        List<Diagnostic> fixedDiags;
        try {
            fixedDiags = fixedEngine.diagnose(path, SOURCE);
        } catch (Throwable e) {
            System.out.println("[fixed-order] diagnose EXCEPTION " + e);
            throw new RuntimeException(e);
        }
        printDiagnostics("fixed-order (src first)", fixedDiags, SOURCE);

        System.out.println("[verdict] device-order=" + deviceDiags.size()
                + " diag(s), fixed-order=" + fixedDiags.size()
                + " diag(s) — écart = "
                + (deviceDiags.size() - fixedDiags.size()));

        // Complétions aux mêmes positions dans les DEUX configurations.
        // a) membre : « b. » sur une locale Bundle.
        String srcMember = SOURCE.replace(
                "setContentView(R.layout.activity_main);",
                "Bundle b = savedInstanceState;\n        b.");
        int memberCaret = srcMember.indexOf("\n        b.") + "\n        b.".length();
        printCompletion("device-order B MEMBER b.", path, srcMember,
                memberCaret, deviceEngine);
        printCompletion("fixed-order  B MEMBER b.", path, srcMember,
                memberCaret, fixedEngine);

        // b) nom simple : « alp » locale.
        String srcName = SOURCE.replace(
                "super.onCreate(savedInstanceState);",
                "String alpha;\n        alp");
        int nameCaret = srcName.indexOf("alp") + 3;
        printCompletion("device-order D NAME alp", path, srcName,
                nameCaret, deviceEngine);
        printCompletion("fixed-order  D NAME alp", path, srcName,
                nameCaret, fixedEngine);

        // Invariant : l'ordre corrigé doit produire STRICTEMENT MOINS de
        // bruit que l'ordre device si le bug FQCN est réel.
        assertTrue(fixedDiags.size() <= deviceDiags.size(),
                "l'ordre corrigé devrait réduire le bruit: fixed="
                        + fixedDiags.size() + " device=" + deviceDiags.size());

        // RÉGRESSION FQCN : même dans l'ordre historique (racine projet
        // EN PREMIER — comme le faisait createConnectionProvider), le
        // moteur (fqcnFor longest-match) ne doit PAS produire
        // « The declared package ... does not match the expected package ».
        for (Diagnostic d : deviceDiags) {
            assertFalse(
                    d.message() != null
                            && d.message().contains(
                                    "does not match the expected package"),
                    "REGRESSION FQCN longest-match (ordre racine-projet "
                            + "d'abord) : " + d.message());
        }
    }
}
