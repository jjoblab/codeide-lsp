package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests P5 (v1.4.0) — diagnostics de hiérarchie enrichis
 * (portage de {@code HierarchyDiagnosticsTest.kt}, projet tiers de référence v3.20).
 *
 * <p>« La hiérarchie du type X est incohérente »
 * ({@code IProblem.HierarchyHasProblems}) ne nomme que le sous-type cassé —
 * jamais l'ancêtre réellement absent du classpath, qui est pourtant LA
 * question de l'utilisateur (« quelle dépendance ajouter ? »). Quand
 * l'ancêtre manquant est le super d'un supertype <em>binaire</em> (une
 * classe présente sur le classpath dont la propre superclasse a été
 * retirée du jar), le moteur doit désormais replier le FQN manquant et
 * son exigeant dans le message :</p>
 *
 * <pre>La hiérarchie du type C est incohérente — absent du classpath :
 * lib.A (requis par lib.B)</pre>
 *
 * <p><strong>Fixture</strong> : {@code lib.A} et {@code lib.B}
 * ({@code B extends A}) sont compilés par le {@code javac} du JVM de test,
 * puis SEUL {@code B.class} est placé dans le jar du classpath —
 * {@code A} est donc un ancêtre binaire manquant. Le test se skippe
 * proprement sur un JRE sans compilateur système.</p>
 *
 * @author jo@Dev
 */
class JdtHierarchyDiagnosticsTest {

    @TempDir
    Path tmp;

    /** Le compilateur système du JVM de test (null sur un JRE nu). */
    private static JavaCompiler javac() {
        return ToolProvider.getSystemJavaCompiler();
    }

    /**
     * Scénario de référence : jar contenant {@code B.class} seul
     * ({@code A} absent) — le diagnostic d'hiérarchie de {@code C}
     * doit nommer {@code lib.A} « requis par lib.B ».
     */
    @Test
    void ancetreBinaireManquantEstNomme() throws Exception {
        JavaCompiler javac = javac();
        Assumptions.assumeTrue(javac != null, "JRE sans javac — skip");

        Path jar = jarAvecSeulementB();
        Path srcRoot = Files.createDirectories(tmp.resolve("src"));
        EcjCompilerEngine engine = newEngine(srcRoot, jar);

        String code = "package app;\nclass C extends lib.B {}\n";
        List<Diagnostic> diags = engine.diagnose(
                srcRoot.resolve("app/C.java").toString(), code);

        // Le message enrichi = hiérarchie incohérente + FQN manquant +
        // exigeant (le message natif ecj « indirectly referenced »
        // accompagne le lot sans porter le suffixe français).
        String enriched = null;
        for (Diagnostic d : diags) {
            String m = d.message();
            if (m.contains("lib.A") && m.contains("requis par lib.B")) {
                enriched = m;
                break;
            }
        }
        assertNotNull(enriched,
                "le diagnostic enrichi doit nommer lib.A (requis par lib.B) : "
                        + diags);
        assertTrue(enriched.contains("classpath"),
                "le suffixe doit mentionner le classpath : " + enriched);
        // Le message d'ORIGINE est préservé en préfixe (type cassé nommé,
        // quelle que soit la Locale du problème ecj).
        assertTrue(enriched.contains("C"),
                "le message d'origine (nommant C) doit rester présent : "
                        + enriched);
    }

    /**
     * Garde-fou anti-fuite : avec le jar COMPLET ({@code A.class} +
     * {@code B.class}), aucune erreur, aucun enrichissement — le suffixe
     * « classpath » ne doit apparaître nulle part.
     */
    @Test
    void hierarchieCompleteAucunEnrichissement() throws Exception {
        JavaCompiler javac = javac();
        Assumptions.assumeTrue(javac != null, "JRE sans javac — skip");

        Path jar = jarComplet();
        Path srcRoot = Files.createDirectories(tmp.resolve("src"));
        EcjCompilerEngine engine = newEngine(srcRoot, jar);

        String code = "package app;\nclass C extends lib.B {}\n";
        List<Diagnostic> diags = engine.diagnose(
                srcRoot.resolve("app/C.java").toString(), code);

        for (Diagnostic d : diags) {
            assertFalse(d.message().contains("classpath"),
                    "aucun enrichissement attendu sur hiérarchie saine : "
                            + d.message());
        }
        for (Diagnostic d : diags) {
            assertFalse(d.severity() == Severity.ERROR,
                    "hiérarchie complète = zéro erreur : " + d.message());
        }
    }

    /**
     * Chemin best-effort : supertype SOURCE inconnu
     * ({@code class C extends Inconnu {}}) — le diagnose ne doit ni
     * planter ni perdre le lot de diagnostics (l'erreur « cannot be
     * resolved » d'ecj reste publiée, l'enrichissement au pire absent).
     */
    @Test
    void supertypeSourceInconnuNePlantePas() throws Exception {
        Path srcRoot = Files.createDirectories(tmp.resolve("src"));
        EcjCompilerEngine engine = newEngine(srcRoot, null);

        String code = "package app;\nclass C extends Inconnu {}\n";
        List<Diagnostic> diags = engine.diagnose(
                srcRoot.resolve("app/C.java").toString(), code);

        assertNotNull(diags, "le diagnose doit toujours retourner un lot");
        assertFalse(diags.isEmpty(),
                "l'erreur de résolution d'Inconnu doit être publiée : "
                        + diags);
        boolean cannotResolve = diags.stream()
                .anyMatch(d -> d.severity() == Severity.ERROR);
        assertTrue(cannotResolve,
                "au moins une erreur attendue pour Inconnu : " + diags);
    }

    // ─── Fixtures ─────────────────────────────────────────────────────

    /**
     * Compile {@code lib.A} + {@code lib.B} (B extends A) et retourne le
     * dossier de sorties {@code .class}.
     */
    private Path compileFixture(JavaCompiler javac) throws Exception {
        Path libSrc = Files.createDirectories(tmp.resolve("libs"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(libSrc.resolve("A.java"),
                "package lib; public class A {}");
        Files.writeString(libSrc.resolve("B.java"),
                "package lib; public class B extends A {}");
        int rc = javac.run(null, null, null,
                "-d", classes.toString(),
                libSrc.resolve("A.java").toString(),
                libSrc.resolve("B.java").toString());
        assertEquals(0, rc, "compilation javac du fixture (rc=" + rc + ")");
        return classes;
    }

    /** Jar ne contenant QUE {@code lib/B.class} (A absent du classpath). */
    private Path jarAvecSeulementB() throws Exception {
        Path classes = compileFixture(javac());
        Path jar = tmp.resolve("b-seul.jar");
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(jar))) {
            putClassEntry(jos, classes, "lib/B.class");
        }
        return jar;
    }

    /** Jar complet ({@code lib/A.class} + {@code lib/B.class}). */
    private Path jarComplet() throws Exception {
        Path classes = compileFixture(javac());
        Path jar = tmp.resolve("complet.jar");
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(jar))) {
            putClassEntry(jos, classes, "lib/A.class");
            putClassEntry(jos, classes, "lib/B.class");
        }
        return jar;
    }

    private static void putClassEntry(JarOutputStream jos, Path classes,
                                      String entry) throws Exception {
        jos.putNextEntry(new ZipEntry(entry));
        jos.write(Files.readAllBytes(classes.resolve(entry)));
        jos.closeEntry();
    }

    /**
     * Moteur sur la racine source donnée ; {@code classpathJar} null =
     * classpath de jars vide (seul le jrt du JVM de test résout java.*).
     */
    private static EcjCompilerEngine newEngine(Path srcRoot, Path classpathJar)
            throws Exception {
        List<java.io.File> jars = classpathJar == null
                ? List.of()
                : List.of(classpathJar.toFile());
        return new EcjCompilerEngine(
                List.of(srcRoot.toFile()), jars, "17");
    }
}
