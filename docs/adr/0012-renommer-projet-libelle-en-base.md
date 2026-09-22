# ADR 0012 — Renommer un projet : libellé en base, jamais le dossier

- Date : 2026-09-22 (étape 4, v0.5.0)
- Statut : accepté

## Contexte

L'étape 4 introduit le registre des projets (`ProjectRepository`,
`RenameProjectUseCase`). Un projet référence un dossier réel sur le
stockage via SAF. « Renommer un projet » peut donc s'entendre de deux
façons : changer le **libellé d'affichage** (registre) ou renommer le
**dossier sur disque** (`DocumentsContract.renameDocument`).

## Décision

En Phase 1, `RenameProjectUseCase` ne change **que le libellé en base**.
Le dossier sur disque n'est jamais renommé, déplacé ni réécrit.

Motifs :
- Le contrat `FileSystem` de l'étape 4 (piloté par le prompt : `exists`,
  `stat`, `list`, `createDirectory`, `createFile`, `writeText`,
  `writeBytes`, `readText`, `delete`, permissions persistantes) **n'a
  pas d'opération de renommage** — en ajouter une hors spécification
  serait un débordement d'étape (règle 3 du mode d'emploi).
- Renommer un dossier SAF en masse est risqué et lent (une requête par
  enfant pour réécrire `.codeide/project.json`, permissions et rollback
  à gérer) : c'est une fonctionnalité à part entière, pas un effet d'un
  libellé.
- Le dossier peut être partagé avec un PC (section 12.3 : les projets
  peuvent être copiés vers un ordinateur) : renommer « à l'insu » du
  stockage casse des liens externes.

Le nom persisté reste disponible pour un futur « renommage complet »
(Phase 2+), qui devra alors répercuter : le dossier, le
`.codeide/project.json`, et l'affichage — en une opération annulable.

## Conséquences

- `ProjectRepository.renameProject` met à jour la colonne `name`
  uniquement (le `documentUri`, l'index unique et l'emplacement SAF ne
  bougent pas).
- Le dossier de travail reste identifiable par son `documentUri`
  immuable : l'état d'accès (`ProjectAccessState`) ne change pas après
  un renommage.
- L'UI future (étape 7) pourra distinguer visuellement libellé et
  dossier si besoin ; la décision d'un renommage complet de dossier
  reste ouverte pour une phase ultérieure.
