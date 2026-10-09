# ADR 0094 — Moteur de recherche : balayage Kotlin pur en flux

- Statut : accepté (2026-10-09)
- Contexte : mission Recherche S0 (prompt `prompt-agent-recherche.md`).

## Décision

Le moteur de recherche de CodeIDE est un **balayage Kotlin pur en flux**
(`java.nio`, coroutines, parallélisme borné) sur le chemin FUSE réel du
projet. Les alternatives `rg`/`grep` du bootstrap et l'index persistant
sont **écartées** pour la v1.

## Justification

### Comparaison

| Critère | (a) Balayage Kotlin | (b) rg/grep bootstrap | (c) Index persistant |
|---|---|---|---|
| Dépendance | Aucune | Runtime terminal | Lucene/autre |
| Contrôle | Total (coroutines) | Ligne de commande | Complex |
| Annulation | Native (Flow) | kill process | Difficile |
| Tests JVM | Faciles (vrais dossiers) | Process/stub | Invalidations |
| Mémoire | Bornée (lots, plafond) | Natif | Index sur disque |
| Premier résultat | Immédiat (flux) | Latence process | Pré-calculé |

Le critère déterminant : **pas de nouvelle dépendance** (règle du projet)
et **contrôle total** de l'annulation et de la mémoire via les coroutines.

### Pont FUSE

`ResoudreRepertoireProjet` (ADR 0038) produit le chemin réel
`/storage/emulated/0/…` — utilisable directement par `java.nio.File`
pour le balayage.

### Règles d'exclusion

- Toujours exclure : `.git/`, `build/`, `.gradle/`, `node_modules/`
- Respecter `.gitignore` (option activée par défaut)
- Ignorer les fichiers binaires (NUL dans les 8 premiers Ko)
- Taille max par fichier : 2 Mo (configurable)
- Encodage UTF-8 avec repli explicite
- Plafond de résultats : 20 000 (comme VS Code)

## Conséquences

- Nouveau port `RechercheMoteur` dans `core:domain` (interface pure JVM)
- Implémentation `RechercheMoteurBalayage` dans `core:domain` (pur JVM,
  `java.nio`, coroutines)
- Fake `FakeRechercheMoteur` dans `core:testing`
- Flux de résultats par lots (`Flow<LotResultats>`)
- Annulation immédiate quand la requête change

## Références

- ADR 0038 (pont SAF → FUSE)
- VS Code « Find in Files » (plafond 20 000, exclusion .gitignore)
- Android Studio « Find in Files » (`FindInProjectUtil.java`)
