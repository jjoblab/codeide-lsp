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
 * REPRODUCTION E2E du template « Empty Views Activity » d'Android Studio
 * (le monde NON-Compose : vues XML, AppCompat, ViewBinding) — chaîne de
 * PRODUCTION complète (SessionLsp Java + index disque ADR 0111 + vue
 * fusion → SessionLspKotlin → serveur LSP4J + client espion) nourrie du
 * CLASSPATH RÉEL du template :
 *
 *   kotlin-stdlib 2.2.10 + android.jar 36 + classes.jar extraits des
 *   AAR : appcompat 1.7.0, constraintlayout 2.2.0, core 1.15.0,
 *   core-ktx 1.15.0, lifecycle-runtime-android 2.8.7,
 *   lifecycle-viewmodel-android 2.8.7, lifecycle-viewmodel-savedstate
 *   2.8.7, savedstate 1.2.1, tracing 1.2.0, startup-runtime 1.2.0,
 *   annotation-experimental 1.1.0, material 1.12.0, core-common,
 *   annotation-jvm.
 *
 * Exercé : l'héritage AppCompat (ADR 0216 — setContentView membre
 * HÉRITÉ de trois maillons plus haut), les synthétiques R (K5), le
 * ViewBinding (K5 — ActivityMainBinding depuis le layout), les types
 * qualifiés (android.os.Bundle — ADR 0216 E4), findViewById.
 *
 * Corpus via REPRO_VIEWS_DIR ; ABSENCE → test IGNORÉ.
 */
class ReproductionViewsE2ETest {
    @field:TempDir
    public lateinit var dossierTemp: File

    private val corpus: File? =
        System.getenv("REPRO_VIEWS_DIR")?.let(::File)?.takeIf { it.isDirectory }

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
            java.util.concurrent.CompletableFuture
                .completedFuture(null)

        override fun logMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }
    }

    /** Le projet complet du template : sources + ressources XML. */
    private fun ecrireTemplate(racine: File): File {
        val sources = File(racine, "src/main/java")
        val res = File(racine, "src/main/res")
        File(sources, "com/example/myapplication").mkdirs()
        File(res, "layout").mkdirs()
        File(res, "values").mkdirs()
        File(racine, "src/main").resolve("AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.myapplication" />
            """.trimIndent(),
        )
        File(res, "layout").resolve("activity_main.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <androidx.constraintlayout.widget.ConstraintLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <TextView
                    android:id="@+id/textView"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello World!" />

            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
        )
        File(res, "values").resolve("strings.xml").writeText(
            """
            <resources>
                <string name="app_name">My Application</string>
            </resources>
            """.trimIndent(),
        )
        // MainActivity du template « Empty Views Activity ».
        File(sources, "com/example/myapplication").resolve("MainActivity.kt").writeText(
            """
            package com.example.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
            """.trimIndent(),
        )
        return File(racine, "src/main/java")
    }

    /** ClasspathResult miroir du tooling v8 : UN module :app aux jars du corpus. */
    private fun resultatViews(
        racine: File,
        sources: File,
        jars: List<File>,
    ): ClasspathResult =
        ClasspathResult(
            id = "repro-views",
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

    private fun jarsDuCorpus(corpus: File): List<File> =
        listOfNotNull(
            File(corpus, "kotlin-stdlib-2.2.10.jar").takeIf { it.isFile },
            File(corpus, "android-36.jar").takeIf { it.isFile },
        ) + corpus.listFiles { f -> f.name.endsWith("-classes.jar") }!!.sortedBy { it.name }

    /** Ouvre la chaîne complète (Java + Kotlin + serveur espion). */
    private fun ouvrirChaine(
        nom: String,
        sources: File,
        jars: List<File>,
    ): Quadruple<SessionLsp, SessionLspKotlin, KotlinLanguageServer, EspionClient> {
        val racine = dossierTemp.resolve(nom)
        val resultat = resultatViews(racine, sources, jars)
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

    /**
     * LE template « Empty Views Activity » : MainActivity AppCompat +
     * setContentView HÉRITÉ + R.layout synthétique — zéro faux positif.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — un scénario
     * E2E de production complet (chaîne + corpus + assertions), miroir
     * de ReproductionComposeE2ETest.
     */
    @Suppress("LongMethod")
    @Test
    public fun `views - template Empty Views Activity, zero faux positif`() {
        val corpus =
            this.corpus ?: run {
                System.out.println("REPRO_VIEWS_DIR absent — test ignoré")
                return
            }
        val racine = dossierTemp.resolve("projet-views")
        val sources = ecrireTemplate(racine)
        // Exemption detekt ciblée (règle 16) : la chaîne E2E porte
        // QUATRE valeurs (sessions + serveur + espion) — accès nommé.
        val chaine = ouvrirChaine("projet-views", sources, jarsDuCorpus(corpus))
        val sessionJava = chaine.premier
        val facade = chaine.second
        val serveur = chaine.troisieme
        val espion = chaine.quatrieme

        val fichier = File(sources, "com/example/myapplication/MainActivity.kt")
        val source = fichier.readText()
        val uri = fichier.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )

        TimeUnit.MILLISECONDS.sleep(2500)
        val derniers =
            espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS VIEWS (${derniers.size}) ===")
        derniers.forEach { System.out.println("  ${it.code} ${it.message} @${it.range.start}") }

        assertTrue(
            derniers.none { it.message.contains("non résolue", ignoreCase = true) },
            "Le template Views canonique ne doit produire AUCUN faux positif — obtenu : " +
                derniers.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // ── Sondages des capacités (miroir Android Studio) ───────────
        fun position(motif: String): org.eclipse.lsp4j.Position {
            val index = source.indexOf(motif)
            check(index >= 0) { "motif introuvable : $motif" }
            val lignes = source.substring(0, index).split('\n')
            return org.eclipse.lsp4j.Position(lignes.size - 1, lignes.last().length)
        }
        val tds = serveur.textDocumentService

        // SURVOL du supertype AppCompatActivity (classpath, import exact).
        val survolAppCompat =
            tds
                .hover(
                    org.eclipse.lsp4j.HoverParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        position("AppCompatActivity()"),
                    ),
                ).join()
        System.out.println(
            "SONDAGE survol AppCompatActivity => " +
                (
                    survolAppCompat
                        ?.contents
                        ?.right
                        ?.toString()
                        ?.take(150) ?: "null"
                ),
        )

        // SURVOL du membre HÉRITÉ setContentView (voie 2-ter ADR 0216 —
        // trois maillons plus haut dans la chaîne AppCompat).
        val survolSetContentView =
            tds
                .hover(
                    org.eclipse.lsp4j.HoverParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        position("setContentView"),
                    ),
                ).join()
        System.out.println(
            "SONDAGE survol setContentView (hérité) => " +
                (
                    survolSetContentView
                        ?.contents
                        ?.right
                        ?.toString()
                        ?.take(150) ?: "null"
                ),
        )

        // COMPLÉTION membre après super. (membres hérités de AppCompatActivity).
        val indexSuper = source.indexOf("super.") + "super.".length
        val lignesSuper = source.substring(0, indexSuper).split('\n')
        val completionSuper =
            tds
                .completion(
                    org.eclipse.lsp4j.CompletionParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        org.eclipse.lsp4j.Position(lignesSuper.size - 1, lignesSuper.last().length),
                    ),
                ).join()
        val itemsSuper = completionSuper?.right?.items ?: emptyList()
        System.out.println(
            "SONDAGE completion apres super. => ${itemsSuper.size} items — " +
                itemsSuper.take(10).joinToString { it.label },
        )

        // RÉFÉRENCES du mot-clé class (ADR 0218).
        val references =
            tds
                .references(
                    org.eclipse.lsp4j.ReferenceParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        position("class MainActivity"),
                        org.eclipse.lsp4j.ReferenceContext(true),
                    ),
                ).join()
        System.out.println("SONDAGE references MainActivity => ${references?.size}")

        facade.close()
        sessionJava.close()
    }

    /**
     * Le VRAI négatif Views : une typo et un appel inexistant restent
     * signalés au milieu du template (garde de non-régression D3).
     */
    @Test
    public fun `views - vrai negatif, toujours signale`() {
        val corpus =
            this.corpus ?: run {
                System.out.println("REPRO_VIEWS_DIR absent — test ignoré")
                return
            }
        val racine = dossierTemp.resolve("projet-views-neg")
        val sources = ecrireTemplate(racine)
        // Exemption detekt ciblée (règle 16) : la chaîne E2E porte
        // QUATRE valeurs (sessions + serveur + espion) — accès nommé.
        val chaine = ouvrirChaine("projet-views-neg", sources, jarsDuCorpus(corpus))
        val sessionJava = chaine.premier
        val facade = chaine.second
        val serveur = chaine.troisieme
        val espion = chaine.quatrieme

        val fichier = File(sources, "com/example/myapplication/MainActivity.kt")
        val source =
            """
            package com.example.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContenuVeu(R.layout.activity_main)
    }
}
            """.trimIndent()
        fichier.writeText(source)
        val uri = fichier.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )

        TimeUnit.MILLISECONDS.sleep(2500)
        val nonResolus =
            (espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList())
                .filter { it.message.contains("non résolue") }
        System.out.println("=== VRAI NÉGATIF VIEWS (${nonResolus.size}) : ${nonResolus.map { it.message }} ===")
        assertEquals(
            1,
            nonResolus.size,
            "La typo « setContenuVeu » doit rester signalée (une seule) — obtenu : ${nonResolus.map { it.message }}",
        )

        facade.close()
        sessionJava.close()
    }

    /**
     * La forme VIEWBINDING du monde non-Compose (généré au build par
     * AGP, synthétisé par K5 AVANT build) : ActivityMainBinding depuis
     * le layout activity_main.xml, binding.root/binding.textView.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — scénario E2E
     * + sondages des capacités verrouillés par assertions (ADR 0219).
     */
    @Suppress("LongMethod")
    @Test
    public fun `views - ViewBinding synthetique, zero faux positif`() {
        val corpus =
            this.corpus ?: run {
                System.out.println("REPRO_VIEWS_DIR absent — test ignoré")
                return
            }
        val racine = dossierTemp.resolve("projet-views-binding")
        val sources = ecrireTemplate(racine)
        // La forme ViewBinding du même écran.
        val source =
            """
            package com.example.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textView.text = getString(R.string.app_name)
    }
}
            """.trimIndent()
        val fichier = File(sources, "com/example/myapplication/MainActivity.kt")
        fichier.writeText(source)
        // Exemption detekt ciblée (règle 16) : la chaîne E2E porte
        // QUATRE valeurs (sessions + serveur + espion) — accès nommé.
        val chaine = ouvrirChaine("projet-views-binding", sources, jarsDuCorpus(corpus))
        val sessionJava = chaine.premier
        val facade = chaine.second
        val serveur = chaine.troisieme
        val espion = chaine.quatrieme

        val uri = fichier.toURI().toString()
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )

        TimeUnit.MILLISECONDS.sleep(2500)
        val derniers = espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
        System.out.println("=== DIAGNOSTICS VIEWBINDING (${derniers.size}) ===")
        derniers.forEach { System.out.println("  ${it.code} ${it.message} @${it.range.start}") }

        // Le binding synthétisé K5 résout ; textView (champ du layout)
        // et root aussi. Les SEULES tolérances acceptées : aucune.
        assertTrue(
            derniers.none { it.message.contains("non résolue", ignoreCase = true) },
            "La forme ViewBinding ne doit produire AUCUN faux positif — obtenu : " +
                derniers.joinToString("\n") { "${it.code} ${it.message}" },
        )

        // ── Sondages des capacités (miroir Android Studio, ADR 0219) ──
        fun position(motif: String): org.eclipse.lsp4j.Position {
            val index = source.indexOf(motif)
            check(index >= 0) { "motif introuvable : $motif" }
            val lignes = source.substring(0, index).split('\n')
            return org.eclipse.lsp4j.Position(lignes.size - 1, lignes.last().length)
        }
        val tds = serveur.textDocumentService

        // SURVOL de la PROPRIÉTÉ SYNTHÉTIQUE Java (ADR 0219) —
        // layoutInflater nu : le getter getLayoutInflater()
        // (android.app.Activity, maillon 4) porte le type.
        val survolLayoutInflater =
            tds
                .hover(
                    org.eclipse.lsp4j.HoverParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        position("layoutInflater"),
                    ),
                ).join()
        System.out.println(
            "SONDAGE survol layoutInflater (synthétique) => " +
                (
                    survolLayoutInflater
                        ?.contents
                        ?.right
                        ?.toString()
                        ?.take(150) ?: "null"
                ),
        )
        assertTrue(
            survolLayoutInflater
                ?.contents
                ?.right
                ?.toString()
                ?.contains("LayoutInflater") == true,
            "le survol de la propriété synthétique doit répondre (ADR 0219) — obtenu : " +
                survolLayoutInflater?.contents?.right,
        )

        // SURVOL de la MÉTHODE HÉRITÉE du maillon 7 (ADR 0219 —
        // android.content.Context : la borne de chaîne corrigée).
        val survolGetString =
            tds
                .hover(
                    org.eclipse.lsp4j.HoverParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        position("getString"),
                    ),
                ).join()
        System.out.println(
            "SONDAGE survol getString (maillon 7) => " +
                (
                    survolGetString
                        ?.contents
                        ?.right
                        ?.toString()
                        ?.take(150) ?: "null"
                ),
        )
        assertTrue(
            survolGetString
                ?.contents
                ?.right
                ?.toString()
                ?.contains("getString") == true,
            "le survol du membre du maillon 7 doit répondre (ADR 0219) — obtenu : " +
                survolGetString?.contents?.right,
        )

        // COMPLÉTION membre après binding. (champs du layout — K5).
        // Motif « binding.root » — le simple « binding. » matcherait
        // DANS « databinding.ActivityMainBinding » de l'import !
        val indexBinding = source.indexOf("binding.root") + "binding.".length
        val lignesBinding = source.substring(0, indexBinding).split('\n')
        val completionBinding =
            tds
                .completion(
                    org.eclipse.lsp4j.CompletionParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                        org.eclipse.lsp4j.Position(lignesBinding.size - 1, lignesBinding.last().length),
                    ),
                ).join()
        val itemsBinding = completionBinding?.right?.items ?: emptyList()
        System.out.println(
            "SONDAGE completion apres binding. => ${itemsBinding.size} items — " +
                itemsBinding.take(10).joinToString { it.label },
        )
        assertTrue(
            itemsBinding.any { it.label == "root" } && itemsBinding.any { it.label == "textView" },
            "les champs du layout (root, textView) s'offrent — obtenu : " +
                itemsBinding.map { it.label },
        )

        facade.close()
        sessionJava.close()
    }
}
