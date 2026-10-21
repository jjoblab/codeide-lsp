package jo.lspjava.api;

/**
 * Une cible de navigation multi-fichiers (GO TO Implementations /
 * Super) : le chemin ABSOLU du fichier déclarant, la plage du NOM de la
 * déclaration, et le libellé du picker.
 *
 * <p>Transportée par la méthode LSP personnalisée
 * {@code textDocument/superDefinition} (paires projet tiers de référence
 * {@code NavTarget(file, offset, label, kind)}). Les champs sont PUBLICS :
 * la sérialisation Gson de LSP4J mappe les champs un à un (le client
 * {@code :cel-lsp} possède son miroir exact).</p>
 *
 * @author jo@Dev
 */
public final class NavigationTarget {

    /** Chemin absolu du fichier déclarant (sans préfixe {@code file://}). */
    public String path;

    /** Offset du NOM de la déclaration dans ce fichier. */
    public int start;

    /** Fin (exclusive) du nom. */
    public int end;

    /** Libellé picker (« Simple  ·  pkg » / « name  ·  Super »). */
    public String label;

    public NavigationTarget() {
        // Gson.
    }

    public NavigationTarget(String path, int start, int end, String label) {
        this.path = path != null ? path : "";
        this.start = start;
        this.end = end;
        this.label = label != null ? label : "";
    }

    @Override
    public String toString() {
        return "NavigationTarget(" + path + "@" + start + ", \"" + label + "\")";
    }
}
