# ADR 0074 — Console hybride : zone texte annexée pour les lignes brutes (performance console)

- Statut : accepté (2026-09-30)
- Contexte : phase 1 du roadmap (`docs/ROADMAP.md`) — problème n°1 connu de
  la v0.41.0 : « un build de 725 ms prend 2 minutes à s'afficher »

## Contexte

Depuis la v3, les lignes stdout/stderr brutes de Gradle voyageaient dans
`EtatGradle.lignes` (une `List<LigneConsole>` bornée à 2 000) derrière le
`StateFlow` process-wide du `GradleService`. Chaque ligne de sortie d'un
build déclenchait donc la chaîne complète :

1. `ajouterLigne` copiait la liste entière (`courant.lignes + ligne`) et
   émettait un NOUVEL état — O(N) par ligne ;
2. `PanneauConsoleFragment.rendre()` collectait l'état et appelait
   `construireRangeesConsole`, qui FILTRAIT et reconstruisait toutes les
   rangées — O(N) par ligne ;
3. `ListAdapter.submitList` passait DiffUtil sur les N rangées (la v0.41.1
   avait de surcroît MÉLANGÉ les rangées brutes aux tâches) — O(N) par
   ligne ;
4. tous les AUTRES collecteurs de l'état (en-tête du panneau, activité)
   se réveillaient pour rien à chaque ligne.

Cumulé : **O(N²)** — un build de 725 ms produisant ~700 lignes mettait
2 minutes à s'afficher. La console était inutilisable en pratique.

## Décision

### 1. Architecture hybride (comme la console d'Android Studio)

Le corps de l'onglet Sortie se scinde en deux zones empilées verticalement
(dans `fragment_panneau_console.xml`, séparées par un filet) :

- **Zone structurée** (RecyclerView, poids 1) : l'arbre des étapes de
  sync, les tâches du build, la synthèse — peu de rangées par
  construction, mises à jour EN PLACE via DiffUtil (O(1) par mise à jour,
  payloads granulaires v0.40.1) ;
- **Zone texte** (ScrollView + TextView monospace 11sp, poids 1) : les
  lignes stdout/stderr brutes, appliquées par `append()` direct — O(1) par
  ligne, jamais de DiffUtil, jamais de reconstruction.

La zone texte n'est visible qu'en **vue BUILD** — la vue Sync reste
l'arbre + son pied (le plan de l'aperçu v5 : il n'existe de toute façon
aucune pompe de sortie du canal Sync en production, seul le build
alimente la zone).

```
┌─────────────────────────────────────┐
│ > Task :app:compileDebugKotlin 6,1s │  ← RecyclerView (structuré)
│ > Task :app:mergeDebugResources 2,3s│
├─────────────────────────────────────┤
│   e: file:///…/Main.kt:12:3 error  │  ← TextView (texte brut)
│   Downloading kotlin-stdlib…        │  ← TextView
└─────────────────────────────────────┘
```

### 2. Flux dédié — le rejeu EST le tampon borné

`GradleService` expose `lignesBrutes : SharedFlow<EvenementConsoleTexte>`
construit avec `MutableSharedFlow(replay = 2_000,
extraBufferCapacity = 2_048, onBufferOverflow = DROP_OLDEST)`. Les
événements sont de deux genres :

- `EvenementConsoleTexte.Ligne(flux, texte, apaisee)` — une ligne brute
  (l'avertissement bénin du daemon voyage apaisé, C5 inchangé) ;
- `EvenementConsoleTexte.Vider` — la zone texte repart vierge, ÉMIS aux
  MÊMES instants que la vidange historique de `lignes` : `suivreBuild`
  (nouveau build) et `attacher` (rattachement d'un espace).

Le **cache de rejeu est le tampon borné** (`NB_LIGNES_MAX` = 2 000, tête
tronquée) : une vue qui s'abonne rejoue l'historique puis suit le direct,
sans instantané séparé, sans course entre instantané et direct. La
reconstruction est CORRECTE PAR CONSTRUCTION : le rejeu est une fenêtre
TÊTE-tronquée, or un `Vider` tombé de la fenêtre emporte avec lui tout ce
qui le précédait — une ligne d'avant le dernier `Vider` conservé ne peut
donc jamais rester seule en scène.

`DROP_OLDEST` : un abonné lent (plus de ~4 000 événements de retard) ne
bloque JAMAIS la pompe — le thread de vidange du client doit pouvoir
vider les canaux sous pression (leçon ADR 0057 : contre-pression vs
connexion perdue). Les lignes tombées de SA fenêtre sont restituées à son
prochain réabonnement (le rejeu est la vérité).

### 3. `ajouterLigne` n'émet plus d'état

La publication d'une ligne brute devient : gardes historiques (buildId
suivi, build non annulé) puis `tryEmit` — O(1), aucune émission d'état.
`EtatGradle.lignes` ne porte plus que les genres typés (`Tache`, `Etape`)
; `LigneConsole.Sortie` est SUPPRIMÉ (type mort). Les gardes et
l'apaisement C5 sont inchangés ; l'état ne s'émet plus qu'aux transitions
structurées.

### 4. Application en place, lotie par trame

Le fragment pose un `Editable` vierge dans le `TextView`, puis chaque
événement s'accumule (`lignesEnAttente`) et un vidage PLANIFIÉ
(`View.post`, dédupliqué) applique le lot en UN SEUL `append` :

- une seule notification de changement et une seule passe de layout PAR
  TRAME, quel que soit le rythme des lignes (le `TextView` + `ScrollView`
  relayoutent au prochain frame — les appends d'une même trame se
  coalescent) ;
- la reconstruction après rotation rejoue jusqu'à 2 000 lignes en UN lot ;
- les spans de couleurs (stderr rouge `codeide_stderr`, stdout
  `codeide_stdout`, apaisé atténué alpha 140) voyagent avec chaque ligne
  (`SpannableString` + `ForegroundColorSpan`, copiés par
  `SpannableStringBuilder.append`) — le langage visuel de la v0.41.1 est
  conservé.

### 5. Auto-défilement honnête

Le bas est suivi tant que l'utilisateur y est resté : l'intention de
lecture est capturée AVANT l'ajout (`scrollY` à une ligne du fond,
tolérance 16 dp) ; un lecteur remonté dans l'historique n'est jamais
rabattu en bas ; un build fini ne défile plus (aucune ligne n'arrive,
rien ne le déclenche — même sémantique que la liste structurée « le
suivi s'arrête quand la liste cesse de grandir »). Défilements
dédupliqués : un post vivant au plus.

### 6. Cycle de vie de la collecte (le piège du rejeu)

La collecte vit sur le `viewLifecycleOwner.lifecycleScope` — PAS
`repeatOnLifecycle(STARTED)` :

- **survivre à un `onStop`** : le retour d'un onglet du panneau ne doit
  PAS ré-abonner (chaque nouvel abonnement rejoue l'historique — les
  lignes doubleraient) ;
- **mourir avec la vue** : une rotation détruit la vue et son collecteur ;
  la nouvelle vue s'abonne et reconstruit exactement depuis le rejeu.

Le lot en attente et les posts en vol sont réinitialisés à
`onDestroyView` (le rejeu reconstruit tout — garder le lot doublerait) ;
le runnable de vidage capture SA liaison et s'ignore si la vue a changé.

## Alternatives rejetées

- **`EtatGradle.texteBrut: String` dans le StateFlow** : l'émission par
  ligne revenait (copie de chaîne O(N) + réveil de tous les collecteurs) —
  le O(N²) changement de conteneur.
- **`StringBuilder` explicite côté service + instantané** : deux sources
  de vérité (tampon et rejeu) et une course entre l'instantané et le
  direct à l'abonnement ; le rejeu unique élimine les deux.
- **Un seul RecyclerView hybride** (rangées brutes + rangées structurées
  mélangées, tenté en v0.41.1) : DiffUtil reste O(N) par ligne — la cause
  même du problème.
- **`ConcatAdapter` (structuré + adaptateur texte)** : un `TextView` par
  rangée, la même reconstruction par ligne, plus la complexité de
  l'adaptateur.

## Conséquences

- Un build de 725 ms s'affiche en moins d'une seconde (objectif de la
  phase 1) : O(N) au total, une passe de layout par trame.
- `etat` ne s'émet plus qu'aux transitions structurées — les en-têtes et
  les autres collecteurs ne se réveillent plus par ligne.
- La vue Build perd l'entrelacement ligne-à-ligne des v0.41.1 au profit
  des deux zones (comme Android Studio : tâches en haut, sortie brute en
  bas) — les diagnostics continuent de vivre dans l'onglet Problèmes.
- L'accessibilité de la zone texte devient un bloc monospace (une
  `TextView`) au lieu de rangées individuelles : compromis assumé de la
  performance, à revisiter si le besoin remonte.
- La mémoire du service croît du rejeu (≈ 2 000 événements de petite
  taille) — du même ordre que l'ancienne fenêtre `lignes` : neutre.
- Le fragment `PanneauConsoleFragment` est TOUJOURS exempté detekt
  (TooManyFunctions) : le lotissement et le défilement ajoutent leurs
  petites fonctions au contrat de câblage.

## Fichiers modifiés (résumé)

### feature/editor — principal

- `GradleService.kt` : `EvenementConsoleTexte` (nouveau, remplace
  `LigneConsole.Sortie`, supprimé) ; `lignesBrutes` (SharedFlow dédié,
  rejeu = tampon) ; `ajouterLigne` sans émission d'état ; `Vider` à
  `suivreBuild`/`attacher` ; `CAPACITE_TAMPON_DIRECT`.
- `RangeesConsole.kt` : `RangeeConsole.LigneGradle` supprimé ; vue BUILD =
  tâches + synthèse seules ; bloc mort SYNC-Sortie et
  `NB_LIGNES_GRADLE_SOUS_ETAPE` supprimés.
- `PanneauxToolingAdapters.kt` : `LigneGradleHolder` et `Type.LIGNE_GRADLE`
  supprimés ; `ALPHA_LIGNE_APAISEE` déménagé au fragment.
- `PanneauConsoleFragment.kt` : collecte du flux dédié, accumulation,
  vidage loti par trame, spans de couleurs, auto-défilement dédupliqué,
  visibilité de la zone par vue, état vide honnête, nettoyage à
  `onDestroyView`.
- `EditorViewModel.kt` : `lignesBrutesConsole` exposé à côté de
  `etatGradle`.
- Layouts : `fragment_panneau_console.xml` (corps scindé en deux zones +
  filet + état vide en couche) ; `ligne_gradle.xml` SUPPRIMÉ.

### Tests

- `GradleServiceTest.kt` : lignes brutes vérifiées sur le rejeu
  (`replayCache`) ; bornage 2 000 ; gardes buildId/annulation ; `Vider` ;
  « plus aucune ligne brute dans l'état » ; apaisement C5.
- `RangeesConsoleTest.kt` : vue Build sans lignes brutes, vue Sync sans
  mélanges.
- `ToolingEditorViewModelTest.kt` : câblage vérifié APRÈS le dernier
  `Vider` (helper `lignesZoneTexteApresDernierVider`).
