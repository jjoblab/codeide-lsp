# ADR 0048 — Navigation hors graphe sûre, `apt update` obligatoire et outils de développement optionnels

- **Statut** : accepté (v0.31.4, correction après retour utilisateur)
- **Contexte** : quatrième retour de terrain (app v0.31.3, moto g06 /
  Android 15, rapport de plantage `f2699ac5`). Trois problèmes :
  1. **Plantage déterministe au retour depuis le terminal** :
     `TerminalActivity` (barre d'outils, flèche retour) →
     `IllegalStateException: Le conteneur de navigation est introuvable
     dans MainActivity` — l'app s'effondre juste après l'ouverture d'un
     projet et un passage au terminal (« le problème avec la création de
     projet persiste » : le wizard fonctionne, c'est l'après-ouverture
     qui tue l'app).
  2. **Écran d'installation qui « ne se met pas à jour correctement »** :
     le journal en direct s'efface à chaque changement d'étape, et les
     états terminaux (réussite/échec) n'affichaient **plus aucun bouton**
     depuis v0.31.2 (le « Fermer » n'était jamais rendu visible, le
     « Réessayer » restait masqué après le lancement) — seuls le geste
     système retour permettait de sortir.
  3. **Durée de la première configuration** : l'utilisateur demande que
     `apt update` reste **obligatoire** (dépôt à jour) mais que
     l'installation des paquets d'outils (`openjdk`, futur SDK Android,
     cmdline-tools…) devienne **optionnelle pour la première
     configuration** — « elle sera demandée plus tard pour que les autres
     fonctionnalités de l'application puissent fonctionner ». Sur
     l'appareil, la phase d'outils a occupé 3 min 30 s des 8 min
     d'installation.

- **Diagnostic** :

1. **`AppNavigatorImpl` est `@ActivityScoped`** : l'activité hôte de
   l'instance est celle qui l'injecte. `TerminalActivity` et
   `EditorActivity` injectent aussi un `AppNavigator` — leur hôte n'a
   **pas de conteneur de navigation** (`activity_terminal` /
   éditeur plein écran), et le getter `findViewById(nav_host_container)
   ?: error(...)` lève à chaque `goBack()`. Le même défaut latent existe
   côté éditeur : `openDiagnostics()` (journal complet) et
   `openBootstrapInstall()` (carte Terminal) y passent aussi —
   `EditorActivity:1277/1281`.
2. **Deux bugs de rendu distincts dans l'écran d'installation** :
   `InstallViewModel.traduire()` reconstruit un `EtatInstallation` dont
   `journal` vaut `emptyList()` par défaut — **chaque émission d'état
   efface le journal** (les tics de progression du téléchargement, de
   l'extraction, puis chaque paquet) ; et le rendu des phases
   `TERMINEE`/`ECHEC` ne touchait jamais `bouton_fermer` ni ne
   re-affichait `bouton_installer` : une fois l'installation lancée,
   aucun bouton terminal n'apparaissait.
3. **Le pipeline v0.31.3 couvrait tout** (base + outils) : la demande
   utilisateur dessine une frontière nette entre l'**environnement de
   base** (shell, apt, dépôt à jour — obligatoire, ~40 Mo) et les
   **outils de développement** (JDK, git — optionnels, >200 Mo,
   différés). La demande « plus tard » des fonctionnalités n'existait
   pas : le daemon tooling se contentait de journaliser « JDK
   introuvable » sans proposer l'action.

- **Décisions** :

1. **Navigateur robuste, jamais `error()`** : le getter devient nullable
   (`navControllerDHote`). `goBack()` hors graphe **termine l'activité
   pleine écran** (les sessions du terminal survivent via le service
   foreground — c'était l'intention documentée de l'écran) ; au sommet
   du graphe, `popBackStack()` faux → `finish()`.
2. **Routage d'écran via l'hôte** : les navigations vers des écrans du
   graphe (paramètres, diagnostic, installation, assistant) appelées
   depuis une activité sans conteneur relancent `MainActivity` avec
   `RoutageEcran.EXTRA_ECRAN_CIBLE` et les drapeaux
   `REORDER_TO_FRONT | SINGLE_TOP` — l'instance existante reçoit
   `onNewIntent` **sans recréation ni duplication**, l'activité
   appelante survit dessous (retour système = retour à l'éditeur, état
   intact). `MainActivity` passe `launchMode="singleTop"` et consomme
   l'extra à froid (après le routage du premier lancement) et à chaud.
   Les destinations réservées au wizard (`openHome`,
   `wizardCreeProjet`, `consommerProjetCree`) se journalisent et ne
   font rien hors graphe — jamais de plantage.
3. **Pipeline scindé (base obligatoire, outils différés)** :
   `demarrer()` s'arrête **après `apt update`** — dépôt à jour, marqueur
   d'installation, `Terminee(outils = vide)`. La nouvelle méthode
   `installerOutils()` (port `BootstrapInstaller`) installe les paquets
   **à la demande** depuis `Terminee`/`OutilsEchoues` ; l'annulation en
   pleine phase d'outils **conserve la base** (retour à `Terminee` avec
   les paquets traités). L'état initial du singleton lit le marqueur :
   un redémarrage avec bootstrap déjà installé démarre à `Terminee`
   (proposition d'outils), jamais une réinstallation destructrice.
   Nouvel état `OutilsEchoues` : l'échec des outils ne compromet pas la
   base — seule la phase d'outils est reprise.
4. **Écran d'installation refondu** : état et journal **combinés** dans
   un seul flux (`combine` + `stateIn`) — le journal ne s'efface plus ;
   progression structurée en deux sections (« Environnement de base »,
   huit étapes ; « Outils de développement », statuts par paquet) ;
   les états terminaux montrent leurs actions (« Fermer » +
   « Installer les outils maintenant » à la réussite, « Réessayer » à
   l'échec de base, « Réessayer l'installation des outils » +
   « Fermer » à l'échec des outils).
5. **Demande ultérieure des outils côté éditeur** :
   `synchroniser`, `exécuter` et le sélecteur de tâches vérifient
   `isJdkInstalled()` AVANT toute tentative — refus typé avec message
   actionnable dans l'onglet Sortie (« installez les outils depuis la
   carte Terminal du tiroir ») au lieu d'une connexion perdue opaque
   après reprises et délais.

- **Alternatives rejetées** :
  - *TerminalActivity sans navigateur (finish() direct)* : répare le
    symptôme rapporté mais laisse les trois chemins latents de
    l'éditeur ; le navigateur est le point unique de la section 5.4,
    c'est lui qui doit connaître le graphe — la robustesse y vit.
  - *`launchMode="singleTask"`* : aurait détruit la pile au-dessus de
    l'accueil (l'éditeur et ses onglets non enregistrés) — contraire à
    la promesse « l'accueil survit en dessous ».
  - *Outils installés dans le même pipeline avec un état « sautés »* :
    aurait gardé l'écran en « progression » pendant 3 min 30 sans
    engagement de l'utilisateur ; le découpage en deux phases rend le
    choix explicite (bouton dédié) et la reprise granulaire possible.
  - *Installation automatique des outils au premier build* : décision
    d'engager >200 Mo de trafic et d'espace sans l'utilisateur —
    contraire à la demande (« optionnel… demandé plus tard ») ; le
    refus typé guide vers le bouton, il ne clique pas à sa place.

- **Conséquences** :
  - La première configuration se termine dépôt à jour (~2 min sur
    l'appareil contre ~8) ; les outils sont un clic plus tard, quand
    une fonctionnalité les réclame.
  - `MainActivity` est `singleTop` : un relancement depuis le lanceur
    ne duplique plus l'accueil (effet de bord bénéfique).
  - Le test d'intégration `MainActivityTest` couvre le routage à froid
    et à chaud ; l'installateur est éprouvé sur les six transitions de
    la nouvelle machine d'états (dont l'annulation en phase d'outils et
    le redémarrage à `Terminee`).
  - `EnCours(InstallationPaquets)` n'est plus émis pendant la phase de
    base — la checklist de l'écran compte huit étapes ; la neuvième vit
    dans la section dédiée avec ses statuts par paquet.
