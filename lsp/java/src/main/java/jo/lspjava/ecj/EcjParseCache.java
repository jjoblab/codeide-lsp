package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache de parse MULTI-ENTRÉES, clé sur {@code (chemin, hachage64 du
 * contenu)} — l'entrée unique historique portée par le module vendored,
 * étendue à l'étape 3 lot D (W8 : « BindingParseCache à entrée unique
 * confirmé (évince à chaque put) » — le plan §10 exige le
 * multi-entrées).
 *
 * <p><strong>Motivation :</strong> sans cache, chaque handler LSP appelle
 * {@link EcjCompilerEngine#resolveUnit} qui re-parse ET re-résout tout le
 * fichier. Une seule frappe déclenche typiquement diagnostics + highlight +
 * inlay hints + signature help : sur un fichier de 500 lignes, le même
 * travail de résolution est refait quatre fois. L'entrée unique
 * mutualisait UNE frappe d'UN fichier — mais l'aller-retour entre
 * fichiers (diagnostics de A puis complétion dans B puis retour à A ;
 * renommage et références multi-fichiers) évinçait l'entrée à chaque
 * put : tout retour repayait le parse. Les {@link #MAX_ENTRIES} plus
 * récemment utilisées vivent maintenant ensemble (LRU d'accès).</p>
 *
 * <p><strong>Clé :</strong> {@code (chemin, longueur, hachage 64 bits du
 * contenu)}. L'entrée unique conservait la SOURCE ENTIÈRE dans la clé
 * (retenue mémoire par entrée) ; le hachage 64 bits FNV-1a bornant le
 * risque de collision à ~10<sup>-13</sup> pour un million de contenus
 * distincts — le compromis mémoire/exactitude retenu. Le chemin et la
 * longueur participent à la clé (rejet rapide avant le hachage).</p>
 *
 * <p><strong>Sémantique d'invalidation :</strong> une édition
 * ({@code didChange}) change le hachage → miss (jamais d'arbre périmé
 * servi) ; {@link #invalidate()} vide tout (changement de classpath —
 * les bindings résolus dépendent du classpath) ;
 * {@link #invalidate(String)} retire le fichier seul (fermeture).</p>
 *
 * <p><strong>Limites :</strong></p>
 * <ul>
 *   <li><b>Taille bornée en ENTRÉES, pas en octets</b> — un arbre résolu
 *       de fichier de 500 lignes ≈ 100 ko ; 8 entrées ≈ 800 ko pire cas,
 *       négligeable devant le gain de re-parse.</li>
 *   <li><b>Non thread-safe par conception</b> — l'accès concurrent est
 *       prévenu par le point de passage unique
 *       {@link EcjCompilerEngine#resolveUnit} qui est lui-même
 *       {@code synchronized} : tout contournement de ce point d'entrée
 *       corromprait silencieusement ce cache sous concurrence.</li>
 *   <li><b>Asymétrie codegen conservée</b> — un arbre mis en cache SANS
 *       codegen ne sert pas les diagnostics (parité des warnings de
 *       flux, cf. {@link #get}) ; la mise en cache AVEC codegen
 *       remplace l'entrée sans codegen du même fichier (upgrade).</li>
 * </ul>
 *
 * @author jo@Dev
 */
final class EcjParseCache {

    /** Capacité en entrées (LRU d'accès). */
    static final int MAX_ENTRIES = 8;

    /** Clé composite : (chemin, longueur, hachage 64 du contenu). */
    private static final class Key {
        final String filePath;
        final long length;
        final long hash;

        Key(String filePath, long length, long hash) {
            this.filePath = filePath == null ? "" : filePath;
            this.length = length;
            this.hash = hash;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return hash == k.hash
                    && length == k.length
                    && filePath.equals(k.filePath);
        }

        @Override public int hashCode() {
            return (int) (hash ^ (hash >>> 32)) ^ filePath.hashCode();
        }
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

    /**
     * LRU d'accès : la réécriture de {@code get} par
     * {@code LinkedHashMap(accessOrder=true)} repositionne l'entrée
     * consultée en fin (la plus récemment utilisée) — l'éviction (via
     * {@code removeEldestEntry}) retire la MOINS récemment utilisée.
     */
    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /**
     * Retourne l'arbre résolu en cache si la clé matche ET si
     * {@code generateCode} est compatible (diagnostics demande
     * {@code generateCode=true}, les autres features acceptent l'arbre
     * généré pour les diagnostics car la résolution de bindings est
     * identique).
     *
     * <p>Le hit REPOSITIONNE l'entrée en fin de LRU (accès récent).</p>
     */
    CompilationUnitDeclaration get(String filePath, String source,
                                    boolean generateCode) {
        Key k = cleDe(filePath, source);
        Entry e = entries.get(k);
        if (e == null) return null;
        if (!e.generateCode && generateCode) {
            // Cache existant n'a pas fait la codegen — on ne peut pas le
            // réutiliser pour les diagnostics (parité des warnings de flux).
            return null;
        }
        return e.cud;
    }

    /**
     * Enregistre un arbre résolu sous la clé {@code (chemin, hachage du
     * contenu)}. Une entrée SANS codegen du même fichier est REMPLACÉE
     * (upgrade : l'arbre avec codegen sert tous les consommateurs) ; une
     * entrée AVEC codegen n'est pas dégradée par un put sans codegen.
     */
    void put(String filePath, String source,
             CompilationUnitDeclaration cud, boolean generateCode) {
        if (cud == null) return;
        Key k = cleDe(filePath, source);
        Entry existante = entries.get(k);
        if (existante != null && existante.generateCode && !generateCode) {
            // Ne jamais dégrader : l'entrée codegen sert tout le monde.
            return;
        }
        entries.put(k, new Entry(k, cud, generateCode));
    }

    /** Invalide tout le cache (changement de classpath, fermeture serveur). */
    void invalidate() {
        entries.clear();
    }

    /** Invalide les entrées du fichier donné (fermeture du document). */
    void invalidate(String filePath) {
        if (filePath == null) return;
        String chemin = filePath;
        entries.keySet().removeIf(k -> k.filePath.equals(chemin));
    }

    /** @return true si le cache contient une entrée pour cette clé. */
    boolean has(String filePath, String source) {
        return entries.containsKey(cleDe(filePath, source));
    }

    /** @return nombre d'entrées courantes (observabilité/tests). */
    int size() {
        return entries.size();
    }

    /** Clé d'un couple (chemin, source) — hachage FNV-1a 64 bits. */
    private static Key cleDe(String filePath, String source) {
        if (source == null) source = "";
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < source.length(); i++) {
            h ^= source.charAt(i);
            h *= 0x100000001b3L;
        }
        // La longueur participe : rejet rapide et discriminant avant
        // l'égalité complète (deux contenus de longueurs différentes ne
        // peuvent pas partager la clé).
        return new Key(filePath, source.length(), h);
    }
}
