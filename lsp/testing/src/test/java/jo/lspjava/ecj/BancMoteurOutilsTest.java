package jo.lspjava.ecj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des UTILITAIRES du banc moteur (lot F2 — docs/LSP_JAVA.md
 * §15.8) : percentiles, émission JSON, normalisation du corpus
 * (AAR → classes.jar, dédoublonnage, entrées ignorées) et sources des
 * scénarios. Le banc lui-même (JVM séparée, jeux §12) vit hors de la
 * chaîne de vérification — ces tests garantissent la FIABILITÉ de ses
 * calculs.
 *
 * @author jo@Dev
 */
class BancMoteurOutilsTest {

    @TempDir
    Path tmp;

    // ─── Percentiles ────────────────────────────────────────────────

    @Test
    void percentilesRangLePlusProche() {
        long[] valeurs = {10, 1, 9, 2, 8, 3, 7, 4, 6, 5};
        // Trié : 1..10 — p50 = rang ceil(5) = 5 → 5 ; p95 = rang 10 → 10.
        assertEquals(5, BancMoteur.percentile(valeurs, 50));
        assertEquals(10, BancMoteur.percentile(valeurs, 95));
        assertEquals(1, BancMoteur.percentile(valeurs, 1));
        // Le tableau d'origine n'est PAS trié (copie interne).
        assertEquals(10, valeurs[0]);
    }

    @Test
    void percentilesCasLimites() {
        assertEquals(-1, BancMoteur.percentile(new long[0], 50));
        assertEquals(-1, BancMoteur.percentile(null, 95));
        long[] unique = {42};
        assertEquals(42, BancMoteur.percentile(unique, 50));
        assertEquals(42, BancMoteur.percentile(unique, 95));
    }

    @Test
    void conversionsMillisecondes() {
        assertEquals(0.5, BancMoteur.msDe(500_000));
        assertEquals(0.001, BancMoteur.msDe(1_000));
        assertEquals(1_400.0, BancMoteur.msDe(1_400_000_000));
    }

    // ─── JSON ───────────────────────────────────────────────────────

    @Test
    void jsonOrdreEtTypes() {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("jeu", "lourd");
        champs.put("jars", 250);
        champs.put("ok", true);
        champs.put("citation", "un \"guillemet\" et un \\");
        String texte = BancMoteur.json(champs);
        assertTrue(texte.contains("\"jeu\": \"lourd\""));
        assertTrue(texte.contains("\"jars\": 250"));
        assertTrue(texte.contains("\"ok\": true"));
        assertTrue(texte.contains("un \\\"guillemet\\\" et un \\\\"));
        // Ordre d'insertion conservé (lecture stable d'un run à l'autre).
        assertTrue(texte.indexOf("\"jeu\"") < texte.indexOf("\"jars\""));
        assertTrue(texte.startsWith("{") && texte.trim().endsWith("}"));
    }

    // ─── Corpus : manifeste normalisé ───────────────────────────────

    @Test
    void corpusNormaliseAarDoublonsEtIgnores() throws Exception {
        // Deux jars (dont un doublon de ligne), un AAR AVEC classes.jar,
        // un chemin absent, un fichier ni jar ni aar.
        Path jar1 = Files.write(tmp.resolve("a.jar"), new byte[]{1});
        Files.write(tmp.resolve("b.jar"), new byte[]{2});
        Path aar = tmp.resolve("lib.aar");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(aar))) {
            zip.putNextEntry(new ZipEntry("classes.jar"));
            zip.write(new byte[]{9, 9, 9});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(new byte[]{0});
            zip.closeEntry();
        }
        Path manifeste = tmp.resolve("manifeste.txt");
        Files.writeString(manifeste, String.join("\n",
                jar1.toString(),
                jar1.toString(),                // doublon — dédupliqué
                tmp.resolve("b.jar").toString(),
                aar.toString(),
                tmp.resolve("absent.jar").toString(),
                tmp.resolve("notes.txt").toString()), StandardCharsets.UTF_8);
        Files.write(tmp.resolve("notes.txt"), new byte[]{3});

        BancMoteur.Corpus corpus = BancMoteur.chargerCorpus(
                manifeste, tmp.resolve("normalises"));

        assertEquals(3, corpus.jars().size(), "a.jar + b.jar + classes.jar");
        assertEquals(1, corpus.aarsNormalises());
        assertEquals(2, corpus.entreesIgnorees(), "absent + notes.txt");
        assertTrue(corpus.jars().stream()
                .anyMatch(p -> p.getFileName().toString()
                        .equals("lib.aar.classes.jar")));
        // Ordre déterministe (tri par chemin).
        List<Path> tri = corpus.jars().stream().sorted()
                .map(p -> p.toAbsolutePath().normalize()).toList();
        assertEquals(tri, corpus.jars().stream()
                .map(p -> p.toAbsolutePath().normalize()).toList());
    }

    @Test
    void normaliserAarEstIdempotentEtTolerant() throws Exception {
        Path aar = tmp.resolve("lib.aar");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(aar))) {
            zip.putNextEntry(new ZipEntry("classes.jar"));
            zip.write(new byte[]{7});
            zip.closeEntry();
        }
        Path extrait = BancMoteur.normaliserAar(aar, tmp.resolve("norm"));
        assertNotNull(extrait);
        assertTrue(Files.isRegularFile(extrait));
        assertEquals(1, Files.size(extrait), "classes.jar recopié tel quel");
        // Second appel : même chemin, PAS de ré-extraction.
        assertEquals(extrait, BancMoteur.normaliserAar(aar, tmp.resolve("norm")));

        // AAR SANS classes.jar → null (compté comme ignoré par le chargeur).
        Path vide = tmp.resolve("vide.aar");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(vide))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.closeEntry();
        }
        assertEquals(null, BancMoteur.normaliserAar(vide, tmp.resolve("norm")));
    }

    // ─── Sources des scénarios ─────────────────────────────────────

    @Test
    void sourcesDiagnoseSuivantLeCorpus() {
        assertTrue(BancMoteur.sourceDiagnose(true)
                .contains("import android.os.Bundle;"));
        assertTrue(BancMoteur.sourceDiagnose(true)
                .contains("new Bundle()"));
        assertTrue(BancMoteur.sourceDiagnose(false)
                .contains("import java.util.ArrayList;"));
        assertFalse(BancMoteur.sourceDiagnose(false).contains("Bundle"));
        // La variante éditée reste DANS le corps de m() (fast-path P6).
        String source = BancMoteur.sourceDiagnose(true);
        assertTrue(source.contains("int x = 0;"));
        assertTrue(source.replace("int x = 0;", "int x = 3;")
                .contains("int x = 3;"));
    }

    @Test
    void effacerRepertoire() throws Exception {
        Path dossier = tmp.resolve("a/b");
        Files.createDirectories(dossier);
        Files.write(dossier.resolve("c.txt"), new byte[]{1});
        BancMoteur.effacer(tmp.resolve("a"));
        assertFalse(Files.exists(tmp.resolve("a")));
        // Idempotent sur un chemin absent.
        BancMoteur.effacer(tmp.resolve("introuvable"));
    }
}
