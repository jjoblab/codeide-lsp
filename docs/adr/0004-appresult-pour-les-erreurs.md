# ADR 0004 — AppResult/AppError pour les erreurs attendues

- **Statut** : accepté (étape 0)
- **Contexte** : les opérations de CodeIDE échouent de façon **prévisible** :
  dossier introuvable, permission perdue, nom déjà pris, disque plein, entrée
  invalide. Remonter ces cas par des exceptions jusqu'à l'UI les transforme
  en plantages potentiels, cache le flux de contrôle et rend les messages
  utilisateur difficiles à localiser.
- **Décision** : les cas d'échec attendus sont **modélisés** — les fonctions
  du domaine retournent `AppResult<out T>` (`Success(value)` |
  `Failure(error)`), avec `AppError` scellé (`Storage` et ses variantes
  `PermissionLost`, `NotFound`, `AlreadyExists`, `NoSpace`, `NotWritable`,
  `Io`, plus `Validation`, `Template`, `Unknown`). L'UI traduit chaque
  `AppError` en message localisé ; les détails techniques vont dans les
  journaux via `AppLogger`. Les exceptions restent le mécanisme des **bugs**
  (attrapés par le gestionnaire de plantages), pas des cas prévus.
  `CancellationException` est toujours relancée.
- **Conséquences** :
  - le type force le traitement de l'échec à la compilation ; le flux
    nominal reste lisible (`when (result)` exhaustive) ;
  - les messages d'erreur sont localisables et testables unitairement ;
  - coût : verbosité accrue aux frontières (mappages résultat → état UI),
    contenue par des helpers dans `core:ui`.
