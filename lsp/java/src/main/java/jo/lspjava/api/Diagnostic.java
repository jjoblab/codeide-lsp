package jo.lspjava.api;

import java.util.List;
import java.util.Objects;

/**
 * Représente un diagnostic (erreur, avertissement, etc.) dans un fichier source.
 *
 * <p>{@link #arguments()} porte les
 * arguments STRUCTURÉS du problème ecj ({@code IProblem.getArguments()}),
 * du compilateur jusqu'au service de quick-fixes. C'est la
 * condition pour brancher les quick-fixes sur l'IDENTIFIANT du problème
 * (pas son message localisé) — pattern {@code JavaProblemCodes} de
 * projet tiers de référence.</p>
 *
 * @author jo@Dev
 */
public final class Diagnostic {

    private final TextRange range;
    private final Severity severity;
    private final String message;
    private final String code;
    private final String source;
    /**
     * Arguments structurés du problème ecj ({@code IProblem.getArguments()})
     * — par exemple {@code ["String"]} pour « Type mismatch: cannot convert
     * from int to String », {@code ["foo"]} pour « foo cannot be resolved ».
     * Vide si non applicable. Utilisés par {@code CodeActionService} pour
     * dériver des quick-fixes fiables (insertion de l'import exact, cast
     * du type attendu, etc.) sans parser le message localisé.
     */
    private final List<String> arguments;

    /**
     * Crée un diagnostic.
     *
     * @param range    la plage de texte concernée
     * @param severity le niveau de sévérité
     * @param message  le message descriptif
     * @param code     le code du diagnostic (peut être null)
     * @param source   la source du diagnostic (peut être null)
     */
    public Diagnostic(TextRange range, Severity severity, String message, String code, String source) {
        this(range, severity, message, code, source, List.of());
    }

    /**
     * Crée un diagnostic avec arguments structurés.
     *
     * @param range     la plage de texte concernée
     * @param severity  le niveau de sévérité
     * @param message   le message descriptif
     * @param code      le code du diagnostic (peut être null)
     * @param source    la source du diagnostic (peut être null)
     * @param arguments les arguments structurés du problème ecj (peut être vide)
     */
    public Diagnostic(TextRange range, Severity severity, String message,
                       String code, String source, List<String> arguments) {
        this.range = Objects.requireNonNull(range, "range");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.message = Objects.requireNonNull(message, "message");
        this.code = code;
        this.source = source;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /**
     * Crée un diagnostic simple sans code ni source.
     *
     * @param range    la plage de texte
     * @param severity le niveau de sévérité
     * @param message  le message
     */
    public Diagnostic(TextRange range, Severity severity, String message) {
        this(range, severity, message, null, null);
    }

    /** @return la plage de texte du diagnostic */
    public TextRange range() { return range; }

    /** @return le niveau de sévérité */
    public Severity severity() { return severity; }

    /** @return le message descriptif */
    public String message() { return message; }

    /** @return le code du diagnostic, ou null */
    public String code() { return code; }

    /** @return la source du diagnostic, ou null */
    public String source() { return source; }

    /**
     * @return les arguments structurés du problème ecj
     *         ({@code IProblem.getArguments()}), jamais null (peut être vide).
     */
    public List<String> arguments() { return arguments; }

    @Override
    public String toString() {
        return "Diagnostic[" + severity + " " + range + ": " + message + "]";
    }
}
