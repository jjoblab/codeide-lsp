package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.SessionLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspjava.lsp.JavaLanguageServer
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * HARNais d'audit — serveur LSP JAVA sur STDIO (sous-processus).
 *
 * Miroir exact du [ServeurStdio] Kotlin, pour la FACE Java de la
 * découverte D6 (rapport d'audit §14, ADR 0215) : la chaîne de
 * production complète —
 *
 *   SessionLsp (routeur fichier → module posé, android.jar branché au
 *   contexte défaut comme sur device) → session.serveur() (le VRAI
 *   JavaLanguageServer de production) → WRAPPER délégant (la classe de
 *   production est `final` — le wrapper ajoute UNE notification
 *   JSON-RPC custom sans jamais la toucher) → LSPLauncher sur
 *   System.in/System.out.
 *
 * La notification `harnais/appliquerClasspath` (chemin d'un
 * `ClasspathResult` sérialisé JSON sur disque) rejoue la sync de
 * production (`session.appliquerClasspath`) APRÈS le didOpen — le
 * scénario E2E prouve que le live-republish efface les faux
 * « cannot be resolved » dès que le modèle nourrit le moteur du
 * MODULE (en production, c'est l'APP qui appelle appliquerClasspath
 * au fil de la sync Gradle ; le client harnais n'a pas d'autre voie
 * d'injection).
 *
 * Sortie : System.out ne porte QUE les messages LSP (encodage LSP4J
 * standard) ; les journaux JUL partent sur stderr.
 */
public object ServeurStdioJava {
    /**
     * Paramètre de la notification harnais — champs publics Gson
     * (miroir des DTO personnalisés de [NavServeurKotlinHarnais]).
     */
    public data class CheminClasspathHarnais(
        public val chemin: String,
    )

    /**
     * Wrapper délégant : le VRAI serveur de production + la notification
     * de sync harnais. LSP4J découvre par réflexion les méthodes du
     * service local RACINE (notamment `getTextDocumentService` → le
     * service texte du serveur réel) : les méthodes standard délèguent,
     * la notification custom vit ICI.
     */
    private class ServeurHarnaisJava(
        private val reel: JavaLanguageServer,
        private val nourrir: (String) -> Unit,
    ) : LanguageServer {
        override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> =
            reel.initialize(params)

        override fun initialized(params: InitializedParams?) {
            reel.initialized(params)
        }

        override fun shutdown(): CompletableFuture<Any> = reel.shutdown()

        override fun exit() {
            reel.exit()
        }

        override fun getTextDocumentService(): TextDocumentService = reel.textDocumentService

        override fun getWorkspaceService(): WorkspaceService = reel.workspaceService

        /** Le connect est propre à l'implémentation — délègue au réel. */
        public fun connect(client: LanguageClient?) {
            reel.connect(client)
        }

        @JsonNotification("harnais/appliquerClasspath")
        fun appliquerClasspathHarnais(params: CheminClasspathHarnais?) {
            if (params == null) return
            nourrir(params.chemin)
        }
    }

    /**
     * Exemption detekt ciblée (règle 16) : LongMethod — main EST la
     * chaîne de production répliquée en UN récit linéaire, même
     * exonération que le [ServeurStdio] Kotlin.
     */
    @Suppress("LongMethod")
    @JvmStatic
    public fun main(args: Array<String>) {
        val racine = File(System.getenv("HARNAIS_RACINE") ?: error("HARNAIS_RACINE requis"))
        val androidJar = System.getenv("HARNAIS_ANDROID_JAR")?.let(::File)?.takeIf { it.isFile }
        val dossierIndex = System.getenv("HARNAIS_INDEX")?.let(::File)

        // ── Chaîne de production : SessionLsp (routeur D6 posé) ────────
        val session =
            SessionLsp(
                racineProjet = racine,
                stockageIndex = dossierIndex ?: File(racine, "index"),
                jdkHome = File(System.getProperty("java.home").orEmpty()).toPath(),
                androidJar = androidJar,
            )
        val json = Json { ignoreUnknownKeys = true }
        // Le type statique est LanguageServer (façade publique) — la
        // fabrique de production retourne TOUJOURS JavaLanguageServer.
        val reel = session.serveur() as JavaLanguageServer
        val serveur =
            ServeurHarnaisJava(reel) { chemin ->
                val brut = json.decodeFromString(ClasspathResult.serializer(), File(chemin).readText())
                // Voie de production : modèle + index + moteurs par
                // module + PERSISTANCE — puis le ContextListener du
                // serveur republie les documents ouverts (live-republish).
                session.appliquerClasspath(brut)
            }

        val lanceur = LSPLauncher.createServerLauncher(serveur, System.`in`, System.out)
        serveur.connect(lanceur.remoteProxy)
        lanceur.startListening().get()

        // stdin fermé (le client a fini) : arrêt exhaustif, sortie propre.
        session.close()
        Runtime.getRuntime().halt(0)
    }
}
