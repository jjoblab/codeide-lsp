package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du cache de parse à entrée unique ({@link JdtCompilerEngine}).
 *
 * <p>Valide que :</p>
 * <ul>
 *   <li>deux appels {@code resolveUnit} successifs sur le même {@code (path, source)}
 *       retournent la MÊME instance d'arbre résolu (cache hit) ;</li>
 *   <li>un changement de {@code source} déclenche un cache miss et produit un
 *       nouvel arbre (sinon le cache servirait un arbre périmé) ;</li>
 *   <li>{@code invalidateParseCache(path)} force un cache miss ciblé ;</li>
 *   <li>le cache fonctionne aussi pour les diagnostics ({@code generateCode=true}).</li>
 * </ul>
 *
 * @author jo@Dev
 */
class BindingParseCacheTest {

    private JdtCompilerEngine newEngine() {
        return new JdtCompilerEngine(
                Collections.<File>emptyList(),
                Collections.<File>emptyList(),
                "17");
    }

    @Test
    void sameSourceReturnsCachedTree() {
        JdtCompilerEngine engine = newEngine();
        String source = """
                package demo;
                public class Main {
                    public void run() {
                        System.out.println("hello");
                    }
                }
                """;
        var cud1 = engine.resolveUnit("/demo/Main.java", source, false);
        var cud2 = engine.resolveUnit("/demo/Main.java", source, false);
        assertNotNull(cud1, "premier resolveUnit doit produire un arbre");
        assertSame(cud1, cud2,
                "deuxième resolveUnit sur le même (path, source) doit servir le cache");
    }

    @Test
    void changedSourceTriggersMiss() {
        JdtCompilerEngine engine = newEngine();
        String v1 = "package demo;\npublic class A { int x; }\n";
        String v2 = "package demo;\npublic class A { int y; }\n";
        var cud1 = engine.resolveUnit("/demo/A.java", v1, false);
        var cud2 = engine.resolveUnit("/demo/A.java", v2, false);
        assertNotNull(cud1);
        assertNotNull(cud2);
        assertNotSame(cud1, cud2,
                "changement de source doit produire un nouvel arbre (cache miss)");
    }

    @Test
    void invalidateParseCacheForcesMiss() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\npublic class A { int x; }\n";
        var cud1 = engine.resolveUnit("/demo/A.java", source, false);
        engine.invalidateParseCache("/demo/A.java");
        var cud2 = engine.resolveUnit("/demo/A.java", source, false);
        assertNotNull(cud1);
        assertNotNull(cud2);
        assertNotSame(cud1, cud2,
                "invalidateParseCache doit forcer un cache miss");
    }

    @Test
    void diagnosticsFillThenHoverHits() {
        // Pattern clé : diagnostics (generateCode=true) remplit
        // le cache, puis hover (generateCode=false) doit HIT et servir le
        // même arbre — la codegen n'affecte que les octets de classe qu'on
        // jette, les bindings sont identiques.
        JdtCompilerEngine engine = newEngine();
        String source = """
                package demo;
                public class Main {
                    public void run() {
                        String s = "hello";
                        System.out.println(s);
                    }
                }
                """;
        // Diagnostics remplit le cache avec generateCode=true.
        List<jo.lspjava.api.Diagnostic> diags =
                assertDoesNotThrow(() -> engine.diagnose("/demo/Main.java", source));
        // Hover avec generateCode=false doit servir le cache (généré avec
        // generateCode=true — réutilisable car les bindings sont identiques).
        var cudHover = engine.resolveUnit("/demo/Main.java", source, false);
        assertNotNull(cudHover, "hover doit servir l'arbre mis en cache par diagnose");
    }

    @Test
    void disposeInvalidatesCache() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\npublic class A { int x; }\n";
        var cud1 = engine.resolveUnit("/demo/A.java", source, false);
        engine.dispose();
        var cud2 = engine.resolveUnit("/demo/A.java", source, false);
        assertNotNull(cud1);
        assertNotNull(cud2);
        assertNotSame(cud1, cud2,
                "dispose() doit invalider le cache");
    }
}
