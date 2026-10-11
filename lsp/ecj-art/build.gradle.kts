// :lsp:ecj-art — patcher build-time d'ecj pour ART (mission « LSP Java »,
// prompt 2 — ADR 0108).
//
// MODULE VENDORED EN JAVA : exempté de la règle « Kotlin 100 % » par
// l'ADR 0108 (exemption ciblée et commentée, règle 8 d'AGENTS.md) ;
// detekt et spotless ne s'appliquent pas à ses sources.
//
// Ce module N'EST PAS dans l'APK : il fournit la classe exécutée par la
// tâche :lsp:java:patchEclipseForArt (ProcessBuilder) qui réécrit
// org.eclipse.jdt:org.eclipse.jdt.core en un jar exécutable sur le
// runtime Android (ART, minSdk 26) :
//
//   1. java.lang.Runtime$Version — absent d'ART (ecj l'utilise dans
//      org.eclipse.jdt.internal.compiler.parser.Parser pour comparer la
//      version -source demandée) → renommé vers jo/lspjava/jdt/compat/
//      RuntimeVersion (shim livré dans :lsp:java).
//   2. InputStream.readAllBytes()/readNBytes() — Java 9, absents d'Android
//      avant l'API 33 ; ecj les atteint dès Parser.<clinit> (chargement des
//      tables du parseur) → les call-sites INVOKEVIRTUAL sont réécrits en
//      INVOKESTATIC jo/lspjava/jdt/compat/InputStreamCompat.
//   3. java.lang.StackWalker → renommé vers le shim (core.runtime).
//   4. Métadonnées OSGi/signatures/MR-jar retirées.
//
// Piste retenue par l'ADR 0112 pour l'intégration continue : une tâche
// future fera échouer le build si un jar patché référence une API absente
// du minSdk 26 (détection des LinkageError d'ART en CI plutôt que sur
// appareil) — livrée avec le traitement D3 (étape 3).

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
dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "640m"
}
