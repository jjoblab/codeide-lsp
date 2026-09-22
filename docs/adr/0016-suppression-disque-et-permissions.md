# ADR 0016 — Supprimer du disque : disque d'abord, registre ensuite, permissions en équilibre

- Date : 2026-09-22 (étape 7, v0.8.0)
- Statut : accepté

## Contexte

L'étape 7 distingue deux actions souvent confondues :

- **« Retirer de la liste »** : le projet quitte le registre, le dossier
  reste sur le disque (le retrait n'est pas une suppression) ;
- **« Supprimer du disque »** (confirmation obligatoire, avec rappel du
  nom) : le dossier du projet et tout son contenu sont **définitivement
  effacés**, puis l'entrée de registre disparaît.

Deux questions d'ordre d'opérations et de permissions se posent :
que faire si la suppression du dossier échoue à mi-chemin, et quand
libérer la permission persistante de l'arbre concerné.

## Décision

1. **Disque d'abord, registre ensuite.** `DeleteProjectOnDiskUseCase`
   supprime le dossier (`FileSystem.delete`), et ne retire l'entrée du
   registre **qu'après succès**. Si la suppression échoue (permission
   révoquée entre-temps, erreur E/S), le registre est intact :
   l'utilisateur peut réessayer, relocaliser, ou retirer l'entrée
   ensuite. L'ordre inverse laisserait une entrée fantôme pointant un
   dossier à demi-supprimé.

2. **La confirmation vit dans l'UI** : le dialogue de
   `feature:home` rappelle le **nom** du projet (exigence de l'étape 7)
   et l'irréversibilité ; le cas d'usage exécute une demande déjà
   confirmée.

3. **Équilibre des permissions** (`libererPermissionSiInutilisee`,
   partagé avec le retrait de liste et la relocalisation) : après retrait
   du registre, la permission de l'arbre est libérée **uniquement si**
   le dossier de travail ne la référence plus **et** si aucun projet
   restant ne vit dans le même arbre. Ainsi :
   - retirer le dernier projet importé d'un arbre libère sa permission ;
   - retirer un projet dont un frère partage l'arbre la conserve ;
   - un projet dans l'arbre du dossier de travail ne libère jamais rien
     (c'est la permission du dossier de travail).

4. **La suppression ne détruit jamais le dossier de travail lui-même**
   en tant que réglage : si l'utilisateur supprime du disque le dossier
   qui sert de dossier de travail (cas dégénéré de l'import), le réglage
   reste — le bandeau de reconfiguration n'apparaît pas — mais l'état
   d'accès des projets vivant dessous passera à `Introuvable` au
   prochain rafraîchissement, avec les actions de résolution.

## Conséquences

- `RemoveProjectUseCase` (étape 4) est enrichi : le retrait applique la
  même règle d'équilibre des permissions — sans cela, retirer le
  dernier projet importé laisserait une permission orpheline jusqu'au
  plafond système (512 sur Android 11+).
- Un projet supprimé du disque dont la suppression échoue laisse le
  registre intact : c'est le comportement demandé (« sans crash », le
  message d'erreur remonte typé).
- Les tests couvrent les trois endroits où la règle s'applique
  (retirer, supprimer du disque, relocaliser) avec projets frères,
  dossier de travail et permission orpheline.
