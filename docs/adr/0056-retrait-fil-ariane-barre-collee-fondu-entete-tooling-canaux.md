# ADR 0056 — Retrait du fil d'Ariane, barre collée au clavier façon projet de référence, en-tête qui s'efface et tooling à canaux

- **Statut** : accepté (v0.32.5, cinquième retour d'appareil réel sur
  l'étape 31)
- **Contexte** : retours utilisateur du 2026-09-26 sur la v0.32.4, cinq
  points : « Tu peux enlever le breadcrumb pour le moment, cela ne me
  donne pas le design espéré. Le virtuelkey n'est toujours pas visible,
  consulte le projet projet de référence de auteur tiers pour voir comment ils ont
  fait. Lorsque bottomsheet behavior est expand le header devrait
  progressivement disparaitre. Le background du bottomsheet behavior est
  complètement transparent. L'étape suivante concerne le tooling : le
  header doit afficher des informations sur le build, sync, task — la
  tâche ou activité en cours, bouton, progress ou timer ; chaque
  information doit avoir un canal unique. Je veux aussi un script pour
  nettoyer le dépôt de mon côté. »
- **Référence analysée** : dépôt projet de référence (auteur tiers, Apache-2.0,
  commit 84cf11e) — `EditorLayouts.kt` place `EditorSymbolBar` en
  DERNIÈRE vue de la colonne éditeur, montre/masque par
  `WindowInsets.ime.getBottom > 0`, cache le dock de build pendant la
  frappe, et la racine de l'UI est remontée par
  `windowInsetsPadding(safeDrawing)` (qui inclut l'IME) — la barre se
  pose littéralement sur le clavier.

## Décisions

### 1. Fil d'Ariane retiré

`BreadcrumbBar` disparaît d'`activity_editor.xml` ; le scanner
`SymbolesEnglobants` (son unique consommateur) et ses 11 tests sont
supprimés ; les chaînes `fil_ariane_editeur_cd` partent. « Pour le
moment » : le besoin (situer le caret) reste ouvert, une future reprise
devra partir d'un design validé, pas d'une transcription.

### 2. Barre de symboles : patron projet de référence, pas un hack de peek

La cause racine de l'invisibilité : `enableEdgeToEdge()` rend
`adjustResize` inerte sur API 30+ (le fenêtre ne rétrécit pas) — le panneau
et sa barre restaient derrière le clavier. La v0.32.4 élargissait le peek
sans jamais remonter quoi que ce soit : la barre dessinait hors écran.

Transposition du patron projet de référence :

- la `SymbolBarView` (vraie classe de la bibliothèque, conservée) devient
  la DERNIÈRE vue de `zone_centrale` (hors du sheet) ;
- le bas de `zone_centrale` est remonté de la hauteur IME par padding
  (`updatePadding(bottom = insets.ime().bottom)`) — la barre se retrouve
  posée sur le clavier, quel que soit le fabricant ;
- la synchronisation image par image passe par
  `WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE)`
  : `onProgress(insets, …)` re-pose le padding à chaque trame de
  l'animation (le « collé » suit les touches) ;
- le sheet se MASQUE pendant la frappe (comme le dock du projet de référence) et
  se restaure à l'état du ViewModel à la fermeture du clavier ;
- la double détection IME (insets + rétrécissement root) est conservée :
  sur les resize hérités (API < 30) le root rétrécit seul, l'inset vaut 0,
  le padding reste nul — même résultat ;
- fond opaque + filet supérieur (`fond_barre_symboles`) : la barre se lit
  comme un prolongement du clavier, pas comme une vue flottante.

### 3. En-tête du panneau : fondu progressif à l'extension

`onSlide` pose l'alpha de l'en-tête (titre + ligne tooling) à CHAQUE trame :
opaque jusqu'à mi-hauteur (offset 0,5), éteint à l'extension (offset 1).
Les sauts programmatiques d'état posent l'alpha depuis l'état stabilisé
(`rendrePanneau`). Éteint = `INVISIBLE` (pas `GONE`) : la hauteur ne
saute pas en cours de glissement et les appuis fantômes cessent. Les
ONGLETS restent : la console étendue garde sa navigation de canaux.

### 4. Fond opaque du panneau

`fond_panneau_inferieur` : coins supérieurs arrondis 12 dp, couleur
`panneau_inferieur_fond` jour/nuit (mêmes jetons que le tiroir),
élévation 8 dp. Fin du sheet « complètement transparent » : il se POSE
sur l'éditeur.

### 5. Tooling à canaux uniques

Chaque information affichée — dans l'en-tête OU la console — porte SON
canal :

- **`CanalTooling` (SYNC / BUILD)** : libellé, couleur signature (teal /
  bleu), icône. Le Journal et les Problèmes sont déjà des ONGLETS
  distincts : ce sont leurs propres canaux.
- **En-tête** : `ligne_tooling` sous le titre — icône du canal, libellé
  de l'activité (tâches du build, sync, dernier résultat), chrono en vol
  (500 ms, horloge injectée `TimeProvider`), bouton Arrêter pendant un
  build, progression indéterminée. Le peek s'élargit pour l'accueillir :
  l'activité se voit MÊME panneau replié (façon barre de build d'Android
  Studio). Fondue avec l'en-tête à l'extension (décision 3).
- **Console** : chaque ligne porte une étiquette de canal en tête
  (colonne de tag façon logcat) ; le statut porte l'icône du canal qu'il
  décrit.
- **État** : `EtatGradle` gagne `taches`, `debutBuildMs`, `debutSyncMs`,
  `canalActif`, `canalDernierResultat` ; `LigneSortieAffichee` gagne
  `canal`. `GradleService` reçoit une horloge injectée (`() -> Long`) —
  les chronos se testent sans cadre Android ; le ViewModel injecte le
  `TimeProvider` du domaine (liaison Hilt existante).
- L'état reste PUR : aucun libellé n'y est codé en dur, le rendu
  localise (fragments et hôte).

### 6. Script de nettoyage du dépôt

`scripts/nettoyage-depot.sh` (bash, simulation par défaut,
`--appliquer` pour effacer) : retire exactement ce qu'aucune archive ne
porte (mêmes exclusions que `package.sh` — build/, .gradle/, .kotlin/,
.idea/, captures/, .cxx/, dist/, *.iml, .DS_Store), refuse de tourner hors
racine de dépôt, ne touche jamais .git/, épargne local.properties sauf
drapeau explicite (régénéré par Android Studio), et termine par l'état
git non suivi. Le dépôt local redevient comparable à l'archive de
livraison.

## Conséquences

- La barre de symboles est enfin visible et « collée » au clavier sur
  TOUT appareil : la mécanique ne dépend plus d'un resize que
  edge-to-edge désactive, mais de l'inset IME que le système fournit
  toujours sur API 30+.
- Le scanner maison de symboles englobants disparaît du socle (−339
  lignes) ; une reprise du fil d'Ariane repartira d'un design validé.
- `EditorViewModel` gagne une dépendance `TimeProvider` — constructeur
  des tests étendu (horloge pilotable `horlogeOutil`).
- L'activité collecte de nouveau `etatGradle` (le chrome de l'en-tête
  lui appartient) — les fragments continuent de rendre le contenu des
  onglets, sans chevauchement.
- Le peek du panneau varie avec l'activité tooling : les tests de layout
  verrouillent l'ordre en-tête → ligne tooling → progression → onglets →
  conteneur, la présence du fond opaque et l'absence d'id de fil d'Ariane.
