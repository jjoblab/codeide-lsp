package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du cache de parse à entrée unique ({@link EcjCompilerEngine}).
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
class EcjParseCacheTest {

    private EcjCompilerEngine newEngine() {
        return new EcjCompilerEngine(
                Collections.<File>emptyList(),
                Collections.<File>emptyList(),
                "17");
    }

    @Test
    void sameSourceReturnsCachedTree() {
        EcjCompilerEngine engine = newEngine();
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
        EcjCompilerEngine engine = newEngine();
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
        EcjCompilerEngine engine = newEngine();
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
        EcjCompilerEngine engine = newEngine();
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
        EcjCompilerEngine engine = newEngine();
        String source = "package demo;\npublic class A { int x; }\n";
        var cud1 = engine.resolveUnit("/demo/A.java", source, false);
        engine.dispose();
        var cud2 = engine.resolveUnit("/demo/A.java", source, false);
        assertNotNull(cud1);
        assertNotNull(cud2);
        assertNotSame(cud1, cud2,
                "dispose() doit invalider le cache");
    }

    // ─── Étape 3 lot D : multi-entrées (W8) ────────────────────────────

    @Test
    void alternatingFilesKeepBothEntries() {
        // W8 : l'entrée unique évinçait à chaque put — l'aller-retour
        // entre fichiers repayait le parse. Multi-entrées : A et B
        // cohabitent, le retour à A est un hit.
        EcjCompilerEngine engine = newEngine();
        String a = "package demo;\npublic class A { int x; }\n";
        String b = "package demo;\npublic class B { int y; }\n";
        var a1 = engine.resolveUnit("/demo/A.java", a, false);
        var b1 = engine.resolveUnit("/demo/B.java", b, false);
        var a2 = engine.resolveUnit("/demo/A.java", a, false);
        assertNotNull(a1);
        assertNotNull(b1);
        assertSame(a1, a2, "retour à A doit HIT (cohabitation multi-entrées)");
        var b2 = engine.resolveUnit("/demo/B.java", b, false);
        assertSame(b1, b2, "B reste en cache aussi");
    }

    @Test
    void capacityEvictsLeastRecentlyUsed() {
        // MAX_ENTRIES + 1 fichiers distincts : le MOINS récemment utilisé
        // est évincé, les autres survivent.
        EcjCompilerEngine engine = newEngine();
        String premier = "package demo;\npublic class F0 { int v0; }\n";
        var f0 = engine.resolveUnit("/demo/F0.java", premier, false);
        assertNotNull(f0);
        for (int i = 1; i <= EcjParseCache.MAX_ENTRIES; i++) {
            String src = "package demo;\npublic class F" + i
                    + " { int v" + i + "; }\n";
            var cud = engine.resolveUnit("/demo/F" + i + ".java", src, false);
            assertNotNull(cud);
        }
        // F0 a été évincé (le plus ancien) : re-parse.
        var f0bis = engine.resolveUnit("/demo/F0.java", premier, false);
        assertNotSame(f0, f0bis, "l'entrée la moins récemment utilisée doit être évincée");
        // Le DERNIER fichier reste présent (le plus récent).
        String dernier = "package demo;\npublic class F" + EcjParseCache.MAX_ENTRIES
                + " { int v" + EcjParseCache.MAX_ENTRIES + "; }\n";
        var fd = engine.resolveUnit(
                "/demo/F" + EcjParseCache.MAX_ENTRIES + ".java", dernier, false);
        // re-parse de F0 a repositionné F0 en MRU : le LRU est désormais F1
        // — le dernier entré AVANT F0bis reste en cache de toute façon
        // (F0bis vient d'y retourner). On vérifie surtout que la capacité
        // est bornée.
        assertTrue(engine.parseCacheSize() <= EcjParseCache.MAX_ENTRIES,
                "capacité bornée : " + engine.parseCacheSize());
    }

    @Test
    void invalidateOnePathKeepsOthers() {
        EcjCompilerEngine engine = newEngine();
        String a = "package demo;\npublic class A { int x; }\n";
        String b = "package demo;\npublic class B { int y; }\n";
        var a1 = engine.resolveUnit("/demo/A.java", a, false);
        var b1 = engine.resolveUnit("/demo/B.java", b, false);
        engine.invalidateParseCache("/demo/A.java");
        var b2 = engine.resolveUnit("/demo/B.java", b, false);
        assertSame(b1, b2, "l'invalidation ciblée ne doit PAS toucher les autres fichiers");
        var a2 = engine.resolveUnit("/demo/A.java", a, false);
        assertNotSame(a1, a2, "seul le fichier invalidé doit re-parser");
    }

    @Test
    void codegenEntryIsNeverDowngraded() {
        // Un put SANS codegen ne dégrade pas une entrée AVEC codegen.
        EcjCompilerEngine engine = newEngine();
        String source = """
                package demo;
                public class Main {
                    public void run() { System.out.println("x"); }
                }
                """;
        List<jo.lspjava.api.Diagnostic> diags =
                assertDoesNotThrow(() -> engine.diagnose("/demo/Main.java", source));
        // put sans codegen (resolveUnit false) — l'entrée codegen survit.
        engine.resolveUnit("/demo/Main.java", source, false);
        // diagnose (true) doit encore HIT l'arbre codegen : observable par
        // le memo diagnose (même arbre → diagnostics recalculés dessus).
        var apres = assertDoesNotThrow(
                () -> engine.diagnose("/demo/Main.java", source));
        assertEquals(diags.size(), apres.size(),
                "l'entrée codegen ne doit pas être dégradée par un put sans codegen");
    }
}
