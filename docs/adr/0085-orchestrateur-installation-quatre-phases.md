# ADR 0085 — Architecture cible : orchestrateur d'installation de l'environnement en quatre phases vérifiées

- **Statut** : accepté (étape E1 de la refonte ; remplace, pour le parcours
  d'installation, les décisions d'exécution des ADR 0082/0083 — voir E6 pour
  le marquage « remplacé par »)
- **Contexte** : cahier des charges « refonte complète du parcours
  d'installation » (sections 1 à 4) ; constats vérifiés dans le code —
  ~1 400 lignes de shell générées, orchestration par frappe dans un pty,
  état déduit par heuristiques disque, double installateur, fallbacks
  silencieux, sorties jetées (ADR 0084).

## Décision

### 1. Quatre phases strictement séquentielles, chacune vérifiée par exécution réelle

| Phase | Contenu | Vérification fonctionnelle (exécutée) |
|---|---|---|
| `BOOTSTRAP` | base Termux-like (`usr/`, `home/`) : téléchargement SHA-256, staging, bascule atomique, second stage — logique éprouvée d'`InstallateurBootstrap` portée dans le nouveau cadre | `$PREFIX/bin/sh -c 'echo ok'` → `ok` ; `apt --version` s'exécute ; binaire `pkg` exécutable ; marqueur d'installation posé |
| `PACKAGE_TOOLS` | `pkg update` (repli `apt update`, nouvelles tentatives à délai croissant, **échec persistant = échec de phase**) puis paquets un par un (liste du catalogue : `curl`, `ca-certificates`, `tar`, `xz-utils`, `unzip`) | chaque outil **exécuté** (`tar --version`, `xz --version`, `unzip -v`, `curl --version`) ; `ca-certificates` présent |
| `JAVA` | `openjdk-17` via `pkg` (version du dépôt interrogeable par `apt-cache policy`, jamais codée) ; résolution `JAVA_HOME` unique (règle de `LocalisationOutils`, conservée) | `java -version` et `javac -version` **démarrent**, version majeure analysée ; **test TLS** : HTTPS Java vers `dl.google.com` (ou `keytool -list -cacerts`) — ADR 0084 R6 : un truststore cassé ne se voit QUE là |
| `ANDROID_SDK` | plan résolu du manifeste v2 (ADR 0086), téléchargements SHA-256 uniques, extraction en staging, bascule atomique par composant, licences après acceptation explicite, câblage Gradle | `sdkmanager --version` (JAVA_HOME explicite + `--sdk_root`) ; `verify` de **chaque composant du plan** (champ du manifeste) ; chaque `android.jar` ouvrable ; `sdkmanager --list_installed` cohérent ; vérification approfondie (bouton) : `assembleDebug` réel d'un projet généré |

La phase N+1 ne démarre **jamais** tant que la phase N n'est pas `Succeeded`
(ou `Degraded` pour les composants non critiques — seule la phase 4 a une
criticité différenciée : `cmdline-tools` non critique, ADR 0086).

### 2. État : une seule source de vérité, typée et persistée

- Le domaine expose `EnvironmentSetupOrchestrator.state:
  StateFlow<EnvironmentSetupState>` — une `PhaseState` par phase
  (`NotStarted` / `Running(step, progress, startedAt)` / `Succeeded(
  verifiedAt, versions)` / `Degraded(verifiedAt, warnings)` / `Failed(error,
  logTail)`), la phase en cours, et la date d'acceptation de la licence SDK
  (conservée dans l'état, exigée avant la phase 4).
- L'état est **persisté** dans `filesDir/install-state.json` à schéma
  versionné (port `InstallStateStore`) : reprise au premier lancement après
  un kill — toute phase `Running` au moment de la mort est relue
  `NotStarted` (rejouée), les phases vérifiées ne sont **jamais** retouchées.
- Au démarrage, contrôles **légers** de re-vérification (exécutions réelles
  rapides) ; à la demande, `verify(deep = true)` : vérification
  approfondie (génération + `assembleDebug` réel).
- Les heuristiques disque (`MarqueursOutils`, déduction d'état par
  `LocalisationOutils`) ne disparaissent pas de l'app (le localisateur
  reste la règle unique de `JAVA_HOME` et sert à l'adoption des
  installations existantes), mais **l'état du parcours** n'est plus déduit
  du disque : il est lu dans `install-state.json`, puis re-vérifié par
  exécution.

### 3. Logique en Kotlin, le shell n'exécute que les outils

- L'orchestration, la résolution de plan, la gestion d'erreurs, la
  progression et la reprise vivent en Kotlin testable (`core:domain` pour
  les types et ports ; `core:bootstrap` pour les implémentations).
- Le shell sert **uniquement** à exécuter les outils eux-mêmes (`pkg`,
  `tar`, `xz`, `sdkmanager`) via le port `CommandRunner` — enveloppe de
  `NativeProcessLauncher` avec capture intégrale stdout/stderr (jamais
  jetée, ADR 0084), délai maximal et annulation. Plus aucun script
  d'orchestration généré : `EcrivainSdkAndroidCli`,
  `EcrivainCodeideEnvCli` et la partie installation d'`EcrivainProfilShell`
  sont supprimés (E6), de même que `VersionneurScriptsTerminal` si plus
  rien ne l'utilise.
- **Le terminal n'est plus le moteur** : plus de frappe de `codeide-env`
  dans un pty (suppression de `ConfigurationEnvTermux` et du port
  `ConfigurationEnvTerminal`). Le journal en direct est une **vue en
  lecture seule** alimentée par le flux de lignes du `CommandRunner`
  (`EnvironmentSetupOrchestrator.journal`, borné). Les commandes
  utilisateur `codeide-env` et `android-sdk` disparaissent ; un petit
  utilitaire `codeide` de **diagnostic en lecture seule** (aucune logique
  d'installation) est jugé utile et livré en E6 — décision réexaminée alors.

### 4. Ports du domaine (`core:domain`, JVM pur)

- `EnvironmentSetupOrchestrator` : `state`, `journal`, `run(from)` (reprend
  à la première phase non vérifiée), `cancel()`, `verify(deep)`:
  `VerificationReport`, `repair(phase)` (répare un composant/une phase sans
  toucher au reste), `acceptSdkLicense()`.
- `InstallStep` : une étape d'une phase — `execute` + `verify` — reçoit un
  `StepContext` (accès aux autres ports, émission de progression et de
  lignes de journal).
- `CommandRunner` : `run(CommandSpec): CommandResult` — programme exécuté
  **sans shell**, environnement explicite (12.4), sortie intégrale, code.
- `DownloadManager` : téléchargement adressé par SHA-256 dans
  `filesDir/cache/downloads`, reprise HTTP `Range`, progression en octets,
  cache (fichier présent + somme correcte = **zéro** nouveau téléchargement),
  sources ordonnées du manifeste (miroirs essayés dans l'ordre, journalisés).
- `ArchiveExtractor` : extraction `.tar.xz` préservant bits d'exécution et
  liens symboliques (via `tar`/`xz` du bootstrap — ADR 0084 R4), garde
  anti-traversée, racine contrôlée.
- `ToolManifestClient` : récupération du manifeste v2 (ADR 0086).
- `InstallStateStore` : persistance `install-state.json` (schéma versionné).

`core:bootstrap` héberge les implémentations : une classe par phase
(`BootstrapPhase`, `PackageToolsPhase`, `JavaPhase`, `AndroidSdkPhase`),
`ToolchainVerifier`, `ManifestClient`, `DownloadManager`, et le
`GradleUserConfigWriter` (§ 6 du cahier : bloc géré et délimité dans
`$GRADLE_USER_HOME/gradle.properties` avec
`android.aapt2FromMavenOverride=<chemin du plan>`, réécriture idempotente,
lignes utilisateur intactes). Dépendances inchangées : `core:domain` seul
(si le module grossit, découpage par ADR ultérieur).

### 5. Exécution : service de premier plan, orchestrateur singleton de processus

- Un **service Android de premier plan** héberge l'orchestrateur :
  notification avec progression et action Annuler ; l'installation survit à
  la sortie de l'écran et à la mort de l'Activity. L'orchestrateur est un
  **singleton Hilt** (portée processus) — le service n'est qu'un hôte, la
  rotation ne touche pas au pipeline.
- `targetSdk` = 28 (ADR 0045, inchangé) : **aucun type de service en
  premier plan n'est imposé par la plateforme** avant la cible 29 ; le
  service existant `specialUse` du terminal (ADR 0035) fournit le précédent
  — le service d'installation déclare `foregroundServiceType="specialUse"`
  avec sous-type documenté (« exécute l'installation de l'environnement de
  développement »). **Non vérifié sur appareil** (comportement Android 15
  avec cible 28 : notifications auto-accordées, constaté ADR 0045 § 45).

### 6. Migration et adoption (détaillées en E6)

Les installations existantes (marqueurs `.codeide-installation-terminee`,
`codeide-env.terminee`, dossier `home/android-sdk`) sont **adoptées sans
retélécharger** si leur vérification fonctionnelle passe ; sinon, réparation
du seul composant fautif. Trois scénarios testés : appareil ancien complet,
appareil neuf, appareil à moitié installé.

## Options écartées

- **Garder `codeide-env` comme orchestrateur shell** (ADR 0083) : non
  testable en unité, orchestration par frappe de pty fragile (attente 1,2 s,
  pas de code de retour), état déduit du disque — les trois causes racines
  des pannes muettes.
- **Prolonger `BootstrapInstaller`** avec de nouvelles étapes : son état
  (`EtatInstallationBootstrap`) ne distingue pas vérifié/non vérifié par
  phase, son journal n'est pas attaché aux échecs, et sa garde « une seule
  installation » empêche la reprise granulaire par composant.
- **Extraction par bibliothèque Java (Apache Commons Compress, zip4j)** :
  perd bits d'exécution et liens symboliques sans gymnastique (le piège
  historique d'`Aapt2Deployeur`, ADR 0084 R4) et ajouterait une dépendance
  (interdit sans ADR) ; `tar`/`xz` du bootstrap, installés en phase 2, font
  le travail nativement.
- **Téléchargement par `curl` du bootstrap** (comme le fait le script
  actuel) : impossible avant la phase 2 (curl n'existe pas encore en phase
  1) et sans progression structurée ni reprise `Range` contrôlables depuis
  Kotlin ; `HttpURLConnection` (éprouvé par `TelechargeurBootstrap`) est
  conservé, prolongé en `DownloadManager` avec cache SHA-256.

## Conséquences

- La machine d'états (transitions, annulation, reprise, `Degraded`) est
  testable en unité avec `FakeCommandRunner`/`FakeNativeProcessLauncher` —
  couverture Kover ≥ 80 % sur `core:domain` et `core:bootstrap`.
- `ToolchainState` poussé aux écrans (ADR 0068) est remplacé par l'état de
  l'orchestrateur ; les consommateurs (bandeau accueil, onboarding, garde
  JDK de l'éditeur) sont rebranchés en E5/E6.
- Le compteur de téléchargements (un test voit exactement **1** par artefact,
  y compris après reprise) devient un invariant testé — le double
  téléchargement cmdline-tools (ADR 0082) est structurellement impossible.
- L'empreinte de la chaîne d'outils (hachage de `JAVA_HOME`,
  `ANDROID_HOME`, chemin et version d'`aapt2`, versions) déclenche la
  relance du daemon Gradle (`tooling:daemon`) — test à l'appui (E4).
