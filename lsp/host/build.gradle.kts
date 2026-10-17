// :lsp:host — l'hôte LSP Java (mission « LSP Java » étape 4, §10 ;
// ADR 0109 D2 : in-process ART par défaut, transport abstrait).
//
// Point UNIQUE de changement de transport : SessionLsp isole le moteur
// (JavaLanguageServer vendored) derrière des frontières nettes —
// démarrer/arrêter, requêtes, flux d'état. Le branchement LSP4J
// in-process (cel-lsp, InProcessStreamConnectionProvider) en est UNE
// implémentation ; un transport par socket vers un sous-processus JDK
// reste possible sans réécriture du moteur.
//
// RESPONSABILITÉS (h1, v0.99.0) :
//  - OrchestrateurLsp : ClasspathResult (protocole tooling v8, ADR
//    0113) → ProjetLsp (:lsp:model) → moteurs PAR module via l'API
//    qualifiée du lot E (remplacerContexteModule) ; vue d'index FUSION
//    sur l'union des classpaths (ADR 0111 : l'union ordonnée des
//    segments, jamais de gros index reconstruit) ; BUDGET de cache
//    dérivé du modèle (leçon du banc lot F2 : la cible §9 « tas
//    < 30 Mo / 100 jars » est un budget PAR JAR).
//  - AdaptateurIndexNoms : l'adaptateur de PRODUCTION de la SPI
//    (l'homologue de test vit dans :lsp:testing, v0.96.3) — empreintes
//    du MODÈLE (maven:/transform:, pas sha256 de test), transition
//    PRÊT par collecte du StateFlow (pas de fil surveillant).
//  - SessionLsp : façade de cycle de vie + définition cel-lsp
//    (DefinitionServeurLspJava) + arrêt exhaustif (prompt 3 : test de
//    fuite — SessionLspTest).
//
// ALIGNEMENT lsp4j : :lsp:java compile contre 0.21.2, cel-lsp contre
// 0.22.0 — la résolution de CE module prend 0.22.0 (la plus haute) ;
// AlignementLsp4jTest prouve le moteur SOUS 0.22.0 (liaison binaire
// initialize/didOpen/completion). L'écart échu à l'étude §2.6 est
// ainsi tranché : aligner, pas isoler.
//
// BIBLIOTHÈQUE ANDROID (pas une feature) : consommation de l'AAR
// cel-lsp réservée à ce module ; les features ne voient que cel-ui
// (prompt compagnon section 3 — l'exception feature reste cel-ui
// seule).
plugins {
    id("maven-publish")
    id("codeide.android.library")
}

// Publication : variante unique release (AGP 9 — le composant « release »
// de maven-publish n'existe qu'une fois la variante déclarée ici). Le
// reste de la configuration Android vit dans la convention
// `codeide.android.library` ; ce bloc n'ajoute QUE le câblage de
// publication (miroir codeide-tooling, PLAN_SEPARATION_DEPOTS.md §9).
android {
    publishing {
        singleVariant("release")
    }
}

dependencies {
    // Moteur vendored + son api lsp4j 0.21.2 (SessionLsp.serveur()
    // expose un LanguageServer — le type traverse la surface publique).
    // NOTE : :lsp:java porte lsp4j en `implementation` (choix vendored)
    // — l'hôte le déclare EXPLICITEMENT ; la résolution prend 0.22.0
    // (imposée par cel-lsp, la plus haute — AlignementLsp4jTest prouve
    // le moteur binaire 0.21.2 SOUS 0.22.0 ; écart §2.6 tranché :
    // aligner, pas isoler).
    api(project(":lsp:java"))
    api(libs.lsp4j)

    // Modèle LSP (types de la surface publique : ClasspathResult par
    // :tooling:protocol en api transitive, ProjetLsp/DepotModeleLsp).
    api(project(":lsp:model"))

    // Index disque : détail interne (adaptateur SPI), pas de surface
    // publique — implementation.
    implementation(project(":lsp:index"))

    // Moteur Kotlin (K2, LSP_KOTLIN.md §4.1 « session ») : l'hôte est
    // l'ADAPTATEUR LSP4J du moteur — le SPI MoteurLangageKotlin traverse
    // la surface publique (paramètre du constructeur SessionLspKotlin,
    // injectable pour les tests), donc api. C'est le SEUL type du moteur
    // visible (D6 : jamais l'implémentation concrète, résolution
    // ServiceLoader — le pack « langue » futur se branche là).
    api(project(":lsp:kotlin"))

    // Moteur XML (XE1, LSP_XML.md §4.1 « session ») : même frontière —
    // le SPI MoteurLangageXml traverse la surface publique (paramètre
    // du constructeur SessionLspXml, injectable pour les tests), donc
    // api ; SEUL type visible, résolution ServiceLoader (miroir
    // :lsp:kotlin — le pack « langue » XML futur se branche là).
    api(project(":lsp:xml"))

    // cel-lsp : la DefinitionServeurLspJava traverse la surface publique
    // (branchement LspProject côté feature à l'étape 4 h2).
    api(libs.codeeditor.lsp)

    // Coroutines : impl du service d'index (implementation côté
    // :lsp:index, non transitive) + collecte du StateFlow d'état.
    implementation(libs.kotlinx.coroutines.core)

    // Tests : Jupiter (même suite que :lsp:java/:lsp:testing) + moteur
    // réel + index réel + coroutines.
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":lsp:index"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kover {
    reports {
        verify {
            rule("couverture-minimale-lsp-host") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Les sondes du moteur cherchent un JDK COMPLET (lib/src.zip,
    // lib/modules) : le JDK du démon Gradle l'est par construction.
    environment("JO_TEST_JDK_HOME", System.getProperty("java.home"))
}


// Publication GitHub Packages (miroir codeide-tooling, mise en place du
// 2026-10-17 — PLAN_SEPARATION_DEPOTS.md §9) : composant release,
// coordonnées com.github.jjoblab.codeide-lsp:<module> — les MÊMES coordonnées que la
// consommation composite de CodeIDE (substitution, version déclarée
// ignorée en dev ; l'épinglage = version du catalogue + tag du dépôt).


// Publication GitHub Packages (miroir codeide-tooling, mise en place du
// 2026-10-17 — PLAN_SEPARATION_DEPOTS.md §9) : composant RELEASE (AAR),
// coordonnées com.github.jjoblab.codeide-lsp:<module> — les MÊMES coordonnées que la
// consommation composite de CodeIDE (substitution, version déclarée
// ignorée en dev ; l'épinglage = version du catalogue + tag du dépôt).
// AGP 9 : le composant « release » n'est créé qu'en afterEvaluate — la
// publication est donc déclarée DANS afterEvaluate (accès direct =
// « SoftwareComponent with name 'release' not found »).
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
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
}
