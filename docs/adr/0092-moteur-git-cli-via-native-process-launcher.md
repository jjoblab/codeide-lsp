# ADR 0092 — Moteur Git : git CLI via NativeProcessLauncher

- Statut : accepté (2026-10-09)
- Contexte : mission Git G0 (prompt `prompt-agent-git-1.md`), section 2.

## Décision

Le moteur Git de CodeIDE est le **binaire `git` du bootstrap**, exécuté via
le port `NativeProcessLauncher` existant (`core:domain/ProcessusNatifs.kt`,
implémenté dans `core:bootstrap/LanceurProcessusNatifs.kt`). L'approche
JGit embarqué est **écartée** pour la v1.

## Justification

### Preuves relevées dans le code

1. **`git` est publiée dans le dépôt APT `codeide-packages`** mais
   **n'est pas embarquée** dans l'archive du bootstrap (`ConfigurationBootstrap.kt`
   L47 : `PAQUETS_OUTILS = listOf("openjdk-17")` — git retiré en v0.52.0,
   ADR 0083). L'utilisateur peut l'installer via `pkg install git`.

2. **`NativeProcessLauncher`** (`core:domain/ProcessusNatifs.kt`) offre
   déjà tout le nécessaire : `launch(command, extraEnv, workingDir)` →
   `ManagedProcess` avec `stdoutLines(): Flow<String>`, `stderrLines()`，
   `awaitExit(): Int` (annulable), `kill(force)` (SIGKILL). Cette
   infrastructure est utilisée pour `apt`, `pkg`, `aapt2`, `java`,
   `android-sdk` — aucune nouveau mécanisme d'exécution à créer.

3. **Le pont SAF → FUSE** (`ResoudreRepertoireProjet`, ADR 0038) produit
   un chemin réel `/storage/emulated/0/…` utilisable comme `workingDir`
   par `NativeProcessLauncher.launch()` — testé par 13 tests JVM, garde
   « répertoire fantôme » incluse.

4. **`targetSdk = 28`** (ADR 0045) autorise l'exécution de binaires
   natifs extraits dans `filesDir/usr` — le binaire `git` serait à
   `$PREFIX/bin/git`.

### Comparaison

| Critère | JGit embarqué | git CLI (choisi) |
|---|---|---|
| Taille APK | +5-8 Mo | 0 |
| Démarrage | ~300-500 ms (classes) | ~10-30 ms (natif) |
| Dépôt volumineux | Risque OOM (heap JVM) | Streaming natif, éprouvé |
| Annulation | Cooperative (`ProgressMonitor`) | `kill(force)` immédiat |
| Tests JVM | Faciles (JGit en JVM) | `FakeNativeProcessLauncher` (existant) |
| Disponibilité | Immédiate (embarqué) | Conditionnelle (`pkg install git`) |
| Cohérence | Nouvelle dépendance Eclipse | Même mécanisme que tout le tooling |

Le critère déterminant est la **cohérence architecturale** : tout le
tooling de CodeIDE (bootstrap, Gradle daemon, SDK installer) utilise
déjà `NativeProcessLauncher`. Ajouter JGit introduirait une double
source de vérité pour l'exécution de sous-processus.

### Risques mitigés

| Risque | Mitigation |
|---|---|
| `git` non installé | Détection `ToolchainLocator.gitBinary()` → UI propose `pkg install git` |
| Parsing sortie | `--porcelain`, `-z`, `--format=…` exclusivement (jamais human-readable) |
| Jeton HTTPS | `git -c http.extraHeader=…` ou `~/.git-credentials` (jamais en clair dans `ps`) |
| Volume démonté | `ResoudreRepertoireProjet` retourne `null` → opération refusée |

## Conséquences

- Nouveau port `MoteurGit` dans `core:domain` (interface pure JVM).
- Implémentation `MoteurGitCli` dans `core:bootstrap` (utilise
  `NativeProcessLauncher` + `ResoudreRepertoireProjet`).
- Fake `FakeMoteurGit` dans `core:testing` (pour les tests ViewModel).
- Aucune dépendance JGit dans `libs.versions.toml`.
- L'utilisateur doit installer `git` via `pkg install git` avant la
  première utilisation (même pattern que le bootstrap JDK).

## Évolution possible

Si un besoin hors-ligne strict ou une intégration LSP fine (diff
in-memory, blame par ligne) émerge, JGit pourra être ajouté en
complément — mais le coût (5-8 Mo APK, double moteur) ne se justifie
pas pour les opérations standard de la v1.

## Références

- `core/domain/src/main/kotlin/jo/codeide/core/domain/ProcessusNatifs.kt`
- `core/bootstrap/src/main/kotlin/jo/codeide/core/bootstrap/LanceurProcessusNatifs.kt`
- `core/domain/src/main/kotlin/jo/codeide/core/domain/ResoudreRepertoireProjet.kt`
- ADR 0038 (pont SAF → FUSE)
- ADR 0045 (targetSdk 28, exécution binaire)
- ADR 0083 (retrait de git du bootstrap automatique)
