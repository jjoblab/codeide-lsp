# ADR 0069 — Protocole v4 : phases de sync réelles, action unique, téléchargements visibles

- Statut : accepté (2026-09-29)
- Contexte : prompt « CodeIDE — tooling Gradle professionnel » (correctifs + design)
- Décision : voir `docs/TOOLING.md` section v4

## Contexte

La sync v3 mentait sur son déroulé : la phase `CONNEXION` prétendait couvrir
le téléchargement de la distribution alors que `connect()` ne télécharge
RIEN (la distribution se résout paresseusement au premier usage), et les
deux `model().get()` séparés configuraient le build DEUX fois. Aucune
visibilité sur les téléchargements de dépendances, ni pour la sync ni pour
le build : `GradleApiImpl.pomper` jetait les `ProgressEvent` (« PERSONNE ne
les émet »). Le timeout client de sync était un TOTAL de 5 minutes — un
premier lancement sur réseau mobile qui téléchargeait lentement mourait
mid-course. Le listage des tâches faisait un aller-retour serveur de 30 s à
chaque ouverture du sélecteur, alors que la sync venait de résoudre
exactement ces informations.

## Décision

1. **Protocole v4** (égalité exacte au handshake, dorés régénérés par
   `RegenerateurDoresTest` — régénération volontaire, relue en diff, jamais
   en CI) : phases RÉELLES `OUTILS/DISTRIBUTION/DAEMON/CONFIGURATION/
   MODELE_TACHES/MODELE_IDE/DEPENDANCES/CLASSPATHS`, `SyncProgress` enrichi
   de détails (octets reçus/total, élément, compteur — défauts compatibles),
   `ProgressEvent` porteur d'un `DetailTelechargement`, arguments embarqués
   dans `SyncRequest`/`ClasspathRequest`.
2. **Vérification d'API avant code (règle 9)** : la surface 9.7.1 est
   inspectée par `javap` sur le JAR du cache — quatre suppositions corrigées
   (paquet `events.download` et non `events.file` ; `GENERIC` et non
   `GENERIC_PROGRESS` ; octets seulement à la fin du téléchargement ;
   `setStreamedValueListener` retourne `void`). La vérification est
   consignée dans `docs/TOOLING.md` pour la prochaine session.
3. **Action unique** : une `BuildAction` résout les deux modèles en une
   requête ; les transitions de phases streament par `BuildController.send`
   vers le `StreamedValueListener` (marqueur java-sérialisable — l'action
   s'exécute dans le daemon, elle ne touche ni bus ni lambda client). Le
   résultat alimente un **CacheSync serveur** : `taches()` et `classpath()`
   répondent sans re-résolution tant qu'aucune sync ne l'a remplacé.
4. **Sondes honnêtes** : la distribution installée se lit au marqueur
   `wrapper/dists/<nom>/<hash>/*.zip.ok` (layout vérifié) ; les octets en
   cours, à la taille des `.part` (la Tooling API n'en donne AUCUN pour la
   distribution) ; les phases opportunistes (CONFIGURATION, DEPENDANCES)
   ne s'ouvrent QUE si Gradle émet les événements — une phase absente est
   un fait, pas un mensonge.
5. **Écouteur commun** (`EcouteurProgressionCommun`) : FILE_DOWNLOAD +
   PROJECT_CONFIGURATION pour la sync ET le build, débit borné
   (5 événements/s par élément), noms d'artefact réduits au dernier
   segment d'URI (règle 15).
6. **Délai d'inactivité client** : 90 s SANS événement (fenêtre réarmée à
   chaque signe de vie) au lieu de 5 minutes de total — seuls les délais
   serveur (§7.5) restent inchangés.

## Conséquences

- Le format câble change (v3 → v4) : app et orchestrateur doivent être
  reconstruits ENSEMBLE (l'égalité exacte au handshake le garantit — le
  JAR orchestrateur est un artefact de build régénéré par `preBuild`,
  jamais versionné, ADR 0040 : la contrainte est déjà structurelle).
- Les tests d'intégration réels valident le déroulé (19 verts, dont
  listage/classpath depuis le cache < 2 s).
- L'état client suit : `etapesAffichees` dérivées des lignes (l'arbre EST
  l'état), `tachesDisponibles` remplies à la fin d'une sync, `EtatEnteteTooling`
  sorti du présentateur pur — l'UI complète (§3.3, §7) s'y branche.
- Refus explicite : les durées des phases opportunistes ne se devinent
  JAMAIS — une phase non conclue par Gradle reste non conclue, la console
  du client conclut tout au pire à `conclureTout()` avec les durées
  réelles mesurées.
