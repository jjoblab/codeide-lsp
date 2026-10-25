# ADR 0218 — Parité collections et déclarations : alias JVM↔Kotlin étendus, repli Android Studio au mot-clé, chaîne d'ancêtres à l'offset 0

- **Statut** : appliqué (25/10/2026 — découvertes du sondage des
  capacités E2E Compose : `filter` introuvable depuis `List`,
  `references` muettes au mot-clé `fun`, requêtes muettes à l'offset 0).
- **Contexte** : trois familles d'ÉCARTS avec le LSP Kotlin officiel
  d'Android Studio :
  - **C1 — extensions de COLLECTIONS introuvables** : la chaîne des
    supertypes (`ResolutionKotlin.chaineSupertypes`, ADR 0205) lit les
    octets JVM — elle produit des noms JAVA (`java.util.Collection`) —
    mais les clés d'extension de l'index portent les FQCN KOTLIN
    (`kotlin.collections.Iterable` — le récepteur ÉCRIT dans les
    métadonnées). De plus `kotlin.collections.List` n'a AUCUN `.class`
    (fiction du langage) et la carte d'alias KOTLIN→JVM ne couvrait que
    String/Int/Long/Boolean : la chaîne s'arrêtait AU PREMIER maillon
    pour toute la hiérarchie des collections — `filter`/`map`/`forEach`
    (extensions sur Iterable) étaient introuvables en survol et en
    complétion membre depuis `List`/`Set`/`Map`.
  - **C2 — requêtes muettes au MOT-CLÉ d'une déclaration** :
    `references`/`documentHighlight`/`renommage` exigeaient le curseur
    SUR l'identifiant du nom ; Android Studio répond Find Usages/Rename
    le curseur n'importe où sur la déclaration (`fun`, `class`,
    `override`…).
  - **C3 — chaîne d'ancêtres morte à l'offset 0** : la
    `PACKAGE_DIRECTIVE` VIDE et l'`IMPORT_LIST` vide sont TOUJOURS
    présentes (découverte de parité K2, LSP_KOTLIN §5) ; sur un fichier
    SANS paquet (`.kts`, script, premier fichier d'un projet vierge), la
    directive vide `[0,0]` est le PREMIER enfant contenant l'offset 0 —
    la descente mourait dedans sans jamais voir la déclaration :
    survol/références/renommage muets au tout début du fichier.
- **Options considérées** :
  1. **Correctifs ponctuels convergents** (retenue) — alias étendus,
     repli en-tête borné, préférence d'enfant non vide.
  2. **Normalisation des clés d'index côté JAVA** (réécrire les clés
     d'extension en noms JVM) : inverserait la convention « le récepteur
     est le FQCN KOTLIN écrit » de tout l'index — rejeté.
  3. **Scan direct des déclarations pour C2** (au lieu de la chaîne
     d'ancêtres) : coût non borné par fichier à CHAQUE requête — rejeté
     au profit de la réutilisation de la chaîne.
- **Décision** : retenir l'**option 1** :
  - **C1** : les cartes `ALIAS_JVM_KOTLIN` et `ALIAS_KOTLIN_JVM` de
    `ResolutionKotlin` couvrent désormais la hiérarchie des collections
    (Iterable, Collection, List, Set, Map, Iterator, ListIterator,
    Map.Entry) et les types usuels (CharSequence, Number, Enum,
    Throwable, Comparable, Comparator) DANS LES DEUX SENS :
    KOTLIN→JVM sert à `chaineSupertypes` pour LIRE les octets d'une
    fiction, JVM→KOTLIN sert à `variantesRecepteur` pour traduire un
    maillon JAVA parcouru en CLÉ d'extension KOTLIN.
  - **C2** : `OccurrencesKotlin.cibleAuCurseur` gagne le repli
    `cibleDeclarationEnglobante` — quand le curseur n'est sur AUCUN
    identifiant, la plus profonde déclaration VIVE dont l'EN-TÊTE porte
    le curseur devient la cible. L'en-tête est la SIGNATURE : il se
    termine au premier enfant qui n'est PAS un constituant de signature
    (MODIFIER_LIST, VALUE_PARAMETER_LIST, TYPE_PARAMETER_LIST,
    TYPE_REFERENCE, SUPER_TYPE_LIST, constructeurs) — le CORPS (bloc,
    corps de classe) et l'INITIALISEUR (littéral, expression, délégué)
    ne visent JAMAIS la déclaration englobante (le corps appartient aux
    déclarations locales et aux expressions). Le repli alimente
    `references`, `surlignagesDocument`, `plageRenommable` et
    `renommage` — miroir Find Usages/Rename IntelliJ.
  - **C3** : `chaineAncetresA` préfère désormais un enfant NON VIDE
    contenant le décalage ; un enfant de largeur nulle ne sert que
    s'il est le SEUL contenant. La directive vide ne capture plus la
    descente à l'offset 0.
- **Preuves** :
  - **3 tests dédiés** (`SessionLangageKotlinOccurrencesTest`, section
    ADR 0218) : `references` au mot-clé `fun` (déclaration + usage),
    au mot-clé `class` (déclaration + type écrit + construction), et
    garde de NON-régression — curseur dans le CORPS (`return`) →
    AUCUNE cible. Les documents y sont ouverts sous leur URI DISQUE
    RÉELLE (la convention `fixture://` des autres tests crée une copie
    fantôme au balayage — documenté dans le test).
  - **Gardes existantes inchangées** : `plageRenommable - hors
    identifiant, null` et `renommage - sans cible prouvée` (le curseur
    dans un littéral reste SANS cible — l'initialiseur n'est pas
    l'en-tête).
  - **E2E Compose** (`ReproductionComposeE2ETest`) : `references` au
    mot-clé `fun Greeting` rend déclaration + usage (2) ; la complétion
    membre après `modifier.` rend 100 items dont `padding` ;
    `signatureHelp` et survols inchangés (298 tests moteur et 236 tests
    hôte verts après les correctifs).
- **Conséquences** :
  - La complétion et le survol des extensions standard de collections
    rejoignent la parité Android Studio (`xs.filter { it > 1 }` survolé
    et complété depuis `List`, `Map`, `Set`).
  - Find Usages / Rename au mot-clé fonctionnent comme dans l'IDE de
    référence ; le curseur dans un corps d'expression ne vise rien
    (miroir IntelliJ).
  - Les requêtes à l'offset 0 d'un fichier sans paquet ne sont plus
    muettes (scripts `.kts` notamment).
  - Les alias restent une liste FERMÉE (pas de traduction générique
    `java.*` → `kotlin.*`) : un type hors carte sous-rapporte honnêtement
    (D3) — la carte s'étendra au fil des retours.
