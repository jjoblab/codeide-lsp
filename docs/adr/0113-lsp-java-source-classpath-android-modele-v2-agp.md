# ADR 0113 — LSP Java étape 1 : source du classpath Android par le modèle v2 d'AGP, modules vendored intégrés au build

- **Statut** : accepté (v0.92.0, étape 1 de la mission « LSP Java » —
  prompt 2 §7/§8, options A+C recommandées par l'étude §2.1/§10)
- **Contexte** : l'étude d'étape 0 a démontré (BancTooling, dépôt de
  référence) que le modèle `IdeaProject` — l'unique source du
  `ClasspathHandler` — est **VIDE pour les 26 modules Android** du
  dépôt (zéro racine source, zéro dépendance) : AGP ne publie pas ses
  configurations dans le modèle idea. Les champs `androidJar`,
  `fichiersGeneres`, `ignore`, `avertissements` du protocole n'étaient
  jamais remplis, les AAR livrés bruts (ADR 0058), les sourcesets main
  et test confondus dans `sourceDirs` (W5), aucune empreinte de
  classpath (W6). Par ailleurs le propriétaire a donné « carte blanche »
  pour que le module lsp-java vendored (ADR 0108) utilise les MÊMES
  fichiers Gradle que le reste de CodeIDE.

## Décisions

1. **Source du classpath Android : les modèles v2 d'AGP via la Tooling
   API** (options A+C de l'étude) — `com.android.tools.build:builder-model`
   (Apache-2.0 AOSP, même train de versions que l'AGP, 9.4.1, vérifié
   Google Maven) :
   - `BasicAndroidProject` par projet : `bootClasspath` (= `android.jar`,
     D5/ADR 0112), sourcesets main **et** unitTest (racines java+kotlin),
     variantes ;
   - `AndroidProject` (v2) par projet : `generatedSourceFolders` (R,
     BuildConfig, KSP/kapt) et `classesFolders` (sorties javac/kotlin +
     `R.jar`) de la variante retenue ;
   - `VariantDependencies` **paramétré** par `ModelBuilderParameter`
     (variante choisie : `debug` si elle existe, sinon la première
     contenant `debug`, sinon la première — même choix qu'Android Studio
     à la première sync) : graphe du classpath de compilation principal
     et de test + carte des bibliothèques. Le paramètre est TOUJOURS
     complet (tous les drapeaux `dontBuild*` fermés) : un getter booléen
     non initialisé du proxy Tooling API renvoie null et fait échouer
     AGP en NPE (constat de la sonde).
   - IdeaProject **conservé pour les modules JVM purs** — mais résolu
     par le **model builder**, PAS dans l'action (voir le point 3).

2. **Normalisation des AAR : livrée par AGP, pas par nous** — mesuré sur
   le jeu « lourd » (191 AAR) : `androidLibraryData.compileJarFiles`
   pointe sur les jars **déjà extraits** par les transforms du cache
   Gradle (`transforms/<haché>/transformed/<artefact>/jars/classes.jar`).
   Le serveur n'extrait rien : chaque jar extrait devient une entrée
   `JAR`, l'AAR d'origine voyage dans le nouveau champ `ClasspathEntry.aar`
   (provenance, future clé d'index ADR 0111). L'ancienne livraison d'AAR
   bruts (ADR 0058) reste le REPLI (AAR sans jars extraits, périphérique).

3. **Partition action / model builder** (constat d'implémentation, mesuré
   sur la fixture multi-module) : dans une `BuildAction`, l'adaptateur
   côté démon proxifie `IdeaModule.getDependencies()` par l'interface de
   BASE `IdeaDependency` — `IdeaModuleDependency` (donc
   `targetModuleName`) devient invisible au `instanceof` alors que
   l'objet sous-jacent est un `DefaultIdeaModuleDependency`. Le model
   builder, lui, livre les types concrets. L'action porte donc TÂCHES +
   modules Android v2 (pas d'équivalent model-builder paramétrable), et
   IdeaProject est résolu par le model builder APRÈS l'action (le démon
   est déjà configuré, l'aller est peu coûteux — la propriété « une
   seule requête » de la v4 est conservée pour ce qui EXIGE l'action).

4. **Protocole v8** : `ClasspathModule.sourceDirsTest` (W5 — main et
   test séparés), `ClasspathEntry.aar` (provenance), `name` normalisé au
   CHEMIN Gradle (`:app`) pour les deux sources (les entrées MODULE
   désignent leur cible par le même identifiant), remplissage de
   `androidJar`/`fichiersGeneres`/`varianteAndroid` (variante RÉELLE du
   modèle, plus l'heuristique de nom v0.40.1)/`ignore`+`raisonIgnore`
   (module conteneur sans sources ni classpath)/`avertissements`
   (dépendances non résolues). Goldens régénérés volontairement
   (`REGENERER_DORES=1`, procédure du module protocol). Les entrées
   RELOCATED et NO_ARTIFACT_FILE sont écartées (la cible réelle figure
   ailleurs dans le graphe résolu / BOM sans fichier) — silencieusement
   car le classpath résolu est complet.

5. **`lsp:model` (nouveau module Kotlin JVM pur)** : conversion PURE du
   `ClasspathResult` en snapshots immuables (§7.1 du prompt 2) —
   `ProjetLsp`/`ModuleLsp`/`EnsemblesSources` (rôles main/test/générés)/
   `ClasspathInstantane` (entrées ordonnées, dédupliquées, empreintées —
   W6 : sha1 du chemin Maven, haché de transform, sinon
   taille+mtime+empreinte partielle)/`DependanceModule` (cycles rejetés
   à l'échelle du PROJET avec avertissement ; drapeau `exporte` présent
   mais non déterminable depuis les modèles v8 — documenté)/
   `AssociationFichier` (racine de sourceset la plus profonde, FQCN
   recoupé à la déclaration package — correctif du bug historique)/
   `PersistanceModeleLsp` (format versionné, validation
   taille/horodatage — W7)/`DepotModeleLsp` (publication par instantanés
   successifs). Dépend UNIQUEMENT de `:tooling:protocol` (extractibilité
   du moteur préservée).

6. **Modules vendored intégrés au build** (carte blanche du
   propriétaire) : `:lsp:java` (moteur, 90 fichiers Java de l'archive,
   sources INTACTES) et `:lsp:ecj-art` (patcher build-time, 2 fichiers)
   — build.gradle traduits en Kotlin DSL, versions par
   `gradle/libs.versions.toml` (vérifiées dépôts officiels),
   `patchEclipseForArt` réécrit en classe de tâche **compatible
   configuration cache** (le dépôt l'active — le banc de l'étape 0 ne
   l'avait pas) avec le lancement du patcher par ProcessBuilder (Gradle
   9 a retiré `Project.javaexec` dans doLast) ; tests JUnit 5 avec
   `JO_TEST_JDK_HOME` fourni par le build (le JDK du démon est un JDK
   complet — les sondes du module attrapent sinon des JRE système sans
   `src.zip`). detekt/spotless ne s'appliquent pas aux sources vendored
   (exemption ADR 0108, règle 8 documentée dans les README des modules).

## Justification empirique (sonde `SondeModeleAndroid`, banc étape 0)

- AGP 9.4.1 / Gradle 9.7.1 / projet Compose 250 jars puis dépôt CodeIDE
  (34 projets) : `bootClasspath` = android.jar ✓, variantes debug/release
  ✓, `generatedSourceFolders` (ap_generated_sources) ✓,
  `classesFolders` (javac + **R.jar** + kotlin) ✓, graphes compile+test
  ✓, dépendances frères PROJECT (par chemin) ✓, AAR avec jars extraits
  ✓, 0 échec — les modèles v2 livrent tout ce que le prompt exige.
- Test d'intégration `ServeurIntegrationTest` sur une NOUVELLE fixture
  AGP réelle (`android-simple`, 2 modules) — le cas que le fixture pur
  JVM n'avait jamais couvert.
- Classpath du classpath direct (sans sync) : même code chemin que la
  sync (l'action + le model builder), plus de doublon de conversion
  (« doublon volontaire » de la v4 fermé — `ConversionClasspath`).

## Alternatives écartées

- **(B) init script injecté côté serveur exportant un modèle dédié
  CodeIDE** : fragile (interface avec chaque AGP de chaque projet
  cible), le modèle v2 officiel existe déjà.
- **IdeaProject seul + expansion applicative** : contraire au constat
  mesuré (vide pour Android).
- **Réécrire la conversion Android côté client (app)** : le serveur voit
  le classpath UNE fois (sync) et le persiste — la normalisation au
  point de résolution profite à tous les consommateurs (même choix que
  l'étude §2.2).
- **Extraire les AAR côté serveur (comme le recommandait l'étude)** :
  inutile — AGP livre déjà les jars extraits (mesuré).

## Conséquences

- Le protocole passe en v8 : app et orchestrateur montent ensemble
  (même APK, handshake à égalité exacte) ; les anciens
  `lsp-classpath.json` persistés restent décodables (champs optionnels)
  mais seront remplacés à la sync suivante.
- `sourceDirs` ne porte plus que les racines PRINCIPALES — les
  consommateurs d'affichage (feature:diagnostics) voient le compte des
  sources principales ; les racines de test voyagent dans
  `sourceDirsTest`.
- Builder-model 9.4.1 exige un AGP cible ≥ 8 (interfaces v2 stables
  depuis 8.0) ; un projet plus ancien retombe sur IdeaProject (repli
  total, jamais fatal) et le classpath des modules Android y reste vide
  — écart documenté, suffisant à l'étape 1 (l'appareil cible construit
  des projets AGP modernes).
- Le coût d'une résolution directe de classpath = 1 action + 1 model
  builder sur démon déjà configuré (la sync n'ajoute que l'IdeaProject
  — ~le coût de la v4).
