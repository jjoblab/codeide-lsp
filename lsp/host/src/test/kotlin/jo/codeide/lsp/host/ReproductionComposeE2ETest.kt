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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REPRODUCTION E2E du retour propriétaire (25/10/2026) : « pour un projet
 * Android Compose j'ai beaucoup de références non résolues sur
 * pratiquement tout » — chaîne de PRODUCTION complète (SessionLsp Java +
 * index disque réel ADR 0111 + vue fusion → SessionLspKotlin → serveur
 * LSP4J branché à un client espion) nourrie du CLASSPATH RÉEL d'un projet
 * Compose :
 *
 *   kotlin-stdlib 2.2.10 + android.jar 36 + classes.jar EXTRAITS des AAR
 *   androidx Compose — le jeu COMPLET des artefacts du template « Empty
 *   Activity » : activity 1.9.3, activity-compose 1.9.3, foundation 1.7.4,
 *   foundation-layout 1.7.4 (padding/fillMaxSize — SÉPARÉ de foundation
 *   depuis Compose 1.5 !), material3 1.3.1, runtime 1.7.4,
 *   runtime-saveable 1.7.4, ui 1.7.4, ui-geometry, ui-graphics, ui-text,
 *   ui-tooling-preview (Preview), ui-unit 1.7.4 (dp/sp), ui-util,
 *   annotation-jvm 1.8.1 — exactement le contenu NORMALISÉ que livre le
 *   protocole tooling v8 (AAR → jar extrait, [ClasspathEntry.aar] portant
 *   la provenance). UN ARTEFACT MANQUANT = des symboles muets (leçon de
 *   ce corpus : foundation SANS foundation-layout laissait `padding`
 *   introuvable).
 *
 * Les jars sont fournis par la variable d'environnement REPRO_COMPOSE_DIR ;
 * en son ABSENCE le test est IGNORÉ (la CI standard n'a pas les AAR).
 */
class ReproductionComposeE2ETest {
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Répertoire du corpus Compose (jars extraits), null si absent. */
    private val corpus: File? =
        System.getenv("REPRO_COMPOSE_DIR")?.let(::File)?.takeIf { it.isDirectory }

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

    /** ClasspathResult miroir du tooling v8 : UN module :app aux jars Compose. */
    private fun resultatCompose(
        racine: File,
        sources: File,
        jars: List<File>,
    ): ClasspathResult =
        ClasspathResult(
            id = "repro-compose",
            protocolVersion = 1,
            projectDir = racine.absolutePath,
            modules =
                listOf(
                    ClasspathModule(
                        name = ":app",
                        sourceDirs = listOf(sources.absolutePath),
                        entries =
                            jars.map {
                                ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "compile")
                            },
                    ),
                ),
        )

    /**
     * LE scénario du propriétaire : MainActivity Compose canonique
     * (template « New Project → Empty Activity » d'Android Studio),
     * diagnostiquée par la chaîne de production nourrie du VRAI classpath.
     */
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario
    // E2E de production complet (chaîne + corpus + assertions).
    @Suppress("LongMethod")
    @Test
    public fun `compose - MainActivity template, zero faux positif`() {
        val corpus = this.corpus ?: run {
            System.out.println("REPRO_COMPOSE_DIR absent — test ignoré")
            return
        }
        val racine = dossierTemp.resolve("projet-compose")
        val sources = File(racine, "src").apply { mkdirs() }
        // Template « Empty Activity » d'Android Studio : MainActivity + Theme
        File(sources, "theme").apply { mkdirs() }
            .resolve("Theme.kt")
            .writeText(
                """package com.example.composeapp

import androidx.compose.runtime.Composable

@Composable
fun ComposeAppTheme(contenu: @Composable () -> Unit) {
  contenu()
}
""".trimIndent() + "\n",
            )
        val stdlib = File(corpus, "kotlin-stdlib-2.2.10.jar")
        val androidJar = File(corpus, "android-36.jar")
        val jars =
            listOfNotNull(
                stdlib.takeIf { it.isFile },
                androidJar.takeIf { it.isFile },
            ) + corpus.listFiles { f -> f.name.endsWith("-classes.jar") }!!.sortedBy { it.name }
        val resultat = resultatCompose(racine, sources, jars)

        // ── Chaîne Java : index disque réel + attente PRÊT ────────────
        val sessionJava = SessionLsp(racineProjet = racine, stockageIndex = File(racine, "index"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
        }
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente" }

        // ── Façade Kotlin nourrie + attente du PRET moteur ─────────────
        val facade = SessionLspKotlin(racineProjet = racine, sessionJava = sessionJava)
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

        // ── Le fichier du propriétaire, AVANT le nourrissage (le scan
        //    doit le voir — miroir du disque réel) ─────────────────
        val source =
            """
            package com.example.composeapp

            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.unit.dp

            class MainActivity : ComponentActivity() {
              override fun onCreate(savedInstanceState: android.os.Bundle?) {
                super.onCreate(savedInstanceState)
                setContent {
                  ComposeAppTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                      Greeting("Android")
                    }
                  }
                }
              }
            }

            @Composable
            fun Greeting(nom: String, modifier: Modifier = Modifier) {
              Text(
                text = "Bonjour ${'$'}nom!",
                modifier = modifier.padding(8.dp)
              )
            }

            @Preview(showBackground = true)
            @Composable
            fun GreetingPreview() {
              Greeting("Android")
            }
            """.trimIndent()

        val uri = File(sources, "MainActivity.kt").toURI().toString()
        File(sources, "MainActivity.kt").writeText(source)
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )

        // ── Collecte : les diagnostics PUBLIÉS (ce que voit l'éditeur) ──
        TimeUnit.MILLISECONDS.sleep(2000)
        val derniers =
            espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS COMPOSE (${derniers.size}) ===")
        derniers.forEach { System.out.println("  ${it.code} [${it.severity}] ${it.message} @${it.range.start}") }

        org.junit.jupiter.api.Assertions.assertTrue(
            derniers.none { it.message.contains("non résolue", ignoreCase = true) },
            "Le template Compose canonique ne doit produire AUCUN faux positif — obtenu : " +
                derniers.joinToString("\n") { "${it.code} ${it.message}" },
        )

        facade.close()
        sessionJava.close()
    }

    /**
     * Garde de non-régression : un VRAI négatif reste signalé — la
     * disparition des faux positifs ne doit JAMAIS museler les vraies
     * erreurs (D3 : un sous-ensemble correct bat un modèle complet faux).
     */
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario
    // E2E de production complet (chaîne + corpus + assertions).
    @Suppress("LongMethod")
    @Test
    public fun `compose - vrai negatif, toujours signale`() {
        val corpus = this.corpus ?: run {
            System.out.println("REPRO_COMPOSE_DIR absent — test ignoré")
            return
        }
        val racine = dossierTemp.resolve("projet-compose-neg")
        val sources = File(racine, "src").apply { mkdirs() }
        val stdlib = File(corpus, "kotlin-stdlib-2.2.10.jar")
        val jars =
            listOfNotNull(stdlib.takeIf { it.isFile }) +
                corpus.listFiles { f -> f.name.endsWith("-classes.jar") }!!.sortedBy { it.name }
        val resultat = resultatCompose(racine, sources, jars)

        val sessionJava = SessionLsp(racineProjet = racine, stockageIndex = File(racine, "index"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
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
        val espion = EspionClient()
        val serveur = facade.serveur() as KotlinLanguageServer
        serveur.connect(espion)
        serveur.initialize(InitializeParams()).join()

        val source =
            """
            package com.example.composeapp

            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun Greeting(nom: String) {
              Texte(text = nom)
            }
            """.trimIndent()
        val uri = File(sources, "Typo.kt").toURI().toString()
        File(sources, "Typo.kt").writeText(source)
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )

        TimeUnit.MILLISECONDS.sleep(2000)
        val nonResolus =
            (espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList())
                .filter { it.message.contains("non résolue") }
        System.out.println("=== VRAI NÉGATIF (${nonResolus.size}) : ${nonResolus.map { it.message }} ===")
        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            nonResolus.size,
            "La typo « Texte » doit rester signalée (une seule) — obtenu : ${nonResolus.map { it.message }}",
        )

        facade.close()
        sessionJava.close()
    }

    /**
     * Sondage des capacités sur le template Compose (miroir de ce que
     * le LSP Kotlin officiel d'Android Studio rend sur le même fichier) :
     * survol, définition, complétion membre, aide à la signature, previews,
     * références — alimente le rapport d'audit du dépôt.
     */
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario
    // E2E de production complet (chaîne + corpus + assertions).
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    public fun `compose - sondage des capacites sur template`() {
        val corpus = this.corpus ?: run {
            System.out.println("REPRO_COMPOSE_DIR absent — test ignoré")
            return
        }
        val racine = dossierTemp.resolve("projet-compose-sondage")
        val sources = File(racine, "src").apply { mkdirs() }
        File(sources, "theme").apply { mkdirs() }
            .resolve("Theme.kt")
            .writeText(
                """package com.example.composeapp

import androidx.compose.runtime.Composable

@Composable
fun ComposeAppTheme(contenu: @Composable () -> Unit) {
  contenu()
}
""".trimIndent() + "\n",
            )
        val stdlib = File(corpus, "kotlin-stdlib-2.2.10.jar")
        val androidJar = File(corpus, "android-36.jar")
        val jars =
            listOfNotNull(
                stdlib.takeIf { it.isFile },
                androidJar.takeIf { it.isFile },
            ) + corpus.listFiles { f -> f.name.endsWith("-classes.jar") }!!.sortedBy { it.name }
        val resultat = resultatCompose(racine, sources, jars)

        val sessionJava = SessionLsp(racineProjet = racine, stockageIndex = File(racine, "index"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
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
        serveur.initialize(InitializeParams()).join()

        val source =
            """
            package com.example.composeapp

            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview

            @Composable
            fun Greeting(nom: String, modifier: Modifier = Modifier) {
              Text(
                text = "Bonjour ${'$'}nom!",
                modifier = modifier.padding(8.dp)
              )
            }

            @Preview(showBackground = true)
            @Composable
            fun GreetingPreview() {
              Greeting("Android")
            }
            """.trimIndent()
        val uri = File(sources, "MainActivity.kt").toURI().toString()
        File(sources, "MainActivity.kt").writeText(source)
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )
        TimeUnit.MILLISECONDS.sleep(1000)

        fun position(motif: String, occurrence: Int = 0): org.eclipse.lsp4j.Position {
            var index = -1
            repeat(occurrence + 1) { index = source.indexOf(motif, index + 1) }
            check(index >= 0) { "motif introuvable : $motif (occurrence $occurrence)" }
            val lignes = source.substring(0, index).split('\n')
            return org.eclipse.lsp4j.Position(lignes.size - 1, lignes.last().length)
        }

        val tds = serveur.textDocumentService

        // SURVOL sur l'usage de Text — la signature du composable.
        val survolText =
            tds.hover(
                org.eclipse.lsp4j.HoverParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position("Text("),
                ),
            ).join()
        System.out.println("SONDAGE survol Text => " + (survolText?.contents?.right?.toString()?.take(220) ?: "null"))

        // SURVOL sur Modifier (type importé).
        val survolModifier =
            tds.hover(
                org.eclipse.lsp4j.HoverParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position("Modifier = Modifier"),
                ),
            ).join()
        System.out.println(
            "SONDAGE survol Modifier => " +
                (survolModifier?.contents?.right?.toString()?.take(220) ?: "null"),
        )

        // DÉFINITION sur Text (classpath : FQCN, pas de source).
        val definitionText =
            tds.definition(
                org.eclipse.lsp4j.DefinitionParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position("Text("),
                ),
            ).join()
        System.out.println("SONDAGE definition Text => " + definitionText)

        // COMPLÉTION membre après « Modifier. » — le curseur juste APRÈS le
        // point (miroir de la frappe réelle : `modifier.` puis Ctrl+Espace).
        val indexModifierPoint = source.indexOf("modifier.") + "modifier.".length
        val lignesPoint = source.substring(0, indexModifierPoint).split('\n')
        val positionPoint = org.eclipse.lsp4j.Position(lignesPoint.size - 1, lignesPoint.last().length)
        val completion =
            tds.completion(
                org.eclipse.lsp4j.CompletionParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    positionPoint,
                ),
            ).join()
        val items = completion?.right?.items ?: completion?.left ?: emptyList()
        System.out.println(
            "SONDAGE completion apres modifier. => ${items.size} items — " +
                items.take(12).joinToString { it.label },
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            items.isEmpty(),
            "la complétion membre après modifier. doit proposer les extensions de Modifier (padding, blur…)",
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            items.any { it.label.startsWith("padding") },
            "padding doit être proposé après modifier. (extension foundation-layout) — obtenu : " +
                items.take(10).joinToString { it.label },
        )

        // AIDE À LA SIGNATURE dans l'appel Text(.
        val signature =
            tds.signatureHelp(
                org.eclipse.lsp4j.SignatureHelpParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position("text ="),
                ),
            ).join()
        System.out.println(
            "SONDAGE signatureHelp Text( => " +
                (signature?.signatures?.joinToString { it.label }?.take(220) ?: "null"),
        )

        // PREVIEWS : la découverte C1 doit trouver GreetingPreview.
        val previews = facade.previews(uri)
        System.out.println("SONDAGE previews => ${previews.map { it.nom }}")

        // RÉFÉRENCES de Greeting (déclaration + usages) — curseur sur le
        // MOT-CLÉ `fun` : miroir Android Studio (Find Usages répond le
        // curseur n'importe où sur la déclaration — ADR 0218).
        val references =
            tds.references(
                org.eclipse.lsp4j.ReferenceParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                    position("fun Greeting"),
                    org.eclipse.lsp4j.ReferenceContext(true),
                ),
            ).join()
        System.out.println("SONDAGE references Greeting => ${references?.size} : ${references?.map { it.range.start }}")
        org.junit.jupiter.api.Assertions.assertTrue(
            (references?.size ?: 0) >= 2,
            "Find Usages sur le mot-clé fun doit rendre la déclaration + l'usage (ADR 0218) — obtenu : " +
                (references?.size ?: 0),
        )

        // SYMBOLES de document — la réponse LSP4J est une liste
        // d'Either<SymbolInformation, DocumentSymbol> : le DocumentSymbol
        // vit dans le DROIT de chaque Either.
        val symboles =
            tds.documentSymbol(
                org.eclipse.lsp4j.DocumentSymbolParams(
                    org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                ),
            ).join()
        val nomsSymboles =
            symboles
                ?.mapNotNull { it.right?.name }
                ?: emptyList()
        System.out.println("SONDAGE documentSymbol => $nomsSymboles")
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Greeting", "GreetingPreview"),
            nomsSymboles,
            "les composables du fichier sont les symboles de premier niveau",
        )

        facade.close()
        sessionJava.close()
    }
}
