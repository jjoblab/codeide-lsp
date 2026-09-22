# ADR 0014 — Réinitialiser les préférences : des états, pas que des préférences

- Date : 2026-09-22 (étape 6, v0.7.0)
- Statut : accepté

## Contexte

L'étape 6 introduit « Réinitialiser les préférences » (confirmation
obligatoire) dans la section Avancé. `AppSettings` contient des champs
de natures différentes : des préférences utilisateur (thème, couleurs
dynamiques, langue, dossier de travail, nom d'auteur, licence,
verbosité) mais aussi `isSetupCompleted`, un **état applicatif** (le
premier lancement a-t-il eu lieu ?). Par ailleurs, la base Room des
projets et les permissions SAF persistantes décrivent des **dossiers
réels** sur le stockage.

## Décision

`ResetPreferencesUseCase` remet **uniquement les préférences** à leurs
valeurs par défaut :

1. `isSetupCompleted` est **conservé vrai** — « Relancer
   l'assistant » est une action distincte et explicite de la même
   section ; une réinitialisation silencieuse qui ferait revenir
   l'assistant au prochain lancement serait une surprise.
2. Le **registre des projets (Room) n'est pas touché** : les projets
   référencent des dossiers réels qui existent indépendamment des
   préférences ; les effacer rendrait des dossiers orphelins
   inaccessibles.
3. Le dossier de travail est effacé du réglage, et sa permission
   persistante suit la **même règle que le reste de l'étape 6** :
   libérée uniquement si aucun projet du registre ne vit dans son
   arbre (un projet dépend du dossier si son URI de document est
   celle du dossier ou commence par elle suivie d'un séparateur).

La même règle des permissions conditionnelles s'applique au
**changement** (`ChangeWorkspaceUseCase`) et à l'**effacement**
(`ClearWorkspaceUseCase`) du dossier de travail : l'ancienne
permission n'est jamais libérée aveuglément, et l'ancien dossier est
toujours lu **avant** la persistance du nouveau (l'écrasement
d'abord ferait perdre la référence à libérer).

## Conséquences

- La réinitialisation est visible et réversible par l'utilisateur
  uniquement par re-saisie : d'où la confirmation obligatoire.
- Un utilisateur qui veut « tout refaire » combine : réinitialiser les
  préférences (cet écran), relancer l'assistant (même section) et
  retirer ses projets (étape 7, actions par projet).
- Le plafond système de 512 permissions persistantes (Android 11+,
  section 5.6) reste respecté : on ne garde une permission que si un
  projet en dépend.
