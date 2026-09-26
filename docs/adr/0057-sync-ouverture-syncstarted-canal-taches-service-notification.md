# ADR 0057 — Sync à l'ouverture, SyncStarted du serveur, canal Taches et service de notification

- **Statut** : accepté (v0.33.0, étape 32 — tooling professionnel à la Android
  Studio)
- **Contexte** : retour utilisateur du 2026-09-26 sur la v0.32.5 : « Je veux
  que tu testes le tooling en situation réelle, pour voir si réellement il
  fait son travail, si chaque situation a bien son propre canal de
  diffusion — la sync, build, task, etc. Parce que dans l'application,
  lorsque l'utilisateur ouvre son projet le tooling se met immédiatement en
  marche pour synchroniser le projet (en forçant aussi une résolution des
  dépendances pour les classpaths, sources utilisées pour les
  fonctionnalités des LSP), assure-toi qu'il y a bien des informations
  diffusées depuis le serveur au client concernant la sync, de même pour
  build, task. Quand un sync, build, task commence et finit, pour que l'UI
  de l'application se mette à jour correctement, le gradleservice doit
  bien utiliser le service d'Android, mettre à jour correctement la
  notification — je veux que le tooling fonctionne de manière professionnelle
  comme le fait Android Studio. »
- **Vérification réelle conduite avant décision** (harnais
  `scripts/test_tooling_reel.py` hors dépôt, rejoué contre le JAR) :
  handshake validé, sync résolvant `GradleProject` + `IdeaProject` (donc
  configuration + dépendances/classpaths) en ~1,1 s sur le daemon Gradle
  réel, 32 tâches listées, build exécuté avec sortie ligne à ligne,
  ping/pong vivant, orchestrateur sortant seul (code 0). Les LACUNES
  constatées ont fondé les décisions ci-dessous : la sync était manuelle
  (bouton), le serveur ne diffusait RIEN au départ d'une sync (contrairement
  aux builds), le listage des tâches n'avait pas de canal, et aucune
  notification ne vivait pendant les activités.

## Décisions

### 1. Sync à l'ouverture du projet

`EditorViewModel` lance `synchroniserProjetGradle()` UNE fois, dès la
première connaissance du projet (`lancerSyncOuverture`) — comme l'ouverture
d'un projet dans Android Studio, la synchronisation part sans attendre un
geste. La résolution des modèles (`GradleProject` : tâches ; `IdeaProject` :
structure IDE, dépendances, classpaths) force la configuration du projet :
c'est le socle que les prompts LSP à venir consommeront (les sources des
dépendances restent leur périmètre). La garde JDK (ADR 0048) s'applique
d'abord : sans outils, le refus typé s'affiche dans le canal Sync dès
l'ouverture — un état actionnable, jamais une erreur opaque.

### 2. `SyncStarted` diffusé PAR le serveur

Nouvel événement du protocole (`sync_started`, 25e message — fichier doré
inclus) : `SyncHandler` l'émis AVANT la résolution des modèles, symétrique
du `BuildStarted` des builds. L'app ne présuppose plus son propre geste :
l'en-tête et la notification se posent sur un fait du serveur. Côté client,
`GradleApiImpl` expose l'état observé (`observeSyncState` sur le port du
domaine) ; `marquerSyncEnCours` devient idempotent — le marquage local
(réactivité immédiate) et l'annonce du serveur (confirmation) ne remettent
pas le chrono à zéro. À la perte de session, l'état repasse au repos
(`rompreSyncEnCours`) — jamais de « en cours » mort.

### 3. Canal Taches

`CanalTooling` gagne `TACHES` (violet, `ic_liste_taches`) : le listage du
sélecteur « Exécuter » vit sur SON canal — indicateur de vol dans l'en-tête
(« Chargement des tâches… » + chrono) pendant la requête, le sélecteur EST
le résultat (pas de ligne de résultat). Priorité du canal actif :
Sync (elle conditionne tout) > Build > Taches.

### 4. État tooling process-wide : singleton, attache, rattachement

`GradleService` devient `@Singleton` Hilt (le ViewModel l'injecte au lieu
de le construire) : la mort de l'espace de travail ne tue plus l'état
tooling. `attacher()` ouvre une session d'espace : console et problèmes
repartent vierges (ce sont des vues), les activités en vol et la connexion
sont conservées. `rattacherBuildEnVol()` ré-observe à l'ouverture le build
parti avant la fermeture — ses sorties et son état continuent d'arriver,
comme un IDE qui se rattache à ses tâches de fond. Le chronomètre
(`TimeProvider`) reste injecté — les tests pilotent le temps.

### 5. Service Android de notification (piloté par transitions)

`GradleService` utilise le service d'Android par un port dédié
(`DemarreurServiceTooling`, implémentation `DemarreurServiceToolingAndroid`
— même contrat que `DemarreurService` du terminal, prompt Terminal-1 §4.2)
: au premier départ d'activité (canal actif `null` → non nul), le service
foreground part ; l'arrêt lui appartient (plus d'activité → stopSelf). La
décision de CONTENU est pure (`decisionNotificationTooling`) : en cours
pendant l'activité (« Synchronisation du projet en cours… », « Build en
cours — assembleDebug », « Chargement des tâches… »), notification finale
au résultat (« Synchronisation réussie en 12 s », « Build échoué », « Build
annulé ») qui reste dans le tiroir jusqu'à effacement. Le service
(`ToolingService`, `specialUse` documenté au manifeste) observe l'état
process-wide et s'arrête de lui-même au repos — honnête, jamais collant.

### 6. Vérification réelle répétable

Le harnais de situation réelle (socket Unix, VRAI jar orchestrateur, VRAI
daemon Gradle, fixture copiée en temporaire) valide de bout en bout :
ordre `sync_started` → `sync_result`, tâches listées, ordre
`build_started` → sorties → `build_finished`, `task_started`/`task_finished`
observés, ping/pong, sortie propre code 0. Les assertions d'ordre
(`SyncStarted` précède `SyncResult`) sont AUSSI gelées dans
`ServeurIntegrationTest` — la pérennité vit dans les tests du dépôt.

## Conséquences

- Le protocole compte 25 messages (16 événements) : les fichiers dorés et
  le catalogue des échantillons suivent — tout renommage fut y est vu.
- `GradleToolingRepository` gagne `observeSyncState` : les faux des tests
  suivent ; le modèle domaine `EtatSyncTooling` reste sans type tooling
  (règle §2.2).
- L'`EditorViewModel` n'a plus d'horloge propre (supprimée) : les chronos
  vivent dans le `GradleService` singleton.
- Le manifeste de `feature:editor` déclare le service (permissions
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`)
  — la permission de notification elle-même relève du parcours existant
  (page Notifications de l'onboarding).
- `ServerVersion.CURRENT` passe à 0.33.0 (annoncée au handshake,
  informationnelle).
