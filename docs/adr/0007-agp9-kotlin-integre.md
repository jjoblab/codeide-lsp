# ADR 0007 — AGP 9 avec Kotlin intégré et chaîne de versions vérifiée

- **Statut** : accepté (étape 0)
- **Contexte** : le projet démarre en septembre 2026. La ligne stable
  actuelle d'Android Gradle Plugin est la 9.4.x ; AGP 9 introduit le support
  Kotlin **intégré** (*built-in Kotlin*) activé par défaut : le plugin
  `org.jetbrains.kotlin.android` n'est plus appliqué (il est même incompatible
  avec la nouvelle DSL), kapt est incompatible, et la configuration passe par
  `kotlin { compilerOptions { } }`. AGP 9.4.1 embarque KGP **2.2.10** — c'est
  la version de Kotlin officiellement testée pour le built-in. Parallèlement,
  les bibliothèques kotlinx récentes sont compilées avec des Kotlin plus
  récents (ex. kotlinx-serialization 1.10+ est compilé avec Kotlin 2.3 et
  devient **illisible** pour un compilateur 2.2).
- **Décision** :
  1. Adopter **AGP 9.4.1 + Gradle 9.7.1 (wrapper) + JDK Temurin 21** ;
     configurer le Kotlin **intégré** via les convention plugins
     (`kotlin { compilerOptions { allWarningsAsErrors } }`), ne jamais
     appliquer `kotlin-android`, tout traitement d'annotations passe par
     **KSP 2.3.12** (versionnement indépendant, compatible AGP 9).
  2. Figer **Kotlin 2.2.10** partout (y compris modules JVM purs) : c'est la
     version embarquée par AGP 9.4.1 ; monter à 2.4.x exigerait de revalider
     toute la chaîne et casserait la lecture des métadonnées compilées.
  3. Figer **kotlinx-serialization 1.9.0** (dernière compilée pour Kotlin
     2.2) et vérifier pour chaque dépendance la stdlib minimale requise.
  4. Le module `core:domain` (JVM pur) utilise `org.jetbrains.kotlin.jvm`
     2.2.10 et peut consommer `javax.inject` (autorisé par la section 5.2).
  5. Lint : désactivation ciblée et commentée des seuls conseils de fraîcheur
     (`NewerVersionAvailable`, `GradleDependency`) car les versions plus
     récentes qu'ils proposent **casseraient la compatibilité** ci-dessus.
- **Vérifications effectuées** (avant toute écriture de code de projet) :
  versions relevées sur Google Maven / Maven Central / Plugin Portal ;
  combinaison validée empiriquement sur un projet pilote (build + tests
  Robolectric + Hilt/KSP/Room + detekt/spotless), puis sur le projet réel.
- **Conséquences** :
  - base moderne et supportée ; pas de dette kapt ; avertissements Kotlin en
    erreurs dès le premier jour ;
  - la montée de version de Kotlin est un acte **architectural** (elle
    dépend d'AGP et des métadonnées des dépendances) : toujours la vérifier
    sur les dépôts et la revalider par un build complet avant de la figer ;
  - particularités à connaître (documentées dans `docs/ENVIRONNEMENT.md`) :
    plugins déclarés `apply false` à la racine (sinon erreur de chargeur de
    classes Spotless), `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`
    pour Robolectric, `isIncludeAndroidResources = true`, et l'impossibilité
    d'appliquer KSP à un module **sans test** (répertoires de sortie vides
    que Gradle 9 considère comme des sources) — les conventions
    `feature`/`hilt`/`room` ne s'appliquent donc qu'aux modules qui ont du
    code.
