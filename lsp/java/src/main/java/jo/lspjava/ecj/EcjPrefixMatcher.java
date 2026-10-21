package jo.lspjava.ecj;

/**
 * Portage Java du {@code dev.ide.lang.completion.PrefixMatcher}
 * du projet tiers de référence : matching de préfixe gradué, partagé par toutes les sources
 * de candidats du moteur de complétion.
 *
 * <p>Un nom matche à exactement UNE qualité (la meilleure applicable) :</p>
 * <ul>
 *   <li>{@link Grade#EXACT} — égalité stricte avec le préfixe ;</li>
 *   <li>{@link Grade#PREFIX} — le nom commence par le préfixe (sensible à
 *       la casse) ;</li>
 *   <li>{@link Grade#PREFIX_CI} — idem, insensible à la casse ;</li>
 *   <li>{@link Grade#HUMP} — camel-hump ancré au premier caractère (chaque
 *       caractère de la requête continue le run courant ou saute au début
 *       d'un mot : majuscule, après séparateur, premier chiffre d'un run) —
 *       {@code mDL} atteint {@code myDynamicList}, {@code NPE} atteint
 *       {@code NullPointerException} ;</li>
 *   <li>{@link Grade#SUBSTRING} — occurrence au milieu, réservée aux
 *       requêtes d'au moins {@link #MIN_SUBSTRING_QUERY} caractères (une
 *       requête de 1-2 caractères middle-matche presque tout le candidat
 *       set : du bruit, pas du rappel).</li>
 * </ul>
 *
 * <p>Allocation-free sur le chemin chaud ({@link #matches(String)} /
 * {@link #grade(String)} parcourent des milliers de candidats par frappe ;
 * la récursion de humpMatch est bornée par la taille des identifiants).</p>
 *
 * @author jo@Dev
 */
final class EcjPrefixMatcher {

    /** Qualité de match, de la meilleure à la pire. */
    enum Grade {
        EXACT, PREFIX, PREFIX_CI, HUMP, SUBSTRING
    }

    private final String prefix;

    EcjPrefixMatcher(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Le préfixe de la requête. */
    String prefix() {
        return prefix;
    }

    /** @return true si la requête ressemble à un pattern camel-hump
     *          délibéré (une majuscule après la première position). */
    boolean isHumpQuery() {
        if (prefix.length() < 2) return false;
        for (int i = 1; i < prefix.length(); i++) {
            if (Character.isUpperCase(prefix.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Le plus long préfixe PLAIN (insensible à la casse) que tout nom
     * matchant est garanti de commencer par — le préfixe complet pour une
     * requête plain, le premier caractère pour une requête hump.
     */
    String indexPrefix() {
        return isHumpQuery() ? prefix.substring(0, 1) : prefix;
    }

    /** @return true si {@code name} matche le préfixe (à n'importe quelle qualité). */
    boolean matches(String name) {
        return grade(name) != null;
    }

    /**
     * La meilleure {@link Grade} à laquelle {@code name} matche, ou null.
     * Un préfixe vide matche tout (PREFIX).
     */
    Grade grade(String name) {
        if (prefix.isEmpty()) return Grade.PREFIX;
        if (name.equals(prefix)) return Grade.EXACT;
        if (name.startsWith(prefix)) return Grade.PREFIX;
        if (startsWithIgnoreCase(name, prefix)) return Grade.PREFIX_CI;
        if (humpMatch(name, null)) return Grade.HUMP;
        if (prefix.length() >= MIN_SUBSTRING_QUERY
                && containsIgnoreCase(name, prefix)) {
            return Grade.SUBSTRING;
        }
        return null;
    }

    // ─── hump match (backtracking) ─────────────────────────────────────

    /**
     * Camel-hump : le premier caractère de la requête matche le premier
     * caractère du nom (insensible à la casse) ; chaque caractère suivant
     * continue le run contigu courant ou saute en avant vers un début de
     * hump. Backtracking (et non scan glouton) : un binding hump précoce
     * qui échoue plus loin est rejoué autrement.
     */
    private boolean humpMatch(String name, int[] out) {
        if (name.isEmpty() || prefix.isEmpty()) return false;
        if (!eqCi(name.charAt(0), prefix.charAt(0))) return false;
        if (out != null) out[0] = 0;
        return tryFrom(name, 1, 1, out);
    }

    private boolean tryFrom(String name, int qi, int ni, int[] out) {
        if (qi == prefix.length()) return true;
        for (int i = ni; i < name.length(); i++) {
            if (eqCi(name.charAt(i), prefix.charAt(qi))
                    && (i == ni || isHumpStart(name, i))) {
                if (out != null) out[qi] = i;
                if (tryFrom(name, qi + 1, i + 1, out)) return true;
            }
        }
        return false;
    }

    private static boolean isHumpStart(String name, int i) {
        if (i == 0) return true;
        char c = name.charAt(i);
        char prev = name.charAt(i - 1);
        return Character.isUpperCase(c)
                || !Character.isLetterOrDigit(prev)
                || (Character.isDigit(c) && !Character.isDigit(prev));
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static final int MIN_SUBSTRING_QUERY = 3;

    private static boolean eqCi(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }

    private static boolean startsWithIgnoreCase(String s, String p) {
        return s.length() >= p.length() && s.regionMatches(true, 0, p, 0, p.length());
    }

    private static boolean containsIgnoreCase(String s, String p) {
        if (p.isEmpty()) return true;
        int max = s.length() - p.length();
        for (int i = 0; i <= max; i++) {
            if (s.regionMatches(true, i, p, 0, p.length())) return true;
        }
        return false;
    }
}
