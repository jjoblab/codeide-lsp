# ADR 0112 — D5 : plateforme de compilation — android.jar seul pour les modules Android

- **Statut** : accepté (v0.91.0, étape 0 — prompt 2 §3 D5)
- **Contexte** : pour compiler/résoudre un module **Android**, la
  plateforme est le `android.jar` du compileSdk (boot classpath).
  L'ancien code (`CompilationEnvironment`, `JdtCompilerEnvironment`)
  branche À LA FOIS `android.jar` et l'image `jrt` du JDK (`jdkHome`,
  scan paresseux `scanJrtIfNeeded`) : l'image `jrt` peut introduire des
  API ABSENTES d'Android (`java.net.http`, `java.util.stream` étendus…)
  et des classes en double avec `android.jar` — sources de « faux vert »
  en complétion et de `LinkageError` à l'exécution.

## Décision

1. **Modules Android : `android.jar` SEUL en plateforme.** Le `jrt`
   n'entre JAMAIS dans le classpath d'un module Android. Le JDK reste
   utile pour :
   - les **modules JVM purs** (jrt, comme aujourd'hui) ;
   - les **sources de `java.*`** pour la navigation/Javadoc
     (`lib/src.zip` du JDK du bootstrap quand présent — le banc a
     vérifié sa présence dans l'installation JDK du terminal) ;
   - exécuter le démon de tooling.
2. **Niveau de conformité PAR MODULE** (issu du modèle d'outils quand
   disponible), pas une constante globale : l'ancien code fige « 17 »
   par défaut pour tout le projet (W5).
3. **Détection de collision** : `lsp:model` signale un doublon
   `jrt`/`android.jar` comme avertissement de module, jamais silencieux.
4. **Piste retenue pour ecj-art** (amélioration justifiée par l'analyse,
   à planifier à l'étape 3) : une tâche de build qui échoue si un jar
   patché référence une API absente du minSdk 26 — détecter les
   `LinkageError` d'ART en intégration continue plutôt que sur appareil.

## Preuves de l'étape 0

- Vérification dans le code vendored : branchement double confirmé
  (`findType` tente snapshot → jars → jrt ; `jrtAvailable` détecté sur
  `lib/modules`) ; le scan jrt coûte ~20–37 ms à froid sur JVM de bureau
  (banc, phase A) — faible sur bureau, mais le RISQUE FONCTIONNEL
  (API absentes, doublons) est réel sur ART.
- Le banc « lourd » (250 jars dont `android.jar` 41,8 Mo) tourne avec
  `jdkHome` NON nul : reproduire à l'étape 3 le même banc avec
  `jdkHome=null` pour isoler la contribution du jrt sur ART.

## Conséquences

- `lsp:model` (étape 1) type la plateforme par module :
  `Android(cheminAndroidJar)` | `Jvm(jdkHome)` — pas de « les deux ».
- La conformité par module supprime le repli silencieux « 17 ».
- Les tests de non-régression portés (`AndroidClasspathReproTest`,
  `RealClasspathDeviceReproTest`) restent la garde sur ce comportement.
