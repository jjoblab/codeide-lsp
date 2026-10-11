package jo.lspjava.api;

/**
 * Paramètres de la requête LSP PERSONNALISÉE
 * {@code textDocument/decompiledSource} : l'application hôte demande au
 * serveur LSP Java de lui fournir la source (attachée ou décompilée) d'un
 * FQN binaire (classpath, JDK).
 *
 * <p>Le scénario complet :</p>
 * <ol>
 *   <li>le serveur répond à {@code textDocument/definition} avec une
 *       {@code Location} dont l'URI est {@code jdt://decompiled/<fqcn>.java}
 *       quand la cible est un type binaire (pas dans les sources du projet) ;</li>
 *   <li>le client ({@code :cel-lsp}) appelle alors
 *       {@code textDocument/decompiledSource(fqcn)} pour récupérer la source ;</li>
 *   <li>l'app host materialise le texte dans un buffer scratch read-only.</li>
 * </ol>
 *
 * <p>Les champs sont PUBLICS — la sérialisation Gson de LSP4J mappe les
 * champs un à un (le client {@code :cel-lsp} possède son miroir exact).</p>
 *
 * @author jo@Dev
 */
public final class DecompiledSourceParams {

    /** Le fully-qualified name du type binaire (ex. {@code java.util.HashMap}). */
    public String fqcn;

    public DecompiledSourceParams() {
        // Gson.
    }

    public DecompiledSourceParams(String fqcn) {
        this.fqcn = fqcn != null ? fqcn : "";
    }

    /** Le FQCN cible. */
    public String getFqcn() {
        return fqcn != null ? fqcn : "";
    }

    @Override
    public String toString() {
        return "DecompiledSourceParams(" + fqcn + ")";
    }
}
