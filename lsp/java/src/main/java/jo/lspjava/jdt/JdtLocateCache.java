package jo.lspjava.jdt;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Cache de localisation de classes — mappe un FQCN vers le jar du
 * classpath qui le définit, avec <strong>sentinelle négative</strong> :
 * un échec de recherche est mémorisé aussi (« pas sur CE classpath »),
 * et non re-probé à chaque résolution.
 *
 * <p><strong>Motivation :</strong> sans ce cache, chaque lecture d'un type
 * binaire ({@code binaryType}, {@code classBytes}) sonde les N jars du
 * classpath l'un après l'autre. ecj résout des centaines de types par
 * frappe (super-types, types d'arguments, retours…) ; sur un classpath
 * Android (~200 jars), les sondages répétés dominent le coût de la
 * complétion. Avec le cache, une recherche coûte une lecture de map —
 * le premier miss seul paie le sondage.</p>
 *
 * <p>Portage du pattern {@code locateCache} de {@code JdtEnvironmentCache.kt}
 * (projet de référence v3.20) : LRU par ordre d'accès borné à {@link #MAX_ENTRIES},
 * sentinelle {@link #NOT_FOUND} pour les négatifs, invalidation totale sur
 * changement de classpath.</p>
 *
 * <p><strong>Thread-safety :</strong> accès verrouillés par un
 * {@link ReentrantLock} (les lectures complètent sous le verrou, comme le
 * pool LRU du projet de référence, pour qu'un éviction concurrente ne ferme jamais
 * un handle en cours d'utilisation).</p>
 *
 * @author jo@Dev
 */
public final class JdtLocateCache {

    /** Capacité maximale (entrées positives ET négatives confondues). */
    static final int MAX_ENTRIES = 8_192;

    /**
     * Sentinelle « vérifié, absent de ce classpath » — un négatif est
     * mémorisé, pas re-sondé.
     */
    static final Path NOT_FOUND = Path.of("__lspjava_locate_miss__");

    private final ReentrantLock lock = new ReentrantLock();

    /** fqcn → jar propriétaire (ou {@link #NOT_FOUND}). LRU d'accès. */
    private final LinkedHashMap<String, Path> cache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, Path> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Le jar propriétaire du FQCN, via le cache ; au premier appel pour un
     * FQCN, la sonde {@code prober} est exécutée (elle doit retourner le
     * jar ou {@code null}) puis le résultat — même négatif — est mémorisé.
     *
     * @param fqcn   le nom pleinement qualifié (avec ou sans {@code $})
     * @param prober la sonde de repli (itération des jars du classpath)
     * @return le jar propriétaire, ou {@code null} si absent du classpath
     */
    public Path locate(String fqcn, Function<String, Path> prober) {
        if (fqcn == null || fqcn.isEmpty()) return null;
        lock.lock();
        try {
            Path hit = cache.get(fqcn);
            if (hit != null) return hit == NOT_FOUND ? null : hit;
        } finally {
            lock.unlock();
        }
        // Sonde HORS verrou : elle peut être coûteuse (open/scan jar).
        Path found;
        try {
            found = prober.apply(fqcn);
        } catch (Throwable t) {
            // Sonde défaillante — traité comme absent, non mémorisé.
            return null;
        }
        lock.lock();
        try {
            cache.put(fqcn, found != null ? found : NOT_FOUND);
        } finally {
            lock.unlock();
        }
        return found;
    }

    /**
     * Variantes de types imbriqués : un type {@code a.b.Outer$Inner} vit
     * dans le jar de son type englobant {@code a.b.Outer} — si le FQCN
     * contient un {@code $}, tenter d'abord le préfixe englobant.
     *
     * @param fqcn   le nom binaire demandé (peut contenir des {@code $})
     * @param prober la sonde de repli par FQCN de type TOP-LEVEL
     * @return le jar propriétaire, ou {@code null}
     */
    public Path locateBinary(String fqcn, Function<String, Path> prober) {
        if (fqcn == null || fqcn.isEmpty()) return null;
        Path direct = locate(fqcn, prober);
        if (direct != null) return direct;
        int dollar = fqcn.indexOf('$');
        if (dollar > 0) {
            return locate(fqcn.substring(0, dollar), prober);
        }
        return null;
    }

    /**
     * Lecture d'une entrée {@code .class} dans un jar connu, avec
     * mémorisation du négatif par FQCN. Complète la localisation pour
     * les appels du style {@code bytes(fqcn)}.
     *
     * @param fqcn    le type demandé
     * @param prober  sonde de localisation (jar ou null)
     * @param reader  lecteur d'entrée (jar, entryName) → octets ou null
     * @return les octets du {@code .class}, ou {@code null}
     */
    public byte[] bytes(String fqcn, Function<String, Path> prober,
                        BiReader reader) {
        Path jar = locateBinary(fqcn, prober);
        if (jar == null) return null;
        String entryName = fqcn.replace('.', '/') + ".class";
        try {
            return reader.read(jar, entryName);
        } catch (Throwable ignored) {
            // Handle transitoirement invalide — traité comme absent.
            return null;
        }
    }

    /** Lecteur d'entrée de jar (utilisé par {@link #bytes}). */
    @FunctionalInterface
    public interface BiReader {
        byte[] read(Path jar, String entryName) throws Exception;
    }

    /**
     * Invalidation totale — à appeler sur tout changement de classpath
     * (un négatif mémorisé sous l'ancien classpath doit s'auto-guérir).
     */
    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Nombre d'entrées courantes ( observabilité/tests). */
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }
}
