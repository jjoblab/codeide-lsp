package jo.lspjava.jdt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lecteur ZIP minimaliste SANS {@code java.util.zip.ZipFile}.
 *
 * <h2>Pourquoi réécrire ce que le JDK sait déjà faire ?</h2>
 *
 * <p>Les logs de production (device réel) montrent ~2 259 warnings
 * {@code System: A resource failed to call ZipFile.close.} en une seule
 * session d'édition (~30-60/seconde). Or toutes les ouvertures étaient en
 * try-with-resources — fermées proprement. Le CloseGuard interne de
 * libcore warnIfOpen() déclenche donc SUR CETTE ROM même pour des
 * instances correctement close() (comportement observé : un pool de
 * NoCloseZipFile persistants produit UNE rafale au swap d'index ; des
 * lectures transitoires produisent chacune leur warning — la fermeture
 * explicite ne suffit pas à supprimer l'alerte du finaliseur).
 * Conséquence empirique : <strong>tant qu'on instancie
 * {@code java.util.zip.ZipFile}, on alimente les warnings</strong>.</p>
 *
 * <p>Solution structurelle : parser nous-mêmes le répertoire central du zip
 * (format trivial) avec uniquement {@link RandomAccessFile} (dont le
 * CloseGuard se comporte correctement depuis toujours — sinon TOUTE
 * application Android flood-erait) + {@link Inflater} (aucun guard). Le
 * message « ZipFile.close » devient IMPOSSIBLE côté :lspjava car plus
 * aucune instance de cette classe n'est créée.</p>
 *
 * <h2>Format implémenté (spécification PKWARE APPNOTE)</h2>
 * <ul>
 *   <li>EOCD (0x06054b50) localisé par scan arrière des 64 Ko finaux ;</li>
 *   <li>répertoire central (0x02014b50) : offset header local, taille
 *       compressée, méthode (0=STORED, 8=DEFLATED), nom d'entrée UTF-8 ;</li>
 *   <li>header local (0x04034b50) : saut nom+extra puis lecture brute.</li>
 * </ul>
 *
 * <p>ZIP64 non supporté (aucun jar Android ne dépasse 4 Go / 65 535 entrées
 * — android.jar ≈ 30 Ko entrées, largement sous la limite).</p>
 *
 * <h2>Thread-safety</h2>
 * <p>{@link Handle} synchronise chaque lecture (les accès concurrents ecj
 * multi-lanes restent corrects) ; les méthodes statiques transitoires ouvrent
 * leur propre RAF par appel.</p>
 *
 * @author jo@Dev
 */
public final class RawZipReader {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;

    private static final int EOCOff = 8;    // u16 entries
    private static final int EOCD_SIZE = 22;

    private static final int CEN_METHOD = 10;      // u16
    private static final int CEN_CSIZE = 20;       // u32
    private static final int CEN_USIZE = 24;       // u32
    private static final int CEN_NAMELEN = 28;     // u16
    private static final int CEN_EXTRALEN = 30;    // u16
    private static final int CEN_COMMENTLEN = 32;  // u16
    private static final int CEN_LOCOPEN = 42;     // u32
    private static final int CEN_FIXED = 46;

    /** Répertoire central parsé : nom → métadonnées d'entrée. */
    static final class Directory {
        final Map<String, EntryInfo> byName = new LinkedHashMap<>();
    }

    static final class EntryInfo {
        final long localHeaderOffset;
        final long csize;
        final long usize;
        final int method;

        EntryInfo(long localHeaderOffset, long csize, long usize, int method) {
            this.localHeaderOffset = localHeaderOffset;
            this.csize = csize;
            this.usize = usize;
            this.method = method;
        }
    }

    /** Handle persistant léger : UN fd ({@link RandomAccessFile}) + le CD. */
    public static final class Handle implements AutoCloseable {
        private final RandomAccessFile raf;
        private final Directory dir;

        private Handle(RandomAccessFile raf, Directory dir) {
            this.raf = raf;
            this.dir = dir;
        }

        public Set<String> names() {
            return java.util.Collections.unmodifiableSet(dir.byName.keySet());
        }

        public boolean contains(String name) {
            return dir.byName.containsKey(name);
        }

        /** Métadonnées d'entrée (tailles/méthode) ou null si absente. */
        EntryInfo info(String name) {
            return dir.byName.get(name);
        }

        public synchronized byte[] read(String name) throws IOException {
            return readEntry(raf, dir, name);
        }

        @Override
        public synchronized void close() {
            try {
                raf.close();
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
    }

    private RawZipReader() {
        // Utilitaire statique.
    }

    // ─── Accès transitoires (ouvre/parse/referme immédiatement) ──────────

    /** Ouvre, parse et referme — rend le set des noms d'entrées. */
    public static Set<String> scanNames(Path zipPath) throws IOException {
        try (Handle h = open(zipPath)) {
            return h.names();
        }
    }

    /** Ouvre, lit UNE entrée et referme immédiatement. */
    public static byte[] readSingle(Path zipPath, String entryName) throws IOException {
        try (Handle h = open(zipPath)) {
            return h.read(entryName);
        }
    }

    /**
     * Ouvre un handle persistant (UN fd). À refermer explicitement
     * ({@code dispose()} du moteur) — c'est le SEUL cas où un fd vit plus
     * longtemps que l'appel, borné par le nombre de jars volumineux
     * (android.jar…), fermés au shutdown LSP.
     */
    public static Handle open(Path zipPath) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(zipPath.toFile(), "r");
        try {
            Directory dir = parseCentralDirectory(raf);
            return new Handle(raf, dir);
        } catch (Throwable t) {
            try {
                raf.close();
            } catch (Throwable ignored) {
                // Best-effort.
            }
            throw new IOException("RawZipReader: " + zipPath.getFileName(), t);
        }
    }

    // ─── Parsing ─────────────────────────────────────────────────────────

    private static Directory parseCentralDirectory(RandomAccessFile raf)
            throws IOException {
        long len = raf.length();
        if (len < EOCD_SIZE) {
            throw new IOException("fichier trop court");
        }
        // Scan arrière de l'EOCD dans les derniers min(len,64Ko)+22 octets.
        long windowStart = Math.max(0, len - (64 * 1024));
        byte[] window = new byte[(int) (len - windowStart)];
        raf.seek(windowStart);
        raf.readFully(window);

        long eocdPosInWindow = -1;
        for (int i = window.length - EOCD_SIZE; i >= 0; i--) {
            if (u32(window, i) == EOCD_SIG) {
                eocdPosInWindow = i;
                break;
            }
        }
        if (eocdPosInWindow < 0) {
            throw new IOException("EOCD introuvable");
        }
        int entryCount = u16(window, (int) eocdPosInWindow + EOCOff);
        long cdOffset = u32(window, (int) eocdPosInWindow + 16)
                & 0xFFFFFFFFL;
        long cdSize = u32(window, (int) eocdPosInWindow + 12)
                & 0xFFFFFFFFL;

        Directory dir = new Directory();
        if (entryCount <= 0 || cdOffset >= len) {
            return dir; // zip vide ou anomalie — renvoyer vide plutôt que lever
        }

        byte[] cd = new byte[(int) Math.min(cdSize > 0 ? cdSize : len - cdOffset,
                Integer.MAX_VALUE)];
        raf.seek(cdOffset);
        raf.readFully(cd);

        int pos = 0;
        for (int seen = 0; seen < entryCount && pos + CEN_FIXED <= cd.length;
             seen++) {
            if (u32(cd, pos) != CEN_SIG) break; // parsing hors synchro — stop
            int method = u16(cd, pos + CEN_METHOD);
            long csize = u32(cd, pos + CEN_CSIZE) & 0xFFFFFFFFL;
            long usize = u32(cd, pos + CEN_USIZE) & 0xFFFFFFFFL;
            int nameLen = u16(cd, pos + CEN_NAMELEN);
            int extraLen = u16(cd, pos + CEN_EXTRALEN);
            int commentLen = u16(cd, pos + CEN_COMMENTLEN);
            long locOpen = u32(cd, pos + CEN_LOCOPEN) & 0xFFFFFFFFL;

            if (pos + CEN_FIXED + nameLen > cd.length) break;
            String name = new String(cd, pos + CEN_FIXED, nameLen,
                    StandardCharsets.UTF_8);
            dir.byName.put(name,
                    new EntryInfo(locOpen, csize, usize, method));
            pos += CEN_FIXED + nameLen + extraLen + commentLen;
        }
        return dir;
    }

    /** Lit (et si besoin inflate) l'entrée {@code name}. */
    private static byte[] readEntry(RandomAccessFile raf, Directory dir,
                                    String name) throws IOException {
        EntryInfo info = dir.byName.get(name);
        if (info == null) return null;

        byte[] loc = new byte[30];
        raf.seek(info.localHeaderOffset);
        raf.readFully(loc);
        if (u32(loc, 0) != LOC_SIG) {
            throw new IOException("signature locale invalide");
        }
        int nameLen = u16(loc, 26);
        int extraLen = u16(loc, 28);
        long dataStart = info.localHeaderOffset + 30 + nameLen + extraLen;

        if (info.csize == 0 || info.csize > Integer.MAX_VALUE - 8) {
            return null;
        }
        byte[] compressed = new byte[(int) info.csize];
        raf.seek(dataStart);
        raf.readFully(compressed);

        if (info.method == 0 /* STORED */) {
            return compressed;
        }
        if (info.method != 8 /* DEFLATED */) {
            return null; // méthode inconnue — entrée ignorée
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(64, compressed.length * 3));
            byte[] buf = new byte[16384];
            while (!inflater.finished()) {
                int nread = inflater.inflate(buf);
                if (nread == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break; // flux tronqué — on rend ce qu'on a déjà?
                        // Non : tronqué = échec → exception ci-dessous.
                    }
                } else {
                    out.write(buf, 0, nread);
                }
            }
            if (!inflater.finished()) {
                throw new DataFormatException("flux deflate tronqué");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("deflate échoué pour " + name, e);
        } finally {
            inflater.end();
        }
    }

    // ─── Primitives little-endian ────────────────────────────────────────

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
