# ADR 0083 — Configuration automatique de l'environnement dans le terminal : `codeide-env`

- Statut : accepté (2026-10-05)
- Contexte : comportement demandé par l'utilisateur — « une fois que le
  bootstrap installé et `pkg update`, la configuration de l'environnement
  avec l'installation de java, android sdk, etc. » ; ajustements suivants :
  **git retiré** (« pas vraiment urgent ») et **journal live dans le
  TerminalView** (« pour un design plus cohérent »).

## Contexte

Jusqu'à la v0.51.0, la fin de l'installation de base (écran Installation)
laissait l'environnement INCOMPLET : la phase d'outils (`openjdk-17`,
`git`) exigeait un clic manuel — et le SDK Android, lui, exigeait que
l'utilisateur tape `android-sdk installer` lui-même dans le terminal. Le
parcours de premier lancement s'arrêtait donc à mi-chemin : un projet
Android ne compilait pas sans une suite de gestes que rien ne guidait.

Dans le même temps, l'écran d'installation possédait son PROPRE journal
de progression (un `TextView` monospace sous carte) — un deuxième
affichage de sorties de processus, à côté du VRAI terminal intégré
(ADR 0036, 0053) : incohérence de design et logique dupliquée.

## Décision

### 1. Une commande orchestrateur : `$PREFIX/bin/codeide-env`

Nouveau script VERSIONNÉ (VersionneurScriptsTerminal 6 → 7, posé par
[EcrivainCodeideEnvCli] comme les commandes `gradle` et `android-sdk`) :

1. **pré-vol** : bannière, espace disque (~1,5 Gio), sortie immédiate si
   l'environnement est déjà complet (idempotence) ;
2. **`pkg update`** (repli `apt`) — non fatal en échec ;
3. **OpenJDK 17** par le gestionnaire de paquets (dépôt
   `codeide-packages`) — sauté si un `java` fonctionnel existe déjà ;
   résolution de `JAVA_HOME` (`lib/jvm` du dépôt APT puis `opt/openjdk*`,
   le dernier valide gagne — même règle que `codeidesetup`) ;
   **git n'est PAS installé** (retrait demandé) ;
4. **SDK Android par DÉLÉGATION à `android-sdk installer`** (ADR 0082) :
   binaires de l'architecture depuis le manifeste `codeide-tools`
   (SHA-256), cmdline-tools rev 12.0, plateformes via `sdkmanager` —
   SOURCE DE VÉRITÉ UNIQUE, jamais de duplication du shell
   d'installation ;
5. **pont d'environnement** : `JAVA_HOME` et `ANDROID_SDK_ROOT` posés
   dans `$PREFIX/etc/ide-environment.properties` (upsert, les autres
   lignes conservées — même contrat que `codeidesetup`, honoré par le
   profil shell depuis la v0.51.0) ;
6. **vérifications** (`java -version`, `aapt2`, `sdkmanager`,
   plateformes) puis **marqueur** `$PREFIX/etc/codeide-env.terminee`.

Sous-commandes : `codeide-env` (configurer, idempotent), `statut`,
`refaire` (supprime le SDK puis reconfigure), `aide`. POSIX sh strict
(dash) ; interruptions reprises sans dommage (chaque étape est
idempotente). Le dépôt `codeide-tools` reste INCHANGÉ : source des
binaires (manifeste + releases) et voie autonome (`codeidesetup`) sans
l'application.

### 2. Le TerminalView EST le journal live

La configuration s'exécute dans une **vraie session de terminal** :
l'application crée une session étiquetée « Configuration » et y **écrit
la commande** (`TerminalSession.write` — la voie du clavier logiciel :
écho, historique readline, Ctrl+C fonctionnent). Aucun écran de
progression parallèle : le rendu live est le TerminalView, couleurs ANSI
comprises. À la fin du script l'invite revient — la session reste un
shell normal, réutilisable.

Mécanique (`core:terminal-runtime`, ADR 0035 inchangé) :

- `TerminalSessionRepository.envoyerTexte(sessionId, texte)` (NOUVEAU,
  port du domaine) : envoi « comme si tapé », sans effet sur une session
  inconnue ou fermée ;
- `ConfigurationEnvTerminal` (NOUVEAU port du domaine, implémenté par
  `ConfigurationEnvTermux`) : `estComplet()` (lu sur le disque via
  `ToolchainLocator` — une installation manuelle compte autant) et
  `lancer()` — crée la session (HOME du shell, étiquetée), attend la
  pose du shell (~1,2 s, l'entrée du pty tamponne de toute façon), y
  tape `codeide-env`, et veille à **une seule session de configuration à
  la fois** (une session vivante est RETROUVÉE, jamais doublée ; une
  session morte + environnement incomplet = nouvelle session, la
  reprise repart où elle en était).

### 3. Déclenchement automatique à la fin de la base

L'écran Installation (`feature:install`) déclenche la configuration dès
l'état `Terminee` de la base — y compris l'état INITIAL (écran rouvert
sur un bootstrap installé mais un environnement incomplet : c'est la
voie de reprise). Un garde tient le déclenchement à UN par vie de
l'écran ; l'effet `OuvrirTerminal` bascule vers `TerminalActivity`
(`AppNavigator.openTerminal`). La complétude étant disque, un
environnement déjà prêt ne déclenche rien.

Le bouton « Ouvrir le terminal » (phase résultat) y revient à tout
moment ; `codeide-env` reste lançable à la main dans n'importe quelle
session. **Repli** : si la session ne peut pas être créée, l'ancienne
phase d'outils par paquets subsiste — réduite à `openjdk-17` seul
(`PAQUETS_OUTILS` perd `git`).

## Conséquences

- Premier lancement : bootstrap → `apt update` → **la configuration
  démarre seule** dans le terminal (Java puis SDK Android) —
  comportement demandé, atteint sans nouvel écran.
- Le journal de la configuration vit dans le TerminalView : design
  cohérent, plus de double affichage ; l'écran Installation garde SON
  journal pour la seule phase de base (qui précède l'existence du
  shell — impossible à exécuter dans le terminal).
- `git` n'est plus installé automatiquement (`pkg install git` à la
  demande) ; l'option `-g` de `codeidesetup` (voie autonome) demeure.
- Les appareils v0.37.3+ reçoivent la commande au démarrage suivant
  (versionneur 6 → 7), SANS réinstallation du bootstrap ; ceux dont le
  JDK/SDK est déjà complet ne voient aucun changement de comportement.
- Le seuil d'espace disque du script (~1,5 Gio) est surchargeable
  (`CODEIDE_ESPACE_REQUIS_KO`) ; le paquet JDK aussi (`CODEIDE_JDK`).
- Le banc d'essai dash (5 scénarios : installation, idempotence,
  statut, reprise JDK seul, `refaire`) vit dans le test
  `EcrivainCodeideEnvCliTest` — exécution réelle du script avec `pkg`
  et `android-sdk` factices, PATH sanitisé (le java du poste hôte ne
  doit pas court-circuiter l'étape OpenJDK).

## Références

- ADR 0082 (binaires du SDK depuis `codeide-tools`, commande
  `android-sdk`), ADR 0048 (base vs outils, `apt update` obligatoire),
  ADR 0035/0036/0053 (sessions de terminal, écran, tiroir), ADR 0033
  (pipeline coroutine de l'installation).
- `EcrivainCodeideEnvCli` (`core:bootstrap`), `ConfigurationEnvTermux`
  et `envoyerTexte` (`core:terminal-runtime`), déclenchement
  `feature:install` — v0.52.0.
