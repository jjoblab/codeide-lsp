// :lsp:java — moteur d'analyse Java vendored (mission « LSP Java »,
// prompt 2 §3 D1 — ADR 0108).
//
// MODULE VENDORED EN JAVA : exempté de la règle « Kotlin 100 % » par
// l'ADR 0108 (exemption ciblée et commentée, règle 8 d'AGENTS.md) :
// detekt et spotless ne s'appliquent PAS aux sources de ce module tant
// qu'il n'est pas réécrit (traitement D3 par lots, étape 3). Toute
// NOUVELLE couche (lsp:model, lsp:index, lsp:host, adaptateur cel-lsp)
// s'écrit en Kotlin et vit HORS de ce module.
//
// ENTITÉ INDÉPENDANTE : aucune dépendance vers CodeIDE ni Android.
// L'API publique est `jo.lspjava.api.*` (consommable par n'importe quel
// projet Java). Logs : java.util.logging uniquement (l'hôte branchera un
// Handler JUL vers AppLogger/logcat — pont livré à l'étape 4).
//
// Eclipse JDT patché pour ART : le moteur exécute le compilateur interne
// ecj et le formateur Eclipse DANS le process de l'app (ADR 0109,
// in-process). Les jars Eclipse sont du pur Java mais référencent des API
// absentes du runtime Android (minSdk 26) :
//   - java.lang.Runtime$Version   → renommé vers le shim jo/lspjava/jdt/compat/RuntimeVersion
//   - java.lang.StackWalker       → renommé vers le shim jo/lspjava/jdt/compat/StackWalker
//   - InputStream.readAllBytes/NBytes → call-sites réécrits vers InputStreamCompat (API 33+)
// La tâche patchEclipseForArt exécute :lsp:ecj-art (ProcessBuilder) sur
// CHAQUE jar du graphe Maven de org.eclipse.jdt:org.eclipse.jdt.core et
// produit build/eclipse-art/<nom>-art.jar. Les renommages sont inoffensifs
// quand la référence est absente — un même jar patché sert desktop et
// appareil. Les métadonnées OSGi (plugin.xml, OSGI-INF, signatures,
// MR-jar) sont retirées pour éviter les collisions au merge APK.
//
// Gradle 9 : Project.javaexec n'est plus utilisable dans doLast — le
// patcher est lancé par ProcessBuilder (équivalent strict du javaexec
// d'origine : même classpath, même main, mêmes arguments).

plugins {
    `java-library`
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

configurations {
    // Graphe Maven brut de jdt.core (ecj, core.runtime, equinox, osgi…).
    create("eclipseRaw") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    // Le patcheur lui-même (module build-time :lsp:ecj-art).
    create("eclipsePatcher") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
}

val eclipseArtDir = layout.buildDirectory.dir("eclipse-art")

dependencies {
    // jdt.core = DOM + formateur Eclipse ; son graphe compile-scope apporte
    // ecj (compilateur interne), core.runtime (+ equinox.common, osgi,
    // contenttype, jobs…), core.resources, core.filesystem, org.eclipse.text.
    "eclipseRaw"(libs.eclipse.jdt.core)
    "eclipsePatcher"(project(":lsp:ecj-art"))

    // ★ Jars Eclipse patchés pour ART — compile + runtime. Propagent aux
    // consommateurs (runtimeElements) qui les dexent dans l'APK. Le fileTree
    // est évalué paresseusement ; les tâches de compilation/test dépendent
    // de patchEclipseForArt (cf. ci-dessous).
    // ★ api (pas implementation) : les consommateurs utilisent AUSSI l'AST
    // ecj directement (SymbolIndexer) — les jars Eclipse doivent être sur
    // leur classpath de compilation.
    api(fileTree(eclipseArtDir) { include("*.jar") })

    implementation(libs.lsp4j)
    implementation(libs.gson)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Réécrit chaque jar Eclipse du graphe jdt.core pour le runtime Android.
 *
 * Tâche CC-compatible (configuration cache du dépôt actif) : toutes les
 * entrées sont des propriétés sérialisables captées à la configuration ;
 * le patcher :lsp:ecj-art est lancé par ProcessBuilder (Gradle 9 a retiré
 * Project.javaexec dans doLast) — même classpath, même main, mêmes
 * arguments que le javaexec d'origine.
 */
abstract class PatchEclipseForArtTask : DefaultTask() {
    /** Graphe Maven brut de jdt.core (ecj, core.runtime, équinox, osgi…). */
    @get:InputFiles
    abstract val jarsEclipse: ConfigurableFileCollection

    /** Programme du patcher (module build-time :lsp:ecj-art). */
    @get:InputFiles
    abstract val classpathPatcher: ConfigurableFileCollection

    /** Répertoire de sortie (build/eclipse-art). */
    @get:OutputDirectory
    abstract val dossierSortie: DirectoryProperty

    @TaskAction
    fun patcher() {
        val outDir = dossierSortie.get().asFile
        // Nettoie les sorties périmées (jars retirés du graphe).
        outDir.listFiles()?.forEach { it.delete() }
        outDir.mkdirs()
        val jars =
            jarsEclipse.files
                .filter { it.name.endsWith(".jar") }
                .sortedBy { it.name }
        logger.lifecycle("[eclipse-art] ${jars.size} jars à patcher : ${jars.joinToString(", ") { it.name }}")
        val javaBin = File(System.getProperty("java.home"), "bin/java")
        val cheminPatcher = classpathPatcher.files.joinToString(File.pathSeparator) { it.absolutePath }
        for (jar in jars) {
            val sortie = File(outDir, jar.name.replaceFirst(Regex("\\.jar$"), "-art.jar"))
            val processeur =
                ProcessBuilder(
                    javaBin.absolutePath,
                    "-cp",
                    cheminPatcher,
                    "jo.lspjava.jdtpatch.EcjArtPatcher",
                    jar.absolutePath,
                    sortie.absolutePath,
                ).redirectErrorStream(true).start()
            processeur
                .inputStream
                .bufferedReader()
                .use { lecteur -> lecteur.forEachLine { logger.lifecycle("[eclipse-art] $it") } }
            val code = processeur.waitFor()
            if (code != 0) {
                throw GradleException("patcher ecj-art échoué ($code) sur ${jar.name}")
            }
        }
    }
}

val patchEclipseForArt =
    tasks.register<PatchEclipseForArtTask>("patchEclipseForArt") {
        group = "build"
        description =
            "Réécrit chaque jar Eclipse du graphe jdt.core pour le runtime Android (Runtime\$Version, StackWalker, InputStream.readAllBytes → shims)."
        jarsEclipse.from(configurations["eclipseRaw"])
        // Le patcher lui-même est un input — le changer doit re-patcher les jars.
        classpathPatcher.from(configurations["eclipsePatcher"])
        dossierSortie.set(eclipseArtDir)
    }

// Le jar patché doit exister avant de compiler/tester/packager ce module
// (le moteur importe org.eclipse.jdt.internal.compiler.* et le formateur
// org.eclipse.jdt.core.formatter.*).
tasks.withType<JavaCompile>().configureEach { dependsOn(patchEclipseForArt) }
tasks.named("jar") { dependsOn(patchEclipseForArt) }
tasks.named("test") { dependsOn(patchEclipseForArt) }

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "640m"
    // Les tests environnementaux cherchent un JDK COMPLET (lib/src.zip,
    // lib/modules) : la variable JO_TEST_JDK_HOME du module vendored est
    // fournie par le build — le JDK du démon Gradle est par construction
    // un JDK complet (Temurin 21 en local et en CI), ce que les sondes
    // intégrées (chemins java-17/java-21 du système, souvent des JRE sans
    // src.zip) ne garantissent pas.
    environment("JO_TEST_JDK_HOME", System.getProperty("java.home"))
    // Le reste des tests env-dépendants (android.jar, device) se skippe
    // proprement via assumeTrue/@EnabledIf.
}
