# ADR 0225 — Univers incomplets : couper la preuve d'absence (étape B)

- **Statut** : appliqué (26/10/2026 — étape B de la mission fiabilisation ;
  les 5 correctifs priorisés §7 du rapport `docs/RAPPORT-FAUX-POSITIFS-2026-10-26.md`,
  chacun verrouillé par son test de reproduction A.2 dont les assertions
  sont INVERSÉES par le correctif — TDD maison : la reproduction PRÉCÈDE
  la correction).
- **Contexte** : l'étape A a mesuré CINQ familles de faux `kt.unresolved`
  où le moteur prouve une ABSENCE sur un univers INCOMPLET ou un symbole
  hors de portée du modèle — toutes violations du contrat conservateur
  D3 (« jamais de faux positif ») :
  1. **H1 — troncation du scan** : la marche s'arrête à
     `BORN_FICHIERS = 2000` SANS compter au-delà (`StatsPassage.total`
     borné : la troncation est indétectable côté bilan ADR 0213) et le
     drapeau `pret` est posé sur un passage TRONQUÉ (ADR 0212 : « un
     résultat partiel est un état abouti ») → `estUniversConnu()` rend
     VRAI → les voies 4/5 de `estResolu` (déclarations et classes du
     PROJET) prouvent l'absence sur un univers amputé : une référence à
     un type déclaré derrière la borne est un FAUX positif.
  2. **Intrinsèques du compilateur** (§3.1) : `arrayOf`, `intArrayOf`
     (et la même famille vérifiée : `*ArrayOf` signés et non signés,
     `enumValues`/`enumValueOf`) sont `@InlineOnly` SANS AUCUN miroir
     bytecode dans kotlin-stdlib (vérifié sur 2.2.10 :
     `kotlin.ArrayIntrinsicsKt` ne porte QUE `emptyArray`,
     `kotlin.enums` n'a même pas de classe `EnumsKt`) → l'index des
     appelables ne peut JAMAIS les connaître.
  3. **`Enum.entries`** (§3.2) : Kotlin 1.9+ génère un getter
     `getEntries()` STATIQUE sur toute classe d'enum (vérifié :
     `kotlin.io.FileWalkDirection`) ; `getterSynthetique` n'acceptait
     que les accesseurs NON statiques (« les propriétés synthétiques
     statiques n'existent pas en Kotlin ») → absence prouvée à tort
     (ADR 0223) sur TOUTE écriture `MonEnum.entries`.
  4. **H2 — sources générées** : le scan EXCLUT `build/` (ADR 0212 :
     le scan est le SOURCE) — exactement là où vivent les types
     générés KSP/Hilt (`Hilt_MainActivity`, parent d'un
     `@AndroidEntryPoint`), Room (`_Impl`), DataBinding
     (`BindingImpl`). Les racines générées viennent du TOOLING après
     un build (`EnsemblesSources.generes` — « vides sans build
     préalable ») : sans build, la référence au parent généré est un
     FAUX positif.
  5. **H3 — imbriquées Java de niveau ≥ 3** : `SourcesJava` ne
     parcourait que le niveau 1 des corps — l'imbriquée de niveau 2
     résolvait (par le repli PROPRIÉTÉ membre, vague 2), le dernier
     segment d'un type qualifié de niveau 3 (`Aquarium.Bac.Couloir`)
     était introuvable → FAUX positif en position de TYPE.
- **Options considérées** :
  1. **H1** : (a) marche honnête + drapeau `tronque` + garde dans la
     cascade de noms (retenue) ; (b) simplement relever la borne —
     rejetée : elle ne fait que déplacer le mur (un jour ou l'autre le
     projet dépasse) et le coût du parse grimpe en proportion ;
     (c) couper TOUT `kt.unresolved` sur univers tronqué — rejetée :
     les preuves CLASSPATH (sélection par types complets ADR 0223)
     restent fondées, seule la preuve PROJET (voies 4/5) ne l'est pas.
  2. **Intrinsèques** : (a) réputation côté moteur (retenue) ;
     (b) corriger le DÉCODEUR — rejetée : IMPOSSIBLE, il n'y a RIEN à
     décoder (aucun bytecode, aucune métadonnée indexable) ;
     (c) injecter des entrées synthétiques dans l'index — rejetée :
     l'index est un CACHE de jars (ADR 0211), pas un registre de
     symboles ; la complétion des intrinsèques suivra la même
     réputation le jour où elle sera requise.
  3. **`entries`** : (a) accepter le getter statique sur les classes
     marquées ACC_ENUM (retenue) ; (b) accepter les getters statiques
     PARTOUT — rejetée : un getter Java statique n'est JAMAIS une
     propriété Kotlin (miroir kotlinc), l'accepter créerait des
     silences injustifiés ; (c) cas particulier du nom « entries » —
     rejetée : fragile (renommages, `@JvmName`) et le drapeau de
     CLASSE est le critère sémantique exact.
  4. **H2** : (a) silence sur les conventions de nommage générées
     (retenue) ; (b) lire `build/generated` dans le scan — rejetée :
     le scan est le SOURCE (ADR 0212), les racines générées relèvent
     du CONTRAT TOOLING (W10, `EnsemblesSources.generes`), et inclure
     les arbres générés mettrait les projets réels AU-DELÀ de la borne
     → troncation permanente → H1 déclenché en permanence ;
     (c) attendre du client qu'il passe les racines générées — hors
     périmètre (contrat déjà en place POST-build, le manque est le
     pré-build).
  5. **H3** : (a) extraction récursive BORNÉE dans `SourcesJava`
     (retenue) ; (b) extraction récursive SANS borne — rejetée : un
     fichier dégénéré (généré himself) doit rester borné, D3 ;
     (c) émettre les niveaux 3+ comme simple PROPRIÉTÉ du parent
     (miroir du repli vague 2) — rejetée : le genre exact et le
     conteneur FQCN sont gratuits et servent la complétion/navigation.
- **Décision** : retenir les options (a) :
  1. **`ScanProjet`** : la marche compte AU-DELÀ de la borne (classe
     privée `Marche` : collecte bornée, compte illimité,
     `tronque = vus > collectés`) ; `StatsPassage.total` rend le total
     RÉEL (le bilan ADR 0213 `fichiersParcourus` devient honnête) ;
     le drapeau `tronque` est publié SOUS LE MÊME VERROU que
     `scannees`/`pret` (paire cohérente pour le lecteur) ;
     `estUniversConnu()` = racines vides **OU** (`pret` **ET** NON
     `tronque`). `pret` reste posé sur un passage tronqué (la
     FRAÎCHEUR du snapshot tient, ADR 0212 ; c'est la PREUVE
     d'absence qui est coupée, pas l'invalidation).
  2. **`ResolutionReferences.estResolu`** : nouveau paramètre
     `universProjetComplet` (défaut `true`, appelants existants
     inchangés) — quand l'univers est tronqué, la cascade rend VRAI
     après les voies LOCALES et FICHIER (voies 1-3) : les voies 4/5 ne
     peuvent prouver NI résolution NI absence. Miroir EXACT de la
     garde `scanProjetComplet` de `selectionProjet` (vague 3),
     remontée au niveau de la CASCADE de noms. Branché par
     `DiagnosticsSemantiques` (diagnostic) ET `CandidatsImport`
     (quick-fix d'import — même prédicat que le diagnostic : sur
     univers tronqué, aucun candidat n'est proposé, l'absence n'est
     pas prouvable).
  3. **`ResolutionKotlin`** : `estAppeableIntrinseque(nom)` consulté
     par la voie 6 (set privé `APPELABLES_INTRINSEQUES` — miroir des
     `IMPORTS_DEFAUT` consultés par `fqcnDuNomEcrit` en voie 5) ;
     `getterStatiqueEnum` : getter statique accepté dans
     `getterSynthetique` QUAND le porteur porte `DRAPEAU_ENUM_KOTLIN`
     (nouvelle constante publique ACC_ENUM 0x4000 de l'api
     `ClasspathKotlin`, miroir des deux drapeaux existants).
  4. **`DiagnosticsSemantiques`** : garde `estNomGenere` (préfixe
     `Hilt_`, suffixes `_Impl`, `BindingImpl`) placée avec les gardes
     de silence par référence — à ÉTENDRE au fil des générateurs
     constatés (SafeArgs `Directions`/`Args`, usines Dagger).
  5. **`SourcesJava`** : `typeImbrique` (mot-clé de type ANCRÉ en tête
     de segment après espacement des annotations et consommation des
     modificateurs) émet l'imbriquée comme DÉCLARATION à genre exact,
     conteneur = FQCN du parent, `estSansHeritageEcrit` = FALSE
     (la preuve d'absence vague 3 reste limitée au PREMIER niveau,
     D3 conservateur) ; `membresDuType(profondeur)` REPARCOURT le
     corps de chaque imbriquée jusqu'à `BORN_IMBRIQUEES = 4` (niveaux
     2 à 5 émis, membres des niveaux 2 à 4) ; le repli PROPRIÉTÉ des
     segments de type (vague 2) est REMPLACÉ par la déclaration.
- **Preuves** :
  - `ReproductionTroncationScanTest` (3 tests) : total RÉEL (4) au
    delà de la borne, `estUniversConnu()` FAUX, la référence au type
    derrière la borne est MUETTE ; contrôle sans troncation inchangé.
  - `ReproductionAppelablesBuiltinTest` (2) : `arrayOf`,
    `intArrayOf`, `uintArrayOf`, `enumValues`, `enumValueOf` muets ;
    contrôles `listOf`/`emptyArray`/`println` inchangés.
  - `ReproductionMembresClasspathTest` (3) : `Couleur.entries` muet ;
    champ statique, `values()`/`valueOf()` muets ; le vrai négatif
    `absenteDuBytecode` reste SIGNALÉ.
  - `ReproductionSourcesGenereesTest` (2) : `Hilt_MainActivity`
    muet ; contrôle racine non exclue inchangé.
  - `ReproductionJavaImbriqueTest` (5) : `Couloir` (niveau 3, type)
    muet ; niveaux 1/2, expression, enum inchangés.
  - Chaîne `:lsp:kotlin` + `:lsp:index` (test, detekt, spotlessCheck,
    koverVerify) VERTE — 401 tests `:lsp:kotlin`, 0 régression ;
    baseline de parité re-synchronisée (le corpus EST le dépôt).
  - Angle mort assumé : sur un univers TRONQUÉ, les nus faillibles
    des voies 4/5 sont silencieux (sous-rapport D3 voulu — miroir
    kotlinc sur un projet aux sources incomplètes) ; les preuves
    CLASSPATH (ADR 0223) restent actives.
