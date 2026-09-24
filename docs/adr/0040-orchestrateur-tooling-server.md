# ADR 0040 — Orchestrateur du tooling Gradle (G2) : tooling:api + tooling:server

- **Statut** : accepté (étape G2, v0.27.0)
- **Contexte** : prompt compagnon Tooling, sections 2, 4, 7.2 et 9 — après
  le protocole figé de G1, l'orchestrateur JVM qui traduit ce protocole en
  appels à la Gradle Tooling API. Il doit être testé en isolation sur JVM
  de bureau/CI, sans Android (§7.2), et livré en JAR unique exécutable
  embarqué dans les assets de l'app (§4.7).
- **Décisions** :

1. **`tooling:api` créé avec G2** (§2.1/2.2 : server en dépend). Modèles du
   projet indépendants du format câble (`LigneSortieBuild`, `EtatBuild`,
   `InstantaneTas`, `EtatConnexion`, `InfoTache`, `Diagnostic`,
   `ModeleProjet`/`ModeleModule`) + mappers protocol → api, frontière
   unique : le client Android (G3) n'interprétera jamais un message brut.
   Réutilise les enums du protocole (`StreamKind`, `DiagnosticSeverity`) —
   aucune duplication.

2. **`ModelRequest` répond par `SyncResult`** : le catalogue figé de G1 (24
   messages, fichiers dorés) ne prévoit pas de `ModelResult` dédié, et la
   sémantique de « demander un modèle de la Tooling API » est celle de la
   synchronisation. `ModeleProjet` (construit depuis `IdeaProject`, consigné
   au journal) servira les évolutions LSP — transport à définir alors.

3. **`DependenciesResult` liste les dépendances INTER-PROJETS**
   (`IdeaModuleDependency.getTargetModuleName` + portée) : c'est ce que
   « module + configuration » désigne au §3.2 ; les dépendances externes de
   fichiers/bibliothèques ne portent pas de « module » au sens du protocole
   et le sélecteur d'exécution n'en a pas l'usage.

4. **Diffusion sans perte, côté serveur aussi** (§5.2 adapté) :
   `EventBusSocket` = file bornée 8192 + `put()` BLOQUANT — la
   contre-pression s'applique aux fils de pompage de sortie de Gradle
   (contrôle de flux légitime), un unique fil consommateur écrit le socket
   (aucune frame entrelacée). Jamais `DROP_OLDEST`.

5. **Timeouts de garde serveur** (§7.5) : build 30 min, sync 5 min, tâches
   30 s, dépendances 30 s, modèle 5 min — le dépassement devient
   `ErrorResponse(TIMEOUT)` ; l'annulation d'un build en délai annule le
   jeton Tooling API et publie un `BuildFinished` en échec.

6. **Assemblage et empaquetage** (§4.7) : convention `codeide.tooling.server`
   (build-logic) applique `com.gradleup.shadow` **9.6.1** (successeur maintenu
   de johnrengelman — fin de vie ; le fork CONSERVE le package historique
   `com.github.jengelman.gradle.plugins.shadow`, leçon de G2) ;
   `gradle-server.jar` (Main-Class `ServerMain` — `main()` vit dans un
   `object` avec `@JvmStatic`, pas un `ServerMainKt` top-level, leçon de G2)
   + `mergeServiceFiles`. Le JAR est un ARTEFACT DE BUILD : recopié vers
   `app/src/main/assets/tooling/` par `copierJarVersAssets`, contrôlé
   (présent + non vide) par `controlerJarAssets` branché sur `preBuild` de
   l'app — liaison par TÂCHE, pas par dépendance de module (l'app ne
   consomme le tooling que via le domaine, §2.2). Gitignore + exclusions
   package.sh/verify-archive.sh : jamais de binaire généré versionné ni
   archivé, chaque build le régénère (autonomie de l'archive prouvée par
   verify-archive).

7. **`org.gradle:gradle-tooling-api` 9.7.1** résolue depuis
   `repo.gradle.org/gradle/libs-releases` (ajouté au
   `dependencyResolutionManagement` de settings.gradle.kts — les métadonnées
   Maven Central de cette coordonnée sont périmées, 7.3-snapshot de 2021) ;
   version ALIGNÉE sur le Gradle du wrapper. Surfaces d'API vérifiées par
   javap avant écriture (`TaskOperationResult` ne porte AUCUNE information
   d'échec — la réussite d'une tâche se lit sur
   `SuccessResult`/`SkippedResult`/`FailureResult`).

8. **Journalisation du process séparé** (règle 14) : l'orchestrateur ne
   peut pas appeler AppLogger — sa sortie d'erreur EST son canal (tag
   `gradle-server`, §6 du prompt : le daemon la réinjectera). Exemption
   detekt ciblée `detekt-serveur-tooling.yml`, rien d'autre n'y est levé.

9. **Fixtures : daemons bornés** — `org.gradle.jvmargs=-Xmx512m` ajouté aux
   4 fixtures (environnement 4 Go partagé avec le build lui-même) ;
   `FixturesGradle` monte le système de fichiers zip quand les ressources
   vivent dans le jar d'une dépendance de test (`FileSystemNotFoundException`
   sinon — leçon de G2).

- **Alternatives rejetées** : gRPC/Protobuf (§1, déjà tranché en G1) ·
  pool de fils dédié pour `runBuild` (§4.3 : Gradle gère son threading) ·
  `DROP_OLDEST` sur la file d'événements (perdre des lignes de build serait
  trompeur) · tag Git du JAR dans les assets (binaire généré, jamais
  versionné).

- **Conséquences** : APK debug ~17 Mo (JAR orchestrateur 7,6 Mo embarqué) ;
  `ServerVersion.CURRENT` alignée manuellement à chaque étape ; l'app
  builder exige `repo.gradle.org` (CI GitHub incluse).
