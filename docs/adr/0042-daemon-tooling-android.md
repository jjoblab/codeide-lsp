# ADR 0042 — Daemon du tooling Gradle (G4) : tooling:daemon

- **Statut** : accepté (étape G4, v0.29.0)
- **Contexte** : prompt compagnon Tooling, section 5.4 — après le client
  muet de G3, le composant qui FAIT vivre l'orchestrateur : déployer le JAR,
  ouvrir l'écoute, lancer le process JVM, le surveiller (santé ping/pong,
  sorties) et le relancer borné. Premier bout-en-bout réel (§7.4) : le
  daemon lance le VRAI orchestrateur et exécute un VRAI build Gradle.

- **Décisions** :

1. **Module `tooling:daemon` (Android + Hilt), allow-list déjà gelée en
   G1** : `:tooling:protocol`, `:core:domain`, `:tooling:client`. Le daemon
   ne redéfinit RIEN de ce qui existe : le lancement passe par le port
   `NativeProcessLauncher` (impl. `core:bootstrap`, environnement canonique
   — « jamais redéfini »), l'écoute et la session sont celles de
   `tool:client` (enveloppées), le JAR vient des assets (artefat de build,
   ADR 0040).

2. **Coutures publiques minimalistes dans tooling:client** :
   `GradleSocketServer`, `SessionTooling` et `EchecHandshakeClient`
   deviennent publics (enveloppes pour le daemon), `GradleApiImpl` expose
   `ouvrirSession`/`fermerSession` (existaient), les marqueurs
   `marquerEnConnexion`/`marquerEchouee` (ADR 0041 décision 8 : G3 n'avait
   posé que DECONNECTEE/CONNECTEE) et le repère `dernierPongMs` — les pongs
   transitent dans le flux d'événements pompé par la façade, c'est elle
   qui tient l'horodatage à jour. L'API du domaine reste inchangée.

3. **`HoteSocketTooling` : couture de l'écoute** — production :
   `HoteSocketAndroid` (délégation pure au `GradleSocketServer` de G3, la
   colle `LocalSocket` reste concentrée là-bas) ; tests : hôte JVM sur
   VRAI socket Unix (`ServerSocketChannel`) ou hôte factice. Même politique
   que la couture `SessionTooling` de G3 (Robolectric n'a aucune shadow
   `LocalSocket`).

4. **`JarDeployer` à marqueur de version = empreinte SHA-256 de la
   source** : copie atomique (`.tmp` + renommage) des assets vers
   `filesDir/tooling/`, recopie SEULEMENT si l'empreinte du marqueur
   diffère — un redémarrage d'app sur un JAR inchangé ne recopie rien (le
   JAR fait 7,6 Mo). Le hash se calcule sur le flux (aucune lecture
   superflue en mémoire), l'échec de déploiement (asset absent, disque
   plein) est DÉFINITIF : réessayer identique est vain.

5. **Health check ping/pong (§5.4, constantes G1)** : un `PingMessage`
   toutes les 5 s (`HEARTBEAT_INTERVAL_MS`) sur la session ouverte ;
   `GradleApiImpl.dernierPongMs` (rafraîchi par le pompe, initialisé à
   l'ouverture) vieillissant au-delà de 15 s (`HEARTBEAT_TIMEOUT_MS`) →
   `kill(force)` → relance par la boucle. L'échec d'envoi du ping (session
   morte) tuite pareil.

6. **Relances bornées et échecs définitifs typés** : au plus
   `MAX_RECONNECT_ATTEMPTS` (5) avec repli exponentiel borné (1 s → 10 s) ;
   épuisement → `ECHOUEE` (échec définitif jusqu'à un nouveau `demarrer`).
   DÉFINITIFS sans relance : `EchecHandshakeClient` (version/secret),
   code de sortie 2 (arguments invalides — NOTRE bug de ligne de
   commande), JAR indisponible. **JDK absent ≠ échec** : retour immédiat à
   `DECONNECTEE` sans aucun lancement (le bootstrap peut s'installer
   ensuite — l'app re-déclenche le daemon à `Terminee`), jamais une boucle
   de tentatives vouée à l'échec.

7. **Secret frais par tentative** (`SecureRandom`, 32 octets URL-safe),
   transmis au process PAR argument de commande — jamais écrit, jamais
   journalisé (§4.4) ; l'assertion du bout-en-bout vérifie que le process
   présente exactement le secret qui lui a été passé.

8. **Stderr du process = son journal** (règle 14, ADR 0040) : stderr →
   `AppLogger.w("gradle-server")`, stdout → `AppLogger.i` — l'onglet
   Journal de l'éditeur et l'écran Diagnostic voient l'orchestrateur comme
   n'importe quel producteur applicatif. Nettoyage SYNCHRONE dans les
   terminaisons (leçon T2 : `kill`/`fermer`/`fermerSession` ne suspendent
   jamais — l'annulation de la surveillance ne laisse rien vivant).

9. **`java -Xmx256m -jar`** : le process orchestrateur a le tas borné —
   il ne FAIT pas les builds (daemon Gradle séparé), il orchestre la
   Tooling API et route le protocole ; 256 Mio couvrent, et un process
   invasif sur un téléphone reste inacceptable. La ligne de commande est
   une couture (`fabriqueCommande`) : le bout-en-bout §7.4 relance le VRAI
   `ServerMain` par classpath (l'artefact shadowJar n'existe pas en JVM de
   test) — production inchangée.

10. **Bout-en-bout réel (§7.4) en test JVM** : le daemon lance un VRAI
    sous-processus `java` (ServerMain complet) sur un VRAI socket Unix JDK,
    handshake au secret vérifié, pong réel, VRAI build Gradle sur la
    fixture `minimal-java` (sortie ligne à ligne, état REUSSI), arrêt
    propre. Exception de dépendance : `:tooling:server` n'entre dans
    `:tooling:daemon` qu'en configuration de TEST (extension ciblée de
    `ModuleRulesPlugin`, miroir du régime de `core:testing`) — en
    production le daemon ne voit du serveur que le JAR déployé.

11. **Démarrage : processus principal de l'app** (`CodeIdeApplication`) —
    `demarrer` à la création + re-déclenchement idempotent quand
    l'installation du bootstrap aboutit (`BootstrapInstaller.etat` →
    `Terminee`). La mort de l'app ferme le socket → l'orchestrateur voit
    l'EOF et s'arrête SEUL (code 0) : aucun process orphelin, aucun arrêt
    explicite à gérer au cycle de vie Android.

- **Alternatives rejetées** : lancement in-process de l'orchestrateur
  (même JVM que l'app : un crash du tooling emporterait l'IDE, et la
  Tooling API exige un JVM dédié) · polling de découverte du process
  (l'écoute précède le lancement, §5.1 — aucun fichier, aucun polling) ·
  secret persisté pour « éviter » la négociation (§4.4 : frais à chaque
  démarrage) · arrêt explicite au `onTerminate` (jamais appelé de façon
  fiable — l'EOF du socket fait le travail) · health check par état de
  connexion seul (une session TCP vivante ne prouve pas que l'orchestrateur
  POMPÉ ses événements — le ping/pong prouve la boucle complète).

- **Découverte d'ingénierie (leçon durable)** : le classpath de
  COMPILATION des tests unitaires Android porte android.jar — pour les
  classes java.* couvertes par les builtins Kotlin, c'est la version JDK 8
  qui gagne : `Process.onExit()` (JDK 9) et
  `ServerSocketChannel.open(ProtocolFamily)` (JDK 15) ne RÉSOLVENT PAS à
  la compilation alors qu'elles existent au runtime. Contournements
  éprouvés : réflexion ciblée (même précédent que le `pid` de
  `ProcessusGere`) et sondage `isAlive` (miroir du port production). Les
  API java.* récentes des tests vivent dans les modules purs (G2) ou par
  ces coutures.

- **Conséquences** : le client n'est plus muet — l'état de connexion
  reflète le cycle de vie réel (EN_CONNEXION/CONNECTEE/ECHOUEE animés) ;
  G5 branche l'UI (onglets Sortie/Problèmes, actions
  Synchroniser/Exécuter), G6 l'audit chaos ; le JAR déployé vit sous
  `filesDir/tooling` (rétention négligeable, redéployé au changement) ;
  la CI exécute le bout-en-bout sur socket Unix réel.
