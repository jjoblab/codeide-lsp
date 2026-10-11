package jo.lspjava.ecj.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Remplaçant ART-safe de {@code java.lang.Runtime.Version} — réécriture
 * « niveau A » du lot 1 du traitement D3 (ADR 0110 §5.3), depuis la
 * spécification publique (javadoc du JDK + grammaire JEP 322), SANS
 * lecture du code tiers.
 *
 * <p><strong>Pourquoi ce shim existe :</strong> {@code java.lang.Runtime
 * .Version} est absent du runtime Android (pas dans {@code android.jar},
 * pas dans ART) et ne peut pas être stubbé — les classes d'application
 * n'ont pas le droit de vivre dans l'espace de noms {@code java.*}. Le
 * {@code Parser} d'ecj le référence pour comparer la version
 * {@code -source} demandée à la dernière version supportée et émettre un
 * avertissement « trop récent ». Sans réécriture, cette référence remonte
 * en {@link LinkageError} incapturable qui désactive toute l'analyse
 * Java.</p>
 *
 * <p><strong>Spécification implémentée</strong> (javadoc
 * {@code java.lang.Runtime.Version.parse} + JEP 322) : une chaîne de
 * version est une séquence {@code $VNUM} d'entiers séparés par des
 * points, optionnellement suivie d'un suffixe pré-release
 * ({@code -$PRE}), build ({@code +$BUILD}) ou optionnel ({@code -$OPT}).
 * {@code parse} lève {@link NullPointerException} pour {@code null} et
 * {@link IllegalArgumentException} pour une séquence invalide (segment
 * vide, non numérique ou négatif). L'ordre est la comparaison élément par
 * élément de {@code $VNUM}, les éléments manquants en queue comptant pour
 * zéro — les suffixes n'ordonnent pas. Divergence assumée et documentée
 * (identique aux besoins d'ecj) : {@code "1.8"} ordonne strictement
 * numériquement (inférieur à {@code "9"}) alors que le JDK normalise
 * {@code 1.8 → 8} ; le JDK ordonne aussi les pré-releases (absent &gt;
 * présent) — inutile pour l'usage d'ecj.</p>
 *
 * <p><strong>Contrainte de surface</strong> : les descripteurs du bytecode
 * ecj patché ({@code :lsp:ecj-art}) imposent exactement
 * {@code parse(String)}, {@code compareTo(ArtCompatVersion)} et
 * {@code toString()} — la réécriture du patcher ne change pas l'arité des
 * méthodes, seulement les noms de classes. {@code equals}/{@code hashCode}
 * suivent l'ordre naturel pour cohérence avec les usages collections.</p>
 *
 * @author jo@Dev
 */
public final class ArtCompatVersion implements Comparable<ArtCompatVersion> {

    /** Segments numériques de {@code $VNUM}, en ordre (au moins un). */
    private final int[] segments;

    /** Chaîne originale (suffixes compris), rendue par {@link #toString()}. */
    private final String brute;

    private ArtCompatVersion(int[] segments, String brute) {
        this.segments = segments;
        this.brute = brute;
    }

    /**
     * Parse une chaîne de version — pour nos usages, équivalent de
     * {@code java.lang.Runtime.Version.parse}.
     *
     * @param version chaîne non null
     * @return la version parsée
     * @throws NullPointerException     si {@code version} est null
     * @throws IllegalArgumentException si {@code $VNUM} est absent, vide,
     *                                  non numérique ou négatif
     */
    public static ArtCompatVersion parse(String version) {
        if (version == null) {
            throw new NullPointerException("version string");
        }
        // 1. Bornes du $VNUM : au premier '-' (pré-release ou optionnel)
        //    ou '+' (build), la séquence numérique s'arrête — tout ce qui
        //    suit est conservé dans la chaîne brute mais n'ordonne pas.
        int finVnum = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '+') {
                finVnum = i;
                break;
            }
        }
        if (finVnum == 0) {
            throw invalide(version);
        }
        // 2. Segmentation par balayage unique : chaque point délimite un
        //    segment ; le dernier délimiteur est la borne du $VNUM.
        List<Integer> lus = new ArrayList<>(4);
        int debutSegment = 0;
        for (int i = 0; i <= finVnum; i++) {
            boolean delimiteur = i == finVnum || version.charAt(i) == '.';
            if (!delimiteur) {
                continue;
            }
            String segment = version.substring(debutSegment, i);
            if (segment.isEmpty()) {
                throw invalide(version);
            }
            int valeur;
            try {
                valeur = Integer.parseInt(segment);
            } catch (NumberFormatException e) {
                throw invalide(version);
            }
            if (valeur < 0) {
                throw invalide(version);
            }
            lus.add(valeur);
            debutSegment = i + 1;
        }
        int[] numeros = new int[lus.size()];
        for (int i = 0; i < numeros.length; i++) {
            numeros[i] = lus.get(i);
        }
        return new ArtCompatVersion(numeros, version);
    }

    /**
     * Ordre de {@code $VNUM} : comparaison élément par élément, les
     * éléments manquants en queue comptent pour zéro
     * ({@code 17.0.1 > 17}, {@code 17 == 17.0}).
     */
    @Override
    public int compareTo(ArtCompatVersion autre) {
        int profondeur = Math.max(segments.length, autre.segments.length);
        for (int i = 0; i < profondeur; i++) {
            int a = i < segments.length ? segments[i] : 0;
            int b = i < autre.segments.length ? autre.segments[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    /** La chaîne originale, suffixes compris (comme le JDK). */
    @Override
    public String toString() {
        return brute;
    }

    /** Égal si même ordre naturel ({@code 17} et {@code 17.0} égaux). */
    @Override
    public boolean equals(Object o) {
        return o instanceof ArtCompatVersion && compareTo((ArtCompatVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(normalise());
    }

    /** {@code $VNUM} avec les zéros de queue retirés — pour un hash
     *  cohérent avec l'égalité ordinale ({@code 17} et {@code 17.0}). */
    private int[] normalise() {
        int utile = segments.length;
        while (utile > 1 && segments[utile - 1] == 0) {
            utile--;
        }
        return utile == segments.length ? segments : Arrays.copyOf(segments, utile);
    }

    private static IllegalArgumentException invalide(String version) {
        return new IllegalArgumentException("Invalid version string: '" + version + "'");
    }
}
