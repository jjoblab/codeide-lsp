package jo.lspjava.api;

/**
 * Réponse de la requête LSP PERSONNALISÉE
 * {@code textDocument/decompiledSource} : la source (attachée ou stub
 * décompilé) d'un FQN binaire, transportée au client {@code :cel-lsp}.
 *
 * <p>Le serveur ({@code :lspjava}) appelle
 * {@link jo.lspjava.jdt.JdtCompilerEngine#decompiledSource(String)} :
 * source attachée (src.zip du JDK, -sources.jar du classpath, caches Gradle)
 * en priorité — fidélité maximale ; sinon stub décompilé depuis le
 * ClassFileReader du binaire ({@link jo.lspjava.jdt.ClassFileStubBuilder}).
 * Quand ni l'un ni l'autre n'est disponible, le champ {@code source} est
 * {@code null} : l'app host en avise l'utilisateur (Snackbar « Source
 * décompilée indisponible »).</p>
 *
 * <p>Les champs sont PUBLICS — la sérialisation Gson de LSP4J mappe les
 * champs un à un (le client {@code :cel-lsp} possède son miroir exact).</p>
 *
 * @author jo@Dev
 */
public final class DecompiledSourceResult {

    /** La source (attachée ou stub), ou {@code null} si indisponible. */
    public String source;

    public DecompiledSourceResult() {
        // Gson.
    }

    public DecompiledSourceResult(String source) {
        this.source = source;
    }
}
