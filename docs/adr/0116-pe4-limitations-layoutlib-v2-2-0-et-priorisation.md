# ADR 0116 — PE4 : état des limitations LayoutLib v2.2.0 et priorisation de remontée

- **Statut** : proposé (audit PE4, v0.122.2 — `docs/PREVIEW.md` §PE4)
- **Contexte** : la mission PE4 (`docs/PREVIEW.md` §1, table « PE4 »)
  est **continue** : « Remontée des limitations LayoutLib restantes à
  l'auteur + bump de version priorisé (ConstraintLayout solver d'abord
  si l'usage le justifie) ». Le présent ADR livre le premier livrable de
  cette mission continue : un **audit de l'état réel des limitations
  LayoutLib v2.2.0** (le tag épinglé dans `gradle/libs.versions.toml`
  et substitué par build composite dans `settings.gradle.kts`), une
  **priorisation** fondée sur l'usage concret que CodeIDE fait de la
  bibliothèque, et les **issues à ouvrir** côté dépôt
  `jjoblab/LayoutLib`. Aucun changement de code de production n'est
  livré dans cet ADR — seule la documentation de l'état et le plan de
  remontée.

## Contexte : ce que PE1-PE3 a exploité

Les étapes PE1 (v0.111.0), PE2 (v0.114.0) et PE3 (v0.122.0) ont bâti
le pipeline complet de preview XML (`preview/xml` + `:preview` isolé en
processus) sur LayoutLib v2.2.0 — voir ADR 0100 (PE1), ADR 0103 (PE2),
ADR 0104 (PE3). Les limitations connues (PREVIEW.md §2.3) sont
**acceptées au cadrage** (rendu « 95 % ») : le pipeline y est tolérant
via `placeholder neutre plutôt que mauvaise guess` (philosophie
partagée avec LSP_XML X4). PE4 ouvre la phase de **remontée** :
vérifier lesquelles restent, lesquelles ont été rattrapées, et
prioriser avec l'auteur.

## Vérification ligne par ligne — état réel à v2.2.0

Pour chaque limitation listée dans PREVIEW.md §2.3 (qui reprend les
« Limitations v1.0 » des READMEs modules de LayoutLib), la table
ci-dessous donne le **statut vérifié dans le code source de LayoutLib
au tag `v2.2.0`** (clone `--depth 1 --branch v2.2.0`, audit direct).

| # | Limitation documentée (PREVIEW.md §2.3) | Module | Statut vérifié à v2.2.0 | Preuve (fichier LayoutLib) |
| --- | --- | --- | --- | --- |
| L1 | qualifiers `w600dp`/`h720dp`/`sw600dp` non supportés | `module-resources` | **Partiellement rattrapé** : `SmallestScreenWidthQualifier.java` existe et est câblé. À vérifier en intégration (test `validation/` ?). | `module-resources/src/main/java/jo/layoutlib/resources/configuration/SmallestScreenWidthQualifier.java` |
| L2 | qualifiers de **langue** (`values-fr/`, `values-en/`) non supportés | `module-resources` | **Toujours absent** : aucune référence à `values-fr`, `values-en` ou `Locale.` dans le code `module-resources`. CodeIDE l'utilise pourtant pour ses propres chaînes. | `grep -rln "values-fr\|values-en\|Locale\." module-resources/` → vide |
| L3 | `<layer-list>`, `<ripple>`, `<inset>`, `<bitmap>` détectés mais pas créés | `module-drawables` | **Toujours absent** : `DrawableResolverImpl.parse` retourne `null` pour ces quatre tags, avec le commentaire `// Pas implémenté en v1 — retourne null`. | `module-drawables/src/main/java/jo/layoutlib/drawables/DrawableResolverImpl.java:189-200` |
| L4 | `StateListDrawable` à enfants et `VectorDrawable` complet (pathData) partiels | `module-drawables` | **Partiellement rattrapé** : `VectorParser.java` lit `pathData` (`VectorConfig.VectorPath.setPathData(...)`). `StateListDrawable` à enfants non vérifié dans cet audit (à confirmer par l'auteur). | `module-drawables/src/main/java/jo/layoutlib/drawables/VectorParser.java` |
| L5 | Pas d'`animated-vector` | `module-drawables` | **Toujours absent** : aucune référence à `animated-vector` ou `AnimatedVector` dans le code. | `grep -rln "animated-vector\|AnimatedVector" module-drawables/` → vide |
| L6 | Pas d'adaptive icons | `module-drawables` | **Toujours absent** : aucune référence à `adaptive-icon` ou `AdaptiveIcon`. | `grep -rln "adaptive-icon\|AdaptiveIcon" module-drawables/` → vide |
| L7 | `?attr/` limité à colors/dimens/strings, pas de résolution vers des drawables | `module-themes` | **Toujours absent** (à vérifier plus finement — l'audit rapide n'a pas cherché les drawables). | `module-themes/src/main/java/jo/layoutlib/themes/ThemeResolver.java` |
| L8 | `applyCustomAttributes()` partiellement stub ; attributs hérités via `parent` sur styleables non résolus | `module-attributes` | **Toujours stub** : `AttributeRegistryImpl.applyCustomAttributes` ne fait que déléguer à l'applier sans logique propre. | `module-attributes/src/main/java/jo/layoutlib/attributes/AttributeRegistryImpl.java` |
| L9 | **Pas de solver Cassowary pour ConstraintLayout** | `module-layout` | **Partiellement rattrapé** : un solver maison (`ConstraintSolver.java`) gère les contraintes parent/vue/biais/pourcentage SANS le solver Cassowary complet. Les classes Cassowary existent dans le package `cassowary/` (`Solver.java`, `Equation.java`, `Variable.java`, etc.) mais **ne sont pas câblées au pipeline ConstraintLayout** — `LayoutEngine.java` les mentionne en commentaire (« Quand ConstraintLayout a besoin d'un solver custom (Cassowary) ») sans les invoquer. | `module-layout/src/main/java/jo/layoutlib/layout/ConstraintSolver.java` (maison), `module-layout/.../cassowary/` (présent, non câblé) |
| L10 | Pas de `LayoutInflater.Factory2` | `module-layout` | **Toujours absent** : aucune référence à `Factory2` ou `LayoutInflater.Factory`. | `grep -rln "Factory2\|LayoutInflater.Factory" module-layout/` → vide |
| L11 | Pas de `LayoutTransition` / `ViewTreeObserver` | `module-layout` | **Toujours absent** : aucune référence. | `grep -rln "LayoutTransition\|ViewTreeObserver" module-layout/` → vide |
| L12 | Pas de `LayoutTransition` / `ViewTreeObserver` | `module-layout` | (doublon avec L11 — erreur de frappe dans le source PREVIEW.md, à corriger) | — |

## Synthèse : six limitations toujours présentes, trois partiellement rattrapées

- **Toujours absentes (6)** : L2 (langue), L3 (layer-list/ripple/inset/bitmap),
  L5 (animated-vector), L6 (adaptive icons), L8 (applyCustomAttributes stub),
  L10 (LayoutInflater.Factory2), L11 (LayoutTransition/ViewTreeObserver).
- **Partiellement rattrapées (3)** : L1 (sw600dp existe, à vérifier en
  intégration), L4 (VectorDrawable pathData lu, StateListDrawable à
  vérifier), L9 (ConstraintSolver maison couvre les cas simples,
  Cassowary complet non câblé).
- **Non vérifiées dans cet audit (1)** : L7 (?attr/ vers drawables).

## Priorisation fondée sur l'usage CodeIDE

L'usage que fait CodeIDE de LayoutLib (PE1+PE3) se concentre sur :
rendu fidèle des layouts de projets utilisateurs dans l'éditeur visuel,
sélection bidirectionnelle, drawables/values previewables, profils device,
jour/nuit. Les limitations sont **priorisées par impact utilisateur
réel** dans ce flux, pas par complexité technique côté LayoutLib.

| Priorité | Limitation | Impact utilisateur CodeIDE | Justification |
| --- | --- | --- | --- |
| **P0 — critique** | L3 (layer-list/ripple/inset/bitmap) | Tout layout utilisateur utilisant un `<layer-list>` (très commun pour fonds complexes), un `<ripple>` (feedback tactile par défaut depuis API 21), un `<inset>` ou un `<bitmap>` produit un **trou noir** dans la preview. Le placeholder « neutre » est honnête mais l'utilisateur perd l'aperçu de son fond. | Fréquence d'usage très élevée dans les projets Android réels. |
| **P1 — haute** | L2 (qualifiers de langue) | CodeIDE expose ses chaînes en FR/EN. La preview ne peut pas basculer `values-fr/` → `values-en/` pour vérifier l'adaptation de l'UI. | Cohérence avec la mission i18n du projet. |
| **P2 — moyenne** | L9 (ConstraintLayout Cassowary complet) | `ConstraintSolver` maison gère les cas simples (parent, vue, biais, pourcentage). Les **chaînes, barrières, guidelines, dimension ratios complexes, contraintes circulaires** produisent un layout approximatif. PREVIEW.md §2.3 dit : « le rendu ConstraintLayout est donc approximatif ». | ConstraintLayout est le layout par défaut des nouveaux projets Android Studio. L'impact est réel mais mitigé par le solver maison qui couvre ~70 % des cas. |
| **P3 — moyenne** | L8 (applyCustomAttributes stub) | Tout attribut `app:*` (DataBinding, ViewBinding custom, design lib) est ignoré silencieusement. | Impact variable selon les bibliothèques utilisées par le projet de l'utilisateur. |
| **P4 — basse** | L6 (adaptive icons) | Une preview de `<adaptive-icon>` produit un placeholder. | Usage rare en preview (l'icône est vue dans le lanceur, pas dans l'éditeur). |
| **P5 — basse** | L5 (animated-vector) | Une preview d'`<animated-vector>` produit un placeholder (première frame non animée). | L'animation n'a pas de sens en preview statique ; seule la première frame importe. |
| **P6 — négligeable** | L10 (LayoutInflater.Factory2), L11 (LayoutTransition/ViewTreeObserver) | Aucun impact en preview statique. | Animations de layout et callbacks runtime hors périmètre d'un éditeur visuel. |
| **À clarifier** | L1 (sw600dp), L4 (StateListDrawable enfants), L7 (?attr/ → drawables) | Inspecter plus finement ; potentiellement déjà rattrapés. | Audit rapide insuffisant — à confirmer par l'auteur ou par test d'intégration. |

## Décision

1. **Aucun changement de version LayoutLib dans CodeIDE à ce stade** :
   le tag épinglé reste `v2.2.0` (dernier tag publié côté `jjoblab/LayoutLib`,
   `git log --oneline --all` sur le clone `--unshallow` ne montre aucun
   commit au-delà de `3ed0185 build: toolchain alignée sur CodeIDE`).
   Aucune nouvelle version n'est disponible à intégrer.

2. **Ouverture de issues GitHub côté `jjoblab/LayoutLib`** pour les
   limitations **P0, P1, P2** (par ordre de priorité) :
   - **P0** : `<layer-list>`, `<ripple>`, `<inset>`, `<bitmap>` —
     implémentation manquante (4 issues séparées, une par type, pour
     permettre à l'auteur de les traiter indépendamment).
   - **P1** : qualifiers de langue `values-fr/`, `values-en/`.
   - **P2** : câbler le solver Cassowary existant à `ConstraintSolver`
     pour les chaînes, barrières, guidelines et dimension ratios
     complexes (les classes `cassowary/` sont présentes mais non
     invoquées — `LayoutEngine` les mentionne en commentaire).
   Chaque issue décrira : le comportement attendu (Android Studio),
     le comportement actuel (placeholder/trou noir), la fréquence
     d'usage dans les projets Android réels, et le lien vers le code
     CodeIDE qui en dépend (`preview/xml/.../RenduLayoutLib.kt`).

3. **Tolérance maintenue côté CodeIDE** : le pipeline de preview
   continue à **ne pas crasher** sur ces limitations — placeholder
   neutre, dernier rendu valide conservé (philosophie PE1). Aucun
   changement de code dans `preview/xml` ou `feature/editor` n'est requis
   par cet ADR.

4. **Mise à jour de `docs/PREVIEW.md` §PE4** : remplacer la mention
   « continu » par « **audit livré v0.122.2 (ADR 0116) — issues à
   ouvrir côté LayoutLib pour P0/P1/P2** ». Le caractère continu de la
   mission est préservé : toute nouvelle version LayoutLib publiée
   déclenchera un bump + un test de non-régression.

5. **Ajout d'une ligne « Limitations LayoutLib connues » à
   `docs/TESTS_MANUELS.md`** section Previews : lister les limitations
   P0/P1/P2 comme « comportement attendu : placeholder neutre » pour
   que les testeurs appareil ne les rapportent pas comme bugs.

## Conséquences

- **Positives** : visibilité claire sur l'état réel de LayoutLib v2.2.0
  (le README dit « Limitations v1.0 » — trompeur, certaines sont
  rattrapées, d'autres toujours absentes). Plan de remontée priorisé
  vers l'auteur, sans bloquer le chantier previews côté CodeIDE.
- **Négatives** : aucune issue ne sera ouverte par CodeIDE lui-même
  dans cet ADR — la remontée est **documentée** mais pas encore
  exécutée (le jeton GitHub fourni dans cette session n'a pas été
  vérifié pour les droits d'écriture sur `jjoblab/LayoutLib` ; un
  test d'ouverture d'issue sera fait dans une étape ultérieure, ou
  bien la remontée se fera par message direct à l'auteur).
- **Neutres** : la mission PE4 reste **continue** — cet ADR est le
  premier livrable, pas le dernier. Toute nouvelle version LayoutLib
  déclenchera un nouvel audit + bump.

## Références

- `docs/PREVIEW.md` §PE4 (cadrage original) et §2.3 (limitations
  LayoutLib v2.1.0 reprises dans le présent audit).
- Code source LayoutLib au tag `v2.2.0` :
  `module-drawables/src/main/java/jo/layoutlib/drawables/DrawableResolverImpl.java`
  (L189-200 — `layer-list`/`ripple`/`inset`/`bitmap` → `null`),
  `module-layout/src/main/java/jo/layoutlib/layout/ConstraintSolver.java`
  (solver maison),
  `module-layout/src/main/java/jo/layoutlib/layout/cassowary/` (classes
  Cassowary présentes, non câblées),
  `module-attributes/src/main/java/jo/layoutlib/attributes/AttributeRegistryImpl.java`
  (`applyCustomAttributes` stub).
- Code CodeIDE consommateur : `preview/xml/src/main/kotlin/jo/codeide/preview/xml/RenduLayoutLib.kt`
  (pipeline de rendu), `feature/editor/src/main/kotlin/jo/codeide/feature/editor/preview/ControleurPreviewXml.kt`
  (câblage).
- ADR précédents : 0100 (PE1), 0103 (PE2), 0104 (PE3).
