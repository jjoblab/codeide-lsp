# ADR 0099 — Sections d'en-tête conditionnelles, état vide stable, poignée racine, icônes non filtrées

- Statut : accepté (2026-10-10)
- Contexte : retour utilisateur après la v0.80.1 — (1) la deuxième
  section (ligne tooling) doit être ÉTEINTE sur les onglets Problèmes
  et Journal, et la première section doit aussi disparaître quand le
  sheet est ÉTENDU ; (2) l'état vide « bouge » après étirement puis
  repli du sheet (un espace vide apparaît) ; (3) la poignée de
  redimensionnement doit chevaucher à 50 % le tiroir et à 50 % la zone
  centrale — l'effet existant est obtenu par une bande de débord
  transparente EMPRUNTÉE au tiroir ; (4) il manque des icônes pour
  d'autres types de fichiers (zip…), l'icône dossier doit être
  identique repliée/dépliée et les couleurs ne doivent plus être
  filtrées (référence : icônes d'Android Studio).

## Décision

Quatre changements dans `feature:editor` et `core:ui` :

1. **Matrice de visibilité des deux sections (v0.80.2).**
   `PanneauToolingController.appliquerVisibiliteLigne()` est la seule
   source de vérité : la ligne tooling (et sa progression) est GONE
   hors onglet CONSOLE (sur Problèmes/Journal, seule la PREMIÈRE
   section porte les informations de l'onglet — la deuxième section
   est éteinte), GONE quand le sheet est ÉTENDU stabilisé
   (`definirSheetEtendu`, posé par `onStateChanged` : EXPANDED marque,
   DRAGGING/SETTLING démarque — les onglets montent au sommet du
   sheet étendu, comme l'en-tête `ViewFlipper` d'AndroidIDE qui
   s'efface à l'extension), INVISIBLE sous le seuil du fondu (la
   place est conservée : jamais de saut de hauteur en plein
   glissement), VISIBLE sinon. Miroir côté activité :
   `marquerSheetEtendu()` fait passer `entete_panneau` GONE à
   l'extension stable, INVISIBLE sous le seuil du fondu au repli.
   Corollaire peek : la ligne tooling ne compte PLUS dans le peek
   hors onglet Console.

2. **Réserve constante sous l'éditeur (bug de l'état vide).**
   `appliquerReservePanneau()` posait `padding=0` dès que le sheet
   n'était pas COLLAPSED : étendre puis replier faisait RE-CENTRER la
   vue vide en saut (padding 0 → peek/2). La réserve est désormais
   CONSTANTE (`padding bas = peekHeight`, équivalent exact du
   `marginBottom = peekHeight` d'AndroidIDE) : la zone d'édition ne
   change pas de taille entre replié, mi-hauteur et étendu, le sheet
   se pose par-dessus.

3. **Poignée racine à cheval réel sur le bord.** La poignée ⋮ quitte
   le tiroir : `conteneur_poignee` (FrameLayout plein écran,
   transparent, non cliquable) est le DERNIER enfant de la racine
   `TiroirPoussantLayout`. L'ordre des enfants le dessine AU-DESSUS du
   tiroir et l'itération inverse des touches le sert AVANT lui — à
   condition que l'élévation du tiroir soit 0 (`setDrawerElevation(0)`)
   : `DrawerLayout` force 10 dp par défaut, et le tri par Z des
   enfants passerait la poignée sous le tiroir. Le rognage
   `clipRect` de `DrawerLayout.drawChild` (un contenu ne dessine jamais
   sous un tiroir opaque) est BYPASSÉ pour ce conteneur
   (`TiroirPoussantLayout.drawChild`) : la poignée est justement à
   cheval sur cette frontière. `recalerPoignee()` la suit image par
   image — translationX = bord × fraction − 13 dp (moitié tiroir,
   moitié zone centrale), miroir en RTL (translationX n'est jamais
   auto-miroir), translationY centrée sur la hauteur du tiroir,
   masquée sous 25 % d'ouverture. `fond_tiroir` devient pleine
   largeur (plus de bande de débord transparente de 13 dp, plus de
   variante ldrtl d'inset), la colonne du tiroir perd sa marge de fin.

4. **Icônes parité Android Studio, sans filtrage.** Onze icônes
   converties des `fileTypes` d'IntelliJ New UI (jour + nuit) :
   archive (zip/jar/apk/7z/rar/tar/gz/tgz/bz2/xz — le jar quitte
   l'icône db), image, html, css, js, yaml, shell, sql, csv, police,
   binaire. L'icône dossier `nodes/folder.svg` sert AUSSI BIEN replié
   que déplié (Android Studio New UI n'a PAS de variante ouverte —
   seule la flèche tourne) : vérifié à la source dans
   intellij-community (`expui/nodes`, `IconUtil.getIcon(virtualFile,
   flags=0)` sur `PsiDirectoryNode`). `ExplorateurAdapter` n'applique
   PLUS AUCUN `setColorFilter` sur les icônes de l'arbre (les
   résidus des lignes recyclées sont systématiquement effacés) : les
   couleurs officielles s'affichent telles quelles ; les quatre teintes
   de dossier dédiées (`codeide_explorateur_dossier*`) sont retirées
   des palettes (le popover de destination garde un glyphe monochrome
   teinté au rôle `colorOnSurfaceVariant` du thème).

## Justification

- **GONE seulement stabilisé** : marquer la disparition à EXPANDED
  mais la rétablir en INVISIBLE dès DRAGGING/SETTLING évite de
  recomposer le peek en plein geste — la hauteur du sheet ne saute
  jamais ; c'est le comportement du `ViewFlipper` d'AndroidIDE
  (fondu de l'en-tête pendant la traîne, effacement une fois étendu).
- **Réserve constante** : c'est l'approche d'AndroidIDE
  (`marginBottom = peekHeight`) ; l'alternative « padding 0 quand le
  sheet n'est pas replié » économise ~50 dp d'écran mais fait
  respirer la zone d'édition à chaque transition — d'où le saut
  signalé sur l'état vide (vue centrée qui re-centre).
- **Poignée racine plutôt que bande de débord** : l'ancien montage
  (largeur du tiroir = W + 13 dp, `fond_tiroir` inset 13 dp) donnait
  l'ILLUSION du chevauchement — la moitié externe de la poignée
  couvrait une bande TRANSPARENTE du tiroir, morte aux touches de
  redimensionnement. Le conteneur racine donne un VRAI chevauchement
  sur la zone centrale, sans coût pour les fragments (pleine largeur
  utile). L'élévation du tiroir passe à 0 : le tri par Z de
  `buildOrderedChildList` pilote à la fois le dessin ET les touches —
  l'ordre des enfants (poignée en dernier) suffit alors à la placer
  au-dessus ; l'ombre du bord du tiroir est négligeable en mode
  POUSSÉ (la zone centrale est translatée, pas recouverte).
- **Icônes non teintées** : les `fileTypes` d'IntelliJ portent leurs
  propres couleurs (violet Kotlin, bleu Gradle…) ; teinter les
  dossiers par contexte masquait les couleurs officielles
  (gris clair/gris foncé de `folder.svg`) et séparait l'arbre du
  rendu d'Android Studio.

## Conséquences

- `activity_editor.xml` : `conteneur_poignee` (dernier enfant) +
  `poignee_tiroir` dedans (GONE par défaut) ; colonne du tiroir
  pleine largeur ; `fond_tiroir` simple forme pleine largeur,
  variante ldrtl supprimée.
- `EditorActivity` : `fractionTiroir`, `sheetEtenduStable`,
  `recalerPoignee()` (RTL miroir), `marquerSheetEtendu()`,
  réserve constante dans `appliquerReservePanneau()`,
  `setDrawerElevation(0f)`, `setConteneurPoignee`.
- `PanneauToolingController` : `definirSheetEtendu`,
  `appliquerVisibiliteLigne` (matrice unique), peek sans ligne
  hors Console, progression réservée à la Console.
- `TiroirPoussantLayout` : `setConteneurPoignee` + `drawChild`
  bypassant le rognage pour le conteneur de poignée.
- `core:ui` : 22 drawables (11 icônes × jour/nuit),
  `IconesFichiers.pourExtension` étendu (html, css, js, yaml, shell,
  sql, csv, archive, image, police, binaire — le jar passe à
  l'archive), 4 couleurs de dossier retirées des palettes jour et
  nuit.
- Tests : `PanneauToolingControllerTest` (5 tests — matrice complète
  onglet × étendu × fondu), `ActivityEditorLayoutTest` (structure
  racine de la poignée + fond pleine largeur),
  `IconesFichiersTest` (3 tests d'extensions, repli inconnu corrigé).
