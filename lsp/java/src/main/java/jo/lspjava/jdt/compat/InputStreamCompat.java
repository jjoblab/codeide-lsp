package jo.lspjava.jdt.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Backports ART-safe de {@link java.io.InputStream#readAllBytes()} et
 * {@link java.io.InputStream#readNBytes(int)}.
 *
 * <p>Les deux méthodes sont Java 9, mais n'existent sur Android qu'à partir
 * de l'API 33. Le minSdk de CodeIDE est 26 : sur un appareil API 26 à 32, les
 * appels ne résolvent rien et lèvent {@link NoSuchMethodError} au runtime.
 * ecj atteint {@code readAllBytes()} dès son premier parse — le
 * {@code Parser.<clinit>} charge les tables du parseur via
 * {@code Util.getInputStreamAsByteArray(InputStream)}, dont tout le corps est
 * un {@code input.readAllBytes()}. L'erreur remonte dans l'initialiseur
 * statique comme {@link ExceptionInInitializerError} incapturable, ce qui
 * désactive TOUTE l'analyse Java.</p>
 *
 * <p>Le build réécrit les call-sites {@code INVOKEVIRTUAL
 * java/io/InputStream.readAllBytes ()[B} (et {@code readNBytes (I)[B}) des
 * classes ecj en {@code INVOKESTATIC} vers les méthodes ci-dessous, qui
 * lisent le flux avec les primitives {@code read(byte[])} disponibles dès
 * l'API 1. La réécriture ne touche qu'ecj ; les shims sont inoffensifs sur
 * une JVM complète.</p>
 *
 * <p>Porté de {@code dev.ide.lang.jdt.compat.InputStreamCompat} (projet de référence,
 * GPLv3) — adapté au package {@code jo.lspjava}.</p>
 *
 * @author jo@Dev
 */
public final class InputStreamCompat {

    private InputStreamCompat() {}

    private static final int CHUNK = 8192;

    /** Lit {@code input} jusqu'à la fin — équivalent de
     *  {@link InputStream#readAllBytes()}. */
    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(CHUNK, input.available()));
        byte[] buffer = new byte[CHUNK];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Lit jusqu'à {@code length} octets, en bloquant jusqu'à les avoir tous
     * ou la fin du flux — équivalent de {@link InputStream#readNBytes(int)}.
     */
    public static byte[] readNBytes(InputStream input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("length < 0: " + length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, Math.min(length, CHUNK)));
        byte[] buffer = new byte[Math.min(length, CHUNK)];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(remaining, buffer.length));
            if (read == -1) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }
}
