# ADR 0061 — Réveil de l'accept du socket tooling et stderr journalisé pendant la fenêtre de connexion

- **Statut** : accepté (v0.35.1, retour utilisateur du 2026-09-27 —
  « orchestrateur non connecté » à l'ouverture d'un projet)
- **Contexte** : sur appareil réel, l'ouverture d'un projet affiche
  « orchestrateur non connecté » et le journal s'arrête net après
  `orchestrateur lancé (pid …)` — ni `orchestrateur connecté`, ni
  aucune relance, pendant au moins une minute. Reproduction JVM des
  deux chemins de la commande de production
  (`scripts/TesteJarOrchestrateur.java` du dépôt de travail) :
  (a) `java -jar gradle-server.jar --socket … --secret …` contre une
  écoute réelle se connecte et complète le handshake — le JAR, la
  ligne de commande et le client sont sains ;
  (b) sans écoute, l'orchestrateur imprime la cause EXACTE de l'échec
  sur stderr (« connexion au socket impossible : … ») et s'arrête en
  moins de 10 s avec le code 3. Deux défauts rendaient cet échec
  invisible et le daemon inerte :
  1. `GradleSocketServer.accepterUneFois` attendait la connexion par
     `withTimeout { runInterruptible { serveur.accept() } }` — or le
     `accept()` d'`android.net.LocalServerSocket` (libcore) n'est PAS
     interruptible, contrairement aux canaux NIO de l'hôte JVM des
     tests (`BoutEnBoutTest`) : le délai de 10 s ne se déclenchait
     jamais, la coroutine du daemon restait suspendue indéfiniment —
     aucune relance, aucun état `ECHOUEE`, « orchestrateur non
     connecté » à perpète ;
  2. les sorties du process n'étaient branchées au journal qu'APRÈS
     l'accept (`brancherSurveillance` post-connexion) : le stderr qui
     explique l'échec de l'orchestrateur partait dans un tuyau que
     personne ne lisait pendant la fenêtre de connexion.

## Décisions

1. **Accept détaché et réveil garanti** (`GradleSocketServer`) :
   l'accept vit dans un job détaché (`CoroutineScope(SupervisorJob())`
   + `Dispatchers.IO`), attendu par `withTimeout(delaiMs)`. Au délai,
   un client FACTICE (un `LocalSocket` de l'app elle-même) rejoint
   l'écoute — le noyau complète sa connexion dans le backlog, donc
   `accept(2)` rend la main : c'est le seul réveil garanti d'un appel
   non interruptible. Ce que le fil a fini par accepter (le factice,
   ou l'orchestrateur trop tardif) est refermé, puis l'échec typé
   `IOException` (« orchestrateur silencieux à la connexion ») remonte
   au daemon — qui nettoie, consomme une tentative et relance sur un
   secret frais. Best effort borné (`REVEIL_ACCEPT_MS` 1 s) : un réveil
   impossible n'entrave pas la remontée de l'échec.
2. **Fenêtre de connexion dédiée** (`DaemonManager.
   FENETRE_CONNEXION_MS`, 30 s) : le délai protocole
   `CONNECT_TIMEOUT_MS` (§7.5, 10 s) reste dimensionné pour le seul
   `connect` de l'orchestrateur — l'écoute existe déjà à son
   démarrage. L'attente de l'app, elle, couvre le démarrage à froid de
   la JVM sur appareil : le bout-en-bout JVM assumait déjà 30 s
   (`DELAI_CONNEXION` de `BoutEnBoutTest`). Un JVM lente n'est plus
   tuée trop tôt ; une JVM morte ou muette est relancée (bornée à
   `MAX_RECONNECT_ATTEMPTS`).
3. **stderr/stdout journalisés dès le lancement**
   (`DaemonManager.brancherSorties`, avant l'accept) : les lignes de
   l'orchestrateur rejoignent le journal applicatif sous le tag
   `gradle-server` pendant la fenêtre de connexion — la prochaine
   occurrence de l'échec de terrain s'expliquera ELLE-MÊME dans le
   journal (journalisation de l'orchestrateur, règle 14 / ADR 0040).
4. **Contrat de l'hôte** (`HoteSocketTooling.accepterUneFois`) :
   documenté `@throws IOException` — échec consommable par le daemon.
   Le nouveau bras `catch (silencieux: IOException)` de `tentative()`
   remonte l'échec TEL QUEL (message déjà précis), la boucle de vie
   traitant l'IOException comme un lancement impossible : tentative
   consommée, repli exponentiel, `ECHOUEE` après épuisement.

## Conséquences

- Le gel silencieux du daemon est impossible : chaque tentative se
  termine par une session, une mort de process ou un échec typé
  journalisé ; la machine d'états converge (`CONNECTEE` ou
  `ECHOUEE`), l'UI ne reste jamais bloquée en `EN_CONNEXION`.
- La cause racine de l'échec de connexion SUR L'APPAREIL (exécution
  JVM, environnement, politique de sécurité) n'est pas reproduite en
  JVM de bureau : elle sera lisible dans le journal applicatif au
  prochain essai — l'orchestrateur s'explique lui-même sur stderr.
- Régression couverte : `DaemonManagerTest` (stderr pendant la
  fenêtre sans session ; échec IO consommé puis épuisé).
