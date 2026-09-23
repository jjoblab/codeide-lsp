# 0032 — Disposition du bootstrap natif et localisation des outils

- Date : 2026-09-23
- Statut : accepté
- Contexte : étape T1 du prompt compagnon « Terminal intégré et bootstrap
  natif » (Terminal-1, sections 1.4, 2.2, 3.1 et 3.2) — première étape de
  la Phase 2, numérotée 19 dans la ROADMAP réordonnée.

## Contexte

Le terminal intégré (puis le tooling) exécute des binaires natifs fournis
par le dépôt `jjoblab/codeide-packages` : un bootstrap type Termux extrait
dans le stockage privé de l'application, complété par des paquets APT
(JDK, `git`…). Les binaires ELF de ce bootstrap portent
`TERMUX_APP_PACKAGE=jo.codeide` compilé en dur dans leur `.rodata` :
l'`applicationId` reste `jo.codeide` (contrainte technique, prompt
Terminal-1 section 1.1).

Avant d'installer quoi que ce soit (étape T2), il faut savoir **où**
chercher les outils et **quel environnement** donner aux sous-processus —
une seule source de vérité partagée par le terminal et le futur tooling.

## Décision

### 1. Disposition physique type Termux

```
root   = context.filesDir
prefix = root/usr        (PREFIX)
home   = root/home       (HOME du shell)
```

### 2. Scan multi-emplacements avec marqueurs de validité

Chaque outil est localisé par des **fonctions Kotlin pures**
(`core:bootstrap`, paramètre `racine`) qui essayent des candidats
successifs et ne retiennent que ceux qui portent leur marqueur — les
trois bugs historiques documentés par le prompt sont rejoués par les
tests :

- **JDK** : `lib/jvm/*` (emplacement réel du paquet `openjdk-17` de
  `codeide-packages`, constaté sur le fichier `Contents-aarch64` du dépôt
  APT) puis `opt/jdk*`/`opt/openjdk*` ; marqueur `bin/java` **et**
  `bin/javac` (le tooling compile — un JRE ne suffit pas) ; version la
  plus haute retenue par comparaison numérique de segments.
- **Gradle** : `opt/gradle/*` et `opt/gradle-*` ; marqueur
  `lib/gradle-launcher-*.jar` (ou `gradle-core-*`/`gradle-wrapper-*`) —
  *bug historique : un répertoire ne contenant qu'un script de lancement
  était pris à tort pour une distribution complète
  (`UnsupportedVersionException` ensuite)* ; un éventuel
  `$PREFIX/bin/gradle` n'est remonté **que si `getCanonicalFile()`
  diffère réellement du fichier** — *bug historique : un script
  d'enrobage régulier n'indique rien sur l'emplacement d'une
  distribution*.
- **Wrapper Gradle** : le Gradle réellement utilisé par un projet vit
  dans `home/.gradle/wrapper/dists/gradle-<version>-bin/<hash>/gradle-<version>/`
  — le localisateur le retrouve (`findCachedGradleDistribution`) pour
  éviter un retéléchargement.
- **SDK Android** : `opt/android-sdk`, `lib/android-sdk` ; marqueur : au
  moins un `android.jar` de plateforme ; `androidJar()` retient la
  plateforme la plus récente.
- **aapt2** : `usr/bin/aapt2`, déployé depuis les assets par le futur
  `Aapt2Deployer` (AGP télécharge par défaut un binaire pour
  l'architecture du poste de build, inexécutable sur un téléphone ARM) ;
  marqueur : bit d'exécution posé.

### 3. Environnement de sous-processus (`ProcessEnvironmentProvider`)

Une seule implémentation (fonction pure), consommée par les sessions
shell **et** le futur `NativeProcessLauncher` :

- retrait de `CLASSPATH` et `LD_PRELOAD` hérités du processus Android ;
- `HOME`, `TMPDIR=$PREFIX/tmp`, `PREFIX`, `LANG=en_US.UTF-8`,
  `LD_LIBRARY_PATH=$PREFIX/lib` fixés explicitement ;
- **`GRADLE_USER_HOME` fixé explicitement** — *bug historique : la JVM
  résout `user.home` via `getpwuid()`, pas depuis `HOME` ; sans cette
  variable, le cache Gradle atterrit au mauvais endroit* ;
- `PATH` = `$JAVA_HOME/bin:$PREFIX/bin:<PATH hérité>` ;
- `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` ajoutés seulement si
  les outils sont réellement installés.

### 4. Exception assumée à l'ADR 0003 (SAF)

L'ADR 0003 impose SAF pour les **documents de l'utilisateur**. Les
outils du bootstrap vivent dans le stockage privé (`filesDir`) où un
chemin `File` réel est la seule représentation **exécutable par le
noyau** — on ne peut pas `exec` une URI `content://`. Les ports
`ToolchainLocator` et `ProcessEnvironmentProvider` restent néanmoins des
interfaces de `core:domain` (doublures possibles) ; l'exception est
bornée à `core:bootstrap` et `core:terminal-runtime`.

## Conséquences

- Nouveau module `core:bootstrap` (dépend de `core:model`, `core:domain`
  — règle ajoutée à `checkModuleDependencies`) ; non référencé par `app`
  tant qu'aucun écran ne le consomme (branchement prévu en T3/T4) —
  l'audit « dépendances non consommées » de l'étape 18 portait sur le
  catalogue de bibliothèques, pas sur les modules en attente de
  consommateur.
- `feature:editor` et `feature:home` ne dépendront jamais de
  `com.termux:*` ni de `feature:terminal` : ils consomment uniquement
  `TerminalSessionRepository` (T4) via `core:domain`.
- Le `targetSdk` du module terminal (sections 1.2 du prompt) sera fixé
  par un ADR dédié lors de l'installation réelle du bootstrap (T2/T3,
  tests empiriques sur `jjoblab/codeide-packages`).
- Signalé au dépôt `codeide-packages` : **aucun paquet `gradle` ni
  `android-sdk`** n'existe à ce jour dans le dépôt APT (seuls
  `openjdk-17`, `git` et les paquets de base y figurent) —
  l'installateur (T2) devra soit s'appuyer sur les releases GitHub, soit
  attendre l'ajout des paquets ; conformément au prompt (section 1.1),
  le manque est signalé, pas contourné côté app.

## Références

- Prompt compagnon Terminal-1 (upload/CodeIDE_Prompt_Agent_IA_Terminal-1.md),
  sections 1.4, 2.2, 3.1, 3.2.
- Dépôt APT `https://jjoblab.github.io/codeide-packages/apt/codeide-main`
  (fichier `Contents-aarch64` : `usr/lib/jvm/java-17-openjdk/bin/javac`
  appartient au paquet `openjdk-17` 17.0.20 — emplacement retenu).
- Releases `github.com/jjoblab/codeide-packages` : seul
  `bootstrap-aarch64.zip` est publié à ce jour.
