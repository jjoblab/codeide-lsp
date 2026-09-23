# ADR 0037 — Intégration continue GitHub Actions et procédure de vérification allégée

- **Statut** : accepté (étape 24 = Terminal T6, v0.25.0)
- **Contexte** : demande utilisateur à l'étape T6 — « ajouter un workflow
  GitHub pour pouvoir compiler le projet depuis GitHub », et constat que
  chaque étape passait des heures en vérification alors que les
  modifications sont souvent minimes. La publication GitHub depuis la
  sandbox reste impossible (pas d'authentification, signalé depuis
  l'étape 16) : c'est l'utilisateur qui pousse — mais rien ne vérifiait
  le dépôt poussé.

## Décision

1. **Workflow `ci.yml`** (`.github/workflows/ci.yml`) : sur `push`
   (branches `main` + tags `v*`), `pull_request` et déclenchement
   manuel. Ubuntu-latest + JDK 21 Temurin + SDK Android du runner
   (licences déjà acceptées, AGP auto-installe la plateforme manquante),
   cache Gradle par `gradle/actions/setup-gradle`. La chaîne exécutée
   est **la vérification complète du projet sans `clean`** :
   `spotlessCheck detekt checkModuleDependencies lintDebug
   testDebugUnitTest koverVerify assembleDebug`. L'APK debug est
   téléchargeable en artefact du run ; les rapports ne sont publiés
   qu'en cas d'échec.
2. **Les réglages mémoire séquentiels de `gradle.properties` sont
   conservés tels quels sur CI** (workers=1, pas de parallélisme,
   tas borné) : le runner GitHub (7 Go) tolérerait plus, mais le
   workflow ne dérègle rien — un réglage CI divergent serait un
   deuxième environnement à maintenir. Le run est plus lent, jamais OOM.
3. **La vérification locale s'allège : plus de `clean` systématique.**
   Mesures empiriques à l'étape T6 (detekt 1.23.8, caches Gradle
   activés depuis l'étape 0) :
   - detekt sans changement : **1,6 s** (22 tâches up-to-date) ;
   - detekt après modification d'un module : **27 s** (seules les
     tâches du module touché se réexécutent) ;
   - detekt après `clean` : **6,5 s** (22 tâches **from cache** — le
     build cache restitue tout) ;
   - detekt lui-même n'a **pas** d'analyse incrémentale par fichier :
     une tâche réexécutée relit tout le source set du module — mais la
     granularité par module suffit à amortir, et le cache Gradle
     survit au `clean`.
   Conclusion : le `clean` historique n'apportait plus rien (le build
   cache et le cache de configuration font mieux), il coûtait une
   reconstruction complète. La procédure devient : **vérifications
   ciblées par module pendant le développement, chaîne complète sans
   `clean` avant chaque livraison** — la compilation from-scratch reste
   garantie par le workflow GitHub au push.
4. **Le verrou de conformité reste le même** : la chaîne complète verte
   avant commit de livraison. Rien n'est retiré, seul l'artefact
   « repartir de zéro » passe du sandbox (lent, monopolisé des heures)
   au runner GitHub (parallèle à la sandbox).

## Alternatives rejetées

- **CI release (R8) + signature** : la signature reste locale (clé
  absente de la sandbox) — hors périmètre de ce premier workflow.
- **Un job par module (matrice)** : la plupart des vérifications sont
  déjà séquentielles par conception mémoire ; découper ferait payer N
  démarrages de démon Gradle pour un projet de 20 modules.
- **Overrider `gradle.properties` dans le workflow** : un deuxième jeu
  de réglages à maintenir et à justifier — refusé tant qu'un besoin
  réel ne se présente pas.

## Conséquences

- L'utilisateur doit **pousser le dépôt** (`git push origin main
  --follow-tags`) pour activer la CI — les tags v0.25.0+ sont vérifiés
  à la source.
- La procédure « vérification complète » documentée dans AGENTS.md
  perd son `clean` ; les sessions futures gagnent typiquement 10 à
  15 minutes par étape, davantage quand les modifications sont minimes.
- Un échec CI se voit dans l'onglet Actions du dépôt GitHub (artefacts
  de diagnostic publiés automatiquement).
