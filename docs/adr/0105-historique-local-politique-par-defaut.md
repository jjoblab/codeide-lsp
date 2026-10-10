# ADR 0105 — Historique local : politique par défaut (rétention, quota, exclusions, secrets)

- Statut : accepté (2026-10-10) — phase H0 de la mission « Historique
  local »
- Contexte : Android Studio conserve **5 jours** (valeur exacte
  `localHistory.daysToKeep`, défaut 5) — sémantique mesurée : 5 jours
  de **longueur d'activité cumulée** (chaque trou ≥ 12 h compte « 1
  jour »), purge UNE fois par session, **AUCUN quota de taille ni de
  nombre** (acceptable sur un poste de travail, dangereux sur un
  téléphone au stockage privé borné), contenu binaire non enregistré
  par défaut, fichiers ignorés exclus.

## Décision

### Rétention : 5 jours calendaires (divergence assumée et documentée)

IntelliJ compte des « jours d'activité » (trous ≥ 12 h = 1 jour) — plus
généreux pour un travail continu. CodeIDE retient **5 jours
calendaires** : plus simple à expliquer dans les Paramètres (« Conserver
5 jours »), à éprouver en JVM (horloge factice, pas de sémantique de
trous), et assez proche du ressenti. Réglable (H6). Divergence
consignée ici même.

### Plafonds mobiles (absents chez IntelliJ — nécessaires ici)

| Plafond | Valeur par défaut | Rôle |
|---|---|---|
| Quota total par projet | **256 Mo** (blobs comptés) | le stockage privé d'une app n'est pas le disque d'un poste : au-delà, on supprime les entrées LES PLUS ANCIENNES d'abord, puis leurs blobs orphelins |
| Taille maximale par fichier | **2 Mo** de texte | au-delà : entrée **sans contenu** (la pierre tombale existe, le contenu est « indisponible » — honnêteté d'IntelliJ : `content is not available`) |
| Nombre maximal d'entrées | **5 000** par projet | borne la taille de l'index et le coût du listing |

La purge (âge + quota) s'exécute **à l'ouverture du projet** puis après
chaque lot d'écritures enregistrées — sur le dispatcheur E/S, jamais sur
le fil principal ; elle est incrémentale (vieux blobs d'abord).

### Fichiers texte uniquement + exclusions de CHEMINS

Enregistrer le contenu des fichiers **texte** ; les binaires reçoivent
des entrées (création/suppression/renommage) mais **pas de contenu**
(même choix qu'IntelliJ, `lvcs.store.binary.file.content.on.deletion.mb
= 0` par défaut). Exclusions par segments du chemin relatif :

- `build/`, `.gradle/`, `.git/`, `.codeide/`, `.idea/`, `node_modules/`
  — artefacts et état, jamais des sources ;
- **secrets** (toujours exclus, contenu JAMAIS historisé) :
  `local.properties`, `*.jks`, `*.keystore`, `*.env`, `*.pem`, `*.p12`,
  `*.key`, `secrets.properties`, `google-services.json`.

Les exclusions s'appliquent à la CAPTURE (le décorateur ne crée pas
d'entrée) comme au contenu : le moindre `local.properties` ne doit pas
atterrir dans le stockage privé de CodeIDE au titre de l'historique.

### Pas d'entrée si rien ne change

Contenu identique à la dernière révision du même chemin → **aucune
entrée** (pas d'historique de bruit — même règle qu'IntelliJ via
`modificationStamp`).

## Conséquences

- `PolitiqueHistorique` : type valeur du domaine, réglable (H6), défaut
  figé dans une constante documentée.
- Les plafonds sont testés en JVM pur (horloge + dossier temporaires) :
  purge par âge, par quota, par nombre, entrée sans contenu au-delà de
  la taille maximale, exclusion des secrets.
- Aucune donnée ne sort de l'appareil ; la purge ne demande jamais
  confirmation (l'historique est un cache, pas un document).
