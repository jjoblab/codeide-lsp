# :lsp:testing — suite d'intégration moteur ↔ index disque

> **Exemption « Kotlin 100 % » (règle 8 d'AGENTS.md, ADR 0108)** : ce
> module est **Java** comme le moteur qu'il éprouve — les tests
> rejoignent le paquet `jo.lspjava.ecj` (split package de TEST sur le
> classpath, aucun module-info) pour les membres package-private du
> moteur (`typeIndex()`, accesseurs `debug*()`). detekt et spotless ne
> s'appliquent pas à ses sources.

Suite d'intégration du **branchement de l'index disque** (mission
« LSP Java », étape 3 lot C — ADR 0111 §8 ; docs/LSP_JAVA.md §15.4) :
le moteur vendored `:lsp:java` consomme l'index Kotlin `:lsp:index`
à travers la SPI publique `jo.lspjava.api.IndexNoms`.

**Pourquoi un module dédié** (v0.96.3) : le lot C avait posé
`testImplementation(project(":lsp:index"))` dans `:lsp:java` — la CI
l'a rejeté (`checkModuleDependencies`, section 5.2 : le module
vendored ne dépend d'AUCUN module interne, test compris —
extractibilité bibliothèque indépendante, ADR 0108/0110). La suite
vit désormais AU-DESSUS des deux modules qu'elle éprouve, sur le
modèle de `:tooling:testing` (dépendance de test uniquement) — même
schéma que le futur hôte `:lsp:host` (étape 4), adaptateur de
production compris.

## Contenu

- `AdaptateurIndexNoms` — adaptateur de TEST de la SPI `IndexNoms`
  sur l'index réel (`ServiceIndexDisque` + `VueIndex`, surveillance
  de la transition CONSTRUCTION → PRÊT par fil, clé de segment
  sha256 du contenu du jar) ;
- `IndexNomsIntegrationTest` — 4 tests sur jar compilé au vol
  (javax.tools, zéro dépendance à android.jar ou au cache Gradle —
  la suite tourne partout, CI incluse via koverVerify) : parité
  avec/sans index, autorité du localisateur à PRÊT, réouverture
  chaude sans index mémoire, purge du `EcjLocateCache` à la
  transition (P7/P8).

## Dépendances autorisées (section 5.2)

`lsp:java`, `lsp:index`, `lsp:model` — rien d'autre. Vérifié par
`checkModuleDependencies` (plugin `codeide.module-rules`).
