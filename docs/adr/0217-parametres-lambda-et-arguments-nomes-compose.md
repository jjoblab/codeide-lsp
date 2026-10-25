# ADR 0217 — Découvertes E1/E2 du rapport Compose : paramètres de LAMBDA et noms d'ARGUMENTS nommés

- **Statut** : appliqué (25/10/2026 — découvertes E1 et E2 du rapport
  d'audit Compose, retour propriétaire « beaucoup de références non
  résolues sur pratiquement tout » sur un projet Android Compose).
- **Contexte** : deux familles de FAUX POSITIFS du diagnostic
  `kt.unresolved` spécifiques au style Compose :
  - **E1 — paramètres EXPLICITES de lambda** : le code Compose est saturé
    de trailing lambdas À PARAMÈTRE NOMMÉ — `Scaffold { innerPadding -> … }`,
    `map { nombre -> … }`. La collecte des locales en portée
    (`SymbolesProjet.localesEnPortee`, K4) couvrait les paramètres des
    FONCTIONS (voie `KtNodeTypes.FUN`) mais PAS ceux des
    `FUNCTION_LITERAL` : TOUT identifiant de paramètre de lambda était un
    faux `kt.unresolved` (et muet en survol/complétion de nom).
  - **E2 — noms d'ARGUMENTS nommés** : la grammaire vendorée (amont
    `parseValueArgument`) enveloppe le nom d'un argument nommé
    (`text = …`, `showBackground = true`) dans une `REFERENCE_EXPRESSION`
    sous `VALUE_ARGUMENT_NAME`. Le compilateur Kotlin ne le résout
    JAMAIS comme une référence de nom — sa preuve exige la sélection de
    l'appelé par types complets (hors modèle T1, LSP_KOTLIN §4.3) —
    d'où un faux positif PAR argument nommé de tout appel Compose.
- **Options considérées** :
  1. **Collecte des paramètres de lambda + garde argument nommé**
     (retenue) — deux correctifs ponctuels, miroir kotlinc/Android
     Studio qui ne signalent jamais ces deux formes.
  2. **Inférence complète des types de lambda** (K5 déjà partiel) :
     ne résout pas E2 (le nom d'argument n'est pas une référence) et
     hors budget pour E1 seul — rejeté.
  3. **Sous-rapport de tout nom dans un littéral** : masque de vrais
     négatifs (typo dans un corps de lambda) — rejeté.
- **Décision** : retenir l'**option 1** :
  - **E1** : la marche `FUNCTION_LITERAL` de `localesEnPortee` collecte
    désormais les `VALUE_PARAMETER` de sa `VALUE_PARAMETER_LIST` (portée
    = TOUT le corps du littéral, miroir kotlinc : le paramètre lie toute
    la fonction) — le littéral rejoint aussi les portées de propriétés
    locales du bloc.
  - **E2** : `DiagnosticsSemantiques` gagne `estNomArgument` — une
    `REFERENCE_EXPRESSION` vivant sous un `VALUE_ARGUMENT_NAME` (chaîne
    d'ancêtres) n'est JAMAIS signalée par `kt.unresolved` (D3 :
    sous-rapport ; quick-fixes et complétion d'import la traitent déjà
    par ailleurs).
- **Preuves** :
  - **12 tests de reproduction** (`ReproductionComposeTest`, moteur par
    SPI, stdlib RÉEL voie 6 ADR 0203) : lambda finale à paramètre nommé,
    lambdas imbriquées, appels pleinement qualifiés (premier segment
    paquet nu), annotation qualifiée, `by lazy`, alias d'import, import
    joker, sondages survol/symboles/références/complétion — zéro faux
    positif attendu partout.
  - **3 scénarios E2E** (`ReproductionComposeE2ETest`, chaîne de
    PRODUCTION complète : SessionLsp Java + index disque ADR 0111 + vue
    fusion → SessionLspKotlin → serveur LSP4J + client espion) nourrie du
    classpath RÉEL d'un projet Compose (kotlin-stdlib 2.2.10, android-36,
    classes.jar extraits des AAR activity, activity-compose, foundation,
    foundation-layout, material3, runtime, runtime-saveable, ui,
    ui-geometry, ui-graphics, ui-text, ui-tooling-preview, ui-unit,
    ui-util, annotation-jvm — variable `REPRO_COMPOSE_DIR`) :
    template « Empty Activity » d'Android Studio → AUCUN faux positif ;
    vrai négatif (typo `Texte`) TOUJOURS signalé (garde de
    non-régression D3) ; sondage des capacités (survol, définition,
    complétion membre, aide à la signature, previews, références,
    symboles) alimente le rapport d'audit.
- **Conséquences** :
  - Le template Compose canonique ne produit PLUS AUCUN faux positif —
    la conformité miroir Android Studio est atteinte sur ce périmètre.
  - Les vrais négatifs restent signalés (le corpus E2E le prouve par
    garde dédiée).
  - La découverte E2 est le premier cas documenté d'une référence
    syntaxiquement présente mais SEMANTIQUEMENT absente du modèle de
    résolution Kotlin — la garde vit dans le diagnostic (pas dans le
    parseur : l'arbre DOIT rester le miroir PSI de la grammaire amont).
