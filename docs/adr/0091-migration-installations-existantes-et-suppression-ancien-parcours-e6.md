# ADR 0091 — Migration des installations existantes et suppression de l'ancien parcours (E6)

- **Statut** : accepté (étape E6 de la refonte — dernière étape ; clôt la
  phase R « Refonte du parcours d'installation » E1-E6)
- **Contexte** : ADR 0085 § 6 (« Migration et adoption — détaillées en
  E6 ») et ROADMAP E6 : « migration des installations existantes,
  suppression de l'ancien code (`EcrivainSdkAndroidCli`,
  `EcrivainCodeideEnvCli`, `ConfigurationEnvTermux`, `Aapt2Deployeur`…),
  ADR 0082/0083 marqués remplacés, documentation ». Les étapes E2-E5 ont
  livré le parcours complet (cadre, phases 1-4, interface) ; l'ancien
  pipeline cohabitait encore dans le binaire — deux orchestrateurs, deux
  états, deux journaux.

## Décision

### 1. Adoption des installations existantes — l'exécution tranche, jamais le fichier

Un appareil ayant vécu l'ancien parcours (≤ v0.54.0) porte des artefacts
réels mais **aucun `install-state.json`** : préfixe complet avec marqueur
`.codeide-installation-terminee`, JDK `pkg`-installé, composants du SDK
posés par la commande `android-sdk` sous `home/android-sdk` (même racine
que le plan du manifeste v2 — les `installPath` coïncident), licences
écrites par `sdkmanager`. Le principe « installé = vérifié en l'exécutant »
(ADR 0085) interdit d'adopter ces artefacts sur leur seule présence : le
premier `run()` du parcours les adopte **par la vérification**, jamais en
les déduisant du disque.

Deux règles d'adoption, aux endroits exacts où l'ancien flux divergeait :

- **Composants du SDK** (`controleComposant`) : un composant présent sous
  son `installPath` **sans quadruplet persisté** — la signature exacte
  d'une installation antérieure au parcours — n'est plus réparé d'office.
  Son exécution décide : le `verify` du manifeste passe → **adopté**, le
  quadruplet du plan est reconstruit en fin de phase
  (`composantsInstalles` → `install-state.json`), **zéro
  retéléchargement** ; l'exécution échoue → réparation de CE composant
  seul, exactement comme un quadruplet divergent (§ 12.4, inchangé —
  l'adoption ne concerne que l'absence de quadruplet, jamais la
  divergence).
- **Archive du bootstrap** (`EtapeTelechargement.verify`) : un préfixe
  déjà basculé (shell + second stage en place) dispense du
  téléchargement — l'archive n'est qu'un moyen de créer le préfixe,
  jamais une fin. L'ancien flux n'écrivait pas le cache SHA-256 du
  nouveau gestionnaire : exiger le fichier d'archive aurait retéléchargé
  ~80 Mio pour écraser un préfixe fonctionnel. La réalité du préfixe est
  prouvée en aval par exécution réelle (second stage, `apt`, `pkg`,
  marqueur).

Les phases 1 à 3 n'exigent rien de plus : la reprise « verify-first » de
l'ADR 0087 vérifie chaque étape par exécution, indépendamment de tout
état persisté — un ancien préfixe sain saute le téléchargement,
l'extraction, la bascule ; le second stage et la configuration APT
(idempotents) sont rejoués ; `pkg update` rafraîchit l'index (métadonnées,
pas un artefact). La licence du SDK (§ 12.5) n'est JAMAIS migrée : le
consentement explicite est un acte d'utilisateur, l'adoption d'une
installation ne vaut pas acceptation — la phase 4 déjà vérifiée n'en a
pas besoin, une réparation future la redemandera.

**Trois scénarios testés** (ADR 0085 § 6, monde simulé de
`PhaseBootstrapTest`/`PhaseAndroidSdkTest`) :

| Scénario | Attente | Test |
|---|---|---|
| Appareil ancien complet | 0 requête d'archive, 0 téléchargement de composant, quadruplets reconstruits | `un appareil ancien complet est adopté sans retélécharger…` (×2 : bootstrap et SDK) |
| Appareil neuf | parcours propre inchangé (4 téléchargements) | `le parcours propre installe chaque composant…`, `la phase complète s'exécute depuis un appareil neuf…` |
| À moitié installé | seuls les composants présents et vérifiés sont adoptés ; le fautif éventuel est réparé seul | `un appareil à moitié installé n adopte que les composants présents`, `un composant ancien dont l exécution échoue est réparé seul…` |

**Non vérifié sur appareil** (règle 2 du cahier) : l'adoption réelle d'un
appareil ≤ v0.54 (notamment : les `verify` du manifeste v2 passent-ils sur
les binaires posés par le manifeste v1 de `codeide-tools` — même layout,
mêmes versions 35.0.2). Scénarios consignés dans `docs/TESTS_MANUELS.md`
(E91-E95) ; toute divergence sera signalée au propriétaire (protocole
§ 12), aucun contournement silencieux.

**Limite assumée** : la réparation d'un préfixe ancien profondément
corrompu (apt cassé, fichiers système manquants) reste **superficielle**
— verify-first saute téléchargement/extraction/bascule tant que le
préfixe existe, le second stage et la configuration APT sont rejoués, et
un échec persistant est rapporté avec sa sortie réelle. Ce comportement
est IDENTIQUE à celui d'un appareil du nouveau parcours dont le cache
existe toujours : il n'y a pas de régression, et la refonte complète
(reste du préfixe par réinstallation) reste possible en effaçant les
données de l'application.

### 2. Suppression de l'ancien code — la liste close

**`core:bootstrap`** : `InstallateurBootstrap` (ancien orchestrateur),
`TelechargeurBootstrap`, `ExtracteurBootstrap.extraire` en flux
d'étapes (devient `suspend fun` — le port `ArchiveExtractor` du cadre ne
consomme aucune granularité intermédiaire), `EcrivainSdkAndroidCli`,
`EcrivainCodeideEnvCli`, `EcrivainGradleCli`, `EcrivainProfilShell`,
`Aapt2Deployeur`, `VersionneurScriptsTerminal` — et leurs tests.
**Conservés** (partagés avec le nouveau parcours) : `ExtracteurBootstrap`
(algorithme), `ConfigurateurApt`, `EspaceDisque`, `CapaciteArchitecture`,
`EchecBootstrap` + `AppError.Bootstrap` (traduits à la frontière par
`ErreursInstallation`, ADR 0087 § 7), `LocalisationOutils` (son
héritage `aapt2` de `$PREFIX/bin` documente les installations anciennes),
`MarqueursOutils`, `CachesGradle`, `ToolchainBootstrap`,
`EnvironnementProcessus`, `SupervisionProcessus`, `OperationsSysteme`.

**`core:domain`** : ports `BootstrapInstaller` et `BootstrapAssetsSource`
(son unique consommateur était `Aapt2Deployeur`) retirés —
`EnvironmentSetupOrchestrator` (ADR 0085) est l'unique source de vérité ;
port `ConfigurationEnvTerminal` retiré (ADR 0083 remplacée).
**`core:model`** : `EtatInstallationBootstrap`, `EtapeInstallation`,
`OutilResume` retirés (l'état du parcours vit dans `core:domain`).
**`core:terminal-runtime`** : `ConfigurationEnvTermux` retirée — le
terminal n'orchestre plus la configuration par frappe de pty.
**`core:testing`** : `FakeBootstrapInstaller`, `FakeConfigurationEnvTerminal`
retirés ; les consommateurs sont rebranchés sur
`FakeEnvironmentSetupOrchestrator`.
**`feature:install`** : ancien écran `InstallFragment` +
`InstallViewModel` + `ClientTerminalMini`, ses layouts
(`fragment_install`, `rangee_etape_install`, `rangee_outil_install`),
26+19 clés de ressources orphelines et les dépendances terminal-view
(ADR 0083 n'a plus de raison d'être dans ce module).
**`app`** : `AssetsBootstrapSource` + `BootstrapAssetsModule`.

### 3. Rebranchements

- **`ObservateurOutilsTerminal`** (port `ObserveToolchainStateUseCase`,
  consommé par l'accueil, l'onboarding, l'éditeur, le tiroir du terminal) :
  le stimulus « transitions de l'installateur » devient l'état de
  l'orchestrateur du parcours — chaque changement de phase redéclenche le
  scan disque ; le ballotage périodique (2 s) reste pour les outils posés
  hors parcours (distribution Gradle du tooling).
- **Bandeau terminal de l'accueil** (`HomeViewModel`) : « bootstrap
  installé » = phase `BOOTSTRAP` vérifiée (`Succeeded`/`Degraded`) OU
  marqueur disque ; « installation en cours » = `running != null`.
- **`CodeIdeApplication`** : l'observateur « daemon (re)part quand
  l'installation aboutit » est retiré — le détecteur d'empreinte de la
  chaîne d'outils (E4, ADR 0089 § 6) couvre désormais ce cas : le
  localisateur scanne le disque, l'empreinte change dès que `java`/`javac`
  existent, le daemon Gradle (re)part.
- **`refreshTerminalScripts()` retiré** (v0.37.3) : le versionnage des
  scripts (`etc/codeide.sh`, `bin/gradle`, `bin/android-sdk`,
  `bin/codeide-env`) n'a plus d'objet — l'application ne pose plus ces
  scripts, l'environnement des sessions vient de
  `ProcessEnvironmentProvider` (`EnvironnementProcessus` : JAVA_HOME,
  ANDROID_HOME, PATH, GRADLE_USER_HOME injectés par processus). Sur un
  appareil ancien, les scripts posés restent sur le disque et continuent
  de fonctionner tels quels — ils ne sont simplement plus maintenus par
  l'application (rupture assumée et documentée au CHANGELOG).

## Options écartées

- **Vérification complète au démarrage pour « adopter d'office »** : la
  vérification légère exécute `pkg update` (réseau, 4 tentatives à délai
  croissant) et la sonde TLS — inacceptable en tâche de fond au lancement
  de l'app. L'adoption se fait au premier `run()` explicite, derrière
  l'écran de progression et son service de premier plan.
- **Écrire un `install-state.json` « déclaré » depuis les marqueurs
  anciens** : violerait « installé = vérifié en l'exécutant » — un marqueur
  ne prouve rien (rapport d'appareil 7842f130, v0.29.0 : le marqueur
  d'extraction mentait après un échec du second stage).
- **Prolonger `BootstrapInstaller`** : déjà écarté par l'ADR 0085 (état
  sans distinction vérifié/non vérifié, journal non attaché aux échecs,
  garde « une seule installation » incompatible de la reprise granulaire).

## Conséquences

- Un seul orchestrateur, un seul état persisté, un seul journal : la
  charge cognitive et la surface de test baissent d'un tiers dans
  `core:bootstrap` (~2 900 lignes retirées, adaptation comprise).
- L'invariant « un composant = une version résolue = un téléchargement »
  (ADR 0085) s'étend aux installations anciennes : l'adoption ne
  télécharge RIEN, la réparation ne retélécharge QUE le fautif — testé
  sur les trois scénarios § 1.
- Les ADR 0082 et 0083 sont marquées **remplacées** (par 0089 pour la
  fourniture des binaires ; par 0085/0087/0089/0091 pour la configuration
  automatique). L'ADR 0068 reste historique (précurseur du localisateur).
- `feature:install` ne dépend plus de `core:terminal-runtime` ni des
  artefacts Termux : l'autorisation correspondante de build-logic
  (v0.54.0) devient inerte, elle est conservée sans coût.
