// codeide-lsp — paramètres du build.
// Dépôt extrait de jjoblab/CodeIDE le 2026-10-17 (git filter-repo,
// historique conservé — 339 commits). Il porte les 8 modules du domaine
// lsp : model, index, java (moteur vendored), ecj-art (patcher ASM
// build-time), testing, host, kotlin (grammaire vendorée), xml.
// Versionnement indépendant : tags v1.0.0+ propres à ce dépôt.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Jeton de lecture GitHub Packages (même recette que CodeIDE — les
// packages exigent une authentification MÊME en lecture publique).
// En DEV sans jeton : mavenLocal (publishToMavenLocal de code-lsp pose
// jo/codeide/lsp/java) — le chemin de test local.
val jetonPackages: String? =
    providers.gradleProperty("github.packages.pat")
        .orElse(providers.environmentVariable("CODEIDE_PACKAGES_PAT"))
        .orNull

// Interrupteur du mode composite (opt-in, même convention que CodeIDE) :
// substituer le moteur Java RÉÉCRIT par le PROJET du checkout frère
// code-lsp pour une session de chirurgie croisée multi-dépôts — sans
// lui, la coordonnée résout en binaire (mavenLocal d'abord, Packages
// ensuite).
val modeSourcesMoteur: Boolean =
    providers.gradleProperty("freres.sources").isPresent ||
        providers.environmentVariable("CODEIDE_FRERES_SOURCES").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Moteur Java RÉÉCRIT (bascule §8.5 / INTEGRATION-CODEIDE §3.2.2) :
        // chemin binaire LOCAL EN TÊTE (avant google/mavenCentral — sinon
        // Central est interrogé d'abord pour ce groupe) — le
        // publishToMavenLocal de code-lsp y fait ombre au registre
        // distant et est consommé immédiatement, sans jeton (DEV).
        // FILTRE STRICT : seul le groupe du moteur neuf y résout.
        mavenLocal {
            content {
                includeGroup("jo.codeide.lsp.java")
            }
        }
        if (jetonPackages != null) {
            // Moteur Java RÉÉCRIT : dépôt jjoblab/code-lsp, même jeton de
            // lecture, filtre strict sur le groupe du moteur.
            maven {
                url = uri("https://maven.pkg.github.com/jjoblab/code-lsp")
                content { includeGroup("jo.codeide.lsp.java") }
                credentials {
                    username = "jjoblab"
                    password = jetonPackages
                }
            }
        }
        google()
        mavenCentral()
        // cel-lsp (l'AAR LSP4J du dépôt code-editor, consommé par
        // :lsp:host) : JitPack pour un build autonome sans jeton — même
        // mécanique que CodeIDE (settings.gradle.kts du dépôt principal).
        maven { url = uri("https://jitpack.io") }
        // LSP Kotlin (K1, ADR 0115) : org.jetbrains:syntax-api — l'arbre de
        // construction syntaxique contre lequel la grammaire vendorée du
        // compilateur Kotlin est écrite. Pas sur Central (artefact de la
        // plateforme IntelliJ), publié sur intellij-dependencies ; accès
        // par le cache-redirector public de JetBrains (plus stable que
        // packages.jetbrains.team, même contenu). Filtre strict : rien
        // d'autre ne vient de là.
        maven {
            url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies")
            content {
                // Le filtre doit admettre les coordonnées RACINES (métadonnées
                // KMP) ET leurs variantes par cible (-jvm…) — un filtre ne
                // nommant que la racine résout les métadonnées puis ne
                // trouve aucun jar. lang-syntax = arbre syntaxique KMP
                // (SyntaxNode/LeafSyntaxNode) dont syntax-api dépend.
                includeModuleByRegex("org\\.jetbrains", "syntax-api.*")
                includeModuleByRegex("org\\.jetbrains", "lang-syntax.*")
            }
        }
    }
}

// Convention plugins de build-logic (build inclus).
includeBuild("build-logic")

// Tooling Gradle : :lsp:model dépend de
// com.github.jjoblab.codeide-tooling:protocol (extraction 2026-10-17 —
// le protocole vit désormais dans le dépôt jjoblab/codeide-tooling).
// En DEV, le dépôt frère est intégré en SOURCE par build composite —
// substitution des coordonnées, miroir exact du mécanisme LayoutLib de
// CodeIDE : le dossier est résolu dans cet ordre : propriété Gradle
// `tooling.dir`, variable d'environnement CODEIDE_TOOLING_DIR, puis
// défaut `../codeide-tooling` (checkout frère — convention de la CI et
// de la sandbox). La SUBSTITUTION remplace les coordonnées par les
// projets du build inclus : l'épinglage de VERSION se fait par le ref
// git check out (la substitution ignore la version déclarée).
val dossierTooling: File =
    providers.gradleProperty("tooling.dir")
        .orElse(providers.environmentVariable("CODEIDE_TOOLING_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-tooling"))
        .get()
if (!dossierTooling.resolve("settings.gradle.kts").isFile) {
    throw GradleException(
        "codeide-tooling introuvable : $dossierTooling\n" +
            "Cloner le dépôt frère (git clone https://github.com/jjoblab/codeide-tooling.git ../codeide-tooling) " +
            "ou pointer la propriété Gradle tooling.dir.",
    )
}
includeBuild(dossierTooling) {
    dependencySubstitution {
        substitute(module("com.github.jjoblab.codeide-tooling:protocol")).using(project(":tooling:protocol"))
        substitute(module("com.github.jjoblab.codeide-tooling:api")).using(project(":tooling:api"))
        substitute(module("com.github.jjoblab.codeide-tooling:testing")).using(project(":tooling:testing"))
    }
}

// Moteur Java RÉÉCRIT (bascule §8.5 / INTEGRATION-CODEIDE §3.2.2) —
// composite OPT-IN (même convention que CodeIDE) : le dossier est résolu
// dans cet ordre : propriété Gradle `moteur-java.dir`, variable
// d'environnement CODEIDE_MOTEUR_JAVA_DIR, puis défaut `../code-lsp`
// (checkout frère). La SUBSTITUTION remplace la coordonnée moteur par
// le projet :java du build inclus (l'épinglage par le ref git check out ;
// le compagnon ecj-art reste résolu en binaire — mavenLocal/Packages).
if (modeSourcesMoteur) {
    val dossierMoteurJava: File =
        providers.gradleProperty("moteur-java.dir")
            .orElse(providers.environmentVariable("CODEIDE_MOTEUR_JAVA_DIR"))
            .map(rootDir::resolve)
            .orElse(rootDir.resolve("../code-lsp"))
            .get()
    if (dossierMoteurJava.resolve("settings.gradle.kts").isFile) {
        includeBuild(dossierMoteurJava) {
            dependencySubstitution {
                substitute(module("jo.codeide.lsp.java:jo-codeide-lsp-java"))
                    .using(project(":java"))
            }
        }
    }
}

rootProject.name = "codeide-lsp"

include(":lsp:model")
include(":lsp:index")
include(":lsp:java")
include(":lsp:ecj-art")
include(":lsp:testing")
include(":lsp:host")
include(":lsp:kotlin")
include(":lsp:xml")
