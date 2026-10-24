package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.KotlinLanguageServer
import jo.codeide.lsp.host.PhaseIndexation
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.File

/**
 * HARNais d'audit — serveur LSP Kotlin sur STDIO (sous-processus).
 *
 * Réplique la chaîne de PRODUCTION complète de CodeIDE, telle que
 * décrite par la matrice des capacités :
 *
 *   SessionLsp (index disque réel, ADR 0111 — la vue fusion nourrie du
 *   kotlin-stdlib RÉEL) → vueClasspathKotlin() (AdaptateurVueClasspath)
 *   → SessionLspKotlin.appliquerClasspath(resultat, vue) (façade)
 *   → facade.serveur() (KotlinLanguageServer, LSP4J 0.22)
 *   → LSPLauncher sur System.in/System.out (vrai transport JSON-RPC).
 *
 * Le processus est lancé par [HarnaisClientLspTest] avec :
 *   - HARNAIS_RACINE  : racine du projet (sources de test sur disque) ;
 *   - HARNAIS_STDLIB  : chemin du jar kotlin-stdlib (peut être absent
 *     → régime dégradé « symboles projet seuls ») ;
 *   - HARNAIS_INDEX   : dossier PERSISTANT partagé de l'index disque
 *     (les segments par empreinte rendent les démarrages suivants chauds) ;
 *   - HARNAIS_INDEX_KOTLIN : dossier des segments kotlin-appelables.
 *
 * Sortie : System.out ne porte QUE les messages LSP (encodage LSP4J
 * standard) ; les journaux JUL partent sur stderr.
 */
public object ServeurStdio {
    @JvmStatic
    public fun main(args: Array<String>) {
        val racine = File(System.getenv("HARNAIS_RACINE") ?: error("HARNAIS_RACINE requis"))
        val stdlib = System.getenv("HARNAIS_STDLIB")?.let(::File)?.takeIf { it.isFile }
        val androidJar = System.getenv("HARNAIS_ANDROID_JAR")?.let(::File)?.takeIf { it.isFile }
        val dossierIndex = System.getenv("HARNAIS_INDEX")?.let(::File)
        val dossierIndexKotlin = System.getenv("HARNAIS_INDEX_KOTLIN")?.let(::File)
        val sources = File(racine, "src").apply { mkdirs() }

        // ── Chaîne de production : index réel + vue fusion ─────────────
        val sessionJava =
            SessionLsp(
                racineProjet = racine,
                stockageIndex = dossierIndex ?: File(racine, "index"),
            )
        // Classpath miroir de la production : stdlib + PLATEFORME
        // (CodeIDE nourrit toujours l'union jars + android.jar).
        val entrees =
            listOfNotNull(
                stdlib?.let { ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "compile") },
                androidJar?.let { ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "platform") },
            )
        val resultat =
            ClasspathResult(
                id = "harnais",
                protocolVersion = 1,
                projectDir = racine.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(sources.absolutePath),
                            entries = entrees,
                        ),
                    ),
            )
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(240_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
        }
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente après index PRÊT" }

        // ── Façade Kotlin nourrie + serveur LSP4J sur stdio ────────────
        val facadeKotlin =
            SessionLspKotlin(
                racineProjet = racine,
                dossierIndex = dossierIndexKotlin,
                sessionJava = sessionJava,
            )
        facadeKotlin.appliquerClasspath(resultat, vue)
        val serveur = facadeKotlin.serveur()

        val lanceur = LSPLauncher.createServerLauncher(serveur, System.`in`, System.out)
        (serveur as KotlinLanguageServer).connect(lanceur.remoteProxy)
        lanceur.startListening().get()

        // stdin fermé (le client a fini) : arrêt exhaustif, sortie propre.
        facadeKotlin.close()
        sessionJava.close()
        Runtime.getRuntime().halt(0)
    }
}
