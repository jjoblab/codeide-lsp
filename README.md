# codeide-lsp

Moteurs LSP de [CodeIDE](https://github.com/jjoblab/CodeIDE) **extraits
le 2026-10-17** (`git filter-repo`, historique conservé — 339 commits).

## Modules

| Module | Type | Rôle |
| --- | --- | --- |
| `:lsp:model` | JVM pur | Modèle de projet LSP — snapshots immuables depuis `ClasspathResult` du protocole tooling |
| `:lsp:index` | JVM pur | Index disque en segments par jar (clés de contenu, fusion LSM à la requête, ADR 0111) |
| `:lsp:java` | Android (AAR) | Moteur Java vendored — ecj patché pour ART (ADR 0108), lsp4j 0.21.2 |
| `:lsp:ecj-art` | JVM (build-time) | Patcher ASM de l'ecj — jamais dans l'APK |
| `:lsp:testing` | JVM | Suite d'intégration moteur ↔ index (au-dessus des deux, ADR 0108) |
| `:lsp:host` | Android (AAR) | L'HÔTE — SessionLsp, orchestrateur sync → modèle → index → moteurs (ADR 0109) |
| `:lsp:kotlin` | JVM pur | Moteur Kotlin — parseur sur la grammaire officielle du compilateur VENDORÉE (ADR 0115, étapes K1–K3) ; l'Analysis API K2 est écartée (D1) et la compilation K2 différée au tooling (D8) ; suite de parité |
| `:lsp:xml` | JVM pur | Parseur XML Android tolérant écrit main — zéro dépendance (décision X1) |

`kotlin` et `xml` sont des **entités indépendantes** (aucune dépendance
vers un module interne) — pensés comme packs langue téléchargeables (D6,
SPI ServiceLoader). `host` ne dépend d'aucun `core:*`.

## Capacités par langage (1.3.0)

L'état de référence est la **matrice des capacités**
([docs/LSP_MATRICE_CAPACITES.md](docs/LSP_MATRICE_CAPACITES.md)) — chaque
case cite sa preuve (annonce verrouillée par le test de parité, tests
moteur, aller-retour LSP4J réel ; définition « en service » : ADR 0201).
Résumé :

- **Java** : complétion, survol, définition, définition de type,
  implémentations, super-définition (expérimental), source décompilée
  (expérimental), actions de code, symboles de document et d'espace,
  surlignage, références, renommage + prepareRename, formatage (document
  et plage), formatage à la frappe, lentilles de code, hiérarchies
  d'appels et de types, aide à la signature, indices en ligne, pliage,
  jetons sémantiques, plage de sélection.
- **Kotlin** (1.3.0) : complétion, survol, définition, actions de code,
  symboles de document, pliage, aide à la signature, références,
  surlignage, renommage + prepareRename, **formatage borné (ADR 0202) et
  formatage de plage**, **définition de type**, **implémentations**,
  **indices en ligne**, **symboles d'espace**, **plage de sélection** —
  jetons sémantiques volontairement non annoncés (D3).
- **XML** (1.3.0) : complétion, survol, définition, actions de code,
  symboles de document, pliage, renommage + prepareRename, formatage,
  **références et surlignage d'occurrences des ressources**, **formatage
  de plage**, **édition liée**, **plage de sélection**, **couleurs de
  document**.
- Les capacités servies mais non consommées par le client actuel sont
  consignées dans [docs/LSP_ECARTS_CLIENT.md](docs/LSP_ECARTS_CLIENT.md).

Chiffres (21/10/2026) : `:lsp:kotlin` 218 tests, `:lsp:xml` 245,
`:lsp:java` 400, `:lsp:host` 141 — tous verts ; CI verte sur `main`.

## Dépendance externe unique

`:lsp:model` dépend de `com.github.jjoblab.codeide-tooling:protocol`
(extrait de CodeIDE le même jour). En **DEV**, le dépôt frère est
intégré en SOURCE par build composite — miroir du mécanisme LayoutLib :

```bash
git clone https://github.com/jjoblab/codeide-tooling.git ../codeide-tooling
# (ou propriété Gradle tooling.dir / variable CODEIDE_TOOLING_DIR)
```

La CI clonne le frère hors de l'espace de travail ; la substitution
ignore la version déclarée (l'épinglage se fait par le ref check out).

## Build

JDK 21 requis ; les modules Android (`java`, `host`) demandent le SDK
(`local.properties` → sdk.dir ou environnement CI).

```bash
./gradlew spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify
```

## ADR et numérotation

`docs/adr/` héberge **0001–0120** : l'héritage **extrait de CodeIDE**
(les mêmes décisions, purgées des désignations tiers — ADR 0200) ; ces
numéros restent alignés sur ceux de CodeIDE et sont **gelés** ici — jamais
réutilisés pour une décision propre à ce dépôt.

Les décisions **propres à `codeide-lsp`** sont numérotées **0200–0299**
(plage réservée, directive du 2026-10-20) : CodeIDE garde 0001–0199,
`codeide-tooling` prendrait 0300–0399 si des ADR y apparaissent un jour.
La première d'entre elles : **ADR 0200 — rectification de provenance**
(anciennement « 0121 », renumérotée le 2026-10-20 pour lever la collision
avec l'ADR 0121 de CodeIDE, `ia-perimetre-reseau-internet-byok`).

## Versionnement

Indépendant de CodeIDE : tags `v1.0.0`+ ; **publiée sur GitHub Packages
depuis la v1.0.0** (`com.github.jjoblab.codeide-lsp:*`, workflow `v*`)
et consommée par CodeIDE via son catalogue de versions (`1.0.2` à la
date du 2026-10-20).
