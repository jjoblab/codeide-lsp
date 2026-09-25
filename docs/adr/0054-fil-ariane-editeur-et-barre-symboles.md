# ADR 0054 — Fil d'Ariane de l'éditeur et barre de symboles au clavier

- **Statut** : accepté (v0.32.3, retour d'appareil réel sur l'étape 31)
- **Contexte** : retour utilisateur du 2026-09-26, quatrième point :
  « Analyse le dépôt code-editor, il a la fonctionnalité breadcrumb qu'il
  ajoute, et aussi virtualkey à ajouter dans l'entête du bottom sheet
  behavior qui deviendra visible quand le clavier est visible. » Le dépôt
  [jjoblab/code-editor](https://github.com/jjoblab/code-editor) — la
  bibliothèque d'édition elle-même — porte en tête (après le tag v3.37.0
  que CodeIDE consomme) deux vues de chrome : `BreadcrumbBar`
  (`view/chrome/BreadcrumbBar.java`, fil « fichier › classe › méthode »
  suivant le caret, inspirée du projet de référence/IntelliJ) et `SymbolBarView`
  (`view/chrome/SymbolBarView.java`, touches de symboles au-dessus de
  l'IME, inspirée de l'EditorSymbolBar du projet de référence). Ces deux classes
  n'existent pas dans la version publiée 3.37.0 : les transposer dans
  CodeIDE plutôt que de mettre à jour la dépendance (bump de version =
  redéployer la bibliothèque, hors périmètre d'un correctif).

- **Décisions** :

1. **Fil d'Ariane transposé, scanner maison.** La vue
   `VueFilArianeEditeur` (feature/editor) reprend la géométrie de la
   `BreadcrumbBar` — 28 dp, monospace 12 sp, chevrons « › », dernier
   segment en gras — avec deux écarts assumés : les couleurs suivent le
   **thème Material** de l'application (jour/nuit) au lieu de constantes
   sombres, et la vue se mesure à la largeur de son contenu pour défiler
   dans un `HorizontalScrollView` (le chemin d'un vrai projet dépasse
   l'écran ; la bibliothèque ne défile pas, elle déborde). La
   bibliothèque alimente ses segments par le SPI `SymbolProvider` ;
   celui-ci n'a aucune implémentation embarquée en 3.37.0 (les langues
   LSP arrivent avec `cel-lsp`, hors périmètre) : le scanner
   `SymbolesEnglobants` est donc maison — déclarations Kotlin/Java
   repérées ligne à ligne, **bornes par profondeur d'accolades**
   (profondeur mémorisée au début de la ligne de déclaration : les
   accolades de la ligne sont déjà comptées quand son `\n` arrive —
   piège réel trouvé au traçage), expressions sans accolades (`fun x()
   = …`) refermées par la déclaration suivante, annotations et
   modificateurs élagués, méthodes Java reconnues par le motif
   `Type nom(…) {` avec liste de mots non déclaratifs en garde.
   Limites assumées et documentées : accolades dans les chaînes,
   annotations à arguments, `companion object` sans nom — affichage
   indicatif, jamais un pilote d'édition. Segments : dossier › … ›
   fichier (depuis `cheminRelatif` de l'onglet) › symboles englobants
   du caret. Suivi du caret par `addOnSelectionChangedListener`
   (v3.4.0 : n'écrase pas le listener primaire) avec le même débounce
   de 200 ms que la bibliothèque ; renonciation aux symboles sur les
   très gros documents (`EditorDocument.isLarge` : scan O(n) à chaque
   arrêt du caret) ; défilement automatique vers le segment courant
   (côté opposé en RTL).

2. **Barre de symboles dans l'entête du panneau, visible à l'IME.**
   `BarreSymbolesEditeur` (feature/editor) transpose la
   `SymbolBarView` : touches épinglées **Tab, //, ↑, ↓, Dup** puis 28
   symboles défilants, séparés d'un filet — le même jeu exact que la
   bibliothèque. Le détail décisif est repris tel quel : les touches
   utilisent `onTouchEvent` **brut** (PAS `setOnClickListener`) — une
   vue cliquable réclamerait le focus, l'éditeur perdrait le sien et
   l'IME se fermerait ; le toucher retourne `true` dès `ACTION_DOWN`,
   `performClick()` sur `ACTION_UP` pour l'accessibilité. La barre vit
   sous l'en-tête du panneau inférieur (`BottomSheetBehavior`, prompt
   compagnon 5.5) et **n'apparaît que quand le clavier virtuel est
   visible ET qu'un fichier est édité** — la demande exacte du retour.
   Détection : insets IME natifs sur le root (API 30+,
   `adjustResize` + edge-to-edge) avec repli par la hauteur du root
   pour API 26-29 (le root rétrécit quand le clavier prend sa place ;
   seuil 15 % de l'écran, une marge d'insets n'atteint jamais ça).
   Quand elle apparaît, le panneau se replie (`STATE_COLLAPSED`) : son
   en-tête et la barre montent au-dessus du clavier, l'espace restant
   reste à l'éditeur. Actions : Tab → `indent()`, // →
   `toggleLineComment()`, ↑/↓ → `moveLineUp()`/`moveLineDown()`,
   Dup → `duplicateSelection()` ; chaque symbole s'insère par
   `typeChar()` (la fermeture automatique des paires `{`, `(`, `"`
   est conservée). Cibles tactiles 44 dp (la bibliothèque a 36/38 :
   le standard de l'app est plus exigeant).

3. **Corrections du même retour, sans ADR dédié** (portée trop
   courte) : le tap sur un onglet de fichier ne changeait rien — la
   leçon ADR 0051 (une vue à écouteur d'appui long SEUL consomme les
   taps simples sans agir) n'avait été fixée que pour le terminal :
   la racine d'onglet de l'éditeur **agit** désormais sur son propre
   tap (elle sélectionne l'onglet) ; ouvrir un fichier depuis
   l'explorateur émet le nouvel effet `EffetEditor.FichierOuvert`,
   l'activité referme le tiroir (grand écran ancré excepté, ADR 0026) ;
   l'état vide de l'éditeur devient une vraie surface (illustration
   `</>`, actions « Parcourir les fichiers » / « Terminal », astuces
   de découverte des gestes de l'étape 31).

- **Conséquences** :

  - `feature/editor` gagne trois fichiers (`VueFilArianeEditeur`,
    `SymbolesEnglobants`, `BarreSymbolesEditeur`) et zéro dépendance
    nouvelle ; la bibliothèque reste en 3.37.0.
  - Le scanner est couvert par `SymbolesEnglobantsTest` (11 cas :
    imbrication, Java, commentaires, expressions non déclaratives,
    méthodes sans accolades, bornes hors texte) ; le gonflage du
    layout enrichi est couvert par `ActivityEditorLayoutTest`.
  - Si une future version de cel-ui publie le SPI `SymbolProvider`
    embarqué (langues LSP), le scanner pourra être remplacé par le
    branchement officiel — la vue ne change pas (elle reçoit des
    segments, quel que soit le producteur).
  - La barre de symboles s'étendra naturellement (touches Ctrl/Alt
    pour le terminal, jeu par langage) sans toucher à l'architecture :
    la vue ne connaît que son écouteur.
