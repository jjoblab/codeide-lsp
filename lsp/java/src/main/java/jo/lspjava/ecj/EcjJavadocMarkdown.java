package jo.lspjava.ecj;

/**
 * Nettoyage du commentaire javadoc brut vers du <strong>markdown
 * léger</strong> — lot S2-a (survol riche, miroir Java du KDocParser
 * Kotlin livré en v0.134.9).
 *
 * <p>Portée volontairement BORNÉE (aucun parseur HTML complet) : le
 * commentaire brut — tel que tranché dans la source par les positions du
 * nœud {@code Javadoc} d'ecj — perd ses délimiteurs ({@code /**} /
 * {@code *}{@code /}) et ses astérisques de marge ; les balises inline
 * usuelles sont converties en spans markdown ; les sauts de paragraphe
 * HTML courants deviennent des sauts markdown. Tout le reste du
 * commentaire passe VERBATIM : un javadoc exotique s'affiche tel quel
 * plutôt que d'être déformé par une conversion approximative.</p>
 *
 * <p>Contrat : {@code nettoyer(null)} → {@code null} ; un commentaire
 * réduit à ses délimiteurs → {@code null} (survol signature seule) ;
 * JAMAIS d'exception — une documentation qui résiste au nettoyage n'est
 * pas une erreur de survol.</p>
 *
 * @author jo@Dev
 */
public final class EcjJavadocMarkdown {

    private EcjJavadocMarkdown() {
    }

    /**
     * Plage du javadoc attaché à une déclaration — extraction TEXTUELLE
     * (le parseur ecj ne monte le nœud {@code Javadoc} que si le support
     * des commentaires doc est activé, ce qui n'est pas le cas dans le
     * moteur ; le KDocParser Kotlin procède pareillement).
     *
     * <p>Deux cas, selon la position reçue :
     * <ol>
     *   <li>le range de déclaration OUVRE sur {@code /**} — c'est le
     *       comportement observé du parseur ecj ({@code declarationSourceStart}
     *       inclut le commentaire) : le commentaire est tranché AVANT ;</li>
     *   <li>sinon, remontée BORNÉE de l'en-tête de déclaration — espace,
     *       identifiants/modifieurs, annotations (parenthèses avec
     *       littéraux chaîne/caractère, initialisateurs de tableau),
     *       génériques — jusqu'au {@code *}{@code /} fermant d'un bloc,
     *       dont l'ouverture doit être {@code /**} ; TOUT jeton de code
     *       ({@code ; { } =} hors parenthèses) ou tout commentaire non
     *       javadoc rompt l'attachement.</li>
     * </ol>
     *
     * <p>Limite assumée : un javadoc contenant un {@code /**} littéral peut
     * tronquer le texte remonté (jamais d'attachement fautif à un autre
     * membre — le commentaire trouvé est adjacent à la déclaration).</p>
     *
     * @param source     le texte complet (celui de l'arbre scanné)
     * @param membreStart l'offset du début du range de déclaration
     *                   ({@code declarationSourceStart} du nœud ecj, en
     *                   repli {@code sourceStart})
     * @return la plage {ouverture, fermeture} INCLUSIVE du commentaire
     *         {@code /** ... *}{@code /}, ou null s'il n'y en a pas
     */
    public static int[] plageAvant(String source, int membreStart) {
        if (source == null || membreStart < 0 || membreStart >= source.length()) {
            return null;
        }
        try {
            // Cas 1 : le commentaire OUVRE le range de déclaration.
            if (source.startsWith("/**", membreStart)) {
                int fin = source.indexOf("*/", membreStart + 3);
                return fin < 0 ? null : new int[] {membreStart, fin + 1};
            }
            // Cas 2 : remontée bornée de l'en-tête de déclaration.
            int i = membreStart - 1;
            int profondeur = 0;
            while (i >= 0) {
                char c = source.charAt(i);
                if (c == '/' && i > 0 && source.charAt(i - 1) == '*') {
                    return javadocAvantFermeture(source, i);
                }
                if (c == ')') {
                    profondeur++;
                } else if (c == '(') {
                    if (profondeur == 0) return null;
                    profondeur--;
                } else if (profondeur == 0
                        && (c == ';' || c == '{' || c == '}' || c == '=')) {
                    return null; // du code s'intercale — pas de javadoc attaché
                } else if (c == '"') {
                    i = ouvertureChaineAvant(source, i);
                    if (i < 0) return null;
                } else if (c == '\'') {
                    i = ouvertureCaractereAvant(source, i);
                    if (i < 0) return null;
                }
                i--;
            }
            return null;
        } catch (Throwable t) {
            return null; // jamais d'exception depuis un scan de déclarations
        }
    }

    /**
     * Depuis la position du {@code /} fermant d'un commentaire bloc,
     * remonte à son ouverture : la PREMIÈRE ouverture rencontrée en
     * remontant est la bonne (un corps de commentaire ne peut pas contenir
     * le fermant) ; ouverture {@code /**} → javadoc, toute autre ({@code /*})
     * → attachement rompu (commentaire ordinaire intercalé).
     */
    private static int[] javadocAvantFermeture(String source, int fermeture) {
        for (int j = fermeture - 2; j >= 0; j--) {
            if (source.charAt(j) == '/' && source.charAt(j + 1) == '*') {
                if (j + 2 < source.length() && source.charAt(j + 2) == '*') {
                    return new int[] {j, fermeture};
                }
                return null; // commentaire ordinaire — pas un javadoc
            }
        }
        return null;
    }

    /** Index du guillemet OUVRANT du littéral chaîne fermé à i, -1 si
     *  introuvable (échappements {@code \\"} pris au simple). */
    private static int ouvertureChaineAvant(String source, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (source.charAt(j) == '"' && (j == 0 || source.charAt(j - 1) != '\\')) {
                return j - 1;
            }
        }
        return -1;
    }

    /** Index de l'apostrophe OUVRANTE du littéral caractère fermé à i,
     *  -1 si introuvable (même convention que {@link #ouvertureChaineAvant}). */
    private static int ouvertureCaractereAvant(String source, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (source.charAt(j) == '\'' && (j == 0 || source.charAt(j - 1) != '\\')) {
                return j - 1;
            }
        }
        return -1;
    }

    /**
     * Nettoie un commentaire javadoc brut (délimiteurs inclus) en markdown
     * léger.
     *
     * @param brut le texte du commentaire tel que tranché de la source
     *             (début {@code /**}, fin {@code *}{@code /}, astérisques
     *             de marge compris)
     * @return le markdown nettoyé, ou {@code null} si rien ne reste
     */
    public static String nettoyer(String brut) {
        if (brut == null || brut.isBlank()) return null;
        try {
            String s = brut.strip();
            s = s.startsWith("///") ? lignesMarkdown(s) : blocEtoiles(s);
            s = balisesInline(s);
            s = s.replaceAll("\n{3,}", "\n\n").strip();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null; // jamais d'exception depuis un hover
        }
    }

    /**
     * Bloc classique {@code /** ... *}{@code /} : retire les délimiteurs
     * puis les astérisques de marge ligne à ligne (un astérisque tête de
     * ligne + UNE espace qui suit — l'espace de mise en page, pas le
     * contenu).
     */
    private static String blocEtoiles(String brut) {
        String s = brut;
        if (s.startsWith("/**")) s = s.substring(3);
        if (s.endsWith("*/")) s = s.substring(0, s.length() - 2);
        String[] lignes = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String ligne : lignes) {
            String l = ligne.strip();
            if (l.startsWith("*")) l = l.substring(1);
            if (l.startsWith(" ")) l = l.substring(1);
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /**
     * Javadoc markdown {@code ///} (Java 23+, jamais atteint à compliance
     * ≤ 21 — garde gratuite) : retire le préfixe ligne à ligne.
     */
    private static String lignesMarkdown(String brut) {
        String[] lignes = brut.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String ligne : lignes) {
            String l = ligne.strip();
            if (l.startsWith("///")) l = l.substring(3);
            if (l.startsWith(" ")) l = l.substring(1);
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /**
     * Balises inline usuelles : {@code {@code x}} → {@code `x`},
     * {@code {@link C#m()}} / {@code {@linkplain C#m()}} → {@code `C.m()`},
     * {@code {@literal x}} → {@code x} ; {@code <p>} → saut de paragraphe,
     * {@code <br>} (toutes variantes) → saut de ligne. Le {@code #} des
     * liens membres devient {@code .} (convention de lecture Java).
     */
    private static String balisesInline(String s) {
        String r = s;
        r = r.replaceAll("\\{@code\\s+([^}]*?)}", "`$1`");
        r = r.replaceAll("\\{@link(plain)?\\s+([^}]*?)}", "`$2`");
        r = r.replaceAll("\\{@literal\\s+([^}]*?)}", "$1");
        // Lien membre `` `Class#method()` `` → `` `Class.method()` `` — le
        // dièse n'est converti QUE DANS les spans de code (un # hors span
        // est du contenu, pas un séparateur de lien).
        r = dieseDansSpans(r);
        r = r.replaceAll("<p>|</p>", "\n\n");
        r = r.replaceAll("<br\\s*/?>", "\n");
        return r;
    }

    /** Convertit le {@code #} en {@code .} à l'intérieur des spans de
     *  code (liens membres {@code `Class#m()`} → {@code `Class.m()`}). */
    private static String dieseDansSpans(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean dansSpan = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '`') dansSpan = !dansSpan;
            sb.append(c == '#' && dansSpan ? '.' : c);
        }
        return sb.toString();
    }
}
