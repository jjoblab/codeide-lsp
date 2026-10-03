# ADR 0079 — Flux de sync ordonné : le résultat voyage sur le câble, la console montre le vrai flux

- Statut : accepté (2026-10-03)
- Contexte : retour utilisateur v0.47.0 — « lors d'un Sync la console
  manque toujours les outputs nécessaire, la plupart est affiché dans le
  header du bottomsheet et à la fin du sync l'ui n'est toujours pas à
  jour (la console et le header) ».

## Contexte

Deux constats de terrain distincts, une même famille de causes : la sync
ne traversait le tooling que par des ÉTATS, jamais par un FLUX.

1. **La console Sync était muette.** La capture stdout/stderr de la sync
   avait été RETIRÉE en v0.45.1 : la capture v0.41.1 publiait des
   `BuildOutput` avec l'identifiant de la REQUÊTE sync comme `buildId`,
   or le client n'ouvrait un canal de sortie QUE pour les identifiants de
   BUILD — chaque ligne était publiée sur le bus puis JETÉE à la
   réception. La suppression avait coupé la publication morte, laissant
   la console Sync à ~7 lignes de transitions d'étapes pendant que tout
   le détail vivant (octets, élément courant, « Starting Gradle Daemon »)
   nourrissait le SEUL sous-titre de l'en-tête du panneau.
2. **La fin de sync laissait l'UI en l'air.** Le résultat de sync n'était
   publié à `GradleService` que par la coroutine LANÇANTE. La
   revalidation silencieuse (v0.40.1, lancée à chaque ré-ouverture dont
   l'empreinte Gradle n'a pas changé) ne le publiait JAMAIS — alors que
   son `SyncStarted` avait armé `synchronisationEnCours` via le collecteur
   `observeSyncState` : l'en-tête restait « étape n/N » avec spinner et
   chrono qui couraient POUR TOUJOURS, et la console (vidée au départ)
   ne recevait jamais sa conclusion. Même symphonie si le ViewModel mourait
   en pleine sync (écran fermé). Enfin, un échec ne concluait PAS la
   console : seul l'état portait le verdict.

## Décision

### 1. UN flux de sync, ORDONNÉ — `EvenementSyncFlux`

`observeSyncProgress(): Flow<EtapeSyncTooling>` devient
`observeFluxSync(): Flow<EvenementSyncFlux>` (scellé du domaine) :

```kotlin
sealed interface EvenementSyncFlux {
    data class Debut(val projectDir: String?)
    data class Ligne(val sortie: LigneSortieSync)
    data class Etape(val etape: EtapeSyncTooling)
    data class Terminal(val resultat: AppResult<ResultatSynchronisation>)
}
```

Le client y envoie, SUR SA POMPE (un seul fil, des `send` séquentiels),
dans l'ordre d'arrivée des événements du serveur : `SyncStarted` → Debut,
`SyncOutput` → Ligne, `SyncProgress` → Etape, `SyncResult`/
`PartialSyncResult` → Terminal (AVANT de réveiller l'attendeur), et un
`ErrorResponse` répondant à une sync en vol ( registre `syncsEnVol`) ou
une rupture de session produit AUSSI un Terminal. L'ordre du câble EST
l'ordre du flux : la conclusion ne peut pas précéder les lignes et étapes
qu'elle conclut, le vidage de console (`Debut` → `marquerSyncEnCours`)
ne peut pas tomber APRÈS les premières lignes (la course du collecteur
`observeSyncState` conflaté du viewModelScope — supprimé — pouvait
emporter les outputs d'un `Vider` tardif).

### 2. Le TERMINAL conclut l'UI, pas la coroutine lancante

La vidange process-wide (`PompeBuildTooling.pomperSync`) route le flux :
`Debut` → `marquerSyncEnCours`, `Ligne` → `ajouterLigneSync`, `Etape` →
`ajouterEtapeSync`, **`Terminal` → `publierResultatSync`**. Toute sync —
manuelle, d'ouverture, de revalidation silencieuse — se conclut sur le
FAIT du serveur, même si l'écran lancant est mort. L'appelant ne publie
plus que les échecs LOCAUX de transport (aucun événement serveur ne
conclura une sync jamais partie) ; une double conclusion éventuelle
(SyncResult tardif après un délai d'inactivité local) est DÉDUPLIQUÉE
par contenu dans `GradleService` (mémo remis à null à chaque nouveau
cycle) — un verdict DIFFÉRENT s'écrit : chronologie honnête, le dernier
verdict gagne.

### 3. Le VRAI flux de Gradle dans la console Sync

- **Protocole v6** : nouveau message `SyncOutput` (`sync_output` :
  `projectDir`, `stream`, `line`, `timestampMs`) — miroir de
  `BuildOutput` pour la sync ; la montée de version (handshake à
  égalité exacte) refuse le croisement avec un client antérieur qui
  ne saurait pas décoder le nouveau discriminant.
- **Serveur** : `StreamingFluxSync` (miroir de `StreamingOutputStream`)
  publie le stdout/stderr de l'action de sync, `--console=plain` comme
  le build ; `EcouteurStatutLegacy` republie en plus chaque statut
  textuel CHANGÉ (« Starting Gradle Daemon ») — le seul signal vivant
  pendant la fenêtre daemon, des minutes sur un téléphone froid.
- **Rendu** : `ajouterLigneSync` écrit dans le canal SYNC (stdout en
  style sortie, stderr en rouge), stdout ET stderr s'accumulent entre
  le `Vider` du départ et la conclusion.

### 4. L'échec conclut aussi la console

`publierResultatSync` écrit désormais « Synchronisation échouée en Xs »
+ le message du serveur en ligne d'erreur — parité « SYNC FAILED »
d'Android Studio, qui explique TOUJOURS pourquoi la sync a échoué dans
sa fenêtre. La réussite garde sa conclusion (dédupliquée).

## Alternatives rejetées

- **Rebrancher la capture v0.41.1 telle quelle** (buildId = requête
  sync) : la publication resterait jetée par le client — le cœur du
  problème initial.
- **Deux canaux séparés** (étapes + terminal / lignes) : l'ordre
  CROSS-canal n'est pas structurel — la conclusion pouvait précéder les
  dernières lignes, le `Vider` suivre les premières. Un seul canal
  ordonné élimine la classe entière.
- **Étendre `EtatSyncTooling` au résultat** : la conflation d'un état
  perd l'ORDRE — précisément ce que la console exige.
- **Garder la publication au seul ViewModel + publier aussi dans la
  revalidation silencieuse** : deux appels à maintenir en cohérence, et
  le cas « ViewModel mort en pleine sync » restait sans conclusion.

## Conséquences

- `GradleToolingRepository.observeFluxSync` remplace
  `observeSyncProgress` (doubles de test adaptés) ; `observeSyncState`
  reste (l'état « en cours » sert toujours).
- Le protocole passe en v6 (`SyncOutput`) : serveur et app se
  déploient ensemble (daemon redéployé par l'app), aucun croisement
  réel, le handshake refuserait de toute façon.
- La console Sync voit sa population passer de ~7 transitions à
  « transitions + flux réel + statuts daemon + conclusion » : l'en-tête
  du panneau garde le vivant (compteur n/N, octets, verdict) SANS être
  le seul à parler — la console est l'historique complet.
- `revaliderSyncSilencieusement` ne publie plus l'état : le terminal le
  fait (un échec LOCAL — pas de session — n'arme jamais « en cours »,
  l'état « Synchronisé » restitué est conservé, design v0.40.1 tenu).

## Fichiers modifiés (résumé)

- `tooling/protocol` : `Messages.kt` (+`SyncOutput`),
  `GradleProtocol.kt` (v6), échantillon + doré `sync_output.json`.
- `tooling:server` : `StreamingFluxSync.kt` (NOUVEAU), `SyncHandler.kt`
  (capture stdout/stderr + `--console=plain`), `EcouteurStatutLegacy`
  (republication console des statuts changés).
- `core:domain` : `GradleToolingPort.kt` (`EvenementSyncFlux`,
  `LigneSortieSync`, `observeFluxSync`).
- `tooling:client` : `GradleApiImpl.kt` (flux ordonné, `syncsEnVol`,
  Terminal sur ErrorResponse/rupture), `ConversionsDomaine.kt`
  (`versResultatDomaine` partagé).
- `feature:editor` : `GradleService.kt` (`ajouterLigneSync`, conclusion
  d'échec, déduplication), `LignesConsoleTexte.kt`
  (`conclusionSyncEchouee`), `PompeBuildTooling.kt` (vidange du flux
  complet), `EditorViewModel.kt` (collecteur `observeSyncState`
  supprimé, publication des seuls échecs locaux), strings fr/en.
