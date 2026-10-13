// :lsp:kotlin — moteur d'analyse Kotlin (mission « LSP Kotlin », étape K1 —
// docs/LSP_KOTLIN.md, ADR 0115).
//
// MODULE VENDORED : la grammaire officielle du compilateur Kotlin
// (compiler/multiplatform-parsing, Apache 2.0) vit dans le source-root
// `src/main/vendor` — octet pour octet, zéro modification locale
// (VENDOR.md dans ce répertoire). De ce fait, et par décision ADR 0115 :
//   - explicitApi() et allWarningsAsErrors ne s'appliquent PAS : les
//     sources amont n'ont ni API explicite ni garantie « zéro avertissement
//     de compilation » sous notre compilateur ;
//   - detekt et spotless excluent `**/vendor/**` : on ne reformate JAMAIS
//     du code vendoré (l'octet identique est la garantie de traçabilité) ;
//   - kover exclut `org.jetbrains.kotlin.kmp.*` de la vérification de
//     couverture : la grammaire est celle du compilateur, attestée par la
//     suite de parité (corpus + fixtures), pas par notre couverture de
//     lignes ; la couverture reste ≥ 80 % sur NOTRE code (facade + api),
//     comme partout.
//   Précédent : :lsp:java (ADR 0108) — mêmes exemptions, même discipline.
//
// ENTITÉ INDÉPENDANTE : aucune dépendance vers un module CodeIDE
// (checkModuleDependencies) — c'est la condition de la distribution future
// en « pack langue » téléchargeable (LSP_KOTLIN §6, décision propriétaire
// 2026-10-13 : découpage DIFFÉRÉ à post-stabilisation ; dès K1, l'app ne
// voit ce module que par le SPI mince jo.lspkotlin.api.MoteurLangageKotlin
// derrière ServiceLoader + le descripteur de pack version.properties).
//
// Dépendances : org.jetbrains:syntax-api (dépôt intellij-dependencies de
// JetBrains, version épinglée dans le catalogue — à monter ENSEMBLE avec
// la grammaire vendorée, cf. VENDOR.md) ; org.jetbrains:annotations
// (Maven Central). Rien d'autre : pas de compilateur, pas de FIR — le
// compilateur ne doit jamais être sur le chemin critique de l'édition
// (leçon projet de référence, LSP_KOTLIN §2).

import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // ADR 0115 : les sources vendorées sont compilées telles quelles —
        // les avertissements de l'amont (dépréciations internes, etc.) ne
        // nous appartiennent pas. NOTRE code reste sous detekt + spotless.
        allWarningsAsErrors.set(false)
    }
    // Source-root vendoré : séparé de src/main/kotlin pour que « ce qui
    // est à l'amont » et « ce qui est à nous » ne se devine jamais —
    // et pour qu'un re-sync soit un remplacement de répertoire.
    sourceSets {
        main {
            kotlin.srcDir("src/main/vendor")
        }
    }
}

dependencies {
    // Ce contre quoi la grammaire vendorée est écrite (API publique de
    // l'arbre de construction). api : notre façade l'expose dans ses
    // signatures (types SyntaxElementType des nœuds).
    api(libs.jetbrains.syntax.api)
    implementation(libs.jetbrains.annotations)

    // K3 (LSP_KOTLIN.md §5) : décodage des @Metadata des bibliothèques
    // Kotlin du classpath — appelables de premier niveau + extensions.
    // Externe Maven (pas un module CodeIDE) : le module reste une ENTITÉ
    // INDÉPENDANTE (checkModuleDependencies) et embarquera cette
    // dépendance dans le futur « pack langue » (§6) ; version = celle du
    // compilateur du build, la bibliothèque suit le compilateur au pas.
    implementation(libs.kotlin.metadata.jvm)

    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Le corpus du dépôt (~1 000 fichiers .kt) est parsé en mémoire
    // séquentiellement — 1 Go laisse la marge du sweep de parité sans
    // gonfler le tas des suites ordinaires.
    maxHeapSize = "1g"
    // Racine du corpus = le dépôt lui-même (sweep « dépôt » de la suite
    // de parité, cf. PariteCorpusDepotTest) ; -Dparite.* transmis
    // explicitement : un -D de la ligne Gradle atteint la JVM Gradle,
    // pas la JVM de test forkée.
    System.getProperty("parite.majBase")
        ?.let { systemProperty("parite.majBase", it) }
    System.getProperty("parite.racineCorpus")
        ?.let { systemProperty("parite.racineCorpus", it) }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
    // Le vendoré n'est pas à nous : jamais de diagnostic detekt dessus.
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
        ),
    )
}

spotless {
    // Exclusion vendoré : on ne reformate pas l'amont (octet identique).
    kotlin {
        target("src/**/*.kt")
        targetExclude("src/main/vendor/**")
    }
}

kover {
    reports {
        // La grammaire vendorée est hors couverture (attestée par la
        // parité, cf. en-tête) ; la vérification ≥ 80 % porte sur notre
        // code (façade Kt*, parse, api).
        filters {
            excludes {
                packages("org.jetbrains.kotlin.kmp")
            }
        }
        verify {
            rule("couverture-minimale-lsp-kotlin") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}

// Rapport de taille du module (LSP_KOTLIN §8 : mesure DÈS K1 — la cible
// d'embarquement est ≤ 4 Mo pour T1 ; le « pack » §6 est la soupape si la
// réalité déçoit). Écrit build/rapport-taille-kotlin.txt + journalisation
// Gradle : taille du jar, nombre de classes, répartition vendor/notre-code.
val rapportTaille =
    tasks.register("rapportTaille") {
        group = "verification"
        description = "Mesure la taille du module :lsp:kotlin (jar, classes, vendor vs nôtre)."
        dependsOn(tasks.jar)

        val fichierJar = tasks.jar.map { it.archiveFile.get().asFile }
        val sortie = layout.buildDirectory.file("rapport-taille-kotlin.txt")

        outputs.file(sortie)

        doLast {
            val jar = fichierJar.get()
            check(jar.exists()) { "jar absent : ${jar.path}" }
            var vendor = 0
            var notre = 0
            var autres = 0
            jar.inputStream().use { flux ->
                ZipInputStream(flux).use { zip ->
                    var entree = zip.nextEntry
                    while (entree != null) {
                        if (entree.name.endsWith(".class")) {
                            when {
                                // grammaire vendorée (lexers générés inclus —
                                // même paquet org.jetbrains.kotlin.kmp.lexer)
                                entree.name.startsWith("org/jetbrains/kotlin/kmp/") -> vendor += 1

                                entree.name.startsWith("jo/lspkotlin/") -> notre += 1

                                else -> autres += 1
                            }
                        }
                        entree = zip.nextEntry
                    }
                }
            }
            val lignes =
                listOf(
                    "module :lsp:kotlin — rapport de taille (K1)",
                    "jar : ${jar.name} = ${jar.length()} octets (${"%.1f".format(jar.length() / 1024.0 / 1024.0)} Mo)",
                    "classes vendorées (grammaire) : $vendor",
                    "classes CodeIDE (façade + api) : $notre",
                    "autres classes (syntax-api n'est PAS dans ce jar — module seul) : $autres",
                    "cible T1 embarqué ≤ 4 Mo (LSP_KOTLIN §7) — le dex réduit habituellement de ~30 %",
                )
            val cible = sortie.get().asFile
            cible.parentFile.mkdirs()
            cible.writeText(lignes.joinToString(System.lineSeparator()))
            lignes.forEach { logger.lifecycle("[rapportTaille] {}", it) }
        }
    }
