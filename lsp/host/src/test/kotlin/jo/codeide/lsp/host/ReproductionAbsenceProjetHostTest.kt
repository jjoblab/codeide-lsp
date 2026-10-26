package jo.codeide.lsp.host

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REPRODUCTION HÔTE (vague 3, 26/10/2026) : le membre inexistant d'un
 * OBJECT sans héritage d'un module FRÈRE (`:shared`), appelé depuis
 * `:app` qui l'importe — doit être SIGNALÉ (preuve d'absence projet).
 * Le même cas PASSES au niveau moteur (ReproductionObjetsClasspathTest
 * « objectimporte ») mais ÉCHOUE en E2E U3 (consulterInconnu muet) :
 * cette reproduction isole la couche HÔTE (SessionLsp + index disque +
 * façade SessionLspKotlin + serveur LSP4J) du moteur.
 */
class ReproductionAbsenceProjetHostTest {
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
            java.util.concurrent.CompletableFuture
                .completedFuture(null)

        override fun logMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }
    }

    @Suppress("LongMethod")
    @Test
    fun `object du frere sans heritage - membre inexistant signale via la chaine hote`() {
        val projet = File("/home/z/my-project/utilisateurs/u3/projet")
        val corpus = File("/home/z/my-project/utilisateurs/u3/corpus")
        if (!projet.isDirectory || !corpus.isDirectory) {
            System.out.println("fixtures U3 absentes — test ignoré")
            return
        }
        val shared = File(projet, "shared/src/commonMain/kotlin")
        val sharedAndroid = File(projet, "shared/src/androidMain/kotlin")
        val app = File(projet, "app/src/main/java")
        val stdlib = File(corpus, "kotlin-stdlib-2.2.10.jar")
        val coroutines = File(corpus, "kotlinx-coroutines-core-jvm-1.9.0.jar")
        val androidJar = File(corpus, "android-37.2.jar")
        val jarsApp =
            listOf(
                "activity-1.11.0-classes.jar",
                "core-1.17.0-classes.jar",
                "lifecycle-viewmodel-2.9.0-classes.jar",
                "lifecycle-runtime-2.9.0-classes.jar",
                "lifecycle-common-jvm-2.9.0.jar",
                "annotation-jvm-1.9.1.jar",
                "kotlinx-coroutines-android-1.9.0.jar",
            ).map { File(corpus, it) }
        val resultat =
            ClasspathResult(
                id = "repro-host-absence",
                protocolVersion = 1,
                projectDir = projet.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":shared",
                            sourceDirs = listOf(shared.absolutePath, sharedAndroid.absolutePath),
                            entries =
                                listOf(
                                    ClasspathEntry(coroutines.absolutePath, ClasspathKind.JAR, "compile"),
                                    ClasspathEntry(stdlib.absolutePath, ClasspathKind.JAR, "compile"),
                                    ClasspathEntry(androidJar.absolutePath, ClasspathKind.JAR, "platform"),
                                ),
                            androidJar = androidJar.absolutePath,
                            varianteAndroid = "debug",
                        ),
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(app.absolutePath),
                            entries =
                                listOf(
                                    ClasspathEntry(":shared", ClasspathKind.MODULE, "compile"),
                                    ClasspathEntry(stdlib.absolutePath, ClasspathKind.JAR, "compile"),
                                    ClasspathEntry(androidJar.absolutePath, ClasspathKind.JAR, "platform"),
                                ) +
                                    jarsApp.map {
                                        ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "compile")
                                    },
                            androidJar = androidJar.absolutePath,
                            varianteAndroid = "debug",
                        ),
                    ),
            )
        val sessionJava = SessionLsp(racineProjet = projet, stockageIndex = File(projet, "index-lsp"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
        }
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente" }
        val facade = SessionLspKotlin(racineProjet = projet, sessionJava = sessionJava)
        facade.appliquerClasspath(resultat, vue)
        runBlocking {
            withTimeout(300_000) {
                facade.etatIndexation.first {
                    it.phase == PhaseIndexation.PRET || it.phase == PhaseIndexation.PARTIEL
                }
            }
        }
        val espion = EspionClient()
        val serveur = facade.serveur() as KotlinLanguageServer
        serveur.connect(espion)
        serveur.initialize(InitializeParams()).join()

        val calculatrice = File(shared, "budget/partage/logique/CalculatriceBudget.kt")
        val fichiers =
            listOf(
                File(shared, "budget/partage/model/Operation.kt"),
                File(shared, "budget/partage/model/Budget.kt"),
                File(shared, "budget/partage/repo/DepotBudget.kt"),
                File(shared, "budget/partage/repo/DepotBudgetMemoire.kt"),
                calculatrice,
                File(shared, "budget/partage/logique/Formateurs.kt"),
                File(sharedAndroid, "budget/partage/logique/Formateurs.android.kt"),
                File(app, "budget/partage/app/MainActivity.kt"),
                File(app, "budget/partage/app/vm/BudgetViewModel.kt"),
            )
        val viewModel = fichiers.last()
        for (fichier in fichiers) {
            val uri = fichier.toURI().toString()
            serveur.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, fichier.readText())),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }
        val uriViewModel = viewModel.toURI().toString()
        val derniers = espion.recus.lastOrNull { it.uri == uriViewModel }?.diagnostics ?: emptyList()
        System.out.println("=== diagnostics BudgetViewModel : ${derniers.size}")
        derniers.forEach {
            System.out.println("    ${it.code?.left ?: it.code?.right ?: "?"} [${it.severity}] ${it.message}")
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            derniers.any { it.message.contains("consulterInconnu") },
            "consulterInconnu doit être signalé (preuve d'absence projet, object :shared sans héritage) : " +
                derniers.joinToString { it.message },
        )
        serveur.shutdown().join()
        serveur.exit()
        facade.close()
        sessionJava.close()
    }
}
