# ADR 0052 — Explorateur de fichiers v2 : tiroir à fragments et mutations par nœud

- **Statut** : accepté (v0.32.0, étape 31 — implémentation de
  `docs/EXPLORATEUR_V2.md`, spécification de reproduction validée par
  la maquette interactive `docs/preview/explorateur-v2.html`)
- **Contexte** : l'explorateur v1 (étapes 14-17) vivait dans un empilement
  de vues du layout `activity_editor.xml` : entête commun (nom, chemin,
  type, « Fermer le projet »), `RecyclerView` aplati, `BottomNavigationView`
  à quatre destinations dont deux désactivées, menus contextuels
  **système** (`PopupMenu`/`MaterialAlertDialog`) et rechargement de
  l'arbre entier à chaque mutation de fichier. La refonte validée en
  maquette (retour utilisateur du 2026-09-25 : crochets `[ ]` retirés —
  les chevrons seuls suffisent ; bascule Projet/Privé **exclusive**)
  demande une reproduction à 100 % : entête propre par fragment,
  treeview à guides fins, points d'état pilotés par les onglets,
  popover maison ancré au doigt, presse-papiers d'arbre, snackbar
  maison annulable, poignée ⋮ de redimensionnement.

- **Décisions** :

1. **Tiroir à fragments (ADR 0052 proprement dite).** L'empilement de
   vues disparaît : le tiroir porte un `FragmentContainerView` et un
   **rail de fragments** commun (§ 14 — vue maison, plus de
   `BottomNavigationView`). Les quatre destinations (Fichiers,
   Recherche, Git, Terminal) sont des fragments ajoutés UNE fois puis
   montrés/cachés — l'état de défilement et les plis survivent aux
   changements de destination. Seul **ExplorateurFragment** est
   fonctionnel à l'étape 31 ; Recherche/Git restent des aperçus
   statiques (`ApercuSimpleFragment`), Terminal migre TEL QUEL la carte
   d'aperçu T6 dans `TerminalTiroirFragment` (mêmes identifiants, zéro
   dépendance Termux). Chaque fragment porte **son propre entête**
   (emblème coloré, titre, sous-titre mono = chemin de la racine
   affichée) — plus d'entête commun. « Fermer le projet » rejoint le
   débordement de la toolbar, le type de projet devient son sous-titre.
   *Amendé v0.32.2 (retour appareil réel — ADR 0053)* : le fragment
   Terminal ne migre plus la carte T6 — il déménage dans
   `feature:terminal` et rend de vraies sessions (fabrique Hilt
   `FabriqueFragmentTerminalTiroir` + contrat `ControleurTerminalTiroir`,
   tous deux dans `core:ui` — les features ne se référencent toujours
   pas).

2. **Arbre v2 sans re-chargement d'arbre entier.** `NoeudExplorateur`
   porte désormais son état de présentation : sélection (fond dégradé +
   barre gauche), coupe (opacité 50 % + nom barré), point d'état
   (§ 7 : défaut/ouvert/actif/sélectionné — **piloté par les onglets**
   de l'éditeur, la sélection prime sur la bordure), marque de dernier
   enfant, compteur d'enfants, flash de 1,1 s. Les guides (§ 6.2 :
   trait vertical + coude, 22 dp par niveau) sont **dessinés** par
   `VueGuides` (aucune vue imbriquée — perf § 19), le point d'état par
   `VuePointEtat` (transition de couleurs 0,16 s). Le masque
   `masqueAncetresDerniers` (bit *k−1* = ancêtre de profondeur *k*
   dernier enfant) arrête les traits ancestraux finis — notation `└`.
   Le `ListAdapter`/DiffUtil ne re-lie que les lignes changées.

3. **Bascule Projet/Privé exclusive (§ 5).** Les deux arbres vivent
   derrière le MÊME port `FileSystem`, distingués par le qualifier Hilt
   `@FileSystemPrive` (fichier `FileSystemPrive.kt`) ; l'adaptateur
   `core:storage.FileSystemPrive` expose `filesDir`, `cacheDir`,
   `codeCacheDir`, `databases`, `shared_prefs` sous l'URI racine
   `prive:///` (schéma maison qui ne traverse jamais le port). ADR 0003
   inchangée : `java.io.File` reste interdit pour les **projets** —
   l'usage de `File` est confiné à cette implémentation derrière le
   port. Un appui sur l'autre segment réinitialise la sélection,
   re-rend l'arbre cible, met à jour le sous-titre et montre le
   snackbar ; les onglets de l'éditeur ne sont pas touchés. Le port
   gagne `readBytes`/`writeBytes` (octets bruts — copier un `.jar` ou
   une `.db` sans corrompre `readText`).

4. **Popover maison, jamais de menu système (§ 10).**
   `PopoverExplorateur` enveloppe une `PopupWindow` : coque 258 dp,
   flèche 12 dp, ancrage à la **position exacte du doigt** (appui long
   — le contact est capturé au `ACTION_DOWN` de la ligne), algorithme
   § 10.2 (x−30 dp borné 6 dp, y+16 dp, retournement y−h−14 dp si le
   bas déborde, flèche bornée 20 dp ↔ largeur−20, zoom d'apparition
   .94→1 en 0,15 s depuis la flèche). Quatre popovers : actions du nœud
   (variantes fichier/dossier/racine du § 10.4, action dangereuse
   rouge, « Coller » désactivé sans presse-papiers valide — note = nom
   du presse-papiers), « Déplacer vers… » (validation LOCALE du chemin
   contre `cheminsDossiers` : erreurs en ligne sans fermer le popover),
   confirmation « Supprimer », « Légende » (pastilles figées). Un seul
   popover à la fois ; fermeture par clic extérieur, Échap ou action.

5. **Mutations par nœud + presse-papiers d'arbre (§ 11).** Création et
   renommage passent par un **éditeur inline** dans la liste (icône de
   type, champ à repère, valider vert/annuler, Enter/Échap) — plus de
   dialogue modal. Suppression : confirmation popover, fermeture des
   onglets touchés, snackbar **annulable** 4 600 ms qui restaure
   l'élément via un instantané mémoire (`ArbreMemoire`, octets bruts)
   et ROUVRE ses onglets. Copier/couper/coller : presse-papiers mémoire
   (jamais le presse-papiers système), suffixe anti-collision
   `NomsCopies` (« (copie) », « (copie 2) »… insensible à la casse),
   garde-fou collage interdit source→descendant. « Déplacer vers… »
   suit le même `DeplacerArbreUseCase` (copie complète puis suppression
   — jamais de perte). Les use cases `CopierArbre`, `DeplacerArbre`,
   `LireArbre`, `RestaurerArbre` vivent dans `core:domain` (testables
   purs).

6. **Poignée ⋮ de redimensionnement (§ 13).** Vue dédiée 26 × 78 dp
   collée au bord droit du tiroir (elle **déborde à moitié** : la
   largeur du tiroir inclut un débord de 13 dp) : glissement horizontal
   borné 45-98 % de l'écran, aimants 55/69/85/98 % (tolérance ±12 dp au
   relâchement, animation 180 ms hors glissement), pastille de taille
   « NN % » visible pendant le glissement puis fondue (380 ms). La
   largeur est mémorisée par instance sauvegardée (§ 19 — par session,
   pas persistée en disque).
   *Affinage v0.32.1 (retour appareil réel)* : le FOND du tiroir est
   borné à la largeur visible (`fond_tiroir`, inset de fin de 13 dp) —
   la bande de débord reste **transparente**, la moitié externe de la
   poignée flotte sur l'éditeur assombri comme `right:-13px` dans la
   maquette, et les fragments remplissent le tiroir visible d'un bord à
   l'autre ; la poignée reste entièrement DANS le cadre du tiroir
   (entièrement touchable — un enfant hors des bornes de son parent ne
   recevrait pas les touchers). Le tiroir porte en sus une MARGE haute
   égale à l'inset de la barre de statut
   (`applySystemBarsInsetsTopMargin`, core:ui) : il ne peint plus rien
   derrière elle (maquette § 2 : le tiroir s'ouvre sous la barre de
   statut), la barre de navigation reste un rembourrage bas.
   *Affinage v0.32.2 (retour appareil réel)* : la barre de navigation
   devient à son tour une MARGE basse
   (`applySystemBarsInsetsMargins`, core:ui — le tiroir ne chevauche
   plus la navbar, son rail s'arrête au-dessus des gestes), et le
   glissement de la poignée porte enfin l'état « pendant » de la
   maquette — sélecteur pressé posé à la main (l'écouteur consommant
   tout, il ne s'active jamais seul), points ⋮ accent, grossissement
   1.08 animé 150 ms, retour symétrique au relâchement.

7. **Snackbar maison (§ 15).** Vue gonflée dans une zone du tiroir
   centrée au-dessus du rail : message + chemin en seconde ligne mono,
   action unique « Annuler » (restauration d'une suppression),
   apparition fondu + montée 18 dp en 0,22 s, masquage automatique
   après 4 600 ms piloté par le ViewModel (`MasquerNotification` —
   l'UI ne détient aucun chronomètre d'état).

- **Conséquences** :

- Le layout `activity_editor.xml` perd ~350 lignes d'empilement ;
  `menu_tiroir.xml`, `dialogue_nom_fichier.xml` et l'entête commun
  disparaissent ; le label du rail passe d'« Explorateur » à
  « Fichiers » (§ 14). Les chaînes v2 sont traduites (en) au fil de
  l'eau.
- Trois couches testables : `NomsCopies` et les use cases d'arbre
  (purs, `core:domain`), le `EditorViewModel` (85 tests — tri ADR 0027,
  validation inline, anti-collision, coller interdit, annulation,
  bascule exclusive, points d'état), les layouts (gonflage Robolectric).
  Les tests du ViewModel utilisent `runCurrent()` (le temps virtuel ne
  franchit pas les 4 600 ms d'expiration du snackbar avant les
  assertions).
- Écarts assumés et documentés (spec § 3.1 note) : les valeurs de la
  maquette (px sur 414) deviennent des dp/dimens ; le fond dégradé de
  sélection s'efface sur toute la largeur (inset en pourcentage
  interdit par AAPT) ; l'autocomplétion « datalist » du popover
  Déplacer est remplacée par la validation locale contre les dossiers
  énumérés ; l'appui long 420 ms/9 px suit les seuils plateforme
  (ViewConfiguration) ; la vibration 12 ms utilise le retour haptique
  long standard.
- `FakeFileSystem` (core:testing) ampute SA barre finale de l'URI de
  racine listée (« prive:/// » → « prive:// ») pour calculer le préfixe
  d'enfants — comportement inchangé pour les racines SAF.
- Les étapes suivantes (32 : plugins, 33 : services, 34 : autres
  langages) reprennent leur cours ; Recherche et Git attendent leur
  lot dédié derrière leurs aperçus d'accueil.
