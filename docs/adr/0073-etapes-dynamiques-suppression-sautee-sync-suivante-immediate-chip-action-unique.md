# ADR 0073 — Étapes dynamiques : suppression du concept « sautée / En cache », sync suivante immédiate, chip d'action unique

- Statut : accepté (2026-09-29)
- Contexte : prompt de suivi §2, §3 — retour utilisateur sur la 0.40.0

## Contexte

La 0.40.0 (ADR 0071) avait introduit le concept « phase sautée / en cache » :
une phase satisfaite d'avance (distribution Gradle déjà en cache) se
déclarait SAUTÉE via `SyncProgress.sautee = true` — le client rendait la
rangée en « en cache » (point gris, libellé atténué, durée « En cache »).

**Problèmes constatés** :

1. Le plan affiché était FIXE (7 étapes, les absentes affichées « En cache »)
   — il ne s'adaptait pas au projet ni à l'état du cache. Une sync chaude
   affichait 7 étapes dont 3 « en cache » — l'utilisateur voyait du bruit
   au lieu d'un plan simplifié.
2. Le catalogue était conçu SANS observer les sorties réelles de Gradle —
   des hypothèses non validées (DAEMON détectable via Tooling API ? distribution
   seulement au 1er lancement ?) étaient affichées comme des faits.
3. Au retour d'un projet sans changement, l'UI relançait la sync complète
   — pas d'état immédiat « Synchronisé · il y a X » comme Android Studio.
4. Les chips Sync/Build étaient cliquables — la bascule utilisateur
   polluait l'état de la console (l'utilisateur pouvait revenir à SYNC
   pendant un build).

## Décision

### 1. Suppression du concept « sautée / En cache » (§2)

- `SyncProgress.sautee` supprimé du protocole v5 (champ optionnel —
  compatibilité ascendante préservée).
- `ConteurPhasesSync.sauter()` supprimé, set `sautees` supprimé.
- `SyncHandler` : la distribution en cache n'est ni ouverte ni sautée —
  elle n'est pas émise du tout par le serveur.
- `StatutEtapeSync.SAUTEE` supprimé (4 états : EN_ATTENTE, EN_COURS,
  TERMINEE, ECHOUEE).
- `EtapeSyncAffichee.sautee`, `EtatEtapeArbre.sautee` supprimés.
- `R.string.editor_console_etape_en_cache` (FR + EN) supprimé.
- `point_etape_sautee.xml` supprimé.
- `ALPHA_LIBELLE_SAUTEE` et `ALPHA_PLEIN` supprimés.

### 2. Sync suivante immédiate (§2)

- Nouveau fichier `.codeide/local/sync-state.json` (comme
  `lsp-classpath.json` déjà existant) stocke :
  - empreinte SHA-256 des fichiers Gradle (`build.gradle*`,
    `settings.gradle*`, `gradle.properties`, `gradle/libs.versions.toml`,
    `gradle-wrapper.properties` + `buildSrc/`) ;
  - tâches du projet ;
  - durée et instant de la dernière sync réussie ;
  - synthèse (tachesActionnables/Executees/AJour).
- `CalculerEmpreinteGradleUseCase` : SHA-256 hexadécimale (64 caractères),
  par ordre déterministe, marqueur de début par fichier (un absent ≠ un
  vide — les deux ont la même empreinte brute, le marqueur garantit un
  changement détectable).
- `LireSyncStateUseCase` : lecture tolérante (null si absent/illisible/
  corrompu).
- `EcrireSyncStateUseCase` : écriture non bloquante (échec journalisé,
  jamais remonté à l'UI).
- Au retour d'un projet dont l'empreinte est identique au state persisté :
  - publier immédiatement `synchronisationReussie` + `tachesDisponibles` ;
  - lancer la revalidation silencieuse en arrière-plan (pas de
    `marquerSyncEnCours`, pas de déroulé visible) ;
  - en cas d'échec de revalidation, l'état « Synchronisé » est CONSERVÉ
    (pas de rouge) — l'utilisateur peut relancer manuellement.
- En cas d'échec de sync manuelle : le state précédent reste valide pour
  le retour (persisterSyncState n'est appelé qu'en cas de succès) — les
  tâches/classpath précédents sont conservés.

### 3. Chip d'action unique (§3)

- Un UNIQUE chip reflète l'action Gradle courante (Sync / Build / Tâches /
  Classpaths…), NON cliquable — plus de bascule.
- La console montre toujours l'action courante (ou la dernière exécutée).
- Aucun chip s'il n'y a eu aucune action (état vierge).
- `ActionEditor.BasculerFiltreConsole` supprimée.
- `PanneauConsoleFragment.brancherChips` / `rendreChips` /
  `renduChipsEnCours` supprimés — remplacés par `rendreChipAction(etat)`.
- Layout `fragment_panneau_console.xml` : `ChipGroup` Sync/Build
  remplacé par un seul `Chip` non cliquable (`chip_action_courante`).
- Couleur de canal harmonisée (bordure + fond teinté 12 % + texte
  couleur de canal — aperçu v3 §6).

### 4. Stats classpath par module (§4)

- `ClasspathModule` (protocole) étendu avec 10 champs optionnels : nbJars,
  nbAars, nbSources, varianteAndroid, nbDependancesProjet, fichiersGeneres,
  androidJar, ignore, raisonIgnore, avertissements.
- `ModuleClasspath` (domaine) étendu avec les 10 mêmes champs.
- `ClasspathHandler.versClasspath` (serveur) : calcule nbJars, nbAars,
  nbDependancesProjet, nbSources, varianteAndroid pour chaque module.
- `EtatGradle.statsClasspath` : nouveau champ `List<ModuleClasspath>?`.
- `GradleService.publierStatsClasspath` : nouvelle méthode.
- `RangeeConsole.SyntheseSync` : 4 nouveaux champs (nbModules, nbJars,
  nbAars, nbSources) pour le récapitulatif du pied de sync.
- `piedSync` : agrège les stats par module en totaux.
- `SyntheseHolder.lier(SyntheseSync)` : affiche le récapitulatif en
  sous-ligne (« 3 modules · 312 jars · 4 sources · 12 AARs »).

## Hypothèses invalidées par les scénarios (Phase 0, ADR 0071)

- **DAEMON non détectable via Tooling API** : `OperationType.DAEMON`
  n'existe pas — le seul signal est sur stderr (`Starting process 'Gradle
  build daemon'` vs `Starting Nth build in daemon`). Le `StreamingOutputStream`
  doit observer le stderr pour détecter cette phase.
- **DISTRIBUTION à chaque changement de wrapper** : pas seulement au
  premier lancement — l'empreinte SHA-256 de `gradle-wrapper.properties`
  est nécessaire (pas seulement « existe-t-il un dossier dans
  `wrapper/dists` »).
- **Plugins Gradle téléchargés même sans dépendances runtime** : même un
  projet Kotlin/JVM pur télécharge les métadonnées des plugins (depuis
  `plugins.gradle.org`). La phase « Dépendances » doit toujours être
  listée — fusionnée avec « Modèle IDE » sous `DEPENDANCES_MODELE`.

## Conséquences

- Le plan affiché est **construit pour la sync en cours** : une étape non
  concernée n'existe pas dans la liste. Sync 1er lancement = 7 étapes ;
  sync suivante = 4 étapes ; Gradle modifié = 6 étapes.
- Le retour d'un projet sans changement affiche immédiatement «
  Synchronisé · il y a X » + les tâches — revalidation silencieuse en
  arrière-plan.
- Le chip d'action est NON cliquable — la bascule Sync/Build est
  automatique (sync démarre → SYNC, build démarre → BUILD).
- Le pied de sync affiche un récapitulatif classpath (total modules /
  jars / sources / AARs).

## Fichiers modifiés (résumé)

### Protocole (tooling/protocol)
- `Messages.kt` : `SyncProgress.sautee` supprimé ; `ClasspathModule`
  étendu (10 champs optionnels).
- `GradleProtocol.kt` : commentaire v6.
- `EchantillonsMessages.kt` : `ClasspathModule` avec stats.
- `golden/sync_progress.json` : régénéré sans `sautee`.
- `golden/classpath_result.json` : régénéré avec stats.

### Serveur (tooling/server)
- `ConteurPhasesSync.kt` : `sauter()` / `sautees` / `publier(sautee)`
  supprimés.
- `SyncHandler.kt` : distribution en cache non émise.
- `ClasspathHandler.kt` : `versClasspath` calcule stats ; nouvelle
  `detecterVarianteAndroid`.
- `ParseurSyntheseBuild.kt` (correctif n°4 du commit 38d7933, déjà livré).

### Domaine (core/domain)
- `GradleToolingPort.kt` : `EtapeSyncTooling.sautee` supprimé ;
  `ModuleClasspath` étendu ; `InfoTache` annoté `@Serializable`.
- `SyncStateUseCases.kt` (nouveau) : `CalculerEmpreinteGradleUseCase`,
  `EtatSyncLocal`, `LireSyncStateUseCase`, `EcrireSyncStateUseCase`.
- `OutilsTerminalObservation.kt` : `EtatOutilsTerminal.initialise`
  (correctif n°1 du commit 38d7933, déjà livré).

### UI (feature/editor)
- `EditorViewModel.kt` : 3 nouveaux paramètres (calculerEmpreinteGradle,
  lireSyncState, ecrireSyncState) ; `restaurerEtatSyncSiEmpreinte
  Identique` ; `revaliderSyncSilencieusement` ; `persisterSyncState` ;
  `basculerFiltreConsole` supprimé.
- `GradleService.kt` : `EtatGradle.statsClasspath` ; `publierStats
  Classpath` ; `StatutEtapeSync.SAUTEE` supprimé ; `EtapeSyncAffichee
  .sautee` supprimé.
- `RangeesConsole.kt` : `FiltreCanalConsole` public (pas internal) ;
  `EtatEtapeArbre.sautee` supprimé ; `consolider` sans sautee ;
  `SyntheseSync` étendue (nbModules/Jars/Aars/Sources) ; `piedSync`
  agrège les stats.
- `PanneauxToolingAdapters.kt` : `EtapeArbreHolder` sans sautee ;
  `DiffRangeesConsole.getChangePayload` (payloads granulaires) ;
  `SyntheseHolder.lier(SyntheseSync)` affiche le récapitulatif classpath.
- `PanneauConsoleFragment.kt` : refonte complète (chip d'action unique
  non cliquable, `rendreChipAction`).
- `PanneauToolingController.kt` : `reinitialiserEntete` quand canal null
  (correctif n°5 du commit 38d7933, déjà livré).
- `AnneauTournant.kt` (nouveau, ADR 0072).
- `EtatEditor.kt` : `ActionEditor.BasculerFiltreConsole` supprimée.
- Layouts : `ligne_arbre_etape.xml` (AnneauTournant) ;
  `fragment_panneau_console.xml` (chip unique) ; `ligne_synthese_build.xml`
  (seconde ligne monospace — correctif n°4 du commit 38d7933, déjà livré).
- Strings FR + EN : `editor_console_etape_en_cache` supprimée ;
  `editor_console_synthese_taches_*` ajoutées (correctif n°4) ;
  `editor_console_stats_*` ajoutées (§4).
- Drawables : `point_etape_sautee.xml` supprimé ; `anneau_etape_en_cours
  .xml` (nouveau, ADR 0072).

### Tests
- `AnneauTournantTest.kt` (nouveau, 6 cas).
- `ParseurSyntheseBuildTest.kt` (nouveau, 8 cas — correctif n°4).
- `RangeesConsoleTest.kt` : tests sautee supprimés/adaptés.
- `GradleServiceTest.kt` : test sautee supprimé.
- `PresentationToolingTest.kt` : test sautee adapté.
- `EditorViewModelTest.kt` : assertions assouplies (revalidation
  silencieuse).
- `BaseEditorViewModelTest.kt` : 3 nouveaux paramètres injectés.
- `ActivityEditorLayoutTest.kt` : chip unique non cliquable au lieu de
  ChipGroup.
- `ConteurPhasesSyncTest.kt` : 3 tests sautee supprimés.
- `GradleApiImplTest.kt` : test sautee supprimé.
- `ProtocoleRoundTripTest.kt` : test sautee supprimé.
- `ObservateurOutilsTerminalTest.kt` : mis à jour (correctif n°1).
