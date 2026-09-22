# ADR 0017 — Moteur de templates : plan figé, le dry-run est l'écriture

- Date : 2026-09-22 (étape 8, v0.9.0)
- Statut : accepté

## Contexte

Le wizard de création (étapes 10-11) doit montrer un **récapitulatif
prévisionnel** de l'arborescence avant que l'utilisateur ne confirme, puis
créer exactement ce qui a été annoncé. L'étape 8 introduit le moteur qui
produira ces fichiers : manifestes déclaratifs, substitution, filtres
d'échappement, métadonnées `.codeide/project.json`, licence.

Deux approches étaient possibles :

1. Un moteur qui énumère des **chemins** au dry-run, puis re-rend les
   contenus à l'écriture (deux passages) ;
2. Un moteur qui produit un **plan complètement figé** — chemins substitués,
   textes rendus, binaires lus — consommé tel quel par l'écriture.

## Décision

**Le plan (`TemplatePlan`) contient le contenu final de chaque fichier**
(`PlannedFile(chemin, group, contenu)`), et `CreateProjectUseCase` écrit
exactement ce plan, dans l'ordre, sans re-rendu.

1. **`PlanProjectCreationUseCase` et `CreateProjectUseCase` partagent le
   même `TemplateProjectPlanner`** — même assemblage des entrées (auteur
   des paramètres applicatifs, année de l'horloge injectée, version du
   générateur), même appel `moteur.planifier(…)`. Ce qui est planifié est
   ce qui est écrit, à l'octet près.

2. **Dry-run structurel** : le moteur ne reçoit que `TemplateAssetsSource`
   (lecture) — aucun port d'écriture n'existe dans son constructeur. Un
   plan ne peut pas écrire sur le disque, même par accident.

3. **Déterminisme** : le plan est une valeur figée (l'année vient de
   l'horloge injectée, pas de `System.currentTimeMillis()`). Deux
   exécutions avec les mêmes entrées produisent des plans égaux —
   `PlannedContent.Binaire` implémente l'égalité par contenu pour que
   l'assertion soit possible sur les octets.

4. **Ordre d'écriture garanti** : fichiers dans l'ordre du manifeste,
   puis `LICENSE` (option), puis `.codeide/project.json` (toujours) —
   le récapitulatif et la progression affichent le même ordre.

5. **Jamais d'écrasement** : le dossier racine doit être **absent**
   (`createDirectory` échoue s'il existe) ; les doublons de chemins dans
   le plan sont refusés (comparaison insensible à la casse, FAT/NTFS) ;
   la garde de sécurité contrôle chaque chemin rendu (`..`, absolu,
   séparateurs, noms réservés Windows — les projets sont copiés vers des
   PC).

## Conséquences

- Le récapitulatif de l'étape 11 affichera le plan tel quel (chemin +
  groupe), sans recomputation ni divergence possible.
- `CreateProjectUseCase` reste mince : revalidation, création du dossier
  racine, écriture des fichiers du plan, enregistrement en base, rollback
  (`NonCancellable`) — toute la complexité de rendu vit dans le moteur pur.
- Un plan volumineux consomme de la mémoire (contenus complets en
  attente) : acceptable, les projets générés sont de petits scaffolds
  (bornes de taille par asset : 8 Mo).
- Les tests d'acceptation comparent directement le disque au plan
  (`le disque contient exactement le plan byte à byte`).
