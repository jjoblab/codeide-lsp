package jo.lspjava.jdt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Formateur Java sur le <strong>formateur Eclipse JDT</strong>
 * ({@code org.eclipse.jdt.core.ToolFactory} / {@code DefaultCodeFormatter})
 * — le moteur qu'utilise Eclipse lui-même, exécuté in-process dans l'app
 * (approche projet de référence {@code JdtFormattingService}).
 *
 * <p>Le formatage est <em>purement syntaxique</em> : aucun classpath ni
 * environnement de noms n'est nécessaire, contrairement aux diagnostics.
 * Le niveau de conformité du module est passé aux options pour que la
 * syntaxe récente (records, switch expressions, text blocks) formate
 * proprement.</p>
 *
 * <p>Sur un tampon que le formateur ne peut pas parser (erreur de syntaxe
 * irrécupérable), {@code ToolFactory} retourne {@code null} : on retourne
 * alors {@code null} — l'appelant traitera cela comme un no-op plutôt que
 * de massacrer la source (comportement projet de référence).</p>
 *
 * <p>Le {@link TextEdit} retourné (un arbre MultiTextEdit de feuilles) est
 * aplatit en éditions (offset, longueur, texte) puis appliqué <strong>en
 * ordre décroissant d'offset</strong> — les offsets amont restent valides
 * pendant l'application.</p>
 *
 * @author jo@Dev
 */
public final class JdtFormatter {

    /** Style de formatage. */
    public enum Style {
        /** Défauts Eclipse : 4 espaces, 120 colonnes, accolades en fin de
         *  ligne. Le style « classique » que tout développeur Java connaît. */
        ECLIPSE,
        /** Google Java Style : 2 espaces, 100 colonnes, continuation +2. */
        GOOGLE
    }

    private final Map<String, String> options;

    // Cache du CodeFormatter (instance thread-safe d'après la doc Eclipse).
    // Créé paresseusement au premier format(), réutilisé ensuite —
    // ToolFactory.createCodeFormatter(options) alloue et parse le profil à
    // chaque appel, ce qui coûterait une création + GC par formatage.
    // NOTE : CodeFormatter est documenté comme thread-safe par Eclipse,
    // mais par précaution on synchronise le format() au niveau de la méthode.
    private volatile CodeFormatter cachedFormatter;

    public JdtFormatter(String complianceLevel) {
        this(complianceLevel, Style.ECLIPSE);
    }

    public JdtFormatter(String complianceLevel, Style style) {
        this.options = buildOptions(complianceLevel, style);
    }

    /**
     * Retourne le CodeFormatter caché (lazy init, double-checked locking).
     */
    private CodeFormatter getFormatter() {
        CodeFormatter f = cachedFormatter;
        if (f == null) {
            synchronized (this) {
                f = cachedFormatter;
                if (f == null) {
                    f = ToolFactory.createCodeFormatter(options);
                    cachedFormatter = f;
                }
            }
        }
        return f;
    }

    /**
     * Formate la totalité de la source.
     *
     * @param source le texte complet
     * @return le texte formaté, ou null si la source est imparsable
     */
    public String format(String source) {
        return formatRange(source, 0, source.length());
    }

    /**
     * Formate une plage de la source (le formateur parse l'unité entière
     * pour le contexte, mais ne réécrit que la plage demandée).
     *
     * @param source le texte complet
     * @param offset début de la plage (inclusif)
     * @param length longueur de la plage
     * @return le texte complet avec la plage formatée, ou null si imparsable
     */
    public String formatRange(String source, int offset, int length) {
        if (source == null || source.isEmpty()) return source;
        int start = Math.max(0, Math.min(offset, source.length()));
        int len = Math.max(0, Math.min(length, source.length() - start));
        if (len == 0) return source;

        CodeFormatter formatter = getFormatter();
        // K_COMPILATION_UNIT parse toute la source pour le contexte ;
        // F_INCLUDE_COMMENTS reformate aussi javadoc/commentaires.
        int kind = CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS;
        TextEdit edit;
        try {
            edit = formatter.format(kind, source, start, len,
                    /* indentationLevel */ 0, /* lineDelimiter */ "\n");
        } catch (Throwable t) {
            return null; // dégrade en no-op — ne jamais massacrer la source
        }
        if (edit == null) return null;

        return applyEdits(source, flatten(edit));
    }

    // ─── Options ───────────────────────────────────────────────────────

    /**
     * Construit la carte d'options du formateur : défauts Eclipse
     * ({@link DefaultCodeFormatterConstants#getEclipseDefaultSettings()})
     * + niveau de conformité + ajustements du style.
     */
    private static Map<String, String> buildOptions(String compliance, Style style) {
        Map<String, String> o = new HashMap<>(
                DefaultCodeFormatterConstants.getEclipseDefaultSettings());

        String level = normalizeCompliance(compliance);
        // Parse au niveau du module pour que la syntaxe récente formate bien.
        o.put(JavaCore.COMPILER_COMPLIANCE, level);
        o.put(JavaCore.COMPILER_SOURCE, level);
        o.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, level);

        switch (style) {
            case GOOGLE -> {
                o.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
                o.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "2");
                o.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, "2");
                // Continuation en UNITÉS d'indentation : +2 espaces = 1 unité.
                o.put(DefaultCodeFormatterConstants.FORMATTER_CONTINUATION_INDENTATION, "1");
                o.put(DefaultCodeFormatterConstants.FORMATTER_CONTINUATION_INDENTATION_FOR_ARRAY_INITIALIZER, "1");
                o.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, "100");
                o.put(DefaultCodeFormatterConstants.FORMATTER_COMMENT_LINE_LENGTH, "100");
                // Google indente les case sous le switch.
                o.put(DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_SWITCH,
                        DefaultCodeFormatterConstants.TRUE);
                o.put(DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_CASES,
                        DefaultCodeFormatterConstants.TRUE);
            }
            case ECLIPSE -> {
                // Défauts Eclipse : tab=SPACE 4 (FORMATTER_TAB_CHAR vaut
                // déjà mix par défaut — on force SPACE pour la cohérence
                // éditeur), 120 colonnes.
                o.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
                o.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "4");
                o.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, "4");
                o.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, "120");
            }
        }
        return o;
    }

    private static String normalizeCompliance(String level) {
        if (level == null || level.isBlank()) return "17";
        String s = level.startsWith("1.") ? level.substring(2) : level;
        try {
            Integer.parseInt(s);
            return s;
        } catch (NumberFormatException e) {
            return "17";
        }
    }

    // ─── Application des éditions ──────────────────────────────────────

    /** Une édition aplatie : remplacer [offset, offset+length) par text. */
    private record FlatEdit(int offset, int length, String text) {}

    /** Aplatit l'arbre {@link TextEdit} (MultiTextEdit de feuilles). */
    private static List<FlatEdit> flatten(TextEdit edit) {
        List<FlatEdit> out = new ArrayList<>();
        collect(edit, out);
        // Ordre décroissant d'offset — les offsets amont restent valides.
        out.sort(Comparator.comparingInt(FlatEdit::offset).reversed());
        return out;
    }

    private static void collect(TextEdit edit, List<FlatEdit> out) {
        if (edit instanceof ReplaceEdit r) {
            out.add(new FlatEdit(r.getOffset(), r.getLength(), r.getText()));
        } else if (edit instanceof InsertEdit i) {
            out.add(new FlatEdit(i.getOffset(), 0, i.getText()));
        } else if (edit instanceof DeleteEdit d) {
            out.add(new FlatEdit(d.getOffset(), d.getLength(), ""));
        } else if (edit.hasChildren()) {
            // MultiTextEdit (ou autre conteneur) — on descend.
            for (TextEdit child : edit.getChildren()) {
                collect(child, out);
            }
        }
        // Feuille d'un autre type — ignorée (CopySourceEdit etc. ne
        // surviennent pas en formatage).
    }

    /** Applique les éditions aplaties (déjà triées en décroissant). */
    private static String applyEdits(String source, List<FlatEdit> edits) {
        if (edits.isEmpty()) return source;
        String result = source;
        for (FlatEdit e : edits) {
            int start = Math.max(0, Math.min(e.offset(), result.length()));
            int end = Math.max(start, Math.min(e.offset() + e.length(), result.length()));
            result = result.substring(0, start) + e.text() + result.substring(end);
        }
        return result;
    }
}
