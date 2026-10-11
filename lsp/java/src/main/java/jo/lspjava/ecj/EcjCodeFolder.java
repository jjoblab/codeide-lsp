package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Régions de folding Java — portage du {@code EcjCodeFolder} du projet de référence.
 *
 * <p><strong>Parité projet de référence (lang-jdt) :</strong></p>
 * <ul>
 *   <li>le groupe d'imports → {@code import ...} (replié PAR DÉFAUT, comme
 *       IntelliJ/projet de référence) ;</li>
 *   <li>les paires d'accolades (corps de type, de méthode, blocs de contrôle
 *       if/for/while/try, lambdas) → la région couvre EXACTEMENT le texte
 *       caché : de juste après {@code {} à la {@code }} fermante (les
 *       accolades restent visibles, motif {@code prefix + "…" + suffix}) ;</li>
 *   <li>les commentaires de bloc multi-lignes / Javadoc →
 *       {@code /*…*&#47;} / {@code /**…*&#47;}.</li>
 * </ul>
 *
 * <p>Seules les régions couvrant PLUS d'une ligne sont émises (règle
 * {@code addRegion} du projet de référence).</p>
 *
 * <p><strong>Implémentation :</strong> projet de référence visite l'AST DOM JDT
 * ({@code Block}, {@code TypeDeclaration}…). L'AST interne ecj utilisé ici
 * n'expose PAS les blocs d'instructions à l'{@code ASTVisitor} ; le pliage des
 * accolades est donc un scan lexical aware chaînes/commentaires/text-blocks
 * (couvre un SUR-ensemble strict des {@code Block} DOM : corps, blocs de
 * contrôle, lambdas — et aussi les initialiseurs de tableaux multi-lignes,
 * qu'IntelliJ plie également). Le groupe d'imports vient de l'AST ecj
 * ({@code CompilationUnitDeclaration.imports}) avec repli lexical si la
 * résolution échoue.</p>
 *
 * @author jo@Dev
 */
public final class EcjCodeFolder {

    /** Une région de folding (offsets de la PLAGE CACHÉE). */
    public static final class Fold {
        /** Offset de début (inclus) — juste après le marqueur ouvrant. */
        public final int start;
        /** Offset de fin (EXCLUSIF) — sur le marqueur fermant. */
        public final int end;
        /** Texte affiché en mode replié. */
        public final String placeholder;
        /** Type : "imports" | "block" | "comment". */
        public final String kind;
        /** Repliée par défaut (groupe d'imports — projet de référence/IntelliJ). */
        public final boolean collapsedByDefault;

        Fold(int start, int end, String placeholder, String kind,
                boolean collapsedByDefault) {
            this.start = start;
            this.end = end;
            this.placeholder = placeholder;
            this.kind = kind;
            this.collapsedByDefault = collapsedByDefault;
        }
    }

    private final EcjCompilerEngine engine;

    public EcjCodeFolder(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Calcule les régions de folding d'une unité.
     *
     * @param filePath chemin du fichier (clé du cache de parse de l'engine)
     * @param source   le texte complet
     * @return les régions triées par offset de début, jamais null
     */
    public List<Fold> foldsFor(String filePath, String source) {
        List<Fold> out = new ArrayList<>(32);
        if (source == null || source.isEmpty()) return out;
        try {
            scanBracesAndComments(source, out);
        } catch (Throwable ignored) {
            // Scan partiel — on garde ce qui est collecté.
        }
        addImportFold(filePath, source, out);
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    // ── Groupe d'imports ──────────────────────────────────────────────

    /**
     * Le groupe d'imports : premier import → dernier import, replié par
     * défaut. Positions exactes depuis l'AST ecj
     * ({@code ImportReference.declarationSourceStart/End}), repli lexical
     * par lignes si la résolution échoue.
     */
    private void addImportFold(String filePath, String source, List<Fold> out) {
        int start = -1;
        int end = -1;
        try {
            CompilationUnitDeclaration cud =
                    engine.resolveUnit(filePath, source, false);
            ImportReference[] imports = cud == null ? null : cud.imports;
            if (imports != null && imports.length > 0) {
                start = imports[0].declarationSourceStart;
                ImportReference last = imports[imports.length - 1];
                end = last.declarationSourceEnd + 1; // juste après le ';'
            }
        } catch (Throwable ignored) {
            // Résolution impossible — repli lexical ci-dessous.
        }
        if (start < 0) {
            // Repli lexical : première/dernière ligne « import … ; ».
            int lineStart = 0;
            int firstImportStart = -1;
            int lastImportEnd = -1;
            for (int i = 0; i <= source.length(); i++) {
                boolean eol = i == source.length() || source.charAt(i) == '\n';
                if (eol) {
                    String line = source.substring(lineStart, i).trim();
                    if (line.startsWith("import ") && line.endsWith(";")) {
                        if (firstImportStart < 0) firstImportStart = lineStart;
                        lastImportEnd = i;
                    } else if (firstImportStart >= 0 && !line.isEmpty()) {
                        break; // fin du bloc d'imports
                    }
                    lineStart = i + 1;
                }
            }
            start = firstImportStart;
            end = lastImportEnd;
        }
        if (start >= 0 && end > start && end <= source.length()
                && spansMultipleLines(source, start, end)) {
            out.add(new Fold(start, end, "import ...", "imports", true));
        }
    }

    // ── Scan accolades + commentaires ─────────────────────────────────

    /**
     * Unique passage lexical : collecte les paires d'accolades (région =
     * intérieur des accolades) ET les commentaires de bloc multi-lignes.
     * Aware chaînes, caractères, échappements, text-blocks {@code """},
     * commentaires ligne/bloc — une accolade dans une de ces zones n'est
     * jamais comptée (l'ancien {@code FoldDetector} ne connaissait pas les
     * text-blocks).
     */
    private static void scanBracesAndComments(String text, List<Fold> out) {
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                // Commentaire de ligne — jusqu'au \n.
                while (i < n && text.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                // Commentaire de bloc (ou Javadoc) — région pliable.
                // La région couvre TOUT le commentaire, délimiteurs inclus
                // (projet de référence : c.startPosition .. c.startPosition+c.length).
                int start = i;
                int close = text.indexOf("*/", i + 2);
                if (close < 0) break; // non terminé — on s'arrête (syntaxe cassée)
                boolean javadoc = text.startsWith("/**", start)
                        && close > start + 3;
                int end = close + 2; // juste après le « */ » fermant
                if (spansMultipleLines(text, start, end)) {
                    out.add(new Fold(start, end,
                            javadoc ? "/**…*/" : "/*…*/", "comment", false));
                }
                i = end;
            } else if (c == '"') {
                if (next == '"' && i + 2 < n && text.charAt(i + 2) == '"') {
                    // Text-block Java 15+ : sauter jusqu'au """ fermant.
                    i = skipTextBlock(text, i + 3);
                } else {
                    i = skipString(text, i + 1, '"');
                }
            } else if (c == '\'') {
                i = skipString(text, i + 1, '\'');
            } else if (c == '{') {
                stack.push(i);
                i++;
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    int open = stack.pop();
                    // Région = intérieur strict : (open+1 .. close) — les
                    // accolades restent visibles (projet de référence braces()).
                    if (spansMultipleLines(text, open + 1, i)) {
                        out.add(new Fold(open + 1, i, "…", "block", false));
                    }
                }
                i++;
            } else {
                i++;
            }
        }
    }

    /** Saute une chaîne/constante caractère déjà ouverte ; retourne l'index après le délimiteur fermant. */
    private static int skipString(String text, int from, char delim) {
        int i = from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == delim || c == '\n') return i + 1; // \n = littéral cassé
            i++;
        }
        return i;
    }

    /** Saute le contenu d'un text-block (délimiteur ouvrant consommé). */
    private static int skipTextBlock(String text, int from) {
        int i = from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') {
                if (i + 2 < n && text.charAt(i + 1) == '"'
                        && text.charAt(i + 2) == '"') {
                    return i + 3;
                }
                // Séquence de guillemets internes — sauter les doublons "".
                if (i + 1 < n && text.charAt(i + 1) == '"') { i += 2; continue; }
            }
            i++;
        }
        return i;
    }

    /** La plage [start, end) contient-elle au moins un retour à la ligne ? */
    private static boolean spansMultipleLines(String text, int start, int end) {
        if (start < 0 || end <= start || end > text.length()) return false;
        int nl = text.indexOf('\n', start);
        return nl != -1 && nl < end;
    }
}
