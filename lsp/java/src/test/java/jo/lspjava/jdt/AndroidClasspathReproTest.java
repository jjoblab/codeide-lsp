package jo.lspjava.jdt;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jo.lspjava.api.CompletionItem;
import jo.lspjava.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduit la condition mobile (log device) :
 * MainActivity 349 chars, classpath = android.jar + appcompat classes.jar
 * (extrait d'AAR — comme AndroidProjectClasspathResolver), PAS de R.jar
 * (pas de build préalable). Attendu : ~10 diagnostics comme sur l'appareil
 * et UN diagnostic ferme de complétion (items > 0 pour les contextes liés).
 *
 * <p>Test de DIAGNOSTIC : il affiche tout ce qu'il observe (stdout) et ne
 * peut échouer que sur les invariants forts (index vide, complétion nulle
 * sur contexte member-access résolvable).</p>
 *
 * @author jo@Dev
 */
class AndroidClasspathReproTest {

    private static final String SOURCE =
            "package com.example.d;\n"
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

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-35/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Extrait le premier classes.jar du premier *.aar trouvé sous group. */
    private static File extractClassesJar(String gradleCacheGroup, String group,
                                          String artifact, @TempDir Path out)
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
                // Extrait classes.jar dans out.
                try (ZipFile zip = new ZipFile(aar)) {
                    Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (!"classes.jar".equals(e.getName())) continue;
                        Path target = out.resolve(artifact + "-classes.jar");
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

    private static File firstJarUnder(String gradleCacheGroup, String group,
                                      String artifact) {
        File root = new File(gradleCacheGroup, group + "/" + artifact);
        if (!root.isDirectory()) return null;
        File[] found = root.listFiles(java.io.File::isDirectory);
        if (found == null) return null;
        for (File v : found) {
            List<File> jars = new ArrayList<>();
            collectJars(v, jars);
            if (!jars.isEmpty()) return jars.get(0);
        }
        return null;
    }

    private static void collectJars(File f, List<File> out) {
        if (f.isFile() && f.getName().endsWith(".jar")) { out.add(f); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) collectJars(k, out);
    }

    /** Identique aux splices construits par JdtCompletionEngine.complete(). */
    private static final String MARKER = "__codeide_completion__";

    private static List<String> spliceVariants(String src, int caret) {
        String prefix = identifierPrefix(src, caret);
        int replaceStart = caret - prefix.length();
        boolean afterDot = replaceStart > 0 && src.charAt(replaceStart - 1) == '.';
        List<String> out = new ArrayList<>();
        out.add(src.substring(0, replaceStart) + MARKER + src.substring(caret));
        out.add(src.substring(0, replaceStart) + MARKER + ";" + src.substring(caret));
        if (afterDot) {
            String token = MARKER + "()";
            out.add(src.substring(0, replaceStart) + token + src.substring(caret));
            out.add(src.substring(0, replaceStart) + token + ";"
                    + src.substring(caret));
        }
        return out;
    }

    private static String identifierPrefix(String s, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(s.charAt(start - 1))) {
            start--;
        }
        return s.substring(start, end);
    }

    /** Passe chaque étape de complete() sous la loupe (avec
     *  consommation du suffixe — identique au code de production). */
    private void stageDiagnosis(JdtCompilerEngine engine, String label,
                                String path, String source, int caret) {
        System.out.println("[stage] ===== " + label + " caret=" + caret);
        String prefix = identifierPrefix(source, caret);
        int replaceStart = caret - prefix.length();
        int replaceEnd = caret;
        while (replaceEnd < source.length()
                && Character.isJavaIdentifierPart(source.charAt(replaceEnd))) {
            replaceEnd++;
        }
        List<String> vars = new ArrayList<>();
        vars.add(source.substring(0, replaceStart) + MARKER
                + source.substring(replaceEnd));
        vars.add(source.substring(0, replaceStart) + MARKER + ";"
                + source.substring(replaceEnd));
        if (replaceStart > 0 && source.charAt(replaceStart - 1) == '.') {
            vars.add(source.substring(0, replaceStart) + MARKER + "()"
                    + source.substring(replaceEnd));
        }
        int v = 0;
        for (String spliced : vars) {
            org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration cud =
                    engine.resolveUnit(path, spliced, false);
            if (cud == null) { System.out.println("  #" + v + " cud=NULL"); v++; continue; }
            int types = cud.types == null ? -1 : cud.types.length;
            String[] tr = new String[1];
            JdtCompletionEngine.Context ctx =
                    JdtCompletionEngine.analyzeContext(cud, replaceStart, tr, spliced);
            // Compteur INDÉPENDANT : est-ce que ecj visite AU MOINSS le type ?
            java.util.concurrent.atomic.AtomicInteger cType =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger cCud =
                    new java.util.concurrent.atomic.AtomicInteger();
            org.eclipse.jdt.internal.compiler.ASTVisitor counter =
                    new org.eclipse.jdt.internal.compiler.ASTVisitor() {
                @Override public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration u,
                        org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
                    cCud.incrementAndGet(); return true;
                }
                @Override public boolean visit(
                        org.eclipse.jdt.internal.compiler.ast.TypeDeclaration td,
                        org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
                    cType.incrementAndGet(); return true;
                }
            };
            Exception travEx = null;
            try { cud.traverse(counter, cud.scope); }
            catch (Exception e) { travEx = e; }
            System.out.println("  #" + v + " types=" + types
                    + " scope=" + (cud.scope != null)
                    + " ctx=" + summarize(ctx)
                    + " | rawCount[cud=" + cCud.get() + " type=" + cType.get()
                    + "]" + (travEx != null ? " TRAV-EX=" + travEx : ""));
            System.out.println("     trace=" + tr[0]);
            v++;
        }
    }

    private static String summarize(JdtCompletionEngine.Context ctx) {
        if (ctx == null) return "NULL";
        StringBuilder sb = new StringBuilder("{");
        sb.append("import=").append(ctx.importStatement);
        if (ctx.importStatement) sb.append(" path='").append(ctx.importPath).append('\'');
        sb.append(", member=").append(ctx.memberAccess);
        sb.append(", type=").append(ctx.type != null
                ? String.valueOf(ctx.type.readableName()) : "null");
        return sb.append('}').toString();
    }

    @Test
    void reproDeviceScenario() throws Exception {
        String gradleCache = System.getProperty("user.home")
                + "/.gradle/caches/modules-2/files-2.1";
        Assumptions.assumeTrue(new File(gradleCache).isDirectory(),
                "cache Gradle absent — repro ignoré");

        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        Path extract = Files.createDirectories(tmp.resolve("extract"));
        File appcompat = extractClassesJar(gradleCache,
                "androidx.appcompat", "appcompat", extract);
        Assumptions.assumeTrue(appcompat != null && appcompat.isFile(),
                "appcompat AAR absent du cache");
        File core = extractClassesJar(gradleCache,
                "androidx.core", "core", extract);

        // Racine source avec le fichier (comme un vrai module).
        Path srcRoot = tmp.resolve("java");
        Path pkg = srcRoot.resolve("com/example/d");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("MainActivity.java"),
                SOURCE.getBytes(StandardCharsets.UTF_8));

        List<File> sourceRoots = new ArrayList<>();
        sourceRoots.add(srcRoot.toFile());
        List<File> classpathJars = new ArrayList<>();
        classpathJars.add(androidJar);
        classpathJars.add(appcompat);
        if (core != null) classpathJars.add(core);
        File annotation = firstJarUnder(gradleCache,
                "androidx.annotation", "annotation");
        if (annotation != null) classpathJars.add(annotation);

        System.out.println("[repro] classpath:");
        for (File f : classpathJars) {
            System.out.println("   - " + f + " (" + f.length() / 1024 + " KB)");
        }

        // ── Santé de l'index (via les accès debug).
        List<Path> srcRootsP = new ArrayList<>();
        srcRootsP.add(srcRoot);
        List<Path> jarsP = new ArrayList<>();
        for (File f : classpathJars) jarsP.add(f.toPath());
        JdtCompilerEnvironment envProbe = new JdtCompilerEnvironment(
                srcRootsP, jarsP, Paths.get(System.getProperty("java.home")));
        System.out.println("[repro] hasBinarySource=" + envProbe.hasBinarySource());
        System.out.println("[repro] indexés=" + envProbe.debugIndexedJarCount()
                + " paquets=" + envProbe.debugBinaryPackageCount()
                + " types=" + envProbe.debugSimpleNameCount());
        System.out.println("[repro] rapport par jar:\n"
                + envProbe.debugJarIndexReport());
        assertTrue(envProbe.hasBinarySource(), "index binaire vide !");
        assertTrue(envProbe.find("androidx.appcompat.app.AppCompatActivity") != null,
                "AppCompatActivity non résolu par l'index !");
        assertTrue(envProbe.find("android.os.Bundle") != null,
                "Bundle non résolu par l'index !");

        // ── Diagnostics (doit refléter les ~10 erreurs du device —
        //    essentiellement R non résolu avant 1er build).
        JdtCompilerEngine engine = new JdtCompilerEngine(
                sourceRoots, classpathJars, "17");
        List<Diagnostic> diags;
        try {
            diags = engine.diagnose(
                    srcRoot.resolve("com/example/d/MainActivity.java").toString(),
                    SOURCE);
        } catch (Throwable e) {
            System.out.println("[repro] diagnose EXCEPTION " + e);
            throw new RuntimeException(e);
        }
        System.out.println("[repro] diagnostics: " + diags.size());
        for (Diagnostic d : diags) {
            System.out.println("   [" + d.severity() + "] "
                    + d.message().replace('\n', ' '));
        }

        // ── Complétions aux positions RÉALISTES (construites exactement).
        String path = srcRoot.resolve("com/example/d/MainActivity.java").toString();

        // A) Import : caret juste après le dernier point du chemin.
        int impDot = SOURCE.indexOf("androidx.appcompat.app.")
                + "androidx.appcompat.app.".length();
        printCompletion(engine, "A IMPORT après dernier '.'", path,
                SOURCE, impDot);

        // B) Member access vide : « b. » sur une variable Bundle locale.
        String srcMember = SOURCE.replace(
                "setContentView(R.layout.activity_main);",
                "Bundle b = savedInstanceState;\n        b.");
        int memberCaret = srcMember.indexOf("\n        b.") + "\n        b.".length();
        printCompletion(engine, "B MEMBER 'b.' (Bundle)", path,
                srcMember, memberCaret);

        // C) Mid-word : complétion à l'intérieur d'un identifiant.
        String srcMid = SOURCE.replace("activity_main;",
                "activity_mai__;"); // dummy pour garde-fou remplacement unique
        String srcMid2 = SOURCE.replace("R.layout.activity_main;",
                "R.layout.activity_ma();"); // fallback
        String srcWord = SOURCE.replace("setContentView(R.layout.activity_main);",
                "setContentView(R.layout.activity_mainx);");
        int wordCaret = srcWord.indexOf("activity_main") + "activity_mai".length();
        printCompletion(engine, "C MID-WORD 'activity_mai|x'", path,
                srcWord, wordCaret);

        // D) Contexte nom simple : début d'instruction.
        String srcName = SOURCE.replace(
                "super.onCreate(savedInstanceState);",
                "String alpha;\n        alp");
        int nameCaret = srcName.indexOf("alp") + 3;
        printCompletion(engine, "D NAME 'alp' (locale)", path,
                srcName, nameCaret);

        // Étape par étape : le CUD splicé contient-il encore des types ?
        stageDiagnosis(engine, "D NAME", path, srcName, nameCaret);
        stageDiagnosis(engine, "B MEMBER", path, srcMember, memberCaret);

        // ── DISCRIMINATEUR : quel ingrédient casse la traversée ?
        String control = """
                package demo;

                public class Main {
                    public int test(String s) {
                        return s.le
                    }
                }
                """;
        int ctrlCaret = control.indexOf("s.le") + "s.le".length();
        JdtCompilerEngine plain = new JdtCompilerEngine(
                new ArrayList<>(), new ArrayList<>(), "17");
        printCompletion(plain, "T1 plain-vide (CI-like)", "/demo/Main.java",
                control, ctrlCaret);
        JdtCompilerEngine withJars = new JdtCompilerEngine(
                new ArrayList<>(), classpathJars, "17");
        printCompletion(withJars, "T2 +jars seulement", "/demo/Main.java",
                control, ctrlCaret);
        JdtCompilerEngine withRoots = new JdtCompilerEngine(
                sourceRoots, new ArrayList<>(), "17");
        printCompletion(withRoots, "T3 +sourceRoots seulement (demo)",
                "/demo/Main.java", control, ctrlCaret);
        printCompletion(withRoots, "T3b +sourceRoots (MainAct, D NAME)",
                path, srcName, nameCaret);
    }

    private static void printCompletion(JdtCompilerEngine engine, String label,
                                        String root, String source, int caret) {
        JdtCompletionEngine ce = new JdtCompletionEngine(engine);
        String[] trace = new String[1];
        List<CompletionItem> items;
        try {
            items = ce.complete(root + "/MainActivity.java", source, caret, trace);
        } catch (Exception e) {
            System.out.println("[repro] completion " + label
                    + " → EXCEPTION " + e);
            return;
        }
        int n = items == null ? -1 : items.size();
        System.out.println("[repro] completion " + label + " → "
                + (items == null ? "null" : n + " items")
                + " | trace=" + trace[0]);
        if (items != null) {
            for (int i = 0; i < Math.min(8, items.size()); i++) {
                System.out.println("     · " + items.get(i).label()
                        + " (" + items.get(i).kind() + ")");
            }
        }
    }
}
