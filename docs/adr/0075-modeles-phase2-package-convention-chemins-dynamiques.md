# ADR 0075 — Modèles corrigés : package `com.example`, chemins dynamiques, enrichissements (phase 2 du roadmap)

- Statut : accepté (2026-09-30)
- Contexte : phase 2 du roadmap (`docs/ROADMAP.md`) — problèmes n°2 à n°4
  connus de la v0.42.0 : les modèles `android-app`, `spring-boot` et
  `kotlin-multiplatform` étaient **cassés à la génération**

## Contexte

Les trois modèles ajoutés en v0.41.1 n'avaient jamais été branchés au
harnais de vérification (`scripts/verify-templates.sh`) : seuls
`kotlin-jvm` et `java` y figuraient. Le constat à l'ouverture de la
phase 2 (génération de contrôle via `:tools:generateur`) est sévère :

1. **la génération échouait carrément** — `gitattributes.entete`
   manquait dans les i18n fr/en des trois modèles (le moteur échoue
   explicitement sur toute clé i18n inconnue, par construction) ;
2. **les chemins de sources étaient codés en dur** —
   `src/main/kotlin/jo/codeide/template/…` au lieu de
   `{{packageName|packagePath}}` : le fichier généré déclarait
   `package {{packageName}}` depuis un répertoire figé, le build du
   projet généré ne pouvait pas compiler ;
3. **le `actual` KMP n'était pas câblé** — `Greeter.jvm.kt.tpl`
   existait dans les assets mais le manifeste ne le référençait pas :
   le `expect class Greeter` restait sans implémentation ;
4. **le package par défaut n'était pas conventionnel** —
   `packageFromNameAndAuthor` produit `auteur.projet`, pas le
   `com.example.<nom>` attendu pour un projet Android, Spring ou KMP
   neuf ;
5. **des versions figées incompatibles avec le wrapper embarqué** —
   Gradle 9.7.1 avec AGP 8.7.3, Kotlin 2.0.21, Spring Boot 3.4.1 :
   la chaîne ne pouvait pas résoudre ses plugins ;
6. les options communes (`includeReadme`, `includeGitignore`,
   `includeEditorconfig`) étaient ignorées (aucune condition `when`).

## Décision

### 1. Conventions de package (fonctions `defaultFrom` nommées)

Deux fonctions rejoignent `slug`, `parentPackage` et
`packageFromNameAndAuthor` dans `TemplateDefaultFunctions` (le moteur
n'évalue toujours **aucun code** du manifeste) :

- `packageFromAppName` → `com.example.<slug(appName)>` (modèle
  Android) ;
- `packageFromArtifactId` → `com.example.<slug(artifactId)>` (Spring
  Boot, KMP).

`kotlin-jvm` et `java` conservent `packageFromNameAndAuthor` : ce sont
des projets JVM sans convention de package imposée.

`Sources` gagne `valeursParametres` : la carte des valeurs EFFECTIVES
au moment de la dérivation (copie figée de la passe 2 du moteur). Une
dérivée peut donc lire la valeur d'un paramètre déclaré AVANT elle —
la règle documentée (« la source précède sa dérivée ») s'applique
maintenant aussi aux paramètres, pas seulement au nom du projet et à
l'auteur. Les manifestes sont réordonnés en conséquence : `appName`
avant `packageName` (Android), `artifactId` avant `packageName` (Spring
et KMP) ; `groupId` (`parentPackage`) reste après `packageName`.

Le repli est déterministe : une valeur muette donne `com.example.app`
(package toujours valide) ; un segment numérique est préfixé (`p2048`).

### 2. Filtre `resourceName`

Les noms de ressources Android doivent commencer par une lettre et ne
contenir que `[A-Za-z0-9._]`. Le nouveau filtre de transformation
`resourceName` normalise n'importe quelle saisie : décomposition NFD,
élimination des marques combinantes, mots capitalisés et collés,
repli `App`, préfixe si le nom commence par un chiffre —
`"mon éclat & 2048"` → `"MonEclat2048"`. Il alimente le nom du thème
(`Theme.{{appName|resourceName}}` dans `themes.xml` et le manifeste).

### 3. Chemins dynamiques et corrections de câblage

- tous les chemins de sources passent à
  `{{packageName|packagePath}}` (y compris l'arbre du README) ;
- `Greeter.jvm.kt` est câblé au manifeste KMP (l'`actual` existe
  enfin dans le projet généré) ;
- les options communes obtiennent leurs conditions `when`
  (`includeReadme`, `includeGitignore`, `includeEditorconfig`, et le
  nouveau `app/.gitignore`) ;
- `settings.gradle.kts` d'Android utilise
  `{{projectName|kotlinString}}` (le nom du PROJET, échappé) et non
  plus l'appName brut ;
- le manifeste Android référence `@string/app_name` et
  `@style/Theme.<Nom>` au lieu de littéraux.

### 4. Enrichissements

- **Android** : `res/values/strings.xml` (`app_name`), `colors.xml`,
  `themes.xml` (`Theme.Material3.DayNight` + couleurs), `proguard-rules.pro`
  (avec `buildTypes.release` minifié), `ExampleUnitTest.kt`,
  `app/.gitignore` ; `allowBackup`/`supportsRtl` au manifeste ;
- **Spring Boot** : `GreeterRepository.kt` (patron repository, mémoire
  séquentielle), `ApplicationTests.kt` (`@SpringBootTest`), et
  `application.yml` REMPLACE `application.properties` (une seule
  source de configuration — deux sources actives seraient une surprise
  de priorité) ; le contrôleur gagne `POST/GET /salutations` ;
- **KMP** : `commonTest` enrichi (ordre de `greetAll`, plateforme
  nommée) ;
- les trois README deviennent i18n (fr/en) comme ceux de `kotlin-jvm`
  et `java`.

### 5. Chaîne d'outils des projets générés

Les versions sont alignées sur le Gradle 9.7.1 que le wrapper
embarqué télécharge, et vérifiées une à une sur Maven Central / Google
Maven (règle « ne jamais deviner une version ») :

- **Android** : AGP 9.4.1 (Kotlin INTÉGRÉ — le plugin
  `kotlin-android` n'est plus appliqué, `kotlin { compilerOptions }`
  remplace `kotlinOptions`), `compileSdk 37` + `compileSdkMinor 2`,
  core-ktx 1.19.0, appcompat 1.8.0, material 1.14.0, tests JUnit 4
  (le `kotlin("test")` nu, sans KGP, ne résout pas `kotlin.test.Test`
  — JUnit 4 est le choix zéro-configuration d'AGP) ;
- **Spring Boot** : Boot 4.1.1 (exige Gradle 9 — aligné), Kotlin
  2.2.21, plugin `kotlin-spring` (les classes finales ne peuvent pas
  être proxyfiées par CGLIB), BOM par `platform()` native Gradle (le
  plugin `io.spring.dependency-management` n'est plus nécessaire),
  `application.yml` ;
- **KMP** : Kotlin 2.2.21 ; la tâche `run` est un `JavaExec` branché
  sur la sortie de la compilation `jvmMain` — le plugin
  `application` est INCOMPATIBLE avec KMP sur Gradle 9 (verrouillage
  des configurations sortantes `:apiElements`).

## Conséquences

- `scripts/verify-templates.sh` couvre les 24 combinaisons : les 18
  historiques + 6 nouvelles (`sb-fr`, `sb-en-min`, `kmp-fr`,
  `kmp-en-min`, `andr-fr`, `andr-en-min`) — build réel, tests, `run`
  KMP, `assembleDebug` + `testDebugUnitTest` Android (SDK via
  `CODEIDE_ANDROID_SDK`) ;
- la valeur effective du champ package suit sa source à chaque frappe
  dans le wizard (mécanisme existant « suit tant que non figé ») ;
- `ModelesEmbarquesTest` passe de 15 à 22 tests : dérivation
  `com.example` des trois modèles, fichiers attendus, noms hostiles
  (`resourceName` + échappements), traductions fr/en, absence des
  fichiers optionnels ;
- le wizard n'a besoin d'AUCUNE modification : les paramètres sont
  lus dynamiquement depuis les manifestes (le réordonnancement
  source-avant-dérivée est invisible hors du manifeste).
