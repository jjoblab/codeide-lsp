# ADR 0086 — Catalogue de versions de la chaîne d'outils et consommation du manifeste v2 (`codeide-tools`)

- **Statut** : accepté (étape E1 de la refonte)
- **Contexte** : cahier des charges sections 3 (principes 1 et 8), 6 et 12
  (contrat commun `codeide-tools` v2 ↔ CodeIDE — bloc **identique** dans les
  prompts 1 et 2, prévalant sur toute autre formulation). Le manifeste v1
  actuel (`manifest.json` du dépôt `jjoblab/codeide-tools`) ne connaît que
  `build_tools`/`platform_tools` 35.0.2 par architecture et un
  `cmdline_tools` reconditionné ; les plateformes viennent aujourd'hui du
  `sdkmanager` Google, les versions sont éparpillées entre scripts shell et
  constantes.

## Décision

### 1. Le catalogue (`ToolchainCatalog`, `core:domain`) : les EXIGENCES de l'app

Un unique objet injecté exprime ce que **l'application exige** — jamais ce
que le dépôt publit. Valeurs initiales, chacune **justifiée** :

| Exigence | Valeur | Justification (vérifiée dans ce dépôt) |
|---|---|---|
| `manifestUrl` | constante unique, **configurable** | § 12.7 : tant que le prompt 2 n'a pas fixé l'URL réelle (R5), la constante pointe un **faux manifeste de test conforme à 12.2** (ressource de test) ; aucune URL de composant en dur nulle part ailleurs |
| `sdkProfile` | `default` | nom du profil consommé dans `profiles.<nom>` du manifeste v2 |
| `jdkMajor` | `17` | `openjdk-17` est le seul JDK publié par le dépôt APT (vérifié : `Packages` de `codeide-main`) ; le manifeste n'exprime que `requires: jdk>=17` |
| `jdkPackage` | `openjdk-17` | phase 3 : `pkg install openjdk-17` (§ 12.1 : le JDK est de la responsabilité du prompt 1) |
| `packageTools` | `curl`, `ca-certificates`, `tar`, `xz-utils`, `unzip` | § 5 phase 2 : outils nécessaires pour télécharger, vérifier et extraire la suite ; extraction `.tar.xz` par `tar`/`xz` du bootstrap (ADR 0084 R4, ADR 0085) |
| `requiredComponents` | `build-tools@35.0.2`, `platform@android-37.2` | voir § 2 |
| `spaceThresholdBytes` | 1 Gio (seuil plancher), recalculé par le plan | § 5 phase 4 : contrôle d'espace sur la taille totale **résolue** du plan |

Le catalogue est **consommé par les templates** : `android-app`/
`android-library` référencent la même version de build-tools
(`buildToolsVersion` explicite — ajouté en E4) — une seule source de vérité,
testée (le test de résolution exige chaque version du catalogue dans le
plan résolu).

### 2. build-tools 35.0.2 : alignement délibéré sur ce qui existe pour aarch64

Le dépôt `codeide-tools` ne publie **que** build-tools 35.0.2 par
architecture (manifeste v1 vérifié ; source amont
`lzhiyong/android-sdk-tools`, dernier tag publié à ce jour). La machine de
build x86_64 utilise 36.0.0 (`docs/ENVIRONNEMENT.md`) — **ce n'est pas la
même contrainte** : sur l'appareil, un build-tools inexistant pour
l'architecture ferait retélécharger par AGP un binaire x86_64 inutilisable
(le piège originel, ADR 0082). Décisions :

- le catalogue exige `build-tools@35.0.2` ; les templates posent
  `buildToolsVersion = "35.0.2"` ;
- AGP 9.4.1 + `buildToolsVersion` explicite 35.0.2 + `compileSdk` 37.2 :
  la matrice de compatibilité est documentée dans `docs/ENVIRONNEMENT.md`
  (E4) et confrontée à la ligne `compat` du manifeste v2
  (`status: tested|untested`) ;
- **montée de version = geste délibéré** : le manifeste publie d'abord
  (prompt 2), puis le catalogue et les templates suivent ensemble, avec un
  test qui échoue tant que le plan résolu ne contient pas la version exigée.

**Divergence constatée à signaler au propriétaire** (§ 12 protocole) :
`platform` et `aapt2` ne figurent pas au manifeste v1 — le prompt 2 doit
les publier dans le manifeste v2 (composants `platform@android-37.2`,
éventuellement `aapt2`, sinon le binaire vient du composant `build-tools`,
§ 12.4). Ce dépôt consomme et ne modifie rien dans `codeide-tools`.

### 3. Manifeste v2 : types du domaine et règles de résolution (§ 12.2)

Le domaine modélise le manifeste **à la lettre** du contrat :
`ToolManifest` (racine : `schemaVersion` = 2, `generatedAt`, `components`,
`profiles`, `compat`), `ManifestComponent` (tous champs requis sauf
`channel`/`license`/`minAndroidApi`), `VerifySpec` (`cmd`/`expect`/
`exitCode`), `CompatLine`. L'analyse JSON vit dans `core:bootstrap`
(kotlinx-serialization est déjà la dépendance du domaine pour les
manifestes de templates) ; les champs inconnus sont ignorés, un champ
requis absent rend le manifeste **invalide** (`AppError.EnvironmentSetup(
ManifesteInvalide)`).

**Résolution du plan** — fonction pure du domaine
(`InstallPlanResolver.resolve(catalogue, manifeste, arch)`) :

1. le plan contient, pour chaque composant du profil `sdkProfile` dont
   `arch` est celle de l'appareil ou `any`, l'entrée de `revision` la plus
   haute pour le couple (`id`, `version`) ;
2. si le plan ne contient pas une version exigée par le catalogue →
   erreur **« manifeste incompatible »** (jamais une autre version, jamais
   une autre source) ;
3. l'`installPath` de chaque composant est **exclusif** (contrôle à la
   résolution : deux composants du plan ne partagent jamais un chemin) ;
4. `channel: preview` est ignoré ;
5. immutabilité (§ 12.2.5) : le quadruplet (`id`, `version`, `revision`,
   `arch`) publié n'est jamais modifié — l'app enregistre
   (`id`, `version`, `revision`, `sha256`) dans `install-state.json` et
   répare **ce** composant seul à tout écart (jamais de copie « si le
   fichier est absent » sans comparaison).

**Criticité** (§ 12.2, déclarée par le manifeste, respectée par la phase 4) :
build-tools, platform-tools, plateformes et `aapt2` sont **critiques**
(échec → `Failed`) ; `cmdline-tools` est **non critique** (AGP ne l'utilise
pas pour compiler) — échec isolé → `Degraded` avec diagnostic complet.

### 4. Environnement des processus et câblage (§ 12.4, § 6 du cahier)

- **Un seul** `ProcessEnvironmentProvider`, reconstruit depuis l'état
  **vérifié** : `HOME` = `<filesDir>/home` ; `ANDROID_HOME` =
  `ANDROID_SDK_ROOT` = `$HOME/android-sdk` ; `ANDROID_USER_HOME` =
  `$HOME/.android` ; `GRADLE_USER_HOME` = `$HOME/.gradle` (valeur actuelle
  de `docs/ENVIRONNEMENT.md`, inchangée) ; `JAVA_HOME` par la règle unique
  existante ; `PATH` =
  `$PREFIX/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools`
  (les build-tools n'y figurent jamais — plusieurs versions côte à côte,
  AGP les trouve par `ANDROID_HOME`). `ANDROID_HOME` est exporté dès que le
  dossier SDK est **cohérent** — sans attendre une plateforme (fin du
  « SDK partiel invisible »).
- **`aapt2Binary()` lit le chemin résolu dans le plan** : composant `aapt2`
  du plan s'il existe, sinon composant `build-tools` — jamais un asset ni
  une constante. `Aapt2Deployeur`, son asset et `AssetAbsent` sont supprimés
  (E6).
- **Override AGP** : `GradleUserConfigWriter` maintient un **bloc géré et
  délimité** dans `$GRADLE_USER_HOME/gradle.properties` avec
  `android.aapt2FromMavenOverride=<chemin aapt2 du plan>` — réécriture
  idempotente, lignes de l'utilisateur intactes, testée (idempotence,
  préservation, suppression propre).
- **Le profil shell** : l'app est l'unique écrivain, après chaque phase
  vérifiée, de ce que le profil exporte (écriture atomique). Le pont
  `ide-environment.properties` est supprimé s'il n'a plus de lecteur (E6).
- **Relance du daemon Gradle** quand l'empreinte de la chaîne change
  (hachage de `JAVA_HOME`, `ANDROID_HOME`, chemin et version d'`aapt2`,
  versions) — test à l'appui (E4).

### 5. Licences (§ 12.5)

L'app écrit les fichiers du dossier `licenses/` (hachages validés contre
une vraie exécution de `sdkmanager --licenses` sur appareil — à faire en
E4, sortie capturée, **non vérifié** à ce stade) **après acceptation
explicite** de l'utilisateur : licence affichée (lien + résumé), case à
cocher, date conservée dans `install-state.json`. Le manifeste ne livre
aucun fichier de licence. Plus de `yes | sdkmanager --licenses
>/dev/null`. Ce choix est un défaut prudent, à confirmer par le
propriétaire (signifié dans le compte rendu E1).

### 6. Synchronisation avec `codeide-tools` (§ 12.7)

- URL du manifeste : **constante unique du catalogue** (configurable) ;
  tant que le prompt 2 ne l'a pas fixée, elle pointe un faux manifeste de
  test conforme à 12.2 — aucune URL de composant en dur.
- Vecteurs de référence (`tests/golden/` du dépôt `codeide-tools`) :
  importés dans les tests de `core:bootstrap` au plus tard en E4 ;
  jusqu'ici, les tests utilisent des manifestes factices locaux couvrant
  les mêmes cas (valides/invalides, sommes connues, composant non
  critique en échec).
- Signature du manifeste (R6 du prompt 2) : si elle est retenue, l'app la
  vérifiera avec une clé publique embarquée (ADR dans les deux dépôts +
  incrément de `schemaVersion`) ; à défaut, la somme SHA-256 des archives
  protège.
- Manifeste v1 : un adaptateur v1 **en lecture seule, journalisé** est
  possible pendant la transition (retiré à la date de fin fixée par le
  propriétaire) — décision différée à E4 selon l'état du manifeste v2.

## Conséquences

- « Un composant = une version résolue = un téléchargement » devient un
  **invariant testé** : la résolution produit le plan, le plan produit les
  téléchargements, le cache SHA-256 les déduplique — le double
  téléchargement cmdline-tools (constat n° 1 du cahier) disparaît.
- `sdkmanager` n'est plus une source d'installation des binaires
  architecture-dépendants (build-tools/platform-tools/aapt2) — il n'est
  plus exécuté que comme composant **non critique** du plan, vérifié par
  son champ `verify` du manifeste.
- Les plateformes (`android.jar`, pur Java) viennent du manifeste v2 (fin
  de la double source Google/manifeste) — dépend du prompt 2 (divergence
  signalée ci-dessus).
- `docs/ENVIRONNEMENT.md` documentera la matrice AGP ↔ build-tools ↔
  `compileSdk` (E4).
