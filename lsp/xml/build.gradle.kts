// :lsp:xml — moteur d'analyse XML Android (mission « LSP XML », étape XE1 —
// docs/LSP_XML.md, décision X1).
//
// MODULE MAISON in-process : parseur tolérant écrit main (pile de noms
// ouverts, reprise sur buffer à moitié tapé — leçon projet de référence §2 du
// cadrage), DOM neutre, règles pures + coutures conservatrices (X4),
// complétion par contributeurs (catalogue plateforme + index ressources).
// PAS de Lemminx, PAS de XSD : la sémantique Android ne vient pas d'un
// schéma mais des données de plateforme (widgets.txt/attrs.xml de la
// plateforme du projet) et des ressources du projet.
//
// ENTITÉ INDÉPENDANTE (miroir :lsp:kotlin) : aucune dépendance vers un
// module CodeIDE (checkModuleDependencies) — le module reste découplable
// en « pack langue » téléchargeable ; l'app ne voit que le SPI mince
// jo.lspxml.api.MoteurLangageXml derrière ServiceLoader + le descripteur
// de pack jo/lspxml/pack/version.properties.
//
// Dépendances : RIEN (Kotlin JVM pur, zéro bibliothèque — le parseur est
// écrit main précisément pour ça : aucun XmlPullParser/DOM tiers sur le
// chemin critique de l'édition, comportement identique JVM/ART).

import java.util.zip.ZipInputStream

plugins {
    id("maven-publish")
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
    }
}

dependencies {
    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Corpus LayoutLib (frère du dépôt, LSP_XML §7) : les fixtures de
    // validation deviennent corpus du parseur — absent (CI sans frère)
    // : la suite S'AUTRE-SKIP honnêtement (patron PariteCorpusDepotTest).
    System.getProperty("corpus.racine")
        ?.let { systemProperty("corpus.racine", it) }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
        ),
    )
}

spotless {
    kotlin {
        target("src/**/*.kt")
    }
}

kover {
    reports {
        verify {
            rule("couverture-minimale-lsp-xml") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}

// Rapport de taille du module (miroir rapportTaille de :lsp:kotlin —
// LSP_XML §6 : le moteur vise l'embarquement maigre, mesure dès XE1).
val rapportTaille =
    tasks.register("rapportTaille") {
        group = "verification"
        description = "Mesure la taille du module :lsp:xml (jar, classes)."
        dependsOn(tasks.jar)

        val fichierJar = tasks.jar.map { it.archiveFile.get().asFile }
        val sortie = layout.buildDirectory.file("rapport-taille-xml.txt")

        outputs.file(sortie)

        doLast {
            val jar = fichierJar.get()
            check(jar.exists()) { "jar absent : ${jar.path}" }
            var classes = 0
            jar.inputStream().use { flux ->
                ZipInputStream(flux).use { zip ->
                    var entree = zip.nextEntry
                    while (entree != null) {
                        if (entree.name.endsWith(".class")) classes += 1
                        entree = zip.nextEntry
                    }
                }
            }
            val lignes =
                listOf(
                    "module :lsp:xml — rapport de taille (XE1)",
                    "jar : ${jar.name} = ${jar.length()} octets (${"%.2f".format(jar.length() / 1024.0 / 1024.0)} Mo)",
                    "classes CodeIDE : $classes",
                    "zéro dépendance de bibliothèque (parseur maison)",
                )
            val cible = sortie.get().asFile
            cible.parentFile.mkdirs()
            cible.writeText(lignes.joinToString(System.lineSeparator()))
            lignes.forEach { logger.lifecycle("[rapportTailleXml] {}", it) }
        }
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
