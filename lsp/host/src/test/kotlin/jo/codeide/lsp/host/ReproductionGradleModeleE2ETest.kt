package jo.codeide.lsp.host

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * REPRODUCTION E2E du MODÈLE GRADLE POUR SCRIPTS (rapport d'audit §8.1)
 * — la suite « indexer le classpath des scripts Gradle pour offrir la
 * complétion DSL » : quand l'HÔTE nourrit la session d'un classpath
 * Gradle RÉEL (les jars de la distribution), les segments QUALIFIÉS des
 * scripts résolvent ET se complètent par la machinerie standard :
 *
 *  - `JavaVersion.VERSION_11` (statique d'énumération du classpath
 *    Gradle) : survol + complétion membre ;
 *  - zéro faux `kt.unresolved` sur le script (ADR 0221 : les NUS restent
 *    muets — le récepteur implicite du DSL n'est pas modélisé ; les
 *    QUALIFIÉS maintenant LISIBLES prouvent leur présence).
 *
 * La suite découvre les jars de la distribution Gradle locale (cache du
 * wrapper), env `REPRO_GRADLE_DIR` pour imposer un répertoire `lib/` —
 * sans eux, la suite SAUTE (miroir REPRO_COMPOSE_DIR, corpus absent).
 */
class ReproductionGradleModeleE2ETest {
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Jars Gradle (distribution locale), null si absents — suite sautée. */
    private val jarsGradle: List<File>? by lazy {
        val imposé = System.getenv("REPRO_GRADLE_DIR")?.let(::File)?.takeIf { it.isDirectory }
        val lib =
            imposé
                ?: run {
                    val dists = File(System.getProperty("user.home"), ".gradle/wrapper/dists")
                    dists.walkTopDown()
                        .filter { it.isDirectory && it.name.startsWith("gradle-") }
                        .map { File(it, "lib") }
                        .firstOrNull { it.isDirectory }
                }
                ?: return@lazy null
        val extensions = lib.listFiles { f: File -> f.name.startsWith("gradle-stdlib-java-extensions-") }?.firstOrNull()
        val kotlinDsl = lib.listFiles { f: File -> f.name.startsWith("gradle-kotlin-dsl-") }?.firstOrNull()
        val coreApi = lib.listFiles { f: File -> f.name.startsWith("gradle-core-api-") }?.firstOrNull()
        if (extensions == null || kotlinDsl == null || coreApi == null) return@lazy null
        listOf(coreApi, kotlinDsl, extensions)
    }

    /** Client espion minimal (même contrat que les suites L2). */
    private class EspionClient : org.eclipse.lsp4j.services.LanguageClient {
        public val recus: MutableList<org.eclipse.lsp4j.PublishDiagnosticsParams> =
            java.util.concurrent.CopyOnWriteArrayList()

        override fun telemetryEvent(objet: Any?) {
            // Ignoré : la télémétrie ne concerne pas l'espion.
        }

        override fun publishDiagnostics(diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams) {
            recus.add(diagnostics)
        }

        override fun showMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }

        override fun showMessageRequest(
            params: org.eclipse.lsp4j.ShowMessageRequestParams,
        ): java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem> =
            java.util.concurrent.CompletableFuture.completedFuture(null)

        override fun logMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }
    }

    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario
    // E2E de production complet (chaîne + classpath Gradle + script +
    // assertions), miroir ReproductionGradleKtsE2ETest.
    @Suppress("LongMethod")
    @Test
    public fun `gradle modele - JavaVersion statique survol et completion dans un kts`() {
        val jars = jarsGradle ?: run {
            System.out.println("[gradle-modele] distribution Gradle absente — suite sautée")
            return
        }
        val racine = dossierTemp.resolve("projet-gradle-modele")
        val sources = File(racine, "src/main/java")
        sources.mkdirs()
        val resultat =
            ClasspathResult(
                id = "repro-gradle-modele",
                protocolVersion = 1,
                projectDir = racine.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(sources.absolutePath),
                            entries = jars.map { ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "compile") },
                        ),
                    ),
            )
        val sessionJava = SessionLsp(racineProjet = racine, stockageIndex = File(racine, "index"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) { sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET } }
        }
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente" }
        val facade = SessionLspKotlin(racineProjet = racine, sessionJava = sessionJava)
        facade.appliquerClasspath(resultat, vue)
        runBlocking {
            withTimeout(300_000) {
                facade.etatIndexation.first {
                    it.phase == PhaseIndexation.PRET || it.phase == PhaseIndexation.PARTIEL
                }
            }
        }
        val serveur = facade.serveur() as KotlinLanguageServer
        val espion = EspionClient()
        serveur.connect(espion)
        serveur.initialize(InitializeParams()).join()

        // LE script avec un segment QUALIFIÉ Gradle réel : le template
        // « Empty Views Activity » écrit `JavaVersion.VERSION_11`.
        val fichier = File(racine, "build.gradle.kts")
        val source =
            """
            import org.gradle.api.JavaVersion

            plugins {
              alias(libs.plugins.android.application)
            }

            compileOptionsJavaVersion = JavaVersion.VERSION_11
            """.trimIndent() + "\n"
        fichier.writeText(source)
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(fichier.toURI().toString(), "kotlin", 1, source),
            ),
        )

        // 1. Zéro faux `kt.unresolved` sur le script (ADR 0221 : les NUS
        //    sont muets ; `JavaVersion` et `VERSION_11` qualifiés
        //    prouvent leur présence contre le classpath Gradle réel).
        runBlocking { kotlinx.coroutines.delay(500) }
        val diags =
            espion.recus
                .lastOrNull { it.uri.endsWith("build.gradle.kts") }
                ?.diagnostics
                .orEmpty()
                .filter { (it.code?.right ?: it.code?.left?.toString()) == "kt.unresolved" }
        System.out.println("[gradle-modele] unresolved=${diags.map { it.message }}")
        assertTrue(
            diags.isEmpty(),
        ) { "zéro faux kt.unresolved sur le script à classpath Gradle — obtenu : ${diags.map { it.message }}" }

        // 2. Survol du STATIQUE `VERSION_11` (JavaVersion réel) — la
        //    7e ligne porte `compileOptionsJavaVersion = JavaVersion.VERSION_11`,
        //    le sélecteur vit en colonne 41+.
        val survol =
            serveur.textDocumentService
                .hover(
                    HoverParams(
                        TextDocumentIdentifier(fichier.toURI().toString()),
                        Position(6, 45),
                    ),
                ).get()
        System.out.println("[gradle-modele] survol=$survol")
        // Le serveur Kotlin rend un MarkupContent (branche right de
        // l'Either) — rendu aplati pour l'assertion.
        val renduSurvol =
            survol?.contents?.right?.value
                ?: survol?.contents?.right?.toString()
                ?: survol?.toString()
                ?: ""
        assertTrue(
            renduSurvol.contains("VERSION_11"),
        ) { "le survol de JavaVersion.VERSION_11 rend le statique du classpath Gradle — obtenu : $renduSurvol" }

        // 3. Complétion après `JavaVersion.` : les constantes d'énumération.
        val colonne = 41
        val completions =
            serveur.textDocumentService
                .completion(
                    CompletionParams(
                        TextDocumentIdentifier(fichier.toURI().toString()),
                        Position(6, colonne),
                    ),
                ).get()
        val items =
            if (completions.isLeft) completions.left else completions.right.items
        System.out.println("[gradle-modele] completion=${items.map { it.label }.take(10)}")
        assertTrue(
            items.any { it.label.startsWith("VERSION_1") },
        ) { "VERSION_11 doit s'offrir après JavaVersion. — obtenu : ${items.map { it.label }}" }
    }
}
