# ADR 0220 — Kotlin Multiplatform : membres d'objets scannés, objets types référençables

- **Statut** : appliqué (25/10/2026 — découvertes du template « Kotlin
  Multiplatform » d'Android Studio : module `shared` à deux racines
  sources, `Fabrique.creer()` faux `kt.unresolved`, complétion
  `Fabrique.` muette, expect/actual vérifiés de bout en bout).
- **Contexte** : le propriétaire demande l'extension des tests aux
  projets MULTIPLATEFORME pour détecter les bugs non découverts. Le
  module KMP canonique vit dans DEUX racines sources — le paquet y
  existe DEUX fois (expect dans commonMain, actual dans androidMain).
  Le corpus de reproduction révèle :
  - **K1 — les formes de base tiennent déjà** : `expect fun`/`actual
    fun`, `expect class`/`actual class`, `expect val`, `actual
    typealias`, usages croisés des deux source sets, génériques sur un
    type expect, survol/définition de l'expect (les voies 3/4/5 du
    modèle K3/K4 couvrent les déclarations par nom) — la définition
    rend expect ET actual, l'expect D'ABORD (miroir Android Studio).
  - **K2 — membres d'OBJETS invisibles** : `SymbolesProjet.extraire`
    parcourt les membres des CLASSES (`KtClass.membres()`) mais la
    branche `else` — celle des `OBJECT_DECLARATION` — ajoute la
    déclaration de l'objet SANS JAMAIS PARCOURIR SON CORPS : tout
    membre d'`object` (le singleton Kotlin, `expect object` de toute
    lib KMP — le patron `Fabrique { fun creer() }`) est absent du
    scan → `Fabrique.creer()` est un faux `kt.unresolved`, la
    complétion `Fabrique.` est muette, le survol du membre est muet.
  - **K3 — objets non-référençables comme TYPES** : `classesProjet`
    (nom simple → FQCN, la table de `fqcnDuNomEcrit`) n'accepte que
    CLASSE et INTERFACE : `Objet.membre` ne type pas en statique
    (complétion membre impossible) — un objet nommé EST un type
    référençable en Kotlin (`val f: Fabrique`, `Fabrique.creer()`).
- **Options considérées** :
  1. **Réparer le scan + la table des types** (retenue) — branche
     dédiée `KtObjectDeclaration` avec parcours du corps (miroir
     exact de la branche classe), OBJET admis dans `classesProjet`.
  2. **Représenter les objets comme classes** (genre CLASSE au scan) :
     mentirait sur le genre (survol `object Fabrique`, symboles) —
     rejeté.
  3. **Résolution spécifique expect/actual par source set** (l'usage
     dans commonMain ne voit QUE l'expect, androidMain l'actual) :
     le modèle K3 résout par NOM sans source sets — la séparation
     exige le classpath Gradle par variante (K6+), et le
     conservatisme D3 n'en a pas besoin pour zéro faux positif —
     rejeté (documenté comme voie future).
- **Décision** : retenir l'**option 1** :
  - **K2** : `KtElements.KtObjectDeclaration` gagne `corps()` et
    `membres()` (même layout CLASS_BODY que `KtClass`) ;
    `SymbolesProjet.extraire` gagne la branche `is
    KtObjectDeclaration` — déclaration OBJET puis
    `parcourirConteneur(membres(), …, qualifieConteneur)` : les
    membres d'objet entrent dans le scan avec le FQCN de leur objet
    comme conteneur (clé de la complétion membre projet).
  - **K3** : `classesProjet` (SessionLangageKotlinParse) accepte
    `GenreDeclaration.OBJET` en plus de CLASSE/INTERFACE —
    `fqcnDuNomEcrit` résout les objets nommés, la complétion membre
    `Objet.` sonde les membres projet du conteneur.
- **Preuves** :
  - **Moteur** : `ReproductionKmpTest` (5 — expect fun/class/val,
    usages commonMain/androidMain, survol de l'expect, définition
    expect→actual) et `ReproductionKmpAvanceTest` (5 — expect val,
    expect object + membre, générique expect typé, actual typealias,
    survol du membre, complétion `Fabrique.` offrant `creer`).
  - **E2E production** (`ReproductionKmpE2ETest`, chaîne SessionLsp
    Java + index disque + vue fusion → SessionLspKotlin → serveur
    LSP4J + client espion, ClasspathResult RÉEL à DEUX sourceDirs) :
    module `shared` complet (commonMain + androidMain, kotlin-stdlib
    réel) → AUCUN faux positif dans commonMain, androidMain NI les
    usages des actuals ; complétion `Fabrique.` offrant `creer()` ;
    définitions de `platformName` = Platform.kt (commonMain)
    D'ABORD puis Platform.android.kt (miroir Android Studio) ; la
    typo `platformNaim` reste signalée (garde D3).
  - **Suites complètes vertes** : moteur 317, index 82, hôte 239
    (corpus Compose + Views + KMP branchés).
- **Conséquences** :
  - Le monde KMP rejoint Compose et Views au niveau de conformité
    Android Studio : zéro faux positif sur le template canonique
    multi-racines, sondages verrouillés par assertions.
  - BONUS hors KMP : tout singleton Kotlin (`object` nommé) voit ses
    membres complétés/survolés/résolus — le bug K2/K3 concernait
    TOUT projet Kotlin, pas seulement KMP.
  - Reste sous-rapporté (D3, documenté) : la sélectivité par source
    set (un nom nu résout par l'union expect+actual — jamais un faux
    positif, mais un VRAI négatif inter-source-set ne serait pas
    signalé avant la compilation Gradle) ; les objets anonymes et
    companions sans nom gardent le comportement historique du scan.
