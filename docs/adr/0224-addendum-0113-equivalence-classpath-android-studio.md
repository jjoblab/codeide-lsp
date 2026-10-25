# ADR 0224 — Addendum ADR 0113 : le classpath LSP est acquis comme celui d'Android Studio

- **Statut** : appliqué (25/10/2026 — question du propriétaire : « le
  serveur tooling donne aux LSP les classpaths sources ; n'est-ce pas
  la même chose que fait Android Studio (interroger l'AGP pendant la
  synchronisation) ? » Réponse : **OUI, et c'est un invariant
  d'architecture** — cet ADR l'établit sur pièce et en fixe les
  frontières exactes).
- **Contexte** : l'ADR 0113 (dépôt CodeIDE) a établi la partition des
  sources du classpath : modules JVM purs par `IdeaProject`, modules
  Android par les **modèles tooling v2 d'AGP**. Le rapport d'audit
  v1.12.0 a ensuite concentré l'attention sur les règles du MOTEUR de
  diagnostics (ADR 0216–0223), au point qu'une formulation réductrice —
  « le LSP reconstruit le classpath par inférence » — a pu circuler
  dans les échanges. Elle est **inexacte** pour le code applicatif, et
  elle doit être écartée une fois pour toutes : le classpath livré par
  le serveur tooling est celui que **Gradle et AGP eux-mêmes résolvent
  dans le démon**, par la même Tooling API qu'Android Studio.
- **Équivalence établie** (lue dans `codeide-tooling`, dépôt frère —
  `SyncHandler`, `ModelesSync`, `ModelesAndroidV2`) :

  | Ce qu'Android Studio fait à la sync | Ce que le serveur tooling fait | Identique ? |
  |---|---|---|
  | Connexion au démon Gradle du projet via la Tooling API | `GradleConnectorPool` → `ProjectConnection` (le démon du PROJET, pas un démon ad hoc) | **Oui** |
  | Interroge les modèles `BasicAndroidProject` (bootClasspath = android.jar du compileSdk, sourcesets java+kotlin, main + unitTest) | `ModelesAndroidV2.collecter` : le même modèle par projet du build | **Oui** |
  | Interroge `AndroidProject` par variante : dossiers générés (R, BuildConfig, KSP/kapt, ViewBinding) + dossiers de classes | Le même modèle, `generatedSourceFolders`/`classesFolders` de la variante retenue, livrés dans `fichiersGeneres` et les sorties | **Oui** |
  | Interroge le graphe de dépendances compile résolu (AAR **déjà extraits** par les transforms de Gradle) | `VariantDependencies` PARAMÉTRÉ (variante + drapeaux `dontBuild*`) : graphe `GraphItem`/`Library`, `compileJarFiles` du cache de transforms — mesuré sur le jeu « lourd » : 191 AAR, tous avec leur classes.jar extrait ; l'AAR d'origine voyage en provenance | **Oui** |
  | `IdeaProject` pour les modules JVM purs (dépendances typées) | Model builder APRÈS l'action (ADR 0113 : l'action proxifie mal les types) + `fusionner` par chemin de projet | **Oui** |
  | Choisit `debug` à la première sync | `choisirVariante` : `debug` d'abord (KDoc : « même choix qu'Android Studio à la première sync ») | **Oui** (première sync) |
  | Re-sync au changement de variante/flavor | Non : variante fixée à la sync courante | **Non** (écart assumé) |

- **Frontières précises de l'équivalence** — ce qui N'EST PAS acquis par
  le démon et reste du ressort de règles locales :
  1. **Scripts `.gradle.kts`** (ADR 0221) : AUCUN modèle tooling
     n'expose le classpath des scripts — le LSP découvre les jars de la
     distribution Gradle sur disque. C'est la SEULE zone « heuristique »
     d'acquisition, et elle est testée : le bug du runner CI
     (`gradle-kotlin-dsl-shared-runtime.PROPERTIES` empoisonnait la
     découverte — commit `542fcc1`) y a été trouvé par le harnais E2E.
  2. **Repli AGP ancien** : sans modèles v2, le module Android retombe
     sur sa conversion `IdeaProject` — mesurée VIDE pour les modules
     Android (26 modules, zéro racine source, zéro dépendance) : le
     repli est une DÉGRADATION assumée, pas une équivalence.
  3. **`unitTestSourceProvider` déprécié** : lu parce que les cartes
     `hostTestSourceProviders` d'AGP 9.4.1 sont vides — à revoir si AGP
     retire le déprécié.
  4. **Largeur des diagnostics** : la parité porte sur les ERREURS DE
     COMPILATION (le contrat LSP) — les inspections UAST/Android Lint
     d'AS sont hors périmètre.
- **Décision** :
  1. Faire de cette équivalence un **invariant documenté** : le
     classpath nourri aux LSP est, PAR CONSTRUCTION, celui qu'AGP
     résout dans le démon — toute évocation d'« inférence » ou de
     « reconstruction » pour le code applicatif est une erreur de
     vocabulaire à corriger à la relecture.
  2. Les heuristiques résiduelles sont **localisées et listées** (les
     quatre frontières ci-dessus) — chacune a sa porte de sortie :
     `.gradle.kts` (tests E2E sur la distribution), variante
     (choix à la sync), repli AGP (dégradation), Lint (hors contrat).
  3. Tout nouveau « monde » de test E2E doit nourrir le LSP de
     classpaths **miroir de la normalisation v2** (jars extraits,
     provenance AAR, `fichiersGeneres` réels) — c'est le contrat de
     fidélité des tests utilisateurs.
  4. Surveiller la dépréciation AGP (`unitTestSourceProvider`, modèles
     v2) : l'équivalence doit être REVÉRIFIÉE à chaque montée de
     version d'AGP — un test de sonde par version, pas une présomption.
- **Preuves** :
  - `SyncHandler.executerAction` : l'action unique dans le démon +
    `IdeaProject` par model builder (commentaires : partition v8,
    ADR 0113).
  - `ModelesAndroidV2.collecter/convertir/entrerGraph` : les trois
    modèles v2 par projet, paramétrage `dontBuild*` complet (NPE du
    proxy si incomplet — sonde du banc), AAR normalisés en jars
    extraits + provenance.
  - `fusionner` : prééminence du modèle v2 Android par chemin (le
    constat §2.1 : IdeaProject vide pour les modules Android).
  - `codeide-lsp` commit `542fcc1` : la frontière `.gradle.kts` est
    réelle, testée, et son bug a été trouvé en CI — preuve que la
    zone heuristique est cernée.
- **Conséquences** :
  - Le rapport d'audit et le document d'intégration CodeIDE portent
    désormais cet argument d'architecture (défense en entretien : «
    même démon, mêmes modèles, même normalisation »).
  - Les tests utilisateurs (programme du 25/10/2026) héritent du
    contrat de fidélité n° 3 — leurs classpaths miment la v2, leurs
    conclusions portent sur le MOTEUR, pas sur l'acquisition.
