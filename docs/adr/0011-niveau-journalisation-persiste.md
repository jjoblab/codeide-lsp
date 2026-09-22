# ADR 0011 — Niveau de journalisation persisté : AppSettings.logLevel comme source de vérité

- Date : 2026-09-22 (étape 4, v0.5.0)
- Statut : accepté

## Contexte

La section 5.7 du prompt maître exige un « niveau à l'exécution » piloté
par le réglage utilisateur `AppSettings.logLevel` (`NORMAL` = `INFO`,
`DETAILED` = `DEBUG`, défaut `NORMAL`, `DEBUG` par défaut en build
debug). Jusqu'à l'étape 3, la configuration initiale du moteur était
codée dans `core:logging` sans lecture de réglage ; l'étape 4 introduit
la source persistée (`core:datastore`) et doit fermer la boucle.

Deux questions se posaient :
1. **où brancher** le réglage lu vers le moteur (le détenteur de
   configuration `LogConfigHolder` est interne à `core:logging`, et pour
   cause : c'est le rouage à chaud du pipeline) ;
2. **quels défauts** écrire avant la première émission des paramètres
   (DataStore lit son fichier de façon asynchrone au premier
   démarrage).

## Décision

1. **Façade publique** : `core:logging` expose `LogLevelApplier`, classe
   publique à unique méthode `apply(LogVerbosity)` qui bascule le niveau
   minimal du détenteur interne. Aucun autre rouage du pipeline n'est
   exposé ; les autres bornes (taille, rétention) ne sont pas du ressort
   du réglage utilisateur.
2. **Branchement dans `app`** : `CodeIdeApplication`, **processus
   principal uniquement**, collecte `SettingsRepository.observeSettings()`
   dans sa portée de démarrage et applique `logLevel` à chaque émission.
   Le processus `:crash` ne lit jamais les paramètres (section 5.8) —
   le branchement vit donc dans l'assemblage, pas dans `core:logging`
   ni `core:data`, qui ne sauraient pas distinguer les processus.
3. **Défauts par type de build** : `AppSettings.defaults(buildDebuggable)`
   démarre en `DETAILED` (debug) ou `NORMAL` (release) — la détection
   passe par `FLAG_DEBUGGABLE` (suit la variante installée, pas une
   constante de compilation, cohérent avec le seuil logcat de l'étape 2).
4. **Correction concomitante** : la configuration initiale du moteur
   était `debugDefault()` **quelle que soit la variante** — en release,
   le fichier journalisait donc des entrées `DEBUG`+ jusqu'à la première
   émission des paramètres, contredisant la section 5.7 (« défaut
   `NORMAL` »). La fourniture initiale lit désormais `FLAG_DEBUGGABLE`
   et démarre à `INFO` en release. Le correctif est porté au CHANGELOG
   de la v0.5.0.

## Conséquences

- Fenêtre de démarrage : entre `LoggingInitializer.initialize()` et la
  première émission DataStore, le moteur tourne à la configuration
  initiale (build-aware) — quelques millisecondes en pratique, bornées
  par la lecture du fichier de préférences.
- Un utilisateur qui passe le réglage en `DETAILED` voit le niveau
  s'appliquer **à chaud**, sans redémarrage (effet immédiat sur les
  entrées suivantes, sans interruption du pipeline).
- Les tests : `LogLevelApplierTest` (module `core:logging`) verrouille
  la projection verbosité → niveau et la préservation des autres
  bornes ; l'intégration du branchement est couverte par le démarrage
  réel (`app`, tests d'intégration) — le détenteur interne n'est pas
  observable depuis `app`, c'est un choix assumé d'encapsulation.
