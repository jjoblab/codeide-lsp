# ADR 0030 — Actions de fichiers du tiroir : validation partagée, renommage par URI migrée, reprise par projet

- **Statut** : accepté (étape 17)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et bibliothèque
  d'édition », sections 5.2, 5.3 et 6 (étape 17) — menu contextuel de
  l'explorateur, reprise des onglets, finitions.

## Contexte

L'explorateur doit permettre **Nouveau fichier**, **Nouveau dossier**,
**Renommer**, **Supprimer** (confirmation) et **Actualiser** — tous « via
`FileSystem` » (prompt compagnon 6) — avec une validation de nom « réutilisant
les mêmes règles que le wizard » (section 12.3). La reprise des onglets à la
réouverture d'un projet passe par un petit enregistrement **non synchronisé**
(`.codeide/local/workspace-state.json`, section 5.2). Deux obstacles
structurels : le port `FileSystem` n'expose pas le renommage, et SAF
**change l'URI** d'un document renommé (un onglet ouvert doit suivre).

## Décisions

1. **`FileSystem.rename` rejoint le port (14ᵉ opération).** Toute
   lecture/écriture du stockage passe déjà exclusivement par l'interface —
   le renommage ne fait pas exception : `SafFileSystem` l'implémente par
   `DocumentsContract.renameDocument` et **retourne la nouvelle URI**
   (contrat explicite : l'appelant doit aussitôt utiliser la valeur
   retournée) ; `FakeFileSystem` déplace le sous-arbre entier sous la
   nouvelle clé, refuse la collision par nom insensible à la casse (même
   contrat que la création).
2. **Validation partagée par le validateur `file-name`.** Un nouvel
   identifiant « file-name » dans `TemplateValidators` délègue aux règles
   **exactes** du nom de projet (section 12.3 : longueur 1-64 après trim,
   caractères interdits, « . »/« .. », fin interdite, réservés Windows) —
   une seule source de vérité, consommée par
   `EvaluerNomFichierUseCase` (domaine) puis par le dialogue de l'activité
   (raison typée → ressource localisée, mêmes motifs que le wizard). Le
   dialogue valide **avant** d'émettre l'action et reste ouvert tant que
   le nom est invalide — jamais d'aller-retour silencieux.
3. **L'onglet suit le renommage (URI migrée).** SAF change l'URI : le
   ViewModel migre session, auto-sauvegarde en attente et verrou
   d'écriture vers la nouvelle clé, met à jour nom/chemin/langage de
   l'`EditorTabState` et persiste. La suppression d'un document ouvert
   (ou d'un dossier contenant des documents ouverts) ferme les onglets
   touchés — sessions libérées (`dispose`, ADR 0028).
4. **Rafraîchissement ciblé, le parent reste déplié.** Après une
   opération, seul le dossier parent est ré-énuméré (cache invalidé) ;
   les sous-arbres obsolètes (ancienne URI d'un renommé, dossier
   supprimé) voient caches et plis oubliés. Pas de rechargement complet
   du tiroir.
5. **Création : deux points d'entrée.** Le menu contextuel d'un dossier
   crée **dans** ce dossier ; un bouton dédié dans l'en-tête du tiroir
   crée **à la racine** (sinon un projet vide serait sans issue). Un
   fichier créé est ouvert en onglet immédiatement — le parcours
   « créer → éditer » d'un seul geste.
6. **Reprise par projet : le fichier complète le sauvetage.** Le
   `SavedStateHandle` couvre rotation et mort du processus (état frais) ;
   `workspace-state.json` couvre la **réouverture** (nouvelle instance,
   process vivant) : sans onglets sauvés, le ViewModel lit l'état —
   écriture asynchrone à chaque changement d'onglets, échec journalisé
   jamais bloquant, lecture **totalement tolérante** (absent, illisible,
   corrompu → `null`). Le dossier `.codeide/local/` est créé au besoin
   (projet importé sans `.codeide`) ; les `.gitignore` générés par les
   modèles l'excluent **déjà** (anticipé à l'étape 9).
7. **Suppression : confirmation explicite.** Le rappel du nom et la
   mention « action définitive » (dossier : « et tout son contenu »)
   précèdent l'action — cohérent avec la suppression de projet de
   l'accueil (étape 7).

## Conséquences

- `FileSystem` gagne une opération : `FakeFileSystem`, `SafFileSystem`
  et le test du registre de validateurs suivent — pas d'implémentation
  parallèle possible.
- La reprise lit la racine une fois de plus à l'ouverture (recherche de
  `.codeide`) : coût borné, assumé (les tests de paresse documentent le
  décompte).
- L'accessibilité des onglets est affine : `contentDescription` = nom +
  état de modification (TalkBack annonce l'onglet, pas ses vues
  internes) ; l'audit TalkBack complet reste une procédure manuelle
  (E32-E39).
- Les URIS de documents (identifiants de stockage) vivent dans
  `workspace-state.json` **dans le projet lui-même** — aucune donnée
  personnelle, conformément au contrat `.codeide/` (étape 8).
