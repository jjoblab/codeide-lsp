package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;

import java.util.Objects;

/**
 * Cache de parse à entrée unique, clé sur {@code (filePath, source)} — le
 * <strong>EcjParseCache</strong> du projet de référence porté en Java.
 *
 * <p><strong>Motivation :</strong> sans cache, chaque handler LSP appelle
 * {@link EcjCompilerEngine#resolveUnit} qui re-parse ET re-résout tout le
 * fichier. Une seule frappe déclenche typiquement diagnostics + highlight +
 * inlay hints + signature help : sur un fichier de 500 lignes, le même
 * travail de résolution est refait quatre fois. Ce cache mutualise un seul
 * arbre résolu entre toutes les features successives d'une même frappe — la
 * latence et la mémoire sont divisées d'autant.</p>
 *
 * <p><strong>Sémantique d'invalidation :</strong> le cache est invalidé dès
 * que le {@code (filePath, source)} change — c'est-à-dire à chaque
 * {@code didChange} LSP, puisque le tampon évolue. Comme la clé porte le
 * contenu ET le chemin, il n'y a pas de risque de servir un arbre périmé
 * après un changement de tampon ou un changement de fichier focal.</p>
 *
 * <p><strong>Limites :</strong></p>
 * <ul>
 *   <li><b>Entrée unique</b> — suffit parce que l'éditeur ne résout qu'un
 *       fichier à la fois (celui affiché). Le multi-fichiers (renommage,
 *       références) parse chaque fichier séparément et bénéficie peu d'un
 *       cache — il ouvre un flux court et passe au suivant.</li>
 *   <li><b>Taille non bornée par fichier</b> — l'arbre résolu d'un fichier
 *       de 500 lignes représente ~100 ko. En cache unique, c'est négligeable
 *       devant le coût d'un re-parse.</li>
 *   <li><b>Non thread-safe par conception</b> — l'accès concurrent est
 *       prévenu par le point de passage unique
 *       {@link EcjCompilerEngine#resolveUnit} qui est lui-même
 *       {@code synchronized} : tout contournement de ce point d'entrée
 *       corromprait silencieusement ce cache sous concurrence.</li>
 * </ul>
 *
 * <p><strong>Parité avec la référence :</strong> projet de référence utilise le même
 * pattern ({@code EcjParseCache} à entrée unique, clé
 * {@code (path, text)}, invalidé à l'édition). C'est l'optimisation la plus
 * rentable du LSP Java.</p>
 *
 * @author jo@Dev
 */
final class EcjParseCache {

    /** Clé composite : (filePath, source). */
    private static final class Key {
        final String filePath;
        final String source;
        final int hash;

        Key(String filePath, String source) {
            this.filePath = filePath == null ? "" : filePath;
            this.source = source == null ? "" : source;
            this.hash = Objects.hash(this.filePath, this.source);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return hash == k.hash
                    && filePath.equals(k.filePath)
                    && source.equals(k.source);
        }

        @Override public int hashCode() { return hash; }
    }

    /** Entrée cache : clé + arbre résolu. */
    private static final class Entry {
        final Key key;
        final CompilationUnitDeclaration cud;
        final boolean generateCode;  // diagnostics requiert generateCode=true

        Entry(Key key, CompilationUnitDeclaration cud, boolean generateCode) {
            this.key = key;
            this.cud = cud;
            this.generateCode = generateCode;
        }
    }

    private volatile Entry entry;

    /**
     * Retourne l'arbre résolu en cache si la clé matche ET si
     * {@code generateCode} est compatible (diagnostics demande
     * {@code generateCode=true}, les autres features acceptent l'arbre
     * généré pour les diagnostics car la résolution de bindings est
     * identique).
     */
    CompilationUnitDeclaration get(String filePath, String source,
                                    boolean generateCode) {
        Entry e = entry;
        if (e == null) return null;
        if (!e.generateCode && generateCode) {
            // Cache existant n'a pas fait la codegen — on ne peut pas le
            // réutiliser pour les diagnostics (parité des warnings de flux).
            return null;
        }
        Key k = new Key(filePath, source);
        return k.equals(e.key) ? e.cud : null;
    }

    /** Enregistre un arbre résolu sous la clé {@code (filePath, source)}. */
    void put(String filePath, String source,
             CompilationUnitDeclaration cud, boolean generateCode) {
        if (cud == null) return;
        entry = new Entry(new Key(filePath, source), cud, generateCode);
    }

    /** Invalide le cache (changement de classpath, fermeture serveur). */
    void invalidate() {
        entry = null;
    }

    /** Invalide le cache si le fichier correspond à l'entrée courante. */
    void invalidate(String filePath) {
        Entry e = entry;
        if (e == null) return;
        String cachedPath = e.key.filePath;
        if (cachedPath.equals(filePath)) {
            entry = null;
        }
    }

    /** @return true si le cache contient une entrée pour cette clé. */
    boolean has(String filePath, String source) {
        Entry e = entry;
        if (e == null) return false;
        return e.key.equals(new Key(filePath, source));
    }
}
