package jo.lspjava.ecj.compat;

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
 * Tests des shims ART ({@link ArtCompatVersion},
 * {@link ArtCompatInputStream}, {@link ArtCompatStackWalker}).
 *
 * <p>Ces shims sont les cibles des réécritures bytecode du jar ecJ
 * ({@code :lsp:ecj-art}) — leurs surfaces doivent matcher exactement ce
 * qu'ecJ appelle. Réécrits au lot 1 du traitement D3 (ADR 0110 §5.3)
 * depuis la spécification JDK : ces tests servent de filet de
 * non-régression de la réécriture.</p>
 *
 * @author jo@Dev
 */
class CompatShimsTest {

    // ─── ArtCompatVersion ─────────────────────────────────────────────

    @Test
    void parsesAndOrdersVersions() {
        assertTrue(ArtCompatVersion.parse("8").compareTo(ArtCompatVersion.parse("17")) < 0);
        assertTrue(ArtCompatVersion.parse("17").compareTo(ArtCompatVersion.parse("17")) == 0);
        assertTrue(ArtCompatVersion.parse("21").compareTo(ArtCompatVersion.parse("17")) > 0);
        // "1.8" ordre strictement numérique ([1,8] < [9]) — divergence
        // assumée avec Runtime.Version.parse (qui normalise 1.8→8),
        // conservée par la réécriture D3 lot 1 ; suffisant pour l'usage
        // d'ecj (comparer la version -source à la dernière supportée).
        assertTrue(ArtCompatVersion.parse("1.8").compareTo(ArtCompatVersion.parse("9")) < 0);
        // Ordre JEP 322 : [17,0,1] > [17] (les éléments manquants valent 0)
        assertTrue(ArtCompatVersion.parse("17.0.1").compareTo(ArtCompatVersion.parse("17")) > 0);
        assertTrue(ArtCompatVersion.parse("17.0.2").compareTo(ArtCompatVersion.parse("17.0.1")) > 0);
    }

    @Test
    void toStringReturnsRawString() {
        assertEquals("17.0.1+8", ArtCompatVersion.parse("17.0.1+8").toString());
        assertEquals("1.8", ArtCompatVersion.parse("1.8").toString());
    }

    @Test
    void rejectsMalformedVersions() {
        assertThrows(IllegalArgumentException.class, () -> ArtCompatVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ArtCompatVersion.parse("a.b"));
        assertThrows(IllegalArgumentException.class, () -> ArtCompatVersion.parse("1..2"));
        assertThrows(NullPointerException.class, () -> ArtCompatVersion.parse(null));
    }

    // ─── ArtCompatInputStream ──────────────────────────────────────────

    @Test
    void readAllBytesRoundTrips() throws IOException {
        byte[] data = "Hello, CodeIDE! — éàü".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(data);
        assertArrayEquals(data, ArtCompatInputStream.readAllBytes(in));
    }

    @Test
    void readAllBytesOnEmptyStream() throws IOException {
        assertArrayEquals(new byte[0],
                ArtCompatInputStream.readAllBytes(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void readNBytesLimits() throws IOException {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        // Lit au plus N octets.
        assertArrayEquals(new byte[]{1, 2, 3},
                ArtCompatInputStream.readNBytes(new ByteArrayInputStream(data), 3));
        // N au-delà de la taille — lit tout.
        assertArrayEquals(data,
                ArtCompatInputStream.readNBytes(new ByteArrayInputStream(data), 100));
        // N nul.
        assertArrayEquals(new byte[0],
                ArtCompatInputStream.readNBytes(new ByteArrayInputStream(data), 0));
        assertThrows(IllegalArgumentException.class,
                () -> ArtCompatInputStream.readNBytes(new ByteArrayInputStream(data), -1));
    }

    @Test
    void readAllBytesHandlesLargeStreams() throws IOException {
        // > TAILLE_TAMPON (16384) — vérifie le doublement de tampon.
        byte[] data = new byte[50_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        assertArrayEquals(data,
                ArtCompatInputStream.readAllBytes(new ByteArrayInputStream(data)));
    }

    // ─── ArtCompatStackWalker ──────────────────────────────────────────

    @Test
    void callerClassSkipsShimAndInvoker() {
        Class<?> appelant = new PorteIntermediaire().appeler();
        // L'appelant de la méthode qui invoque getCallerClass est CE test.
        assertEquals(CompatShimsTest.class, appelant);
    }

    @Test
    void optionConstantsMirrorJdk() {
        // Les trois constantes du JDK doivent exister (le patcher déplace
        // les références StackWalker$Option par préfixe) et getInstance
        // rend toujours un marcheur, quelle que soit l'option.
        ArtCompatStackWalker.Option[] valeurs = ArtCompatStackWalker.Option.values();
        assertEquals(3, valeurs.length);
        for (ArtCompatStackWalker.Option o : valeurs) {
            assertTrue(ArtCompatStackWalker.getInstance(o) != null);
        }
    }

    /** Porte un cran sous le test — getCallerClass doit sauter CE shim,
     *  puis la méthode invoquante, pour rendre la classe du test. */
    private static final class PorteIntermediaire {
        Class<?> appeler() {
            return ArtCompatStackWalker.getInstance(
                    ArtCompatStackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        }
    }
}
