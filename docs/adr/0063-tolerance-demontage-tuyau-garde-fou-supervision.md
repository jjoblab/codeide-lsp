# ADR 0063 — Le démontage du tuyau n'est pas un plantage : tolérance de `lignes` + garde-fou anti-plantage de la supervision

- **Statut** : accepté (v0.35.3, retour utilisateur du 2026-09-27 — rapport
  de plantage v0.35.2 : EditorActivity, 137,8 s de process)
- **Contexte** : le correctif de l'ADR 0062 a tenu — le journal de terrain
  montre `orchestrateur connecté`, `handshake accepté — app 0.35.2,
  fonctionnalités : build, sync, tasks, dependencies, model, cancel, heap,
  cancel…`, puis `connexion Tooling API ouverte`. Puis, environ deux minutes
  plus tard : `orchestrateur muet (aucun pong en 15000 ms) — arrêt forcé`,
  et **56 ms après**, l'app PLANTE :
  `java.io.InterruptedIOException: read interrupted by close() on another
  thread`, pile `ProcessusGere$lignes$1.invokeSuspend(ProcessusGere.kt:61)`.

## Analyse

1. **Le plantage** : sur Android (libcore), la mort du process referme les
   descripteurs du tuyau depuis un AUTRE fil — la lecture bloquée dans
   `readLine()` est RÉVEILLÉE par `InterruptedIOException` (message exact
   de libcore : « read interrupted by close() on another thread »). Cette
   fermeture est DÉCÉDÉE par notre supervision (arrêt forcé du health check,
   nettoyage de fin de tentative) : la fin du flux est NORMALE, pas un
   échec. Or le flux `lignes` laissait l'exception remonter au collecteur —
   une coroutine `launchIn` sous `SupervisorJob` **sans gestionnaire
   d'exceptions** : le `SupervisorJob` isole les ANNULATIONS, pas les
   exceptions non interceptées — elles atteignaient le gestionnaire de
   non-interception d'Android et tuaient le processus entier, pour un
   diagnostic de tuyau.

2. **Constat JVM de contraste** (empirique, reproduit au harnais) : sur
   JVM bureau, une fermeture par un autre fil ne réveille PAS la lecture
   (blocage indéfini) — le mécanisme d'interruption-réveil est propre à
   libcore. Le chemin EOF (kill → tuyau refermé → `readLine` rend `null`)
   reste le comportement JVM observable ; les deux sont désormais des fins
   normales.

3. **La muétude de l'orchestrateur** (déclencheur de l'arrêt forcé, distinct
   du plantage) : diagnostic porté au ROADMAP — le pompe d'événements du
   client (`GradleApiImpl.pomper`) route TOUT par un seul collecteur, et
   son unique point SUSPENDANT est la livraison des lignes de build
   (`pomperSortie.send` sur un canal borné de 4096). Un collecteur UI mort
   (ViewModel détruit pendant un build, écran quitté) remplit le canal,
   gèle le pompe — les pongs ne sont plus traités, `dernierPongMs` vieillit,
   le health check déclare la muétude et tue. Le serveur engage alors SA
   contre-pression conçue (file de 8192, `publier` bloquant — légitime sur
   les fils de pompage Gradle, contrat §5.2). La découpe santé/livraison
   mérite sa propre décision (v0.35.4+) : elle touche au contrat de
   non-perte documenté de `pomperFin` (drainage du tampon par un collecteur
   tardif).

## Décisions

1. **`ProcessusGere.lignes` tolère le démontage** : une
   `InterruptedIOException` pendant la lecture termine le flux
   NORMALEMENT (la supervision a décidé de fermer) ; une `IOException`
   avec le process déjà mort reçoit le même traitement (bruit de
   démontage) ; le process VIVANT la remonte — une vraie erreur de lecture
   n'est jamais masquée.
2. **Garde-fou anti-plantage de la supervision** (`DaemonManager`) : un
   `CoroutineExceptionHandler` journalise tout échec NON PRÉVU des
   coroutines de supervision (lectures stdout/stderr du process, health
   check, boucle de vie) — plus jamais un diagnostic de tuyau ne tue
   l'app. L'échec reste visible dans le journal applicatif (rapport
   d'erreur de l'opérateur).
3. **Keystore debug stable en CI** (`ci.yml`) : le runner GitHub est
   éphémère — AGP régénère `~/.android/debug.keystore` à chaque run, chaque
   APK porte une signature différente, Android refuse la mise à jour
   (« conflit de package » — retour utilisateur : désinstallation
   obligatoire avant chaque réinstallation). Le workflow met désormais le
   fichier en cache (`actions/cache`, clé fixe) : tous les APK CI
   successifs partagent la même signature et se mettent à jour les uns sur
   les autres. Les keystores restent HORS du dépôt (règle .gitignore
   « jamais dans le dépôt ni dans les archives ») — seule la machine de
   runner les conserve. Durcissement possible si l'éviction de cache
   (7 jours d'inactivité) mordait un jour : secret GitHub
   `DEBUG_KEYSTORE_B64` écrit au même chemin — décision reportée, le cache
   suffit au rythme de livraison actuel.

## Conséquences

- L'arrêt forcé (orchestrateur muet, fin de tentative, `arreter`) ne
  plante plus l'app : le lecteur du tuyau se termine proprement, le fil
  d'E/S retourne au pool.
- Un échec imprévu de supervision devient une ligne de journal, pas un
  rapport de plantage — le chemin de secours reste observable.
- Les régressions sont verrouillées des deux côtés du mécanisme :
  `ProcessusGereTest` rejoue l'`InterruptedIOException` EXACTE du journal
  de terrain sur un process factice (plus le kill réel pendant la lecture
  bloquée, plus les frontières vif/mort des `IOException`) ;
  `DaemonManagerTest` vérifie que le lecteur explosif est JOURNALISÉ et
  que la session SURVIT.
- La muétude de l'orchestrateur reste possible (arrêt forcé puis relance
  bornée — le comportement CONÇU) tant que la découpe santé/livraison du
  pompe n'est pas décidée : voir ROADMAP v0.35.4+.
