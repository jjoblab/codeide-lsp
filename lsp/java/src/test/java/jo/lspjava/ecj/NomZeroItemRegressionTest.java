package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import jo.lspjava.api.CompletionItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de NON-RÉGRESSION du « 0 item » de la complétion NOM (étude
 * §4.2/§4.3 — symptôme « got 0 items » du banc ; lot F, étape 3).
 *
 * <p><strong>Constat reproduit :</strong> les contextes NON capturés par
 * conception (nom de déclaration — parité projet de référence
 * « ContextAnalyzer ne capture pas les noms de déclarations » ;
 * annotation en recovery ; position de tableau) faisaient rendre
 * {@code null} par {@link EcjCompletionEngine#complete} — l'appelant
 * itérant sur null jetait une NPE avalée, et le banc observait
 * « 0 items » (étude §4.2 : « La complétion NOM rend 0 item dans le
 * scénario du banc (reproduit le symptôme "got 0 items" de l'ancienne
 * version) : comportement préexistant du module sur ce contexte, à
 * corriger à l'étape 3 »).</p>
 *
 * <p><strong>Correctif testé :</strong> le CONTRAT « jamais null » —
 * ces positions rendent une liste VIDE honnête ; les positions
 * TRAVAILLÉES du même corpus continuent de rendre leurs items.</p>
 *
 * <p>Fixture : jar compilé au vol par {@code javax.tools} (aucune
 * dépendance android.jar/cache — les tests tournent partout).</p>
 *
 * @author jo@Dev
 */
class NomZeroItemRegressionTest {

    @TempDir
    static Path tmp;

    static Path jarFixture;
    static Path fichier;
    static EcjCompilerEngine moteur;

    @BeforeAll
    static void preparer() throws Exception {
        JavaCompiler compilateur = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compilateur != null, "JDK sans compilateur");

        List<String> sources = List.of(
                "package alpha;\npublic class Alpha {\n"
                        + "    public static int COMPTEUR = 3;\n"
                        + "    public int instance;\n}\n",
                "package alpha;\npublic class AlphaVoisin {\n"
                        + "    public void bouger() {}\n}\n",
                "package alpha.sous;\npublic class Profond {\n"
                        + "    public void descendre() {}\n}\n",
                "package beta;\npublic class Beta {\n"
                        + "    public void tester() {}\n}\n");
        String[] noms = {"Alpha", "AlphaVoisin", "Profond", "Beta"};

        Path src = Files.createDirectories(tmp.resolve("src"));
        List<File> fichiers = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Path f = src.resolve(noms[i] + ".java");
            Files.writeString(f, sources.get(i), StandardCharsets.UTF_8);
            fichiers.add(f.toFile());
        }
        Path sortie = Files.createDirectories(tmp.resolve("classes"));
        try (StandardJavaFileManager gm =
                     compilateur.getStandardFileManager(null, null, null)) {
            boolean ok = compilateur.getTask(null, gm, null,
                    List.of("-d", sortie.toString()), null,
                    gm.getJavaFileObjectsFromFiles(fichiers)).call();
            assertTrue(ok, "la fixture doit compiler");
        }
        jarFixture = tmp.resolve("fixture-nom.jar");
        try (JarOutputStream jar = new JarOutputStream(
                new java.io.FileOutputStream(jarFixture.toFile()),
                new Manifest())) {
            try (var liste = Files.walk(sortie)) {
                for (Path p : liste.sorted().toList()) {
                    if (p.toString().endsWith(".class")) {
                        jar.putNextEntry(new ZipEntry(sortie.relativize(p)
                                .toString().replace(File.separatorChar, '/')));
                        jar.write(Files.readAllBytes(p));
                        jar.closeEntry();
                    }
                }
            }
        }
        Path racine = Files.createDirectories(tmp.resolve("projet/exemple"));
        fichier = racine.resolve("Essai.java");
        moteur = new EcjCompilerEngine(List.of(racine.toFile()),
                List.of(jarFixture.toFile()), "17");
    }

    // ─── Le contrat : jamais null, y compris les contextes non capturés ──

    @Test
    void nomDeDeclarationRendVidePasNull() throws Exception {
        // « void autre(int Bund| » — le marqueur est en position de NOM de
        // paramètre : non capturé PAR CONCEPTION (parité projet de référence).
        // L'ancien comportement : complete() → null → NPE côté appelant.
        List<CompletionItem> items = completer("        void autre(int Bund");
        assertNotNull(items, "complete() ne doit JAMAIS rendre null");
        // Vide honnête — pas une erreur, pas un crash.
    }

    @Test
    void annotationEnRecoveryRendVidePasNull() throws Exception {
        // « @Overri| » dans un corps de méthode — l'annotation-statements
        // n'est pas une syntaxe Java valide : ecj la jette en recovery.
        List<CompletionItem> items = completer("        @Overri");
        assertNotNull(items, "complete() ne doit JAMAIS rendre null");
    }

    @Test
    void apresTableauRendVidePasNull() throws Exception {
        // « Alp[]| » — le marqueur après les crochets du type tableau.
        List<CompletionItem> items = completer("        Alp[]");
        assertNotNull(items, "complete() ne doit JAMAIS rendre null");
    }

    // ─── Les positions travaillées continuent de rendre leurs items ────

    @Test
    void typeEnParametrePropose() throws Exception {
        // « void autre(Bund| » — le marqueur est en position de TYPE de
        // paramètre : items attendus (position travaillée).
        List<CompletionItem> items = completer("        void autre(Bund");
        assertTrue(!items.isEmpty(), "le TYPE de paramètre doit proposer");
    }

    @Test
    void nomEnInstructionProposeTypesDuJar() throws Exception {
        // « Alp| » au début d'une instruction — le scénario NOM nominal.
        List<CompletionItem> items = completer("        Alp");
        List<String> labels = labels(items);
        assertTrue(labels.contains("Alpha"),
                "Alpha attendu : " + labels);
        assertTrue(labels.contains("AlphaVoisin"),
                "AlphaVoisin attendu : " + labels);
    }

    @Test
    void typeDansGeneriquePropose() throws Exception {
        List<CompletionItem> items = completer("        java.util.List<Alp");
        assertTrue(labels(items).contains("Alpha"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private List<CompletionItem> completer(String corps) throws Exception {
        String source = "package exemple;\n\npublic class Essai {\n"
                + "    void methode() {\n" + corps + "\n    }\n}\n";
        String prefixe = "package exemple;\n\npublic class Essai {\n"
                + "    void methode() {\n";
        int caret = prefixe.length() + corps.length();
        Files.writeString(fichier, source, StandardCharsets.UTF_8);
        EcjCompletionEngine ce = new EcjCompletionEngine(moteur);
        return ce.complete(fichier.toString(), source, caret);
    }

    private static List<String> labels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) {
            out.add(it.label());
        }
        return out;
    }
}
