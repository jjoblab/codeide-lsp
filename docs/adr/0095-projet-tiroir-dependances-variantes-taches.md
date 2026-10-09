# ADR 0095 — Section « Projet » du tiroir : dépendances, variantes, tâches

- Statut : accepté (2026-10-09)
- Contexte : mission Projet P0 (prompt `prompt-agent-dependances.md`).

## Décision

Une **cinquième destination** du tiroir (« Projet », entre Recherche et Git)
regroupe les fonctions liées à la structure du projet Gradle :
dépendances déclarées et résolues, variantes de build, tâches Gradle.

### 1. Protocole tooling — incrément v7

`DependenciesResult` actuel est trop pauvre (liste plate sans version ni
arbre). Le protocole passe en **v7** avec un nouveau
`ResolvedDependenciesRequest`/`Result` exposant un arbre par
module×configuration (groupe, nom, version demandée, version retenue,
raison, type). Compat ascendante préservée (`ignoreUnknownKeys` +
nouveaux champs avec défauts).

### 2. Dépendances déclarées — lecteur pur JVM

Pas d'évolution du protocole côté serveur. Un **lecteur de scripts**
Kotlin/Groovy + catalogue `libs.versions.toml` vit côté app (module pur
JVM testable), lisant les fichiers via le pont FUSE
(`ResoudreRepertoireProjet`). Édition syntaxique minimale : préserver
mise en forme et commentaires, ne toucher que les lignes concernées,
refuser proprement les scripts trop dynamiques.

### 3. Variantes — nouveau `BuildVariantsRequest`/`Result`

Exposé via le modèle TAPI AGP. La variante choisie est mémorisée par
projet et réellement utilisée par `ClasspathRequest` et `BuildRequest`.

### 4. Versions disponibles — `maven-metadata.xml`

Client HTTP côté app (HTTPS only, cache borné, respect `--offline`,
dépôts réellement déclarés). ADR séparé pour la dépendance HTTP.

### 5. Tâches Gradle — réutiliser `FeuilleTachesFragment`

Le sélecteur de tâches existant (`FeuilleTachesFragment`) est réutilisé,
pas dupliqué. La section « Projet » l'ouvre en onglet intégré.

### 6. Rail du tiroir

5 destinations : Fichiers → Recherche → **Projet** → Git → Terminal.
L'emplacement « Projet » après Recherche suit l'ordre d'Android Studio
(Project view à gauche, Git en bas).

## Conséquences

- Nouveau fragment `ProjetFragment` + `ProjetViewModel` dans `feature:editor`.
- Incrément protocole v7 (`tooling/protocol`, `tooling/server`, `tooling/client`).
- Nouveau module pur JVM pour le lecteur de scripts (ou dans `core:domain`).
- `activity_editor.xml` : 5e entrée dans le rail de fragments.
- `docs/PROJET.md` (spec) + `docs/preview/projet.html` (maquette).

## Références

- ADR 0052 (tiroir à fragments)
- ADR 0039 (protocole tooling Gradle)
- ADR 0040 (modèle Gradle TAPI)
- ADR 0086 (catalogue de versions)
- `tooling/protocol/README.md`
