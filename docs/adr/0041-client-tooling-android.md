# ADR 0041 — Client Android du tooling Gradle (G3) : tooling:client

- **Statut** : accepté (étape G3, v0.28.0)
- **Contexte** : prompt compagnon Tooling, sections 5.1 à 5.3 et 7.3 — après
  l'orchestrateur JVM de G2, le côté Android du dialogue : écoute du socket
  AVANT tout lancement de process, validation du handshake au secret, façade
  publique dans le domaine, diffusion JAMAIS conflatée de la sortie des
  builds. Testé avec un `SocketClient` fake (§7.3), sans Android.

- **Décisions** :

1. **Port du domaine, pas de tool:api côté app** (§5.3/§2.2) :
   `GradleToolingRepository` vit dans `core:domain` avec ses modèles propres
   (`LigneSortieBuild`, `EtatBuild`, `ResultatSynchronisation`, `InfoTache`,
   `DiagnosticBuild`, `InstantaneTas`, `EtatConnexion`) — DÉLIBÉRÉMENT sans
   aucun type du protocole ni de `tooling:api`. Miroir exact de
   `TerminalSessionRepository` : les features (G5) consommeront l'interface
   du domaine sans jamais dépendre d'un module tooling. `tooling:api` (G2)
   reste la frontière du côté JVM ; les modèles domaine sont la frontière du
   côté app — deux vocabulaires, deux frontières, aucun mélange.

2. **Namespace FICHIER pour le socket** (§5.1/§4.4) : l'orchestrateur JDK 17
   se connecte via `UnixDomainSocketAddress`, qui ne sait joindre QUE des
   chemins de fichiers — le namespace abstrait par défaut de
   `LocalServerSocket(String)` lui est inaccessible (vérifié dans le source
   AOSP : le constructeur `String` bind en ABSTRACT). Astuce API publique :
   `LocalSocket.bind(LocalSocketAddress(..., FILESYSTEM))` occupe l'adresse
   fichier, puis `LocalServerSocket(FileDescriptor)` pose le `listen()` sur
   ce descripteur — tout est public depuis l'API 8, sans réflexion sur les
   interfaces cachées. Défense en profondeur conservée : répertoire `0700`,
   permissions du fichier socket resserrées au mieux (best effort), résidu
   d'une session précédente supprimé — et le secret de handshake reste
   OBLIGATOIRE dans tous les cas (le prompt ne conditionne jamais la
   sécurité à la seule vérification filesystem, §4.4).

3. **Canaux bornés par build, pas un `SharedFlow` global** (§5.2) : la
   sortie d'un build voyage dans un `Channel(4096)` créé À L'OUVERTURE du
   build et `send()` suspend (contre-pression) — jamais `DROP_OLDEST`.
   Différence décisive avec le `MutableSharedFlow` de l'esquisse du prompt :
   le canal tamponne les lignes émises PENDANT le build même sans abonné,
   et reste lisible APRÈS `BuildFinished` (fermé mais non retiré) — un
   onglet Sortie ouvert tardivement, ou une rotation, draine l'historique
   complet au lieu de le manquer. Les états (build/tas/connexion/
   diagnostics) restent des `StateFlow` : conflation légitime, seul
   l'état courant compte. Test §7.3 : 12 000 lignes d'un trait, aucune
   perdue (le `SharedFlow` historique à rejeu nul en perdrait — c'est le
   bug `LiveData.postValue` documenté par le prompt).

4. **Écho d'identifiant requis, serveur corrigé** (§3.2) : en écrivant le
   client, la corrélation par livre de promesses (`CompletableDeferred`
   par `requete.id`) a mis au jour un défaut de G2 : `TasksHandler`,
   `DependenciesHandler`, `SyncHandler` et `ModelHandler` généraient un
   NOUVEL identifiant au lieu d'échoyer celui de la requête — les
   réponses ne résolvaient jamais la promesse (délai systématique).
   Corrigé côté serveur (`id = requete.id`) + assertions d'écho ajoutées
   aux tests d'intégration réels de G2.

5. **Handshake consommé avant le pompe** : la première frame (le
   `HelloRequest`) est lue hors du flux d'événements, dans
   `GradleSocketServer.accepterUneFois` — le pompe de `GradleApiImpl` ne
   voit que la suite (frame 2+). AUCUNE requête ne peut donc atteindre un
   handler avant validation du secret (§8) : le refus (`HandshakeApp`)
   envoie l'`ErrorResponse` typée à l'orchestrateur PUIS ferme la
   connexion (§1.6 : message clair, jamais un comportement indéfini).

6. **Couture `SessionTooling` pour les tests** (§7.3) : Robolectric 4.17
   n'a AUCUNE shadow de `LocalServerSocket`/`LocalSocket` (vérifié) — la
   logique (handshake, façade, corrélation, diffusion) est testée via
   `SessionFactice`, la colle socket reste une couche mince non couverte
   (filtres kover documentés dans build.gradle.kts, même précédent que la
   colle Termux/JNI de `core:terminal-runtime` — ADR 0035).

7. **Erreurs typées jusqu'à l'UI** : `ErrorResponse(code)` (protocole) est
   traduit en `AppError.Tooling(code: ToolingReason)` (domaine) — miroir
   exact des `ErrorCode` au vocabulaire du domaine. L'UI traduit le code,
   elle n'interprète jamais le texte brut de l'orchestrateur (§1.6) ;
   traductions ajoutées (accueil, diagnostics, assistant de création —
   le tooling ne participe pas à la génération : échec inattendu).

8. **États `EN_CONNEXION`/`ECHOUEE` pilotés par G4** : le port expose les
   quatre états de connexion, mais seuls `DECONNECTEE`/`CONNECTEE` sont
   posés par ce module — le daemon (lancement du process, tentatives
   bornées, health check) animera les transitions intermédiaires.

9. **Agrégation Hilt par `:app`** : `implementation(project(":tooling:client"))`
   dans app/build.gradle.kts (comme bootstrap/terminal-runtime) pour que le
   `@Binds` de `ModuleToolingClient` entre dans le graphe Dagger final. Le
   JAR orchestrateur reste un artefact de build lié par TÂCHE (G2) : la
   dépendance de module ne concerne que le code Kotlin.

- **Alternatives rejetées** : `MutableSharedFlow` global à rejeu nul pour la
  sortie (perd l'historique pré-abonnement — précisément le bug §5.2) ·
  namespace abstrait + traduction de chemin (le client JDK ne peut pas s'y
  connecter) · réflexion sur les API cachées d'Android pour le socket
  filesystem (`bind(FileDescriptor)` public suffit) · polling/fichier de
  découverte de port (l'écoute précède le lancement, §5.1) · consommer
  `tooling:api` depuis les features (règle §2.2 : interface du domaine
  uniquement).

- **Conséquences** : le client est opérationnel mais MUET tant que G4
  n'ouvre pas de session réelle (les opérations renvoient des échecs typés
  de connexion — comportement testé, jamais de blocage silencieux) ; la
  mémoire des sorties est bornée par la durée de la session (canals fermés
  à `fermerSession`) ; G5 branchera l'UI, G6 l'audit chaos.
