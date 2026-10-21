# :lsp:java — moteur d'analyse Java vendored

> **Exemption « Kotlin 100 % » (règle 8 d'AGENTS.md, ADR 0108)** : ce
> module est un module JVM **vendored en Java**, exempté de la règle
> « Kotlin 100 %, aucun Java écrit à la main » par l'ADR 0108. detekt et
> spotless ne s'appliquent pas à ses sources tant qu'il n'est pas réécrit
> (plan de traitement D3 par lots, étape 3 de la mission). Les nouvelles
> couches (`lsp:model`, `lsp:index`, `lsp:host`, adaptateur `cel-lsp`)
> s'écrivent en Kotlin et vivent HORS de ce module.

Moteur LSP Java bâti sur ecj/JDT (compilateur interne Eclipse), conservé
pour ses ~42 fichiers de tests et ses correctifs de comportement ART
éprouvés sur appareil (shims `Runtime$Version`/`StackWalker`/
`readAllBytes`, `RawZipReader` contre les rafales CloseGuard, FQCN
« racine la plus profonde d'abord »). Livré par l'archive de la mission
« LSP Java » (prompt 2) et intégré au dépôt tel quel — les sources
Java ne sont PAS retouchés à l'intégration (seuls les fichiers Gradle
ont été traduits en Kotlin DSL et adaptés à Gradle 9 : `javaexec` →
`ProcessBuilder` dans `patchEclipseForArt`).

## Crédits

Certaines idées d'architecture de ce module (index de classpath,
snapshots immuables, négatifs mémorisés, planification en files
priorisées) s'inspirent des **principes d'architecture documentés de
**le projet tiers de référence** (projet tiers — étude d'architecture uniquement, désignation purgée : ADR 0200).
C'est un remerciement pour l'inspiration, **pas une licence** ; le
traitement de provenance par fichier (réécriture cloisonnée, ADR 0110)
reste requis et suit son cours à l'étape 3.

## Structure

- `jo.lspjava.api` — API publique (types de résultat purs, sans lsp4j) ;
- `jo.lspjava.engine` — environnement de compilation et ordonnanceur ;
- `jo.lspjava.ecj` — moteur ecj (le plus gros du module) ;
- `jo.lspjava.ecj.compat` — shims ART (cibles du patcher `:lsp:ecj-art`, réécrits D3 lot 1 — `ArtCompatVersion`, `ArtCompatStackWalker`, `ArtCompatInputStream`) ;
- `jo.lspjava.lsp`, `jo.lspjava.services` — façade LSP4J.

Logs : `java.util.logging` uniquement — l'hôte Android branchera un
Handler JUL vers `AppLogger`/logcat (pont livré à l'étape 4).

## Tests

Suite JUnit 5 (42 fichiers) conservée comme **suite de non-régression**
(prompt 2 §4.1) : `AndroidClasspathReproTest`, `RealClasspathDeviceReproTest`,
`DeviceFqcnReproTest`, `SubpackageDiagnosticTest`, `SubpackageGoldenRulesTest`,
`EcjEngineSchedulerTest`, `EcjCompilerEngineConcurrencyTest`, `RawZipReaderTest`,
`EcjLocateCacheTest`, `EcjParseCacheTest`, `CompletionRegressionTest`,
`BinaryDefinitionFallbackTest`, `DecompiledSourceTest`,
`JavaLanguageServerLifecycleTest`, `ImportCompletionIntegrationTest`,
`CompatShimsTest` — plus les tests d'accompagnement.

Les tests environnementaux (JDK complet avec `lib/src.zip`,
`android.jar`, appareil) se skippe proprement (`assumeTrue`/
`@EnabledIf`, `JO_TEST_JDK_HOME`).

## Jars Eclipse patchés pour ART

La tâche `patchEclipseForArt` réécrit le graphe Maven de
`org.eclipse.jdt:org.eclipse.jdt.core` (versions dans
`gradle/libs.versions.toml`) via `:lsp:ecj-art` — voir le en-tête de
`build.gradle.kts` pour le détail des renommages et purges.
