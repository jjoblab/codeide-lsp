# ADR 0084 — Investigation : cause racine du « SDK non fonctionnel » (reproductions réelles et sortie capturée)

- **Statut** : accepté (étape E1 de la refonte du parcours d'installation)
- **Contexte** : constat n° 2 du cahier des charges de la refonte — « le SDK n'est
  pas fonctionnel » en fin d'installation s'affichait sans que la cause racine du
  premier échec de `sdkmanager --version` n'ait jamais été capturée (sortie
  jetée par `sdk_fonctionnel` : `"${SDKMANAGER}" --version >/dev/null 2>&1`).
  Règle de travail n° 2 : toute affirmation sur le comportement à l'exécution
  est soit **reproduite**, soit marquée **non vérifié**.

## Méthode

Trois sources de preuve, distinguées explicitement :

1. **Reproductions sur Linux x86_64** (machine de build, OpenJDK 21) : les
   mécanismes du script `sdkmanager` (rev 12.0) et de la JVM sont
   architecture-indépendants — chaque mode d'échec est déclenché et sa sortie
   **intégrale** capturée ci-dessous.
2. **Analyse statique des artefacts aarch64 réels** : l'archive
   `cmdline-tools.tar.xz` reconditionnée publiée par `codeide-tools`
   (123 Mio, SHA-256 vérifié contre le manifeste v1 :
   `8b2790ab…b0d45f`), le zip Google `commandlinetools-linux-11076708`
   (rev 12.0) et le paquet APT **réel** `openjdk-17_17.0.20_aarch64.deb`
   (96 Mio, SHA-256 vérifié contre le fichier `Packages` du dépôt).
3. **Vérification sur appareil aarch64** : **non vérifié** dans cet
   environnement (aucun appareil ni émulateur ; pas de qemu). Le plan de
   vérification sur appareil est livré en fin d'ADR et sera exécuté en E2/E4.

## Constat liminaire : les deux `sdkmanager` sont identiques

`diff` entre `cmdline-tools/latest/bin/sdkmanager` (archive reconditionnée
codeide-tools) et `cmdline-tools/bin/sdkmanager` (zip Google rev 12.0) :
**vide** — les scripts sont identiques octet pour octet. L'archive
reconditionnée est une repack de la rev 12.0, sans binaire natif `android`
(le piège ENOEXEC des rev 19+, documenté v0.48.0, est évité), avec bits
d'exécution posés (`-rwxr-xr-x`) et la disposition `cmdline-tools/latest/`
déjà conforme. **L'archive reconditionnée n'est pas cassée en soi.**

Le script (rev 12.0, lu intégralement) résout la JVM ainsi :
`JAVA_HOME` → `$JAVA_HOME/bin/java` (erreur explicite code 1 si invalide),
sinon `java` du `PATH` (erreur explicite sinon), puis :

```sh
CLASSPATH=$APP_HOME/lib/sdkmanager-classpath.jar
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $SDKMANAGER_OPTS \
    -classpath "$CLASSPATH" com.android.sdklib.tool.sdkmanager.SdkManagerCli "$APP_ARGS"
```

**Conséquence clé** : le script relaie fidèlement le code de sortie **et**
la stderr de la JVM. Tout diagnostic perdu l'a été côté application.

## Reproductions (x86_64, sorties intégrales)

### R1 — Archive reconditionnée, JVM saine : SUCCÈS

```console
$ ./cmdline-tools/latest/bin/sdkmanager --version
12.0
$ echo $?
0
```

Avec JAVA_HOME absent (java du PATH) **et** avec JAVA_HOME pointant le JDK 21.
Une archive saine + une JVM qui démarre = succès, quelle que soit la source.

### R2 — `JAVA_HOME` invalide : échec EXPLICITE (code 1)

```console
$ JAVA_HOME=/chemin/inexistant ./cmdline-tools/latest/bin/sdkmanager --version

ERROR: JAVA_HOME is set to an invalid directory: /chemin/inexistant

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
$ echo $?
1
```

Mode visible et auto-documenté : ce n'est PAS le mode muet recherché.

### R3 — HOME non inscriptible : `--version` réussit quand même (code 0)

`HOME` pointé vers un répertoire en lecture seule : `sdkmanager --version`
répond `12.0`, code 0. `~/.android` n'est pas requis par `--version` —
un HOME cassé ne peut PAS être la cause du « non fonctionnel » de `--version`
(à la différence d'opérations ultérieures).

### R4 — Bits d'exécution : l'extraction `tar` du préfixe les préserve

`tar -xJf … --no-same-permissions --mode=-rw-r--r--` : les fichiers
repartent **exécutables** (mode stocké 755 ⊕ umask 022 → 755). Le mode
« bits perdus à l'extraction » suppose un extracteur maison (zip Java sans
pose de permissions — le piège historique d'`Aapt2Deployeur`), pas `tar` :
l'extraction par `tar`/`xz` du bootstrap, exigée par le cahier (§ 5 phase 2),
préserve les bits par construction. **Non vérifié sur appareil** (umask du
shell du bootstrap supposé 022, convention Termux).

### R5 — JVM présente mais incapable de démarrer : LE MODE MUET (code 127)

Un `JAVA_HOME` dont `bin/java` existe et est exécutable mais dont les
bibliothèques manquent :

```console
$ JAVA_HOME=…/faux-jdk ./cmdline-tools/latest/bin/sdkmanager --version
$ echo $?
127
$ # stdout : VIDE
$ # stderr :
faux-jdk/bin/java: error while loading shared libraries: libjli.so: \
cannot open shared object file: No such file or directory
```

**Code 127, stdout vide, diagnostic uniquement sur stderr.** C'est
exactement le profil du « SDK non fonctionnel » sans indice : le contrôle
`sdk_fonctionnel` jetait les deux tuyaux (`>/dev/null 2>&1`), le pipeline
ne voyait qu'un code non nul — et le message « JDK à réinstaller ? » de la
v0.53.0 était une devinette, pas un diagnostic.

### R6 — Truststore cassé : `--version` réussit (code 0)

`lib/security/cacerts` supprimé du JDK : `sdkmanager --version` répond
`12.0`, code 0. **Le truststore ne peut pas être la cause de l'échec de
`--version`** — il casse les opérations **réseau** ultérieures du
sdkmanager (`--list`, installations de plateformes), avec des symptômes
différés. Conséquence architecturale : le test TLS exigé par le cahier
(§ 5 phase 3) doit vivre en **phase 3** (Java), pas en phase 4.

## Analyse statique du paquet `openjdk-17` aarch64 réel (dépôt APT)

Le paquet `openjdk-17_17.0.20_aarch64.deb` installé sur l'appareil par
`pkg install openjdk-17` (dépendances et emplacements vérifiés sur le
`.deb` extrait — pas déduits de mémoire) :

- s'installe dans `$PREFIX/lib/jvm/java-17-openjdk` ;
- `bin/java` (ELF aarch64, interprète `/system/bin/linker64`, NDK r29) :
  - `NEEDED`: `libjli.so`, `libc.so` ;
  - `RUNPATH`: `/data/data/jo.codeide/files/usr/lib/jvm/java-17-openjdk/lib`
    **(chemin absolu codé en dur)** `:` `/data/data/jo.codeide/files/usr/lib`
    `:` `$ORIGIN` `:` `$ORIGIN/../lib` ;
- `lib/libjli.so` : `NEEDED` **`libz.so.1`** (fourni par le paquet `zlib`) ;
- `lib/server/libjvm.so` : `NEEDED` **`libandroid-shmem.so`** (paquet
  `libandroid-shmem`) ;
- `Depends`: `libandroid-shmem, libandroid-spawn, libiconv, libjpeg-turbo,
  zlib, littlecms, alsa-plugins` ;
- `Recommends`: **`ca-certificates-java`** (le truststore Java !),
  `openjdk-17-x`, `resolv-conf`.

**Chaîne causale complète du mode R5 sur appareil** :
`java` → `libjli.so` (résolu par RUNPATH) → `libz.so.1` (paquet `zlib`) ;
`libjvm.so` → `libandroid-shmem.so`. **Toute** dépendance APT absente
(installation partielle, dépôt incohérent, préfixe réparé à moitié) ou
RUNPATH mort (filesDir hors `/data/data/jo.codeide/files` — multi-user,
profil de travail ; les replis `$ORIGIN` couvrent ce cas) déclenche le
mode R5 : code 127, diagnostic sur stderr uniquement.

Le RUNPATH absolu codé en dur est une **fragilité réelle** constatée :
`/data/data/jo.codeide/files` n'est l'emplacement canonique que pour
l'utilisateur 0 sans profil ; ailleurs, seuls les replis `$ORIGIN` (qui
pointent correctement `$JAVA_HOME/lib`) sauvent le chargement. Sur appareil,
`getpwuid` (bug documenté ADR 0032) et le chemin `filesDir` réel peuvent
diverger de ce qui est compilé dans le binaire. **Non vérifié sur appareil.**

## Conclusion — cause racine

Le « SDK n'est pas fonctionnel » en fin d'installation est l'affichage
d'un échec de `sdkmanager --version` dont **l'application jetait la
sortie**. Le mode d'échec dominant (seul profil reproductible muet :
stdout vide + code 127) est **une JVM du préfixe incapable de démarrer**
(bibliothèque manquante — `libz.so.1`, `libandroid-shmem.so`… — ou RUNPATH
mort). Le sdkmanager rev 12.0 relaie correctement code et stderr : la
panne était **en amont** (JDK du dépôt APT partiellement installé ou
dépendances absentes), et **invisible** parce que l'app ne capturait rien.

Cause racine secondaire, différée : le truststore (`ca-certificates-java`,
simple `Recommends`) ne casse pas `--version` mais casse le réseau du
sdkmanager — détecté désormais en phase 3 par le test TLS (requête HTTPS
Java vers `dl.google.com`), avant d'engager les 150+ Mio de la phase 4.

## Décisions induites (développées dans les ADR 0085 et 0086)

1. **Aucune sortie jetée, jamais** : le `CommandRunner` capture stdout et
   stderr de chaque commande, les journalise (journal dédié `install`) et
   attache les 200 dernières lignes expurgées à l'état d'échec.
2. **« Installé » = « vérifié en l'exécutant »** : chaque phase se conclut
   par une vérification fonctionnelle réelle (phase 3 : `java -version`,
   `javac -version` **et** test TLS — R6 démontre que la version seule ne
   suffit pas).
3. **Un composant = une version résolue = un téléchargement** : le double
   parcours cmdline-tools (manifeste puis Google en repli silencieux)
   disparaît ; cmdline-tools vient du manifeste v2, sans second chemin.
4. **Le diagnostic JVM est structuré** : l'échec d'une commande porte son
   `SortieCommande` (commande, code, dernières lignes) — le message
   « SDK non fonctionnel » sans sortie réelle est interdit (critère
   d'acceptation du cahier).

## Plan de vérification sur appareil (aarch64) — non vérifié ici

À exécuter sur un appareil réel à la première occasion (E2 ou E4), les
sorties étant capturées par le nouveau `CommandRunner` :

1. `$PREFIX/lib/jvm/java-17-openjdk/bin/java -version` — code et sortie
   complète (reproduit R5 ou non sur CET appareil).
2. `readelf -d $JAVA_HOME/bin/java | grep RUNPATH` — l'emplacement réel du
   `filesDir` de l'appareil couvre-t-il le chemin codé en dur ?
3. `pkg list-installed` après la phase 3 — les 7 `Depends` du paquet
   `openjdk-17` sont-ils tous installés ?
4. `ls $JAVA_HOME/lib/security/cacerts` + test TLS phase 3 — le
   `Recommends` `ca-certificates-java` a-t-il été installé par `pkg` ?
5. `sdkmanager --version` avec `JAVA_HOME` explicite et `--sdk_root`, sortie
   complète capturée (vérification fonctionnelle de la phase 4).

Chaque point entre dans le compte rendu de l'étape qui l'exécute, avec la
sortie réelle — ou le marqueur « non vérifié » si l'appareil manque.
