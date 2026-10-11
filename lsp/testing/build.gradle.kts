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
    // Moteur vendored — `api(fileTree(eclipse-art))` PROPAGE les jars
    // Eclipse patchés (le fileTree porte builtBy(patchEclipseForArt) :
    // la résolution du classpath de test exécute le patcher d'abord).
    testImplementation(project(":lsp:java"))

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
