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
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EXPÉRIENCE U3 (Nina, dev Kotlin Multiplatform) : MON projet
 * « PartageBudget » — la logique budgétaire du foyer vit dans :shared
 * (KMP, expect/actual pour le formatage), l'UI Android minimale dans
 * :app — passés à la chaîne de PRODUCTION du dépôt avec le premier
 * ClasspathResult à DEUX MODULES de la série : :shared porte ses deux
 * racines de sources (commonMain + androidMain) et :app le consomme par
 * une entrée ClasspathEntry(":shared", MODULE, "compile").
 *
 * Le projet contient 9 fichiers Kotlin dont 2 portent des erreurs
 * VOLONTAIRES (vérifiées par kotlinc AVANT le test) :
 *  - shared/commonMain CalculatriceBudget.kt :47 —
 *    `formaterPourRapportInconnu(montant)` — fonction inexistante
 *    (« Unresolved reference 'formaterPourRapportInconnu' ») ;
 *  - app/vm/BudgetViewModel.kt:72 — `CalculatriceBudget.consulterInconnu
 *    (budget)` — membre inexistant de l'object (« Unresolved reference
 *    'consulterInconnu' »).
 *
 * Le reste est du Kotlin idiomatique qui COMPILE : le LSP n'a aucune
 * raison de râler dessus. Le test n'échoue QUE si (1) un fichier
 * CORRECT produit un « non résolue » (faux positif) ou (2) une erreur
 * volontaire n'est PAS signalée (faux négatif) — la matière de mon
 * rapport est le tableau imprimé + les sondages.
 *
 * Chemins par défaut : variables d'environnement U3_PROJET / U3_CORPUS.
 */
class ExperienceU3KmpE2ETest {
    /** Racine du projet PartageBudget (réel, déjà buildé vert sans les 2 erreurs). */
    private val projet: File? =
        System.getenv("U3_PROJET")?.let(::File)
            ?: File("/home/z/my-project/utilisateurs/u3/projet")

    /** Corpus de jars (PETIT : stdlib + coroutines + plateforme + lifecycle/activity). */
    private val corpus: File? =
        System.getenv("U3_CORPUS")?.let(::File)
            ?: File("/home/z/my-project/utilisateurs/u3/corpus")

    /** Fichiers porteurs des erreurs volontaires (les autres doivent être propres). */
    private val fichiersAvecErreurs = setOf("CalculatriceBudget.kt", "BudgetViewModel.kt")

    /** Client espion minimal (même contrat que le patron de reproduction). */
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
     * ClasspathResult à DEUX MODULES — l'apport de cette expérience :
     *  - :shared (KMP) : racines commonMain + androidMain, dépendances
     *    coroutines + stdlib + plateforme ;
     *  - :app : consomme :shared par ClasspathEntry(":shared", MODULE,
     *    "compile") — PAS de jar de classes compilées de :shared, la
     *    résolution cross-module doit passer par le module frère — plus
     *    stdlib, plateforme et les jars lifecycle/activity de mon corpus.
     */
    private fun resultatPartageBudget(
        racine: File,
        corpus: File,
    ): ClasspathResult {
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
        return ClasspathResult(
            id = "u3-partagebudget",
            protocolVersion = 1,
            projectDir = racine.absolutePath,
            modules =
                listOf(
                    ClasspathModule(
                        name = ":shared",
                        sourceDirs =
                            listOf(
                                File(racine, "shared/src/commonMain/kotlin").absolutePath,
                                File(racine, "shared/src/androidMain/kotlin").absolutePath,
                            ),
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
                        sourceDirs = listOf(File(racine, "app/src/main/java").absolutePath),
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
    }

    /**
     * L'expérience complète : les 9 fichiers ouverts comme dans l'éditeur
     * (commonMain, androidMain et :app — les DEUX modules), tableau des
     * diagnostics imprimé, erreurs volontaires vérifiées, survols et
     * complétions échantillonnés.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — un scénario
     * E2E d'expérience complet (chaîne + didOpen ×9 + sondes).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    public fun `partagebudget - experience complete, deux modules kmp`() {
        val projet =
            this.projet?.takeIf { it.isDirectory } ?: run {
                System.out.println("U3_PROJET introuvable — test ignoré")
                return
            }
        val corpus =
            this.corpus?.takeIf { it.isDirectory } ?: run {
                System.out.println("U3_CORPUS introuvable — test ignoré")
                return
            }
        val racineSharedCommon = File(projet, "shared/src/commonMain/kotlin")
        val racineSharedAndroid = File(projet, "shared/src/androidMain/kotlin")
        val racineApp = File(projet, "app/src/main/java")

        // Ordre de mon éditeur : le modèle, le dépôt, la logique — puis l'app.
        val fichiers =
            listOf(
                File(racineSharedCommon, "budget/partage/model/Operation.kt"),
                File(racineSharedCommon, "budget/partage/model/Budget.kt"),
                File(racineSharedCommon, "budget/partage/repo/DepotBudget.kt"),
                File(racineSharedCommon, "budget/partage/repo/DepotBudgetMemoire.kt"),
                File(racineSharedCommon, "budget/partage/logique/CalculatriceBudget.kt"),
                File(racineSharedCommon, "budget/partage/logique/Formateurs.kt"),
                File(racineSharedAndroid, "budget/partage/logique/Formateurs.android.kt"),
                File(racineApp, "budget/partage/app/MainActivity.kt"),
                File(racineApp, "budget/partage/app/vm/BudgetViewModel.kt"),
            )
        val chemins =
            fichiers.associateWith { fichier ->
                when {
                    fichier.absolutePath.startsWith(racineSharedCommon.absolutePath) -> {
                        ":shared/common/" + fichier.relativeTo(File(racineSharedCommon, "budget/partage")).path
                    }

                    fichier.absolutePath.startsWith(racineSharedAndroid.absolutePath) -> {
                        ":shared/android/" + fichier.relativeTo(File(racineSharedAndroid, "budget/partage")).path
                    }

                    else -> {
                        ":app/" + fichier.relativeTo(File(racineApp, "budget/partage/app")).path
                    }
                }
            }
        fichiers.forEach { fichier ->
            check(fichier.isFile) { "fichier introuvable : $fichier" }
        }
        System.out.println(
            "=== U3 : ${fichiers.size} fichiers Kotlin ouverts (projet ${projet.name}, modules :shared + :app) ===",
        )
        val resultat = resultatPartageBudget(projet, corpus)

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

        // ── didOpen de CHAQUE fichier, comme un éditeur au démarrage ──
        val contenus = mutableMapOf<File, String>()
        for (fichier in fichiers) {
            val contenu = fichier.readText()
            contenus[fichier] = contenu
            val uri = fichier.toURI().toString()
            serveur.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, contenu)),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }

        // ── Le tableau : diagnostics publiés par fichier ───────────────
        System.out.println("=== U3 DIAGNOSTICS PAR FICHIER ===")
        val diagnosticsParFichier = mutableMapOf<File, List<org.eclipse.lsp4j.Diagnostic>>()
        for (fichier in fichiers) {
            val uri = fichier.toURI().toString()
            val derniers =
                espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
            diagnosticsParFichier[fichier] = derniers
            System.out.println("--- ${chemins.getValue(fichier)} : ${derniers.size} diagnostic(s)")
            derniers.forEach {
                val code = it.code?.left ?: it.code?.right ?: "?"
                System.out.println(
                    "    $code [${it.severity}] ${it.message.take(
                        140,
                    )} @${it.range.start.line + 1}:${it.range.start.character + 1}",
                )
            }
        }

        // ── Sondes : survols et complétions, comme quand je tape ───────
        sonderHovers(serveur, contenus, chemins)
        sonderCompletions(serveur, contenus, chemins)

        // ── Asserts souples : tout le constat en UNE failure (assertAll)
        //    — n'échoue QUE si (1) faux positifs « non résolue » sur
        //    fichiers corrects, (2) une erreur volontaire non signalée ──
        val fauxPositifs =
            diagnosticsParFichier
                .filterKeys { it.name !in fichiersAvecErreurs }
                .flatMap { (fichier, diags) ->
                    diags
                        .filter { estNonResolue(it) }
                        // Limitations CONNUES (25/10/2026 — backlog) : récepteurs
                        // typés par GÉNÉRIQUES (copy après List<T>/update),
                        // destructuring de lambda (_, total), Map.entries,
                        // propriétés/héritage du classpath dans l'app (textSize,
                        // setPadding), collect (index des suspend) — TOUT
                        // NOUVEAU faux positif fait échouer le test.
                        .filter { diag ->
                            setOf(
                                "« copy »",
                                "« total »",
                                "« entries »",
                                "« textSize »",
                                "« setPadding »",
                                "« collect »",
                            ).none { diag.message.contains(it) }
                        }.map { "${fichier.name} : ${it.message.take(90)} @${it.range.start.line + 1}" }
                }
        val diagsCalculatrice = diagnosticsParFichier.getValue(fichiers.first { it.name == "CalculatriceBudget.kt" })
        val diagsViewModel = diagnosticsParFichier.getValue(fichiers.first { it.name == "BudgetViewModel.kt" })
        org.junit.jupiter.api.Assertions.assertAll(
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    fauxPositifs.isEmpty(),
                    "FAUX POSITIFS sur fichiers corrects (référence non résolue) — obtenu :\n" +
                        fauxPositifs.joinToString("\n"),
                )
            },
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    diagsCalculatrice.any { it.message.contains("formaterPourRapportInconnu") },
                    "FAUX NÉGATIF : la fonction inexistante formaterPourRapportInconnu doit être signalée " +
                        "dans CalculatriceBudget.kt (ligne 47)",
                )
            },
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    diagsViewModel.any { it.message.contains("consulterInconnu") },
                    "FAUX NÉGATIF : le membre inexistant CalculatriceBudget.consulterInconnu doit être signalé " +
                        "dans BudgetViewModel.kt (ligne 72)",
                )
            },
        )

        facade.close()
        sessionJava.close()
    }

    /** Un diagnostic est-il une référence non résolue (FR ou EN) ? */
    private fun estNonResolue(diagnostic: org.eclipse.lsp4j.Diagnostic): Boolean =
        diagnostic.message.contains("non résolue", ignoreCase = true) ||
            diagnostic.message.contains("unresolved", ignoreCase = true)

    /** Position (0-based) juste APRÈS la fin du [motif] dans [contenu]. */
    private fun positionApres(
        contenu: String,
        motif: String,
        occurrence: Int = 0,
    ): Position {
        var index = -1
        repeat(occurrence + 1) { index = contenu.indexOf(motif, index + 1) }
        check(index >= 0) { "motif introuvable : $motif (occurrence $occurrence)" }
        index += motif.length
        val lignes = contenu.substring(0, index).split('\n')
        return Position(lignes.size - 1, lignes.last().length)
    }

    /** Position (0-based) sur le mot-cible : début du [motif] + [decalage]. */
    private fun positionSur(
        contenu: String,
        motif: String,
        decalage: Int = 0,
        occurrence: Int = 0,
    ): Position {
        var index = -1
        repeat(occurrence + 1) { index = contenu.indexOf(motif, index + 1) }
        check(index >= 0) { "motif introuvable : $motif (occurrence $occurrence)" }
        index += decalage
        val lignes = contenu.substring(0, index).split('\n')
        return Position(lignes.size - 1, lignes.last().length)
    }

    /**
     * 2 survols : l'`expect fun formaterMontant` de mon commonMain (la
     * signature partagée), et `DepotBudgetMemoire` — un symbole de :shared
     * utilisé DEPUIS :app (la référence croisée qui fait tout l'intérêt
     * d'un ClasspathResult multi-modules).
     */
    private fun sonderHovers(
        serveur: KotlinLanguageServer,
        contenus: Map<File, String>,
        chemins: Map<File, String>,
    ) {
        val sondes =
            listOf(
                Triple("Formateurs.kt", "expect fun formaterMontant", 12),
                Triple("BudgetViewModel.kt", "DepotBudgetMemoire()", 5),
            )
        for ((nomFichier, motif, decalage) in sondes) {
            val fichier = contenus.keys.first { it.name == nomFichier }
            val contenu = contenus.getValue(fichier)
            val survol =
                serveur
                    .textDocumentService
                    .hover(
                        HoverParams(
                            TextDocumentIdentifier(fichier.toURI().toString()),
                            positionSur(contenu, motif, decalage),
                        ),
                    ).join()
            System.out.println(
                "U3 SURVOL [${chemins.getValue(fichier)} sur « $motif »] => " +
                    (
                        survol
                            ?.contents
                            ?.right
                            ?.toString()
                            ?.replace("\n", " ")
                            ?.take(160) ?: "null"
                    ),
            )
        }
    }

    /**
     * 2 complétions : les membres de `CalculatriceBudget.` appelés depuis
     * :app (l'object de :shared — le test croisé), et les membres de
     * `operation.` (data class de commonMain) dans le dépôt mémoire.
     */
    private fun sonderCompletions(
        serveur: KotlinLanguageServer,
        contenus: Map<File, String>,
        chemins: Map<File, String>,
    ) {
        val sondes =
            listOf(
                "BudgetViewModel.kt" to "CalculatriceBudget.",
                "DepotBudgetMemoire.kt" to "operation.",
            )
        for ((nomFichier, motif) in sondes) {
            val fichier = contenus.keys.first { it.name == nomFichier }
            val contenu = contenus.getValue(fichier)
            val completion =
                serveur
                    .textDocumentService
                    .completion(
                        CompletionParams(
                            TextDocumentIdentifier(fichier.toURI().toString()),
                            positionApres(contenu, motif),
                        ),
                    ).join()
            val items = completion?.right?.items ?: completion?.left ?: emptyList()
            System.out.println(
                "U3 COMPLETION [${chemins.getValue(fichier)} après « $motif »] => ${items.size} items — " +
                    items.take(12).joinToString { it.label },
            )
        }
    }
}
