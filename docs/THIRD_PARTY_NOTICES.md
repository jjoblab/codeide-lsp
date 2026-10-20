# Notices tierces

Bibliothèques tierces distribuées dans les binaires de codeide-lsp
(artefacts Maven `com.github.jjoblab.codeide-lsp:*`), avec leur licence
et leur usage. Chaque entrée est vérifiée à la date d'ajout contre les
sources officielles.

Tableau resynchronisé aux 8 modules du dépôt le 2026-10-20 (extraction
du 2026-10-17) : les entrées des modules restés dans le dépôt
principal CodeIDE — `feature:editor`, `core:ui`,
`core:terminal-runtime`, `feature:terminal`, `:preview:xml` — ont été
retirées de ce fichier ; leur suivi continue dans les notices du dépôt
CodeIDE. Les références historiques aux ADR de ce dépôt (0035, 0036,
0115, 0200) restent exactes : le fichier continue de recenser les
dépendances du périmètre lsp.

| Bibliothèque | Version | Licence | Usage | Modules |
|---|---|---|---|---|
| `org.eclipse.jdt:org.eclipse.jdt.core` (graphe : ecj, core.runtime, equinox.common, osgi, contenttype, jobs, core.resources, core.filesystem, org.eclipse.text) | 3.40.0 | EPL-2.0 (Maven Central, vérifié le 2026-10-11) | Compilateur Java + DOM + formateur embarqués — jars patchés pour ART par `:lsp:ecj-art` puis classes GRAPHÉES dans le jar publié de `:lsp:java` (tâche `patchEclipseForArt`, cf. note 1) | `:lsp:java` (contenu), `:lsp:ecj-art` (patcheur) |
| `org.eclipse.lsp4j:lsp4j` | 0.21.2 | EPL-2.0 (Maven Central, vérifié le 2026-10-11) | Types et protocole LSP — façade des trois moteurs | `:lsp:host` (api), `:lsp:java` (implementation) |
| `org.ow2.asm:asm` (+ `asm-commons`) | 9.7 | BSD-3-Clause (Maven Central, vérifié le 2026-10-11) | Patchage bytecode build-time des jars ECJ (renommages/shims ART) — dépendance déclarée de l'artefact `:lsp:ecj-art`, classes NON fusionnées | `:lsp:ecj-art` |
| `com.google.code.gson:gson` | 2.10.1 | Apache-2.0 (Maven Central, vérifié le 2026-10-11) | Types de l'ancienne couche app | `:lsp:java` |
| `com.github.jjoblab.code-editor:cel-lsp` | 3.72.0 | Apache-2.0 (dépôt [`jjoblab/code-editor`](https://github.com/jjoblab/code-editor) — la licence du dépôt est passée de MIT à Apache-2.0 en 3.71.0 ; embarque une dépendance LSP sous EPL-2.0, cf. `NOTICE` amont) | Client LSP in-process (`LspProject`/`LspEditor`, transport in-process) | `:lsp:host` |
| `com.github.jjoblab.code-editor:cel-lsp-api` | 3.72.0 | Apache-2.0 (même dépôt) | Types d'API du client LSP (`LanguageServerDefinition`, `EditorHost`…) — transitive via `cel-lsp` | `:lsp:host` |
| `com.github.jjoblab.codeide-tooling:protocol` | 1.0.0 | Projet du propriétaire (dépôt [`jjoblab/codeide-tooling`](https://github.com/jjoblab/codeide-tooling) — pas de fichier LICENSE sur le dépôt à ce jour) | Protocole outillage (types d'échange avec l'orchestrateur Gradle) | `:lsp:model` |
| Grammaire Kotlin multiplateforme (vendoring `compiler/multiplatform-parsing`) | v2.3.0 (commit `f95cb2f769`) | Apache-2.0 — dépôt [`JetBrains/kotlin`](https://github.com/JetBrains/kotlin), `LICENSE.txt`/`COPYRIGHT.txt` copiés dans `lsp/kotlin/src/main/vendor/` | Parseur Kotlin du LSP in-process, octet pour octet, zéro modification locale (ADR 0115) | `:lsp:kotlin` |
| `org.jetbrains:syntax-api` (+ `lang-syntax` transitif) | 0.3.340 | Apache-2.0 — plateforme IntelliJ, dépôt intellij-dependencies (accès cache-redirector) | Arbre de construction syntaxique contre lequel la grammaire vendorée est écrite | `:lsp:kotlin` (api) |
| `org.jetbrains:annotations` | 26.0.2 | Apache-2.0 | Annotations de l'amont de la grammaire (dépendance publiée du module de parsing, cf. `VENDOR.md`) | `:lsp:kotlin` |
| `org.jetbrains.kotlin:kotlin-metadata-jvm` | 2.2.10 | Apache-2.0 | Décodage `@Metadata` des bibliothèques Kotlin du classpath (complétion, K3) | `:lsp:kotlin` |
| KotlinX — `kotlinx-coroutines-core`, `kotlinx-serialization-json` | 1.11.0 / 1.9.0 | Apache-2.0 | Concurrence structurée (index, host) ; JSON du protocole du modèle | `:lsp:model`, `:lsp:index`, `:lsp:host` |
| Reconstruction d'arbre adaptée de `NewTestParser.kt` amont | v2.3.0 | Apache-2.0 — tests du dépôt `JetBrains/kotlin` | Algorithme de reconstruction arbre ← marqueurs de production (adapté, pas copié tel quel ; crédité dans la KDoc) | `:lsp:kotlin` |

## Notes de vérification

1. **ECJ graphé dans `:lsp:java`** (vérifié le 2026-10-20 par lecture
   de `lsp/java/build.gradle.kts`) : la configuration custom
   `eclipseRaw` résout le graphe compile-scope de jdt.core ; la tâche
   `patchEclipseForArt` (processus `:lsp:ecj-art`, ASM) réécrit chaque
   jar — `Runtime$Version`/`StackWalker` renommés vers les shims
   `jo/lspjava/ecj/compat/`, call-sites
   `readAllBytes()`/`readNBytes()` réécrits en `INVOKESTATIC` shim,
   métadonnées OSGi/signatures/MR-jar retirées — puis les classes sont
   décompressées dans `build/eclipse-art-classes`, arbre `from`
   déterministe du Jar du module. Les consommateurs binaires prennent
   les types ECJ dans l'artefact lui-même (correctif v1.0.1 : le
   contenu ECJ ne voyagent JAMAIS dans un POM — NoClassDefFoundError
   `ASTVisitor` constaté le 2026-10-18 par la CI binaire de CodeIDE).
   Au sens EPL-2.0 : code MODIFIÉ (patch ART ci-dessus) et
   REDISTRIBUÉ — les sources amont restent disponibles sur
   [`eclipse-jdt/eclipse.jdt.core`](https://github.com/eclipse-jdt/eclipse.jdt.core)
   (version 3.40.0), les modifications sont décrites ci-dessus et dans
   la KDoc de `lsp/java/build.gradle.kts`.
2. **`cel-lsp` / `cel-lsp-api` 3.72.0** : artefacts JitPack du dépôt
   `code-editor` vérifiés (build « ok » le 2026-10-16) ; licence du
   dépôt MIT → Apache-2.0 en 3.71.0 (les notices deviennent exactes).
3. **Grammaire Kotlin** : le vendoring est décrit dans
   `lsp/kotlin/src/main/vendor/VENDOR.md` (tag v2.3.0, commit
   `f95cb2f7693a15966fd8fd9b1d78d440545e7b66`, Apache-2.0, extraction
   du 2026-10-13 ; resynchronisation = remplacement intégral, jamais
   fusion).
4. **Hors périmètre (scope test uniquement, jamais distribués)** :
   JUnit 4/5, Turbine, Robolectric, `org.json`, `androidx-test-core`.
   `:lsp:xml` est zéro dépendance de bibliothèque (parseur maison —
   cf. l'en-tête de son build) ; `:lsp:testing` ne publie AUCUNE
   dépendance externe (toutes ses dépendances sont en scope test).
