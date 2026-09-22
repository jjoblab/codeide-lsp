# ADR 0024 — Mise en évidence du projet créé via la pile de retour

- **Statut** : accepté (étape 11)
- **Contexte** : la section 12.3 exige qu'après une création réussie, « le
  nouveau projet apparaît [à l'accueil], mis en évidence ». Le wizard
  (`feature:newproject`) et l'accueil (`feature:home`) sont des modules
  qui **ne se connaissent pas** (section 5.4) ; il faut transmettre
  l'identifiant du projet créé d'un ViewModel à l'autre, en survivant à la
  recréation de l'activité et à la mort du processus.
- **Décision** :
  - `AppNavigator` (core:ui) gagne deux méthodes : `wizardCreeProjet(id)`
    — appelée par le wizard au moment de se refermer — et
    `consommerProjetCree()`, appelée une fois par l'accueil.
  - l'implémentation applicative dépose l'identifiant dans le
    **`SavedStateHandle` de l'entrée d'accueil de la pile de retour**
    (`previousBackStackEntry`), puis dépile le wizard ; l'accueil le
    **retire** (`remove`) à son retour — un consommé ne revient pas.
  - la mise en évidence proprement dite vit dans `feature:home` :
    `ActionAccueil.SurlignerProjet(id)` → `EtatAccueil.projetEnEvidence`
    (transitoire, non sauvé) ; la carte du projet reçoit un **contour de
    la couleur primaire du thème** (suit le mode sombre, jamais une
    couleur sémantique d'état) et la liste **défile jusqu'à lui une seule
    fois** (tiers d'écran au-dessus).
  - `OuvrirProjetCree` (bouton « Ouvrir le projet » de l'écran de succès)
    marque d'abord `lastOpenedAt` (tri des récents — l'éditeur arrive à
    l'étape 13), puis suit le même chemin : l'accueil met en évidence.
- **Alternatives rejetées** :
  - *Singleton / bus d'événements* : un état global mutable pour un
    événement ponctuel — antipattern, et la perte à la mort du processus
    exigerait de toute façon une persistance.
  - *Persistance en base/DataStore* : « projet à surligner » n'est pas
    une donnée : une mise en évidence survit à la recréation d'écran par
    accident mais n'a pas de sens après une vraie fin de session.
  - *Écriture directe du ViewModel de l'accueil* : les ViewModels des
    destinations ne sont pas shareables sans les lier au fragment hôte —
    casserait l'isolation des features.
- **Conséquences** :
  - le contrat `AppNavigator` reste la seule couture entre features ;
  - l'identifiant survit à la rotation et à la mort du processus
    (SavedStateHandle de la pile de retour) sans aucun état global ;
  - si l'utilisateur referme l'accueil avant consommation, la mise en
    évidence est simplement perdue — cosmétique, jamais bloquante.
