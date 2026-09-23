# ADR 0033 — Installateur du bootstrap : pipeline coroutine et états partagés

- **Statut** : accepté (étape 20 = Terminal T2, v0.21.0)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « Terminal intégré et bootstrap natif »,
  sections 3.3, 3.4 et 3.5 ; ADR 0032 (localisation et environnement).

## Décision

L'installation du bootstrap natif est un **pipeline coroutine à état
partagé**, porté par `core:bootstrap` :

1. `NativeProcessLauncher` / `ManagedProcess` deviennent des **ports de
   `core:domain`** (périmètre exact de la section 2.2 du prompt) : le
   lanceur construit l'environnement du processus via
   `ProcessEnvironmentProvider` — source unique de vérité, jamais
   dupliquée — et expose stdout/stderr en `Flow` de lignes, l'attente de
   sortie par sondage annulable, la terminaison explicite. Le `pid` est
   lu par réflexion sur `java.lang.Process` (même approche que Termux,
   repli `-1` documenté).
2. `BootstrapInstaller` est également un **port du domaine** : la
   progression est un `StateFlow<EtatInstallationBootstrap>` (types dans
   `core:model`, comme `CreationProgress`), consommable simultanément
   par l'étape « Terminal » de l'onboarding et par le déclenchement à la
   demande (étape T3) — **une seule installation peut courir**, un
   `demarrer()` pendant `EnCours` ou après `Terminee` est sans effet.
3. Le pipeline rejoue de zéro à chaque démarrage : vérification d'espace
   disque (seuil 1 Gio) et d'**architecture** (seul `aarch64` est publié
   — erreur typée plutôt que téléchargement inutilisable), téléchargement
   `HttpURLConnection` avec progression et **vérification de l'empreinte
   SHA-256 publiée**, extraction vers `usr-staging` (fichiers réguliers,
   permissions `0700` sur `bin/`, `libexec`, assistants `apt` et script
   de second stage — chemin **constaté dans l'archive réelle** :
   `etc/termux/termux-bootstrap/second-stage/…`), liens symboliques du
   manifeste `SYMLINKS.txt` (séparateur « ← », lecture **sans fermer**
   le flux zip), bascule atomique (tout préfixe existant est détruit :
   une reprise rejoue aussi le second stage, dont le verrou vit sous le
   préfixe), second stage via le lanceur canonique, écriture atomique du
   `sources.list` **avec `[trusted=yes]`** (la ligne embarquée par
   l'archive en est dépourvue — constat du 2026-09-23 — toute URL
   antérieure est corrigée), `apt update` puis installation des paquets
   **un par un**.
4. `Aapt2Deployeur` déploie le binaire cross-compilé depuis les **assets
   de l'application** vers `$PREFIX/bin` (port `BootstrapAssetsSource`
   du domaine, implémentation `AssetManager` dans `app` au branchement
   T3) — l'absence actuelle de l'asset est une erreur **typée**
   (`AssetAbsent`), signalée, non contournée.
5. Les échecs sont typés `AppError.Bootstrap` (réseau, espace disque,
   archive corrompue, empreinte, permission, second stage, `apt`,
   asset, architecture) ; les traducteurs d'`AppError` existants
   (accueil, wizard, diagnostic) reçoivent la nouvelle branche.

## Options écartées

- **Callback d'avancement** (style Termux historique) : le prompt exige
  `Flow` ; un `StateFlow` partagé en plus rend l'installation observable
  depuis deux points d'entrée sans coordination côté UI.
- **Installation en un seul `apt install` groupé** : un paquet absent
  ferait échouer l'ensemble ; l'installation paquet par paquet rapporte
  l'état réel de chaque outil (`OutilResume`) — nécessaire tant que le
  dépôt APT n'expose pas `gradle` ni `android-sdk`.
- **`readLines()` de la bibliothèque standard** pour `SYMLINKS.txt` :
  la fonction **ferme** le flux sous-jacent (`use`) et casse
  `getNextEntry()` — lecture ligne à ligne sans fermeture, comme
  l'installateur Termux.
- **Nettoyage suspendu dans le gestionnaire d'annulation** : un
  `withContext` (même `NonCancellable`) appelé depuis une coroutine déjà
  annulée **ne revient pas** (constat empirique, kotlinx-coroutines
  1.11) — l'état `Annulee` n'était jamais publié. Le nettoyage du
  staging y est délibérément synchrone.

## Conséquences

- La permission `INTERNET` n'est pas encore ajoutée : le module n'est
  pas branché à `app` (c'est l'objet de T3, avec son ADR dédié).
- Paquets constatés dans le dépôt APT à cette date : `openjdk-17`
  (17.0.20) et `git` ; **`gradle` et `android-sdk` sont absents** —
  signalé côté `jjoblab/codeide-packages` (tickets en attente), la
  configuration centrale (`ConfigurationBootstrap`) centralise la liste
  pour un ajout sans refonte.
- L'archive de bootstrap est épinglée par URL + empreinte
  (`bootstrap-2026.08.14-r3`) : une nouvelle release exige une mise à
  jour de la configuration (même discipline que les versions figées du
  catalogue de dépendances).
- `core:testing` fournit désormais `FakeToolchainLocator`,
  `FakeProcessEnvironmentProvider`, `FakeNativeProcessLauncher` (+ le
  processus scripté) et `FakeBootstrapInstaller`, exigés par le prompt
  (section 2.2) pour les ViewModel de T3.
