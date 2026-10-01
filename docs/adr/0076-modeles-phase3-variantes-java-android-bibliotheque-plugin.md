# ADR 0076 — Modèles avancés : variantes, Java Android, bibliothèque .aar et plugin Gradle (phase 3 du roadmap)

- Statut : accepté (2026-10-01)
- Contexte : phase 3 du roadmap (`docs/ROADMAP.md`) — 3.1 types de projet
  Android, 3.2 support Java, 3.3 modèles supplémentaires

## Contexte

La phase 2 a rendu les cinq modèles existants fiables (ADR 0075) mais le
modèle Android reste monocellulaire : une seule variante d'écran, Kotlin
seulement. Le roadmap demande des types de projet (`empty-activity`,
`no-activity`, `basic-activity`), le langage Java pour Android, une
bibliothèque `.aar` et un modèle de développement de plugin Gradle
(`compose-app` restant différé par l'ADR 0002).

## Décisions

### 1. Variantes par paramètres, pas par modèles dupliqués

`projectType` et `language` sont des `CHOICE` du manifeste `android-app`
(v1.2.0) ; les fichiers se **sélectionnent par `when`** sur des variables
calculées (`estActiviteVide`, `estSansActivite`, `estActiviteTiroir`,
`langageKotlin`), et le contenu commun varie par `{{#if}}` (manifeste sans
activité, thème `NoActionBar`, dépendance `drawerlayout`, chaînes du
tiroir). Un modèle dupliqué par variante aurait multiplié la maintenance
pour un benefit nul : le mini-langage d'expressions (ADR 0018) suffit.

Point clé du moteur : le contrôle des doublons de chemins au chargement ne
porte que sur les chemins **statiques**. Deux entrées vers le même chemin
**templatisé** (`…/java/{{packageName|packagePath}}/MainActivity.kt`,
version simple et version tiroir) sont donc licites — elles ne s'excluent
que par leurs `when`. Les chemins statiques (layouts) gardent des noms
distincts (`activity_main.xml`, `activity_main_tiroir.xml`).

### 2. Wizard adaptatif

Le registre des composants nommés (`RenduParametres`) mappait
`projectType` aux tuiles segmentées — **deux** tuiles par construction.
La phase 3 introduisant trois valeurs Android, la règle devient
adaptative : au-delà de deux choix, les **cartes radio empilées**
prennent le relais (chaque valeur garde son explication) ; `language`
(deux valeurs) rejoint les tuiles. Aucune modification du moteur : c'est
une décision de présentation, comme le prévoyait l'étape 10.

### 3. Java Android : miroir des sources

`MainActivity`, `FragmentAccueil`, `Greeter` et les deux tests sont
déclinés en `.java` (champs publics du ViewBinding, imports `static`
pour JUnit). Le bloc `kotlin { compilerOptions }` est enveloppé dans
`{{#if langageKotlin}}` : un projet Java pur n'embarque ni plugin ni
runtime Kotlin. Les layouts, menus et ressources restent partagés (XML
indifférent au langage).

### 4. `android-library` : un module, pas une app déguisée

Nouveau modèle (v1.0.0) : `com.android.library` sur `:library`,
`consumer-rules.pro`, manifeste minimal, API publique `Greeter`, README
d'intégration. La dérivation `com.example.<nom>` est réutilisée telle
quelle (le paramètre s'appelle `appName` — l'étiquette i18n dit
« Nom de la bibliothèque » : l'identifiant sert la convention moteur,
le libellé sert l'humain).

### 5. `gradle-plugin` : compilateur embarqué, identifiants dérivés

Le choix structurant : **`kotlin-dsl`** (plugin embarqué dans la
distribution Gradle) plutôt qu'un `kotlin("jvm")` externe. Motif
empirique : Gradle 9.7.1 embarque Kotlin 2.4.0, et le `gradleApi()`
expose ce `kotlin-reflect` 2.4.0 sur le classpath de compilation — un
KGP 2.2.21 échoue sur les métadonnées (« binary version 2.4.0, expected
2.2.0 »). `kotlin-dsl` aligne par construction le compilateur sur la
distribution qui exécutera le plugin, au prix de dépendre du DSL Gradle
du moment (acceptable : un plugin vit avec sa distribution cible).

Corollaires empiriques du même constat :

- Kotlin 2.4 convertit `Action<T>` en lambda **à récepteur** —
  `tasks.register(NAME, Type::class.java) { … }` doit utiliser la forme
  idiomatique sans paramètre nommé ;
- `validatePlugins` exige une politique de cache déclarée :
  `@DisableCachingByDefault(because = …)` sur la tâche d'exemple (la
  raison est i18n via `{{t:}}` — le rendu précède la compilation) ;
- `java-gradle-plugin` n'applique plus `maven-publish` en Gradle 9 :
  il est appliqué explicitement, et l'artifactId de la publication
  `pluginMaven` est fixé à `{{artifactId}}` — le nom du projet peut
  contenir des espaces, invalides en Maven ;
- `pluginId` dérive de `packageFromArtifactId` (fonction de l'ADR 0075
  **réutilisée** : aucune fonction moteur nouvelle), et la classe
  principale `{{projectName|resourceName}}Plugin` illustre le filtre
  `resourceName` sur des noms hostiles.

### 6. Vérification étendue

`scripts/verify-templates.sh` passe de 24 à **32 combinaisons** (8
nouvelles : 4 variantes Android, 2 bibliothèques, 2 plugins) avec deux
cas de build supplémentaires : `gradle-android-lib` (AAR de release +
tests du module, aucun APK) et `gradle-plugin` (build + `validatePlugins`
+ tests ProjectBuilder + publication maven locale et marqueur). La
comptabilité par empreinte d'assets reste inchangée (reprise par
marqueurs `.verifie`). La 32e vérification est bout-en-bout : un projet
consommateur jetable applique le plugin publié et exécute `greet`.

## Conséquences

- un seul manifeste Android à maintenir pour 6 combinaisons de variantes
  (3 types × 2 langages), toutes vérifiées par build réel ;
- le catalogue passe à 7 modèles ; `ModelesEmbarquesTest` et
  `TemplatesIntegrationTest` ancrent la liste complète,
  `ModelesPhase3Test` (9 tests) éprouve variantes, dérivations, i18n et
  entrées hostiles ;
- la dette « sam conversion » de Kotlin 2.4 est documentée dans le modèle
  même (commentaire du plugin) — elle resurgira pour tout modèle ciblant
  Gradle 9.7+ ;
- `compose-app` reste différé (ADR 0002) : la réévaluation demandera un
  ADR dédié.
