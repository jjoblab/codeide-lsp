# ADR 0021 — Rendu dynamique des paramètres : registre de composants et raisons typées

- **Statut** : accepté (étape 10)
- **Contexte** : la section 12.2 exige que les champs des étapes
  Configuration et Informations soient **générés depuis les
  `TemplateParameter`** du moteur — jamais codés en dur — avec des
  composants dédiés : boutons segmentés, cartes radio, liste déroulante,
  interrupteurs, champs texte. La spécification nomme en outre des
  traitements précis pour certains champs (type de projet en grandes
  tuiles avec icône et sous-titre, système de build en cartes radio avec
  explication, JDK en liste déroulante). Enfin, les messages français des
  validateurs (ADR 0005, section 11) sont destinés aux journaux :
  « l'UI (étape 10) les remplacera par des ressources ».
- **Décision** :
  - `TemplateParameterEvaluation` (core:model) porte désormais les
    **métadonnées de rendu** : `type`, `choices`, `derived` (a un
    `defaultFrom`), `section`. L'évaluation du moteur reste la sortie
    unique du formulaire ; l'interface n'a jamais accès aux manifestes.
  - `RenduParametres` (feature:newproject) choisit le composant :
    un **registre des composants nommés** (`projectType` → tuiles
    segmentées, `buildSystem` → cartes radio) fixe ce que la
    spécification nomme, et un **repli générique par traits** couvre tout
    le reste (`CHOICE` → liste déroulante, `BOOLEAN` → interrupteur,
    `TEXT` → champ texte). C'est une décision de présentation pure : un
    modèle futur s'affiche correctement sans modification, et le registre
    reste modifiable sans toucher au moteur.
  - Les libellés des valeurs de choix (application/library, Gradle/Maven/
    sources, JDK 17/21) et leurs sous-titres relèvent du même registre de
    présentation — avec repli sur la valeur brute pour toute valeur
    inconnue.
  - `RaisonValidation` (core:model, type fermé) remplace les messages
    français côté UI : `TemplateValidators` retourne désormais un échec
    structuré (raison typée + message), le moteur expose
    `errorReason` par paramètre, et l'UI mappe chaque raison vers une
    ressource localisée (le message français reste réservé aux journaux
    et au bloc « copier les détails »). Un validateur inconnu retombe sur
    le message générique.
  - L'apparition/disparition des champs (`visibleWhen`) est animée par
    `animateLayoutChanges` du conteneur, conformément à la section 12.3
    (« animation d'apparition »).
- **Conséquences** :
  - ajouter un paramètre à un manifeste l'affiche immédiatement avec le
    composant de repli ; l'affiner demande une seule entrée de registre ;
  - le type fermé des raisons est testé (core:model) et le mapping
    ressourcé est couvert par l'UI ;
  - `resumer` du moteur résout aussi `iconKey` via le dictionnaire
    i18n (monogramme maison, ex. « kt », « jv ») : la grille de modèles
    n'a jamais à connaître les dictionnaires.
