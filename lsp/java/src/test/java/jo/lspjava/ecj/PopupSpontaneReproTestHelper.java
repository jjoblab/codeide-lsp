package jo.lspjava.ecj;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Assistant partagé : construit un R.jar réaliste pour les bancs. */
final class PopupSpontaneReproTestHelper {

    private PopupSpontaneReproTestHelper() {
    }

    /** R.jar « réel » (aapt2-like) : R + R$style + R$id + R$string. */
    static Path buildRJar(Path tmp) throws Exception {
        Path gen = Files.createDirectories(tmp.resolve("genjar/com/example/monip"));
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
                "-d", tmp.resolve("rjar-out").toString(),
                gen.resolve("R.java").toString());
        if (rc != 0) throw new IllegalStateException("javac R.java a échoué");

        Path out = tmp.resolve("r-bench.jar");
        Path classes = tmp.resolve("rjar-out");
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(out))) {
            add(jos, classes, "com/example/monip/R.class");
            add(jos, classes, "com/example/monip/R$style.class");
            add(jos, classes, "com/example/monip/R$id.class");
            add(jos, classes, "com/example/monip/R$string.class");
        }
        return out;
    }

    private static void add(JarOutputStream jos, Path base, String rel)
            throws Exception {
        jos.putNextEntry(new JarEntry(rel));
        Files.copy(base.resolve(rel), jos);
        jos.closeEntry();
    }
}
