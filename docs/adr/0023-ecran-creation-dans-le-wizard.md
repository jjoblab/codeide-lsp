# ADR 0023 — L'écran de création vit dans le wizard, piloté par l'état

- **Statut** : accepté (étape 11)
- **Contexte** : la section 12.2 impose **cinq étapes numérotées** puis un
  **écran de création** « hors numérotation » : liste de progression en
  temps réel, bouton Annuler, états succès/échec avec rollback signalé.
  Trois implantations étaient possibles : une destination séparée du graphe
  de navigation, une activité dédiée, ou un fragment enfant du wizard.
- **Décision** :
  - l'écran de création est un **fragment enfant de `NewProjectFragment`**
    (`EcranCreationFragment`) rendu dans le **même conteneur** que les
    étapes ; l'hôte masque l'indicateur et la barre d'actions tant que
    `EtatWizard.etatCreation` n'est pas `Inactif` — c'est l'état qui décide
    de l'écran affiché, jamais le fragment qui se déclare (UDF, section 5.3).
  - `EtatCreation` (scellé : `Inactif`, `EnCours(evenements)`, `Succes`,
    `Echec`) vit dans `EtatWizard` : il survit donc **à la rotation** sans
    code dédié, et la mort du processus retombe proprement sur le
    récapitulatif (`etatCreation` n'est pas sauvé : une création interrompue
    par la mort du processus est finie, jamais laissée « en cours »).
  - `WizardViewModel` collecte le flot froid de `CreateProjectUseCase` dans
    un `Job` : chaque événement alimente `EnCours` ; l'événement terminal
    tranche `Succes`/`Echec`. **Annuler = `Job.cancel()`** — le domaine
    attrape la `CancellationException`, roule le rollback en
    `NonCancellable`, puis relance l'annulation ; le ViewModel remet alors
    `Inactif` (retour au récapitulatif, jamais d'écran fantôme).
  - le retour système et le ✕ pendant `EnCours` **annulent** (et non :
    n'abandonnent le wizard) ; sur `Succes`/`Echec` ils referment l'écran
    par les actions existantes.
- **Alternatives rejetées** :
  - *Destination de navigation séparée* : l'état du wizard (paradigme
    « un seul écran, machine à états ») devrait être soit partagé par un
    scope parent artificiel, soit re-sérialisé dans un `Bundle` — perte de
    la survie gratuite pour un gain nul.
  - *Activité dédiée* : aucune raison d'un changement de contexte — la
    création n'est pas un point d'entrée externe (contrairement à
    `CrashActivity`, ADR 0010).
- **Conséquences** :
  - un seul `SavedStateHandle` couvre tout le parcours : étapes **et**
    création survivent ensemble à la rotation ;
  - la liste d'événements (`EnCours`) est reconstituée à la rotation
    depuis l'état — les lignes passées restent affichées ;
  - l'indicateur « Étape N sur M » disparaît pendant la création
    (hors numérotation, conformément à la spécification).
