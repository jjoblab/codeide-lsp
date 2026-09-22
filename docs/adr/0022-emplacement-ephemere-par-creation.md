# ADR 0022 — Emplacement de création éphémère et permission relâchée à l'abandon

- **Statut** : accepté (étape 10)
- **Contexte** : la section 12.3 impose à l'étape « Informations et
  emplacement » un dossier **par défaut = le dossier de travail des
  Paramètres**, avec un bouton « Changer de dossier » **« pour cette
  création uniquement »** (jamais persisté dans les réglages), des
  vérifications asynchrones avec délai (permission, joignabilité,
  collision de nom), et un dialogue d'abandon. Or le système plafonne les
  permissions persistantes SAF (512 sur Android 11+, section 5.6) : un
  dossier choisi hors de l'arbre du dossier de travail en consomme une.
- **Décision** :
  - `ResolveCreationLocationUseCase` (core:domain) applique au choix
    éphémère la **même politique d'héritage que l'import** (ADR 0015) :
    un dossier **dans l'arbre** du dossier de travail hérite de sa
    permission (URI réadressée dans l'arbre) ; un dossier **ailleurs**
    passe les contrôles de l'onboarding — dossiers refusés Android 11+
    **avant** toute prise de permission, permission persistante, test
    d'écriture témoin, relâchement à tout échec.
  - L'override vit dans le `SavedStateHandle` du wizard (survit à la
    mort du processus) mais **jamais dans les réglages** ; l'emplacement
    effectif = override sinon dossier de travail courant.
  - `ReleaseCreationLocationUseCase` est appelé à l'abandon confirmé :
    il ne relâche la permission propre que si elle ne sert plus à rien —
    ni le dossier de travail courant, ni l'arbre d'un projet du registre
    (même équilibre que la suppression, ADR 0016). L'écran de création
    (étape 11) l'appellera aussi sur son propre échec.
  - `VerifyCreationTargetUseCase` éprouve la cible à chaque frappe
    (délai côté ViewModel) : `stat` (une permission révoquée se voit
    comme `NotFound`, jamais un crash) puis listing des enfants —
    **collision insensible à la casse, fichiers compris** (SAF refuse
    toute collision de nom dans un dossier). L'inscriptibilité, elle, a
    été prouvée au moment du choix (témoin d'écriture) : on ne recrée pas
    un témoin à chaque frappe.
- **Conséquences** :
  - le plafond de permissions ne fuit pas : un abandon est toujours
    propre, un succès laisse un projet qui référence l'arbre ;
  - la vérification de cible est annulable et replanifiée (délai 400 ms)
    — aucun listing en rafale ;
  - la carte d'emplacement affiche des erreurs **inline et actionnables**
    (dossier refusé, échec de validation, collision, joignabilité), sans
    jamais de popup (section 12.1).
