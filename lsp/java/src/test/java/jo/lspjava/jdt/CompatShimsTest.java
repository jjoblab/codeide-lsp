package jo.lspjava.jdt.compat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des shims ART ({@link RuntimeVersion}, {@link InputStreamCompat}).
 *
 * <p>Ces shims sont les cibles des réécritures bytecode du jar ecJ
 * ({@code :lspjava:patchEcjForArt}) — leurs surfaces doivent matcher
 * exactement ce qu'ecJ appelle.</p>
 *
 * @author jo@Dev
 */
class CompatShimsTest {

    // ─── RuntimeVersion ───────────────────────────────────────────────

    @Test
    void parsesAndOrdersVersions() {
        assertTrue(RuntimeVersion.parse("8").compareTo(RuntimeVersion.parse("17")) < 0);
        assertTrue(RuntimeVersion.parse("17").compareTo(RuntimeVersion.parse("17")) == 0);
        assertTrue(RuntimeVersion.parse("21").compareTo(RuntimeVersion.parse("17")) > 0);
        // "1.8" ordre strictement numérique ([1,8] < [9]) — divergence
        // assumée avec Runtime.Version.parse (qui normalise 1.8→8), identique
        // au shim projet de référence d'origine ; suffisant pour l'usage d'ecj
        // (comparer la version -source à la dernière version supportée).
        assertTrue(RuntimeVersion.parse("1.8").compareTo(RuntimeVersion.parse("9")) < 0);
        // Ordre JEP 322 : [17,0,1] > [17] (les éléments manquants valent 0)
        assertTrue(RuntimeVersion.parse("17.0.1").compareTo(RuntimeVersion.parse("17")) > 0);
        assertTrue(RuntimeVersion.parse("17.0.2").compareTo(RuntimeVersion.parse("17.0.1")) > 0);
    }

    @Test
    void toStringReturnsRawString() {
        assertEquals("17.0.1+8", RuntimeVersion.parse("17.0.1+8").toString());
        assertEquals("1.8", RuntimeVersion.parse("1.8").toString());
    }

    @Test
    void rejectsMalformedVersions() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> RuntimeVersion.parse("a.b"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeVersion.parse("1..2"));
        assertThrows(NullPointerException.class, () -> RuntimeVersion.parse(null));
    }

    // ─── InputStreamCompat ────────────────────────────────────────────

    @Test
    void readAllBytesRoundTrips() throws IOException {
        byte[] data = "Hello, CodeIDE! — éàü".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(data);
        assertArrayEquals(data, InputStreamCompat.readAllBytes(in));
    }

    @Test
    void readAllBytesOnEmptyStream() throws IOException {
        assertArrayEquals(new byte[0],
                InputStreamCompat.readAllBytes(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void readNBytesLimits() throws IOException {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        // Lit au plus N octets.
        assertArrayEquals(new byte[]{1, 2, 3},
                InputStreamCompat.readNBytes(new ByteArrayInputStream(data), 3));
        // N au-delà de la taille — lit tout.
        assertArrayEquals(data,
                InputStreamCompat.readNBytes(new ByteArrayInputStream(data), 100));
        // N nul.
        assertArrayEquals(new byte[0],
                InputStreamCompat.readNBytes(new ByteArrayInputStream(data), 0));
        assertThrows(IllegalArgumentException.class,
                () -> InputStreamCompat.readNBytes(new ByteArrayInputStream(data), -1));
    }

    @Test
    void readAllBytesHandlesLargeStreams() throws IOException {
        // > CHUNK (8192) — vérifie la boucle tampon.
        byte[] data = new byte[50_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        assertArrayEquals(data,
                InputStreamCompat.readAllBytes(new ByteArrayInputStream(data)));
    }
}
