package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests pour la fonctionnalité « GO TO Definition sur cible
 * binaire » : vérifie que {@link EcjCompilerEngine#decompiledSource(String)}
 * renvoie soit la source attachée (src.zip du JDK) soit un stub décompilé
 * pour les types du JDK ({@code java.util.HashMap}, {@code java.lang.String}).
 *
 * <p>Ces tests ne tournent QUE si le JDK home passé au moteur contient
 * un {@code lib/src.zip} ou un {@code lib/modules} — ce qui est le cas
 * du JDK Temurin 17 utilisé en CI, mais PAS du runtime de build JUnit
 * (qui peut être un JRE minimal).</p>
 *
 * @author jo@Dev
 */
class DecompiledSourceTest {

    /** Le JDK 17 Temurin utilisé par les autres tests (:lspjava/.gradle). */
    private static Path testJdkHome() {
        // 1. Variable d'env explicite (CI).
        String env = System.getenv("JO_TEST_JDK_HOME");
        if (env != null && !env.isEmpty()) {
            Path p = Paths.get(env);
            if (Files.isDirectory(p)) return p;
        }
        // 2. JDK 17 Temurin standard de la machine, y compris celui de
        // l'environnement de build (my-project/tools). On préfère les JDK
        // avec lib/src.zip (nécessaire pour les tests de source attachée :
        // un JDK avec seulement lib/modules ne permet pas l'attachement
        // de source).
        Path[] candidates = new Path[] {
            Paths.get("/home/z/tools/jdk-17.0.20.1+1"),
            Paths.get("/home/z/my-project/tools/jdk-17.0.20.1+1"),
            Paths.get("/usr/lib/jvm/java-17-openjdk-amd64"),
            Paths.get("/usr/lib/jvm/java-21-openjdk-amd64"),
        };
        // Première passe : JDK avec lib/src.zip (préféré pour les tests source).
        for (Path p : candidates) {
            if (Files.isDirectory(p)
                && Files.isRegularFile(p.resolve("lib/src.zip"))) {
                return p;
            }
        }
        // Deuxième passe : JDK avec lib/modules seulement (fallback).
        for (Path p : candidates) {
            if (Files.isDirectory(p)
                && Files.isRegularFile(p.resolve("lib/modules"))) {
                return p;
            }
        }
        // 3. java.home du runtime de build.
        String jh = System.getProperty("java.home");
        if (jh != null) {
            Path p = Paths.get(jh);
            // Remonter d'un niveau si on est dans le sous-dossier jre/.
            if (p.getFileName() != null
                && "jre".equals(p.getFileName().toString())) {
                p = p.getParent();
            }
            if (Files.isRegularFile(p.resolve("lib/modules"))) return p;
        }
        return null;
    }

    private static boolean hasTestJdk() {
        return testJdkHome() != null;
    }

    private EcjCompilerEngine newEngine() {
        Path jdk = testJdkHome();
        assumeTrue(jdk != null, "Pas de JDK avec lib/modules troué — skip.");
        return new EcjCompilerEngine(
            new ArrayList<>(), Collections.<File>emptyList(), "17", jdk);
    }

    @Test
    @EnabledIf("hasTestJdk")
    void decompiledSource_returnsAttachedSourceForJdkClass() {
        EcjCompilerEngine engine = newEngine();
        // java.lang.String est dans src.zip — l'attachement doit marcher.
        String src = engine.decompiledSource("java.lang.String");
        assertNotNull(src, "la source de java.lang.String doit être trouvée");
        // Doit contenir la déclaration de classe.
        assertTrue(src.contains("package java.lang")
                || src.contains("public final class String")
                || src.contains("class String"),
            "la source doit contenir la déclaration de String : " + excerpt(src));
    }

    @Test
    @EnabledIf("hasTestJdk")
    void decompiledSource_returnsSourceForHashMapWithMethods() {
        EcjCompilerEngine engine = newEngine();
        String src = engine.decompiledSource("java.util.HashMap");
        assertNotNull(src);
        // Soit la source attachée (contient de vrais corps de méthodes)
        // soit le stub (contient l'en-tête « Decompiled from »).
        boolean isAttachedSource = src.contains("public class HashMap")
            || src.contains("class HashMap");
        boolean isStub = src.contains("Decompiled from java.util.HashMap");
        assertTrue(isAttachedSource || isStub,
            "La source doit être soit l'attachée, soit le stub : " + excerpt(src));
        // Le stub doit mentionner au moins une méthode publique.
        if (isStub) {
            assertTrue(src.contains("put")
                || src.contains("get")
                || src.contains("size"),
                "le stub doit mentionner au moins une méthode publique : " + excerpt(src));
        }
    }

    @Test
    @EnabledIf("hasTestJdk")
    void decompiledSource_returnsNullForNonexistentFqcn() {
        EcjCompilerEngine engine = newEngine();
        String src = engine.decompiledSource("does.not.Exist");
        // Soit null, soit le stub est retourné ; mais pour un FQCN
        // inexistant, binaryType() retourne null et readSourceAttachment
        // retourne null → résultat null.
        assertTrue(src == null,
            "decompiledSource doit retourner null pour un FQCN inexistant");
    }

    @Test
    @EnabledIf("hasTestJdk")
    void isBinaryType_trueForJdkClass() {
        EcjCompilerEngine engine = newEngine();
        assertTrue(engine.isBinaryType("java.lang.String"),
            "java.lang.String doit être un type binaire");
        assertTrue(engine.isBinaryType("java.util.HashMap"),
            "java.util.HashMap doit être un type binaire");
    }

    @Test
    @EnabledIf("hasTestJdk")
    void isBinaryType_falseForNonexistentFqcn() {
        EcjCompilerEngine engine = newEngine();
        assertFalse(engine.isBinaryType("does.not.Exist"),
            "un FQCN inexistant n'est pas binaire");
    }

    @Test
    @EnabledIf("hasTestJdk")
    void isBinaryType_nullOrEmptyFqcn_returnsFalse() {
        EcjCompilerEngine engine = newEngine();
        assertFalse(engine.isBinaryType(null));
        assertFalse(engine.isBinaryType(""));
    }

    @Test
    void decompiledSource_nullOrEmptyFqcn_returnsNull() {
        // Pas besoin de JDK — le moteur bail early.
        EcjCompilerEngine engine = new EcjCompilerEngine(
            new ArrayList<>(), Collections.<File>emptyList(), "17");
        assertEquals(null, engine.decompiledSource(null));
        assertEquals(null, engine.decompiledSource(""));
    }

    private static String excerpt(String src) {
        if (src == null) return "<null>";
        int n = Math.min(200, src.length());
        return src.substring(0, n).replace('\n', ' ').replace('\r', ' ');
    }
}
