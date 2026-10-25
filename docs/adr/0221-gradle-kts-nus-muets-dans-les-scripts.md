# ADR 0221 — Fichiers .gradle.kts : nus muets dans les scripts, segments qualifiés faillibles

- **Statut** : appliqué (25/10/2026 — demande explicite du propriétaire
  « Je veux aussi réglé les fichiers gradle .kts » ; constat : le
  build.gradle.kts du template « Empty Views Activity » produisait
  **41** faux `kt.unresolved` et le settings.gradle.kts **17**).
- **Contexte** : les fichiers Gradle Kotlin DSL sont des SCRIPTS
  Kotlin dont les identifiants viennent du CONTEXTE HÔTE — le DSL
  Gradle injecte les récepteurs implicites (`plugins`, `android`,
  `dependencies`, `buildTypes`, `implementation`, `alias`…), le
  version catalog (`libs`), et les imports automatiques du DSL
  (`JavaVersion`, `RepositoriesMode`, `rootProject`…). Le moteur ne
  modélise PAS le DSL Gradle (pas de classpath Gradle) : chaque nom
  nu était un faux `kt.unresolved` — un tsunami de 58 diagnostics
  sur les deux scripts canoniques du template.
  Android Studio/IntelliJ SANS modèle Gradle ne signale RIEN sur
  ces scripts (l'absence n'y est pas prouvable) ; AVEC modèle, tout
  résout.
- **Options considérées** :
  1. **Règle script asymétrique** (retenue) — dans un document
     `.kts` (le parseur a déjà son mode SCRIPT), un nom NU ne peut
     pas être prouvé absent : le récepteur implicite du script est
     le contexte hôte. Muet. Les segments QUALIFIÉS écrits restent
     faillibles.
  2. **Table du vocabulaire Gradle** (plugins/android/dependencies…)
     : liste blanche fragile, fausse pour les DSL tiers et les
     versions — rejetée.
  3. **Taiger TOUT diagnostic dans les scripts** : perdrait la garde
     D3 sur les typos qualifiées (`java.io.Fichier`) — rejeté au
     profit de la distinction nu/qualifié.
  4. **Étendre les imports par défaut des scripts** (java.io…,
     miroir kotlinc script) : sans effet sur `libs`/`plugins` (pas
     des imports, des récepteurs injectés) — rejeté (inutile ici).
- **Décision** : retenir l'**option 1** :
  - `DiagnosticsSemantiques.Contexte` porte `script: Boolean`
    (la session le passe depuis le document — le serveur LSP
    détermine déjà `.kts` à l'ouverture comme à la modification).
  - Dans `referencesNonResolues`, après les gardes existantes :
    un document SCRIPT ne diagnostique PAS la référence si
    `estNuDansScript` — la référence est soit SANS ancêtre
    expression qualifiée (`plugins`, `implementation`,
    `getDefaultProguardFile`…), soit le PREMIER SEGMENT (récepteur
    racine) de la chaîne qualifiée la plus proche (`libs`,
    `JavaVersion`, `rootProject`, `repositoriesMode`…), soit un
    TYPE SIMPLE non qualifié (`String`, `JavaVersion` en type).
    Les SEGMENTS de TYPES QUALIFIÉS gardent le régime E4/E4bis
    (`zz.qqq.Inconnu` : le dernier segment reste signalé), et les
    SÉLECTEURS au-delà du premier segment gardent leur règle
    propre (`estSelecteurQualifie`) — **aucune perte de vrai
    négatif** : les sélecteurs déjà muets ne le deviennent pas
    davantage, seuls les récepteurs racines et nus le deviennent.
- **Preuves** :
  - **Moteur** : `ReproductionGradleKtsTest` (5 tests) — le
    build.gradle.kts ET settings.gradle.kts du template → 0 faux
    positif ; le Kotlin STANDARD du script résout (import, locales,
    chaînes stdlib) avec survols ; la garde D3 : `java.io.Fichier`
    reste signalé dans un script.
  - **E2E production** (`ReproductionGradleKtsE2ETest`, chaîne
    SessionLsp Java + index + vue fusion → SessionLspKotlin →
    serveur LSP4J + client espion) : le build.gradle.kts du template
    ouvert dans le serveur → AUCUN faux positif ; la typo qualifiée
    reste signalée.
  - **Suites complètes vertes** : moteur 322, index 82, hôte 240
    (corpus Compose + Views + KMP + script Gradle branchés).
- **Conséquences** :
  - Les fichiers .gradle.kts sont « réglés » : aucun faux positif,
    le Kotlin du script (imports, locales, stdlib, types qualifiés)
    garde diagnostics, survol, complétion.
  - Compromis D3 documenté : dans un script, une typo sur un nom NU
    (`printlnn`) n'est PAS signalée — le contexte hôte peut la
    porter ; la typo sur un segment QUALIFIÉ reste signalée. Les
    `.main.kts` partagent ce régime (leurs noms nus viennent des
    arguments du script).
  - La complétion dans les scripts garde son régime existant (le
    Kotlin standard s'offre ; les récepteurs Gradle restent
    sous-rapportés tant qu'un modèle Gradle n'est pas indexé —
    voie future K6+).
