# ADR 0067 — Identité de signature debug versionnée + échelle release hors dépôt : fin des « conflits de package »

- **Statut** : accepté (v0.37.2, retour utilisateur du 2026-09-28 — v0.37.1
  ne s'installe pas par-dessus v0.37.0 : « conflit de package »). Amendé
  par rapport à l'ADR 0063, décision 3 (cache du keystore en CI) : cette
  atténuation n'a pas tenu.

## Contexte

L'utilisateur installe les APK debug produits par le CI GitHub (artefacts
des runs). Depuis le début, chaque nouvelle version exige une
**désinstallation préalable** : Android refuse la mise à jour d'un package
par un APK signé par une clé différente (« conflit de package » /
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Retours : v0.35.2 (d'où l'ADR 0063,
décision 3), puis à nouveau v0.37.1 sur v0.37.0 alors que le cache
keystore était en place.

Référence demandée par l'utilisateur : **projet de référence** (auteur tiers) —
vérifié à la source (dépôt cloné, workflows lus) :

- projet de référence **versionne un `debug.keystore` dans son dépôt**
  (`app/ide-android/src/main/assets/debug.keystore`), aux identifiants
  publics de convention Android (`android`/`androiddebugkey`,
  `CN=Android Debug,O=Android,C=US`) — son `DebugKeystore.kt` régénère à
  l'identique ces paramètres (PKCS12 *legacy* pour compatibilité ART) ;
- sa clé de **release** ne vit jamais dans le dépôt : échelle
  `keystore.properties` (gitignoré) → propriété Gradle `-PRELEASE_*` →
  variable d'environnement `RELEASE_*` (`keystore.properties.example`
  sert de modèle) ;
- le manifeste de l'image runner GitHub (actions/runner-images,
  Ubuntu 24.04) ne mentionne **aucun** `~/.android/debug.keystore` : le
  runner ne fournit PAS d'identité debug stable.

## Analyse

1. **Pourquoi l'atténuation ADR 0063 a échoué** : le cache
   `actions/cache` (clé fixe `codeide-keystore-debug-v1`) repose sur des
   propriétés qui ne tiennent pas dans notre mode de livraison —
   a. les caches sont **scopés par ref** : un cache sauvé pendant le run
   `refs/heads/main` n'est visible qu'après coup, et les **runs des tags
   poussés ensemble** (main + v0.36.x…v0.37.1 en une fois : 8 runs
   parallèles) partent tous en *cache miss*, chacun générant SA clé
   aléatoire ;
   b. l'**éviction** (7 jours d'inactivité, quota 10 Go sous la pression
   des caches Gradle de `setup-gradle`) peut retirer l'entrée à tout
   moment ;
   c. même en cas de hit, la clé du cache ne correspond à celle d'AUCUNE
   autre machine (bac à sable, poste de l'utilisateur) : un APK du portail
   et un APK du CI ne s'installent jamais l'un sur l'autre.
2. **Ce qu'est réellement une clé debug** : une identité de commodité, pas
   un secret. Les clés de test d'AOSP sont publiques par design ; les
   identifiants `android`/`androiddebugkey` sont une convention connue de
   tous les outils. La compromettre ne prouve rien — elle ne certifie
   aucune identité. La contraindre à la confidentialité n'apporte aucune
   sécurité mais CASSE la propriété essentielle d'une identité de test :
   être **stable et partagée** pour que les builds successifs se
   mettent à jour les uns sur les autres.
3. **La clé de release, elle, reste un secret** (identité de
   distribution) : elle ne doit vivre ni dans le dépôt ni dans les
   archives — d'où les deux régimes distincts.

## Décisions

1. **Identité debug publique, versionnée** :
   `config/signature/debug.keystore` est commis dans le dépôt (exception
   documentée à la règle 7 d'AGENTS.md et au `.gitignore` — tous les
   autres keystores restent ignorés). Paramètres : PKCS12 *legacy*,
   RSA 2048, validité 10 000 jours, alias `androiddebugkey`, mots de
   passe `android`, sujet `CN=Android Debug,O=Android,C=US` —
   exactement les paramètres du `DebugKeystore` du projet de référence et la
   convention Android Studio.
2. **Câblage** : le convention plugin `codeide.android.application`
   impose ce keystore à la signature de la variante debug (échec de
   configuration explicite s'il manque). La release n'est PAS concernée.
3. **Échelle release hors dépôt** (patron projet de référence) : résolution par
   champ dans l'ordre `config/signature/keystore.properties` (gitignoré,
   modèle `keystore.properties.example`) → propriété Gradle
   `-PRELEASE_*` → variable d'environnement `RELEASE_*`. Sans keystore
   résolu, la release reste non signée (inchangé).
4. **Verrou de non-régression** : `scripts/verify-signature.sh` compare
   l'empreinte SHA-256 du certificat signataire de l'APK produit à celle
   du keystore versionné ; le workflow CI l'exécute à CHAQUE run — un
   APK mal signé fait échouer le run. `verify-archive.sh` exige
   `config/signature/debug.keystore` dans l'archive (le build autonome le
   consomme) et interdit tout autre keystore.
5. **Retrait du cache keystore CI** (décision 3 de l'ADR 0063) : la clé
   voyage avec les sources, plus aucune dépendance à un état du runner.
   L'APK CI est renommé avec la version avant publication
   (`CodeIDE-vX.Y.Z-debug.apk`) pour distinguer les téléchargements.

## Conséquences

- **À partir de v0.37.2**, tous les APK debug — CI, bac à sable, poste
  contributeur — partagent la même identité : la v0.37.3 s'installe
  par-dessus la v0.37.2 sans conflit, partout, pour toujours.
- **Transition unique** : les APK ≤ v0.37.1 portent des signatures
  historiques aléatoires ; UNE dernière désinstallation est requise
  avant la première installation de v0.37.2. Ce coût n'est pas évitable
  (on ne peut pas resigner a posteriori les APK déjà installés).
- Perte ou régénération du fichier = changement d'identité = nouveau
  conflit unique : le fichier est versionné, ce scénario suppose une
  action volontaire (à éviter sans bump majeur).
- Une future distribution signée « release » (Play ou side-load sérieux)
  branchera l'échelle hors dépôt ; la CI peut recevoir les secrets
  `RELEASE_*` sans toucher au build.
- Le plan de reprise « si l'éviction mordait : secret
  `DEBUG_KEYSTORE_B64` » de l'ADR 0063 est OBSOLÈTE : le keystore
  versionné le rend inutile (et supérieur : il couvre aussi les machines
  locales, pas seulement le runner).
