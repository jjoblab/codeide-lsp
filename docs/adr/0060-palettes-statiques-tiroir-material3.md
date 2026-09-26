# ADR 0060 — Palettes de couleurs statiques et tiroir citoyen Material 3

- **Statut** : accepté (v0.35.0, retour utilisateur du 2026-09-27 —
  « le thème et les paramètres ne sont pas à 100 % »)
- **Contexte** : retour utilisateur après la v0.34.2, quatre problèmes
  observés sur appareil réel :
  (1) couleurs dynamiques désactivées : l'éditeur et l'écran Diagnostic
  ne repartaient pas sur le thème par défaut — le callback
  `DynamicColors.applyToActivitiesIfAvailable()` enregistré au démarrage
  teste la **capacité de l'appareil** (Android 12+), pas le réglage
  utilisateur : une fois posé, il ré-applique l'overlay du fond d'écran
  à toute activité créée, même après désactivation (vérifié au
  bytecode du composant Material 1.14 — application dans
  `onActivityPreCreated`, précondition `DeviceSupportCondition`) ;
  (2) le tiroir de l'explorateur de fichiers ne suivait AUCUN thème
  choisi : ses ~90 références `codeide_explorateur_*` (gris-bleu
  dessinés à la main) vivaient hors des rôles Material 3 — insensibles
  aux couleurs dynamiques comme à toute palette ;
  (3) une seule palette statique existait (l'indigo de marque) ;
  (4) à la navigation vers une section des Paramètres, des vues
  « empilées » étaient rapportées.

## Décisions

1. **Point d'application possédé** (`core:ui.AppliquerApparence`,
   remplace `DynamicColors.applyToActivitiesIfAvailable` à l'échelle de
   l'application) : un callback `ActivityLifecycleCallbacks` maison
   applique, dans `onActivityPreCreated` (avant le gonflement du
   contenu, même crochet que Material), l'overlay **dynamique** OU la
   **palette statique** selon l'état courant — état lu du miroir
   synchrone au démarrage du processus, puis mis à jour à chaque
   émission des réglages ; un changement recrée les activités vivantes
   (un `theme.applyStyle` ne se retire pas). Le réglage utilisateur est
   LA source de vérité, la capacité de l'appareil n'est qu'une garde
   interne de Material. `MainActivity` ne pilote plus que le mode de
   nuit et la langue.
2. **Huit palettes statiques** (`PaletteCouleur`, clé DataStore
   `color_palette`, miroir `palette_couleurs`) : schémas Material 3
   complets jour/nuit générés par le moteur HCT embarqué dans la
   bibliothèque Material (`Scheme.light/dark`, même algorithme que
   Material Theme Builder — script `scripts/palettes` du dépôt de
   travail) : Indigo (marque, défaut), Bleu, Turquoise, Vert, Ambre,
   Rouge, Violet, Rose. Chaque palette est un `ThemeOverlay.CodeIDE.
   Palette*` (25 rôles M3) appliqué par `AppliquerApparence` quand les
   couleurs dynamiques sont coupées ; l'INDIGO référence les couleurs
   `codeide_*` existantes (aucun changement visuel à la mise à jour).
   Le sélecteur vit dans la section Apparence, désactivé (et signalé)
   tant que les couleurs dynamiques sont actives.
3. **Tiroir citoyen M3** : les ~90 références `codeide_explorateur_*`
   des layouts, drawables et teintes programmatiques migrent vers les
   rôles M3 (`colorSurface`/`colorSurfaceVariant`/`colorOnSurface(−
   Variant)`/`colorPrimary(−Container)`/`colorOutlineVariant`) — le
   tiroir suit le mode clair/sombre, les couleurs dynamiques ET la
   palette. Les teintes FONCTIONNELLES (statuts Git vert/ambre/rouge,
   pastilles d'état, dossiers décoratifs, snackbar inversé) restent
   fixes jour/nuit : la sémantique prime sur la décoration. Les
   drawables portent `?attr/…` (résolu par le thème d'inflation, API
   21+, minSdk 26) ; les teintes en code passent par les extensions
   `couleurRole`/`couleurPrimaire`/… de `core:ui` (`MaterialColors`).
4. **Sections opaques** : la racine du maître et des dix écrans de
   section porte `android:background="?attr/colorSurface"` — aucun
   écran ne peut plus laisser transparaître le précédent, quelle que
   soit la plateforme ; l'argument « bientôt disponible » du graphe
   redevient `string` (la navigation passe un nom d'entrée, jamais
   l'enum). Régression couverte par `NavigationSectionsTest` (le
   conteneur n'abrite qu'un fragment, avant et après recréation).

## Conséquences

- L'apparence colorée a UN point d'application possédé, testable, qui
  suit le réglage — la v0.34.0 dépendait d'un callback Material
  sémantiquement « capacité de l'appareil ».
- Une montée de version depuis v0.34.x converge toute seule : le miroir
  s'enrichit de la clé palette à la première émission (défaut INDIGO).
- Les rôles étendus (journal, canaux, statuts Git, accents par
  langage) ne sont PAS surchargés par palette : fonctionnels ou
  harmonisés au primaire courant (ADR 0059).
- Coût : légère perte de la rampe de gris à 5 niveaux du tiroir
  (aplatie en surface/surfaceVariant) — prix de la conformité thème.
