# ADR 0050 — Fichiers cachés SAF (création honnête) et onglet « + » jamais bordé

- **Statut** : accepté (v0.31.6, correction après retour utilisateur)
- **Contexte** : sixième retour de terrain (app v0.31.5, moto g06 /
  Android 15, rapport 4a4526aa). Deux familles :
  1. **« Le problème de création persiste (… le dossier créé a été
     supprimé, .gitattributes …) »** — l'échec `Storage(AlreadyExists)`
     survient à CHAQUE tentative, quel que soit le nom ou l'emplacement ;
     l'utilisateur observe le dossier naître puis disparaître, et
     l'écran d'échec (v0.31.5 : détails techniques visibles) porte
     précisément « .gitattributes » — premier fichier du plan des
     modèles JVM. Le pré-vol de v0.31.5 ne pouvait rien : la cible est
     VIERGE au moment de l'appui (vérifié avant écriture) — la collision
     se fabrique PENDANT l'écriture.
  2. **Plantage de `TerminalActivity`** 60 ms après « session de terminal
     créée » : `NullPointerException: Missing required view with ID:
     bouton_fermer_session` (`VueOngletSessionBinding.bind`, appelé par
     `synchroniserOnglets` — le diff d'onglets introduit en v0.31.5).

- **Diagnostic** :

1. **Le point initial d'un fichier caché n'est pas une extension** — ni
   pour `mimePour` (qui voyait `contains('.')` → `text/plain`), ni pour
   le contrôle de complétion (`estAchevementExtension` exigeait
   `!demande.contains('.')`). Le fournisseur SAF, lui, complète tout nom
   **sans extension réelle** par l'extension canonique du type demandé
   (« .gitattributes » + `text/plain` → « .gitattributes.txt », comme
   « temoin » → « temoin.txt » — comportement éprouvé sur l'appareil
   depuis v0.31.1, mais jamais testé sur un nom en point initial).
   Chaîne complète : création du dossier racine (visible) → premier
   fichier `.gitattributes` créé puis **renommé** en
   `.gitattributes.txt` par le fournisseur → « renommage hostile » lu
   par le contrôle du nom retourné → document fraîchement créé
   **supprimé** + `AlreadyExists(".gitattributes")` (les détails
   affichés par l'écran v0.31.5) → rollback complet (le dossier
   disparaît) → message « un dossier porte déjà ce nom — choisis un
   autre nom », mensonger : AUCUN nom ne peut marcher, le piège est dans
   le fichier, pas dans le dossier. L'insertion en base n'est pas en
   cause (elle survient après les écritures).
2. **`synchroniserOnglets` par diff** : la boucle de mise à jour lit
   l'onglet existant à la position de chaque session et le binde —
   quand la liste **grandit**, la position visée est occupée par le
   « + » (cas minimal : zéro session, le « + » seul en position 0 —
   exactement le scénario du rapport : activité ouverte vide → création
   de la première session 60 ms plus tôt). La vue du « + » est un
   `ImageView` : `bind` ne trouve pas `bouton_fermer_session` → NPE.
   La régression était invisible en rotation (activités recréées avec
   sessions déjà présentes : tabs reconstruits de zéro) et ne se
   déclenchait que sur la CRÉATION — d'où un écran Terminal qui plantait
   à chaque première session.

- **Décisions** :

1. **Règle partagée `mimeFichierTexte` / `sansExtensionReelle`**
   (`core:domain`, `FileSystem.kt`) : un nom porte une extension
   RÉELLE ssi son dernier point n'est pas le premier caractère. Tout
   fichier **texte** sans extension réelle — caché (`.gitattributes`,
   `.gitignore`, `.editorconfig`) ou sans point (`gradlew`, `LICENSE`,
   `Makefile`) — est créé avec le type privé `text/x-codeide` : inconnu
   de la table système, il n'a pas d'extension canonique — le
   fournisseur ne complète RIEN et le nom demandé est préservé
   exactement. Les noms avec extension réelle gardent `text/plain`.
   `CreateProjectUseCase.mimePour` délègue à cette règle ; l'éditeur
   (`creerFichier` du tiroir) aussi — même piège en germe (« .gitignore »
   créé depuis le tiroir).
2. **Filet dans `SafFileSystem.estAchevementExtension`** : la complétion
   est reconnue bénigne pour tout nom sans extension réelle (le point
   initial ne disqualifie plus) — si un fournisseur complète malgré le
   type privé, le document créé reste le nôtre : réussite, jamais une
   collision de pure invention. Cohérent avec la politique existante
   (« temoin » → « temoin.txt » accepté depuis v0.31.1) ; les
   renommages de collision (« nom (1) ») et autres différences restent
   hostiles.
3. **`vueOngletSessionBordable`** (`feature:terminal`) : la décision
   « border la vue existante ou insérer fraîche » exclut
   explicitement le « + » (identité de tab, pas nature de la vue) ;
   l'insertion fraîche à la position insère AVANT le « + » (le TabLayout
   décale), l'invariant `[sessions…, +]` est maintenu par la boucle de
   retraits. Helper de niveau fichier, internal, testable sur le VRAI
   TabLayout du layout.
4. **Régressions verrouillées** : `MimeFichierTexteTest` (table des
   familles de noms), `SafFileSystemTest` (complétion d'un caché
   acceptée ; type privé jamais complété — sur le vrai chemin
   `DocumentsContract` du faux fournisseur, dont la règle de complétion
   est désormais fidèle : point initial = sans extension, type inconnu =
   aucune complétion), `OngletsSessionsTest` (le « + » jamais bordé,
   une vraie vue de session bordée en place).

- **Alternatives rejetées** :
  - *Renommer le fichier complété* (accepter « .gitattributes.txt » puis
    `renameDocument` vers « .gitattributes ») — deux allers-retours
    fournisseur pour réparer un nom que le bon type MIME n'abîme
    jamais ; le renommage SAF a ses propres pièges de nom retourné.
  - *Traiter la complétion d'un caché comme un échec honnête* (nouveau
    motif d'erreur) — le projet réussit avec le bon type MIME ; ne pas
    fabriquer un écran d'échec pour un cas que la couche appelante
    élimine par construction.
  - *Un marqueur de type sur la vue du « + »* (tag, sous-classe) —
    l'identité du tab (`===`) suffit : le « + » est le seul onglet
    hors-sessions, créé une fois, jamais retiré par la boucle.
  - *Reconstruire tous les onglets à chaque émission* — retour au bug
    v0.31.5 (taps mangés pendant une commande) ; le diff est sain, seul
    son choix de vue à binder était faux.

- **Conséquences** :
  - La création de projet réussit sur les modèles JVM (kotlin-jvm,
    java) : `.gitattributes`, `.gitignore`, `.editorconfig`, `gradlew`,
    `LICENSE` naissent sous leur nom exact ; plus de « dossier déjà
    pris » mensonger, plus de rollback sous les yeux de l'utilisateur.
  - L'écran du terminal survit à la création de session (première
    comprise) ; le diff d'onglets v0.31.5 garde ses garanties (taps
    vivants, sélection stable).
  - Le faux fournisseur de test reproduit désormais la règle d'
    extension du fournisseur réel (point initial, types inconnus) —
    les prochains pièges de noms s'éprouvent en local.
  - Risque résiduel : un fournisseur qui compléterait même les types
    inconnus produirait un fichier au nom complété (projet créé,
    fichier mal nommé — pas de boucle d'échec) ; aucun cas connu.
