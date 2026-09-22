# ADR 0020 — Cadre du wizard : hôte, fragments enfants, état partagé

- **Statut** : accepté (étape 10)
- **Contexte** : la section 12.2 du prompt maître exige un wizard à cinq
  étapes numérotées (« Modèle · Configuration · Informations · Fichiers ·
  Récapitulatif ») dont l'étape 10 ne livre que les trois premières. Le
  cadre doit survivre à la rotation **et à la mort du processus**, les
  étapes doivent rester déclaratives (« liste configurable, pas de `when`
  dispersés ») pour que des modèles puissent ajouter des étapes plus tard,
  et l'étape 11 doit pouvoir insérer « Fichiers » et « Récapitulatif »
  **sans rien changer au cadre**.
- **Décision** :
  - `NewProjectFragment` (hôte) porte la barre d'outils (✕), l'indicateur
    d'étapes (`LinearProgressIndicator` + libellé « Étape N sur M »), le
    conteneur d'étapes (`FragmentContainerView`) et la barre d'actions
    fixe (Retour / Suivant). Il ne rend **aucun champ**.
  - Les étapes sont des **fragments enfants** remplacés dans le
    gestionnaire enfant, avec transitions `MaterialSharedAxis` (axe X),
    désactivées quand le réglage système « réduire les animations » est
    actif. Après rotation, le gestionnaire enfant a déjà restauré la
    bonne classe : le remplacement est sauté si elle correspond.
  - `WizardViewModel` est **scopé à l'hôte** ; chaque fragment d'étape le
    récupère par `viewModels({ requireParentFragment() })` — même store,
    donc même instance. Aucun fragment d'étape ne porte d'état propre :
    ils ne font que rendre `EtatWizard` et émettre des `ActionWizard`.
  - La survie passe par le **`SavedStateHandle`** du ViewModel (étape
    courante, modèle, nom, description, valeurs saisies, champs figés à
    la main, emplacement éphémère) ; le catalogue et les évaluations sont
    **recalculés** à la restitution.
  - Les étapes déclarées vivent dans `ETAPES_WIZARD : List<WizardStep>` ;
    `EtapeId` énumère les cinq identifiants prévus. À l'étape 10 la liste
    en contient trois : l'indicateur affiche « sur 3 », l'insertion des
    deux suivantes ne touchera que cette liste.
  - Le bouton **Suivant est masqué sur la dernière étape livrée** (il
    n'a nulle part où mener) ; « Créer » viendra avec le récapitulatif
    (étape 11). Le bouton retour système recule d'une étape, et depuis la
    première étape déclenche le dialogue « Abandonner la création ? » si
    des données ont été saisies.
- **Conséquences** :
  - la rotation et la mort du processus sont couvertes par construction
    et testées (reconstruction du ViewModel sur le `SavedStateHandle`) ;
  - l'ajout d'une étape = un fragment + une entrée dans la liste : aucun
    `when` dispersé à maintenir ;
  - le coût est un remplacement de fragment par changement d'étape
    (acceptable : les étapes sont saisie-bloc, pas du défilement continu) ;
  - les champs de saisie perdent le focus en changeant d'étape — voulu :
    chaque étape est un objectif unique (section 12.1).
