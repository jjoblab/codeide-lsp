package jo.lspjava.api;

/**
 * Énumération des niveaux de sévérité des diagnostics.
 *
 * @author jo@Dev
 */
public enum Severity {

    /** Erreur bloquante. */
    ERROR,

    /** Avertissement non bloquant. */
    WARNING,

    /** Information générale. */
    INFORMATION,

    /** Indication d'un problème mineur. */
    HINT
}
