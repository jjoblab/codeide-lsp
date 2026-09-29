# ADR 0072 — Correctif du progress circulaire des étapes (anneau 16 dp + animateur partagé)

- Statut : accepté (2026-09-29)
- Contexte : prompt de suivi §5 — bug constaté en 0.40.0

## Contexte

Le marqueur d'étape en cours dans l'arbre de sync utilisait un
`CircularProgressIndicator` Material 3 (`Widget.Material3.CircularProgressIndicator`)
forcé à 16 dp via `app:indicatorSize="@dimen/editor_marqueur_etape_taille"`
avec `layout_width/height=match_parent` dans `ligne_arbre_etape.xml`.

**Problèmes observés** :

1. Le style M3 par défaut est prévu pour ~40 dp (épaisseur de trace 4 dp,
   `indicatorInset`). À 16 dp, l'anneau était **écrasé/rogné** — l'inset
   Material n'était pas conçu pour cette taille.
2. Le `RecyclerView` basculait `visible/gone` le `CircularProgressIndicator`
   à chaque `lier()` du `EtapeArbreHolder` (un marqueur par type d'état) →
   l'animation **redémarrant** à chaque tick de durée de l'étape en cours,
   créant un **clignotement** visible.
3. `DefaultItemAnimator.supportsChangeAnimations = true` (défaut) : un
   simple changement de durée d'étape déclenchait une animation de
   changement (fade) sur la rangée entière.
4. `onBindViewHolder` sans payloads : rebind complet à chaque tick de
   durée → `setVisibility(visible/gone/visible)` sur l'indicateur Material
   → redémarrage d'animation.

## Décision

Remplacer le `CircularProgressIndicator` Material par une `View` dédiée
[`AnneauTournant`] + un drawable vectoriel d'anneau (16 dp, trait 2 dp)
tourné par un **`ObjectAnimator` global UNIQUE partagé** via
[`AnneauTournantState`] (singleton).

### Spécifications (fidèles à l'aperçu v3, §6)

- **Taille** : 16 dp × 16 dp (drawable vectoriel `anneau_etape_en_cours.xml`).
- **Trait** : 2 dp, `strokeLineCap="round"`.
- **Arc** : ~270° (3/4 du cercle) — la rotation `ObjectAnimator` complète
  la rotation visuelle.
- **Piste** : absente (pas de `trackColor` — l'arc seul suffit).
- **Couleur de l'arc** : `backgroundTintList` posé par l'appelant (couleur
  de canal `colorCanalSync` harmonisée via `ThemeHarmonizer`).
- **Rotation** : 360°/s en linéaire, 1 s par tour.
- **Respect de « réduire les animations »** : `ValueAnimator` lit
  automatiquement `Settings.Global.ANIMATOR_DURATION_SCALE` — à 0, l'anneau
  reste statique mais visible.

### Implémentation

- `AnneauTournant` : `View` avec `background = anneau_etape_en_cours.xml`,
  `isClickable = false`, `isFocusable = false`. `onAttachedToWindow` →
  `AnneauTournantState.attacher(this)`, `onDetachedFromWindow` →
  `AnneauTournantState.detacher(this)`. `setVisibility` propage au singleton
  (si `isAttachedToWindow`).
- `AnneauTournantState` : singleton `internal object` avec un
  `ObjectAnimator` global, un `MutableSet<View>` d'abonnées. `attacher(vue)`
  crée l'animateur à la première attache, `detacher(vue)` l'annule à la
  dernière détache — **zéro fuite d'animateur**.
- `DiffUtil.getChangePayload` : payloads granulaires (`EtatEtapeArbre` /
  `EtatTacheAffichee`) → `onBindViewHolder(holder, position, payloads)` ne
  rebondit QUE le marqueur + durée, pas de rebind complet, pas de
  redémarrage d'animation.
- `DefaultItemAnimator.supportsChangeAnimations = false` +
  `changeDuration = 0` dans `PanneauConsoleFragment.onViewCreated` — plus
  d'animation de changement (clignotement) sur la console.

### Tests

`AnneauTournantTest` (6 cas Robolectric) :
- dimensions du drawable (16 dp × 16 dp) ;
- attache d'une vue démarre l'animateur global ;
- détache de la dernière vue arrête l'animateur (pas de fuite) ;
- un seul animateur partagé entre plusieurs vues visibles (détacher une ne
  l'arrête pas) ;
- `setVisibility` sur une vue NON attachée ne démarre pas l'animateur ;
- verrou anti-dérive : `editor_marqueur_etape_taille` reste 16 dp.

**Limites assumées** : Robolectric ne déclenche pas
`View.onAttachedToWindow` sans `Activity` réelle — les tests appellent
directement `AnneauTournantState.attacher/detacher`. La rotation visuelle
frame par frame reste à valider sur appareil/émulateur (cf. RAPPORT
Phase 0 §7).

## Conséquences

- `ligne_arbre_etape.xml` : `CircularProgressIndicator` remplacé par
  `<jo.codeide.feature.editor.AnneauTournant>` — un seul animateur pour
  toutes les étapes visibles.
- `PanneauxToolingAdapters.EtapeArbreHolder` : `lier()` / `lierMajEtat()`
  / `majMarqueur()` / `majDuree()` — mise à jour en place via payloads.
- `ConsoleToolingAdapter.DiffRangeesConsole.getChangePayload` : payloads
  granulaires pour `EtapeArbre` et `Tache`.
- `PanneauConsoleFragment.onViewCreated` :
  `DefaultItemAnimator.supportsChangeAnimations = false`.
- Plus de dépendance Material `CircularProgressIndicator` dans
  `ligne_arbre_etape.xml` (supprimée).

## Fichiers modifiés

- `feature/editor/src/main/kotlin/jo/codeide/feature/editor/AnneauTournant.kt` (nouveau)
- `feature/editor/src/main/res/drawable/anneau_etape_en_cours.xml` (nouveau)
- `feature/editor/src/main/res/layout/ligne_arbre_etape.xml` (modifié)
- `feature/editor/src/main/kotlin/jo/codeide/feature/editor/PanneauxToolingAdapters.kt` (modifié)
- `feature/editor/src/main/kotlin/jo/codeide/feature/editor/PanneauConsoleFragment.kt` (modifié)
- `feature/editor/src/test/kotlin/jo/codeide/feature/editor/AnneauTournantTest.kt` (nouveau)
- `feature/editor/src/test/kotlin/jo/codeide/feature/editor/ActivityEditorLayoutTest.kt` (modifié)
