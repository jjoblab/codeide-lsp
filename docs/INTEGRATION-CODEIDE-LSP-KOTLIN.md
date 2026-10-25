# Intégration CodeIDE ↔ LSP Kotlin — chantiers côté hôte applicatif

> **Destinataire** : l'agent qui travaille sur le dépôt **CodeIDE**
> (l'application hôte qui embarque les serveurs LSP de ce dépôt).
> Ce document fait suite au rapport d'audit
> (`docs/RAPPORT-AUDIT-KOTLIN-LSP.md` §8) : les suites n° 3 et 4 sont
> **livrées côté moteur** (ADR 0222, ADR 0223) ; les n° 1 et 2 exigent
> des décisions **côté CodeIDE** — ce fichier les précise.

## État des suites recommandées (§8 du rapport)

| # | Suite | État | Où |
|---|-------|------|-----|
| 1 | Modèle Gradle (complétion DSL des `.gradle.kts`) | **Moteur prêt** — il manque le classpath Gradle par session | ce document §1 |
| 2 | Sélectivité par source set KMP | **Moteur prêt** — il manque le routage classpath par variante | ce document §2 |
| 3 | Sélection d'appelé par types complets | **Livré** | ADR 0223 |
| 4 | Companion objects sans nom | **Livré** | ADR 0222 |

Le moteur ne connaît qu'UN classpath par SESSION
(`SessionLangageKotlin.definirClasspath`) : tout ce qui suit se joue
donc dans **la façon dont CodeIDE crée et route les sessions**, sans
aucun changement du moteur.

## 1. Modèle Gradle — classpath des scripts `.gradle.kts`

### Ce qui est déjà prouvé (ADR 0223 + E2E `ReproductionGradleModeleE2ETest`)

Quand une session reçoit les jars Gradle RÉELS, la machinerie standard
fonctionne SANS code dédié :

- `import org.gradle.api.JavaVersion` puis `JavaVersion.VERSION_11` :
  survol + complétion (`VERSION_11 … VERSION_20`) par le régime
  statique de l'énumération ;
- zéro faux `kt.unresolved` sur le script (ADR 0221 : les nus restent
  muets — le récepteur implicite du DSL n'est pas modélisé).

L'E2E utilise trois jars de la distribution Gradle locale :
`gradle-core-api-*.jar`, `gradle-kotlin-dsl-*.jar`,
`gradle-stdlib-java-extensions-*.jar` (celui-ci porte
`org.gradle.api.JavaVersion`).

### Ce que CodeIDE doit faire

1. **Routage** : créer une session Kotlin DÉDIÉE aux documents
   `.gradle.kts`/`.settings.gradle.kts` du projet ouvert (une session
   « scripts » à côté de la session « sources »). Le serveur LSP de ce
   dépôt a une session par connexion — deux connexions (ou un
   multiplexeur côté CodeIDE) font l'affaire.
2. **Provisionnement des jars** (par ordre de priorité) :
   - les jars de la distribution Gradle utilisée par le projet (cache
     du wrapper `~/.gradle/wrapper/dists/…/lib/` — découverte par
     préfixe de nom, cf. `ReproductionGradleModeleE2ETest.jarsGradle`) ;
   - le jar AGP du projet (`com.android.tools.build:gradle:x.y.z`,
     dépôt Google Maven) pour les membres de `android { … }` (les
     classes `com.android.build.gradle.*`, p.ex. `BaseExtension`) ;
   - les version catalogs ne sont PAS des jars : `libs.versions.toml`
     reste hors modèle (les accès `libs.*` restent muets — ADR 0221).
3. **Corpus minimal conseillé** (les trois jars Gradle + AGP + ses
   dépendances API directes si le budget le permet — la lecture
   classfile tolère les chaînes incomplètes, chaque maillon lisible
   compte).

### Ce qui reste hors modèle (documenté, pas un bug)

- les ACCESSEURS GÉNÉRÉS par projet (`android { }`, `dependencies {
  implementation(…) }`, `buildTypes { release { … } }`) : Gradle les
  GÉNÈRE par projet à partir des plugins appliqués — ils ne vivent
  dans AUCUN jar. Sans modèle Gradle exécuté, la complétion DANS les
  blocs reste hors de portée (elle exige le typage du récepteur
  implicite des lambdas du DSL — génération + inférence K5+). Les nus
  restent muets (ADR 0221) : aucun faux diagnostic, et les QUALIFIÉS
  écrits (`JavaVersion.VERSION_11`, `org.gradle.api.JavaVersion`) sont
  prouvés par le classpath fourni.

## 2. Sélectivité par source set KMP

### Le besoin

Un projet KMP a des classpaths DIFFÉRENTS par source set
(`commonMain` voit kotlin-stdlib ; `androidMain` voit EN PLUS
android.jar + les dépendances androidMain ; `iosMain` voit les
dépendances natives…). Aujourd'hui CodeIDE fournit l'UNION — les
moteurs sondent donc les classes de TOUS les source sets pour chaque
fichier : aucun faux diagnostic (l'union contient toujours le bon
classpath), mais de la COMPLÉTION parasite (un fichier `commonMain`
voie les types `androidMain`).

### Ce que CodeIDE doit faire

1. **Une session par source set** (ou par variante) : réutiliser le
   mécanisme de la section 1 — le routage décide, à l'ouverture d'un
   document, QUELLE session le porte :
   `src/commonMain/**` → session common ; `src/androidMain/**` →
   session android ; etc.
2. **Classpath par source set** : Gradle le expose via l'outiling API
   (`SourceSet.runtimeClasspath` / IDEA model `ContentRoot` +
   `sourceDirs` par module). Si CodeIDE interroge déjà le wrapper
   Gradle pour le classpath principal, la même requête par source set
   donne la table de routage.
3. **Fallback honnête** : en attendant l'outillage par source set,
   l'union actuelle reste CORRECTE en diagnostic (sur-ensemble) — le
   changement est une AMÉLIORATION de précision de complétion, pas une
   correction d'erreur.

### Ce que le moteur garantit déjà

- les `expect`/`actual` (ADR 0220) : les membres d'objets `expect` et
  la définition ordonnée expect→actual fonctionnent sur TOUT classpath
  contenant les sources ;
- la garde `connaitDesEntrees` (ADR 0223) : une session sans entrées
  ne prouve jamais d'absence — le silence reste le comportement d'un
  source set non provisionné.

## 3. Récapitulatif des contrats moteurs utiles à CodeIDE

| Contrat | Référence |
|---------|-----------|
| Un classpath par session (entrées JAR/AAR + vue + racines sources) | `api/ClasspathKotlin.kt` |
| `.kts` = mode SCRIPT (nus muets, qualifiés faillibles) | ADR 0221 |
| Récepteur statique : classe puis `fqcn$Companion` | ADR 0222 |
| Sélecteur à récepteur typé : présence ET absence prouvables | ADR 0223 |
| Vue fusionnée par SessionLsp Java (`vueClasspathKotlin()`) | tests E2E du dépôt |
| Index disque partagé (garde de couverture) | ADR 0211 |
