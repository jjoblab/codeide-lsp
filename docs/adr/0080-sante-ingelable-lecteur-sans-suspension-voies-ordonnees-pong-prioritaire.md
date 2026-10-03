# ADR 0080 — Santé ingélable : lecteur sans suspension, voies ordonnées, pong prioritaire

- Statut : accepté (2026-10-03)
- Contexte : retour utilisateur v0.47.0/v0.48.0 — les sondes de latence
  v0.43 (« émission orchestrateur → réception client : 19-24 s minimum,
  jusqu'à 28 minutes, rafales ~76 s après la fin du build ; zone texte :
  écart min 0 ms ») et le verdict du watchdog v0.48 (« orchestrateur muet
  (aucun pong en 15000 ms) — arrêt forcé », kill 143, relance 1/5, en
  pleine sync).

## Contexte

Trois générations de correctifs avaient déjà grignoté la même famille de
bug (v0.37.3 : pong sorti du bus ; v0.45.1 : vidanges process-wide pour
tout canal vivant ; ADR 0079 : flux sync ordonné) — et pourtant le
terrain v0.48 montre TOUT le retard concentré dans le transport, avec un
orchestrateur tué pour « mutisme » alors qu'il tournait. Le diagnostic
complet des logs désigne deux fragilités SYMÉTRIQUES qui convergent :

1. **La pompe cliente est une coroutine UNIQUE** qui lit le socket ET
   fait ses envois suspendants dans la même boucle
   (`evenements.collect { pomper(it) }`). TOUT canal aval plein gèle la
   lecture : plus une frame n'est lue, donc plus aucun PONG —
   `dernierPongMs` vieillit, le watchdog (justement) déclare l'orchestrateur
   muet et le TUE. Or l'orchestrateur était sain : c'est la console de
   l'app qui était en retard. Le kill ne réparait rien (la relance
   retrouvait le même canal plein) — pire, il PERDAIT ce qui était en
   vol. La v0.48 a rendu le scénario massif : le canal du flux sync
   ordonné (256) reçoit désormais le VRAI flux Gradle (ADR 0079) et les
   ticks de progression — il se remplit en secondes dès que sa vidange
   traîne ou meurt. Et une vidange MOURAIT en silence : une pan non
   prévue dans un collecteur tuait la coroutine sous un `SupervisorJob`
   sans témoin — le canal ne se vidait plus JAMAIS.
2. **Côté orchestrateur, le pong partage le verrou des événements.** Le
   correctif v0.37.3 écrivait le pong hors bus mais TOUJOURS via le
   `@Synchronized` de `SocketClient.envoyer` : le fil consommateur du
   bus, bloqué dans une écriture socket (tampon de réception de l'app
   plein — cf. fragilité 1, la boucle se mord la queue), gardait le
   moniteur et ensevelissait le pong quand même.
3. **L'ordonnanceur partagé.** La pompe et les vidanges vivaient sur le
   dispatcheur PAR DÉFAUT, en concurrence avec le travail CPU de l'app
   (préparation du classpath LSP, surlignage) : la lecture du socket
   pouvait jeûner des secondes sans qu'aucun canal soit plein.

## Décision

### 1. Le lecteur ne suspend JAMAIS — le pong en chemin rapide

`GradleApiImpl.ouvrirSession` lance un LECTEUR dont le collecteur ne
contient aucune opération suspendante : `PongMessage` → mise à jour
ATOMIQUE de `dernierPongMs` en ligne ; familles build/sync → `trySend`
vers leur voie ; événements légers (réponses de requêtes, tas,
diagnostics) → traitement en ligne (complétions et `StateFlow`, rien de
suspendant). La santé ne peut plus être retardée par quoi que ce soit qui
se passe derrière elle — c'est la clôture définitive de la famille
« orchestrateur muet » côté app.

### 2. Deux voies ordonnées, un consommateur chacune

```kotlin
voieBuild: Channel<ToolingEvent>(UNLIMITED)   // BuildStarted/Output/Finished,
                                               // TaskStarted/Finished, ProgressEvent
voieSync:  Channel<ToolingEvent>(UNLIMITED)   // SyncStarted/Result/Partial/Progress/
                                               // Output, ErrorResponse (conclusions)
```

Les envois suspendants vers les canaux aval (sorties 4096, flusSync 256)
vivent dans le CONSOMMATEUR de la voie, isolé du lecteur : une voie
pleine retarde SA famille, jamais la santé ni l'autre famille. L'ordre
INTRA-famille est l'ordre du câble (l'ordre lignes → clôture de canal,
et l'ordre lignes → terminal de l'ADR 0079, sont préservés — un
`ErrorResponse` qui conclut une sync traverse la voie sync). Les voies
sont NON bornées : le point de contre-pression reste les canaux aval
existants ; la voie n'accumule que ce que l'ancienne architecture
accumulait dans la file serveur (8192) et les tampons de l'OS — la
mémoire globale ne croît pas, elle déménage du côté qui a de la marge.
Chaque événement d'une voie traverse SANS PAN (journalisée tag
`ToolingClient`) : une voie ne meurt plus en silence.

### 3. Voie DÉDIÉE de fils pour la pompe

La portée vit sur `Dispatchers.IO.limitedParallelism(3)` (lecteur +
deux consommateurs) : le dispatcheur par défaut peut saturer de travail
CPU, la lecture du socket ne partage plus son ordonnanceur.

### 4. Fil écrivain unique côté orchestrateur, file PRIORITAIRE pour le pong

`SocketClient` porte un fil `gradle-server-ecrivain` démarré après le
handshake (avant lui, les écritures restent directes, séquentielles) :
les frames normales traversent une file FIFO bornée 8192 (`put` bloquant
— contre-pression sans perte conservée), le pong une file prioritaire
dépollée EN PREMIER. Plus aucun verrou partagé : une écriture bloquée
ne retient plus la santé. L'ordre relatif pong/événements peut s'inverser
(sans conséquence, déjà accepté en v0.37.3) ; l'ordre des événements est
FIFO strict.

### 5. Le verdict du watchdog devient un diagnostic

`sonderSante` journalise les SIGNES VITAUX de la pompe au moment du
verdict (« dernierPong=… ms, voieBuild=…, voieSync=…, session=… » via
`GradleApiImpl.signesVitaux()`) : une voie profonde désigne une console
lente côté app (le kill était exactement le MAUVAIS réflexe), des voies
vides et un pong vieux désignent un orchestrateur réellement mort. De
même, la file d'écriture socket ET les voies clientes avertissent (débit
borné, 5 s) au-delà de leur seuil : le retard se NOMME en se formant.

### 6. Les vidanges process-wide ne meurent plus

`PompeBuildTooling` : chaque événement traverse sans pan (journalisée,
la vidange continue au suivant) et une vidange morte est relancée
(bornée à 3, délai 1 s). La boucle « canal plein → pompe gelée → muet →
kill → relance dans le même canal plein » n'a plus de première marche.

## Conséquences

- Le watchdog garde son rôle de dernier recours (orchestrateur réellement
  mort) mais ne peut plus se déclencher sur un simple retard de console :
  les deux mécanismes qui l'alimentaient par erreur sont fermés par
  construction.
- La latence transport (sondes `ConsoleLatence`) se mesure désormais à la
  RÉCEPTION par le lecteur : la différence avec la publication
  (GradleService) couvre voies + canaux — la moitié cliente entière,
  honnête de bout en bout.
- `PompeBuildTooling` reçoit un `AppLogger` (constructeur) : les pans de
  vidange se voient ; le socle de test passe un `FakeAppLogger`.
- `ServerVersion.CURRENT` passe à 0.49.0 (annoncée au handshake) ;
  `JarDeployer` détecte le JAR changé par son empreinte SHA-256 — aucune
  migration, la redépouille est automatique.
- Le protocole du CÂBLE ne change PAS (mêmes messages, même framing) :
  aucune incompatibilité app ↔ orchestrateur au-delà de la version
  annoncée.
