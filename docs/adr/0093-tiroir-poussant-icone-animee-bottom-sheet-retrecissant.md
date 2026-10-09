# ADR 0093 — Tiroir qui pousse la zone centrale + icône animée + bottom sheet rétrécissant

- Statut : accepté (2026-10-09)
- Contexte : mission Tiroir Effets E0 (prompt `prompt-agent-tiroir-effets.md`).

## Décision

Trois effets visuels sont introduits dans `EditorActivity` :

1. **Icône de tiroir animée** (E1) : `SidebarToggleDrawable` en Kotlin
   dans `core:ui`, miniature d'écran dont le panneau gauche s'élargit et
   se colore quand le tiroir s'ouvre. Pilotée par le `slideOffset` du
   `DrawerLayout`. Clic bascule (ouvre/ferme). Couleurs du thème (pas en
   dur). Dimensions en dp. Support RTL.

2. **Tiroir qui pousse la zone centrale** (E2) : sous-classe de
   `DrawerLayout` qui translate le conteneur de contenu désigné de
   `largeur × slideOffset × facteur` dans `onDrawerSlide`. Facteur
   configurable (défaut = 0,95 « FULL » : un liseré reste visible pour
   refermer). Voile transparent. Contenu enveloppé dans une carte à
   coins arrondis (28 dp). **Aucune translation en mode permanent**
   (grand écran, ADR 0026).

3. **Zone centrale qui rétrécit** (E3) : dans le callback `onSlide` du
   `BottomSheetBehavior`, le conteneur de l'éditeur est mis à l'échelle
   (`scaleX = scaleY = 1 − slideOffset × (1 − 0,87)`), de 100 % à 87 %
   quand le panneau est étendu. Pivot recalculé aux changements
   d'insets/rotation. Couche matérielle activée pendant le glissement
   seulement. Adapté à l'état mi-hauteur (`halfExpandedRatio = 0,5`).

## Justification

Le choix d'une **sous-classe de `DrawerLayout`** (comme AndroidIDE)
plutôt qu'une vue maison (comme l'ancien projet) est motivé par :
- préservation du geste de bord, du retour système, du focus clavier ;
- compatibilité avec `DrawerLayout.LayoutParams` et les outils
  d'accessibilité ;
- moins de code à maintenir qu'une vue maison.

L'effet de **bottom sheet rétrécissant** (E3) n'existe pas dans
AndroidIDE (qui ne gère pas l'état mi-hauteur). L'adaptation consiste à
interpoler l'échelle entre replié (100 %), mi-hauteur (≈ 93 %) et étendu
(87 %), de façon que le texte reste lisible et le curseur jamais caché.

## Interaction avec l'ADR 0026

En mode permanent (grand écran, `smallestScreenWidthDp ≥ 600`), le
tiroir est verrouillé ouvert et l'icône masquée. **Aucune translation**
n'est appliquée : le contenu est simplement à côté du tiroir, comme
avant. L'effet E2 est désactivé par une garde `if (!estGrandEcran)`.

## RTL

L'icône animée est mise en miroir en RTL (le panneau gauche devient le
panneau droit). La translation du tiroir suit la gravité (start →
vers la droite ; end → vers la gauche).

## Conséquences

- Nouvelle classe `TiroirPoussantLayout` dans `feature:editor` (sous-
  classe de `DrawerLayout`).
- Nouvelle classe `SidebarToggleDrawable` dans `core:ui` (Drawable pur
  Kotlin, géométrie testable en JVM).
- `activity_editor.xml` : contenu enveloppé dans une `MaterialCardView`
  à coins arrondis.
- `EditorActivity` : callback `onDrawerSlide` met à jour la fraction de
  l'icône ; callback `onSlide` du bottom sheet met à l'échelle.
- `dimens.xml` : `editor_container_corners` = 28 dp, facteurs
  d'échelle/translation.

## Références

- ADR 0026 (tiroir permanent sur grand écran)
- ADR 0056 (barre de symboles dans la colonne centrale)
- AndroidIDE `ContentTranslatingDrawerLayout` (comportement de
  référence, GPL-3.0 — réécrit, pas copié)
- Annexe A du prompt (icône `SidebarToggleDrawable` de l'ancien projet)
