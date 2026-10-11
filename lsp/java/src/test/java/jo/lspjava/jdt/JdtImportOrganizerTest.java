package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'organisateur d'imports ({@link JdtImportOrganizer}).
 *
 * @author jo@Dev
 */
class JdtImportOrganizerTest {

    private JdtImportOrganizer newOrganizer() {
        return new JdtImportOrganizer(new JdtCompilerEngine(
                Collections.<File>emptyList(),
                Collections.<File>emptyList(),
                "17"));
    }

    @Test
    void javaLangAndSamePackageNeedNoImport() {
        String source = """
                package demo;

                public class Main {
                    public String run(String s) {
                        return s.trim();
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        assertTrue(result.imports.isEmpty(),
                "String est java.lang — aucun import attendu : " + result.imports);
    }

    @Test
    void javaUtilTypeNeedsImport() {
        String source = """
                package demo;

                public class Main {
                    public java.util.List<String> all() {
                        return null;
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        // java.util.List utilisé en QUALIFIÉ : l'import n'est pas strictement
        // nécessaire mais l'organiseur peut le proposer pour permettre le
        // dé-qualification. Ici on vérifie juste qu'il est identifié.
        assertTrue(result.imports.contains("java.util.List"),
                "java.util.List doit être identifié : " + result.imports);
    }

    @Test
    void onDemandImportCoversPackage() {
        String source = """
                package demo;

                import java.util.*;

                public class Main {
                    public List<String> all() {
                        return new ArrayList<>();
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        // java.util.* couvre List et ArrayList — AUCUN import explicite
        // ajouté ; l'on-demand est PRÉSERVÉ (le seul élément du bloc).
        assertEquals(java.util.List.of("java.util.*"), result.imports,
                "on-demand préservé, aucun explicite : " + result.imports);
        assertEquals("import java.util.*;\n", result.importsBlock);
    }

    @Test
    void unqualifiedTypeFromOtherPackageIsImported() {
        String source = """
                package demo;

                public class Main {
                    public java.math.BigDecimal price() {
                        return java.math.BigDecimal.ZERO;
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        assertTrue(result.imports.contains("java.math.BigDecimal"),
                "BigDecimal doit être importé : " + result.imports);
    }

    @Test
    void replacesExistingBlockSorted() {
        String source = """
                package demo;

                import java.math.BigDecimal;
                import java.util.List;

                public class Main {
                    public java.util.List<java.math.BigDecimal> prices() {
                        return null;
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        // Les deux FQN identifiés, bloc trié.
        assertEquals(2, result.imports.size());
        assertTrue(result.importsBlock.startsWith(
                "import java.math.BigDecimal;\nimport java.util.List;"),
                "bloc trié : " + result.importsBlock);
        // La plage de remplacement couvre les imports existants.
        String replaced = source.substring(result.replaceStart, result.replaceEnd);
        assertTrue(replaced.contains("java.math.BigDecimal")
                        && replaced.contains("java.util.List"),
                "la plage remplace les imports existants : " + replaced);
    }

    @Test
    void insertionAfterPackageWhenNoImports() {
        String source = """
                package demo;

                public class Main {
                    public java.math.BigDecimal price() {
                        return java.math.BigDecimal.ZERO;
                    }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        // Insertion juste après « package demo; ».
        assertTrue(result.replaceStart >= source.indexOf("package demo;")
                        && result.replaceStart <= source.indexOf("public class"),
                "insertion après le paquet : " + result.replaceStart);
    }

    @Test
    void brokenCodeYieldsNoFalseImports() {
        String source = """
                package demo;

                public class Main {
                    public UndefinedThing broken = undefinedField;
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        // Pas d'exception ; les bindings non résolus sont ignorés.
        if (result != null) {
            assertTrue(result.imports.stream()
                            .noneMatch(fqn -> fqn.contains("Undefined")),
                    "aucun faux import : " + result.imports);
        }
    }

    @Test
    void superclassFromOtherPackageIsImported() {
        String source = """
                package demo;

                public class Main extends java.util.TimerTask {
                    public void run() { }
                }
                """;
        var result = newOrganizer().organize("/demo/Main.java", source);
        assertNotNull(result);
        assertTrue(result.imports.contains("java.util.TimerTask"),
                "la superclasse doit être importée : " + result.imports);
    }
}
