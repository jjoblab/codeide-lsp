# ADR 0045 — targetSdk 28 : exécuter les binaires du bootstrap (retour d'appareil réel)

- **Statut** : accepté (v0.31.1, correction après retour utilisateur)
- **Contexte** : premier retour de terrain sur l'app v0.29.0 (rapport de
  plantage 7842f130, moto g06, Android 15 / API 35, fr-HT). L'installation
  du bootstrap téléchargeait et extrayait l'archive, puis échouait en
  « erreur inattendue » ; en pressant retour, l'assistant affichait pourtant
  « bootstrap déjà installé ». ADR 0035 avait laissé la question ouverte :
  « l'exécution des binaires du stockage privé n'est pas testée
  empiriquement ici… l'ADR dédiée est planifiée avec les tests
  d'installation réels (ROADMAP, étape T7) » — le moment de vérité est
  arrivé.

- **Diagnostic** (convergent, trois causes) :

1. **W^X — le noyau refuse l'exécution**. Depuis Android 10, une app dont
   `targetSdk >= 29` ne peut plus `exec()` un binaire écrit dans ses
   propres données (`untrusted_app_29+` ne reçoit pas l'autorisation
   SELinux `execute` sur `app_data_file`). CodeIDE ciblait 37 : le second
   stage (`$PREFIX/bin/bash` extrait dans `filesDir/usr`) était refusé
   (EACCES) — `ProcessBuilder.start()` lève une `IOException`, attrapée
   par le fourre-tout du pipeline et publiée `AppError.Unknown`, d'où le
   message « erreur inattendue » sans indice. Termux frappe exactement ce
   mur : c'est pourquoi il cible 28 à ce jour.

2. **Marqueur d'installation mensonger**. La bascule atomique pose le
   préfixe AVANT le second stage ; or `bootstrapInstalle` testait
   uniquement « un shell exécutable sous `bin/` » — vrai dès la bascule.
   Une installation échouée au second stage laissait donc l'assistant
   affirmer « déjà installé », l'écran d'installation montrer l'échec,
   et le terminal ne fonctionnait jamais. Le JDK restant absent, le
   tooling Gradle loggait « JDK introuvable ».

3. **Plantage de l'écran Terminal (indépendant)** : `ClavierEtenduView`
   déclarait sa liste de touches APRÈS le bloc `init` qui l'itère —
   Kotlin initialise dans l'ordre de déclaration, la liste valait `null`
   à la construction, `NullPointerException` à l'inflation du layout
   (corrigé au même lot, avec test de régression gonflant le vrai
   `activity_terminal.xml`).

- **Décision** :

1. **`targetSdk` = 28** (source unique : `gradle/libs.versions.toml`,
   lu par le convention plugin d'application). `compileSdk` reste 37 :
   le code compile contre la dernière API, l'exécution suit les règles
   de la cible. Précédent Termux, même raison. L'app est chargée par
   **side-loading** (archive + APK debug) : l'exigence Play Store
   (cible ≥ 34) ne s'applique pas — l'exception lint ciblée
   `ExpiringTargetSdkVersion` est documentée dans le convention plugin
   (règle 9).
   Comportements résultants, tous favorables ici : notifications
   auto-accordées (pas de permission d'exécution `POST_NOTIFICATIONS`
   à demander), pas de stockage cloisonné forcé (l'app n'utilise QUE
   SAF, identique), pas d'edge-to-edge forcé (l'app le fait déjà
   explicitement), pas de type de service en premier plan imposé (le
   manifeste déclare déjà `specialUse`). Installation possible sur
   Android 15 (cible ≥ 24 exigée) avec l'avertissement système
   « conçue pour une ancienne version d'Android » — assumé.

2. **L'échec de lancement du second stage est typé**
   (`InstallateurBootstrap.executerSecondStage`) : `IOException` →
   `Bootstrap(PermissionRefusee, "second stage non exécutable : …")`,
   exactement comme `ConfigurateurApt` le fait pour `apt` — plus de
   fourre-tout muet, le message éclaire le diagnostic.

3. **Marqueur d'installation terminée** :
   `DispositionsBootstrap.marqueurInstallation` = `$PREFIX/.codeide-installation-terminee`,
   déposé par l'installateur juste avant `Terminee` — le pipeline est
   allé au bout. `bootstrapInstalle` exige désormais le shell ET le
   marqueur (« extrait » n'est plus « installé »). Le marqueur vit sous
   le préfixe : une reprise détruit celui-ci et repart de zéro, le
   marqueur disparaît avec lui — cohérent avec le contrat de reprise.

- **Alternatives rejetées** : garder 37 et exécuter via le linker
  (`linker64 $PREFIX/bin/bash` — contour exploré par la communauté,
  fragile : argv[0], `/proc/self/exe` et hypothèses PIE cassées sur un
  environnement entier ; pas au niveau « distribution ») · `/data/local/tmp`
  (non accessible en écriture à une app) · `targetSdk` 29-33 quelconque
  (le mur W^X est à 29, pas plus haut) · ne corriger que le message
  d'erreur (honnête mais l'app resterait inutilisable).

- **Conséquences** : le terminal intégré, l'installation du bootstrap,
  le daemon du tooling (JDK du dépôt) et aapt2 redeviennent exécutables
  sur tout Android 10+ — c'était la condition d'existence du produit.
  L'ADR 0035 ferme sa question ouverte ; le ROADMAP note la décision.
  La revue mémoire LeakCanary (autre point T7 différé) reste ouverte.
  Si un jour Play Store devient un objectif : re-réévaluer avec les
  mécanismes d'exécution alors disponibles (le mur W^X est policy
  Android, pas permanent).
