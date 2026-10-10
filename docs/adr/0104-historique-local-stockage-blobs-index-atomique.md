# ADR 0104 — Historique local : blobs adressés par empreinte + index atomique (stockage privé)

- Statut : accepté (2026-10-10) — phase H0 de la mission « Historique
  local »
- Contexte : un filet de sécurité **indépendant de Git** — enregistrer
  automatiquement les versions successives des fichiers du projet,
  retrouver un fichier supprimé, comparer, restaurer (mission H,
  « Local History » d'Android Studio). Comportement d'IntelliJ ÉTABLI
  dans le code (lecture du dépôt `intellij-community`, Apache 2.0 —
  concepts repris, code intégralement réécrit) : journal d'entrées avec
  **contenu AVANT** chaque changement (`oldContent`), reconstruction par
  rejeu, contenus adressés par identifiant et comptés par références,
  étiquettes utilisateur et système colorées, revert ANNULABLE (le
  revert est lui-même une commande nommée), purge **une fois par
  session** ~1 s après le démarrage.

## Décision

### (a) Blobs adressés par empreinte SHA-256, dans le stockage PRIVÉ

```
files/historique/<empreinte-SHA-256-de-l'URI-racine-du-projet>/
    index.json      ← liste des entrées (réécrit ATOMIQUEMENT)
    blobs/<sha256>  ← contenus, un fichier par contenu DISTINCT
```

- **Déduplication naturelle** : un contenu identique (deux fichiers, ou
  dix révisions identiques) est stocké **une seule fois** — le nom du
  blob EST son empreinte (même modèle que l'index de Git, même bénéfice
  que le stockage de contenus du VFS d'IntelliJ : comptage par
  références, ici par simple présence d'entrée pointant dessus).
- **Jamais dans le dossier du projet** : pas de pollution du dépôt Git
  ni du `.gitignore`, pas de synchronisation accidentelle.
- **Clé par projet** : l'empreinte de l'URI de document racine —
  stable, sans registre, sans base de données.

### (b) Index JSON réécrit atomiquement — Room écarté

| Critère | Blobs + index JSON (retennu) | Room (écarté) |
|---|---|---|
| Déduplication | par nom de fichier | par colonne + requête |
| Écriture atomique | `write tmp` + `rename` (le noyau garantit l'atomicité) — **la mort du processus ne corrompt JAMAIS l'index** | transactions WAL (correctes, mais BLOBs dans le fichier SQLite, VACUUM pour rendre l'espace) |
| Purge | suppression de fichiers + réécriture d'index | DELETE + VACUUM |
| Testabilité JVM | `File` + dossier temporaire, aucune infra | Room in-memory + migrations à éprouver |
| Migration | version de schéma dans le JSON (rejet propre si inconnue) | migration Room v1→v2 obligatoire (et chaque réglage ensuite) |

Room reste excellent pour le **registre de projets** (requêtes
relationnelles) ; l'historique est un **journal append-then-compact**
dont la forme est un fichier — l'ADR tranche : pas de table, pas de
migration.

### (c) Contenu AVANT, pas après (modèle IntelliJ, prouvé)

Chaque entrée enregistre l'état **PRÉCÉDENT** du fichier : l'état
courant vit sur le disque, l'historique n'est jamais doublé par la
dernière version. La liste des révisions d'un fichier = les entrées +
« Actuel ». Une suppression laisse une **pierre tombale** (dernier
contenu conservé) — le fichier supprimé reste retrouvable et
restaurable.

### (d) Ordre d'écriture (intégrité)

1. blob : écrire `blobs/<empreinte>.tmp` puis `rename` (si le blob
   existe déjà : sauter — dédup) ;
2. index : réécrire `index.json.tmp` (version + entrées) puis `rename`.

Une interruption entre 1 et 2 laisse un blob orphelin — **inoffensif**
(hors index, hors quota effectif, ramassé par la purge) ; une
interruption pendant 2 laisse l'ancien index **intact** (rename
atomique). Récupération au chargement : index absent/corrompu/version
inconnue → historique vide (jamais de fausses données), blobs
orphelins purgés.

## Conséquences

- `MoteurHistoriqueLocal` (core:domain, JVM pur) : sérialisation
  kotlinx.serialization (déjà dépendance du domaine — aucune nouvelle
  dépendance, pas d'ADR d'ajout).
- Décorateur `HistoriqueFileSystem` (voir ADR 0106 pour la capture).
- La confidentialité est par construction : stockage privé, jamais
  exporté ni sauvegardé automatiquement avec les données du projet
  (exclusions de secrets : ADR 0105).
