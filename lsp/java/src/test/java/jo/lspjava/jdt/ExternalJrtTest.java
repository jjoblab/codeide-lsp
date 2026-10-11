package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du jrt <strong>externe</strong> : résolution des classes java.*
 * depuis un JDK autre que le runtime courant, via son {@code lib/jrt-fs.jar}
 * (le chemin réel sur Android/ART où le JDK Termux est externe à l'app).
 *
 * <p>Utilise un second JDK présent sur la machine de test (Java 21 système
 * quand le build tourne sous Java 17) pour exercer le chargement
 * URLClassLoader + ServiceLoader du fournisseur jrt.</p>
 *
 * @author jo@Dev
 */
class ExternalJrtTest {

    /** Le JDK 21 système (autre que le runtime du build). */
    private static Path externalJdk() {
        Path candidate = Paths.get("/usr/lib/jvm/java-21-openjdk-amd64");
        if (Files.isRegularFile(candidate.resolve("lib/modules"))) {
            return candidate;
        }
        return null;
    }

    private static boolean hasExternalJdk() {
        return externalJdk() != null;
    }

    @Test
    @EnabledIf("hasExternalJdk")
    void resolvesJavaLangFromExternalJdkViaJrtFsJar() {
        Path jdk = externalJdk();
        JdtCompilerEngine engine = new JdtCompilerEngine(
                new ArrayList<>(), Collections.<File>emptyList(), "17", jdk);

        // Le moteur doit être utilisable : jrt externe chargé.
        assertTrue(engine.isLikelyUsable(),
                "le jrt du JDK externe doit rendre le moteur utilisable");

        // Un code utilisant java.lang.String doit diagnostiquer proprement
        // (pas de « java.lang.Object cannot be resolved »).
        List<jo.lspjava.api.Diagnostic> diags = null;
        try {
            diags = engine.diagnose("/demo/Main.java", """
                    package demo;

                    public class Main {
                        public String greet(String name) {
                            return "Hello, " + name;
                        }
                    }
                    """);
        } catch (Throwable t) {
            throw new AssertionError("diagnose a échoué", t);
        }
        assertNotNull(diags);
        boolean unresolvedPlatform = diags.stream().anyMatch(d ->
                d.message() != null && d.message().contains("cannot be resolved"));
        assertTrue(!unresolvedPlatform,
                "java.lang doit résoudre depuis le jrt externe : " + diags);
    }

    @Test
    @EnabledIf("hasExternalJdk")
    void externalJdkCompletesStringMembers() {
        Path jdk = externalJdk();
        JdtCompilerEngine engine = new JdtCompilerEngine(
                new ArrayList<>(), Collections.<File>emptyList(), "17", jdk);
        JdtCompletionEngine completion = new JdtCompletionEngine(engine);

        String source = """
                package demo;

                public class Main {
                    public int test(String s) {
                        return s.le
                    }
                }
                """;
        int offset = source.indexOf("s.le") + "s.le".length();
        List<jo.lspjava.api.CompletionItem> items =
                completion.complete("/demo/Main.java", source, offset);
        assertNotNull(items, "la complétion doit être résolue");
        assertTrue(items.stream().anyMatch(i -> "length".equals(i.label())),
                "length() doit résoudre depuis le jrt externe : " + items);
    }

    @Test
    void nullJdkHomeFallsBackToRuntime() {
        // Sans jdkHome explicite, le jrt du runtime courant (Java 17 de
        // test) est utilisé.
        JdtCompilerEngine engine = new JdtCompilerEngine(
                new ArrayList<>(), Collections.<File>emptyList(), "17");
        assertTrue(engine.isLikelyUsable());
    }
}
