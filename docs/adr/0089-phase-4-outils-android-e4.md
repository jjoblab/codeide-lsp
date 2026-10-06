# ADR 0089 — Phase 4 Outils Android : plan résolu en étape, péremption par quadruplet, licences, câblage Gradle, relance du daemon (E4)

- **Statut** : accepté (étape E4 de la refonte ; implémente la phase
  `ANDROID_SDK` du parcours dans le cadre des ADR 0085/0087, consomme le
  manifeste v2 selon l'ADR 0086 et le contrat § 12)
- **Contexte** : cahier des charges § 5.4 (phase 4), § 6 (environnement
  des processus et câblage Gradle), § 12 (contrat commun). E1 a établi le
  modèle (`InstallPlanResolver` pur, catalogue, ports) ; E2 le cadre
  (verify-first, `CommandRunner`, `DownloadManager` à cache SHA-256,
  `install-state.json`) ; E3 le JDK vérifié avec sonde TLS.

## Décision

### 1. Cinq étapes séquentielles, plan résolu UNE fois par exécution

`PhaseAndroidSdk` (core:bootstrap/installation) livre cinq étapes :
`resolution-plan`, `composants`, `licences`, `cablage`,
`verification-sdk`.

- **Plan partagé par exécution** (`ExecutionPhaseSdk`) : chaque appel à
  `etapes()` construit une instance neuve — l'orchestrateur l'appelle
  juste avant d'exécuter (ADR 0087), les cinq étapes partagent donc le
  même plan résolu (une seule récupération du manifeste par exécution),
  et une exécution **ultérieure** (réparation après mise à jour du
  manifeste) re-résout frais — jamais un plan périmé en cache de
  singleton.
- **`resolution-plan`** : manifeste du port (URL unique du catalogue,
  § 12.7), résolution § 12.2 par le résolveur pur d'E1 (exigences du
  catalogue, révision la plus haute, `installPath` exclusifs, canal
  stable), **contrôle d'espace** (le plan doit tenir deux fois :
  archives téléchargées puis extraites). La vérification rejoue la
  résolution — une résolution réussie EST la preuve ; en échec elle
  rend `false` et l'exécution porte l'erreur typée
  (`ManifesteInvalide`/`Reseau`/`EspaceDisque`). Conséquence assumée :
  la reprise hors ligne échoue honnêtement — la cohérence plan/état
  n'est pas prouvable sans manifeste (§ 12.4), aucun repli silencieux.
- Architecture : `aarch64` seul (le bootstrap est aarch64, ADR 0087) —
  redemandé à `CapaciteArchitecture` en défense en profondeur
  (`ArchitectureNonSupportee`).

### 2. Péremption par quadruplet, réparation du SEUL composant fautif

- L'étape `composants` traite le plan **dans l'ordre** ; pour chaque
  composant : contrôle, puis installation si besoin, puis re-contrôle.
- **Contrôle** (`controleComposant`) : (1) l'`installPath` existe sous
  `$HOME/android-sdk` (§ 12.4) ; (2) pour une installation
  pré-existante, le **quadruplet** persisté dans `install-state.json`
  (`id`, `version`, `revision`, `sha256`, § 12.2.5) doit correspondre au
  plan — tout écart → `Absent` → **réparation de CE composant seul**
  (jamais de copie « si le fichier est absent » sans comparaison) ;
  (3) le `verify` du manifeste est **exécuté** (§ 12.2 : sans shell,
  répertoire courant = racine du SDK, code attendu ET regex dans
  stdout+stderr).
- **Installation** : téléchargement UNE fois (cache SHA-256 du cadre —
  présent + somme correcte = zéro requête, invariant testé par
  compteur), extraction `tar -xJf` par les `tar`/`xz` du bootstrap
  (§ 12.3 : bits d'exécution et liens préservés, comme la phase 1),
  **garde anti-traversée** post-extraction (tout chemin canonique doit
  rester sous le staging), bascule **atomique** du
  `staging/<installPath>` vers `sdk/<installPath>` (renommage, même
  système de fichiers, parents posés, ancienne cible purgée).
- **Criticité** (§ 5.4) : un composant critique en échec échoue la
  phase avec sa sortie capturée ; un composant **non critique**
  (`cmdline-tools`) en échec isolé est journalisé (`ComponentIssue`) et
  l'étape **continue** — l'interface `PhaseInstallation` gagne
  `avertissements(contexte)` : liste non vide → la phase finit en
  **`Degraded`** au lieu de `Succeeded` (extension du cadre E2,
  additive, fakes inchangés par défaut). Le contrôle rend `Degrade`
  (toléré par le `verify` de l'étape) pour un composant tenté et en
  échec non critique **pendant cette exécution** — mais `Absent` sur
  une reprise fraîche : la réparation retente toujours les non
  critiques, jamais un `Degraded` figé.

### 3. Composants persistés avec `installPath` (schéma 2)

- `InstalledComponent` porte `installPath` (§ 12.2) ;
  `PersistedInstallState.SCHEMA_VERSION` passe à **2** : un fichier de
  schéma 1 est rejeté et le parcours repart de zéro **sans
  retélécharger** — la reprise verify-first re-vérifie chaque composant
  par exécution réelle, seuls les quadruplets sont re-posés.
- L'orchestrateur journalise les composants après chaque phase réussie
  (`journaliserComposants`) : ce qui est persisté est ce qui vient
  d'être **prouvé par exécution** (`composantsInstalles(contexte)`
  re-contrôle chaque composant du plan), pas ce qui a été posé.
- **`aapt2` résolu depuis le plan** (§ 12.4) :
  `LocalisationOutils.trouverAapt2` lit d'abord l'`installPath` du
  composant `aapt2` persisté, sinon celui de `build-tools` — par une
  analyse **tolérante par expressions régulières** (pas d'org.json :
  `LocalisationOutils` reste du Kotlin JVM pur testable sans Android ;
  tout écart de format rend `null` sans état inventé) ; à défaut scan
  de la racine du SDK (`build-tools/<plus récente>/aapt2`), en dernier
  recours le binaire hérité de `$PREFIX/bin` (ancien `Aapt2Deployeur`,
  retiré en E6). Jamais un asset, jamais une constante.

### 4. Licences : fichiers écrits par l'app après acceptation (§ 12.5)

- `LicencesSdk` écrit `licenses/android-sdk-license` (hachage
  `24333f8a63b6825ea9c5514f83c2829b004d1fee`) et
  `licenses/android-sdk-preview-license`
  (`84831b9409646a918e30573bab4c9c91346d8abd`) — les valeurs
  historiques stables des licences du SDK Android, inchangées par les
  révisions de `sdkmanager`. Écriture atomique, idempotente (un fichier
  conforme n'est pas réécrit).
- **Non vérifié sur appareil** (règle 2) : les hachages n'ont pas pu
  être validés contre une exécution réelle de
  `sdkmanager --licenses` dans cet environnement (pas d'appareil
  aarch64). Le scénario de contre-vérification est consigné pour
  `docs/TESTS_MANUELS.md` (E6) ; une divergence constatée sera
  signalée au propriétaire (protocole § 12) — aucun contournement
  silencieux. L'échec éventuel serait de toute façon capté par la
  vérification fonctionnelle de `sdkmanager` (étape 5) avec sa sortie.
- L'acceptation explicite reste portée par l'orchestrateur (la phase
  refuse de démarrer sans elle, ADR 0087) — l'étape `licences` n'écrit
  que lorsque le cadre garantit l'acceptation.

### 5. Câblage Gradle : bloc géré idempotent, build-tools alignés

- `EcrivainConfigurationGradle` maintient
  `android.aapt2FromMavenOverride=<aapt2 du plan>` dans un **bloc
  délimité** de `$GRADLE_USER_HOME/gradle.properties` : remplacement
  **en place** (idempotence octet pour octet à chemin inchangé),
  lignes utilisateur conservées, override posé à la main hors bloc
  **neutralisé par commentaire** (jamais écrasé en silence), écriture
  atomique (tmp + renommage). Le chemin vient de
  `InstallPlan.aapt2Binary(sdkRoot)` (composant `aapt2` sinon
  `build-tools`) — testé.
- **Templates alignés** : `android-app` et `android-library` portent
  désormais `buildToolsVersion = "35.0.2"` explicite (celle du
  catalogue, installée par la phase) — sans elle, AGP prendrait sa
  build-tools par défaut (36.0.0, x86_64 sur Maven, inutilisable sur
  appareil — le « trou » constaté en E1). Le test
  `AlignementCatalogueTemplatesTest` (app) verrouille l'alignement
  catalogue ↔ templates (build-tools ET compileSdk ↔ plateforme).
- **`ANDROID_HOME` exporté dès que le SDK est cohérent** :
  `MarqueursOutils.estSdkAndroidValide` accepte désormais un dossier
  portant un des répertoires attendus (`cmdline-tools`, `build-tools`,
  `platform-tools`, `platforms`) — un SDK partiel n'est plus invisible
  (constat § 1 corrigé ; `androidJar()` continue d'exiger une
  plateforme réelle).
- **Matrice AGP ↔ build-tools ↔ compileSdk** documentée dans
  `docs/ENVIRONNEMENT.md` (AGP 9.4.1 ↔ build-tools 35.0.2 ↔
  compileSdk 37.2 sur appareil ; la machine de build x86_64 garde
  36.0.0 — deux contraintes différentes, ADR 0086).

### 6. Relance du daemon Gradle par empreinte (§ 6)

- `EmpreinteChaineOutils` (core:domain, pur) : SHA-256 de `JAVA_HOME`,
  `ANDROID_HOME`, chemin d'`aapt2` et des versions recensées (clés
  triées — stable) ; `DetecteurChangementEmpreinte` signale les
  changements, jamais la première observation (le daemon vient de
  démarrer avec l'environnement courant).
- Câblage applicatif (`CodeIdeApplication`) : collecte de l'état du
  parcours → empreinte (chemins par `ToolchainLocator` — lui-même
  alimenté par l'état persisté pour `aapt2` — versions de l'état
  **vérifié**) → changement → `daemonTooling.arreter()` puis
  `demarrer()`. Limite documentée : `PhaseState.Degraded` ne porte pas
  de versions (modèle du cahier § 4) — l'empreinte d'une phase dégradée
  omet ses versions, le chemin d'`aapt2` (la partie critique) reste
  couvert par le localisateur.

### 7. Vérification fonctionnelle (§ 5.4.5) et vérification approfondie

- L'étape `verification-sdk` : `sdkmanager --version` **avec `JAVA_HOME`
  explicite et `--sdk_root`** (un échec dégrade — `cmdline-tools` non
  critique, diagnostic capturé) ; cohérence de
  `sdkmanager --list_installed` avec le plan (chaque `installPath`
  critique attendu, sortie à l'appui en échec) ; chaque `android.jar`
  du plan **ouvrable en archive zip** (contrôle JVM local). Sans
  `cmdline-tools` dans le plan (ou dégradé), les contrôles `sdkmanager`
  sont journalisés et sautés — jamais silencieux.
- **Vérification approfondie** (`verify(deep)`, bouton hors parcours) :
  nouveau port `VerificationApprofondie` (core:domain), implémentation
  applicative `VerificationApprofondieProjets` — génère un **vrai
  projet** par le pipeline complet de création (template `android-app`,
  défauts du manifeste du modèle), lance un **vrai `assembleDebug`**
  via le `gradlew` du projet (le wrapper télécharge sa distribution
  dans `GRADLE_USER_HOME` à la première exécution — réutilisée ensuite,
  délai 30 min), puis **supprime le projet de contrôle** quoi qu'il
  arrive. L'orchestrateur marque la phase `ANDROID_SDK` en échec dans
  le rapport si l'assembleDebug échoue (sortie bornée jointe) ;
  ignorée et journalisée si la phase n'est pas vérifiée.

## Conséquences

- Le parcours complet BOOTSTRAP → PACKAGE_TOOLS → JAVA → ANDROID_SDK
  est livré et exécutable de bout en bout (licence exigée avant la
  phase 4).
- 22 tests nouveaux : `PhaseAndroidSdkTest` (10 : parcours propre,
  licence, reprise 0 retéléchargement, réparation ciblée par quadruplet,
  dégradé non critique, critique en échec, manifeste incompatible,
  espace insuffisant, `--list_installed` incohérent, `android.jar`
  illisible), `EcrivainConfigurationGradleTest` (5),
  `EmpreinteChaineOutilsTest` (4, core:domain),
  `AlignementCatalogueTemplatesTest` (4, app), 4 tests du localisateur
  (SDK cohérent sans plateforme, `aapt2` depuis l'état, priorité
  composant `aapt2`, repli scan).
- `FakeDownloadManager` gagne les archives par somme (plans
  multi-composants) ; `FakeVerificationApprofondie` rejoint
  `core:testing`.
- **Non vérifié sur appareil** (règle 2) : extraction réelle des
  archives `.tar.xz` aarch64, `sdkmanager` réel (sortie de
  `--list_installed` supposée citer l'`installPath`), hachages de
  licences, `assembleDebug` sur appareil — scénarios complets consignés
  pour `docs/TESTS_MANUELS.md` en E6.
- Divergence héritée (§ 12, signalée depuis E1) : le manifeste v1
  publie `platform/aapt2/build-tools` 36.0.2 non publiés en aarch64 —
  le catalogue exige 35.0.2 ; à arbitrer avec le prompt 2.
