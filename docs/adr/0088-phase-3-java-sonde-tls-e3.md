# ADR 0088 — Phase 3 Java : installation du JDK, résolution unique de JAVA_HOME, sonde TLS (E3)

- **Statut** : accepté (étape E3 de la refonte ; implémente la phase
  `JAVA` du parcours dans le cadre de l'ADR 0087)
- **Contexte** : cahier des charges section 5, phase 3 — « Installe
  `openjdk-17` via `pkg`. N'affiche une autre version (ex. 21) que si le
  dépôt APT la fournit réellement (interroge-le : `apt-cache policy`),
  jamais en dur. Une seule règle de résolution de `JAVA_HOME`. Vérification :
  `java -version` et `javac -version` démarrent et la version majeure est
  analysée ; test TLS : une requête HTTPS Java vers `dl.google.com` (ou
  `keytool -list -cacerts`) réussit. Un truststore cassé est une cause
  classique d'échec de `sdkmanager` ; on la détecte ici, pas en phase 4. »

## Décision

### 1. Deux étapes : `openjdk` puis `verification-tls`

- **`openjdk`** (`EtapePaquetJdk`) : `pkg install -y <catalogue.jdkPackage>`
  — le paquet vient du catalogue (ADR 0086 : `openjdk-17`, seul JDK publié
  par le dépôt APT `codeide-packages`, vérifié en E1 contre son fichier
  `Packages`), **jamais d'autre source ni d'autre version** (§ 3.3).
- **`verification-tls`** (`EtapeVerificationTls`) : sonde HTTPS Java,
  compilée puis exécutée par le JDK fraîchement installé (§ 3 ci-dessous).
- La séquentialité du cadre (ADR 0087) garantit que la sonde ne démarre
  qu'après un JDK vérifié ; un échec d'installation n'atteint jamais le
  test TLS (test à l'appui).

### 2. Interrogation du dépôt APT journalisée, version jamais en dur

- Avant l'installation, `apt-cache policy <paquet>` est exécuté et sa
  sortie **journalisée** (bornée à 10 lignes) : ce que le dépôt fournit
  réellement est visible dans le journal d'installation — exigence
  « interroge-le » du cahier. Cette sortie **n'est jamais un verdict** :
  la décision d'installation vient du catalogue ; si `apt-cache`
  échoue, l'installation est tentée quand même et l'échec journalisé.
- La version **affichée** (`PhaseState.Succeeded.versions["jdk"]`) est
  lue sur la sortie réelle de `java -version` (bannière `version "17.0.20"`,
  régex partagée) : aucune version affichée sans exécution. Une éventuelle
  version 21 du dépôt n'apparaîtrait que si le catalogue exigeait son
  paquet — le changement serait alors délibéré et unique (ADR 0086).

### 3. Sonde TLS : une requête HTTPS **réelle**, pas `keytool`

Le cahier offre deux options (requête HTTPS vers `dl.google.com` **ou**
`keytool -list -cacerts`). Choix : **la requête HTTPS**, via une classe
`SondeTls.java` écrite dans `$PREFIX/tmp`, **compilée par `javac`** puis
**exécutée par `java`** du `JAVA_HOME` résolu. Justification :

- le contrôle couvre d'un seul geste le socle **exact** dont `sdkmanager`
  a besoin en phase 4 : démarrage de la JVM, compilation (`javac`),
  chargement du truststore (`HttpsURLConnection`), poignée de main TLS
  et réseau — `keytool -list -cacerts` ne prouve ni le réseau, ni la
  poignée de main, ni le chemin de bibliothèques ;
- `ca-certificates-java` n'est qu'un `Recommends` du paquet APT (ADR 0084,
  dissection du paquet) : un truststore absent ne casse **pas**
  `sdkmanager --version` mais les opérations réseau ultérieures — c'est
  précisément le mode détecté ici, avant la phase 4 ;
- la sonde écrit `TLS_OK <code>` sur stdout en cas de succès ; toute
  exception remonte sur stderr avec sa pile, **capturée intégralement**
  par le `CommandRunner` (contrat « aucune sortie jetée », ADR 0084).
- La cible `https://dl.google.com` est une **cible de sonde** fixée par
  le cahier (§ 5.3) — ce n'est pas une source d'artefact : les sources
  viennent exclusivement du manifeste (§ 12.7, interdit « URL de
  composant en dur »). Délais bornés dans la sonde (15 s connexion, 15 s
  lecture) et côté cadre (2 min `javac`, 1 min sonde).
- **Classification de l'échec** (§ 8, cause en langage clair) : la pile
  capturée est analysée — traces `PKIX`/`ValidatorException`/
  `SSLHandshakeException`/`cacerts` → `Jvm` (« truststore Java
  inutilisable ») ; traces `UnknownHostException`/`ConnectException`/
  `SocketTimeoutException` → `Reseau` (« injoignable ») ; sinon `Jvm`
  générique. La sortie complète reste attachée à l'erreur dans tous les cas.

### 4. Résolution unique de `JAVA_HOME`

- La règle existante (`LocalisationOutils.trouverJavaHome` : candidats
  `usr/lib/jvm/*` puis `usr/opt/jdk*|openjdk*`, marqueur `bin/java` **et**
  `bin/javac` exécutables — JDK complet, pas JRE — plus récent par
  comparaison de versions) est **conservée telle quelle** et réutilisée
  par la phase, l'ancienne et la nouvelle architecture partageant la
  même fonction pure, déjà testée (`LocalisationOutilsTest`).
- La vérification de l'étape `openjdk` : `JAVA_HOME` résolu, puis
  `java -version` (bannière sur **stderr**, lue comme telle) et
  `javac -version` exécutés, code 0 exigé, **majeure extraite** et
  comparée à `catalogue.jdkMajorVersion` (17) — un dépôt qui livrerait
  une autre majeure échoue avec un diagnostic explicite, pas en silence.
- « Installé = vérifié en l'exécutant » appliqué **immédiatement** dans
  `execute` : un paquet posé dont la JVM ne démarre pas (mode R6 de
  l'ADR 0084 : bibliothèque manquante, code 127, diagnostic sur stderr)
  échoue l'étape avec la sortie capturée de `java -version` — plus
  jamais de « SDK non fonctionnel » muet (test à l'appui, régression
  directe du constat E1).

### 5. Délais et reprise

- Installation : 10 min (le JDK et ses dépendances pèsent plusieurs
  centaines de Mio) ; contrôles de version : 30 s (aligné sur le cadre,
  ADR 0087) ; `javac` : 2 min ; sonde : 1 min.
- Reprise « verify-first » du cadre, inchangée : un JDK déjà installé et
  vérifié n'est **jamais réinstallé** (test : 0 appel `pkg install`),
  la sonde TLS reste exécutée à chaque reprise — c'est un contrôle, pas
  une installation (§ 3.2).
- Recensement : `versions["jdk"] = <version réelle de java -version>` —
  alimente le récapitulatif final et l'écran Environnement (E5).

## Conséquences

- La phase `JAVA` est livrée dans `FabriquePhasesParDefaut` : le
  parcours complet BOOTSTRAP → PACKAGE_TOOLS → JAVA est exécutable ;
  il s'arrête proprement avant `ANDROID_SDK` (non livrée avant E4,
  ADR 0087 § 1).
- 7 tests nouveaux (`PhaseJavaTest`) : parcours propre (installation
  unique + dépôt interrogé + TLS), reprise sans réinstallation, échec
  d'installation `Commande`, JVM muette `Jvm` avec sortie (R6),
  truststore cassé `Jvm`, réseau coupé `Reseau`, majeure divergente
  `Jvm`.
- **Non vérifié sur appareil** (règle 2 du cahier) : l'installation
  réelle du paquet `openjdk-17` aarch64 et la sonde TLS sur appareil —
  les reproductions E1 (REPRO 6 : JVM sans bibliothèque ; paquet
  `openjdk-17` disséqué avec ses `Depends`) servent de référence de
  comportement ; le scénario complet sur appareil neuf est consigné
  pour `docs/TESTS_MANUELS.md` en E6.
- Question ouverte pour le propriétaire (héritée E2, inchangée) : le
  dépôt APT publiera-t-il un jour `openjdk-21` ? Le catalogue est prêt
  (changement unique et délibéré, ADR 0086 § 2).
