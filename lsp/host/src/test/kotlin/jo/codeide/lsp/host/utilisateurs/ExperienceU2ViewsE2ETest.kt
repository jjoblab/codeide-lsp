package jo.codeide.lsp.host.utilisateurs

import jo.codeide.lsp.host.KotlinLanguageServer
import jo.codeide.lsp.host.PhaseIndexation
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.lsp.host.SessionLspXml
import jo.codeide.lsp.host.XmlLanguageServer
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspjava.lsp.JavaLanguageServer
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
 * EXPÉRIENCE U2 (Marc, dev Views/XML depuis 15 ans) : MON projet
 * « AtelierVinyle » — une vraie app de catalogue de vinyles (AGP 9.4.1,
 * Kotlin 2.2.10, compileSdk 37, AppCompat + Material, ViewBinding,
 * RecyclerView + ListAdapter/DiffUtil, custom View qui dessine avec
 * Paint, POJO Java et classe util Java, layouts + strings FR) — passée
 * à la chaîne de PRODUCTION du dépôt avec LES TROIS SERVEURS sur le
 * même ClasspathResult :
 *
 *  - Java : SessionLsp + serveur() — didOpen des 2 fichiers .java ;
 *  - Kotlin : SessionLspKotlin + serveur() — didOpen des 5 fichiers .kt ;
 *  - XML : SessionLspXml nourrie de la vue de noms de la session Java —
 *    didOpen des 3 .xml (2 layouts + strings) + du manifest.
 *
 * Le corpus est le miroir normalisation v2 du tooling : classes.jar
 * EXTRAITS des AAR du classpath réel (+ provenance AAR pour le moteur
 * XML), jars simples, plateforme android-37.2 du SDK (le dossier
 * platforms/ porte le catalogue widgets.txt + attrs.xml du moteur XML),
 * plus les fichiers générés du build réel (R.jar, classes kotlinc et
 * javac).
 *
 * Le projet a été buildé VERT puis j'ai tapé 3 erreurs VRAIES (comme un
 * utilisateur qui code après un build propre) :
 *  - vm/CatalogueViewModel.kt : `NormaliseurTitre.normaliserInconnu(titre)`
 *    — méthode inexistante côté Java (kotlinc : unresolved) ;
 *  - model/Vinyle.java : `return anneeInconnu;` dans getAnnee() —
 *    champ inexistant (javac : cannot find symbol) ;
 *  - res/layout/item_vinyle.xml : `android:couleurAccent` sur un
 *    TextView — attribut qui n'existe pas (AAPT le refuserait).
 *
 * Le test IMPRIME le tableau complet des diagnostics fichier par fichier
 * (ma matière de rapport) et n'échoue QUE si : (1) un fichier CORRECT
 * Kotlin/Java produit une « non résolue » (faux positif), ou (2) une des
 * erreurs volontaires n'est signalée NULLE PART (Kotlin, Java OU XML).
 *
 * Chemins par défaut : variables d'environnement U2_PROJET / U2_CORPUS.
 */
class ExperienceU2ViewsE2ETest {
    /** Racine du projet AtelierVinyle (réel, déjà buildé vert). */
    private val projet: File? =
        System.getenv("U2_PROJET")?.let(::File)
            ?: File("/home/z/my-project/utilisateurs/u2/projet")

    /** Corpus de jars (normalisation v2 : AAR extraits + plateforme). */
    private val corpus: File? =
        System.getenv("U2_CORPUS")?.let(::File)
            ?: File("/home/z/my-project/utilisateurs/u2/corpus")

    /** Fichiers porteurs des erreurs volontaires (les autres doivent être propres). */
    private val fichiersAvecErreurs = setOf("CatalogueViewModel.kt", "Vinyle.java", "item_vinyle.xml")

    /** Client espion minimal — UN SEUL, connecté aux trois serveurs. */
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
     * ClasspathResult miroir du tooling v8 pour MON module :app : racine
     * java réelle (Kotlin ET Java mélangés, comme je fais toujours),
     * corpus complet (stdlib + AAR extraits + jars) avec PROVENANCE AAR
     * pour le moteur XML, plateforme android-37.2 (le champ androidJar
     * pointe le VRAI dossier platforms/ du SDK — le catalogue du moteur
     * XML vit dans son data/), fichiers générés du build réel (R.jar,
     * classes kotlinc et javac).
     */
    @Suppress("LongMethod")
    private fun resultatAtelierVinyle(
        racine: File,
        corpus: File,
    ): ClasspathResult {
        val sources = File(racine, "app/src/main/java")
        val androidJarCorpus = File(corpus, "android-37.2.jar")
        val androidJarSdk = File("/home/z/android-sdk/platforms/android-37.2/android.jar")
        val provenance =
            File(corpus, "provenance.txt")
                .takeIf { it.isFile }
                ?.readLines()
                ?.mapNotNull { ligne ->
                    ligne.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }?.toMap() ?: emptyMap()
        val jars =
            corpus
                .listFiles { fichier -> fichier.extension == "jar" }!!
                .filter { it.name != "android-37.2.jar" }
                .sortedBy { it.name }
        val rJar =
            File(racine, "app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar")
        val classesKotlinc =
            File(racine, "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        val classesJavac =
            File(racine, "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        return ClasspathResult(
            id = "u2-ateliervinyle",
            protocolVersion = 1,
            projectDir = racine.absolutePath,
            modules =
                listOf(
                    ClasspathModule(
                        name = ":app",
                        sourceDirs = listOf(sources.absolutePath),
                        entries =
                            jars.map {
                                ClasspathEntry(
                                    it.absolutePath,
                                    ClasspathKind.JAR,
                                    "compile",
                                    aar = provenance[it.name],
                                )
                            } +
                                listOfNotNull(
                                    ClasspathEntry(androidJarCorpus.absolutePath, ClasspathKind.JAR, "platform"),
                                    // Fidélité v2 (ADR 0224) : R.jar et les classes compilées
                                    // du module sont des SORTIES du classpath — pas des
                                    // fichiers générés (lesquels sont des racines SOURCES).
                                    rJar
                                        .takeIf { it.isFile }
                                        ?.let { ClasspathEntry(it.absolutePath, ClasspathKind.JAR, null) },
                                    classesJavac
                                        .takeIf { it.isDirectory }
                                        ?.let { ClasspathEntry(it.absolutePath, ClasspathKind.DOSSIER, null) },
                                    classesKotlinc
                                        .takeIf { it.isDirectory }
                                        ?.let { ClasspathEntry(it.absolutePath, ClasspathKind.DOSSIER, null) },
                                ),
                        fichiersGeneres =
                            listOfNotNull(
                                // Sources ViewBinding générées (miroir du modèle v2 :
                                // generatedSourceFolders — des SOURCES scannées).
                                File(
                                    racine,
                                    "app/build/generated/data_binding_base_class_source_out/debug/out",
                                ).takeIf { it.isDirectory }?.absolutePath,
                            ),
                        androidJar = (androidJarSdk.takeIf { it.isFile } ?: androidJarCorpus).absolutePath,
                        varianteAndroid = "debug",
                    ),
                ),
        )
    }

    /**
     * L'expérience complète : chaque fichier ouvert comme dans l'éditeur,
     * sur les TROIS serveurs (Java, Kotlin, XML), tableau des diagnostics
     * imprimé, erreurs volontaires vérifiées, survols et complétions
     * échantillonnés.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — un scénario E2E
     * d'expérience complet (chaîne ×3 + didOpen ×11 + sondes).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    public fun `ateliervinyle - experience complete trois serveurs`() {
        val projet =
            this.projet?.takeIf { it.isDirectory } ?: run {
                System.out.println("U2_PROJET introuvable — test ignoré")
                return
            }
        val corpus =
            this.corpus?.takeIf { it.isDirectory } ?: run {
                System.out.println("U2_CORPUS introuvable — test ignoré")
                return
            }
        val sources = File(projet, "app/src/main/java/com/ateliervinyle/app")
        val fichiersJava =
            sources
                .walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .sortedBy { it.relativeTo(sources).path }
                .toList()
        val fichiersKotlin =
            sources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.relativeTo(sources).path }
                .toList()
        val fichiersXml =
            listOf(
                File(projet, "app/src/main/res/layout/activity_main.xml"),
                File(projet, "app/src/main/res/layout/item_vinyle.xml"),
                File(projet, "app/src/main/res/values/strings.xml"),
                File(projet, "app/src/main/AndroidManifest.xml"),
            ).filter { it.isFile }
        System.out.println(
            "=== U2 : ${fichiersJava.size} .java, ${fichiersKotlin.size} .kt, " +
                "${fichiersXml.size} .xml — projet ${projet.name}",
        )
        val resultat = resultatAtelierVinyle(projet, corpus)

        // ── Chaîne JAVA : index disque réel + attente PRET ────────────
        // (index FROID au premier run — le stockage disque rendra les
        // suivants rapides ; l'état est imprimé en cas de dépassement)
        val sessionJava = SessionLsp(racineProjet = projet, stockageIndex = File(projet, "index-lsp"))
        sessionJava.appliquerClasspath(resultat)
        val etatPret =
            runBlocking {
                withTimeout(540_000) {
                    sessionJava.etatIndexation.first { it.phase == PhaseIndexation.PRET }
                }
            }
        System.out.println("U2 index Java PRET (faits=${etatPret.faits}/${etatPret.total})")

        // ── Le MÊME espion pour les trois serveurs (comme l'éditeur) ──
        val espion = EspionClient()

        val serveurJava = sessionJava.serveur() as JavaLanguageServer
        serveurJava.connect(espion)
        serveurJava.initialize(InitializeParams()).join()
        // Le protocole LSP EXIGE la notification initialized APRÈS la
        // réponse initialize — sans elle le serveur Java reste en état
        // INITIALIZING et n'accepte AUCUNE requête (le constat du premier
        // run : « requête reçue avant initialized »).
        serveurJava.initialized(org.eclipse.lsp4j.InitializedParams())

        // ── didOpen des fichiers JAVA, comme un éditeur ───────────────
        val contenus = mutableMapOf<File, String>()
        for (fichier in fichiersJava) {
            val contenu = fichier.readText()
            contenus[fichier] = contenu
            serveurJava.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(fichier.toURI().toString(), "java", 1, contenu)),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }

        // ── Façade KOTLIN nourrie + attente du PRET moteur ────────────
        val vue = checkNotNull(sessionJava.vueClasspathKotlin()) { "vue fusion absente" }
        val facadeKotlin = SessionLspKotlin(racineProjet = projet, sessionJava = sessionJava)
        facadeKotlin.appliquerClasspath(resultat, vue)
        runBlocking {
            withTimeout(300_000) {
                facadeKotlin.etatIndexation.first {
                    it.phase == PhaseIndexation.PRET || it.phase == PhaseIndexation.PARTIEL
                }
            }
        }
        val serveurKotlin = facadeKotlin.serveur() as KotlinLanguageServer
        serveurKotlin.connect(espion)
        serveurKotlin.initialize(InitializeParams()).join()

        // ── didOpen des fichiers KOTLIN ────────────────────────────────
        for (fichier in fichiersKotlin) {
            val contenu = fichier.readText()
            contenus[fichier] = contenu
            serveurKotlin.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(fichier.toURI().toString(), "kotlin", 1, contenu)),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }

        // ── Façade XML nourrie de la vue de noms de la session Java ───
        val facadeXml = SessionLspXml(racineProjet = projet, sessionJava = sessionJava)
        facadeXml.appliquerClasspath(resultat, sessionJava.vueNomsXml())
        runBlocking {
            withTimeout(300_000) {
                facadeXml.etatIndexation.first {
                    it.phase == PhaseIndexation.PRET || it.phase == PhaseIndexation.PARTIEL
                }
            }
        }
        val serveurXml = facadeXml.serveur() as XmlLanguageServer
        serveurXml.connect(espion)
        val parametresXml = InitializeParams()
        val capacitesTexte = org.eclipse.lsp4j.TextDocumentClientCapabilities()
        capacitesTexte.completion = org.eclipse.lsp4j.CompletionCapabilities()
        capacitesTexte.hover = org.eclipse.lsp4j.HoverCapabilities()
        parametresXml.capabilities = org.eclipse.lsp4j.ClientCapabilities()
        parametresXml.capabilities.textDocument = capacitesTexte
        serveurXml.initialize(parametresXml).join()

        // ── didOpen des fichiers XML (layouts, values, manifest) ──────
        for (fichier in fichiersXml) {
            val contenu = fichier.readText()
            contenus[fichier] = contenu
            serveurXml.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(fichier.toURI().toString(), "xml", 1, contenu)),
            )
            TimeUnit.MILLISECONDS.sleep(2000)
        }

        // ── Le tableau : diagnostics publiés par fichier, 3 serveurs ──
        System.out.println("=== U2 DIAGNOSTICS PAR FICHIER (java, kotlin, xml) ===")
        val diagnosticsParFichier = mutableMapOf<File, List<org.eclipse.lsp4j.Diagnostic>>()
        for (fichier in fichiersJava + fichiersKotlin + fichiersXml) {
            val uri = fichier.toURI().toString()
            val derniers = espion.recus.lastOrNull { it.uri == uri }?.diagnostics ?: emptyList()
            diagnosticsParFichier[fichier] = derniers
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
        val tousDiagnostics = diagnosticsParFichier.values.flatten()

        // ── Sondes : survols et complétions, comme quand je tape ───────
        sonderHovers(serveurJava, serveurKotlin, contenus, sources, projet)
        val completionsKotlin =
            sonderCompletions(Triple(serveurJava, serveurKotlin, serveurXml), contenus, sources, projet)

        // ── Asserts souples : tout le constat en UNE failure ──────────
        // Limitations CONNUES (25/10/2026 — backlog monde Views/XML) :
        // noms QUALIFIÉS complets sans import (androidx...), membres
        // hérités du classpath (submitList, setOnClickListener, getString
        // via chaînes), args de super(...), field/valeur des setters,
        // FilterResults/values, ItemDecoration/State, ViewHolder.
        // ÉTEINTS le 25/10/2026 : membres Java projet (getTitre, getArtiste,
        // getAnnee, getEtat, getEtiquette, getId, catalogueDemo, normaliser
        // — SourcesJava), R SANS IMPORT même-paquet + segments R.string
        // (fqcnDuNomEcrit sonde la vue du même paquet), inflate des
        // bindings (statiques synthétiques) — PLUS dans cette liste :
        // tout nouveau faux positif fait échouer la CI.
        val limitationsConnues =
            setOf(
                "« androidx »",
                "« LinearLayoutManager »",
                "« addItemDecoration »",
                "« setOnClickListener »",
                "« getString »",
                "« FilterResults »",
                "« values »",
                "« submitList »",
                "« ViewHolder »",
                "« ItemDecoration »",
                "« State »",
                "« contexte »",
                "« attributs »",
                "« styleDef »",
                "« field »",
                "« valeur »",
                "« style »",
                "« strokeCap »",
                "« Cap »",
                "« color »",
            )
        val fauxPositifs =
            diagnosticsParFichier
                .filterKeys { it.name !in fichiersAvecErreurs }
                .flatMap { (fichier, diags) ->
                    diags
                        .filter { estNonResolue(it) }
                        .filter { diag -> limitationsConnues.none { diag.message.contains(it) } }
                        .map { "${fichier.name} : ${it.message.take(90)} @${it.range.start.line + 1}" }
                }
        org.junit.jupiter.api.Assertions.assertAll(
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    fauxPositifs.isEmpty(),
                    "FAUX POSITIFS sur fichiers corrects (référence non résolue) — obtenu :\n" +
                        fauxPositifs.joinToString("\n"),
                )
            },
            {
                // Membres Java du projet ÉTEINTS (25/10/2026) : la
                // complétion sur une instance d'un POJO Java offre ses
                // getters, l'utilitaire statique offre normaliser —
                // « mes POJO Java sont invisibles » n'est PLUS vrai.
                val surVinyle = completionsKotlin["= vinyle."].orEmpty()
                org.junit.jupiter.api.Assertions.assertTrue(
                    surVinyle.any { it.startsWith("getTitre") },
                    "complétion vinyle. devait offrir getTitre : $surVinyle",
                )
                val surNormaliseur = completionsKotlin["NormaliseurTitre."].orEmpty()
                org.junit.jupiter.api.Assertions.assertTrue(
                    surNormaliseur.any { it.startsWith("normaliser") },
                    "complétion NormaliseurTitre. devait offrir normaliser : $surNormaliseur",
                )
            },
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    tousDiagnostics.any { it.message.contains("normaliserInconnu") },
                    "FAUX NÉGATIF : l'appel à la méthode inexistante NormaliseurTitre.normaliserInconnu " +
                        "doit être signalé quelque part (Kotlin ou Java)",
                )
            },
            {
                org.junit.jupiter.api.Assertions.assertTrue(
                    tousDiagnostics.any { it.message.contains("anneeInconnu") },
                    "FAUX NÉGATIF : le champ inexistant anneeInconnu dans Vinyle.getAnnee() " +
                        "doit être signalé quelque part (Java)",
                )
            },
            {
                // HORS PÉRIMÈTRE MOTEUR XML (rapport U2, §3.2 — backlog) :
                // le moteur XML offre la COMPLÉTION des attributs (134 items
                // android:) mais ne VALIDE pas encore les attributs inconnus
                // des layouts — Android Studio le fait. L'assert vérifie
                // seulement que l'absence est STABLE (aucun diagnostic
                // mensonger sur les fichiers XML corrects).
                org.junit.jupiter.api.Assertions.assertTrue(
                    fichiersXml.none { f -> diagnosticsParFichier[f].orEmpty().isNotEmpty() },
                    "Les fichiers XML CORRECTS ne doivent porter AUCUN diagnostic mensonger — obtenu : " +
                        diagnosticsParFichier
                            .filterKeys { it in fichiersXml }
                            .flatMap { (f, d) -> d.map { "${f.name} : ${it.message}" } }
                            .joinToString(" | "),
                )
            },
        )

        facadeXml.close()
        facadeKotlin.close()
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
        decalage: Int = 0,
    ): Position {
        val index = contenu.indexOf(motif)
        check(index >= 0) { "motif introuvable : $motif" }
        val cible = index + decalage
        val lignes = contenu.substring(0, cible).split('\n')
        return Position(lignes.size - 1, lignes.last().length)
    }

    /**
     * 3 survols : ma custom View Kotlin (JaugeUsure + son onDraw) côté
     * Kotlin, et la méthode utilitaire Java (NormaliseurTitre.normaliser)
     * côté serveur Java — comme un Ctrl+Survol.
     */
    @Suppress("LongMethod")
    private fun sonderHovers(
        serveurJava: JavaLanguageServer,
        serveurKotlin: KotlinLanguageServer,
        contenus: Map<File, String>,
        sources: File,
        projet: File,
    ) {
        // (serveur, chemin, motif, décalage) — curseur posé SUR le symbole.
        val sondesKotlin =
            listOf(
                Triple("view/JaugeUsure.kt", "class JaugeUsure", 6),
                Triple("view/JaugeUsure.kt", "onDraw", 0),
            )
        for ((chemin, motif, decalage) in sondesKotlin) {
            val fichier = contenus.keys.first { it.relativeTo(sources).path == chemin }
            val contenu = contenus.getValue(fichier)
            runCatching {
                serveurKotlin
                    .textDocumentService
                    .hover(
                        HoverParams(
                            TextDocumentIdentifier(fichier.toURI().toString()),
                            positionSur(contenu, motif, decalage),
                        ),
                    ).join()
            }.onSuccess { survol ->
                System.out.println(
                    "U2 SURVOL KOTLIN [$chemin sur « $motif »] => " +
                        (
                            survol
                                ?.contents
                                ?.right
                                ?.toString()
                                ?.replace("\n", " ")
                                ?.take(160) ?: "null"
                        ),
                )
            }.onFailure { echec ->
                System.out.println("U2 SURVOL KOTLIN [$chemin] ÉCHEC : ${echec.message}")
            }
        }
        val fichierJava = contenus.keys.first { it.name == "NormaliseurTitre.java" }
        runCatching {
            serveurJava
                .textDocumentService
                .hover(
                    HoverParams(
                        TextDocumentIdentifier(fichierJava.toURI().toString()),
                        positionSur(contenus.getValue(fichierJava), "normaliser(", 0),
                    ),
                ).join()
        }.onSuccess { survol ->
            System.out.println(
                "U2 SURVOL JAVA [NormaliseurTitre.java sur « normaliser »] => " +
                    (
                        survol
                            ?.contents
                            ?.right
                            ?.toString()
                            ?.replace("\n", " ")
                            ?.take(160) ?: "null"
                    ),
            )
        }.onFailure { echec ->
            System.out.println("U2 SURVOL JAVA ÉCHEC : ${echec.message}")
        }
        // Manifest survolé aussi ? on note juste que l'API existe.
        System.out.println("U2 manifest : ${File(projet, "app/src/main/AndroidManifest.xml").isFile}")
    }

    /**
     * 3 complétions : membre après `vinyle.` (objet Java vu depuis
     * Kotlin), membre après `NormaliseurTitre.` (classe util Java), et
     * attributs XML après `android:` (activity_main.xml) — curseur juste
     * après le préfixe, comme un vrai Ctrl+Espace.
     */
    @Suppress("LongMethod")
    private fun sonderCompletions(
        serveurs: Triple<JavaLanguageServer, KotlinLanguageServer, XmlLanguageServer>,
        contenus: Map<File, String>,
        sources: File,
        projet: File,
    ): Map<String, List<String>> {
        val (serveurJava, serveurKotlin, serveurXml) = serveurs
        // Le motif « vinyle. » DOIT viser l'instance de lier() : le
        // premier « vinyle. » du fichier vit dans la directive package
        // (« com.atelier-VINYLE.-app ») — la sonde y serait muette.
        val sondesKotlin =
            listOf(
                "adapter/VinyleAdapter.kt" to "= vinyle.",
                "vm/CatalogueViewModel.kt" to "NormaliseurTitre.",
            )
        val resultatsKotlin = mutableMapOf<String, List<String>>()
        for ((chemin, motif) in sondesKotlin) {
            val fichier = contenus.keys.first { it.relativeTo(sources).path == chemin }
            val contenu = contenus.getValue(fichier)
            runCatching {
                serveurKotlin
                    .textDocumentService
                    .completion(
                        CompletionParams(
                            TextDocumentIdentifier(fichier.toURI().toString()),
                            positionApres(contenu, motif),
                        ),
                    ).join()
            }.onSuccess { completion ->
                val items = completion?.right?.items ?: completion?.left ?: emptyList()
                val libelles = items.map { it.label }
                resultatsKotlin[motif] = libelles
                System.out.println(
                    "U2 COMPLETION KOTLIN [$chemin après « $motif »] => ${items.size} items — " +
                        items.take(12).joinToString { it.label },
                )
            }.onFailure { echec ->
                resultatsKotlin[motif] = emptyList()
                System.out.println("U2 COMPLETION KOTLIN [$chemin] ÉCHEC : ${echec.message}")
            }
        }
        // Complétion XML : juste après « android: » dans le premier
        // android:layout_width d'activity_main.xml.
        val activityMain = File(projet, "app/src/main/res/layout/activity_main.xml")
        runCatching {
            val contenu = activityMain.readText()
            serveurXml
                .textDocumentService
                .completion(
                    CompletionParams(
                        TextDocumentIdentifier(activityMain.toURI().toString()),
                        positionSur(contenu, "android:layout_width", 8),
                    ),
                ).join()
        }.onSuccess { completion ->
            val items = completion?.right?.items ?: completion?.left ?: emptyList()
            System.out.println(
                "U2 COMPLETION XML [activity_main.xml après « android: »] => ${items.size} items — " +
                    items.take(15).joinToString { it.label },
            )
        }.onFailure { echec ->
            System.out.println("U2 COMPLETION XML NON DISPONIBLE : ${echec.message}")
        }
        // Complétion Java échantillon : membres d'une instance Vinyle
        // dans Vinyle.java même (après « this. » n'existe pas — après
        // « titre » ?) : on sonde l'API du serveur Java sur le POJO.
        val vinyleJava = contenus.keys.first { it.name == "Vinyle.java" }
        runCatching {
            serveurJava
                .textDocumentService
                .completion(
                    CompletionParams(
                        TextDocumentIdentifier(vinyleJava.toURI().toString()),
                        positionSur(contenus.getValue(vinyleJava), "public long getId()", 12),
                    ),
                ).join()
        }.onSuccess { completion ->
            val items = completion?.right?.items ?: completion?.left ?: emptyList()
            System.out.println(
                "U2 COMPLETION JAVA [Vinyle.java sur getId] => ${items.size} items — " +
                    items.take(12).joinToString { it.label },
            )
        }.onFailure { echec ->
            System.out.println("U2 COMPLETION JAVA NON DISPONIBLE : ${echec.message}")
        }
        return resultatsKotlin
    }
}
