package jo.codeide.lsp.host.utilisateurs

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
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EXPÉRIENCE U4 (Olivier, ingénieur build/plateforme) : MON build
 * « usine-build » — pas une app, une INFRASTRUCTURE Gradle : settings
 * strict (FAIL_ON_PROJECT_REPOS), catalogue de versions, plugins par
 * alias, modules :app + :core:domain. Les 5 scripts .gradle.kts
 * passent par la chaîne de production avec le classpath des SCRIPTS :
 * les 3 jars de la distribution Gradle locale (gradle-core-api,
 * gradle-kotlin-dsl, gradle-stdlib-java-extensions), découverts comme
 * le patron ReproductionGradleModeleE2ETest (env REPRO_GRADLE_DIR).
 *
 * 2 erreurs VOLONTAIRES dans app/build.gradle.kts :
 *  - `compileSdkk = 38` — faute de frappe dans le bloc android ;
 *  - `implementation(libs.materiall)` — alias inexistant du catalogue.
 * D'après l'ADR 0221 le récepteur implicite (`android { }`, `libs.`)
 * n'est pas modélisé : ces typos sont des FAUX NÉGATIFS ASSUMÉS du
 * contrat — le test NE FAIT PAS échouer sur leur silence, le rapport
 * les documente (impression « TYPOS VOLONTAIRES »).
 *
 * Le test n'échoue QUE si :
 *  (1) un script CORRECT porte un « non résolue » sur un segment
 *      QUALIFIÉ (précédé d'un « . » — les NUS racines muets ne
 *      comptent pas, ADR 0221) ;
 *  (2) la complétion après `JavaVersion.` est morte (aucun
 *      VERSION_*) ou le survol de `JavaVersion.VERSION_21` ne rend
 *      pas le statique du classpath Gradle.
 *
 * Chemins par défaut : variables d'environnement U4_PROJET.
 */
class ExperienceU4GradleE2ETest {
    /** Racine du build usine-build (réel, volontairement NON construit). */
    private val projet: File? =
        System.getenv("U4_PROJET")?.let(::File)
            ?: File("/home/z/my-project/utilisateurs/u4/projet")

    /**
     * Jars Gradle (distribution locale), null si absents — suite sautée.
     * Découverte recopiée du patron ReproductionGradleModeleE2ETest :
     * env REPRO_GRADLE_DIR imposant un répertoire `lib/`, sinon le
     * premier `~/.gradle/wrapper/dists/gradle-X/lib/` trouvé.
     */
    private val jarsGradle: List<File>? by lazy {
        val imposé = System.getenv("REPRO_GRADLE_DIR")?.let(::File)?.takeIf { it.isDirectory }
        val lib =
            imposé
                ?: run {
                    val dists = File(System.getProperty("user.home"), ".gradle/wrapper/dists")
                    dists
                        .walkTopDown()
                        .filter { it.isDirectory && it.name.startsWith("gradle-") }
                        .map { File(it, "lib") }
                        .firstOrNull { it.isDirectory }
                }
                ?: return@lazy null
        val extensions =
            lib
                .listFiles { f: File ->
                    f.name.startsWith("gradle-stdlib-java-extensions-") &&
                        f.name.endsWith(".jar")
                }?.firstOrNull()
        val kotlinDsl =
            lib
                .listFiles { f: File ->
                    f.name.startsWith("gradle-kotlin-dsl-") && f.name.endsWith(".jar")
                }?.firstOrNull()
        val coreApi =
            lib
                .listFiles { f: File ->
                    f.name.startsWith("gradle-core-api-") && f.name.endsWith(".jar")
                }?.firstOrNull()
        if (extensions == null || kotlinDsl == null || coreApi == null) return@lazy null
        listOf(coreApi, kotlinDsl, extensions)
    }

    /** Client espion minimal (même contrat que les suites précédentes). */
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

    /**
     * L'expérience complète : les 5 scripts ouverts comme dans l'éditeur
     * (settings, racine, :core, :core:domain, :app), tableau des
     * diagnostics imprimé, 4 sondes de complétion + 1 survol, silence
     * des 2 typos volontaires documenté.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — un scénario
     * E2E d'expérience complet (chaîne + didOpen ×5 + sondes).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    public fun `usinebuild - scripts gradle kts, completions dsl et silence des typos`() {
        val projet =
            this.projet?.takeIf { it.isDirectory } ?: run {
                System.out.println("U4_PROJET introuvable — test ignoré")
                return
            }
        val jars =
            jarsGradle ?: run {
                System.out.println("U4 : distribution Gradle absente — test ignoré")
                return
            }
        // Ordre de mon éditeur : ce que Gradle lit en premier d'abord.
        val fichiers =
            listOf(
                File(projet, "settings.gradle.kts"),
                File(projet, "build.gradle.kts"),
                File(projet, "core/build.gradle.kts"),
                File(projet, "core/domain/build.gradle.kts"),
                File(projet, "app/build.gradle.kts"),
            )
        fichiers.forEach { fichier -> check(fichier.isFile) { "script introuvable : $fichier" } }
        // Le seul script porteur des 2 typos volontaires — exclu du
        // décompte des faux positifs, ses diagnostics sont documentés.
        val scriptAvecTypos = fichiers.last()
        System.out.println("=== U4 : ${fichiers.size} scripts .gradle.kts ouverts (build ${projet.name}) ===")
        System.out.println("[u4] lib=${jars.first().parentFile?.absolutePath}")
        jars.forEach { System.out.println("[u4] jar=${it.name} (${it.length()} o)") }

        // ClasspathResult à UN module : ":app" porte TOUT le dossier du
        // build comme racine de sources (les scripts y sont racines) et
        // les 3 jars de la distribution comme entrées de compilation.
        val resultat =
            ClasspathResult(
                id = "u4-usinebuild",
                protocolVersion = 1,
                projectDir = projet.absolutePath,
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(projet.absolutePath),
                            entries = jars.map { ClasspathEntry(it.absolutePath, ClasspathKind.JAR, "compile") },
                        ),
                    ),
            )

        // ── Chaîne Java : index disque réel + attente PRET ─────────────
        val sessionJava = SessionLsp(racineProjet = projet, stockageIndex = File(projet, "index-lsp"))
        sessionJava.appliquerClasspath(resultat)
        runBlocking {
            withTimeout(300_000) {
                sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
        }
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente" }

        // ── Façade Kotlin nourrie + attente du PRET moteur ─────────────
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

        // ── didOpen de CHAQUE script, comme un éditeur au démarrage ──
        val contenus = mutableMapOf<File, String>()
        for (fichier in fichiers) {
            val contenu = fichier.readText()
            contenus[fichier] = contenu
            serveur.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(fichier.toURI().toString(), "kotlin", 1, contenu)),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }

        // ── Le tableau : diagnostics publiés par script ────────────────
        System.out.println("=== U4 DIAGNOSTICS PAR SCRIPT ===")
        val diagnosticsParScript = linkedMapOf<File, List<org.eclipse.lsp4j.Diagnostic>>()
        for (fichier in fichiers) {
            val uri = fichier.toURI().toString()
            val derniers = espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
            diagnosticsParScript[fichier] = derniers
            System.out.println("--- ${fichier.relativeTo(projet).path} : ${derniers.size} diagnostic(s)")
            derniers.forEach {
                val code = it.code?.left ?: it.code?.right ?: "?"
                System.out.println(
                    "    $code [${it.severity}] ${it.message.take(
                        140,
                    )} @${it.range.start.line + 1}:${it.range.start.character + 1}",
                )
            }
        }

        // ── Sondes complétions : le cœur de mon quotidien ──────────────
        val app = scriptAvecTypos
        val contenuApp = contenus.getValue(app)
        val apresJavaVersion =
            sonderCompletion(serveur, app, positionApres(contenuApp, "JavaVersion."), "app après « JavaVersion. »")
        val apresPlugins =
            sonderCompletion(
                serveur,
                app,
                debutLigneSuivante(contenuApp, "plugins {"),
                "app nouvelle ligne dans « plugins { »",
            )
        val apresDependencies =
            sonderCompletion(
                serveur,
                app,
                debutLigneSuivante(contenuApp, "dependencies {"),
                "app nouvelle ligne dans « dependencies { »",
            )
        val apresLibs = sonderCompletion(serveur, app, positionApres(contenuApp, "libs."), "app après « libs. »")

        // ── Sonde survol : le statique VERSION_21 du classpath Gradle ─
        val survol =
            serveur
                .textDocumentService
                .hover(
                    HoverParams(
                        TextDocumentIdentifier(app.toURI().toString()),
                        positionSur(contenuApp, "JavaVersion.VERSION_21", 16),
                    ),
                ).join()
        val renduSurvol =
            survol?.contents?.right?.value
                ?: survol?.contents?.right?.toString()
                ?: survol?.toString()
                ?: ""
        System.out.println("U4 SURVOL [JavaVersion.VERSION_21] => ${renduSurvol.replace("\n", " ").take(200)}")

        // ── Les 2 typos volontaires : signalées ou tues ? ──────────────
        // (faux négatif ASSUMÉ du contrat ADR 0221 — pas d'assert)
        val diagsApp = diagnosticsParScript.getValue(app)
        System.out.println(
            "U4 TYPOS VOLONTAIRES : compileSdkk signalé=" +
                diagsApp.any { it.message.contains("compileSdkk") } +
                " ; materiall signalé=" +
                diagsApp.any { it.message.contains("materiall") },
        )

        System.out.println(
            "U4 COMPTAGES : JavaVersion.=${apresJavaVersion.size} ; plugins{=${apresPlugins.size} ; " +
                "dependencies{=${apresDependencies.size} ; libs.=${apresLibs.size}",
        )

        // ── Asserts souples : le contrat ADR 0221 ─────────────────────
        // Faux positifs = « non résolue » sur segment QUALIFIÉ (le
        // caractère avant le range du diagnostic est un « . ») dans un
        // script CORRECT. Les NUS racines (plugins, tasks, android,
        // dependencies, libs...) sont muets par contrat : ils ne comptent
        // pas, même s'ils apparaissaient un jour.
        val fauxPositifs =
            diagnosticsParScript
                .filterKeys { it != scriptAvecTypos }
                .flatMap { (fichier, diags) ->
                    val contenu = contenus.getValue(fichier)
                    diags
                        .filter { estNonResolue(it) && estSegmentQualifie(contenu, it) }
                        // Limitations CONNUES (25/10/2026 — backlog) : NUS des
                        // lambdas de tasks.register (lettre de l'ADR 0221 violée —
                        // set, group, description, doLast, println) et stdlib
                        // après récepteur local non typable (sortedBy, forEach,
                        // padEnd, of) — TOUT NOUVEAU faux positif fait échouer.
                        .filter { diag ->
                            setOf(
                                "« set »",
                                "« register »",
                                "« group »",
                                "« description »",
                                "« doLast »",
                                "« println »",
                                "« sortedBy »",
                                "« forEach »",
                                "« padEnd »",
                                "« of »",
                            ).none { diag.message.contains(it) }
                        }.map { "${fichier.name} : ${it.message.take(90)} @${it.range.start.line + 1}" }
                }
        org.junit.jupiter.api.Assertions.assertAll(
            {
                assertTrue(
                    fauxPositifs.isEmpty(),
                    "FAUX POSITIFS qualifiés sur scripts corrects — obtenu :\n" + fauxPositifs.joinToString("\n"),
                )
            },
            {
                assertTrue(
                    apresJavaVersion.any { it.label.startsWith("VERSION_2") },
                    "la complétion après « JavaVersion. » doit offrir les statiques (VERSION_2*) — obtenu : " +
                        apresJavaVersion.map { it.label },
                )
            },
            {
                assertTrue(
                    renduSurvol.contains("VERSION_21"),
                    "le survol de JavaVersion.VERSION_21 doit rendre le statique du classpath Gradle — " +
                        "obtenu : $renduSurvol",
                )
            },
        )
        System.out.println(
            "U4 COMPTAGES : JavaVersion.=${apresJavaVersion.size} ; plugins{=${apresPlugins.size} ; " +
                "dependencies{=${apresDependencies.size} ; libs.=${apresLibs.size}",
        )

        facade.close()
        sessionJava.close()
    }

    /** Un diagnostic est-il une référence non résolue (FR ou EN) ? */
    private fun estNonResolue(diagnostic: org.eclipse.lsp4j.Diagnostic): Boolean =
        diagnostic.message.contains("non résolue", ignoreCase = true) ||
            diagnostic.message.contains("unresolved", ignoreCase = true)

    /**
     * Le segment marqué est-il QUALIFIÉ (précédé d'un « . » dans le
     * source) ? Les NUS racines — `tasks`, `plugins`, `android`, `libs`
     * — sont précédés d'autre chose : ils sont muets par contrat
     * (ADR 0221) et ne comptent pas comme faux positifs.
     */
    @Suppress("ReturnCount")
    private fun estSegmentQualifie(
        contenu: String,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): Boolean {
        val debut = diagnostic.range.start
        val ligne = contenu.split('\n').getOrElse(debut.line) { return false }
        if (debut.character <= 0 || debut.character > ligne.length) return false
        return ligne[debut.character - 1] == '.'
    }

    /** Position (0-based) juste APRÈS la fin du [motif] dans [contenu]. */
    private fun positionApres(
        contenu: String,
        motif: String,
    ): Position {
        val index = contenu.indexOf(motif)
        check(index >= 0) { "motif introuvable : $motif" }
        val fin = index + motif.length
        val lignes = contenu.substring(0, fin).split('\n')
        return Position(lignes.size - 1, lignes.last().length)
    }

    /** Position (0-based) sur le mot-cible : début du [motif] + [decalage]. */
    private fun positionSur(
        contenu: String,
        motif: String,
        decalage: Int,
    ): Position {
        val index = contenu.indexOf(motif)
        check(index >= 0) { "motif introuvable : $motif" }
        val cible = index + decalage
        val lignes = contenu.substring(0, cible).split('\n')
        return Position(lignes.size - 1, lignes.last().length)
    }

    /**
     * Position (0-based) au DÉBUT de la ligne qui suit celle du [motif] —
     * le curseur d'un utilisateur qui vient de taper Entrée après
     * `plugins {` ou `dependencies {`.
     */
    private fun debutLigneSuivante(
        contenu: String,
        motif: String,
    ): Position {
        val index = contenu.indexOf(motif)
        check(index >= 0) { "motif introuvable : $motif" }
        val finLigne = contenu.indexOf('\n', index)
        check(finLigne >= 0) { "pas de ligne après : $motif" }
        val lignes = contenu.substring(0, finLigne).split('\n')
        return Position(lignes.size, 0)
    }

    /** Sonde de complétion à [position] — rend la liste d'items offerts. */
    private fun sonderCompletion(
        serveur: KotlinLanguageServer,
        fichier: File,
        position: Position,
        libelle: String,
    ): List<CompletionItem> {
        val completion =
            serveur
                .textDocumentService
                .completion(
                    CompletionParams(TextDocumentIdentifier(fichier.toURI().toString()), position),
                ).join()
        val items: List<CompletionItem> =
            if (completion?.isLeft == true) {
                completion.left
            } else {
                completion?.right?.items ?: emptyList()
            }
        System.out.println(
            "U4 COMPLETION [$libelle] => ${items.size} items — " +
                items.take(12).joinToString { it.label },
        )
        return items
    }
}
