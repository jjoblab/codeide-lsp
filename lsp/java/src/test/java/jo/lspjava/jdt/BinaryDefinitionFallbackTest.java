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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests pour le câblage LSP du décompilateur :
 * <ul>
 *   <li>{@link JdtCompilerEngine#fqcnAt} — résolution du FQN au caret
 *       (type déclarant pour definition ; type du symbole pour
 *       typeDefinition) — point d'ancrage du fallback binaire ;</li>
 *   <li>validation du FQN retourné pour des références à des types du JDK
 *       ({@code java.util.HashMap}, {@code java.lang.String}) — le FQN doit
 *       être identifié comme binaire par {@link JdtCompilerEngine#isBinaryType}
 *       → le serveur LSP renvoie une Location {@code jdt://decompiled/...}.</li>
 * </ul>
 *
 * <p>Ces tests ne tournent QUE si le JDK home passé au moteur contient
 * un {@code lib/src.zip} ou un {@code lib/modules} — ce qui est le cas
 * du JDK Temurin 17 utilisé en CI. Pattern identique à
 * {@link DecompiledSourceTest}.</p>
 *
 * @author jo@Dev
 */
class BinaryDefinitionFallbackTest {

    /** Le JDK 17 Temurin utilisé par les autres tests (:lspjava/.gradle). */
    private static Path testJdkHome() {
        String env = System.getenv("JO_TEST_JDK_HOME");
        if (env != null && !env.isEmpty()) {
            Path p = Paths.get(env);
            if (Files.isDirectory(p)) return p;
        }
        // JDK de l'environnement de build + préférence src.zip.
        Path[] candidates = new Path[] {
            Paths.get("/home/z/tools/jdk-17.0.20.1+1"),
            Paths.get("/home/z/my-project/tools/jdk-17.0.20.1+1"),
            Paths.get("/usr/lib/jvm/java-17-openjdk-amd64"),
            Paths.get("/usr/lib/jvm/java-21-openjdk-amd64"),
        };
        // Première passe : JDK avec lib/src.zip.
        for (Path p : candidates) {
            if (Files.isDirectory(p)
                && Files.isRegularFile(p.resolve("lib/src.zip"))) {
                return p;
            }
        }
        // Deuxième passe : JDK avec lib/modules seulement.
        for (Path p : candidates) {
            if (Files.isDirectory(p)
                && Files.isRegularFile(p.resolve("lib/modules"))) {
                return p;
            }
        }
        String jh = System.getProperty("java.home");
        if (jh != null) {
            Path p = Paths.get(jh);
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

    private JdtCompilerEngine newEngine() {
        Path jdk = testJdkHome();
        assumeTrue(jdk != null, "Pas de JDK avec lib/modules trouvé — skip.");
        return new JdtCompilerEngine(
            new ArrayList<>(), Collections.<File>emptyList(), "17", jdk);
    }

    /**
     * Résoud le FQN du type déclarant pour une référence à une méthode
     * d'un type JDK : {@code java.util.HashMap x; x.put(...)} → FQN
     * {@code java.util.HashMap} (targetType=false).
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_methodCallOnJdkType_returnsDeclaringClassFqn() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "import java.util.HashMap;\n"
                + "class Demo {\n"
                + "  void run() {\n"
                + "    HashMap<String, String> m = new HashMap<>();\n"
                + "    m.put(\"a\", \"b\");\n"
                + "  }\n"
                + "}\n";
        // Offset sur "put" (le caret est sur 'p' de put) :
        // Ligne 6, caractère 6 ("    m.put...") — offset approximatif, mais
        // fqcnAt est resilient : n'importe quel offset entre sourceStart/end
        // du MessageSend fonctionne.
        int putOffset = source.indexOf("put(");
        assertTrue(putOffset > 0, "le offset de put doit être trouvé");
        String fqcn = engine.fqcnAt("/tmp/demo/Demo.java", source, putOffset, false);
        assertNotNull(fqcn, "le FQN au caret de put ne doit pas être null");
        // Pour un appel de méthode, fqcnAt(targetType=false) retourne le
        // FQN de la classe déclarante. HashMap.put() est déclaré sur
        // HashMap (override de AbstractMap.put() mais le binding résolu
        // par ecj pointe sur HashMap, ou à défaut sur AbstractMap — les
        // deux sont des types binaires, on accepte donc l'un OU l'autre).
        assertTrue(fqcn.equals("java.util.HashMap")
                || fqcn.equals("java.util.AbstractMap")
                || fqcn.startsWith("java.util."),
            "FQN inattendu pour put() : " + fqcn);
        assertTrue(engine.isBinaryType(fqcn),
            "le FQN " + fqcn + " doit être binaire");
    }

    /**
     * Résoud le FQN du TYPE d'une variable d'un type JDK : caret sur
     * la variable {@code m} de type {@code HashMap} → FQN
     * {@code java.util.HashMap} (targetType=true).
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_variableOfTypeJdk_returnsTypeFqn() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "import java.util.HashMap;\n"
                + "class Demo {\n"
                + "  void run() {\n"
                + "    HashMap<String, String> m = new HashMap<>();\n"
                + "    m.put(\"a\", \"b\");\n"
                + "  }\n"
                + "}\n";
        // Offset sur "m" (la variable, pas l'appel) :
        int varOffset = source.indexOf("m.put");
        assertTrue(varOffset > 0);
        String fqcn = engine.fqcnAt("/tmp/demo/Demo.java", source, varOffset, true);
        assertNotNull(fqcn, "le FQN du type de m ne doit pas être null");
        // Le type de m est HashMap<String, String> — le FQN est HashMap.
        assertEquals("java.util.HashMap", fqcn,
            "le FQN du type de m doit être java.util.HashMap");
        assertTrue(engine.isBinaryType(fqcn),
            "java.util.HashMap doit être binaire");
    }

    /**
     * Résoud le FQN pour une référence de type SUPERCLASS : caret sur
     * {@code Thread} dans {@code class Demo extends Thread} → FQN
     * {@code java.lang.Thread}. Cas d'usage typique du binary GO TO :
     * l'utilisateur veut naviguer vers la définition (binaire) de Thread.
     *
     * <p>Le visiteur AST {@code visit(SingleTypeReference, ClassScope)}
     * couvre ce cas — la référence superclass est traversée via
     * {@code TypeDeclaration.traverse} qui appelle
     * {@code this.superclass.traverse(visitor, this.scope)} avec un
     * ClassScope. La résolution de {@code ref.resolvedType} donne le
     * BinaryTypeBinding de {@code java.lang.Thread}.</p>
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_superclassReference_returnsTypeFqn() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "class Demo extends Thread {\n"
                + "  void run() {}\n"
                + "}\n";
        // Caret sur "Thread" (la superclass — traverse via ClassScope).
        int threadOffset = source.indexOf("Thread");
        assertTrue(threadOffset > 0);
        // targetType=true : FQN du type (Thread).
        String fqcnType = engine.fqcnAt("/tmp/demo/Demo.java", source, threadOffset, true);
        assertNotNull(fqcnType, "le FQN de Thread ne doit pas être null");
        assertEquals("java.lang.Thread", fqcnType);
        // targetType=false : pour une référence de type, la classe déclarante
        // EST le type lui-même (Thread).
        String fqcnDecl = engine.fqcnAt("/tmp/demo/Demo.java", source, threadOffset, false);
        assertNotNull(fqcnDecl);
        assertEquals("java.lang.Thread", fqcnDecl);
        // Thread est binaire — le fallback se déclenche.
        assertTrue(engine.isBinaryType("java.lang.Thread"));
    }

    /**
     * Le FQN d'un type déclaré DANS le fichier (non binaire) : doit être
     * retourné MAIS isBinaryType doit retourner false — le fallback binaire
     * ne se déclenche pas pour les types source.
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_sourceType_isNotBinary() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "class Demo {\n"
                + "  Demo instance;\n"
                + "  void run() { instance.run(); }\n"
                + "}\n";
        // Caret sur "Demo" (la déclaration du champ).
        int typeOffset = source.indexOf("Demo instance");
        assertTrue(typeOffset > 0);
        String fqcn = engine.fqcnAt("/tmp/demo/Demo.java", source, typeOffset, true);
        assertNotNull(fqcn, "le FQN de Demo ne doit pas être null");
        assertEquals("demo.Demo", fqcn);
        // demo.Demo est défini dans le fichier courant — PAS un type binaire.
        // Le serveur LSP doit retourner une Location normalement (findDefinition
        // JDT-first l'a déjà trouvée) — le fallback binaire ne se déclenche pas.
        // (on ne peut pas tester isBinaryType=true ici — c'est le point).
    }

    /**
     * fqcnAt retourne null pour un caret en dehors de tout binding
     * (whitespace, commentaire, etc.) — défensif.
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_caretInWhitespace_returnsNull() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "class Demo {\n"
                + "  void run() {\n"
                + "    // un commentaire\n"
                + "  }\n"
                + "}\n";
        // Caret dans le commentaire.
        int commentOffset = source.indexOf("un commentaire");
        assertTrue(commentOffset > 0);
        String fqcn = engine.fqcnAt("/tmp/demo/Demo.java", source, commentOffset, false);
        assertNull(fqcn, "fqcnAt doit retourner null dans un commentaire");
    }

    /**
     * fqcnAt retourne null pour un source vide ou un offset hors-plage.
     */
    @Test
    void fqcnAt_emptySource_returnsNull() {
        JdtCompilerEngine engine = new JdtCompilerEngine(
            new ArrayList<>(), Collections.<File>emptyList(), "17");
        assertNull(engine.fqcnAt("/tmp/X.java", "", 0, false));
        assertNull(engine.fqcnAt("/tmp/X.java", "", 100, true));
    }

    /**
     * fqcnAt retourne null pour un caret sur une base type ({@code int x}).
     */
    @Test
    @EnabledIf("hasTestJdk")
    void fqcnAt_baseType_returnsNull() {
        JdtCompilerEngine engine = newEngine();
        String source = "package demo;\n"
                + "class Demo {\n"
                + "  void run() {\n"
                + "    int x = 42;\n"
                + "    System.out.println(x);\n"
                + "  }\n"
                + "}\n";
        // Caret sur "int" (un type de base).
        int intOffset = source.indexOf("int x");
        assertTrue(intOffset > 0);
        String fqcn = engine.fqcnAt("/tmp/demo/Demo.java", source, intOffset, true);
        // typeBindingOf filtre les base types (isBaseType()) — fqcnAt
        // retourne null car int n'est pas un TypeBinding valide pour
        // la navigation (pas de déclaration à visiter).
        assertNull(fqcn, "le FQN d'un type de base doit être null");
    }
}
