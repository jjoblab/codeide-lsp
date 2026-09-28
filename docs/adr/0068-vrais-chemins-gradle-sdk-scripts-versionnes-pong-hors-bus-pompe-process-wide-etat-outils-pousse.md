# ADR 0068 — Vrais chemins Gradle/SDK, scripts versionnés, pong hors bus, pompe process-wide et état des outils poussé

Date : 2026-09-28 · Étape : v0.37.3 (retour utilisateur sur le tooling et l'installation)

## Contexte

Quatre retours d'appareil réel arrivés ensemble après la v0.37.2 :

1. **« Ce n'est pas le vrai chemin de gradle »** — la bannière d'état des
   outils affichait `/data/user/0/jo.codeide/files/usr/bin/gradle` (le script
   de découverte posé par l'app) alors que le Gradle RÉELLEMENT utilisé vit
   dans `/data/user/0/jo.codeide/files/home/.gradle/wrapper/dists/gradle-9.7.1/<empreinte>/gradle-9.7.1/`
   — posé là par l'orchestrateur du tooling (Tooling API 9.7.1) et par les
   builds des projets. `LocalisationOutils.trouverGradleHome` ne scannait que
   `$PREFIX/opt/gradle*` et le symlink `bin/gradle` : `gradleHome()` nul,
   `isGradleInstalled()` faux, et la commande `bin/gradle` du terminal
   cherchait le cache wrapper avec un glob à DEUX niveaux (`dists/*/*/`) alors
   que la disposition réelle en compte TROIS (`dists/gradle-<version>-bin/<empreinte>/gradle-<version>/`)
   — la découverte échouait TOUJOURS et tombait dans le message d'explication.
   Même erreur pour le SDK Android : aucun candidat sous le HOME du shell.

2. **« Ajoute la section d'installation de l'Android SDK au script, cmd
   ligne, etc. »** — rien n'existait : ni commande pour poser le SDK, ni
   candidat de localisation, ni affichage dans la bannière du profil.

3. **« Versionne le script de terminal pour ne pas être obligé de
   réinstaller complètement l'application »** — les scripts du terminal
   (profil `etc/codeide.sh`, commande `bin/gradle`) n'étaient écrits qu'à
   l'installation de BASE du bootstrap : toute correction exigeait une
   réinstallation complète du préfixe.

4. **« La console affiche souvent connexion avec l'orchestrateur perdue »**
   et **« la plupart des points de l'UI pour le tooling ne se mettent pas à
   jour »** — deux familles :
   - le `PongMessage` du serveur était publié dans le bus borné (8192) DERRIÈRE
     les `BuildOutput` : en plein build bavard, la contre-pression ensevelissait
     le pong, le bilan de santé du daemon (15 s) déclarait l'orchestrateur muet
     et TUAIT la connexion ;
   - côté client, la vidange des canaux de sortie vivait dans le
     `viewModelScope` de l'éditeur : fermer l'éditeur en plein build annulait
     les collecteurs, le canal borné du client (4096) se remplissait, la pompe
     interne bloquait, le socket n'était plus lu — les pongs ne remontaient
     plus, même verdict ;
   - les points d'UI (carte terminal du tiroir et de l'éditeur, bandeau de
     l'accueil, page terminal de l'onboarding, garde JDK) lisaient le
     `ToolchainLocator` en **instantané pull** à leur construction : une
     installation terminée pendant qu'un écran restait ouvert n'y apparaissait
     jamais, et la garde JDK payait un scan multi-emplacements sur le thread
     principal à chaque build.

## Décision

1. **Localisation réelle** (`LocalisationOutils`) : `trouverGradleHome`
   balaye AUSSI la distribution du cache wrapper
   (`CachesGradle.trouverDistribution($HOME/.gradle, null)` — la vraie maison
   de Gradle, découverte via le même code que le wrapper) ; `trouverAndroidHome`
   connaît les candidats du HOME (`home/android-sdk`, `home/.android-sdk`,
   `home/sdk`) APRÈS ceux du préfixe (priorité à un éventuel paquet APT futur).
2. **Commande `android-sdk`** (`EcrivainSdkAndroidCli`) : script shell posé à
   `$PREFIX/bin/android-sdk` — télécharge les cmdline-tools officiels sous le
   HOME du shell, installe `platforms;android-3x` + `platform-tools` +
   `build-tools` via `sdkmanager` (avec licences acceptées), puis des
   sous-commandes `statut` / `installer` / `desinstaller` ; le profil shell
   affiche l'état réel du SDK dans la bannière d'ouverture (`__codeide_android_info`).
3. **Scripts versionnés** (`VersionneurScriptsTerminal`) : un marqueur
   `$PREFIX/etc/codeide-scripts.version` retient la version du contenu POSÉ ;
   `BootstrapInstaller.refreshTerminalScripts()` (nouveau port, câblé au
   démarrage de l'application) compare au marqueur embarqué et réécrit profil
   + `gradle` + `android-sdk` SEULEMENT en cas d'écart — idempotent, hors
   installation en cours, silencieux sans bootstrap. Règle de contribution :
   toute évolution de script incrémente `VersionneurScriptsTerminal.VERSION`.
4. **Pong hors bus** (`MessageDispatcher`) : la réponse au ping est encodée et
   écrite DIRECTEMENT sur le socket (`SocketClient.envoyer` est `@Synchronized`
   : chaque frame reste atomique) — la seule frame qui ne doit JAMAIS attendre
   la contre-pression du bus. L'ordre relatif pong/événements peut s'inverser,
   sans conséquence (le pong ne porte aucune relation d'ordre).
5. **Pompe process-wide** (`PompeBuildTooling`, feature:editor) : la vidange
   des canaux de build (sortie, état, tâches) vit dans une portée interne du
   SINGLETON — plus dans le `viewModelScope`. Fermer l'espace en plein build
   n'annule plus rien : le canal du client se vide toujours, les pongs
   remontent, et le build quitté continue d'alimenter l'état process-wide
   (notification honnête, console rejouée au ré-attachement — ADR 0057
   étendu du détenteur d'état à la pompe). Une vidange par build exactement ;
   elle se conclut d'elle-même à la fermeture des canaux (fin de build,
   ADR 0041/0065).
6. **État des outils POUSSÉ** (nouveau port `ObserveToolchainStateUseCase` +
   `EtatOutilsTerminal`) : l'implémentation `ObservateurOutilsTerminal`
   (`core:bootstrap`) combine les transitions de l'installateur et un
   ballotage périodique léger (2 s, quelques stat de fichiers — les outils
   peuvent apparaître SANS l'installateur : distribution Gradle téléchargée
   par l'orchestrateur, SDK posé depuis le terminal, `aapt2` déployé à la
   première build), `distinctUntilChanged`, le tout sur `dispatchers.io`.
   Les quatre consommateurs s'y abonnent : carte terminal du tiroir
   (`TerminalTiroirViewModel`), bandeau + bouton terminal de l'accueil
   (`HomeViewModel` — le pull disparaît du `combine`), page terminal de
   l'onboarding (`OnboardingViewModel` — le flot remplace l'instantané du
   `init`), carte terminal et garde JDK de l'éditeur (`EditorViewModel` —
   `jdkAbsent()` lit le cache poussé : plus d'I/S disque sur le thread
   principal à chaque build).

## Conséquences

- La bannière du profil, la commande `gradle` et l'état UI racontent la même
  histoire que le tooling : le Gradle du cache wrapper à trois niveaux et le
  SDK du HOME sont trouvés du premier coup.
- Un appareil v0.36.x/v0.37.x monté en v0.37.3 reçoit les scripts corrigés au
  démarrage suivante SANS réinstallation (marqueur absent ou < 2 → réécriture).
- « Connexion avec l'orchestrateur perdue » en plein build exige désormais la
  conjonction de DEUX pannes réelles (bus ET socket saturés côté serveur,
  pompe ET lecture bloquées côté client) au lieu d'un seul builds bavard ou
  d'un simple changement d'écran.
- Les canaux du client restent bornés (4096) : la pompe process-wide est la
  garantie structurelle qu'ils se vident — le terminal de secours reste la
  clôture des canaux à la fin du build.
- Le ballotage de l'observateur ne tourne QUE pendant qu'au moins un écran
  collecte (`WhileSubscribed` des `stateIn` des ViewModels) : négligeable en
  batterie, nul en fond.
- `FakeObserveToolchainState` (core:testing) double le port pour les tests de
  ViewModels ; `ObservateurOutilsTerminalTest` éprouve l'implémentation sur
  le vrai `filesDir` Robolectric (horloge virtuelle : jamais
  `advanceUntilIdle` — le ticker est infini par construction).
