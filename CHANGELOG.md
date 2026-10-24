# Changelog — codeide-lsp

## 1.6.0 (2026-10-24)

- **Audit E2E du serveur LSP Kotlin (harnais stdio JSON-RPC, classpath réel
  stdlib 2.4.0 + android.jar 37.2) — 5 découvertes corrigées (ADR 0203–0207)**
  : les 18 capacités déclarées sont toutes confirmées servies au fil du
  transport réel ; le harnais d'audit (68 scénarios) est COMMITTÉ dans
  `lsp/host/src/test/kotlin/jo/codeide/lsp/host/harnais/` et roule en CI
  comme garde de non-regression E2E.
  - **ADR 0204 — occurrences : la cible n'est plus son propre homonyme**
    (P1, le plus grave) : le garde « homonyme top-level local » de
    `occurrenceTopLevel` rejetait TOUT usage same-file d'un appelable
    top-level (la cible ELLE-MÊME satisfaisait le prédicat) — `rename`
    produisait du code CASSÉ (déclaration éditée, usages du fichier
    déclarant épargnés). Le garde reçoit la cible et exclut son identité
    (uri + plage du nom) ; la voie 3 de `cibleAuCurseur` dédoublonne par
    le même fait la copie DISQUE du document ouvert (URI distincte) qui
    créait une fausse ambiguïté au curseur d'usage.
  - **ADR 0205 — l'alias JVM↔Kotlin est sondé dans la chaîne de membres**
    (P1) : `kotlin.String` est une FICTION du langage (aucun `.class` ne
    porte ce nom — le bytecode vit sous `java.lang.String`) ;
    `chaineSupertypes`/`membresDe` retentent la variante aliasée au
    premier échec (le maillon de chaîne et les rendus gardent le FQCN
    KOTLIN demandé, seul le chargement des octets passe par l'alias).
    `s.length`, `(1).toString()`, `listOf(...).toString()` résolvent au
    survol ET à la complétion membre (la complétion passe par
    `membresDe`, plus jamais la vue nue) ; effet de bord positif : le
    survol du TYPE `String` reprend la voie `survolTypeClasspath`
    (`class kotlin.String`, plus la fonction stdlib homonyme). Sont
    couverts String, Int, Long, Boolean, Comparable — les types les plus
    sollicités du langage.
  - **ADR 0203 — `kt.unresolved` exige la preuve d'import pour les
    appelables du classpath (voie 6)** (P1) : un nom nu ne résout plus
    par la SEULE présence dans l'index — le nouveau prédicat PARTAGÉ
    `ResolutionKotlin.appelableEnPortee` (paquet courant, import exact,
    joker, paquets par défaut de Kotlin JVM — catalogue complet
    `kotlin.jvm`/`java.lang` compris) sert la voie 6 de
    `ResolutionReferences.estResolu` ET le survol d'appelable en portée
    (`InterrogationsSymboles.estEnPortee` délègue) : `PI`/`sqrt` sans
    import deviennent `kt.unresolved` (parité kotlinc ET parité avec la
    complétion, qui exigeait déjà l'import) ; `println`/`emptyList`
    (paquets par défaut) restent silencieux — les extensions homonymes
    restent sondées (corps de `with`).
  - **ADR 0206 — les propriétés de constructeur primaire sont des
    occurrences de plein droit** (P2) : le paramètre-propriété est DEUX
    choses (paramètre pour l'arité, PROPRIÉTÉ membre pour les
    occurrences) ; le scan (`SymbolesProjet`) émet l'entrée PROPRIÉTÉ
    supplémentaire (conteneur qualifié, plage du nom — la liste signée
    d'arité est conservée) et la lecture vive classe `val`/`var` sous
    classe englobante en PROPRIÉTÉ (`KtParameter.estPropriete`).
    Surlignage, références et renommage couvrent enfin
    `class Vue(private val ctx: Context)` — la forme la plus
    idiomatique de Kotlin/Android ; effet de bord positif : l'usage non
    qualifié ne peut plus produire de faux `kt.unresolved` (voie 4).
  - **ADR 0207 — la KDoc accompagne les usages du document courant**
    (P3) : `survolDeclarationProjet` reçoit l'uri du document courant ;
    quand la déclaration élue vit dans CE document, sa KDoc VIVE
    (recherche par plage du nom dans l'arbre en mémoire — aucun
    re-parse) accompagne la signature aux sites d'usage, miroir du
    survol du nom déclaré ; les déclarations du SCAN (autres fichiers)
    gardent la signature seule (choix K3 documenté — l'option 2 de
    l'ADR rouvrira le dossier cross-fichier).
  - +10 tests moteur (K3/K4/Occurrences : stdlib RÉEL en index pour la
    voie 6, vue factice portée à la vérité JVM pour l'alias) ; les 12
    sondes différentielles du harnais (S1–S12) verrouillent le
    comportement CORRIGÉ sur le transport réel ; baseline de parité du
    corpus roulée délibérément (fichiers=245 — harnais committé).
    Chaîne locale verte : spotless/detekt/tests des modules touchés
    (moteur 236, hôte 209).

## 1.5.0 (2026-10-24)

- **Previews Compose — la découverte remonte la LIGNE de l'annotation**
  (retour propriétaire sur les tests manuels v0.176.0 de CodeIDE : le
  marqueur de gouttière d'Android Studio vit À LA LIGNE de chaque
  `@Preview`, jamais sur le caret — écho `PreviewPickerLineMarkerProvider`
  de la référence : l'ancre du picker est le token « Preview » de
  l'ANNOTATION, un marqueur PAR annotation, AUCUN marqueur sans
  `@Preview`).
  - **`PreviewKotlin.ligneAnnotation`** (champ ADDITIF en queue, défaut
    `0` = inconnu) : ligne 1-indexée de l'entrée d'annotation
    `@Preview` DANS LE DOCUMENT — la ligne du `@`, pas celle du `fun` ;
    les arguments multi-lignes ne déplacent PAS l'ancre (l'entrée
    commence au `@`). Le consommateur place son marqueur de gouttière
    sur cette ligne et RENONCE honnêtement pour `0`.
  - Calcul dans `PreviewsDocumentKotlin` (la marche détient DÉJÀ le
    texte — `ligneDe(annotation.noeud.debut)`, aucune nouvelle passe) ;
    transport app-side par le consommateur via l'indice de la preview
    (CodeIDE enrichit ses rendus au moteur).
  - +3 tests (ligne simple, arguments multi-lignes, previews multiples
    aux lignes distinctes).

## 1.4.0 (2026-10-23)

- **Preview Compose C1 — découverte `@Preview @Composable` par le SPI**
  (PREVIEW.md §4.4 de CodeIDE, consommé par la carte « goutte d'eau »
  de l'éditeur) : la requête `previews(uri)` traverse la frontière D6
  en modèle NEUTRE — jamais l'arbre, jamais un type Compose.
  - **Façade annotations** (`parse/kt/KtAnnotationEntry.kt`) :
    `KtDeclaration.annotations()` (entrées du MODIFIER_LIST, forme
    multi-entrées traversée), nom TEL QU'ÉCRIT de l'annotation (forme
    qualifiée conservée), arguments NOMMÉS/positionnels avec la PLAGE
    du nœud de valeur (le texte se tranche dans la source — chaîne de
    gabarit comprise) ; `KtFunction.corps()` (miroir de
    `KtClass.corps()`).
  - **Preuve FQCN par les imports** : `@Preview` résout à
    `androidx.compose.ui.tooling.preview.Preview` et `@Composable` à
    `androidx.compose.runtime.Composable` — un nom DÉJÀ QUALIFIÉ est
    comparé tel quel, un nom COURT passe par `fqcnDuNomEcrit`
    (imports exacts, jokers par la vue, unicité projet) ; un homonyme
    sans import est ignoré (conservateur D3 — un joker runtime sans
    vue de classpath sous-rapporte honnêtement).
  - **Arbre d'appels NEUTRE** (`api/PreviewsKotlin.kt`) : le corps
    réduit aux appels directs + lambda final de contenu (les lambdas
    passés en ARGUMENT sont hors interprétation C1), arguments
    LITTÉRAL/RÉFÉRENCE/AUTRE distingués (`a.b` = référence, `a.b()` =
    AUTRE), toute autre expression rendue
    `ExpressionNonInterpreteeKotlin` (jamais un faux rendu — PC1).
  - **Inlining des composables du PROJET** : candidate top-level
    homonyme prouvée `@Composable` par les imports de SON PROPRE
    fichier (document ouvert d'abord, disque du scan ensuite — même
    hiérarchie que les occurrences), SANS paramètres en C1 ; cycles
    (la fonction racine est SEMÉE dans la pile — l'auto-récursion est
    un CYCLE typé, jamais une boucle infinie) et budgets (500 nœuds /
    profondeur 6, marche TRONQUÉE et signalée `coupureBudget`) —
    garde-fous PC4 côté moteur, chaque échec est un GENRE TYPIQUE
    (`PROJET_CYCLE`, `PROJET_BUDGET`, `PROJET_PARAMETRES`,
    `PROJET_ILLISIBLE`).
  - **Façade hôte** : `SessionLspKotlin.previews(uri)` — passe-plat
    vers les sessions moteur VIVANTES (la session détenant le document
    répond, première réponse non vide emportée, panne absorbée) ;
    tolérance de version par DÉFAUT VIDE du SPI (un pack antérieur
    dégrade honnêtement).
  - Tests : +13 session (`SessionLangageKotlinPreviewsTest` — preuve
    FQCN, paramètres tranchés, arbre imbriqué, genres d'arguments,
    inline disque, cycle, paramétré, borne de profondeur, tolérance de
    fin de vie) + 3 façade annotations + 1 passe-plat hôte ; baseline
    de parité re-synchronisée (239 fichiers — nouveaux sources).

## 1.3.0 (2026-10-21)

- **Lot L1 — Événements de fichiers aux trois langages** (W1 du plan
  1.3.0) : les serveurs Kotlin et XML reçoivent enfin
  `workspace/didChangeWatchedFiles` (le balayage ne bouclait que sur
  les serveurs Java) — un fichier modifié hors éditeur (build Gradle,
  terminal) rafraîchit les diagnostics des documents ouverts des TROIS
  langages, avec la même garde par destinataire (un serveur fautif
  n'empêche pas les autres) et la désinscription à l'arrêt (W2 vérifié :
  cycle de vie complet par fabriques, aucune fuite).
- **Lot L2 — Capacités Kotlin complètes** :
  - **Formatage BORNÉ** (`formatting` + `rangeFormatting`, ADR 0202) :
    ré-indentation canonique seule depuis l'arbre — le contenu des
    lignes N'EST JAMAIS réécrit ; intouchables : document en erreur
    (liste VIDE), chaînes brutes (le lexer les découpe ligne par ligne
    — le blanc de tête d'une ligne interne est du CONTENU), KDoc et
    blocs de commentaire, continuation d'expression libre, ligne
    vide ; idempotent ; le formatage de plage ne produit des éditions
    que DANS la plage ; taille d'indentation du client (défaut 4).
  - **Définition de type** et **implémentations** par PREUVE FQCN : le
    nom du type résout par les IMPORTS du fichier du curseur (puis
    unicité projet) — chaque héritier candidat prouve son supertype
    par les imports de SON PROPRE fichier ; l'incertain rend VIDE.
  - **Indices en ligne** : noms de paramètres aux appels POSITIONNELS
    prouvés (même chaîne que l'aide à la signature) ; types CERTAINS
    des déclarations sans type écrit (littéraux canoniques,
    constructeurs résolus) — jamais un type inféré deviné ; un
    argument nommé ne porte jamais d'indice.
  - **Symboles d'espace** (`workspace/symbol`) : déclarations nommées
    des documents ouverts + racines du scan, requête contient
    insensible à la casse, locales/paramètres exclus, plafonné,
    ordonné par nom.
  - **Chaînes de sélection** : nœuds englobants du plus profond au
    fichier entier, strictement croissants (fusion des frontières
    égales).
  - **Jetons sémantiques : NON annoncés** (la distinction n'est pas
    fiable au niveau du parseur — D3, dette consignée dans la matrice).
  - Réglables : `FORMATAGE` et `INDICES_EN_LIGNE` entrent dans
    `CAPACITES_KOTLIN` (entrées EXISTANTES de `CapaciteLsp` — aucune
    rupture du miroir CodeIDE) ; la table complète de parité W4
    verrouille les annonces (L0).
  - Correctifs moteur au passage : `fqcnDuNomEcrit` résout les fqcn
    SANS paquet (rouge→vert), `fqcnDuNomEcrit`/preuve FQCN alignés.
- **Lot L3 — Capacités XML complètes** :
  - **Références et surlignage d'occurrences** (`references` +
    `documentHighlight`) pour `@id/@+id/@string/@color/@dimen/@style/
    @drawable`, `?attr` — MÊME preuve que le renommage borné :
    déclarations de l'index (tampon ouvert puis disque, ÉCRITURES),
    références des documents ouverts (LECTURES, la déclarante `@+id`
    comptant comme écriture) ; framework `@android:…` : jamais servi.
  - **Formatage de plage**, **édition liée** (nom d'ouvrante ↔ nom de
    fermante du même élément, jamais sur auto-fermante), **plage de
    sélection** (chaîne DOM jusqu'au fichier), **couleurs de
    document** (`#RGB/#ARGB/#RRGGBB/#AARRGGBB` + `@color/…` résolus
    par l'index — le client ne les consomme pas encore, voir
    `docs/LSP_ECARTS_CLIENT.md`).
  - Réglable : `REFERENCES` entre dans `CAPACITES_XML`.
- **Lot L4 — Vérification Java de bout en bout** : aucun stub trouvé
  (les 30 gestionnaires font un vrai travail) ; E2E des capacités non
  couvertes (formatage document, références, symboles, prepareRename,
  surlignage, `superDefinition` et `decompiledSource` EXPÉRIMENTALES
  par le transport réel) ; **correctif d'une dette préexistante** : le
  classpath de TEST embarque désormais les jars ECJ patchés (la
  compilation de `:lsp:java:test` échouait depuis toujours — la CI ne
  lance pas cette tâche, l'échec était invisible) ; suite ENTIÈRE
  verte (400 tests). Écart moteur consigné : l'appel inter-fichiers
  d'un `references` Java n'est pas trouvé sans sync complet (matrice).
- **Lots L0/L5/L6/L7 — Preuve et documentation** : matrice des
  capacités (`docs/LSP_MATRICE_CAPACITES.md`, chaque case cite sa
  preuve — ADR 0201) ; test de parité sur la table COMPLÈTE des
  annonces + garde anti-stubs (W4) ; ADR 0202 (formateur Kotlin
  borné) ; écarts client (`docs/LSP_ECARTS_CLIENT.md`) ; LSP_KOTLIN/
  LSP_XML/PLAN_STABILISATION à l'état réel (W3 : les hiérarchies Java
  sont LIVRÉES, plus « ouvertes ») ; note de passage
  `docs/MISE-A-JOUR-CODEIDE.md` § 1.3.0.
- **Comptes de tests** : `:lsp:kotlin` 218, `:lsp:xml` 245, `:lsp:java`
  400, `:lsp:host` 141 — tous verts ; baselines de parité régénérées
  pour les AJOUTS VOULUS de fichiers de test au corpus.

## 1.2.0 (2026-10-20)

- **Lot LSP-1 — API de configuration et de réglages (ADR 0128 §4, revue
  `LSP_REVUE_REGLAGES.md` du plan LSP)** : l'hôte expose ENFIN une
  surface de configuration honnête — chaque contrôle de l'écran
  « Intelligence du code » de CodeIDE projette une API réelle.
  - **`ConfigurationLsp`** (données immuables, défauts = comportement
    historique v1.0.x–v1.1.x) : plafond du budget du cache LRU
    (`null` = **Auto**, le dérivé `max(30 Mo, 512 Kio/jar)` de la leçon
    du banc F2) et période du surveillant de fichiers S1-d
    (`5 000 ms` par défaut, `0` = désactivé) ; bornes vérifiées à la
    construction (période ≥ 0, budget > 0 ou Auto).
  - **`SessionLsp(config)` + `appliquer(config)`** (sous-ensemble
    DYNAMIQUE) : un plafond explicite borne le budget dans les DEUX sens
    (le service d'index est recréé au budget DEMANDÉ, les segments
    persistent — zéro ré-indexation, ADR 0111 §3 ; en Auto le contrat
    historique « ne croît jamais » tient) ; la surveillance est
    annulée/relancée à la nouvelle période (baseline réinstallée au
    premier tour, aucun événement fantôme). Le paramètre historique
    `surveillanceFichiersMs` du constructeur devient `config`
    (`ConfigurationLsp.parDefaut()` = comportement identique).
  - **`tailleCacheOctets()`** : occupation DISQUE de l'entrepôt de
    segments (temporaires exclus), mesurable AVANT le premier sync
    (miroirs statiques `ServiceIndexDisque.tailleStockageDe/viderStockageDe`
    — un stockage hérité d'une session précédente compte déjà).
  - **`viderCache()`** : suppression des segments + temporaires, cache
    LRU mémoire vidé ; **refusé pendant une indexation** — le refus
    voyage en DONNÉE (`ResultatVidageCache.Refusee(INDEXATION_EN_COURS)`),
    jamais en exception ; l'index est reconstruit à la prochaine
    ouverture ou par « Réindexer » (auto-guérison ADR 0111 §4).
  - **`capacites(langage)`** : capacités ANNONCÉES par moteur
    (`LangageLsp` JAVA/KOTLIN/XML, `CapaciteLsp` ×10 interrupteurs) —
    la liste des interrupteurs de l'écran L2 de CodeIDE en est DÉRIVÉE,
    jamais codée en dur côté client ; la parité table ↔ `initialize`
    réel est VERROUILLÉE par test (les `ServerCapabilities` des trois
    serveurs se traduisent exactement en la table : Java 10 capacités,
    Kotlin 8 — pas de formatage ni d'indices en ligne, XML 7 — pas de
    références ni de signatures ; renommage et formatage XML depuis
    1.1.0).
  - **Tests** : +14 (réglages de session — défauts, bornes, budget
    plafonné croissant/décroissant dans le résultat public, occupation
    héritée avant sync, vidage refusé [dispatcher gelé, déterministe]
    puis effectif, tables de capacités ; parité ×3 langages ; vidage
    index statique + instance). Suite :lsp:host 119 verts (107 + 12),
    :lsp:index 247 verts (245 + 2), baseline de parité du corpus
    régénérée délibérément (212 → 216 fichiers, `-Dparite.majBase=true`).
- **Correctif de construction** (`:lsp:testing`) : la dépendance vers
  `:lsp:java` EXIGE le variant JAR (`LibraryElements.JAR`) — le variant
  secondaire « classes » (répertoire) du module vendored ne transporte
  pas le graphe ECJ fusionné (reproduction clone frais :
  `cannot access ClassFileReader` au compil de la suite d'intégration) ;
  AGP l'exigeait déjà naturellement côté `:lsp:host`.
- **Vérifié** : chaîne locale verte complète (spotlessCheck, detekt,
  checkModuleDependencies, lintDebug, tests des modules kover —
  model/index/kotlin/xml/testing/host, koverVerify, assembleRelease) ;
  exemption `TooManyFunctions` motivée sur `ServiceIndexDisque` (règle 8 :
  chaque fonction est un verbe du contrat public, occupation et vidage
  du lot LSP-1 compris).

## 1.1.0 (2026-10-19)

- **Rename borné XML** (plan de stabilisation §3.3 S3 « rename borné
  des entrées values », miroir du « rename honnête » Java I8) :
  `textDocument/prepareRename` (plage + placeholder) et
  `textDocument/rename` — cibles : référence `@type/nom` /
  `?attr/nom` / déclarante `@+id/nom` et entrée déclarante `values`
  (valeur de son attribut `name`) ; éditions = déclarations de
  l'index (tampon ouvert PUIS disque, tous fichiers déclarants) +
  références des documents OUVERTS (bornage assumé, document courant
  en tête) ; framework et ressources par fichier jamais renommables ;
  sans cible = erreur typée RequestFailed (-32803, data
  `renommage.sansCible`) — aucun succès vide silencieux.
- **Formateur XML** (`textDocument/formatting`) : ré-indentation
  canonique seule — profondeur logique depuis le DOM (ouvrante/fermante
  = ancêtres, continuation multi-ligne = +1, texte/commentaire =
  profondeur de contenu), contenu des lignes JAMAIS touché (le DOM ne
  modélisant ni commentaires ni CDATA, rien n'est réécrit) ; refus
  honnête sur document cassé (reprises du parseur, balise non fermée)
  = liste vide ; taille d'indentation respectée (défaut 4).
- Hôte : capacités déclarées inconditionnellement (RenameOptions
  prepareProvider, DocumentFormattingProvider — même doctrine XP9) ;
  WorkspaceEdit legacy `changes` (cel-lsp n'annonce pas
  documentChanges) ; éditions du déclarant fermé repositionnées sur
  le tampon ouvert quand il l'est (overlay, miroir Java).
- Audit S2 : parité utile déjà en place sur les trois moteurs
  (Kotlin : references/documentHighlight/rename/folding/hover KDoc
  vendored ; Java : rangeFormatting/selectionRange/onTypeFormatting/
  codeLens/inlayHint/semanticTokens ; XML : folding/outline/XP12/XP15/
  XP16) — plan §3 mis à jour en conséquence ; les restants S3
  structurels (transport socket ADR 0109 plan B, K2 par pack, re-parse
  incrémental, sources-jars/décompilé) restent DEFERRED (lots
  architecturaux dédiés).
- Vérifié : chaîne locale verte complète (spotless, detekt,
  checkModuleDependencies, lint, tests tous modules, koverVerify,
  assembleRelease) ; :lsp:xml 245 tests verts ; :lsp:host 107 verts ;
  baseline de parité Kotlin régénérée (+4 fichiers du lot).

## 1.0.0 (2026-10-17)

- Extraction de jjoblab/CodeIDE par `git filter-repo` (historique
  conservé : 339 commits touchant `lsp/`, `build-logic/`, `gradle/`,
  `config/detekt/`, `docs/adr/`, docs LSP et fichiers de build racine).
- Périmètre : les 8 modules du domaine — model, index, java, ecj-art,
  testing, host, kotlin, xml. Aucun module left-behind : la frontière
  lsp ne dépend d'aucun `core:*` (audit Phase 0).
- Adaptations : `settings.gradle.kts` lsp-only (retrait du bloc LayoutLib
  et des modules non-lsp ; conservés : JitPack pour cel-lsp/:lsp:host,
  cache-redirector JetBrains pour syntax-api/:lsp:kotlin) ;
  `:lsp:model` passe sur les coordonnées externes
  `com.github.jjoblab.codeide-tooling:protocol:1.0.0` avec composite
  `tooling.dir` en dev (miroir LayoutLib) ; version indépendante 1.0.0 ;
  baseline de parité régénérée pour le corpus du nouveau dépôt.
- Vérifié : chaîne locale verte (spotless, detekt, règles, tests tous
  modules, koverVerify) — voir journal d'exécution du dépôt CodeIDE
  (PLAN_SEPARATION_DEPOTS.md §8).
