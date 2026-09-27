# ADR 0065 — Tâches au fil du build, étapes de sync et écran de configuration du tooling

Date : 2026-09-27 · Étape : v0.36.0 (retour utilisateur sur le tooling muet)

## Contexte

Le serveur Gradle émettait déjà `TaskStarted`/`TaskFinished` pour chaque tâche
(`ProgressBridge`, branché sur la vraie Tooling API, `taskPath` compris —
ex. `:app:compileDebugKotlin`). Ces événements traversaient le protocole
jusqu'au client… et étaient **jetés silencieusement** dans
`GradleApiImpl.pomper` (`is TaskStarted, is TaskFinished, is ProgressEvent -> Unit`,
commenté « G5 affine s'il expose les tâches à l'UI »). Concrètement : pendant
un build, la console n'affichait que la sortie brute stdout/stderr et une
ligne de statut générale — **aucune ligne « Tâche :app:xxx » n'apparaissait
jamais**, alors que l'information existait bout en bout.

Deuxième trou, plus profond : le protocole n'avait rien d'équivalent pour la
**synchronisation** — seulement `SyncStarted`/`SyncResult`/`PartialSyncResult`.
Même en réparant le premier trou, la sync restait une boîte noire
« en cours / terminée » alors que sa phase la plus longue (première connexion :
téléchargement de la distribution, démarrage du daemon) est précisément celle
où l'utilisateur a besoin de savoir que quelque chose vit.

Point mineur corrigeé au passage : aucun `--console=plain` n'était passé au
build — la sortie texte reposait sur la détection automatique de Gradle
(pas de TTY), qui fonctionne généralement mais vaut mieux explicite
qu'implicite.

Enfin, l'utilisateur demande « un écran pour configurer le tooling accessible
depuis l'écran consoleview (design propre) » : rien de tel n'existait, et
aucune préférence utilisateur ne pilotait le tooling.

## Décision

1. **Protocole v3** (négociation exacte au handshake inchangée — app et
   orchestrateur sont livrés ensemble, un désaccord se voit au handshake,
   jamais au milieu d'un build) :
   - nouveau `SyncProgress` (`projectDir`, `phase` énumérée `CONNEXION` /
     `MODELE_GRADLE` / `MODELE_IDEA`, `terminee`, `dureeMs`) — annoncé DEUX
     fois par phase (départ, puis fin avec durée) ;
   - `TaskFinished` enrichi de `durationMs` (mesurée par l'opération Gradle :
     `endTime - startTime`, même source de vérité que `BuildFinished`) et
     `skipped` (une tâche sautée n'est ni un échec ni un travail réel) ;
   - champs à défauts : compatibles avec les frames v2 (`ignoreUnknownKeys`
     + valeurs par défaut), fichiers dorés régénérés.
2. **Serveur** : `SyncHandler` annonce chaque phase entre `SyncStarted` et le
   résultat (la connexion est hoistée AVANT les modèles — elle porte sa
   propre phase, et son échec sec évite deux « modèles non résolus » qui ne
   disent pas la cause) ; `BuildHandler` passe `--console=plain` en
   DERNIER argument (l'occurrence finale d'une option Gradle gagne : un
   client qui passerait son propre `--console` resterait maître).
3. **Client** : le trou est réparé — `observeTachesBuild(buildId)` (canal
   borné par build, tamponné AVANT le lancement, fermé à la fin du build,
   mêmes garanties que la sortie) et `observeSyncProgress()` (canal unique,
   jamais conflaté : l'ordre départ/fin est l'information) ; `build()` porte
   les arguments Gradle supplémentaires.
4. **Console (feature:editor)** : les lignes deviennent TYPIÉES
   (`LigneConsole` scellée : `Sortie` / `Tache` / `Etape`) avec identité
   stable — une tâche s'affiche à son départ et sa fin la met à jour EN
   PLACE (statut + durée, comme la vue Build d'Android Studio : une ligne
   par tâche, jamais de défilé bavard) ; les étapes de sync se concluent en
   place avec leur durée ; l'avertissement bénin du daemon Gradle (C5)
   voyage « apaisé » (style informatif, pas rouge d'erreur). Les libellés
   sont LOCALISÉS par le rendu — l'état reste pur.
5. **Écran de configuration** (`DialogueConfigToolingFragment`, plein écran
   AU-DESSUS de l'espace de travail via `Theme.CodeIDE.PleinEcran`, ouvert
   par l'engrenage de l'onglet Sortie — on ne quitte jamais le contexte du
   build, contrairement à une navigation vers les Paramètres) :
   - interrupteur « afficher les tâches » (filtrage EN VOL, relu à chaque
     événement) ;
   - interrupteur mode hors ligne (`--offline`) + champ d'arguments Gradle
     libres (persistés à la fin de saisie, jamais par frappe) ;
   - état vivant de l'orchestrateur (connexion + tas) en lecture seule ;
   - réglages persistés à l'INSTANT dans DataStore (même contrat que les
     sections des Paramètres, ADR 0059/0064 : rendu idempotent piloté par
     le DataStore, drapeau anti-fausses actions).
   La consommation passe par `OptionsTooling` (patron `PorteurStyleCurseur` :
   un détenteur process-wide, un aperçu instantané).

## Alternatives écartées

- **`ProgressEvent` textuel pour la sync** : des libellés localisés côté
  serveur, ou pire non localisés — l'état voyage STRUCTURÉ, les libellés
  appartiennent à l'UI.
- **Horloge client pour la durée des tâches** : approximative (dérive de
  transport) ; la Tooling API fournit `startTime`/`endTime` — la durée est
  MESURÉE côté serveur, comme `BuildFinished.durationMs`.
- **Rafraîchir les lignes dans une liste d'états séparée** : la console est
  la vue unique bornée du tooling (ADR 0041) — les tâches/étapes y vivent
  comme des lignes à part entière, balisées de leur canal.
- **Section des Paramètres pour la configuration** : quitter l'éditeur pour
  régler le build qu'on regarde contredit le contexte ; le dialogue plein
  écran reste DANS l'espace de travail.

## Conséquences

- Le protocole passe en v3 : un orchestrateur v2 refusera de parler à une
  app v3 (message clair au handshake) — voulu, jamais de décodage raté.
- La console affiche enfin CE que Gradle fait, la sync déroule ses phases.
- `--console=plain` est garanti pour tout client du protocole (présent et
  futur).
- Les tests couvrent le dispatch réparé (client), les phases (intégration
  serveur sur vraies fixtures), les mises à jour en place et le filtrage
  (GradleService/ViewModel), l'écran de configuration (ViewModel + layout
  Robolectric), et les fichiers dorés v3 (protocole).
