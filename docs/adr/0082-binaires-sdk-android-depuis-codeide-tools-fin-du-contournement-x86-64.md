# ADR 0082 — Binaires du SDK Android depuis le dépôt `codeide-tools` : fin du contournement x86_64

- Statut : accepté (2026-10-04)
- Contexte : vouloir régler DÉFINITIVEMENT le problème de l'Android SDK,
  « comme AndroidIDE » — création du dépôt séparé
  [`jjoblab/codeide-tools`](https://github.com/jjoblab/codeide-tools)
  et mise à jour de CodeIDE pour le consommer.

## Contexte

Le `sdkmanager` de Google (posé par la commande `android-sdk`, ADR 0068)
installe `platform-tools` et `build-tools` depuis `dl.google.com` : ces
composants sont des binaires Linux **x86_64**. Sur un téléphone aarch64,
le noyau refuse leur exécution (ENOEXEC) — exactement le piège déjà
rencontré pour le binaire natif `android` des cmdline-tools récents
(v0.48.0 : « not executable: 64-bit ELF file »), mais ici pour TOUTE la
boîte à outils : `aapt`, `aapt2`, `aidl`, `zipalign`, `dexdump`, `adb`…
Le contournement historique ne couvrait que `aapt2` (binaire cross-compilé
embarqué en asset de l'app, ADR 0032) ; `aidl`, `zipalign` et le reste
restaient morts sur disque, et le dépôt APT `codeide-packages` ne publie
aucun paquet `android-sdk` (ADR 0032, toujours d'actualité — vérifié :
seuls `openjdk-17`, `git` et les paquets de base y figurent).

La lecture du projet AndroidIDE nomme la solution : eux n'installent
JAMAIS les binaires du dépôt Google — leur dépôt `androidide-tools`
publie des composants SDK RECONDITIONNÉS PAR ARCHITECTURE (compilés pour
Android par [`lzhiyong/android-sdk-tools`](https://github.com/lzhiyong/android-sdk-tools)),
décrits par un manifeste d'URLs, et leur installeur les télécharge.
AndroidIDE sépare ce dépôt de leurs `termux-packages` : les paquets du
bootstrap et les versions du SDK n'ont pas le même cycle de vie.

## Décision

### 1. Le dépôt `jjoblab/codeide-tools` (créé, séparé de `codeide-packages`)

- **Contenu** : `manifest.json` (URLs + **sommes SHA-256** par
  architecture — mêmes clés que le manifeste d'AndroidIDE, plus les
  sommes), `scripts/codeidesetup` (installeur autonome du terminal :
  `curl … | bash`, sans passer par l'app), `scripts/package-sdk.sh`
  (fabrique les archives depuis les zips statiques de
  `lzhiyong/android-sdk-tools` : `build-tools/X.Y.Z/` et `platform-tools/`
  avec `source.properties`, reconditionnés en tar.xz par architecture),
  et deux workflows GitHub Actions de publication (SDK par version,
  command-line tools).
- **Les archives vivent dans les releases GitHub** (`vX.Y.Z` pour le SDK,
  `sdk` pour les cmdline-tools) : rien de binaire n'est versionné dans
  git ; le workflow commite le manifeste mis à jour après publication.
- **Gardes de fabrication** : `package-sdk.sh` refuse un zip
  cmdline-tools portant le binaire natif `bin/android` (ELF — rev 19+ :
  `sdkmanager` y délègue et Google ne le publie Linux qu'en x86_64 : la
  rev 12.0, script 100 % Java, est la référence documentée) ; le
  manifeste absent est créé vierge (fabrication hors dépôt cloné).
- `codeidesetup` écrit `JAVA_HOME` (résolu dynamiquement : `lib/jvm/*`
  du dépôt APT `codeide-packages` — constaté sur `Contents-aarch64` :
  `usr/lib/jvm/java-17-openjdk/bin/java` — puis `opt/openjdk*` Termux)
  et `ANDROID_SDK_ROOT` dans `$SYSROOT/etc/ide-environment.properties`.

### 2. CodeIDE consomme le manifeste (commande `android-sdk`, v0.51.0)

`android-sdk installer` devient :

1. **Binaires Android d'abord** : lecture du manifeste
   (`raw.githubusercontent.com/jjoblab/codeide-tools/main/manifest.json`,
   surcharges `CODEIDE_TOOLS_REPO` / `CODEIDE_TOOLS_MANIFEST`),
   résolution de la version la plus récente publiée pour l'architecture
   de l'appareil (`uname -m`, surcharge `CODEIDE_ARCH` pour les tests ;
   aarch64/arm/x86_64), téléchargement de
   `build-tools-X.Y.Z-<arch>.tar.xz` et
   `platform-tools-X.Y.Z-<arch>.tar.xz` (~8 Mio au total — le parcours
   équivalent par le sdkmanager en pesait ~172), **vérification SHA-256**
   avant extraction sous `$HOME/android-sdk`, idempotence (un `aapt2`
   déjà en place saute le téléchargement). `jq` lit le manifeste
   (présent dans le dépôt APT, installé par la commande s'il manque) :
   jamais d'analyse JSON à la main en shell.
2. **cmdline-tools** : reconditionnés du manifeste s'il les publie
   (miroir GitHub + SHA-256), sinon la rev 12.0 de Google épinglée
   (v0.48.0) ; la vérification fonctionnelle (`sdk_fonctionnel`) fait foi
   dans les deux cas, la GUÉRISON est conservée.
3. **Plateformes via `sdkmanager`** : `android.jar` est pur Java, sans
   dépendance d'architecture — le dépôt Google reste LA source des
   plateformes (`platforms;android-37.2` par défaut) ; il n'est plus
   celle des binaires natifs.

`PAQUETS_DEFAUT` perd `platform-tools` et `build-tools` (désormais
installés par le manifeste) : les arguments d'`installer` ne règlent plus
que les plateformes.

### 3. Pont `ide-environment.properties` (profil shell)

Le profil `$PREFIX/etc/codeide.sh` (VERSIONNEUR 6) respecte les clés
posées par `codeidesetup` : liste blanche (`JAVA_HOME`,
`ANDROID_SDK_ROOT`, `ANDROID_HOME`), chaque clé ne passe QUE si absente
de l'environnement de la session — le ballotage de l'app (ADR 0068)
reste la source la plus fraîche, le fichier comble les blancs (JDK d'un
emplacement non scanné, installation autonome sans l'app). Lecture ligne
à ligne `CLE=VALEUR` avec un `case` par clé : jamais de `.` sourcé (une
valeur hostile ne serait pas évaluée), commentaires ignorés.

### 4. `Aapt2Deployeur` inchangé

Le déploiement d'`aapt2` depuis les assets de l'app reste : il couvre le
cas « SDK pas encore installé » (première build d'un projet neuf avant
tout passage au terminal) et ne conflitte pas — les build-tools du
manifeste vivent sous `$SDK_HOME/build-tools/X.Y.Z/`, l'asset sous
`$PREFIX/bin/aapt2`. Le jour où le dépôt APT publie ses propres paquets,
le même mécanisme de scan multi-emplacements les trouvera (ADR 0032).

## Conséquences

- Sur un téléphone aarch64, `android-sdk installer` pose des build-tools
  et platform-tools RÉELLEMENT exécutables : `aidl`, `zipalign`,
  `dexdump`, `adb`… cessent d'être des décorations x86_64 ; le contournement
  `aapt2`-en-asset devient une roue de secours plutôt que la seule
  solution.
- Le dépôt `codeide-tools` doit rester PUBLIC (le manifeste et les
  releases sont téléchargés sans authentification) — sa CI (validation
  shellcheck + manifeste) protège la chaîne.
- La publication d'une nouvelle version du SDK est un geste DÉLIBÉRÉ du
  dépôt (workflow manuel `Publier build-tools et platform-tools`) : les
  appareils suivent la version la plus récente du manifeste ; un manifeste
  sans version publiée rend un message actionnable, pas un échec muet.
- Les sommes SHA-256 verrouillent chaque archive : une release corrompue
  ou réécrite est REFUSÉE à l'installation (rien n'est extrait).
- Le seuil d'espace disque (~1 Gio) est conservé — les ~8 Mio de binaires
  s'ajoutent aux cmdline-tools (~130 Mio) et à la plateforme (~60 Mio) ;
  il reste surchargeable (`CODEIDE_ESPACE_REQUIS_KO`) pour les appareils
  volontairement limités.
- Les appareils v0.48.0-v0.50.0 reçoivent la nouvelle commande et le
  nouveau profil au démarrage suivant (versionneur 5 → 6), SANS
  réinstallation du bootstrap ; un SDK x86_64 déjà posé est simplement
  complété (les dossiers `build-tools/<version>` manquants sont ajoutés,
  l'ancien reste — le scan de `LocalisationOutils` prend la plateforme la
  plus récente).

## Références

- Dépôt [`jjoblab/codeide-tools`](https://github.com/jjoblab/codeide-tools)
  (README : manifeste, `codeidesetup`, publication par workflows).
- [`Lzhiyong/android-sdk-tools`](https://github.com/Lzhiyong/android-sdk-tools)
  — binaires Android SDK compilés par architecture (tags 33.0.3, 34.0.3,
  35.0.2 à ce jour ; assets `android-sdk-tools-static-<arch>.zip`).
- [`AndroidIDEOfficial/androidide-tools`](https://github.com/AndroidIDEOfficial/androidide-tools)
  — l'architecture de référence (manifeste, releases par architecture).
- ADR 0032 (disposition du bootstrap, `Aapt2Deployeur`, absence de paquet
  APT `android-sdk`), ADR 0068 (commande `android-sdk`, scripts
  versionnés), v0.48.0 (rev 12.0 épinglée, guérison).
- Fichier `Contents-aarch64` du dépôt APT `codeide-packages` (emplacement
  réel du JDK : `usr/lib/jvm/java-17-openjdk/bin/java`).
