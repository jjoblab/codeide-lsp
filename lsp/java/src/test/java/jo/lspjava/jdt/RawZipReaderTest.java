package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lecteur ZIP minimaliste {@link RawZipReader}.
 *
 * <p>Chaque propriété est vérifiée PAR RAPPORT à {@link ZipFile} (vérité
 * terrain JDK) sur un zip de synthèse couvrant les cas réels des jars :
 * entrées DEFLATED, entrées STORED (classes déjà compressées incompressibles
 * / ressources), sous-répertoires profonds, noms UTF-8, grand fichier.</p>
 *
 * @author jo@Dev
 */
class RawZipReaderTest {

    @TempDir
    Path tmp;

    /** Construit un jar de synthèse : DEFLATED + STORED + dossiers + UTF-8. */
    private File buildSampleZip(String name) throws Exception {
        File f = new File(tmp.toFile(), name);
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.FileOutputStream(f))) {
            // Entrée DEFLATED classique.
            zos.putNextEntry(new ZipEntry("com/example/Alpha.class"));
            zos.write(("class Alpha{int x=" + "A".length() + ";}")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Entrées imbriquées (paquets internes, common case AndroidX).
            zos.putNextEntry(new ZipEntry("com/example/R$string.class"));
            zos.write("nested".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Entrée STORED (méthode 0) — chemin distinct dans readEntry.
            ZipEntry stored = new ZipEntry("META-INF/MANIFEST.MF");
            stored.setMethod(ZipEntry.STORED);
            byte[] manifest = ("Manifest-Version: 1.0\nBuilt-By: test\n")
                    .getBytes(StandardCharsets.UTF_8);
            stored.setSize(manifest.length);
            stored.setCrc(crc32(manifest));
            zos.putNextEntry(stored);
            zos.write(manifest);
            zos.closeEntry();

            // Nom UTF-8 avec accent (ressources rares mais possibles).
            zos.putNextEntry(new ZipEntry("déjà/ClassÉ.class"));
            zos.write("utf8".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Payload plus gros pour dépasser un seul buffer d'inflation.
            zos.setLevel(Deflater.BEST_COMPRESSION);
            zos.putNextEntry(new ZipEntry("big/Data.class"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50_000; i++) {
                sb.append("x").append(i % 10);
            }
            zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return f;
    }

    private static long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    @Test
    void scanNamesMatchesZipFileGroundTruth() throws Exception {
        File zip = buildSampleZip("sample.jar");
        Set<String> expected = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                expected.add(it.nextElement().getName());
            }
        }
        Set<String> actual = RawZipReader.scanNames(zip.toPath());
        assertEquals(expected, actual);
    }

    @Test
    void readSingleDeflatedEqualsZipFile() throws Exception {
        File zip = buildSampleZip("deflated.jar");
        String entry = "com/example/Alpha.class";
        byte[] expected;
        try (ZipFile zf = new ZipFile(zip)) {
            try (var in = zf.getInputStream(zf.getEntry(entry))) {
                expected = in.readAllBytes();
            }
        }
        byte[] actual = RawZipReader.readSingle(zip.toPath(), entry);
        assertArrayEquals(expected, actual);
    }

    @Test
    void readSingleStoredEqualsZipFile() throws Exception {
        File zip = buildSampleZip("stored.jar");
        String entry = "META-INF/MANIFEST.MF";
        byte[] expected;
        try (ZipFile zf = new ZipFile(zip)) {
            try (var in = zf.getInputStream(zf.getEntry(entry))) {
                expected = in.readAllBytes();
            }
        }
        byte[] actual = RawZipReader.readSingle(zip.toPath(), entry);
        assertArrayEquals(expected, actual);
    }

    @Test
    void readLargeMultiBufferEntry() throws Exception {
        File zip = buildSampleZip("big.jar");
        String entry = "big/Data.class";
        byte[] actual = RawZipReader.readSingle(zip.toPath(), entry);
        // Vérifie la longueur attendue (« x<i mod 10> » × 50 000 = 100 000
        // caractères) et le motif début/fin.
        assertEquals(100_000, actual.length);
        String text = new String(actual, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("x0x1x2"), text.substring(0, 12));
        // Motif périodique « x<i mod 10> » : la fin est …x7x8x9.
        assertTrue(text.endsWith("x8x9"), text.substring(text.length() - 6));
    }

    @Test
    void utf8EntriesReadable() throws Exception {
        File zip = buildSampleZip("utf8.jar");
        String entry = "déjà/ClassÉ.class";
        assertTrue(RawZipReader.scanNames(zip.toPath()).contains(entry));
        assertEquals("utf8", new String(
                RawZipReader.readSingle(zip.toPath(), entry),
                StandardCharsets.UTF_8));
    }

    @Test
    void persistentHandleLifecycle() throws Exception {
        File zip = buildSampleZip("handle.jar");
        byte[] firstShot = RawZipReader.readSingle(zip.toPath(),
                "com/example/Alpha.class");

        byte[] viaHandle;
        try (RawZipReader.Handle h = RawZipReader.open(zip.toPath())) {
            assertTrue(h.contains("com/example/Alpha.class"));
            assertFalse(h.contains("absent/Nope.class"));
            // Double lecture successive — le handle est réutilisable.
            byte[] a = h.read("com/example/R$string.class");
            byte[] b = h.read("com/example/Alpha.class");
            assertEquals("nested", new String(a, StandardCharsets.UTF_8));
            viaHandle = b;
        }
        assertArrayEquals(firstShot, viaHandle);
    }

    @Test
    void missingOrTruncatedFilesAreRejectedCleanly() throws Exception {
        File ghost = new File(tmp.toFile(), "ghost.jar");
        assertThrows(java.io.IOException.class,
                () -> RawZipReader.open(ghost.toPath()));

        File truncated = new File(tmp.toFile(), "trunc.jar");
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.FileOutputStream(truncated))) {
            zos.putNextEntry(new ZipEntry("a/B.class"));
            zos.write("data".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] corrupted = java.nio.file.Files.readAllBytes(truncated.toPath());
        byte[] cut = new byte[corrupted.length / 2]; // EOCD amputé
        System.arraycopy(corrupted, 0, cut, 0, cut.length);
        java.nio.file.Files.write(truncated.toPath(), cut);
        assertThrows(java.io.IOException.class,
                () -> RawZipReader.open(truncated.toPath()));
        assertNotNull(truncated);
    }
}
