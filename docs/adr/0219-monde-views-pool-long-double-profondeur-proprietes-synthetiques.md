# ADR 0219 — Monde non-Compose (Views) : pool de constantes Long/Double, profondeur de chaîne, propriétés synthétiques Java, `super.` et ViewBinding K5

- **Statut** : appliqué (25/10/2026 — découvertes du template « Empty
  Views Activity » et de sa forme ViewBinding : `getString`/`layoutInflater`
  faux `kt.unresolved`, complétion `binding.` muette, supertest
  d'un corpus réel de 27 artefacts AAR).
- **Contexte** : cinq familles d'ÉCARTS avec le LSP Kotlin officiel
  d'Android Studio, découvertes sur le template NON-Compose (vues XML,
  AppCompat, ViewBinding — le deuxième monde Android après Compose) :
  - **V1 — classes SANS membres dans l'index disque (le bug des 8
    octets)** : `AnalyseurClassFile.lirePoolConstantes` (ADR 0111 §5
    `java.members`) incrémentait l'index du pool pour les constantes
    Long/Double (deux slots) mais ne SAUTAIT PAS les 8 octets de leur
    valeur — la constante suivante se lisait DANS la valeur : tag
    illisible, `null` rendu, et la classe PERDAIT TOUTE sa section
    membres. `android.content.Context` — la SEULE classe de la chaîne
    AppCompat portant une constante `long` — était muette : `getString`,
    `getColor`, `getDrawable`, `getPackageName`… introuvables, et
    TOUTE classe du classpath avec un `long`/`double` (fréquent en
    Android : `Configuration`, `IPackageManager`…) subissait le même
    sort. Le correctif du lecteur ne suffit PAS : les segments v1
    déjà écrits portent un CRC VALIDE de contenu FAUX — seul le saut
    de version du format (1→2, ré-indexation paresseuse) les invalide.
  - **V2 — chaîne de supertypes COUPÉE avant Context** :
    `BORN_PROFONDEUR = 6` dans `ResolutionKotlin.chaineSupertypes`
    arrêtait la hiérarchie Android RÉELLE à ContextWrapper —
    AppCompatActivity → FragmentActivity → activity.ComponentActivity
    → core.ComponentActivity → Activity → ContextThemeWrapper →
    ContextWrapper → **Context** exige PROFONDEUR 7 : même avec V1
    corrigé, `getString` restait un faux `kt.unresolved`.
  - **V3 — PROPRIÉTÉS SYNTHÉTIQUES Java absentes** : l'interop cœur
    du langage (un getter Java `getFoo()` se lit `foo`) n'existait
    pas — `layoutInflater` nu dans une Activity (getter
    `Activity.getLayoutInflater()`), `title = …` (setter
    `Context.setTitle()`)… tous faux `kt.unresolved`, alors que la
    complétion Android Studio les met EN TÊTE de ses listes.
  - **V4 — `super.` et `this.` typés comme la classe PROJET** : la
    classe englobante (`MainActivity`) est INVISIBLE de la vue
    classpath — la complétion/survol après `super.`/`this.` ne voyait
    QUE les membres déclarés du projet, JAMAIS la chaîne binaire du
    supertype écrit (100 items Android Studio attendus).
  - **V5 — ViewBinding K5 hors de son paquet** : AGP génère TOUJOURS
    les bindings dans le sous-paquet `databinding` (ViewBinding
    compris) ; `ClassesSynthetiques.groupeBinding` ne le faisait que
    pour le DataBinding — l'import réel du template
    (`com.ex.app.databinding.ActivityMainBinding`) ne matchait JAMAIS
    le groupe : complétion `binding.` muette.
- **Options considérées** :
  1. **Correctifs convergents, chacun borné à sa couche** (retenue) —
     lecteur + version de format (index), borne + budget (résolveur),
     synthèse d'accesseurs partagée (résolveur, 5 consommateurs),
     typage par clauses écrites (expression), convention AGP (K5).
  2. **Re-parse à la volée des classes au lieu de l'index** : coût par
     requête et contournement de l'extension `java.members` — rejeté
     (l'index est la source de vérité ADR 0111).
  3. **Synthèse des propriétés côté index (écrire `foo` comme
     membre)** : dupliquerait la sémantique Kotlin dans le format
     disque JAVA — rejeté (la synthèse est une décision de LANGAGE).
  4. **Typage `super.` par la classe englobante seule** (comme le
     premier jet) : muet dès que la classe projet n'est pas dans la
     vue — rejeté au profit des clauses ÉCRITES (même source de
     vérité que la voie 2-ter ADR 0216).
- **Décision** : retenir l'**option 1** :
  - **V1** : `lirePoolConstantes` saute les 8 octets (`skipBytes`) ;
    `FormatSegment.VERSION` passe à **2** — les segments v1 sont
    refusés par le lecteur (version divergente) et re-indexés
    paresseusement ; le test « Long/Double deux slots » est RENFORCÉ
    (classe forgée AVEC un long dans le pool — l'ancienne version ne
    forgeait jamais le cas).
  - **V2** : `BORN_PROFONDEUR` 6 → **12** (marge pour les arbres
    d'interfaces) et budget total `BORN_MAILLONS = 256` (défense des
    hiérarchies pathologiques) ; l'ordre DFS visite la superclasse
    AVANT les interfaces — la chaîne de classes reste prioritaire
    dans la liste aplatie (miroir de la résolution Java).
  - **V3** : `ResolutionKotlin` gagne le catalogue d'accesseurs —
    `getterSynthetique` (`get`/`is` + majuscule, zéro paramètre,
    retour non void, public/protégé, non statique, `getClass`
    exclu), `setterSynthetique` (`set` + majuscule, un paramètre),
    `accesseurSynthetique` (prédicat du diagnostic) et
    `proprieteSynthetiqueDe` (direction miroir `getLayoutInflater` →
    `layoutInflater`, `getURL` → `URL` — deux majuscules de suite
    restent, patron JavaBeans/kotlinc). Consommateurs :
    la voie 2-ter et le récepteur `with` du diagnostic, le typage des
    sélecteurs ET du this implicite (`typeMembreImplicite`) et des
    appels nus (repli de `typeAppel`), la complétion membre (offre
    `val layoutInflater: LayoutInflater` — le champ/méthode RÉEL du
    même nom passe toujours en premier, la déduplication garde le
    premier porteur), le survol qualifié ET le nouveau survol
    IMPLICITE (`val layoutInflater: LayoutInflater —
    android.app.Activity`, `var` quand le setter existe).
  - **V4** : `TypageExpression` expose `fqcnsSupertypesEnglobants`
    (clauses ÉCRITES de la classe englobante résolues par les
    imports — miroir de `estMembreHerite`) : `SUPER_EXPRESSION` se
    TYPE comme le PREMIER supertype écrit (repli classe englobante
    pour les objets sans clause) ; la complétion membre d'un
    récepteur `this`/`super` sonde la classe + ses supertypes
    écrits (porteurs) — membres binaires, membres PROJET et
    extensions sur TOUS les porteurs ; les autres récepteurs
    (`binding.`) gardent leur type SEUL (jamais de membres de la
    classe englobante sur un récepteur étranger).
  - **V5** : `groupeBinding` place TOUJOURS le binding dans
    `<paquet>.databinding` (convention AGP réelle, ViewBinding et
    DataBinding confondus).
- **Preuves** :
  - **Moteur** : `ReproductionViewsTest` (11 tests — chaîne factice
    des 8 maillons Android réels : `getString` du maillon 7,
    `layoutInflater`/`packageName` synthétiques, `title =` par
    setter, typo toujours signalée, getter À PARAMÈTRE qui ne
    synthétise PAS, survols implicites/qualifiés avec propriétaire,
    chaîne `layoutInflater.inflate`, complétion `this.`) et
    `ReproductionViewBindingTest` (récepteur PROPRIÉTÉ typée,
    complétion `root`/`textView`/`inflate`/`bind` du groupe K5).
  - **E2E production** (`ReproductionViewsE2ETest`, chaîne
    SessionLsp Java + index disque ADR 0111 + vue fusion →
    SessionLspKotlin → serveur LSP4J + client espion, classpath
    RÉEL de 27 artefacts — kotlin-stdlib 2.2.10, android-36, les
    classes.jar d'appcompat 1.7.0, fragment 1.8.5, activity 1.9.3,
    core 1.15.0, core-ktx, lifecycle, savedstate, material 1.12.0,
    constraintlayout 2.2.0… variable `REPRO_VIEWS_DIR`) :
    template « Empty Views Activity » → AUCUN faux positif ; vrai
    négatif (`setContenuVeu`) TOUJOURS signalé (garde D3) ; forme
    ViewBinding → AUCUN faux positif ET assertions de capacités :
    survol `layoutInflater` (synthétique), survol `getString`
    (maillon 7), complétion `binding.` offrant root/textView,
    complétion `super.` à 100 items (dont les propriétés
    synthétiques `theme`, `title`, `assets`…).
  - **Index** : `AnalyseurClassFileTest` renforcé — la classe forgée
    AVEC un long dans le pool garde ses membres ; les suites
    complètes restent vertes : moteur 307, index 82, hôte 238
    (corpus Compose ET Views branchés).
- **Conséquences** :
  - Le monde non-Compose (vues XML) est au niveau de conformité
    Android Studio du monde Compose : zéro faux positif sur les
    deux templates canoniques, sondages de capacités par assertions.
  - La montée de VERSION du format segment invalide les index v1 au
    premier démarrage (re-indexation paresseuse, coût unique).
  - Les classes `long`/`double` du classpath retrouvent leurs
    membres dans TOUTES les fonctionnalités (Java compris — la
    complétion membre Java consomme la même extension).
  - Reste sous-rapporté (D3, documenté) : sélection d'appelé par
    types complets (sélecteurs qualifiés jamais diagnostiqués),
    définitions classpath vides (contrat K3/D3).
