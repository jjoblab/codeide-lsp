package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.PhaseIndexation
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.lsp.kotlin.protocole.ServeurLangageKotlin
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
 *   SessionLsp (index disque réel, ADR 0111 — le surveillant de fichiers
 *   relaye les événements externes) → SessionLspKotlin.appliquerClasspath(
 *   resultat) (façade — bascule ADR 0136 : le pack décode SES jars, la vue
 *   d'index fusion n'est plus consommée) → facade.serveur()
 *   (ServeurLangageKotlin du pack, LSP4J 0.22) → LSPLauncher sur
 *   System.in/System.out (vrai transport JSON-RPC).
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
    /**
     * Exemption detekt ciblée (règle 16) : LongMethod — main EST la
     * chaîne de production répliquée en UN récit linéaire (index → vue
     * → façade → attente du flux ADR 0213 → serveur stdio) ; la découper
     * casserait le récit du harnais (même exonération que les scénarios).
     */
    @Suppress("LongMethod")
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
        // ── Façade Kotlin nourrie + serveur LSP4J sur stdio ────────────
        val facadeKotlin =
            SessionLspKotlin(
                racineProjet = racine,
                dossierIndex = dossierIndexKotlin,
                sessionJava = sessionJava,
            )
        facadeKotlin.appliquerClasspath(resultat)
        // Bascule ADR 0136 : la fenêtre d'état (CONSTRUCTION → PRET au
        // verdict du guichet) ne dit rien de la couverture réelle — la
        // DÉTERMINISATION des scénarios vit côté CLIENT (la sonde
        // d'index de SondesIndexPack est jouée par le harnais après
        // initialize/initialized, sur le VRAI transport).
        val serveur = facadeKotlin.serveur()

        val lanceur = LSPLauncher.createServerLauncher(serveur, System.`in`, System.out)
        (serveur as ServeurLangageKotlin).connect(lanceur.remoteProxy)
        lanceur.startListening().get()

        // stdin fermé (le client a fini) : arrêt exhaustif, sortie propre.
        facadeKotlin.close()
        sessionJava.close()
        Runtime.getRuntime().halt(0)
    }
}
