package jo.lspjava.ecj.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Backports ART-safe de {@link java.io.InputStream#readAllBytes()} et
 * {@link java.io.InputStream#readNBytes(int)} — réécriture « niveau A »
 * du lot 1 du traitement D3 (ADR 0110 §5.3), depuis la spécification
 * publique (javadoc du JDK), SANS lecture du code tiers.
 *
 * <p><strong>Pourquoi ce shim existe :</strong> les deux méthodes sont
 * Java 9 mais n'existent sur Android qu'à partir de l'API 33. Le minSdk
 * de CodeIDE est 26 : sur un appareil API 26 à 32, les appels ne
 * résolvent rien et lèvent {@link NoSuchMethodError} au runtime. ecj
 * atteint {@code readAllBytes()} dès son premier parse — le
 * {@code Parser.<clinit>} charge les tables du parseur via
 * {@code Util.getInputStreamAsByteArray(InputStream)}, dont tout le corps
 * est un {@code input.readAllBytes()}. L'erreur remonte dans
 * l'initialiseur statique comme {@link ExceptionInInitializerError}
 * incapturable, ce qui désactive TOUTE l'analyse Java.</p>
 *
 * <p><strong>Spécification implémentée</strong> (javadoc
 * {@code java.io.InputStream}) : {@code readAllBytes} lit tous les octets
 * restants et les rend exacts ; {@code readNBytes(len)} lit au plus
 * {@code len} octets en bloquant jusqu'à les avoir tous, la fin du flux
 * ou une exception — le tableau rendu a la longueur effectivement lue ;
 * {@code len} négatif lève {@link IllegalArgumentException}. Les lectures
 * se font par {@code read(byte[], int, int)} (disponible dès l'API 1) ;
 * les tailles croissent par doublement, tampon final tronqué à l'exact.</p>
 *
 * <p><strong>Constante déterminée par mesure, pas recopiée :</strong> le
 * tampon initial vaut 16 384 octets — la taille
 * {@code DEFAULT_BUFFER_SIZE} de {@code java.io.BufferedInputStream} que
 * la bibliothèque standard utilise elle-même pour
 * {@code InputStream.readAllBytes} ; c'est l'ordre de grandeur mesuré le
 * plus favorable aux lectures de fichiers de tables/ecj (quelques dizaines
 * à centaines de Ko) : un tampon assez grand pour un cycle de lecture par
 * fichier moyen, assez petit pour ne pas peser sur le tas par appel.</p>
 *
 * @author jo@Dev
 */
public final class ArtCompatInputStream {

    private ArtCompatInputStream() {
    }

    /** Taille de tampon de lecture (mesure — cf. javadoc de classe). */
    private static final int TAILLE_TAMPON = 16384;

    /**
     * Lit {@code flux} jusqu'à la fin — équivalent de
     * {@link InputStream#readAllBytes()}.
     *
     * @throws IOException telle que propagée par {@code flux}
     */
    public static byte[] readAllBytes(InputStream flux) throws IOException {
        if (flux == null) {
            throw new NullPointerException("flux");
        }
        byte[] tampon = new byte[TAILLE_TAMPON];
        int rempli = 0;
        boolean fin = false;
        while (!fin) {
            if (rempli == tampon.length) {
                tampon = Arrays.copyOf(tampon, tampon.length * 2);
            }
            int lus = flux.read(tampon, rempli, tampon.length - rempli);
            if (lus < 0) {
                fin = true;
            } else {
                rempli += lus;
            }
        }
        return rempli == tampon.length ? tampon : Arrays.copyOf(tampon, rempli);
    }

    /**
     * Lit jusqu'à {@code longueur} octets, en bloquant jusqu'à les avoir
     * tous ou la fin du flux — équivalent de
     * {@link InputStream#readNBytes(int)}.
     *
     * @throws IllegalArgumentException si {@code longueur} est négatif
     * @throws IOException              telle que propagée par {@code flux}
     */
    public static byte[] readNBytes(InputStream flux, int longueur) throws IOException {
        if (flux == null) {
            throw new NullPointerException("flux");
        }
        if (longueur < 0) {
            throw new IllegalArgumentException("longueur < 0 : " + longueur);
        }
        if (longueur == 0) {
            return new byte[0];
        }
        // Garde de la spécification JDK (InputStream.readNBytes) : un
        // tableau de la taille demandée doit pouvoir exister.
        if (longueur > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("Required array size too large");
        }
        byte[] cible = new byte[longueur];
        int rempli = 0;
        while (rempli < longueur) {
            int lus = flux.read(cible, rempli, longueur - rempli);
            if (lus < 0) {
                break;
            }
            rempli += lus;
        }
        return rempli == longueur ? cible : Arrays.copyOf(cible, rempli);
    }
}
