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
//   - java.lang.Runtime$Version   → renommé vers le shim jo/lspjava/ecj/compat/ArtCompatVersion
//   - java.lang.StackWalker       → renommé vers le shim jo/lspjava/ecj/compat/ArtCompatStackWalker
//   - InputStream.readAllBytes/NBytes → call-sites réécrits vers ArtCompatInputStream (API 33+)
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
    id("maven-publish")
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

// Le patcheur est déclaré AVANT les dépendances : le fileTree api des
// jars patchés (ci-dessous) porte builtBy(patchEclipseForArt) —
// l'ordonnancement voyage AVEC la dépendance de fichiers, garantissant
// aux CONSOMMATEURS (:lsp:testing, v0.96.3) que le patcher a produit
// les jars avant toute tâche qui résolvent ce classpath (compilation,
// tests). Les dependsOn explicites de fin de script restent en doublon
// de sécurité pour les tâches de CE module.

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

dependencies {
    // jdt.core = DOM + formateur Eclipse ; son graphe compile-scope apporte
    // ecj (compilateur interne), core.runtime (+ equinox.common, osgi,
    // contenttype, jobs…), core.resources, core.filesystem, org.eclipse.text.
    "eclipseRaw"(libs.eclipse.jdt.core)
    "eclipsePatcher"(project(":lsp:ecj-art"))

    // ★ Jars Eclipse patchés pour ART — COMPILEONLY : ils alimentent la
    // compilation DU module (le moteur vendored importe l'AST ecj) sans
    // rejoindre les variantes de consommation. Leur contenu est GRAPHÉ
    // dans le jar du module (bloc Jar plus bas — v1.0.1) : les
    // dépendances de FICHIERS ne voyagent JAMAIS dans un POM Maven —
    // l'artefact 1.0.0 publié était incomplet (NoClassDefFoundError
    // org/eclipse/jdt/internal/compiler/ASTVisitor chez les
    // consommateurs binaires, constaté le 2026-10-18 par la CI binaire
    // de CodeIDE). Précédent : le shadowJar du tooling server. Les
    // consommateurs (composite ET binaire) prennent les types ECJ dans
    // l'artefact lui-même — zéro doublon, une seule source de classes.
    compileOnly(fileTree(eclipseArtDir) {
        include("*.jar")
        builtBy(patchEclipseForArt)
    })

    implementation(libs.lsp4j)
    implementation(libs.gson)

    // Étape 3 lot C (v0.96.3) : la suite d'intégration du branchement
    // index disque (AdaptateurIndexNoms + IndexNomsIntegrationTest) vit
    // dans :lsp:testing — la CI a rejeté le testImplementation(project(
    // ":lsp:index")) posé au lot C (checkModuleDependencies, section 5.2 :
    // le module vendored ne dépend d'AUCUN module interne, test compris
    // — ADR 0108). Le cœur de ce module reste autonome : AUCUNE
    // dépendance de projet ici, test ou production.

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Repro StringConcatFactory (v0.108.0) : fabrique en fixture un .class
    // binaire dont la SUPER-CLASSE est un intrinsèque du compilateur
    // (motif du constat appareil « indirectly referenced from required
    // .class files ») — externe Maven, test seulement (ADR 0108 : aucune
    // dépendance de PROJET interne dans le module vendored).
    testImplementation(libs.asm)
}

// Le jar patché doit exister avant de compiler/tester/packager ce module
// (le moteur importe org.eclipse.jdt.internal.compiler.* et le formateur
// org.eclipse.jdt.core.formatter.*).
tasks.withType<JavaCompile>().configureEach { dependsOn(patchEclipseForArt) }
tasks.named("test") { dependsOn(patchEclipseForArt) }

// Le jar du module INTÈGRE le contenu des jars Eclipse patchés
// (graphé de jdt.core complet : ecj, core.runtime, equinox, osgi…).
// Sans cette fusion, l'artefact publié n'embarque que les classes du
// module — les dépendances de fichiers étant invisibles au POM, les
// consommateurs binaires perdaient les classes ECJ au runtime. La
// fusion sert AUSSI le composite : une seule source de classes, zéro
// doublon avec l'ancien fileTree api.
tasks.named<Jar>("jar") {
    dependsOn(patchEclipseForArt)
    // Les packages du graphe jdt.core ne se chevauchent pas ; exclusion
    // de sécurité des entrées META-INF conflictuelles et de
    // module-info (le patcher retire déjà OSGi, signatures et MR-jar).
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        val jarsArt = fileTree(eclipseArtDir).matching { include("*-art.jar") }
        jarsArt.files.map { zipTree(it) }
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "module-info.class",
        )
    }
}

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
