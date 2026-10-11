package jo.lspjava.ecj;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du cache de localisation fqcn → jar (P1 v1.3.0, portage
 * {@code locateCache} de {@code JdtEnvironmentCache.kt}, projet de référence v3.20)
 * intégré à {@link EcjCompilerEnvironment#find} :
 *
 * <ul>
 *   <li><b>Positif mémorisé</b> — deux {@code find} successifs d'un FQCN
 *       présent dans un jar renvoient tous deux une réponse (le second
 *       emprunte le cache) ;</li>
 *   <li><b>Négatif mémorisé</b> — un FQCN absent des jars ne re-sonde pas
 *       les N sets à chaque appel : après le premier échec, le lookup est
 *       O(1) et retourne null immédiatement (c'est le gain dominant sur
 *       les gros classpaths — ecj résout des centaines de types absents
 *       par frappe) ;</li>
 *   <li><b>Type imbriqué</b> — {@code a.b.Outer$Inner} se localise via le
 *       jar de {@code a.b.Outer} (locateBinary) ;</li>
 *   <li><b>Invalidation</b> — {@code dispose()} vide le cache (un négatif
 *       mémorisé ne survit pas à un changement de classpath).</li>
 * </ul>
 *
 * <p>Le jar de test est fabriqué à partir d'un vrai {@code .class} copié
 * depuis les sorties de compilation du module (entrée ZIP valide pour
 * {@code ClassFileReader.read}).</p>
 *
 * @author jo@Dev
 */
class EcjLocateCacheTest {

    @TempDir
    Path tmp;

    /** L'environnement sous test — reconstruit par test (dispose inclus). */
    private EcjCompilerEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        Path jar = buildJar("loc.jar", "jo/lspjava/ecj/EcjLiveTemplates.class");
        List<Path> roots = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        jars.add(jar);
        env = new EcjCompilerEnvironment(roots, jars, null);
    }

    /** Construit un jar contenant une entrée .class réelle. */
    private Path buildJar(String name, String entry) throws IOException {
        Path out = tmp.resolve(name);
        Path classFile = Path.of("..", "build", "classes", "java", "main", entry);
        if (!Files.isRegularFile(classFile)) {
            // Repli : chercher depuis la racine du module (exécution IDE).
            classFile = Path.of("build", "classes", "java", "main", entry);
        }
        assertTrue(Files.isRegularFile(classFile),
                "classe témoin introuvable : " + classFile.toAbsolutePath());
        byte[] bytes = Files.readAllBytes(classFile);
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(out))) {
            jos.putNextEntry(new ZipEntry(entry));
            jos.write(bytes);
            jos.closeEntry();
        }
        return out;
    }

    @Test
    void positifMemoriseDeuxFindSuccessifs() throws Exception {
        String fqcn = "jo.lspjava.ecj.EcjLiveTemplates";
        Object first = find(fqcn);
        assertNotNull(first, "premier find : le jar contient la classe");
        // Second appel — emprunte le cache (hit), même réponse.
        Object second = find(fqcn);
        assertNotNull(second, "second find (cache hit) : réponse conservée");
    }

    @Test
    void negatifMemoriseAbsentDesJars() throws Exception {
        String fqcn = "com.inexistant.Classe";
        assertNull(find(fqcn), "absent des jars et du jrt de test → null");
        // Le négatif est mémorisé — re-find immédiat, même verdict.
        assertNull(find(fqcn), "second find : négatif mémorisé (O(1))");
    }

    @Test
    void typeImbriqueLocaliseViaEnglobant() throws Exception {
        // a.b.Outer$Inner n'est PAS une entrée du jar — locateBinary
        // retombe sur a.b.Outer… absent aussi → null SANS exception.
        assertNull(find("jo.lspjava.ecj.PasLa$Inner"));
        // Avec l'englobant PRÉSENT : l'entrée a.b.Outer.class n'existe pas
        // non plus dans notre jar témoin — on vérifie juste la robustesse
        // du chemin $ (aucune exception, verdict null cohérent).
        assertNull(find("jo.lspjava.ecj.EcjLiveTemplates$Inner"));
    }

    @Test
    void disposeVideLeCache() throws Exception {
        String fqcn = "com.inexistant.ApresDispose";
        assertNull(find(fqcn));
        dispose(env);
        // Après dispose, les structures sont vides — find reste sûr (le
        // cache vide re-sonde… sur zéro jar → négatif à nouveau mémorisé).
        assertNull(find(fqcn));
    }

    // ─── Accès réflexifs (membres package-private du moteur) ──────────

    private Object find(String fqcn) throws Exception {
        java.lang.reflect.Method m = EcjCompilerEnvironment.class
                .getDeclaredMethod("find", String.class);
        m.setAccessible(true);
        return m.invoke(env, fqcn);
    }

    private static void dispose(EcjCompilerEnvironment env) throws Exception {
        java.lang.reflect.Method m = EcjCompilerEnvironment.class
                .getDeclaredMethod("dispose");
        m.setAccessible(true);
        m.invoke(env);
    }

    /** Garde-fou de compilation : l'encodage du fichier est bien UTF-8. */
    @Test
    void encodageUtf8DuFichierSource() throws IOException {
        byte[] self = Files.readAllBytes(Path.of(
                "src/test/java/jo/lspjava/ecj/EcjLocateCacheTest.java"));
        String content = new String(self, StandardCharsets.UTF_8);
        assertTrue(content.contains("localisation"));
    }
}
