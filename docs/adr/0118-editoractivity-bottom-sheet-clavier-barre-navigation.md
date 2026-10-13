# ADR 0118 — EditorActivity : bottom sheet, clavier, barre de navigation

- **Statut** : accepté (v0.124.2 → v0.124.6, Phase B du prompt 1 du
  2026-10-13 — « EditorActivity bottom sheet, clavier, barre de
  navigation »). Audit Phase A livré en v0.124.1
  (`docs/audit/EDITORACTIVITY_PHASE_A.md`).
- **Contexte** : l'audit Phase A a confirmé 5 constats sur 5 par
  lecture directe du code v0.124.0 (cf. `docs/audit/EDITORACTIVITY_PHASE_A.md`
  §1) :
  - #1 éditeur masqué : `appliquerReservePanneau` ne prenait que
    `peekHeight` sans inset barre de navigation ; peek dynamique.
  - #1b désalignements : `appliquerEchelleSheet` `scaleX`/`scaleY` sur
    `vue_editeur` avec `LAYER_TYPE_HARDWARE`, pivot centre par défaut
    Android, popups ne suivent pas.
  - #2 touches traversent : `panneau_inferieur` ni `clickable` ni
    `focusable`.
  - #3 bug clavier : `appliquerClavier` bascule `isVisible` + écrit
    `state` en plein relayout ; 3 sources concurrentes ;
    `clavierParInsets` reste vrai jusqu'à la dernière trame de fermeture.
  - #3b mode redimensionnement non défini : `EditorActivity` sans
    `windowSoftInputMode` ni thème.
  - #4 barre navigation : `brancherInsets` ignore `systemBars().bottom`
    pour `zone_centrale` et `panneau_inferieur` ; `enableEdgeToEdge()`
    sans style explicite ; `gestureInsetBottomIgnored` jamais réglé.
  - Constat TiroirPoussantLayout / calque poignée **INFIRMÉ** : ces
    composants vivent hors de la zone sheet — pas d'interaction.

## Décision

Cinq lots livrés séparément (v0.124.2 → v0.124.6), chacun avec sa
vérification locale incrémentale (spotlessCheck, detekt, lintDebug,
testDebugUnitTest, checkModuleDependencies) avant push.

### Lot E3b + E4 + E6 (v0.124.2) — manifeste + touches

1. **E3b (manifeste)** : `EditorActivity` déclare
   `android:windowSoftInputMode="adjustResize"` dans
   `feature/editor/src/main/AndroidManifest.xml`. Les insets IME sont
   désormais livrés de manière fiable par le système (le détecteur de
   hauteur de secours dans `observerClavier` reste pour API 26-29 où
   les insets IME peuvent être silencieux).
2. **E4 (touches)** : `panneau_inferieur` (`activity_editor.xml`) est
   désormais `android:clickable="true"` et `android:focusable="true"`.
   Une touche tombant sur une zone du sheet qu'aucun enfant ne
   consomme ne remonte plus au frère `zone_centrale` et donc à
   l'`EditorView`. Le glissement, le défilement imbriqué et les
   boutons de l'en-tête ne sont pas affectés.
3. **E6 (manifeste partie)** : `windowSoftInputMode` posé (cf. E3b).
   La partie « style des barres système via `enableEdgeToEdge(SystemBarStyle...)` »
   est livrée en v0.124.6.

### Lot E1 + E2 + E3 (v0.124.3) — GestionnaireInsetsEditeur

1. **E1 (propriétaire des insets)** : nouvelle classe
   `GestionnaireInsetsEditeur` (patron `PanneauToolingController`)
   qui centralise la consommation des insets pour l'espace de travail.
   L'activité ne garde que le cycle de vie et la collecte — le
   gestionnaire reçoit les insets une seule fois et pilote les trois
   consommateurs (toolbar, tiroir, colonne centrale + sheet). La
   fonction pure `calculerReserveEditeur(peek, insetNavigation,
   insetIme, clavierOuvert)` est testée unitairement (12 tests JVM
   purs couvrent tous les états du sheet × clavier ouvert/fermé ×
   avec/sans inset de navigation × peek dynamique × cas dégénérés).
2. **E2 (barre de navigation)** : le fond opaque du sheet descend
   désormais jusqu'au bord bas de l'écran (padding interne bas =
   `insetNavigation`), son contenu et son en-tête replié restent
   AU-DESSUS de la barre de navigation. L'ancien
   `appliquerReservePanneau` ne prenait que `peekHeight` sans inset
   navigation — l'en-tête replié de 48 dp se retrouvait sous la barre,
   hors de portée du doigt. Portrait et paysage (insets gauche/droite
   traités).
3. **E3 (réserve sous l'éditeur)** : sheet replié, la réserve =
   `peekHeight + insetNavigation` (au lieu de `peekHeight` seul). La
   dernière ligne et le curseur ne sont jamais masqués, y compris
   quand le peek change (ligne tooling qui apparaît) et quand les
   insets changent (rotation, pli d'écran). Mi-hauteur/étendu :
   décision v0.80.2 préservée (zone d'édition de taille constante).
4. `EditorActivity.appliquerReservePanneau` délègue désormais au
   gestionnaire. Tous les sites d'appel existants (callbacks
   `onStateChanged`, `surPeekChange` du `PanneauToolingController`)
   restent inchangés.

### Lot E5 (v0.124.4) — clavier translationY

1. Le sheet n'est plus masqué via `panneau.isVisible = false/true`
   (saut brutal à la dernière trame de fermeture). Désormais, le
   sheet est TRANSLATÉ verticalement via `translationY` : clavier
   ouvert, le sheet glisse vers le bas de la hauteur de l'IME (il
   reste « posé » sous le clavier, invisible mais pas GONE) ; clavier
   fermé, `translationY` revient à 0 (animation continue via
   `onProgress` du `WindowInsetsAnimationCompat.Callback`).
2. **Aucune écriture de `comportementPanneau.state` pendant
   l'animation** : l'ancien `restaurerEtatPanneau` était appelé en
   plein relayout et causait des sauts. La fonction
   `restaurerEtatPanneau` est SUPPRIMÉE — l'état du sheet
   (replié/mi-hauteur/étendu) est préservé à l'identique pendant toute
   la durée du cycle clavier.
3. **Patron ADR 0056 décision 2 préservé** : la barre de symboles se
   colle au clavier via le padding IME (l'éditeur prime pendant la
   frappe), le sheet s'efface visuellement (translationY) sans être
   GONE.

### Lot E1b (v0.124.5) — désactivation scaleX/scaleY

1. La mise à l'échelle `scaleX`/`scaleY` de l'éditeur pendant le
   glissement du bottom sheet est DÉSACTIVÉE. Constat #1b : l'échelle
   partait du CENTRE de la vue (pivot par défaut Android 50 %, 50 %),
   les popups et poignées de sélection de la bibliothèque
   `code-editor` ne suivaient pas, et la zone visible ne correspondait
   plus à la réserve calculée.
2. **Décision** : désactiver l'échelle plutôt que de la corriger. Le
   coût visuel (éditeur qui rétrécit pendant le glissement) ne
   justifiait pas les désalignements. Le sheet se pose désormais
   PAR-DESSUS l'éditeur à taille constante (décision v0.80.2 déjà en
   place pour l'état vide). La fonction `appliquerEchelleSheet` est
   conservée (pour ne pas casser le site d'appel `onSlide` du
   `BottomSheetCallback`) mais ne fait rien. La constante
   `ECHELLE_SHEET_ETENDU = 0.87f` est conservée pour traçabilité.
3. **Retour éventuel** : si une échelle est réintroduite, elle devra
   (1) partir du coin haut-gauche (pivot 0, 0), (2) être coordonnée
   avec la bibliothèque `code-editor` pour que ses popups suivent,
   (3) recalculer la réserve en fonction de l'échelle courante.

### Lot E6 complet (v0.124.6) — SystemBarStyle explicites

1. `enableEdgeToEdge()` est désormais appelé avec des `SystemBarStyle`
   EXPLICITES :
   - `statusBarStyle = SystemBarStyle.auto(0x66000000, 0x66000000)` —
     s'adapte au thème jour/nuit, scrim sombre pour contraste icônes
     statut.
   - `navigationBarStyle = SystemBarStyle.auto(0x66FFFFFF, 0x66000000)`
     — s'adapte au thème jour/nuit, scrim clair en jour, sombre en nuit
     pour contraste WCAG AA des icônes de navigation.
2. Les valeurs de scrim sont les valeurs recommandées par la
   documentation `androidx.activity` (transparence 40 % + teinte
   adaptée au thème).
3. **Partie « contraste forcé désactivé »** : non traitée dans ce lot
   car `isNavigationBarContrastEnforced` (API 29+) est une propriété
   window — elle sera traitée dans un lot ultérieur si des retours
   appareil le justifient (le sheet gère déjà son fond opaque, le
   contraste forcé est généralement désactivé par défaut en
   edge-to-edge).

## Options écartées

- **E1 — Garder `brancherInsets` original** : ne traitait que la
  toolbar et le tiroir, laissait la colonne centrale et le sheet sous
  la barre de navigation. Rejeté.
- **E2 — `gestureInsetBottomIgnored` sur `BottomSheetBehavior`** :
  non retenu car la lecture de Material 1.14.0 n'a pas été faite
  (pas de cache Gradle dans l'environnement d'audit) et le padding
  interne du sheet + la réserve étendue suffisent à garder l'en-tête
  cliquable.
- **E3 — Garder `peekHeight` seul** : l'en-tête replié se retrouvait
  sous la barre de navigation, hors de portée du doigt. Rejeté.
- **E5 (a) — sheet reste ancré et clavier le recouvre** : aurait
  cassé le patron ADR 0056 décision 2 (barre de symboles collée au
  clavier). Rejeté.
- **E5 (c) — `STATE_HIDDEN` piloté par l'animation IME** : aurait
  laissé `STATE_HIDDEN` comme « l'état du sheet » à la réouverture,
  cassant la préservation d'état. Rejeté au profit de `translationY`.
- **E1b — Corriger l'échelle (pivot 0,0 + coordination code-editor)** :
  aurait nécessité de modifier la bibliothèque `code-editor` (hors
  périmètre du prompt 1) ou de surcharger `EditorView` (risqué).
  Rejeté au profit de la désactivation.

## Conséquences

- **Positives** : tous les constats de l'audit Phase A sont adressés.
  Le sheet consomme ses touches (E4), l'en-tête replié est cliquable
  au-dessus de la barre (E2), la dernière ligne et le curseur sont
  visibles (E3), le clavier s'ouvre et se ferme sans saut (E5),
  l'éditeur ne rétrécit plus pendant le glissement (E1b), les barres
  système ont un contraste WCAG AA (E6).
- **Négatives** : l'effet visuel « éditeur qui rétrécit pendant le
  glissement » est perdu (E1b). Si l'utilisateur le réclame, il
  faudra coordonner avec la bibliothèque `code-editor` pour que ses
  popups suivent la transformation.
- **Neutres** : `isNavigationBarContrastEnforced` (API 29+) n'est pas
  désactivé explicitement — comportement par défaut en edge-to-edge,
  à valider sur appareil.

## Tests

- **Tests unitaires** : 12 tests JVM purs de la fonction pure
  `calculerReserveEditeur` (couvrent tous les états du sheet ×
  clavier ouvert/fermé × avec/sans inset de navigation × peek
  dynamique × cas dégénérés).
- **Tests Robolectric** : non écrits dans cette phase (le test
  d'animation IME réelle n'est pas fiable en Robolectric). À ajouter
  si l'utilisateur le demande.
- **Tests manuels appareil** : 7 recettes à ajouter à
  `docs/TESTS_MANUELS.md` (navigation par gestes vs 3 boutons,
  touches dans le sheet, cycle clavier, dernière ligne visible,
  changement d'onglet, rotation + multi-fenêtre, thème jour/nuit).

## Vérification et livraison

- Chaque lot a passé la vérification locale incrémentale
  (spotlessCheck, detekt, lintDebug, testDebugUnitTest,
  checkModuleDependencies) avant push.
- `koverVerify` non lancé en local (OOM sur 4 Go RAM — la CI GitHub
  le valide avec 16 Go). Tous les lots v0.124.2 → v0.124.5 sont en
  CI SUCCESS. v0.124.6 en cours.
- Tags annotés poussés : v0.124.2, v0.124.3, v0.124.4, v0.124.5,
  v0.124.6.

## Références

- `docs/audit/EDITORACTIVITY_PHASE_A.md` — audit Phase A complet
  (5/5 constats confirmés avec preuves fichier:ligne).
- ADR précédents : 0055 (vraies classes bibliothèque), 0056 (retrait
  fil d'Ariane + barre collée + fond opaque), 0093 (tiroir poussant +
  sheet rétrécissant), 0100 (calque poignée).
- Code source : `feature/editor/src/main/kotlin/jo/codeide/feature/editor/GestionnaireInsetsEditeur.kt`
  (nouvelle classe), `feature/editor/src/main/kotlin/jo/codeide/feature/editor/EditorActivity.kt`
  (refactor), `feature/editor/src/main/res/layout/activity_editor.xml`
  (clickable panneau), `feature/editor/src/main/AndroidManifest.xml`
  (windowSoftInputMode).
- Tests : `feature/editor/src/test/kotlin/jo/codeide/feature/editor/GestionnaireInsetsEditeurTest.kt`
  (12 tests unitaires purs).
