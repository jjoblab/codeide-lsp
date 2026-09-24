# ADR 0046 — Sessions Termux sur le thread principal, installation sous surveillance et page permissions de l'assistant

- **Statut** : accepté (v0.31.2, correction après retour utilisateur)
- **Contexte** : deuxième retour de terrain sur l'app v0.31.1 (rapport de
  plantage 511e1c7f, moto g06, Android 15 / API 35, fr-HT). Trois
  symptômes : (1) plantage de `TerminalActivity` à la création de la
  première session — `RuntimeException: Can't create handler inside
  thread DefaultDispatcher-worker-1 that has not called
  Looper.prepare()` ; (2) écran d'installation muet — un libellé d'étape
  et une barre indéterminée, rien de ce qui se fait réellement, et
  l'échec « la configuration des paquets a échoué » sans le moindre
  indice (le message couvre à la fois `EchecSecondStage` et `EchecApt`)
  ; (3) aucune page de l'assistant ne traite de la permission de
  notification, et l'utilisateur s'interroge sur la nécessité de
  permissions de stockage (« read/write ou manager storage ») malgré SAF.

- **Diagnostic** :

1. **`TerminalSession` (Termux terminal-emulator v0.118.3) construit un
   `Handler` dans son constructeur** — `MainThreadHandler` sans Looper
   explicite, qui exige `Looper.myLooper() != null`, donc le thread
   principal. `RegistreSessionsTermux.createSession` exécutait tout le
   registre sur `Dispatchers.Default` : l'appel passait par
   `FabriqueCoquillesTermux.creer` depuis un worker sans Looper → crash
   déterministe à l'ouverture du terminal (Termux lui-même crée ses
   sessions sur l'UI). Complément : aucun module du graphe ne déclarait
   `kotlinx-coroutines-android` — `DispatcherProvider.main` (qui vaut
   `Dispatchers.Main`) n'avait jamais été consommé en production ; le
   dispatcher principal Android se charge par ServiceLoader, il faut
   l'artefact dans le module qui l'utilise.

2. **L'écran d'installation jetait la sortie des sous-processus**.
   `SupervisionProcessus` drainait stdout (obligatoire : un tuyau non lu
   bloque le processus) mais **sans rien en faire**, et ne conservait
   que 5 lignes de stderr pour les journaux internes — jamais affichées.
   L'utilisateur voyait « la configuration des paquets a échoué » sans
   savoir si le second stage, `apt update` ou `apt install` avait
   failli, ni pourquoi. L'inspection de l'archive réelle (téléchargée,
   empreinte SHA-256 vérifiée) établit que le second stage de la
   publication actuelle no-oppe (`TERMUX_PACKAGE_MANAGER` vide) et que
   la base dpkg embarquée est cohérente (149 paquets « installed ») :
   l'échec vient donc d'`apt` lui-même (réseau, DNS, dépôt) — mais
   seule sa sortie peut le prouver sur l'appareil.

3. **Permissions** : le service foreground du terminal
   (`TerminalService`, `specialUse`) affiche une notification ; sous
   Android 13+ la visibilité dépend de `POST_NOTIFICATIONS`. Pour une
   cible ≤ 32 (targetSdk 28, ADR 0045), le système accorde la
   permission par défaut et montre lui-même le dialogue à la première
   activité après création du canal — le dialogue standard
   `requestPermissions` n'est PAS la voie garantie. En revanche,
   **aucune permission de stockage n'est nécessaire** : le bootstrap
   vit dans `filesDir` (stockage privé, ADR 0034) et le dossier de
   travail passe par SAF (ADR 0003) ; `READ/WRITE_EXTERNAL_STORAGE`
   (stockage partagé, jamais touché) comme `MANAGE_EXTERNAL_STORAGE`
   (rejeté par Play, inutile ici) resteraient des demandes
   disproportionnées.

- **Décision** :

1. **La création des coquilles bascule sur le dispatcher principal** :
   dans `RegistreSessionsTermux.createSession`, l'appel à
   `fabrique.creer(…)` est enveloppé d'un `withContext(dispatchers.main)`
   (le verrou coroutine est conservé à travers le saut). Le fork/exec du
   pty reste bref — même comportement que Termux. `core:terminal-runtime`
   déclare `kotlinx-coroutines-android` (artefact canonique du
   dispatcher principal, chargé par ServiceLoader). Test de régression :
   dispatcher instrumenté comptant les dispatchs → le saut vers `main`
   est prouvé pendant la création.

2. **L'installation passe sous surveillance** : le port
   `BootstrapInstaller` expose un flux `journal` (`StateFlow<List<String>>`,
   borné à 200 lignes par l'implémentation) alimenté (a) par une ligne
   par transition d'étape (anti-rejeu des tics de progression — le
   téléchargement émet toutes les 512 Kio) et (b) par **chaque ligne
   stdout et stderr des sous-processus**, acheminée au fil de l'eau par
   un consommateur passé à `SupervisionProcessus.attendre` (second
   stage, `apt update`, `apt install`). L'écran d'installation affiche
   une checklist des neuf étapes (terminée / en cours / en attente), le
   compteur de l'étape courante (octets, fichiers, paquet), une barre
   déterminée dès que le serveur annonce la taille, et la console de
   journal (auto-défilement) — conservée à l'échec. L'échec affiche le
   message actionnable ET des détails techniques dépliables (code de
   sortie + dernières lignes d'erreur). L'installateur consigne par
   ailleurs étapes et échecs typés dans l'AppLogger (tag
   `Installateur`) — les rapports de plantage futurs porteront la
   raison, règle 15 respectée (identifiants, pas de chemins
   utilisateurs ; la sortie d'apt est de l'affichage, pas du journal
   applicatif).

3. **Page « Notifications et stockage » dans l'assistant** (entre
   Terminal et Apparence, passable comme tout l'assistant) : elle
   explique les notifications honnêtes du terminal, propose la demande
   directe `POST_NOTIFICATIONS` sous Android 13+ (repli : lien vers les
   réglages de l'application) et **relit l'état réel**
   (`areNotificationsEnabled`) au retour sur la page — c'est lui qui
   fait foi, pas la mémoire du dialogue. La page documente l'absence
   volontaire de permission de stockage (SAF + stockage privé).
   Aucune nouvelle permission au manifeste : `POST_NOTIFICATIONS`,
   `FOREGROUND_SERVICE(_SPECIAL_USE)` et `INTERNET` existaient déjà
   (ADR 0034) — la page ne fait que rendre le contrat visible.

- **Alternatives rejetées** :

- *Patcher `TerminalSession` pour prendre le `Looper.getMainLooper()`* :
  modifier l'artefact JitPack maintenu en amont (ou le forker) pour un
  problème d'intégration — le saut de contexte de notre côté est
  trois lignes et éprouvé par Termux lui-même.
- *Créer les sessions sur le thread principal sans coroutines* (handler
  posté) : perd l'ordonnancement du registre (verrou coroutine,
  publication atomique) — le `withContext` préserve la sémantique.
- *Un canal dédié « progression apt » parsé* (pourcentages des
  téléchargements apt) : le parsage de la sortie apt est fragile et
  apporte moins que la sortie brute elle-même ; la console sert aussi
  au diagnostic, pas seulement à la progression.
- *Demander `READ/WRITE_EXTERNAL_STORAGE` « par précaution »* : demandes
  disproportionnées au besoin réel (données strictement privées + SAF),
  rétrogrades (attributs maxSdkVersion) et contraires aux ADR 0003/
  0034 ; le bon geste est l'explication dans l'assistant.
- *Bump targetSdk 33 pour le dialogue standard* : redimensionnerait W^X
  (ADR 0045) — interdit ; le repli par les réglages couvre le cas
  restant.

- **Conséquences** : le registre tient un saut de contexte par création
  de session (coût négligeable) ; l'écran d'installation devient
  diagnostiqueur de première intention (la sortie apt de l'appareil
  tranchera la cause exacte du prochain « la configuration des paquets
  a échoué ») ; l'assistant compte sept pages (progression, ordre et
  bornes recalés, tests inclus) ; `FakeBootstrapInstaller` double le
  journal pour les tests de ViewModel. La re-livraison de l'archive
  bootstrap côté `codeide-packages` avec `TERMUX_PACKAGE_MANAGER=apt`
  reste souhaitable en amont (le second stage y deviendrait réel) —
  suivi côté dépôt de paquets, hors périmètre de cette ADR.
