import java.util.concurrent.TimeUnit
import org.gradle.api.attributes.LibraryElements

// :lsp:testing — suite d'intégration moteur ↔ index disque (mission
// « LSP Java », étape 3 lot C ; déménagement v0.96.3).
//
// MODULE DE TEST (pattern `:tooling:testing`, prompt compagnon Tooling
// §2.2 — dépendance de test uniquement) : la suite d'intégration vit
// AU-DESSUS des deux modules qu'elle éprouve — `:lsp:java` (moteur
// vendored, Java) et `:lsp:index` (index disque, Kotlin). Elle remplace
// le `testImplementation(project(":lsp:index"))` qui vivait dans
// `:lsp:java` depuis le lot C : la CI l'a rejeté au
// `checkModuleDependencies` (section 5.2 — le module vendored ne
// dépend d'AUCUN module interne, test compris, ADR 0108).
//
// JAVA (même exemption ADR 0108 que :lsp:java) : les tests rejoignent
// le PAQUET `jo.lspjava.ecj` — split package de TEST sur le classpath
// (aucun module-info dans le moteur) pour les membres package-private
// (`typeIndex()`, accesseurs `debug*()`). L'ADAPTATEUR
// `AdaptateurIndexNoms` est l'homologue de TEST de l'adaptateur de
// production (`:lsp:host`, étape 4) : il prouve le branchement réel
// (le moteur Java consommant l'index Kotlin à travers la SPI publique
// `jo.lspjava.api.IndexNoms`).
//
// kover : la CI appelle `koverVerify` sur tous les modules qui
// l'appliquent — c'est ainsi que cette suite tourne en CI (le moteur
// :lsp:java, lui, n'applique pas kover : ADR 0108).

plugins {
    id("maven-publish")
    `java-library`
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// NOTE : les dépôts sont définis centralement dans settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS).

dependencies {
    // Moteur vendored — les jars Eclipse patchés sont DÉSORMAIS GRAPHÉS
    // dans le jar de :lsp:java (v1.0.1) : la résolution du classpath de
    // test exécute le patcher d'abord (dependsOn du jar du module).
    //
    // ATTRIBUT JAR EXIGÉ (lot LSP-1, reproduction clone frais 2026-10-20)
    // : le variant SECONDAIRE « classes » (répertoire) que propose
    // :lsp:java ne transporte PAS le graphe ECJ fusionné — un
    // consommateur java pur peut le sélectionner et échouer au compil
    // (`cannot access ClassFileReader` dans IndexNomsIntegrationTest).
    // AGP le fait déjà naturellement côté :lsp:host (consommation par
    // JAR, voir lsp/java/build.gradle.kts) ; on EXIGE le même variant
    // ici : les types ECJ ne vivent QUE dans le jar fusionné.
    testImplementation(project(":lsp:java")) {
        attributes {
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class, LibraryElements.JAR),
            )
        }
    }

    // Index disque réel (Kotlin) — :lsp:index expose api(:lsp:model),
    // donc ClasspathInstantane et les types d'entrées joignent le
    // classpath de COMPIL du test (l'adaptateur les instancie).
    testImplementation(project(":lsp:index"))

    // Coroutines au COMPIL du test (l'impl du service est
    // `implementation` côté :lsp:index — non transitive au compil).
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Tas borné pour la machine CI (4 Go) — voir gradle.properties.
    maxHeapSize = "640m"
    // Les sondes du moteur cherchent un JDK COMPLET (lib/src.zip,
    // lib/modules) : le JDK du démon Gradle est complet par
    // construction (Temurin 21 en local et en CI) — cf. :lsp:java.
    environment("JO_TEST_JDK_HOME", System.getProperty("java.home"))
}

// ── Banc moteur (lot F2, docs/LSP_JAVA.md §15.8) ─────────────────────
//
// Port du banc d'étude DANS LE DÉPÔT — vit dans le source set de TEST
// de :lsp:testing (paquet jo.lspjava.ecj : accès package-private à
// AdaptateurIndexNoms, même motif que la suite d'intégration). Le
// banc compare le moteur AVANT (index mémoire, balayage W3) et APRÈS
// (branchement index disque, lot C) sur les jeux §12 — cible §9 :
// complétion types p95 < 100 ms.
//
// ÉCART au design §15.8 (source set bench de :lsp:java) : la leçon
// v0.96.3 s'applique — le module vendored ne référence AUCUN module
// interne, banc compris (ADR 0108, section 5.2) ; :lsp:testing est le
// point de contact SANCTIONNÉ moteur ↔ index.
//
// Une JVM SÉPARÉE par (jeu × mode) — §11.1 : -Xmx2g -XX:+UseSerialGC,
// mesures comparables d'une étape à l'autre et tas/poignées purs (pas
// de contamination croisée AVANT/APRÈS). ProcessBuilder (précédent
// §11.1 : forker depuis l'action plutôt que JavaExec multiples).
// Jamais dans la chaîne de vérification.
//
// Usage : ./gradlew :lsp:testing:bench
//   -PbancJeux=petit,lourd    (défaut : petit,moyen,lourd — présents)
//   -PbancModes=apres         (défaut : avant,apres)
//   -PbancIterations=10       (défaut : 30 — protocole §11.1)
//   -PbancDelaiIndexMs=600000 (délai de construction de l'index disque)
// Résultats : build/lsp-banc/resultats/banc-<jeu>-<mode>.json
// + banc-complet.log. Jeux : scripts/lsp-index/construire-jeux.sh.
abstract class TacheBancMoteur : DefaultTask() {

    @get:InputFiles
    abstract val bancClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val jeuxDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resultatsDir: DirectoryProperty

    @get:Input
    abstract val jeuxDemandes: ListProperty<String>

    @get:Input
    abstract val modes: ListProperty<String>

    @get:Input
    abstract val iterations: Property<Int>

    @get:Input
    abstract val delaiIndexMs: Property<Long>

    @get:Internal
    abstract val javaExe: Property<String>

    @TaskAction
    fun executer() {
        val dirJeux = jeuxDir.get().asFile
        val jeux = listOf("petit", "moyen", "lourd")
            .filter { jeu ->
                File(dirJeux, "$jeu/manifeste.txt").isFile
            }
            .filter { it in jeuxDemandes.get() }
        if (jeux.isEmpty()) {
            throw GradleException(
                "aucun jeu trouvé dans $dirJeux (demandés : " +
                    "${jeuxDemandes.get()}) — exécuter d'abord " +
                    "scripts/lsp-index/construire-jeux.sh (§15.0.4 : " +
                    "exige le SDK Android et un cache Gradle peuplé)"
            )
        }
        val classpath = bancClasspath.files.joinToString(":") { it.absolutePath }
        val resultats = resultatsDir.get().asFile.apply { mkdirs() }
        val travailRacine = resultats.parentFile.apply { mkdirs() }
        val journalComplet = File(resultats, "banc-complet.log")

        journalComplet.bufferedWriter().use { journal ->
            for (jeu in jeux) {
                for (mode in modes.get()) {
                    val travail = File(travailRacine, "travail-$jeu-$mode")
                        .apply { mkdirs() }
                    val sortie = File(resultats, "banc-$jeu-$mode.json")
                    val manifeste = File(dirJeux, "$jeu/manifeste.txt")
                    val commande = listOf(
                        javaExe.get(),
                        "-Xmx2g", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8",
                        "-cp", classpath,
                        "jo.lspjava.ecj.BancMoteur",
                        mode,
                        manifeste.absolutePath,
                        sortie.absolutePath,
                        travail.absolutePath,
                        iterations.get().toString(),
                        delaiIndexMs.get().toString()
                    )
                    logger.lifecycle("[banc] $jeu / $mode — JVM séparée (-Xmx2g, SerialGC)…")
                    val process = ProcessBuilder(commande)
                        .redirectErrorStream(true)
                        .start()
                    // Lecture du flux dans un fil dédié + attente bornée :
                    // un banc bloqué (jamais observé — verrous moteur) ne
                    // gèle pas le build, il est TUÉ après le délai.
                    val sortieProcess = StringBuilder()
                    val lecteur = Thread {
                        process.inputStream.bufferedReader().useLines { lignes ->
                            for (ligne in lignes) {
                                synchronized(sortieProcess) {
                                    sortieProcess.append(ligne).append('\n')
                                }
                            }
                        }
                    }
                    lecteur.isDaemon = true
                    lecteur.start()
                    val termine = process.waitFor(20, TimeUnit.MINUTES)
                    if (!termine) {
                        process.destroyForcibly()
                        throw GradleException(
                            "banc $jeu/$mode : TIMEOUT 20 min — " +
                                "journal : $journalComplet"
                        )
                    }
                    lecteur.join(5_000)
                    val texte = synchronized(sortieProcess) { sortieProcess.toString() }
                    journal.write(texte)
                    texte.lineSequence()
                        .filter { it.startsWith("[BANC]") }
                        .forEach { logger.lifecycle(it) }
                    if (process.exitValue() != 0) {
                        throw GradleException(
                            "banc $jeu/$mode échoué (exit ${process.exitValue()}) " +
                                "— journal : $journalComplet"
                        )
                    }
                }
            }
        }
        logger.lifecycle(
            "[banc] terminé — résultats dans $resultats (banc-complet.log)"
        )
    }
}

tasks.register("bench", TacheBancMoteur::class) {
    group = "verification"
    description = "Banc de mesure du moteur (§15.8) — AVANT/APRÈS index disque, jeux §12."
    bancClasspath.from(sourceSets.test.get().runtimeClasspath)
    jeuxDir.set(rootProject.layout.projectDirectory.dir("build/lsp-index/jeux"))
    resultatsDir.set(layout.buildDirectory.dir("lsp-banc/resultats"))
    jeuxDemandes.set(
        providers.gradleProperty("bancJeux")
            .map { s -> s.split(',').map(String::trim).filter(String::isNotEmpty) }
            .orElse(listOf("petit", "moyen", "lourd"))
    )
    modes.set(
        providers.gradleProperty("bancModes")
            .map { s -> s.split(',').map(String::trim).filter(String::isNotEmpty) }
            .orElse(listOf("avant", "apres"))
    )
    iterations.set(
        providers.gradleProperty("bancIterations")
            .map { it.toInt() }
            .orElse(30)
    )
    delaiIndexMs.set(
        providers.gradleProperty("bancDelaiIndexMs")
            .map { it.toLong() }
            .orElse(600_000L)
    )
    javaExe.set(
        providers.systemProperty("java.home").map { "$it/bin/java" }
    )
}


// Publication GitHub Packages (miroir codeide-tooling, mise en place du
// 2026-10-17 — PLAN_SEPARATION_DEPOTS.md §9) : composant java,
// coordonnées com.github.jjoblab.codeide-lsp:<module> — les MÊMES coordonnées que la
// consommation composite de CodeIDE (substitution, version déclarée
// ignorée en dev ; l'épinglage = version du catalogue + tag du dépôt).
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jjoblab/codeide-lsp")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
