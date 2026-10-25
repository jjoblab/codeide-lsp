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
 * REPRODUCTION E2E du template « Kotlin Multiplatform » d'Android Studio
 * (le module `shared` : DEUX racines sources — le paquet y vit DEUX fois,
 * expect dans commonMain, actual dans androidMain) — chaîne de
 * PRODUCTION complète (SessionLsp Java + index disque ADR 0111 + vue
 * fusion → SessionLspKotlin → serveur LSP4J + client espion).
 *
 * Classpath : kotlin-stdlib RÉEL (le module KMP pur n'exige aucune AAR
 * Android pour le source set commun — l'actual Android appelle le SDK
 * que le classpath réel du projet porterait ; ici le PATRON KMP :
 * expect/actual, object expect, typealias actual, usages croisés).
 */
class ReproductionKmpE2ETest {
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

    /** Sème le module shared KMP (commonMain + androidMain). */
    private fun semerShared(racine: File): Pair<File, File> {
        val common = File(racine, "src/commonMain/kotlin/com/example/kmp")
        val android = File(racine, "src/androidMain/kotlin/com/example/kmp")
        common.mkdirs()
        android.mkdirs()
        File(common, "Platform.kt").writeText(
            """package com.example.kmp

expect fun platformName(): String

expect object Fabrique {
  fun creer(): String
}

expect class Plateforme(val nom: String) {
  fun salut(): String
}
""".trimIndent(),
        )
        File(common, "Greeting.kt").writeText(
            """package com.example.kmp

class Greeting {
  fun greet(): String = platformName() + " " + Fabrique.creer()
}
""".trimIndent(),
        )
        File(android, "Platform.android.kt").writeText(
            """package com.example.kmp

actual fun platformName(): String = "Android"

actual object Fabrique {
  actual fun creer(): String = "fabrique-android"
}

actual class Plateforme actual constructor(actual val nom: String) {
  actual fun salut(): String = "Bonjour " + nom
}
""".trimIndent(),
        )
        return Pair(common, android)
    }

    /** Ouvre la chaîne complète (Java + Kotlin + serveur espion). */
    private fun ouvrirChaine(
        nom: String,
        racines: List<File>,
        jarStdlib: File,
    ): Quadruple<SessionLsp, SessionLspKotlin, KotlinLanguageServer, EspionClient> {
        val racine = dossierTemp.resolve(nom)
        val resultat =
            ClasspathResult(
                id = "repro-kmp",
                protocolVersion = 1,
                projectDir = racine.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":shared",
                            sourceDirs = racines.map { it.absolutePath },
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
        return Quadruple(sessionJava, facade, serveur, espion)
    }

    /** Quatre valeurs groupées (chaîne + espion). */
    private class Quadruple<A, B, C, D>(
        public val premier: A,
        public val second: B,
        public val troisieme: C,
        public val quatrieme: D,
    ) {
        public operator fun component1(): A = premier
        public operator fun component2(): B = second
        public operator fun component3(): C = troisieme
        public operator fun component4(): D = quatrieme
    }

    /** Jar kotlin-stdlib réel (celui du test lui-même). */
    private val jarStdlib: File =
        File(
            checkNotNull(
                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain?.codeSource?.location,
            ) { "stdlib introuvable" }.toURI(),
        )

    /**
     * LE template KMP : le module shared à deux racines — zéro faux
     * positif dans commonMain ET androidMain (miroir Android Studio).
     */
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario
    // E2E de production complet (chaîne + corpus + assertions).
    @Suppress("LongMethod")
    @Test
    public fun `kmp - module shared, deux racines, zero faux positif`() {
        val racine = dossierTemp.resolve("projet-kmp")
        val (common, android) = semerShared(racine)
        // Exemption detekt ciblée (règle 16) : la chaîne E2E porte
        // QUATRE valeurs (sessions + serveur + espion) — le harnais.
        val chaine = ouvrirChaine("projet-kmp", listOf(common, android), jarStdlib)
        val sessionJava = chaine.premier
        val facade = chaine.second
        val serveur = chaine.troisieme
        val espion = chaine.quatrieme

        // commonMain/Greeting.kt — les usages de l'EXPECT.
        val fichierCommon = File(common, "Greeting.kt")
        val uri = fichierCommon.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, fichierCommon.readText())),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val diagnosticsCommon =
            espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS KMP commonMain (${diagnosticsCommon.size}) ===")
        diagnosticsCommon.forEach { System.out.println("  ${it.code} ${it.message}") }
        assertTrue(
            diagnosticsCommon.none { it.message.contains("non résolue", ignoreCase = true) },
            "commonMain ne doit produire AUCUN faux positif — obtenu : " +
                diagnosticsCommon.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // Sondages des capacités (miroir Android Studio).
        fun position(source: String, motif: String): org.eclipse.lsp4j.Position {
            val index = source.indexOf(motif)
            check(index >= 0) { "motif introuvable : $motif" }
            val lignes = source.substring(0, index).split('\n')
            return org.eclipse.lsp4j.Position(lignes.size - 1, lignes.last().length)
        }
        val tds = serveur.textDocumentService
        val sourceCommon = fichierCommon.readText()

        // SURVOL de l'expect platformName.
        val survolExpect =
            tds.hover(
                org.eclipse.lsp4j.HoverParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position(sourceCommon, "platformName"),
                ),
            ).join()
        System.out.println("SONDAGE survol expect platformName => " +
            (survolExpect?.contents?.right?.toString()?.take(120) ?: "null"))

        // COMPLÉTION membre après Fabrique. (object expect — ADR 0220).
        val apresFabrique = sourceCommon.indexOf("Fabrique.") + "Fabrique.".length
        val lignes = sourceCommon.substring(0, apresFabrique).split('\n')
        val completion =
            tds.completion(
                org.eclipse.lsp4j.CompletionParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    org.eclipse.lsp4j.Position(lignes.size - 1, lignes.last().length),
                ),
            ).join()
        val items = completion?.right?.items ?: emptyList()
        System.out.println("SONDAGE completion apres Fabrique. => ${items.size} items — " +
            items.take(8).joinToString { it.label })
        assertTrue(
            items.any { it.label.startsWith("creer") },
            "creer doit s'offrir après Fabrique. (ADR 0220) — obtenu : ${items.map { it.label }}",
        )

        // DÉFINITION de l'expect : commonMain D'ABORD (miroir Android
        // Studio — l'expect est la cible première, l'actual suit).
        val definitions =
            tds.definition(
                org.eclipse.lsp4j.DefinitionParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position(sourceCommon, "platformName"),
                ),
            ).join()
        val urisGauche = (definitions?.left ?: emptyList()).map { it.uri }
        val urisDroite =
            (definitions?.right as? List<*>)
                ?.filterIsInstance<org.eclipse.lsp4j.Location>()
                ?.map { it.uri }
                ?: emptyList()
        val urisDefinitions = urisGauche + urisDroite
        System.out.println("SONDAGE definitions platformName => $urisDefinitions")
        assertTrue(
            urisDefinitions.any { it.endsWith("Platform.kt") },
            "la définition de l'expect vit dans Platform.kt : $urisDefinitions",
            )

        // androidMain/Platform.android.kt — les ACTUAL.
        val fichierAndroid = File(android, "Platform.android.kt")
        val uriAndroid = fichierAndroid.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uriAndroid, "kotlin", 1, fichierAndroid.readText())),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val diagnosticsAndroid =
            espion.recus.lastOrNull { it.uri == uriAndroid }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS KMP androidMain (${diagnosticsAndroid.size}) ===")
        diagnosticsAndroid.forEach { System.out.println("  ${it.code} ${it.message}") }
        assertTrue(
            diagnosticsAndroid.none { it.message.contains("non résolue", ignoreCase = true) },
            "androidMain ne doit produire AUCUN faux positif — obtenu : " +
                diagnosticsAndroid.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // Usages ANDROID dans un fichier fermé ouvert ensuite : le
        // companion-file AndroidGreeting (usage des actuals).
        val fichierUsage = File(android, "Usage.android.kt")
        fichierUsage.writeText(
            """package com.example.kmp

fun salutAndroid(): String =
  platformName() + Plateforme("Nexus").salut() + Fabrique.creer()
""".trimIndent(),
        )
        val uriUsage = fichierUsage.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uriUsage, "kotlin", 1, fichierUsage.readText())),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val diagnosticsUsage =
            espion.recus.lastOrNull { it.uri == uriUsage }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS KMP usage android (${diagnosticsUsage.size}) ===")
        diagnosticsUsage.forEach { System.out.println("  ${it.code} ${it.message}") }
        assertTrue(
            diagnosticsUsage.none { it.message.contains("non résolue", ignoreCase = true) },
            "les usages des actuals ne doivent produire AUCUN faux positif — obtenu : " +
                diagnosticsUsage.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // VRAI NÉGATIF (garde D3) : une typo dans commonMain reste
        // signalée au milieu des expect.
        val fichierTypo = File(common, "Typo.kt")
        fichierTypo.writeText(
            """package com.example.kmp

fun demo(): String = platformNaim()
""".trimIndent(),
        )
        val uriTypo = fichierTypo.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uriTypo, "kotlin", 1, fichierTypo.readText())),
        )
        TimeUnit.MILLISECONDS.sleep(2500)
        val typoNonResolus =
            (espion.recus.lastOrNull { it.uri == uriTypo }?.diagnostics ?: emptyList())
                .filter { it.message.contains("non résolue") }
        assertEquals(
            1,
            typoNonResolus.size,
            "la typo « platformNaim » doit rester signalée — obtenu : ${typoNonResolus.map { it.message }}",
        )

        facade.close()
        sessionJava.close()
    }
}
