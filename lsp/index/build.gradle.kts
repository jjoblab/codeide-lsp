// :lsp:index — index disque en segments par jar (mission « LSP Java »,
// prompt 2 §7.2, étape 2 — ADR 0111).

// Kotlin JVM PUR (règle 1, ADR 0108) : index persistant du contenu des
// jars du classpath — UN segment PAR JAR, clé = identité du contenu
// (empreintes W6 de :lsp:model : maven:<sha1> / transform:<haché> /
// partiel:…), stockage partagé entre projets (pas dans le projet),
// fusion LSM à la requête (l'union ordonnée des segments d'un classpath
// ne reconstruit JAMAIS de gros index), format binaire versionné
// (en-tête magique + version + crc32, corruption d'un segment ⇒
// reconstruction de CE segment, jamais de crash), construction en
// arrière-plan bornée et annulable avec progression par jar, lecture
// par cache LRU de budget tas explicite, nettoyage des orphelins.

plugins {
    id("codeide.kotlin.library")
}

dependencies {
    // Entrées empreintées (ClasspathInstantane) et types du protocole —
    // les clés de segments dérivent des empreintes W6 de :lsp:model.
    api(project(":lsp:model"))

    // États/progression en flux (construction → prêt/partiel/erreur).
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

kover {
    reports {
        verify {
            rule("couverture-minimale-lsp-index") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}

// Banc de mesure (ADR 0111 : « banc avant/après sur les trois jeux ») —
// source set exécuté par la tâche :lsp:index:bench (JavaExec, JVM
// séparée), jamais dans la chaîne de vérification. Recette de
// reconstitution des jeux : scripts/lsp-index/construire-jeux.sh.
val benchSourceSet =
    sourceSets.create("bench") {
        java.srcDir("src/bench/kotlin")
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    }

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Banc de mesure de l'index disque (ADR 0111) — jeux petit/moyen/lourd."
    mainClass.set("jo.codeide.lsp.index.banc.BancIndexKt")
    workingDir = rootProject.projectDir
    classpath = benchSourceSet.runtimeClasspath
    // La JVM du banc est bornée comme au banc d'étude (§11.1 : -Xmx2g,
    // SerialGC — mesures comparables d'une étape à l'autre).
    jvmArgs("-Xmx2g", "-XX:+UseSerialGC")
}
