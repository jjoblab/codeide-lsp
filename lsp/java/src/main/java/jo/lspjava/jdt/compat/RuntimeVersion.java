package jo.lspjava.jdt.compat;

import java.util.Objects;

/**
 * Remplaçant ART-safe de {@link java.lang.Runtime.Version}.
 *
 * <p>{@code java.lang.Runtime.Version} existe sur toute JVM depuis Java 9,
 * mais il est <em>absent du runtime Android</em> (pas dans android.jar, pas
 * dans ART) et il ne peut pas être stubbé — les classes d'application n'ont
 * pas le droit de vivre dans l'espace de noms {@code java.*}. Le
 * {@code Parser} d'ecj le référence (uniquement) pour comparer la version
 * {@code -source} demandée à la dernière version supportée et émettre un
 * avertissement « trop récent ». Sur ART, cette référence remonte en
 * {@link LinkageError} incapturable qui désactive toute l'analyse Java.</p>
 *
 * <p>Le build réécrit donc les références d'ecj vers cette classe (voir
 * {@code :ecj-art} / {@code :lspjava:patchEcjForArt}). Seule la surface
 * réellement utilisée par le Parser est implémentée — la fabrique statique
 * {@link #parse(String)}, {@link #compareTo(RuntimeVersion)} et
 * {@link #toString()}. L'ordre suit la séquence numérique {@code $VNUM} de la
 * grammaire JEP 322, suffisant pour les chaînes de version que ecj parse
 * ({@code "1.8"}, {@code "11"}, {@code "17"}, {@code "21"}…) ; tout suffixe
 * pré-release/build/optionnel est ignoré. Le comportement d'exception
 * reflète la vraie méthode pour que les {@code catch (Exception)} voisins
 * d'ecj continuent de fonctionner.</p>
 *
 * <p>Porté de {@code dev.ide.lang.jdt.compat.RuntimeVersion} (projet de référence,
 * GPLv3) — adapté au package {@code jo.lspjava}.</p>
 *
 * @author jo@Dev
 */
public final class RuntimeVersion implements Comparable<RuntimeVersion> {

    private final int[] vnum;
    private final String raw;

    private RuntimeVersion(int[] vnum, String raw) {
        this.vnum = vnum;
        this.raw = raw;
    }

    /**
     * Parse une chaîne de version — équivalent, pour nos usages, de
     * {@code java.lang.Runtime.Version.parse}.
     *
     * @throws NullPointerException     si {@code version} est null
     * @throws IllegalArgumentException si la chaîne est invalide
     */
    public static RuntimeVersion parse(String version) {
        Objects.requireNonNull(version, "version string");
        // Retire les suffixes -$PRE / +$BUILD / -$OPT — seul $VNUM ordonne.
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '+') { cut = i; break; }
        }
        String vstr = version.substring(0, cut);
        if (vstr.isEmpty()) throw invalid(version);

        String[] parts = vstr.split("\\.", -1);
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) throw invalid(version);
            int value;
            try {
                value = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw invalid(version);
            }
            if (value < 0) throw invalid(version);
            v[i] = value;
        }
        return new RuntimeVersion(v, version);
    }

    /** Compare les séquences numériques élément par élément ; les éléments
     *  manquants en queue comptent pour zéro. */
    @Override
    public int compareTo(RuntimeVersion other) {
        int n = Math.max(this.vnum.length, other.vnum.length);
        for (int i = 0; i < n; i++) {
            int a = i < this.vnum.length ? this.vnum[i] : 0;
            int b = i < other.vnum.length ? other.vnum[i] : 0;
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;
    }

    /** La chaîne originale, comme {@code Runtime.Version#toString}. */
    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuntimeVersion && compareTo((RuntimeVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int x : vnum) h = h * 31 + x;
        return h;
    }

    private static IllegalArgumentException invalid(String version) {
        return new IllegalArgumentException("Invalid version string: '" + version + "'");
    }
}
