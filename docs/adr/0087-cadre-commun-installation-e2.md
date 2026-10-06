# ADR 0087 — Cadre commun d'exécution : orchestrateur concret, CommandRunner, DownloadManager, état persisté, service de premier plan (E2)

- **Statut** : accepté (étape E2 de la refonte ; met en œuvre l'ADR 0085
  dans `core:bootstrap`, sous-package `installation/`)
- **Contexte** : cahier des charges section 11, étape E2 — « cadre commun
  (orchestrateur, `CommandRunner`, `DownloadManager`, état persisté,
  service de premier plan) + phases 1 et 2 ». Les ports sont posés par E1
  (`EnvironmentSetup.kt`, ADR 0085 § 4) ; l'ancien parcours
  (`InstallateurBootstrap`, ADR 0083) reste actif jusqu'à E6 — le nouveau
  cadre coexiste, aucune UI ne le déclenche avant E5.

## Décision

### 1. Orchestrateur concret (`OrchestrateurInstallation`)

- Singleton Hilt (portée processus, ADR 0085 § 5) implémentant
  `EnvironmentSetupOrchestrator`. Le pipeline s'exécute dans un **scope
  interne** (`SupervisorJob + dispatchers.default`) : il survit à la mort
  de la coroutine appelante — `run()` lance puis attend (`join`) la fin ;
  l'annulation de l'appelant n'interrompt **pas** l'installation, seule
  `cancel()` l'arrête.
- **Reprise « verify-first »** : avant d'exécuter une étape
  (`InstallStep.execute`), l'orchestrateur demande `verify()` — déjà
  vérifiée, l'étape est sautée et journalisée comme telle. C'est le
  mécanisme unique de la reprise (§ 3.5 : ne pas retoucher au vérifié),
  de l'idempotence et de la **réparation ciblée** : `repair(phase)` =
  `run(from = phase)` — la vérification détecte l'écart, seule l'étape
  fautive est rejouée (critère d'acceptation § 13).
- Après `execute`, `verify()` est rejoué : « installé » = « vérifié en
  l'exécutant » (§ 3.2) — un échec de vérification post-exécution échoue
  la phase avec le diagnostic réel.
- Une phase = une liste d'étapes + un recensement des versions
  (`PhaseInstallation`, interface interne : `etapes()`,
  `recenserVersions(context)`) ; l'ordre vient de `InstallPhase.entries`,
  le parcours s'arrête à la première phase non livrée (E2 : `BOOTSTRAP`,
  `PACKAGE_TOOLS` ; E3 ajoutera `JAVA`, E4 `ANDROID_SDK`).
- `ANDROID_SDK` sans licence acceptée (§ 12.5) : le parcours
  **s'arrête proprement** avant cette phase (journal explicite), elle
  n'est pas marquée `Failed` — l'acceptation est un geste utilisateur,
  pas un défaut d'installation.
- **Annulation** : `cancel()` annule le job du pipeline ; dans le
  gestionnaire de `CancellationException` (leçon v0.55.0 :
  un appel suspendu depuis une coroutine annulée ne revient pas), la
  publication de `Failed(Annulation)` est **synchrone** et la
  persistance part dans une coroutine **fraîche** du scope interne —
  jamais dans la coroutine mourante.
- **Échecs d'étape** : la signature du port (`execute` retourne `Unit`)
  impose un véhicule : `EchecEtapeInstallation(erreur:
  AppError.EnvironmentSetup)`, exception locale attrapée **uniquement**
  par l'orchestrateur et convertie en `PhaseState.Failed` avec
  `logTail` (200 dernières lignes du journal, expurgées par
  `LogRedactor`). Les vérifications `verify()` qui échouent produisent
  la même erreur typée (`Commande`, sortie à l'appui).
- **verify(deep)** : rejoue les `verify()` de toutes les étapes des
  phases livrées et retourne le rapport. La vérification
  **approfondie** (projet généré + `assembleDebug` réel) exige la phase
  `ANDROID_SDK` : livrée en E4 ; E2 journalise explicitement cette
  limite quand `deep = true` — aucun repli silencieux.
- **Persistance** : à chaque transition **stable** (`Succeeded`,
  `Degraded`, `Failed`, annulation comprise) et à `acceptSdkLicense()`.
  Les états `Running` ne sont jamais écrits ; la normalisation
  lecture (`Running` → `NotStarted`) reste en défense profonde.

### 2. `CommandRunnerProcessus` (port `CommandRunner`)

- Enveloppe de `NativeProcessLauncher` (ADR 0085 § 3) : `CommandSpec`
  → `launch(programme + arguments, extraEnv, workingDir)`. Jamais de
  shell : `sh -c "…"` reste légitime (le programme exécuté est `sh`).
- **Capture intégrale** stdout **et** stderr (l'ancien
  `SupervisionProcessus` ne gardait que 5 lignes de stderr — le contrat
  « aucune sortie jetée » exige tout) : drainage parallèle de deux flux
  froids + `awaitExit` (leçon v0.55.0 : un tuyau non lu sature).
- Délai maximal : `withTimeoutOrNull` ; au timeout, `kill(force)` puis
  retour `CommandResult(timedOut = true)`. Le champ `timedOut` est une
  extension **additive** du port E1 (`false` par défaut) — sans lui, un
  timeout est indiscernable d'un échec banal.
- Échec de lancement (`IOException`, W^X ADR 0045) : l'exception monte
  telle quelle — c'est l'étape appelante qui la traduit en
  `EnvironmentSetup(Permissions)`.

### 3. `GestionnaireTelechargement` (port `DownloadManager`)

- `HttpURLConnection` éprouvé par `TelechargeurBootstrap` (ADR 0085,
  option retenue) : suivi des redirections, délais 30 s/60 s, tampon
  8 Kio, SHA-256 calculé au fil de l'eau, hexadécimal **indépendant de
  la locale**.
- **Cache adressé par SHA-256** (`filesDir/cache/downloads/<sha256>`) :
  fichier présent + somme correcte → restitution immédiate, zéro
  requête réseau — l'invariant « un composant = une version résolue =
  un téléchargement » (§ 3.1) devient testable par compteur de
  requêtes.
- **Sources ordonnées** : primaire puis miroirs, chaque essai
  journalisé — jamais de repli silencieux (§ 3.3).
- **Reprise `Range`** : téléchargement partiel conservé en `<sha256>.part` ;
  reprise par `Range: bytes=<n>-` si le serveur répond 206 ; une
  réponse 200 redémarre proprement le téléchargement (serveur sans
  support Range). Somme incorrecte à la fin → fichier supprimé,
  `EnvironmentSetup(SommeControle)`.
- `sizeBytes = 0` signifie « inconnue » : la progression utilise alors
  `Content-Length` si le serveur la fournit (l'archive du bootstrap
  n'a pas de taille déclarée dans `ConfigurationBootstrap`).

### 4. `MagasinEtatInstallation` (port `InstallStateStore`)

- `install-state.json` sérialisé avec **`org.json`** (précédent maison
  `CrashReportFileStore`, ADR 0010 : aucune dépendance nouvelle, aucune
  modification du graphe de plugins de `core:model`/`core:domain` que
  exigerait kotlinx-serialization pour annoter le modèle E1).
- Écriture **atomique** (fichier temporaire + renommage) — un état à
  moitié écrit corromprait la reprise.
- `schemaVersion` ≠ 1, JSON illisible ou champ requis absent → `load()`
  retourne `null` : le parcours repart de zéro, jamais d'état déduit du
  disque (ADR 0085 § 2).
- Seuls les états stables sont écrits ; `Running` est normalisé
  `NotStarted` à l'écriture **et** à la lecture.

### 5. `ClientManifesteOutils` (port `ToolManifestClient`)

- Transport minimal livré avec le cadre (le `StepContext` l'expose) :
  `GET` de l'URL **unique** du catalogue (§ 12.7), analyse `org.json`
  vers `ToolManifest` (E1), validation `schemaVersion = 2` et champs
  requis — tout champ requis absent rend le manifeste invalide.
- E4 le consolidera : vecteurs de référence publiés par `codeide-tools`
  (§ 12.7), consommation par la phase 4. Aucune URL de composant n'est
  lue ici — seul le catalogue parle d'URL.

### 6. Extraction : `ExtracteurArchivesBootstrap` (port `ArchiveExtractor`)

- E2 extrait l'archive **zip** du bootstrap (phase 1) : délégation à
  l'algorithme éprouvé d'`ExtracteurBootstrap` (garde anti-traversée,
  `SYMLINKS.txt` au séparateur « ← », bits d'exécution sélectifs,
  création de `tmp/`, bascule atomique `usr-staging` → `usr`) — le
  KDoc du port est élargi : l'artefact de la phase 1 est un zip, le
  `.tar.xz` du manifeste v2 (extraction par `tar`/`xz` du bootstrap,
  § 12.3) arrive en E4 avec la même interface.
- Les `EchecBootstrap` de l'ancien code sont traduits
  `AppError.EnvironmentSetup` à la frontière de l'étape (tableau de
  correspondance ci-dessous).

### 7. Phases 1 et 2 (`InstallStep` concrets)

**Phase `BOOTSTRAP`** (reprise de la logique éprouvée, § 5.1) :

| Étape | execute | verify |
|---|---|---|
| `prealables` | espace ≥ 1 Gio (seuil du catalogue) + architecture `aarch64` | idem |
| `telechargement` | `DownloadManager` (URL + empreinte de `ConfigurationBootstrap`) | fichier en cache, somme correcte |
| `extraction` | zip → `usr-staging` (progression, liens, `tmp/`) | `bin/sh`, `bin/bash`, script du second stage présents |
| `bascule` | bascule atomique staging → `usr` | `bin/sh` présent dans le préfixe |
| `second-stage` | relance **systématique** du script (verrou interne du script termux = idempotence ; postinst Debian re-exécutables) — non vérifié sur appareil | relance, code de retour 0 |
| `configuration-apt` | `sources.list` canonique + guérison des 4 répertoires APT (`ConfigurateurApt`) | contenu conforme + répertoires présents |
| `verification` | `sh -c 'echo ok'` → `ok` ; `apt --version` ; `pkg help` via `sh` (le shebang n'est pas garanti exécutable, l'exécution via `sh` explicite est éprouvée) | idem |
| `marqueur` | pose `.codeide-installation-terminee` | marqueur présent |

Versions recensées : `bootstrap` (release `ConfigurationBootstrap`),
`apt` (`apt --version`, première ligne).

**Phase `PACKAGE_TOOLS`** (§ 5.2) :

| Étape | execute | verify |
|---|---|---|
| `mise-a-jour` | rounds `[pkg update × 3, apt update × 1]`, délai croissant injectable avant chaque relance, chaque essai journalisé ; échec final → `Failed(Reseau)` avec message réseau/miroir explicite | `pkg update` rejoué, code 0 |
| `<paquet>` (un step par paquet du catalogue, dans l'ordre) | `pkg install -y <paquet>` | l'outil **exécuté** : `curl --version`, `tar --version`, `xz --version`, `unzip -v` ; `ca-certificates` vérifié par `dpkg -l` (paquet de certificats : pas de binaire à exécuter) |

Versions recensées : un `--version` par outil (première ligne).

**Correspondance d'erreurs** (`EchecBootstrap`/conditions →
`EnvironmentSetupReason`) :

| Condition | Raison |
|---|---|
| réseau indisponible, miroirs épuisés, HTTP non 2xx persistant | `Reseau` |
| `ENOSPC`/`No space`, seuil plancher | `EspaceDisque` |
| SHA-256 divergente | `SommeControle` |
| code de retour non nul, timeout (`timedOut`) | `Commande` (sortie à l'appui) |
| `IOException` au lancement (W^X), `EACCES` | `Permissions` |
| annulation | `Annulation` |
| archive structurellement invalide malgré une somme correcte, manifeste invalide | `ManifesteInvalide` |
| ABI ≠ `arm64-v8a` | **`ArchitectureNonSupportee` (9ᵉ raison, ajoutée au modèle E1)** — aucune des 8 raisons ne décrit honnêtement ce refus ; l'ajout est purement additif (les traducteurs UI branchent sur le **type**, pas sur la raison — vérifié) |

### 8. Service de premier plan (`ServiceInstallationEnvironnement`)

- Dans `core:bootstrap` (androidx autorisé — seule `androidx.core` est
  ajoutée pour `NotificationCompat`) : précédent `TerminalService`
  (ADR 0035) — manifeste du module, permissions
  `FOREGROUND_SERVICE(_SPECIAL_USE)` + `POST_NOTIFICATIONS`,
  `foregroundServiceType="specialUse"` avec sous-type documenté
  (« exécute l'installation de l'environnement de développement »),
  `targetSdk` 28 (ADR 0045 : aucun type imposé, notifications
  auto-accordées — comportement Android 15 **non vérifié sur appareil**).
- `startForeground` **immédiat** (règle des 5 s), `START_STICKY` ;
  une reprise après mort du processus constate `running = null` (état
  relu normalisé) et s'arrête proprement.
- **Décision pure** `DecisionNotificationInstallation` : état →
  `Notifier(titrePhase, texte, progression)` tant que `running != null`,
  `Arreter` sinon. L'action **Annuler** (première du dépôt) :
  `NotificationCompat.Action` + `PendingIntent` service
  (`FLAG_IMMUTABLE`) → `onStartCommand` `ACTION_ANNULER` →
  `cancel()` de l'orchestrateur.
- Démarrage via port interne `DemarreurServiceInstallation`
  (`DemarreurServiceAndroid` : `startForegroundService`) appelé par
  l'orchestrateur au début du pipeline — l'annulation de l'appelant ne
  tue pas l'installation, le service la porte (§ 4 du cahier).
  **En E2, personne ne déclenche `run()` en production** : le service
  est livré et testé (Robolectric), branché à l'UI en E5.
- Restriction Android 12+ (`ForegroundServiceStartNotAllowedException`)
  : applicable aux applications ciblant 31+ ; cible 28 → non
  concernée (non vérifié sur appareil Android 15).

### 9. Journal

- `EnvironmentSetupOrchestrator.journal` (port) : `StateFlow<List<String>>`
  borné à 200 lignes, alimenté par les transitions d'étapes **et**
  chaque ligne stdout/stderr via un décorateur `CommandRunnerJournalise`
  du `StepContext` (la journalisation est une responsabilité du
  contexte, pas du runner).
- Journal **fichier** dédié `install` : `AppLogger` (tag `install`,
  rotation et bornes existantes d'`core:logging`, ADR 0009) — pas de
  nouvelle infrastructure. `LogRedactor` appliqué à chaque ligne
  publiée (règle d'expurgation d'`AGENTS.md`).

## Options écartées

- **kotlinx-serialization pour `install-state.json`** : exigerait
  d'annoter le modèle E1 (`AppError`, `PhaseState`…) et d'appliquer le
  plugin aux modules purs pour un seul fichier — `org.json` éprouvé
  dans `core:crash` fait le travail sans toucher au build.
- **`run()` exécutant dans la coroutine appelante** : l'installation
  mourrait avec le `viewModelScope` de l'appelant (E5) — contredit la
  survie hors écran (§ 4).
- **Persistance à chaque émission de progression** : écraser le disque
  à chaque octet reçu n'apporte rien — seules les transitions stables
  comptent pour la reprise (`Running` rejoué de toute façon).
- **Un état `WaitingLicense` dédié** : la phase `ANDROID_SDK` non
  démarrée **est** `NotStarted` ; un état supplémentaire dupliquerait
  l'information portée par `sdkLicenseAcceptedAtMillis`.
- **Timeout traduit en exception depuis `CommandRunner`** : le port
  retourne un résultat, pas une exception ; `timedOut` garde le
  diagnostic (sortie partielle) accessible à l'étape appelante.
- **`verify(deep = true)` qui échoue bruyamment avant E4** : la
  vérification approfondie n'a d'objet qu'avec la phase 4 ; E2
  retourne le rapport léger **et journalise la limite** — pas de
  repli silencieux, pas d'échec artificiel.

## Conséquences

- Le compteur de requêtes HTTP des tests du `GestionnaireTelechargement`
  fait du « zéro retéléchargement » un **invariant vérifié** (cache,
  reprise, double exécution).
- `InstallateurBootstrap` et le nouveau cadre coexistent jusqu'à E6
  (suppression) ; aucun nom ne collide (sous-package `installation/`).
- `EnvironmentSetupDomaineTest` (E1) vit dans
  `InstallPlanResolverTest.kt` — vérifié en E2, aucun doublon créé.
- `feature:install` ne dépend toujours que de `core:domain` (le port
  `EnvironmentSetupOrchestrator`) : aucune modification de
  `checkModuleDependencies` ni de `docs/ARCHITECTURE.md`.
- Kover ≥ 80 % sur `core:bootstrap` : les exclusions du code généré
  Hilt suivent le précédent `core:terminal-runtime`.
