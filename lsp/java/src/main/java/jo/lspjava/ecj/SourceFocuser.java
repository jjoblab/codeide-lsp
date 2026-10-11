package jo.lspjava.ecj;

/**
 * Réducteur de source — technique de {@code java-language-server} (gwf)
 * portée en Java pour :lspjava.
 *
 * <p><strong>Motivation :</strong> la complétion live appelle
 * {@link EcjCompletionEngine#complete} qui construit jusqu'à 4 variantes
 * "splicées" du texte autour du curseur, et appelle
 * {@link EcjCompilerEngine#resolveUnit} pour chacune. Chaque résolution
 * ecj est <em>complète</em> — le {@link EcjParseCache} est invalidé à
 * chaque {@code didChange}, et la complétion change volontairement le texte
 * via le marqueur de splice (clé de cache différente — 0 réutilisation).
 * Sur un fichier Android réel (imports AppCompatActivity, plusieurs
 * méthodes), une résolution ecj complète n'est pas gratuite ; multipliée
 * par 4 tentatives à chaque caractère, sans annulation ni réduction —
 * c'est l'explication directe de la lenteur à la frappe.</p>
 *
 * <p><strong>Solution (technique de java-language-server) :</strong>
 * avant d'appeler {@code resolveUnit()} pour une requête interactive,
 * <em>effacer le corps de toute méthode qui ne contient pas l'offset du
 * curseur</em>, en gardant les signatures intactes pour que la résolution
 * de surcharges et l'héritage restent corrects. Le texte réduit est moins
 * coûteux à parser et résoudre.</p>
 *
 * <p><strong>Algorithme :</strong> on marche caractère par caractère en
 * suivant le bracket matching des accolades. À chaque fois qu'on entre
 * dans un corps de méthode (une accolade ouvrante au niveau classe, c.-à-d.
 * à depth==1), on calcule la position de l'accolade fermante correspondante
 * et on teste si l'offset curseur est dans l'intervalle. Si non, on remplace
 * tout le contenu du corps par un bloc vide {@code {}} — la signature de
 * méthode reste intacte, ce qui préserve la résolution de surcharges /
 * héritage.</p>
 *
 * <p><strong>Limites :</strong></p>
 * <ul>
 *   <li>Le scanner saute les strings entre guillemets, les char literals et
 *       les commentaires {@code //} et {@code /* ... *}{@code /} — sinon
 *       une accolade dans un string tromperait le bracket matcher.</li>
 *   <li>La réduction casse les diagnostics de flux (une méthode réduite à
 *       un corps vide ne peut pas avoir de warnings "variable non utilisée"
 *       ou "return manquant"). Donc <strong>ne pas appliquer pour les
 *       diagnostics</strong> — uniquement pour hover, completion, signature
 *       help, inlay hints, document symbol, go-to-definition.</li>
 * </ul>
 *
 * @author jo@Dev
 */
public final class SourceFocuser {

    private SourceFocuser() { }

    /**
     * Résultat d'une réduction avec traduction d'offset — record simple.
     * <p>Le couple (source réduite, offset traduit) permet aux appelants
     * (complétion, hover, signature help) de travailler sur un texte moins
     * coûteux à résoudre tout en gardant la position du curseur valide.</p>
     *
     * @author jo@Dev
     */
    public static final class Focused {
        private final String source;
        private final int offset;
        /** Points d'insertion des corps effacés — paires (position
         * du « { » inséré dans la source réduite, position de reprise dans
         * la source originale). Identité vide = source non réduite. */
        private final int[] skipOut;
        private final int[] skipIn;

        Focused(String source, int offset) {
            this(source, offset, null, null);
        }

        Focused(String source, int offset, int[] skipOut, int[] skipIn) {
            this.source = source;
            this.offset = offset;
            this.skipOut = skipOut;
            this.skipIn = skipIn;
        }
        /** Le source réduit (corps hors-portée remplacés par {@code {}}). */
        public String source() { return source; }
        /** L'offset traduit dans la source réduite. */
        public int offset() { return offset; }

        /**
         * Traduit une position de la source RÉDUITE vers la source
         * ORIGINALE. Inverse de {@link #offset()} : chaque corps de méthode
         * effacé (2 chars insérés {@code {}} remplaçant N chars) décale les
         * positions suivantes. Une position tombant DANS un jeton inséré
         * renvoie la position de reprise (juste après le corps retiré).
         * Identité quand la source n'a pas été réduite.
         *
         * <p><strong>Motivation :</strong> {@code findDefinition} rendait des
         * plages en coordonnées réduites que le service LSP convertissait
         * avec la source COMPLÈTE — sur un fichier de plus de 2000 chars,
         * sauter à une méthode déclarée APRÈS la méthode du caret atterrissait
         * à un offset décalé vers la gauche.</p>
         */
        public int mapBack(int reducedPos) {
            if (skipOut == null || skipOut.length == 0) return reducedPos;
            if (reducedPos < 0) return reducedPos;
            // Dernière insertion dont le « { » précède la position.
            int lo = 0, hi = skipOut.length - 1, k = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (skipOut[mid] <= reducedPos) { k = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (k < 0) return reducedPos; // avant toute insertion — identité
            if (reducedPos < skipOut[k] + 2) return skipIn[k]; // dans le jeton « {} »
            return skipIn[k] + (reducedPos - skipOut[k] - 2);
        }
    }

    /**
     * Réduit le code source à la méthode contenant l'offset — remplace le
     * corps des méthodes hors-portée par un bloc vide {@code {}}.
     *
     * <p><strong>Attention :</strong> l'offset du curseur dans la source
     * réduite peut DIFFÉRER de l'offset dans la source originale, parce que
     * les corps de méthodes précédant la méthode focalisée sont raccourcis.
     * Pour récupérer l'offset traduit, utiliser {@link #focusAndMap}.</p>
     *
     * @param source le code source complet du fichier Java
     * @param offset l'offset du curseur dans {@code source}
     * @return le texte réduit (ou l'original si l'offset est invalide / hors
     *         d'un corps de méthode / la classe est trop petite pour valoir
     *         la peine)
     */
    public static String focus(String source, int offset) {
        Focused f = focusAndMap(source, offset);
        return f.source();
    }

    /**
     * Réduit le code source à la méthode contenant l'offset ET traduit
     * l'offset curseur dans la source réduite.
     *
     * <p>L'algorithme marche caractère par caractère en suivant le bracket
     * matching des accolades. À chaque fois qu'on entre dans un corps de
     * méthode hors-portée, on calcule la position de l'accolade fermante
     * correspondante, on émet {@code {}} dans le StringBuilder de sortie,
     * et on saute tout le contenu. Le offset curseur dans la source réduite
     * est calculé en parallèle : on garde un compteur {@code outPos} qui
     * suit la position courante dans le StringBuilder, et quand on entre dans
     * le corps de la méthode focalisée (entre {@code enclosingOpen} et
     * {@code enclosingClose}), on translate l'offset original en
     * {@code outPos + (offset - i)}.</p>
     *
     * @param source le code source complet du fichier Java
     * @param offset l'offset du curseur dans {@code source}
     * @return un {@link Focused} contenant la source réduite et l'offset
     *         traduit (ou l'original si l'offset est invalide / hors d'un
     *         corps de méthode / la classe est trop petite)
     */
    public static Focused focusAndMap(String source, int offset) {
        if (source == null || source.isEmpty() || offset < 0
                || offset >= source.length()) {
            return new Focused(source, offset);
        }
        // Si la classe fait moins de 2000 chars, la réduction est peu utile
        // — le coût du scan dépasse le gain.
        if (source.length() < 2_000) return new Focused(source, offset);

        // On ne réduit que si l'offset est à l'intérieur d'un corps de
        // méthode — sinon on rendrait le code invalide.
        int[] enclosing = findEnclosingMethod(source, offset);
        if (enclosing == null) return new Focused(source, offset);
        int enclosingOpen = enclosing[0];
        int enclosingClose = enclosing[1];

        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        int depth = 0;  // profondeur des accolades, en sautant strings/comments.
        // Traduction d'offset : -1 signifie « pas encore calculé ». Calculé
        // au moment où on entre dans le corps de la méthode focalisée.
        int mappedOffset = -1;
        // Points d'insertion (outPos du « { », inPos de reprise)
        // — nourrit Focused.mapBack.
        int[] skipOut = new int[16];
        int[] skipIn = new int[16];
        int skipCount = 0;

        while (i < len) {
            char c = source.charAt(i);

            // Saute les chaînes
            if (c == '"') {
                // Détection des text blocks ("""...""") : un text block
                // peut contenir des " isolés — on ne doit PAS le traiter comme
                // une chaîne simple (ça casserait au premier " rencontré) ;
                // on détecte les triple quotes et on saute jusqu'au """
                // fermant.
                if (i + 2 < len && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"') {
                    // Text block (bloc de texte).
                    out.append("\"\"\"");
                    i += 3;
                    while (i < len) {
                        char cc = source.charAt(i);
                        if (cc == '"'
                                && i + 2 < len
                                && source.charAt(i + 1) == '"'
                                && source.charAt(i + 2) == '"') {
                            out.append("\"\"\"");
                            i += 3;
                            break;
                        }
                        out.append(cc);
                        i++;
                        if (cc == '\\' && i < len) {
                            out.append(source.charAt(i));
                            i++;
                        }
                    }
                } else {
                    // Chaîne simple.
                    out.append(c);
                    i++;
                    while (i < len) {
                        char cc = source.charAt(i);
                        out.append(cc);
                        i++;
                        if (cc == '\\') {
                            if (i < len) { out.append(source.charAt(i)); i++; }
                            continue;
                        }
                        if (cc == '"') break;
                    }
                }
                continue;
            }
            // Saute les littéraux caractères
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < len) {
                    char cc = source.charAt(i);
                    out.append(cc);
                    i++;
                    if (cc == '\\') {
                        if (i < len) { out.append(source.charAt(i)); i++; }
                        continue;
                    }
                    if (cc == '\'') break;
                }
                continue;
            }
            // Saute les commentaires de ligne
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                while (i < len && source.charAt(i) != '\n') {
                    out.append(source.charAt(i));
                    i++;
                }
                continue;
            }
            // Saute les commentaires de bloc
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                out.append(c);
                out.append(source.charAt(i + 1));
                i += 2;
                while (i < len) {
                    char cc = source.charAt(i);
                    out.append(cc);
                    i++;
                    if (cc == '*' && i < len && source.charAt(i) == '/') {
                        out.append('/');
                        i++;
                        break;
                    }
                }
                continue;
            }

            if (c == '{') {
                // La première accolade ouvrante est le corps de classe (depth 0 → 1).
                // Les accolades au niveau classe (depth == 1) sont des corps de méthode.
                if (depth == 1) {
                    int end = findMatchingClose(source, i);
                    if (end < 0) {
                        // Mal parenthésé — abandonne la réduction sur ce bloc.
                        out.append(c);
                        depth++;
                        i++;
                        continue;
                    }
                    // Si l'offset n'est PAS dans ce corps, et que ce corps
                    // n'est PAS la méthode focalisée, on efface le contenu.
                    if (i != enclosingOpen && (offset < i || offset > end)) {
                        // Méthode hors-contexte — remplace tout le contenu
                        // entre '{' et '}' par rien (corps vide).
                        if (skipCount == skipOut.length) {
                            skipOut = java.util.Arrays.copyOf(skipOut, skipCount * 2);
                            skipIn = java.util.Arrays.copyOf(skipIn, skipCount * 2);
                        }
                        skipOut[skipCount] = out.length();
                        skipIn[skipCount] = end + 1;
                        skipCount++;
                        out.append('{').append('}');
                        i = end + 1;  // saute le contenu + la '}'
                        // depth reste à 1 (on est ressorti du corps).
                        continue;
                    }
                    // Sinon, préserve le corps de la méthode focalisée.
                    // L'offset est dans ce corps — on calcule sa position
                    // dans la source réduite.
                    if (mappedOffset < 0 && offset >= i && offset <= end) {
                        mappedOffset = out.length() + (offset - i);
                    }
                    out.append(c);
                    depth++;
                    i++;
                    continue;
                }
                // depth == 0 (corps de classe) ou depth >= 2 (bloc interne
                // à une méthode) — on préserve simplement l'accolade.
                out.append(c);
                depth++;
                i++;
                continue;
            }

            if (c == '}') {
                if (depth > 0) depth--;
                out.append(c);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
        // Si l'offset n'a pas pu être traduit (parcours non atteint), on
        // garde l'offset original comme meilleure approximation.
        if (mappedOffset < 0 || mappedOffset > out.length()) {
            mappedOffset = Math.min(offset, out.length());
        }
        int[] trimmedOut = skipCount == skipOut.length
                ? skipOut : java.util.Arrays.copyOf(skipOut, skipCount);
        int[] trimmedIn = skipCount == skipIn.length
                ? skipIn : java.util.Arrays.copyOf(skipIn, skipCount);
        return new Focused(out.toString(), mappedOffset, trimmedOut, trimmedIn);
    }

    /**
     * Trouve l'accolade fermante qui matche celle en position {@code open}.
     *
     * <p>Package-private : réutilisé par le fast-path de diagnostics
     * incrémentaux ({@link EcjFastDiagnose}) pour vérifier qu'un corps
     * édité se ferme toujours au même endroit (preuve d'obligation du
     * portage — cf. {@code EcjFastDiagnose}).</p>
     *
     * <p>Conscient des chaînes simples, des <em>text blocks</em>
     * {@code """..."""}, des littéraux caractères et des commentaires —
     * une accolade dans l'un d'eux n'est pas une accolade.</p>
     *
     * @return l'index de la fermante, ou -1 si non trouvée.
     */
    static int findMatchingClose(String source, int open) {
        int depth = 1;
        int i = open + 1;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '"') {
                // Text block (""" ... """) : sauter jusqu'au fermant """ —
                // un " isolé à l'intérieur ne termine rien.
                if (i + 2 < len && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"') {
                    i += 3;
                    while (i < len) {
                        char cc = source.charAt(i);
                        if (cc == '\\') { i += 2; continue; }
                        if (cc == '"'
                                && i + 2 < len
                                && source.charAt(i + 1) == '"'
                                && source.charAt(i + 2) == '"') {
                            i += 3;
                            break;
                        }
                        i++;
                    }
                    continue;
                }
                i++;
                while (i < len) {
                    char cc = source.charAt(i++);
                    if (cc == '\\') { if (i < len) i++; continue; }
                    if (cc == '"') break;
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < len) {
                    char cc = source.charAt(i++);
                    if (cc == '\\') { if (i < len) i++; continue; }
                    if (cc == '\'') break;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                while (i < len && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (source.charAt(i) == '*' && i + 1 < len
                            && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Trouve la paire (open, close) du corps de méthode qui contient l'offset.
     * @return un tableau {open, close} d'indices, ou null si l'offset est hors
     *         d'un corps de méthode (au niveau de la classe, en dehors des
     *         méthodes, etc.)
     */
    private static int[] findEnclosingMethod(String source, int offset) {
        int len = source.length();
        int i = 0;
        int depth = 0;
        while (i < len) {
            char c = source.charAt(i);
            // Saute chaînes/caractères/commentaires.
            if (c == '"') {
                i++;
                while (i < len) {
                    char cc = source.charAt(i++);
                    if (cc == '\\') { if (i < len) i++; continue; }
                    if (cc == '"') break;
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < len) {
                    char cc = source.charAt(i++);
                    if (cc == '\\') { if (i < len) i++; continue; }
                    if (cc == '\'') break;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                while (i < len && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < len) {
                    if (source.charAt(i) == '*' && i + 1 < len
                            && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '{') {
                if (depth == 1) {
                    int end = findMatchingClose(source, i);
                    if (end < 0) return null;
                    if (offset >= i && offset <= end) {
                        return new int[]{i, end};
                    }
                    i = end + 1;  // saute tout le corps
                    continue;
                }
                // depth == 0 (corps classe) ou depth >= 2 (bloc interne).
                int end = findMatchingClose(source, i);
                if (end < 0) return null;
                if (depth == 0) {
                    // Entre dans le corps de classe — on doit entrer pour
                    // visiter les méthodes à l'intérieur.
                    depth++;
                    i++;
                    continue;
                }
                // depth >= 2 : bloc interne d'une méthode — on saute.
                i = end + 1;
                continue;
            }
            if (c == '}') {
                if (depth > 0) depth--;
                i++;
                continue;
            }
            i++;
        }
        return null;
    }
}
