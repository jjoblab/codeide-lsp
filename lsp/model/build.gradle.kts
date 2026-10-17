// :lsp:model — modèle de projet LSP (mission « LSP Java », prompt 2
// §7.1, étape 1).
//
// Kotlin JVM PUR (règle 1) : conversion pure et testable du
// ClasspathResult du protocole vers des snapshots IMMUABLES —
// ProjetLsp → ModuleLsp → ensembles de sources par rôle (main, test,
// générés) et ClasspathInstantane (entrées ordonnées, dédupliquées,
// empreintes par contenu — W6). Résolution « plus récent gagne » des
// doublons, dépendances inter-modules avec drapeau exporté, cycles
// REJETÉS avec avertissement, association fichier → module par la
// racine de sourceset la plus profonde, persistance versionnée
// VALIDÉE (taille/horodatage des jars — corrige W7).
//
// Réutilisable par les LSP Kotlin/XML futurs (hors périmètre prompt 2
// mais exigé par son §2) : aucune dépendance Android, aucune dépendance
// vers le moteur :lsp:java — seulement le protocole du tooling.

plugins {
    id("maven-publish")
    id("codeide.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Types Classpath* du protocole — source de la conversion (prompt 2
    // §7.1 : « Construction pure depuis ClasspathResult »). Le protocole
    // vit dans le dépôt jjoblab/codeide-tooling (extraction 2026-10-17) :
    // coordonnées externes convention JitPack multi-modules ; en DEV, la
    // substitution composite de settings.gradle.kts (tooling.dir) remplace
    // ces coordonnées par le projet source :tooling:protocol.
    api("com.github.jjoblab.codeide-tooling:protocol:1.0.0")
    // Sérialisation de la persistance versionnée (lsp-modele.json).
    api(libs.kotlinx.serialization.json)

    // Flux d'état (publication par instantanés successifs, §7.1).
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

kover {
    reports {
        verify {
            rule("couverture-minimale-lsp-model") {
                bound {
                    minValue.set(80)
                }
            }
        }
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
