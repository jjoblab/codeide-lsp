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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REPRODUCTION E2E des fichiers GRADLE KOTLIN DSL (ADR 0221) — le
 * build.gradle.kts du template « Empty Views Activity » ouvert dans le
 * serveur LSP de PRODUCTION (chaîne complète : SessionLsp Java + index
 * disque → SessionLspKotlin → serveur LSP4J + client espion) : le DSL
 * Gradle est le récepteur implicite du CONTEXTE HÔTE — jamais de
 * tsunami de faux `kt.unresolved` (miroir Android Studio sans modèle
 * Gradle), le Kotlin standard du script répond, la typo QUALIFIÉE
 * reste signalée (garde D3).
 */
class ReproductionGradleKtsE2ETest {
    @field:TempDir
    public lateinit var dossierTemp: File

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
    // E2E de production complet (chaîne + script + assertions).
    @Suppress("LongMethod")
    @Test
    public fun `gradle kts - build gradle kts du template, zero faux positif`() {
        val racine = dossierTemp.resolve("projet-gradle")
        val sources = File(racine, "src/main/java")
        sources.mkdirs()
        val jarStdlib =
            File(
                checkNotNull(
                    kotlin.jvm.internal.Intrinsics::class.java.protectionDomain?.codeSource?.location,
                ) { "stdlib introuvable" }.toURI(),
            )
        val resultat =
            ClasspathResult(
                id = "repro-gradle-kts",
                protocolVersion = 1,
                projectDir = racine.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(sources.absolutePath),
                            entries =
                                listOf(ClasspathEntry(jarStdlib.absolutePath, ClasspathKind.JAR, "compile")),
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

        // LE build.gradle.kts du template (viewBinding, version catalog).
        val fichier = File(racine, "build.gradle.kts")
        val source =
            """
            plugins {
              alias(libs.plugins.android.application)
            }

            android {
              namespace = "com.example.myapplication"
              compileSdk = 36

              defaultConfig {
                applicationId = "com.example.myapplication"
                minSdk = 24
                targetSdk = 36
                versionCode = 1
                versionName = "1.0"
              }

              buildTypes {
                release {
                  isMinifyEnabled = false
                  proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                  )
                }
              }
              compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
              }
              kotlinOptions {
                jvmTarget = "11"
              }
              buildFeatures {
                viewBinding = true
              }
            }

            dependencies {
              implementation(libs.androidx.core.ktx)
              implementation(libs.androidx.appcompat)
              implementation(libs.material)
              implementation(libs.constraintlayout)
              testImplementation(libs.junit)
              androidTestImplementation(libs.androidx.junit)
              androidTestImplementation(libs.androidx.espresso.core)
            }
            """.trimIndent()
        fichier.writeText(source)
        val uri = fichier.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val diagnostics =
            espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS BUILD.GRADLE.KTS (${diagnostics.size}) ===")
        diagnostics.forEach { System.out.println("  ${it.code} ${it.message}") }
        assertTrue(
            diagnostics.none { it.message.contains("non résolue", ignoreCase = true) },
            "le build.gradle.kts ne doit produire AUCUN faux positif — obtenu : " +
                diagnostics.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // Garde D3 : la typo QUALIFIÉE reste signalée dans le script.
        val fichierGarde = File(racine, "outil.gradle.kts")
        val sourceGarde = "val x = java.io.Fichier(\"essai\")\n"
        fichierGarde.writeText(sourceGarde)
        val uriGarde = fichierGarde.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uriGarde, "kotlin", 1, sourceGarde)),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val nonResolus =
            (espion.recus.lastOrNull { it.uri == uriGarde }?.diagnostics ?: emptyList())
                .filter { it.message.contains("non résolue") }
        assertEquals(
            1,
            nonResolus.size,
            "la typo qualifiée « Fichier » doit rester signalée (D3) — obtenu : " +
                nonResolus.map { it.message },
        )

        facade.close()
        sessionJava.close()
    }
}
