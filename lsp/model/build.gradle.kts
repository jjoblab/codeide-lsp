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
    id("codeide.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Types Classpath* du protocole — source de la conversion (prompt 2
    // §7.1 : « Construction pure depuis ClasspathResult »).
    api(project(":tooling:protocol"))
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
