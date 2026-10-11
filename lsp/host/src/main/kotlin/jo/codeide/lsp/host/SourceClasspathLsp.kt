package jo.codeide.lsp.host

import jo.codeide.tooling.protocol.ClasspathResult
import java.io.File

/**
 * Source du classpath BRUT (protocole v8, ADR 0113) de l'hôte LSP —
 * mission « LSP Java » étape 4 lot h2 (docs/LSP_JAVA.md §16.2).
 *
 * Couture injectable : l'implémentation de production vit dans le graphe
 * Hilt de l'APP (elle appelle l'accesseur protocole `classpathBrut` de
 * `tooling:client` — le client convertit aujourd'hui vers `core:domain`
 * pour la persistance, l'hôte consomme l'ORIGINAL). Les features ne
 * voient que ce type de `:lsp:host` : jamais `tooling:client` ni ses
 * types (règle §2.2 du prompt Tooling, consommation par interfaces).
 *
 * Jamais bloquante pour l'hôte : un échec de résolution se traduit en
 * `null` (journalisé par l'implémentation) — la session vit sans modèle
 * jusqu'à la prochaine sync.
 */
public fun interface SourceClasspathLsp {
    /**
     * Résout le classpath du dossier pour les arguments Gradle donnés.
     *
     * @param dossier racine du projet (FUSE, celle de la sync).
     * @param arguments arguments Gradle applicables (réglés `--offline`,
     *   arguments libres — mêmes règles que la sync et le classpath
     *   persisté).
     * @return le résultat BRUT du protocole, ou `null` si la résolution
     *   a échoué (indisponible, connexion morte) — pas d'exception.
     */
    public suspend fun resoudre(
        dossier: File,
        arguments: List<String>,
    ): ClasspathResult?
}

/**
 * Emplacement du stockage d'index partagé (ADR 0111 §2 : « répertoire de
 * l'hôte, chemin toujours injectable ») — mission « LSP Java » étape 4
 * lot h2 : le défaut `~/.codeide/lsp/index` n'a pas de sens sous ART
 * (`user.home` n'y est pas garanti). L'APP lie le répertoire privé
 * `filesDir/codeide/lsp/index` : stable (les segments survivent aux
 * redémarrages de l'app, la déduplication par empreinte profite à tous
 * les projets), auto-guérissant (un segment purgé se reconstruit —
 * l'index est un CACHE, jamais une autorité).
 */
public fun interface DossierIndexLsp {
    /** Répertoire persistant des segments d'index. */
    public fun emplacement(): File
}
