# ADR 0078 — Console flux brut unique : un TextView par canal, la fin des rangées structurées

- Statut : accepté (2026-10-02)
- Contexte : retour utilisateur v0.45.3 — « dans le cas de build les
  outputs sont affichés dans deux endroits. Je veux utiliser un seul,
  peut-être que l'utilisation de RecyclerView contribue au problème que je
  rencontre, de même pour Sync. Je veux une refonte totale pour un
  affichage normal comme ce que fait Android Studio. »

## Contexte

La console hybride de l'ADR 0074 (v0.42.0) avait deux zones empilées : une
zone STRUCTURÉE (`RecyclerView` + DiffUtil : arbre des étapes de sync,
lignes de tâches, téléchargements, synthèses) et une zone TEXTE
(stdout/stderr bruts par append direct). Deux constats de terrain l'ont
condamnée :

1. **La duplication était réelle, pas visuelle** : Gradle écrit LUI-MÊME
   ses lignes « > Task :app:compileDebugKotlin », « BUILD SUCCESSFUL in
   6s » et « N actionable tasks: M executed » sur le flux stdout capturé
   par la Tooling API (vérifié dans les sorties réelles de
   `docs/tooling-scenarios/sorties/04-kotlin-jvm-avec-deps-chaud.log`).
   La zone structurée AFFICHAIT des équivalents français construits des
   mêmes événements — chaque tâche, chaque synthèse apparaissait DEUX FOIS
   (« deux endroits »), dans deux styles, deux langues.
2. **Le RecyclerView restait le suspect de performance de l'utilisateur** :
   chaque événement de tâche ou de téléchargement émettait un état, chaque
   état redéclenchait `construireRangeesConsole` + `submitList` + DiffUtil
   — la leçon de l'ADR 0074 (jamais de reconstruction par ligne) n'était
   tenue que pour le flux brut, pas pour les rangées.

Le terminal intégré, lui, affiche le flux du VRAI Gradle immédiatement
(test terrain : « BUILD FAILED in 6s » affiché en 6 s) — c'est exactement
le contrat de la fenêtre Build d'Android Studio : IntelliJ n'y re-rend
PAS les tâches, il montre le flux.

## Décision

### 1. La console EST le flux (parité Android Studio)

Le corps de l'onglet Sortie devient **deux `TextView` monospace, un PAR
CANAL** (Sync et Build) dans deux `NestedScrollView` superposés ; le chip
d'action (lecture seule, piloté par le ViewModel comme depuis v0.40.1)
choisit lequel est VISIBLE. L'autre continue de s'accumuler en coulisses
(`GONE` : aucun layout, aucun redraw) — basculer ne perd rien, ne rejoue
rien, ne reconstruit rien.

- **Canal Build** : le flux stdout/stderr de Gradle TEL QUEL — ses lignes
  « > Task », les diagnostics de compilation (`e: file:///…`),
  « BUILD SUCCESSFUL in 6s », « N actionable tasks: … » — enrichi des
  lignes de statut de l'orchestrateur (déjà en place depuis v0.45.1/2 :
  « connexion au daemon Gradle… », « Starting Gradle Daemon », « build
  Gradle : 10 s · démarrage du daemon : 235 s · total : 245 s »), d'une
  ligne d'en-tête au format d'Android Studio (« Exécution des tâches :
  [:app:assembleDebug] dans le projet MonIP » — le VRAI sélecteur Gradle
  s'y lit, celui que le terminal attend) et d'UNE LIGNE PAR ARTEFACT
  téléchargé terminé (« Téléchargé : kotlin-stdlib.jar · 34,2 Mo reçus au
  total »). L'ANNULATION seule reçoit une ligne de conclusion propre —
  Gradle n'imprime rien de tel après un cancel en pleine configuration.
- **Canal Sync** : une ligne PAR TRANSITION d'étape (« Libellé… » à la
  première annonce, « Libellé ✓ 34,1s » — · volume reçu · compte
  d'artefacts le cas échéant — à la conclusion) et la conclusion de la
  sync (« Synchronisation terminée en 8,4s — les tâches sont
  disponibles. »). Les ticks d'octets intermédiaires n'écrivent RIEN :
  le détail EN VOL vit dans l'en-tête du panneau (compteur « étape n/N »,
  progression déterminée — inchangé), la console est l'HISTORIQUE.

### 2. Les tâches ne produisent plus rien ; l'état porte les étapes

`ajouterTache` disparaît : le canal `observeTachesBuild` reste VIDÉ (leçon
v0.45.1 : la pompe unique du client ne doit jamais bloquer sur un canal
sans consommateur) mais ses événements ne publient plus ni ligne ni état.
`EtatGradle.lignes` (fenêtre de `LigneConsole` typées) devient
`etapesSync : List<EtapeSyncAffichee>` — les seules données structurées
restantes, celles dont l'EN-TÊTE a besoin ; `telechargementsBuild` disparaît
(remplacé par l'accumulateur privé du service qui alimente les lignes).
Le réglage « afficher les tâches » disparaît de la page de configuration
(le champ DataStore reste dormant pour la compatibilité des réglages
persistés).

### 3. `EvenementConsoleTexte` porte canal + libellé + style

```kotlin
data class Ligne(canal: CanalTooling, libelle: TexteTooling, style: StyleLigne, horodatageMs: Long)
data class Vider(canal: CanalTooling)
```

- `canal` : les DEUX canaux s'accumulent dans le MÊME rejeu (tampon borné
  partagé, `Vider` PAR canal) — la correction par construction de l'ADR
  0074 tient canal par canal : un `Vider(canal X)` tombé de la fenêtre
  emporte tout ce qui le précédait, y compris les lignes de X ;
- `libelle` : `TexteTooling` (ressource / brut / composé — le libellé
  d'étape est une ressource suivie d'un suffixe formaté, la composition
  se résout au rendu, l'événement reste pur) ;
- `style` : SORTIE / ERREUR / APAISEE / ETAPE / TELECHARGEMENT / SYNTHESE —
  la couleur se résout au rendu (stderr rouge, apaisé atténué, synthèse
  grasse).

Les constructeurs vivent dans `LignesConsoleTexte.kt` (fonctions PURES
testées, même discipline que le présentateur) ; `DureesLisibles` et
`OctetsLisibles` y déménagent avec la disparition de l'adaptateur.

### 4. Application lotie par trame, auto-défilement honnête — inchangés

Le fragment accumule les événements puis applique en UN post par trame :
un seul `append` par console, O(1) par ligne, jamais de DiffUtil, jamais
de reconstruction. L'auto-défilement suit le bas tant que l'utilisateur y
est resté (intention capturée AVANT l'ajout, tolérance une ligne). La
collecte vit sur le `viewLifecycleOwner.lifecycleScope` (survit à un
onStop sans doubler, meurt avec la vue — le rejeu reconstruit les DEUX
consoles à la rotation).

## Alternatives rejetées

- **Garder l'hybride en « corrigeant » la duplication** (filtrer les
  lignes « > Task » du stdout) : filtrer le flux de Gradle en fonction
  d'un préfixe de version, ou enrichir SES lignes en place — fragile et
  trompeur ; Android Studio ne filtre rien.
- **Un seul TextView pour les deux canaux** : entrelacer sync et build
  dans le même défilement rend l'onglet illisible ; AS sépare ses
  fenêtres Build et Sync, le chip fait de même.
- **Mises à jour en place de lignes (clef → plage dans l'Editable)** :
  possible mais décale toutes les plages suivantes à chaque remplacement
  (O(nb clefs) par mise à jour, maintenance de la carte des offsets) —
  les lignes accumulées font le même métier pour zéro mécanique (c'est
  aussi la sémantique d'un terminal).

## Conséquences

- Un seul endroit par information : le flux porte les sorties, l'en-tête
  du panneau porte le vivant (compteur d'étapes, progression, verdict),
  l'onglet Problèmes porte les diagnostics groupés.
- Le RecyclerView, `ConsoleToolingAdapter`, `RangeeConsole`,
  `construireRangeesConsole` et les layouts de rangées
  (`ligne_arbre_etape`, `ligne_tache_console`, `ligne_detail_telechargement`,
  `ligne_synthese_build`, `ligne_classpath_module`) sont SUPPRIMÉS
  (−2 800 lignes) ; l'onglet Problèmes garde son adaptateur (une liste
  CLIQUABLE de diagnostics reste un cas légitime de RecyclerView).
- Les tâches ne s'affichent plus en français avec durées en place :
  elles s'affichent comme Gradle les écrit (« > Task :app:x », sans
  durée — la synthèse « N actionable tasks » et l'en-tête du panneau
  portent les comptes et la durée du build).
- Le canal Taches ne publie plus de progression de téléchargements en
  état — la ligne par artefact la remplace dans le flux.
- `PanneauConsoleFragment` reste exempté detekt (TooManyFunctions) ;
  `viderLot` extrait `lotDuCanal` pour tenir la limite de complexité.

## Fichiers modifiés (résumé)

### feature/editor — principal

- `LignesConsoleTexte.kt` (NOUVEAU) : `StyleLigne`, constructeurs de
  lignes purs, `DureesLisibles`/`OctetsLisibles` déménagés, traduction
  `EtapeSyncTooling.versEtatAffiche`.
- `GradleService.kt` : `EvenementConsoleTexte` v2 (canal + libelle +
  style + `Vider(canal)`) ; `ajouterLigne` balise ; `ajouterEtapeSync`
  écrit les lignes de transitions et porte `etapesSync` ;
  `ajouterTelechargement` écrit UNE ligne par artefact ;
  `publierResultatSync` conclut la console ; `publierEtatBuild` annule →
  ligne ; `marquerSyncEnCours` vide le canal SYNC ; `ajouterTache`,
  `LigneConsole`, `EtatTacheAffichee`, `EtatTelechargementBuild`,
  `telechargementsBuild` supprimés.
- `PanneauConsoleFragment.kt` : deux consoles par canal, application
  lotie par trame, styles au rendu, plus d'adaptateur ni de submitList.
- `PresentationTooling.kt` : `TexteTooling.Compose` + sa résolution.
- `RangeesConsole.kt` : ne garde que `FiltreCanalConsole` et
  `EtapeConsoleSync`.
- `PanneauxToolingAdapters.kt` : seul `ProblemesAdapter` reste.
- `PompeBuildTooling.kt` : drain inconditionnel du canal tâches, plus
  d'optionsTooling ; `OptionsTooling.kt` perd `afficherTaches`.
- `PanneauConfigToolingFragment.kt` / `ConfigToolingViewModel.kt` /
  `fragment_config_tooling.xml` : carte « Affichage » retirée.
- Layouts : `fragment_panneau_console.xml` (deux consoles) ; cinq layouts
  de rangées supprimés.
- Strings (fr/en) : suffixes d'étapes, téléchargement, conclusions de
  sync avec durée ; lignes de tâches et comptes de synthèse retirés.

### tooling:server

- `BuildHandler.kt` : ligne d'en-tête « Exécution des tâches : […]
  dans le projet X » (le vrai sélecteur Gradle se lit dans la console).

### core:bootstrap

- `EcrivainGradleCli.kt` : script v3 — le sélecteur erroné « task:FOO »
  (retour terrain : « Cannot locate tasks that match 'task:assembleDebug'
  as project 'task' not found ») est réécrit en « FOO » avec un
  avertissement ; `VersionneurScriptsTerminal.VERSION` 2 → 3
  (re-distribution automatique aux appareils installés).

### Tests

- `GradleServiceTest.kt` : canaux, styles, lignes par transition,
  téléchargements par artefact, conclusion d'annulation, vidages par
  canal.
- `LignesConsoleTexteTest.kt` (NOUVEAU) : constructeurs purs.
- `ToolingEditorViewModelTest.kt` / `BaseEditorViewModelTest.kt` /
  `ConfigToolingViewModelTest.kt` / `PresentationToolingTest.kt` /
  `PresentationToolingLocalisationTest.kt` / `ActivityEditorLayoutTest.kt` :
  adaptés ; `RangeesConsoleTest.kt` supprimé (la fonction a disparu).
- `EcrivainGradleCliTest.kt` : réécriture du sélecteur exécutée pour de
  vrai (avertissement + arguments réécrits, arguments intacts sinon).
