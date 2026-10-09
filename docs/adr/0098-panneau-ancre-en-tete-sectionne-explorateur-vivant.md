# ADR 0098 — Panneau inférieur aligné AndroidIDE, en-tête sectionné par onglet, explorateur vivant

- Statut : accepté (2026-10-10)
- Contexte : retour utilisateur après la v0.80.0 — l'explorateur de
  fichiers ne se met pas à jour (`.gradle/`, `app/build/` et le stockage
  privé restent invisibles après leur création par Gradle), le
  positionnement du panneau inférieur doit rejoindre celui d'AndroidIDE,
  l'en-tête du panneau porte une première section inutile sur l'onglet
  Console, et le redimensionnement du tiroir ignore la zone centrale.

## Décision

Quatre changements dans `feature:editor` :

1. **Panneau inférieur ancré sous la toolbar (alignement AndroidIDE).**
   Le code d'AndroidIDE est consulté à la source
   (`EditorBottomSheet.setOffsetAnchor(editorAppBarLayout)` :
   `behavior.expandedOffset = hauteur de l'AppBar + 1dp`). CodeIDE pose
   désormais `expandedOffset = haut de conteneur_editeur` — le sheet
   ÉTENDU s'arrête SOUS la toolbar et les onglets de fichiers, qui
   restent visibles et utilisables (jamais recouverts). L'ancre est
   reposée à chaque layout (rotation, onglets qui apparaissent).
   Le reste de la mécanique était déjà conforme : sheet dans le
   `CoordinatorLayout` du contenu du drawer (le sheet est poussé avec la
   zone centrale quand le tiroir s'ouvre), `fitToContents=false`,
   réserve B3 sous l'éditeur au peek (équivalent du `marginBottom` du
   `editor_container` d'AndroidIDE). `skipCollapsed` d'AndroidIDE est
   volontairement ÉCARTÉ : l'état REPLIE de CodeIDE est persisté et
   survit au retour système.

2. **En-tête du panneau sectionné par onglet.** L'onglet CONSOLE n'a
   plus de première section : `entete_panneau` passe GONE, la LIGNE
   TOOLING (pastille, « étape n/N », chrono, arrêt, progression) EST
   l'en-tête — son appui bascule replié ↔ mi-hauteur. Sans activité
   tooling, les ONGLETS du panneau deviennent la poignée repliée du
   sheet (peek = hauteur des onglets) : la feuille reste attrapable.
   Sur PROBLÈMES/JOURNAL, la première section reste et porte les
   INFORMATIONS correspondantes : sous-titre (compte de diagnostics
   plurialisé / compte d'entrées) et badge étendu aux problèmes (compte
   total de l'état Gradle). Le peek est composé par
   `PanneauToolingController.definirOnglet` : section (48 dp) + ligne
   (52 dp + progression) + onglets (48 dp, Console sans tooling
   uniquement).

3. **Explorateur vivant.** Deux mécanismes :
   - **Bascules câblées** : les actions `BasculerAffichageCompact`,
     `BasculerFichiersCaches`, `BasculerDossiersBuild` (existantes,
     testées, mais SANS interface) sont exposées dans le popover
     « Légende » (section « Affichage », trois `MaterialCheckBox`,
     enchaînables sans refermer). Corollaire : `masquerDossiersBuild`
     passe à `false` par défaut — l'utilisateur de l'IDE se sert de
     `.gradle/` et `app/build/` (suivi d'une sync Gradle) ; la case les
     masque d'un geste.
   - **Balayage périodique** : `DemarrerSurveillanceArbre` /
     `ArreterSurveillanceArbre` (posées par `onStart`/`onStop` de
     l'activité) lancent un balayage de 4 s qui re-liste les dossiers EN
     CACHE de l'arbre affiché (borné à 25, dépliés d'abord), compare
     (URI + type) et reconstruit l'arbre au changement ; un dossier
     disparu (NotFound) est purgé avec ses sous-arbres. Un échec
     d'accès interrompt le balayage SANS toucher l'état (le bandeau
     d'accès relève de `Rafraichir`).

4. **Redimensionnement → zone centrale.** `TiroirPoussantLayout.
   reevaluerTranslation()` applique la formule de `onDrawerSlide`
   (largeur × facteur) à chaque changement de largeur du tiroir —
   pendant le glissement de la poignée, à chaque trame de l'animation
   d'aimant et après la pose. Le tiroir OUVERT garde la zone centrale
   poussée au bord de sa NOUVELLE largeur.

## Justification

- **Balayage plutôt que FileObserver** : le port `FileSystem` abstrait
  les URI (SAF et privé). SAF n'a AUCUN observateur de dossier (les
  `ContentObserver` sont par autorité, pas par arbre), et le pont FUSE
  (`ResoudreRepertoireProjet`) n'est pas garanti sur tous les appareils
  (volumes amovibles, fournisseurs non « primary »). Le balayage
  comparatif traverse le port, couvre les DEUX arbres, et reste borné
  (25 dossiers × une requête par période de 4 s, uniquement pendant que
  l'espace est visible). La comparaison par (URI + type) évite les
  reconstructions d'arbre pour des changements de contenu seuls —
  seules les lignes visibles comptent.
- **Console sans première section** : le titre y dupliquait l'onglet
  sélectionné juste en dessous et le badge est réservé au Journal ; la
  ligne tooling porte déjà tout (activité, chrono, arrêt). C'est aussi
  la structure d'AndroidIDE (un ViewFlipper d'en-tête, la ligne de
  statut de build au peek).
- **expandedOffset plutôt que pleine hauteur** : la toolbar (titre du
  projet, Run, sync, tâches) et les onglets de fichiers doivent rester
  accessibles quand la console est étendue — c'est le comportement
  d'AndroidIDE et d'Android Studio.

## Conséquences

- `activity_editor.xml` : `entete_panneau` porte une colonne
  titre + `sous_titre_panneau` (informations de l'onglet actif).
- `popover_legende.xml` : section « Affichage » (trois cases).
- `EditorViewModel` : surveillance (démarrage/arrêt par action,
  `balayerArbre` interne), défaut `masquerDossiersBuild = false`.
- `PanneauToolingController` : `definirOnglet` compose le peek, la
  visibilité de la ligne tooling ne dépend plus de la première section.
- `TiroirPoussantLayout` : `reevaluerTranslation()` public interne.
- Chaînes fr/en : sous-titre d'informations (pluriels problèmes /
  entrées), section Affichage du popover, contentDescription enrichi.
- Tests : `SurveillanceArbreEditorViewModelTest` (6 tests : découverte
  `.gradle`, `app/build` déplié, purge, arbre privé, absence de
  balayage arrêté, arrêt), défauts des filtres retournés
  (`FiltresEtModelesEditorViewModelTest`), vues nouvelles
  (`ActivityEditorLayoutTest`).
