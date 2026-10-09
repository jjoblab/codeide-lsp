# ADR 0096 — Protocole tooling v7 : scripts, dépendances résolues, variantes

- Statut : accepté (2026-10-09)
- Contexte : mission Projet P1, P4, P5 (prompts `prompt-agent-dependances.md`).
- Complète : ADR 0095 (section Projet du tiroir), ADR 0039 (protocole tooling Gradle).

## Décision

Le protocole tooling passe en **v7** avec l'ajout de 6 nouveaux messages
couvrant les cas d'usage du tiroir Projet (P1, P4, P5) :

### 1. P1 — Scripts de build (lecture brute)

* `BuildScriptsRequest` (app → serveur) : `projectDir`.
* `BuildScriptsResult` (serveur → app) : `scripts: List<BuildScriptInfo>`
  avec `cheminRelatif`, `contenu`, `tailleOctets`.
* `BuildScriptInfo` (type support).

Le serveur parcourt le `projectDir` réel (chemin FUSE résolu côté app
par `ResoudreRepertoireProjet`, ADR 0038) et lit les scripts
`build.gradle.kts`, `build.gradle`, `settings.gradle.kts`,
`settings.gradle`, `gradle.properties` et le catalogue
`gradle/libs.versions.toml`. Les dossiers `build/`, `.gradle/`, `.git/`,
`node_modules/` sont exclus. Aucun appel à la Tooling API Gradle —
lecture brute, modifiable plus tard pour l'édition P3.

### 2. P4 — Dépendances résolues (arbre avec transitives)

* `ResolvedDependenciesRequest` (app → serveur) : `projectDir`,
  `module` (chemin Gradle `:app`), `configuration`
  (`debugRuntimeClasspath`).
* `ResolvedDependenciesResult` (serveur → app) : `racine: List<ResolvedDependencyNode>`.
* `ResolvedDependencyNode` (récursif) : `group`, `name`,
  `versionDemandee`, `versionRetenue`, `configuration`, `type`,
  `raison`, `transitives: List<ResolvedDependencyNode>`.
* `ResolvedDependencyKind` : `LIBRARY`, `PROJECT`, `FILE`.

Contrairement à `DependenciesResult` (v0.40, liste plate des
dépendances inter-projets), `ResolvedDependenciesResult` expose
l'arbre complet d'une configuration, avec versions demandée vs
retenue (conflits) et dépendances transitives.

**Implémentation actuelle (P4 stub)** : le handler serveur publie
une réponse vide. Le branchement au modèle `IdeaProject` +
`IdeaSingleEntryLibraryDependency` (avec récursion sur
`IdeaDependency.getChildren()` pour les transitives) et la résolution
des conflits via `dependencyInsight` sont un livrable P6+ — l'app
gère l'absence (message « aucune dépendance résolue »).

### 3. P5 — Variantes de build

* `BuildVariantsRequest` (app → serveur) : `projectDir`.
* `BuildVariantsResult` (serveur → app) : `variants: List<BuildVariantInfo>`.
* `BuildVariantInfo` : `module`, `buildType`, `productFlavors: Map<String, String>`,
  `name` (concaténation AGP, ex. `paidDebug`).

**Implémentation actuelle (P5 stub)** : le handler serveur publie
une réponse vide. Le branchement au modèle `AndroidProject` d'AGP
TAPI (~10 Mo dans le JAR serveur) est un livrable P6+ — conditionné
à l'activation d'une dépendance AGP-classes facultative.

## Compatibilité ascendante

`ignoreUnknownKeys` est déjà activé côté client (`ProtocolJson`) et
les nouveaux `@SerialName` ne cassent pas le décodage des anciens
messages : un client v6 recevant un message v7 inconnu le rejette
proprement ( ErrorResponse `UNKNOWN_REQUEST`). La montée à v7 refuse
le handshake croisé v6 ↔ v7 (message clair).

## Catalogue

Le test `ProtocoleRoundTripTest.le catalogue couvre 36 messages` passe
à **14 requêtes / 22 événements / 36 total** (was 12/20/32 en v6).

## Conséquences

- `tooling:protocol` : 6 nouveaux messages + 3 nouveaux types support.
- `tooling:server` : 3 nouveaux handlers (BuildScriptsHandler,
  ResolvedDependenciesHandler, BuildVariantsHandler) + branches `when`
  dans `MessageDispatcher`.
- `tooling:client` : branches `is ...Result` dans `GradleApiImpl.router`
  (complètent la promesse via id).
- `core:domain` : port `GradleToolingRepository.scriptsBuild` ajouté
  (P2). Les ports P4/P5 seront ajoutés quand les handlers serveur
  seront pleinement implémentés (P6+).
- `feature:editor` : `ProjetViewModel` + `ProjetFragment` consomment
  `scriptsBuild` (P2) et `ParseurDependances` (P3) pour l'onglet
  Scripts et l'onglet Dépendances déclarées.

## Références

- ADR 0095 — Section « Projet » du tiroir
- ADR 0039 — Protocole tooling Gradle
- ADR 0038 — Pont SAF → FUSE
- `docs/PROJET.md` (spec)
- `tooling/protocol/README.md`
