# Changelog — codeide-lsp

## 1.22.0 (2026-10-29)

- **Correctifs d'expression wrap / cast / surround (chantier 3 de la
  feuille de route post-P1, ADR 0232)** : les deux diagnostics les plus
  actionnables du moteur Kotlin gagnent leurs quick-fixes — le message
  « utiliser « ?. » ou « !! » » devient un geste :
  - **`kt.unsafeNullable` → trois correctifs** : « Remplacer « . » par
    « ?. » » (insertion du `?` devant le point de CETTE expression —
    `s.length.trim()` corrige le point de son propre segment), «
    Encadrer le récepteur de « !! » » (le cast de nullité T? → T), «
    Entourer d'un test de nullité « if (s != null) » » — l'instruction
    englobante (l'enfant direct du bloc le plus profond), MULTI-LIGNE
    quand elle possède sa ligne (corps indenté d'un pas de 4 espaces,
    garde fermée à l'indent de l'instruction, miroir Android Studio),
    EN LIGNE sinon ; sans bloc ancêtre (premier niveau), l'entourage
    reste muet (`if` illégal au sommet d'un fichier) ;
  - **`kt.typeMismatch` → deux correctifs** : « Changer le type en « X »
    » (le représentant de la famille du littéral — `val x: Int = "5"`
    → `val x: String = "5"`, toujours légal) et « Encadrer d'une
    conversion « .toX() » » (matrice FERMÉE des conversions standard :
    numérique → numérique/Char/String, chaîne → numérique et Boolean,
    booléen/caractère → String ; caractère → numérique EXCLU —
    `Char.toInt()` déprécié depuis Kotlin 1.5) ; JAMAIS de cast `as` —
    illégal entre familles disjointes, le correctif ne doit pas
    produire du code cassé (D3) ;
  - **l'alignement est par construction** : les correctifs dérivent des
    MÊMES marches que les diagnostics (collecteurs internes partagés
    `litterauxIncompatibles`/`accesNonSursProuves`, régions propres
    P1-A comprises) — un correctif ne s'offre QUE là où le diagnostic
    peut s'exprimer ; le moteur RE-PROUVE à la requête sur l'arbre
    courant : un diagnostic périmé rend VIDE (la preuve est
    structurelle, jamais une mémoire du publié) ;
  - branchement session : `correctifs(uri, decalage)` au contrat
    (défaut VIDE — tolérance de version), le transport `codeAction`
    traduit chaque édition en CodeAction QuickFix relié à son
    diagnostic, plages dédoublonnées, moteur en échec honnête (rien) ;
    aucune capability ne change.
- Tests : +13 moteur (`CorrectifsTexteTest` — les trois correctifs de
  l'accès non sûr aux éditions exactes, l'entourage multi-ligne/en
  ligne/propriété locale/premier niveau muet, le segment imbriqué, les
  gardes D3, la matrice de conversion fermée, l'alignement ⟺
  diagnostic, le diagnostic périmé, le document inconnu), +4 hôte
  (`KotlinLanguageServerTest` — trois actions titrées reliées au
  diagnostic, deux actions du littéral avec l'édition de la plage
  écrite, moteur en échec honnête, diagnostic dupliqué dédoublonné),
  baseline de parité re-synchronisée.

## 1.21.1 (2026-10-29)

- **Republication complète de 1.21.0 (publication interrompue en
  plein vol)** : le re-tag de v1.21.0 (restructuration feat/release
  des commits juste après la poussée) a ANNULÉ la publication GitHub
  Packages en cours — l'annulation concurrence (`cancel-in-progress:
  true`) a laissé le module `index` PARTIEL (jar publié, pom manquant
  : Gradle téléverse le jar AVANT le pom), état irrécupérable sans
  droit `delete:packages` (la garde 409 sonde le pom seul, le jar
  existant refuse tout nouveau téléversement). La version 1.21.0
  doit rester NON CONSOMMÉE (modules model/java/ecj-art/testing
  complets mais index non résoluble, host/kotlin/xml absents) ;
  1.21.1 republie le MÊME contenu intégralement :
  - le workflow de publication n'annule JAMAIS plus un run en cours
    (`cancel-in-progress: false`) — un re-tag devient inoffensif : la
    publication en file d'attente FINIT, la suivante sonde l'état
    complet et saute (garde 409) ; un module partiel ne peut plus se
    produire par ce chemin ;
  - aucun changement de code : le contenu des artefacts 1.21.1 est le
    chantier 2 (ADR 0231) de 1.21.0.

## 1.21.0 (2026-10-29)

- **Indices lambda des littéraux de fonction (chantier 2 de la feuille
  de route post-P1, ADR 0231)** : les indices en ligne
  (`textDocument/inlayHint`) rejoignent l'information la plus précieuse
  d'un littéral — le paramètre IMPLICITE, dont le nom n'existe NULLE
  PART dans le source :
  - **genre LAMBDA** : le `it` implicite d'un littéral sans liste de
    paramètres rend un indice au libellé COMPLET `it: TypeCourt`
    (`repeter(2) { println(it) }` → `it: String` en pâle après
    l'accolade ouvrante — miroir du rendu d'Android Studio), ancré sur
    l'accolade, rendu APRÈS elle, kind Parameter, padding des deux
    côtés ; l'attente doit être UNAIRE (garde D3) ;
  - **paramètres explicites** : chaque paramètre sans type écrit rend
    un indice TYPE `: T` ancré sur son NOM (`combiner(1, 2) { x, y ->
    }` → `x: Int`, `y: Int`), libellé au nom COURT du FQCN prouvé ;
    un paramètre déjà typé n'en reçoit pas, l'arité doit épouser
    l'attente — à la moindre divergence, AUCUN indice (D3) ;
  - **la preuve** réutilise l'inférence bornée K4/K5 : nouvelle sonde
    `ParametresLambda.typesLitteral` (même moteur que `typeParametre`,
    ancre au DÉBUT du littéral) rend la liste COMPLÈTE des types
    attendus du CONSOMMATEUR (propriété typée, fonction projet, SAM
    projet, appel classpath qualifié), `null` sans preuve — un littéral
    passé à une fonction inconnue ou imbriqué dans un autre littéral
    reste MUET ;
  - **ancre TYPE honorée par le transport** : le pont LSP positionnait
    TOUT à `debut` — le type se tissait AVANT le nom (`val : Int
    entier`), contredisant le contrat du genre (« le label se rend
    APRÈS le nom »). Corrigé : PARAMETRE → `debut` (AVANT l'argument,
    inchangé), TYPE et LAMBDA → `fin` (APRÈS l'ancre) — `val entier:
    Int = 42` ;
  - branchement session : le contexte d'indices réutilise
    `InterrogationsSymboles.Contexte` (nommage, résolveur, déclarations
    projet) — aucun contexte nouveau.
- Tests : +4 moteur (`CapacitesL2KotlinTest` — `it` implicite par
  fonction projet, paramètres explicites par nom, gardes sans
  preuve/arité divergente/paramètre typé, propriété typée + SAM
  projet), +1 hôte (`KotlinLanguageServerL2Test` — libellé LAMBDA
  rendu TEL QUEL après l'accolade, padding, position `n =` AVANT,
  ancre TYPE en fin de nom), baseline de parité re-synchronisée.

## 1.20.0 (2026-10-29)

- **Fiche classpath au survol, KDoc aux signatures (chantier P1-D de la
  feuille de route du rapport d'analyse du 28/10, ADR 0230)** : la
  documentation rejoint les deux surfaces où l'utilisateur l'attend —
  le survol d'un type du classpath parle, l'aide à la signature
  accompagne ses candidates projet de leur KDoc :
  - **fiche structurelle du survol** : première ligne
    `[public] genre FQCN [extends super]` (genre lu des drapeaux JVM —
    `interface`/`enum`/`class`, un `object` Kotlin prouvé par son champ
    `INSTANCE` statique final) ; héritage direct affiché HORS racines
    universelles (`Object` masqué pour tous, `Enum` pour les enums) ;
    sections `Interfaces : …` (directes), `Constantes : …` (champs
    `ACC_ENUM` d'une enum, plafond 20) ou `Champs publics : Type nom, …`
    (plafond 8) — synthétiques `$` et marqueur `INSTANCE` exclus.
    Sans `.class` lisible : genre et champs restent, héritage et
    interfaces se taisent (D3 — le bytecode ne porte NI KDoc NI
    Javadoc, la fiche est structurelle) ;
  - **KDoc des candidates de signature** : le port cross-fichier de
    l'ADR 0208 (arbre vif du document ouvert, sinon lecture disque +
    parse bornées UNE fois par candidate tierce) réutilisé TEL QUEL
    par les candidates FONCTION, EXTENSION et MEMBRE VIF ; les
    candidates du classpath restent muettes (bytecode sans doc) ;
    le contrat `SignatureKotlin.documentation` est ré-ouvert (il
    annonçait « toujours null ») — le transport hôte, qui servait déjà
    le champ en plaintext, est inchangé ;
  - sonde `ResolutionKotlin.infosClasse` (miroir aliasé de
    `membresDe`), branchement session aux TROIS contextes de
    signatures (aide, indices en ligne, complétion contextuelle).
- Tests : +11 moteur (`FicheClasspathTest` — octets synthétiques
  super/interfaces réels, plafonds, binaire illisible, port KDoc
  fermé/ouvert/disque périmé), +1 hôte (doc en plaintext au
  signatureHelp), S11 re-synchronisée sur la fiche ; baseline de
  parité re-synchronisée.

## 1.19.0 (2026-10-29)

- **Implémenter les membres manquants (chantier P1-C de la feuille de
  route du rapport d'analyse du 28/10, ADR 0229)** : premier correctif
  Kotlin AU-DELÀ des imports — sur la classe/objet englobant le
  curseur, le quick-fix insère les stubs des membres ABSTRAITS de la
  clôture des supertypes non déjà implémentés :
  - **clôture** (miroir du GO TO Super) : chaque supertype ÉCRIT résolu
    par les imports du fichier déclarant (preuve FQCN) ; un lien PROJET
    se lit dans son arbre (port de la session) et prouve sa propre
    clause, un lien CLASSPATH parcourt sa chaîne binaire (vue JVM) ;
    sources Java du scan jamais traversées, profondeur 12, 64 stubs ;
  - **fidélité** : PROJET — noms et types réels, paramètres de type
    SUBSTITUÉS par les arguments écrits de la clause (la substitution
    se compose le long de la clôture, `Depot<String>` rend
    `fun lire(id: String)`) ; CLASSPATH — vue JVM érasée, paramètres
    `p0…`, getters/setters abstraits APPARIÉS en propriétés
    `override val x` / `override var x` ;
  - **garde générique** (l'apport décisif) : un membre du classpath
    portant l'attribut `Signature` du bytecode (variable de type ou
    type paramétré) est ÉCARTÉ — son rendu érasé ne compilerait pas,
    un stub faux est pire que pas de stub (D3). Nouvelle sonde maison
    `LecteurClassBrut.methodesGeneriques` (octet par octet, `null` sur
    octets illisibles : le lien entier se tait) ;
  - **édition** : stubs `= TODO()` / `get() = TODO()` indentés comme
    le corps ; corps vide → le blanc entre accolades est REMPLACÉ,
    corps peuplé → insertion avant l'accolade fermante (un commentaire
    force l'insertion, rien n'est détruit), corps ABSENT → créé en fin
    d'en-tête ;
  - **bornes honnêtes** : hors classe/objet, interface, classe
    abstraite, sans supertype écrit, tout implémenté → `null` ;
    exclusions par nom+arité (fonctions) et nom (propriétés,
    constructeur primaire compris) ;
  - **contrat** : `SessionLangageKotlin.membresManquants` (défaut
    `null`, tolérance de version), l'hôte n'ajoute que le titre
    (singulier/pluriel) et l'enveloppe `WorkspaceEdit` — QuickFix SANS
    diagnostic (l'action est bornée à la déclaration englobante).
- Tests : +10 moteur (`MembresManquantsTest` — octets de classe
  synthétiques avec attribut `Signature` réel) + 3 hôte
  (`KotlinLanguageServerTest`), 441 au total sur `:lsp:kotlin`
  (0 échec), 251 sur `:lsp:host` (0 échec) ; baseline de parité
  re-synchronisée.

## 1.18.0 (2026-10-29)

- **Complétion contextuelle Kotlin, vague 1 (chantier P1-B de la
  feuille de route du rapport d'analyse du 28/10, ADR 0228)** : les
  trois contextes que le moteur Java sert depuis la 1.16.0, portés au
  Kotlin — la complétion sait maintenant OÙ elle est dans les positions
  à forte valeur de frappe :
  - **arguments nommés** : `foo(na|` offre `nom = ` — les noms de
    paramètres des surcharges PROUVÉES de l'appel englobant (même
    graphe que l'aide à la signature), déjà fournis en forme nommée
    exclus, FUSIONNÉS au régime de nom (la locale `nom` reste offerte
    à côté de `nom =` ; un argument accepte toute expression). Le
    curseur collé à la fin de l'identifiant en frappe compte ; une
    liste refermée garde le curseur hors contexte ;
  - **constantes d'enum** : `when (c) { RO| ->` offre les entrées de
    l'énumération du sujet (projet ∪ classpath : champs statiques
    typés par l'enum elle-même, miroir du moteur Java), contexte
    EXCLUSIF de la position de condition — le corps d'une branche,
    une garde, le sujet lui-même et un sujet non enum retombent
    honnêtement sur le régime de nom ;
  - **attributs d'annotation** : `@Anno(lev|` offre `level = ` — les
    méthodes publiques abstraites du type d'annotation (résolu par
    les imports du fichier), non déjà fournies, le séparateur VOYAGE
    avec le candidat (contexte exclusif, miroir du `@Anno(` Java) ;
  - **transport** : nouveau champ optionnel
    `PropositionCompletionKotlin.texteInsertion` (« nom = » affiché,
    `nom = ` inséré), traduit en `insertText` LSP par l'hôte — miroir
    du `CompletionItem` du moteur Java ; défaut `null` = le client
    insère le libellé (contrat historique inchangé, tolérance de
    version des packs).
- Tests : +14 moteur (`SessionLangageKotlinCompletionContextuelleTest`)
  + 2 hôte (`KotlinLanguageServerTest`), 432 au total sur `:lsp:kotlin`
  (0 échec), 248 sur `:lsp:host` (0 échec) ; baseline de parité
  re-synchronisée (fichiers=302 — le corpus EST le dépôt, re-sync
  délibéré).

## 1.17.0 (2026-10-29)

- **Diagnostics par déclaration sur texte vivant (chantier P1-A de la
  feuille de route du rapport d'analyse du 28/10, ADR 0227)** : la
  garde historique « un marqueur d'erreur de parse éteint TOUT le
  sémantique » devient une garde PAR DÉCLARATION. Une frappe au bas du
  fichier (nouvelle déclaration en cours) n'éteint plus les preuves du
  reste du document — précisément le moment où l'utilisateur regarde
  les soulignés :
  - **régions propres** : les familles sémantiques n'émettent que dans
    les déclarations dont le sous-arbre parse proprement (FUN,
    PROPERTY, CLASS, OBJECT_DECLARATION, TYPEALIAS, ENUM_ENTRY, à toute
    profondeur — un membre propre d'une classe cassée reste diagnostiqué) ;
  - **gardes d'absence pour `kt.unresolved`** (preuve d'ABSENCE, D3) :
    un nom DÉCLARÉ quelque part dans le document (déclaration en cours
    de frappe ou avalée par la récupération) n'est jamais signalé ;
    une zone package/import brisée rend les noms importés non fiables
    (silence sur tout le document) ; un en-tête de classe brisé
    (supertypes, paramètres de type, clause where, constructeur primaire)
    rend les membres hérités illisibles — les membres propres restent
    diagnosticables mais l'absence s'y tait ;
  - **arité** : une surcharge du document courant hors région propre
    (paramètres avalés) n'est pas un témoin d'arité fiable —
    `kt.argumentCount` l'écarte ;
  - **chemin propre inchangé** : un document sans marqueur d'erreur ne
    passe jamais par la garde ; un document sans aucune région propre
    reste muet (l'ancien comportement devient un cas particulier).
- Tests : +8 (`DiagnosticsParDeclarationTest`), 417 au total sur
  `:lsp:kotlin`, 0 échec ; baseline de parité re-synchronisée
  (fichiers=299 — le corpus EST le dépôt, re-sync délibéré).

## 1.16.0 (2026-10-28)

- **Aide de signature robuste (texte vivant cassé)** : la frappe en
  cours (`add(`, `add(1, `, `add("abc`, `add(1, par`) laissait le
  popup VIDE — ecj jette le MessageSend en recovery quand l'appel
  n'est pas refermé ou l'argument non résoluble, et la chaîne de
  résolution (arbre focalisé, parseCache, SourceFocuser) rendait null.
  Un **niveau 4** de variantes réparées referme l'appel avec un
  argument synthétique et les blocs ouverts : texte tel quel, littéral
  apposé (`null`/`0`), argument courant remplacé (`null`/`0`/`true`)
  — la première variante qui rend un appel résolu gagne. Le «;» de
  fermeture est inséré avant la première accolade ; le splice n'ajoute
  rien avant le caret, le paramètre actif est exactement celui de la
  frappe. Les **constructeurs sont énumérés** (`new StringBuilder(`
  sert (), (int), (String), (CharSequence)) et leur label perd le
  « void » redondant.
- **Complétion contextuelle** :
  - **Filtres de position grammaticale** — `extends` d'une classe →
    classes concrètes non finales ; `implements`/`extends`
    d'interface → interfaces ; `throws`/`catch` → sous-classes de
    Throwable ; `new` → classes concrètes ; `@anno` → annotations.
    Les mots-clés disparaissent des positions spécialisées, les
    candidats non résolus restent proposés (plateforme absente) ;
  - **Stubs d'override en corps de classe** — les méthodes
    surchargeables du graphe des super-types en snippets complets
    (`@Override` + signature + corps super/throw + arrêt `$0`),
    indentés, excluant les méthodes déjà déclarées ; couvre le chemin
    synthétique du marqueur nu en corps de classe ;
  - **Suggestions de noms en déclaration** — `Button but|` →
    `button`, `b` (suffixes camel-hump lowerFirst : `HttpURLClient` →
    `httpClient`, `urlClient`, `client`), pour locales, champs et
    paramètres ; contexte dédié qui ne propose rien d'autre ;
  - **Constantes d'enum** — labels de `case` (switch instruction et
    expression) et valeurs d'attribut d'annotation enum (paires
    nommées et forme mono-valeur `@Anno(LO|`) ;
  - **Attributs d'annotation** — `@Anno(lev|` → attributs non fournis,
    insertion avec « = ».
- Tests : +19 (7 signature `JdtInlayAndSignatureTest` + 12
  `CompletionContextIntelligenceTest`), 449 au total sur :lsp:java,
  0 échec.

## 1.15.0 (2026-10-26)

- **Programme utilisateurs — vague 5** (retour utilisateurs
  25/10/2026, `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2) : items 1
  et 2 du backlog — les quatre mondes du programme sont à ZÉRO faux
  positif déclaré : le monde Compose de Léa ET le monde KMP de Nina
  rejoignent celui de Marc (leurs contrats E2E sont VIDES : tout
  nouveau FP casse la CI).
  - **RÉCEPTEURS DE PORTÉE des lambdas de scope** (U1 « weight »/
    « align » — le n° 1 du backlog) : `Row { Modifier.weight(1f) }`
    — le paramètre `content: RowScope.() -> Unit` de la fonction
    consommatrice prête `RowScope` comme this IMPLICITE à tout le
    corps du littéral. Membres NUS et MEMBRE-EXTENSIONS (`weight`
    vit DANS RowScope, récepteur d'extension = premier paramètre
    JVM) résolvent, miroir kotlinc. Candidates des DEUX côtés :
    PROJET (type écrit `A.() -> B`, texte exact) et CLASSPATH
    (l'index rend `Function1<RowScope, Unit>` — premier argument de
    type, nom court résolu par l'UNICITÉ du nom simple dans la vue :
    RowScope ne s'importe jamais). PILE des littéraux emboîtés
    (ColumnScope puis RowScope). Un membre introuvable n'y prouve
    rien — le vrai positif `grincher` est gardé. BORNE D3 documentée
    : kotlin-metadata 2.2.10 ne distingue PAS `A.() -> B` de
    `(a: A) -> B` (sérialisation `Function1<A, B>` IDENTIQUE, expérience
    vague 5 — l'annotation `kotlin.ExtensionFunctionType` n'est pas
    rendue par l'API) : le premier argument peut être un premier
    paramètre ordinaire, un nom nu membre de ce type est alors tenu
    pour résolu — sous-rapportage borné, jamais de rouge mensonger ;
  - **DESTRUCTURING de paramètre de lambda** (U3 « total ») :
    `sortedByDescending { (_, total) -> total }` — un paramètre à
    parenthèse directe (le type écrit vit dans le NŒUD enfant
    TYPE_REFERENCE) prête chaque composant nommé (`_` exclu) comme
    locale de portée, miroir kotlinc — le vrai positif d'un nom
    inconnu dans le corps est gardé ;
  - **PROPRIÉTÉS BUILTINS de Map** (U3 « entries ») :
    kotlin.collections.Map n'a aucun `.class` (expect dans
    `collections.kotlin_builtins`, actual JVM java.util.Map) —
    `entries` → `entrySet()`, `keys` → `keySet()` (garde
    ALIAS-AWARE : la chaîne de supertypes SAUTE le nom aliasé) ;
    alias `Mutable*` complétés dans la chaîne ADR 0218
    (`buildList { add(…) }` — `MutableList<E>.() -> Unit` rendu par
    l'index, maillon java.util.List).
  - 8 tests K5 nouveaux (`ReproductionVague5Test` — scope
    projet/classpath, pile, non-fuite, destructuring ×2, builtins
    Map, dont 2 vrais positifs) ; contrats E2E durcis (U1 et U3 :
    listes de limitations VIDES).

## 1.14.0 (2026-10-26)

- **Programme utilisateurs — vague 4** (`cac026a`,
  `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2) : les items 1 à 4 du
  backlog — le monde Views/XML de l'utilisateur Marc atteint **ZÉRO
  faux positif** (contrat E2E VIDE : tout nouveau FP casse la CI),
  Léa passe de 6 à 2 FP restants, Nina de 4 à 2.
  - **PILE des this IMPLICITES** (membres hérités du classpath, le
    n° 1 du quotidien) : l'objet ANONYME le plus interne ouvre la
    marche par SA clause écrite, PUIS les classes nommées
    englobantes prêtent chacune la leur — `submitList` nu dans un
    `object : Filter()` sous un adaptant voit Filter PUIS ListAdapter
    (miroir kotlinc, récepteurs empilés) : submitList, getItem,
    addItemDecoration, setOnClickListener, getString ÉTEINTS ;
  - **Types IMBRIQUÉS des supertypes par nom simple** : `fqcn$Nom`
    trouvé sur la fermeture transitive = résolution — règle kotlinc
    PROUVÉE par expérience (hériter met les imbriqués en portée ;
    l'IMPORT du conteneur seul ne suffit PAS) : FilterResults nu en
    signature d'override, ViewHolder, ItemDecoration, State ;
  - **Frontière `?.` des sélecteurs** (bug TRANSVERSAL du moteur) :
    le jeton `?.` [89..91] captait le décalage 91 — le DÉBUT du
    sélecteur suivant : la référence n'entrait JAMAIS dans la chaîne
    d'ancêtres (`estSelecteurQualifie` rendait `false`, faux positif
    sur CHAQUE sélecteur d'accès sûr — `results?.values` ; le jeton
    `.` d'UN caractère ne déclenchait pas le bug, `?.` de DEUX si).
    La priorité d'enfant : contenant STRICT [debut, fin) > frontière
    fermée (curseur collé à la fin d'un mot — comportement historique
    préservé) > enfant vide (ADR 0218) ;
  - **Paramètres du constructeur PRIMAIRE en zone de CONSTRUCTION**
    (expérience kotlinc : initialisateurs et `init` les acceptent,
    corps de méthode et setters custom les refusent) :
    contexte/attributs/styleDef de `super(...)` ÉTEINTS, le VRAI
    POSITIF d'un paramètre hors zone RATTRAPÉ ; le setter custom
    prête son paramètre et `field` ;
  - **FQN complets sans import EN EXPRESSION** (miroir E4 des types)
    et **imbriqués qualifiés en POSITION DE TYPE** :
    `androidx…LinearLayoutManager(this)` et `ViewModelProvider.Factory`
    résolvent (fqcn complet dans la vue → tous les segments muets) ;
    introuvable → premier et derniers segments faillibles, le milieu
    muet — jamais trois rougeurs sur une seule faute ;
  - **Appels GÉNÉRIQUES liés par leur lambda** : `fun <T>
    remember(calculation: () -> T): T` top-level sans récepteur se
    lie par la DERNIÈRE expression de son littéral (locale ET
    récepteur-appel) — showSnackbar ÉTEINT ; le DÉCODEUR de
    métadonnées rend désormais le retour PARAMÈTRE DE TYPE (« T »,
    `KmClassifier.TypeParameter` était muet) ;
  - **Récepteur implicite de `apply`/`run`** (miroir `with`) et
    **unicité du nom simple dans la vue** (le type écrit `TextView`
    du champ ViewBinding vit dans le contexte du fichier DÉCLARANT,
    hors du modèle du scan) : style/color/strokeCap dans `apply { }`,
    chaînes de binding vers `context.getString(...)` — currency,
    textSize, setPadding ÉTEINTS.
  - 12 tests K5 nouveaux (`ReproductionHeritageVague4Test`) dont un
    VRAI POSITIF rattrapé ; contrats E2E durcis (U2 : liste de
    limitations VIDE).

## 1.13.0 (2026-10-26)

- **Programme utilisateurs du 25/10/2026 — trois vagues de correctifs**
  (`docs/RETOURS-UTILISATEURS-2026-10-25.md`) : quatre utilisateurs
  simulés (projets Android réels CONSTRUITS et BUILDÉS — Compose
  Material3, Views/XML+Java mixte, KMP expect/actual, scripts Gradle
  kts) ont éprouvé la chaîne LSP de production sur corpus
  normalisation v2 ; les 115 faux positifs consolidés en ~15 familles
  ont été attaqués par priorité de quotidien.
  - **Vague 1 — six familles** : arité des constructeurs à VALEURS PAR
    DÉFAUT (le FP n° 1 : « 4 fourni(s), 6 attendu(s) » sur un
    constructeur à défauts) ; membres SYNTHÉTISÉS des data classes
    (`copy`/`componentN`) ; GETTERS Compose (signature
    `(Composer,int)` — `MaterialTheme.typography`,
    `Arrangement.spacedBy` résolvent) ; MANGING des inline classes
    (`spacedBy-0680j_4` → `spacedBy(Dp)`) ; types IMBRIQUES du
    classpath (`fqcn$Nom` : `Icons.Filled`, `Paint.Cap` — sélection
    et typage) ; imports `kotlin.*` IMPLICITES (`@OptIn`,
    `@Suppress`) + imports de DÉLÉGATION `by` (`getValue`/`setValue`
    non marqués inutilisés) + garde erasure (un `Object` EFFACÉ n'est
    jamais preuve d'absence — le récepteur `remember { }` reste muet
    D3).
  - **Vague 2 — membres des sources JAVA du projet** (le monde mixte,
    47 FP chez l'utilisateur Views) : `SourcesJava` extrait champs +
    méthodes de niveau 1 avec conteneur = FQCN, signature ANCRÉE en
    tête de segment (un appel d'initialisateur n'est jamais lu comme
    membre), propriétés SYNTHÉTIQUES des getters (`getTitre()` →
    `titre`, miroir ADR 0219) — diagnostic, complétion (`vinyle.` :
    0 → 21 items) et typage en chaîne (`v.getTitre().length`)
    fonctionnent ; **R sans import** (même paquet) :
    `fqcnDuNomEcrit` sonde la vue du classpath pour un nom du paquet
    du document — miroir kotlinc, le `R` du build v2 résout.
  - **Vague 3 — trois familles** (tête du backlog) : le CALLEE suit
    son récepteur TYPÉ (`vueModele.effets.collect { }` — un callee
    d'appel qualifié dont le récepteur porte un type sans preuve
    (générique, classe illisible) est MUET D3, `collect` et ses
    semblables éteints) ; le modificateur `data` est ENFIN LU —
    `estData`/`estEnum` sondaient les jetons DIRECTS du nœud classe
    alors que les modificateurs vivent dans le MODIFIER_LIST ENFANT :
    la branche copy/componentN de la vague 1 était du CODE MORT,
    corrigée les data classes émettent leurs membres synthétisés
    (complétion `p.` propose `copy`) ; PREUVE D'ABSENCE PROJET — un
    type du projet SANS héritage écrit (la contrainte d'un paramètre
    générique `T extends Comparable<T>` n'est PAS un supertype)
    porte exactement ses membres DÉCLARÉS + les implicites de
    Any/Object : `CalculatriceBudget.consulterInconnu` est SIGNALÉ
    (vrai positif rattrapé), héritage écrit/enum/annotation/extension
    homonyme ou scan non abouti → silence D3.
  - **Correctif CI — course WARM** : le test d'inline disque écrivait
    un fichier APRÈS `definirClasspath` — le scan WARM amorcé par la
    sync pouvait aboutir (dossier vide) AVANT l'écriture et figer un
    snapshot sans le fichier (runner 2 cœurs = fenêtre ouverte).
    `sessionProjet(fichiersDisque)` écrit les fichiers DISQUE AVANT
    la sync — la course est fermée par construction (ADR 0212).
  - **ADR 0224** (addendum ADR 0113) : le classpath applicatif est
    acquis comme celui d'Android Studio PAR CONSTRUCTION (mêmes
    modèles AGP v2 — `BasicAndroidProject`, `AndroidProject`,
    `VariantDependencies` — dans la même action Tooling API du démon
    Gradle) ; les heuristiques restent localisées aux scripts
    `.gradle.kts` et aux règles de diagnostic, PAS dans le classpath.
  - **Comptes** : Léa (Compose) 45 → 6 FP restants ; Marc (Views)
    Java IMPECCABLE (0 FP), 29 restants (membres hérités classpath) ;
    Nina (KMP) cross-module et expect/actual PARFAITS, 4 restants ;
    les contrats E2E des quatre mondes durcis (limitations connues
    listées en dur — tout nouveau faux positif casse la CI) ;
    353 tests moteur + 246 hôte verts, baseline de parité 281
    fichiers.

## 1.12.0 (2026-10-25)

- **Chantier « suites recommandées du rapport d'audit §8 » — ADR
  0222/0223** (les suites n° 3 et 4 livrées au moteur, les n° 1 et 2
  documentées pour CodeIDE — `docs/INTEGRATION-CODEIDE-LSP-KOTLIN.md`).
  - **Companion objects — ADR 0222** : le nom d'une classe Kotlin
    PRÊTE les membres de son `companion object` (`Boite.creer()`,
    `Regex.escape(…)`, `UInt.MAX_VALUE`) — le patron idiomatique
    était un non-fonctionnement de bout en bout. Côté projet : le
    compagnon ANONYME porte son nom effectif « Companion » (le repli
    générique `nomJeton()` lisait le PREMIER MEMBRE — le compagnon
    était enregistré « creer », ses membres sous « pkg.Boite.creer »,
    et la même heuristique poisonnait `classeEnglobante`,
    `nomConteneur` et `estMembreHerite`) ; les membres sont émis DEUX
    FOIS (sous `pkg.Boite.Companion` ET sous la classe `pkg.Boite`).
    Côté classpath : `fqcn$Companion` sondé sur le régime STATIQUE de
    toutes les capacités — typage des chaînes, complétion membre,
    survol, signatures, occurrences. L'OBJET « Companion » s'offre
    après le nom de la classe, miroir kotlinc.
  - **Sélection d'appelé par types complets — ADR 0223** : quand le
    RÉCEPTEUR d'une sélection TYPE, la cascade du survol qualifié
    (champ → méthode → accesseur synthétique → compagnon ADR 0222 →
    membre du projet → extension sur le récepteur) décide. PRÉSENCE
    PROUVÉE : le callee membre d'instance d'un récepteur typé RÉSOUT —
    le faux positif LATENT `layoutInflater.inflate(1, null, null)`
    (le callee membre n'est pas un appelable de portée, la résolution
    par nom seul ne pouvait pas le trouver) est réparé. ABSENCE
    PROUVÉE : `s.inexistant`, `tv.setTexe(…)` redeviennent signalés —
    miroir Android Studio. Gardes D3 : classe illisible, session sans
    entrées d'index (`connaitDesEntrees`), homonyme d'extension
    quelconque → silence inchangé.
  - **Typage nullables/génériques** (`fqcnDuNomEcrit`) : le nom écrit
    est NORMALISÉ (`String?`, `List<Int>` → la classe cible, miroir
    `traduireTypeEcrit`) — tout récepteur local de type nullable ou
    générique (`binding?.root`, `items.size`) restait muet en typage,
    complétion et sélection par types.
  - **Modèle Gradle (suite §8.1, tranche livrée)** : E2E
    `ReproductionGradleModeleE2ETest` — un script `.gradle.kts` à
    classpath Gradle RÉEL (jars de la distribution) : zéro faux
    `kt.unresolved`, `JavaVersion.VERSION_11` survolé et complété
    (`VERSION_11 … VERSION_20`), le tout par la machinerie standard
    SANS code dédié aux scripts ; le provisionnement et le routage
    session-« scripts » sont documentés pour CodeIDE.
  - **Sélectivité source sets KMP (suite §8.2)** : modèle documenté
    pour CodeIDE (une session par source set, classpath par variante
    Gradle) — l'union actuelle reste correcte en diagnostic.
  - **Baseline de parité** : régénérée (`-Dparite.majBase=true`) — le
    corpus du dépôt a gagné les nouveaux tests et les sources
    modifiées.
  - **Gardes de non-régression** : le VRAI négatif sélecteur
    (`s.inexistant`) signalé au milieu du stdlib réel ; le récepteur
    NON typable reste muet (D3 inchangé) ; l'objet simple (ADR 0220)
    et le this implicite du corps du compagnon inchangés.

## 1.11.0 (2026-10-25)

- **Chantier « monde NON-Compose (Views) » — ADR 0219** (le deuxième
  monde Android : vues XML, AppCompat, ViewBinding — même niveau de
  conformité Android Studio que le monde Compose, zéro faux positif
  sur le template canonique).
  - **Bug CRITIQUE de l'index disque — les 8 octets du pool** :
    `AnalyseurClassFile.lirePoolConstantes` incrémentait l'index des
    constantes Long/Double (deux slots) sans SAUTER les 8 octets de
    leur valeur — la constante suivante se lisait DANS la valeur et
    TOUTE la section membres de la classe était perdue.
    `android.content.Context` (seule classe de la chaîne AppCompat
    portant un `long`) était muette : `getString`/`getColor`/
    `getPackageName` introuvables — et toute classe du classpath
    avec un `long`/`double` subissait le même sort (Java compris).
    `FormatSegment.VERSION` passe à 2 : les segments v1 (CRC valide
    de contenu faux) sont refusés et re-indexés paresseusement ; le
    test « Long/Double deux slots » est renforcé (classe forgée AVEC
    un long dans le pool — l'ancienne version ne forgeait jamais le
    cas).
  - **Profondeur de chaîne corrigée** : `BORN_PROFONDEUR` 6 → 12 +
    budget total de 256 maillons — la hiérarchie Android réelle
    (AppCompatActivity → … → Context) exige PROFONDEUR 7 :
    `getString` vivait au maillon 7 et restait un faux
    `kt.unresolved` alors que le classpath portait tout.
  - **Propriétés SYNTHÉTIQUES Java** (interop cœur du langage,
    miroir Android Studio) : un getter `getFoo()` se lit `foo` —
    `layoutInflater` nu dans une Activity, `title = …` via
    setTitle, `packageManager`, `classLoader`, `contentResolver`…
    Le catalogue (`getterSynthetique`/`setterSynthetique`/
    `proprieteSynthetiqueDe` — zéro paramètre, retour non void,
    public/protégé, non statique, `getClass` exclu, `getURL` →
    `URL`) alimente le DIAGNOSTIC (voie 2-ter + récepteur `with`),
    le TYPAGE (sélecteurs, this implicite, appels nus — la chaîne
    `layoutInflater.inflate(…)` type), la COMPLÉTION membre
    (`val layoutInflater: LayoutInflater` offert en tête des listes
    du monde non-Compose) et le SURVOL (qualifié et le NOUVEAU survol
    implicite : `val layoutInflater: LayoutInflater —
    android.app.Activity`, `var` quand le setter existe).
  - **`super.` et `this.` au niveau Android Studio** : `super.` se
    TYPE comme le premier supertype ÉCRIT résolu (repli classe
    englobante) ; la complétion après `this.`/`super.` sonde la
    classe + ses supertypes écrits (porteurs) — membres binaires,
    membres projet et extensions : 100 items sur le template réel
    (dont `theme`, `title`, `assets` — les synthétiques).
  - **ViewBinding K5 dans son VRAI paquet** : AGP génère TOUJOURS
    les bindings dans `<paquet>.databinding` (ViewBinding compris) —
    l'import réel du template matche désormais le groupe synthétique
    (complétion `binding.` offrant `root`/`textView`/`inflate`/
    `bind` au lieu de rester muette).
  - **Corpus E2E Views RÉEL** : 27 artefacts (kotlin-stdlib 2.2.10,
    android-36, appcompat 1.7.0, fragment 1.8.5, activity 1.9.3,
    core 1.15.0, core-ktx, lifecycle 2.8.7, savedstate, material
    1.12.0, constraintlayout 2.2.0… — variable `REPRO_VIEWS_DIR`) ;
    trois scénarios E2E : template « Empty Views Activity » (AUCUN
    faux positif), VRAI négatif (`setContenuVeu` TOUJOURS signalé —
    garde D3), forme ViewBinding (AUCUN faux positif + assertions :
    survols `layoutInflater`/`getString`, complétions `binding.` et
    `super.`). Moteur : `ReproductionViewsTest` (11 tests, chaîne
    factice des 8 maillons réels) + `ReproductionViewBindingTest`.
  - **Suites** : moteur 307, index 82, hôte 238 — toutes vertes,
    corpus Compose ET Views branchés.
- **Chantier « Kotlin MULTIPLATFORME » — ADR 0220** (le troisième
  monde : module `shared` à DEUX racines sources — le paquet y vit
  deux fois, expect dans commonMain, actual dans androidMain).
  - **Membres d'OBJETS enfin scannés** : la branche `else` du scan
    (`SymbolesProjet.extraire`) ajoutait la déclaration d'`object`
    sans JAMAIS parcourir son corps — tout membre de singleton
    Kotlin (`Fabrique.creer()`, `expect object` des libs KMP) était
    un faux `kt.unresolved` : complétion et survol muets. La nouvelle
    branche `KtObjectDeclaration` parcourt le corps (miroir exact de
    la branche classe) — BONUS pour TOUT projet Kotlin, pas
    seulement KMP.
  - **Objets TYPES référençables** : `classesProjet` (la table de
    `fqcnDuNomEcrit`) n'acceptait que classes et interfaces —
    `Objet.membre` ne typait pas, la complétion membre après
    `Fabrique.` était muette. Le genre OBJET rejoint la table.
  - **Le reste du KMP tenait déjà** (constaté par reproduction
    verrouillée) : `expect`/`actual` fun/class/val, `actual
    typealias`, usages croisés des source sets, génériques sur un
    type expect, définition rendant l'expect D'ABORD puis l'actual
    (miroir Android Studio).
  - **Corpus** : `ReproductionKmpTest` + `ReproductionKmpAvanceTest`
    (moteur, 10 tests) et `ReproductionKmpE2ETest` (E2E production à
    DEUX sourceDirs : zéro faux positif commonMain/androidMain/usages,
    complétion `Fabrique.` → `creer()`, définitions expect→actual,
    typo `platformNaim` toujours signalée — garde D3).
  - **Suites** : moteur 317, index 82, hôte 239 — toutes vertes,
    corpus Compose + Views + KMP branchés.
- **Fichiers GRADLE KOTLIN DSL (.gradle.kts) — ADR 0221** (demande
  explicite du propriétaire ; constat : 41 + 17 = 58 faux
  `kt.unresolved` sur les build.gradle.kts et settings.gradle.kts
  du template).
  - **La règle** : le récepteur implicite d'un SCRIPT est le
    CONTEXTE HÔTE (DSL Gradle : `plugins`/`android`/`dependencies`/
    `implementation`/`alias`…, version catalog `libs`, imports du
    DSL `JavaVersion`/`rootProject`) — un nom NU ou PREMIER SEGMENT
    ne peut pas être prouvé absent : muet (miroir Android Studio
    sans modèle Gradle). Les segments QUALIFIÉS écrits restent
    faillibles (`java.io.Fichier` reste signalé — garde D3).
  - **Aucune perte de vrai négatif** : les sélecteurs au-delà du
    premier segment étaient déjà muets (récepteur inconnu) — seuls
    les nus et récepteurs racines le deviennent.
  - **Corpus** : `ReproductionGradleKtsTest` (moteur, 5 tests —
    build + settings du template à zéro, Kotlin standard du script
    résolu avec survols, garde D3) et `ReproductionGradleKtsE2ETest`
    (E2E production : le build.gradle.kts du template ouvert dans
    le serveur LSP → AUCUN faux positif, typo qualifiée signalée).
  - **Suites** : moteur 322, index 82, hôte 240 — corpus Compose +
    Views + KMP + script Gradle branchés.

## 1.10.0 (2026-10-25)

- **Chantier « parité LSP Kotlin officiel d'Android Studio » — ADR
  0217/0218** (suite du retour Compose : zéro faux positif sur le
  template canonique, et chaque capacité répond comme dans l'IDE de
  référence).
  - **ADR 0217 — découvertes E1/E2 du rapport Compose** : paramètres
    EXPLICITES de lambda (`Scaffold { innerPadding -> … }`) enfin en
    portée (`FUNCTION_LITERAL` collecte sa `VALUE_PARAMETER_LIST` —
    tout identifiant de paramètre de lambda était un faux
    `kt.unresolved`) ; noms d'ARGUMENTS nommés (`text = …`,
    `showBackground = true`) jamais signalés (`estNomArgument` — le
    compilateur ne résout pas ces noms comme des références, miroir
    kotlinc/Android Studio).
  - **ADR 0218 — parité collections et déclarations** : alias
    JVM↔Kotlin étendus à TOUTE la hiérarchie des collections
    (Iterable/Collection/List/Set/Map/Iterator/ListIterator/Map.Entry)
    et aux types usuels (CharSequence/Number/Enum/Throwable/Comparable/
    Comparator) dans les DEUX sens — `chaineSupertypes` lit enfin les
    octets d'une fiction Kotlin et `variantesRecepteur` traduit les
    maillons JAVA parcourus en clés d'extension KOTLIN (`filter`/
    `map`/`forEach` survolés et complétés depuis `List`/`Set`/`Map`) ;
    repli Android Studio au MOT-CLÉ (`references`/`documentHighlight`/
    `renommage` répondent le curseur sur `fun`/`class`/`override` —
    en-tête borné à la SIGNATURE, le corps et l'initialiseur ne visent
    jamais la déclaration englobante) ; `chaineAncetresA` préfère un
    enfant NON VIDE — la `PACKAGE_DIRECTIVE` vide ne capture plus la
    descente à l'offset 0 (fichiers sans paquet, scripts `.kts`).
  - **Corpus E2E Compose COMPLET** : les artefacts transitifs
    manquants du template « Empty Activity » sont téléchargés et
    extraits (foundation-**layout** 1.7.4 — `padding`/`fillMaxSize` y
    vivent, SÉPARÉS de foundation depuis Compose 1.5 ; ui-unit 1.7.4 —
    `dp`/`sp` ; ui-geometry, ui-graphics, ui-text, ui-tooling-preview,
    ui-util, runtime-saveable, annotation-jvm) : le corpus documente
    désormais le jeu EXACT d'artefacts que livre le protocole tooling
    v8 — un artefact manquant = des symboles muets (leçon du
    débogage : foundation sans foundation-layout laissait `padding`
    introuvable).
  - **Sondage des capacités E2E verrouillé par des assertions** :
    survol `Text`/`Modifier` (signatures réelles material3/ui),
    complétion membre après `modifier.` (100 items dont `padding`),
    `signatureHelp` `Text(`, previews, `references` au mot-clé `fun`
    (déclaration + usage), `documentSymbol` (hiérarchie Either
    correctement lue) ; la définition d'un symbole CLASSPATH reste
    vide par contrat K3 (pas de source attachée — le survol porte la
    signature, D3 documenté).
  - **Gardes de non-régression** : le VRAI négatif (typo `Texte`)
    reste signalé au milieu du template (D3 inchangé) ;
    `plageRenommable`/`renommage` hors identifiant dans un littéral
    restent SANS cible ; curseur dans le CORPS (`return`) → aucune
    cible (l'en-tête seul vise la déclaration).
## 1.9.1 (2026-10-25)

- **Chantier « complétion Java : rapidité + honnêteté »** (réponse au
  retour propriétaire : « les suggestions de complétion sont devenues
  lentes », « le popup de complétion propose des trucs juste après une
  déclaration de variable ou un retour à la ligne », « est-ce normal
  d'avoir des suggestions `import com.example.monip.R$style;` ? »).
  - **Cache négatif de présence source (lenteur)** — chaque
    `find(fqcn)` de la résolution ecj sondait le disque AVANT TOUT
    (`Files.isRegularFile` PAR racine source, y compris pour les types
    du classpath : des centaines d'appels par passe, la seule hiérarchie
    `View` en touche ~200). Sur le stockage FUSE d'un appareil (0,5 à
    5 ms par stat), une complétion payait jusqu'à plusieurs secondes de
    stats — × 3 à 5 splices par requête. Le cache négatif (borné 8192,
    TTL 30 s) mémorise « ce FQCN n'est pas une source du projet » ; les
    positifs restent lus à chaque fois (fraîcheur du contenu garantie).
    Fraîcheur à la CRÉATION : `didChangeWatchedFiles` (Created/Deleted)
    invalide les caches de présence via `invalidateSourcePresence()` —
    une nouvelle classe résout sans attendre le TTL ; le walk FQN des
    sources passe de 10 s à 30 s de TTL pour le même prix.
  - **Plus de classes internes brutes en complétion (`R$style`,
    `R$id`, `R$string`)** — trois chemins de l'index disque PRÊT
    servaient les entrées binaires imbriquées telles quelles : la
    complétion d'import (`import com.example.monip.|` proposait
    `R$style`), la complétion de types par préfixe (la clé d'index
    « style » rendait le FQN `com.example.monip.R$style` — un nom
    impossible à écrire en Java source) et le quick-fix « Importer »
    (`fqnsForSimpleName`). Le filtre `$` est posé sur les TROIS chemins
    disque + le chemin jrt (l'image indexe `NumberFormat$Style`
    verbatim, attrapé par le matcher substring) — parité retrouvée avec
    l'index mémoire qui les a toujours exclus. Les imbriquées restent
    proposées là où elles sont LÉGITIMES : l'accès membre `R.` (forme
    Java `R.style`, via les bindings ecj).
  - **Caractères déclencheurs de complétion resserrés à `. @`** — le
    serveur annonçait `[".", " ", "(", ","]` : le client cel ouvre le
    popup à préfixe VIDE dès que le caractère précédent est annoncé
    déclencheur, donc CHAQUE espace (et l'auto-indent du retour à la
    ligne, qui finit par un espace) ouvrait le popup avec la liste
    bruyante noms en portée + tous les mots-clés. Discipline de
    l'éditeur de référence : `.` rouvre (accès membre), `@` rouvre
    (annotations), tout autre caractère non-identifiant referme. Les
    `(` et `,` restent déclencheurs de SIGNATURE HELP (leur vrai
    rôle). Miroir des serveurs Kotlin (`.` `:`) et XML.
  - Tests : `FuiteClassesImbriqueesTest` (index disque PRÊT factice
    mimant la production, 4 scénarios — les trois chemins + le
    contexte annotation), `CachePresenceSourceTest` (négatif TTL,
    invalidation par événement de surveillance, positifs toujours
    frais), `PopupSpontaneReproTest` (repro documentée des scénarios du
    retour), `LatenceCompletionBenchmark` (banc de mesure — 5 à 9 ms à
    chaud sur module réaliste côté JVM).

## 1.9.0 (2026-10-24)

- **Chantier « retour propriétaire E2E » — ADR 0215/0216** (réponse au
  retour du propriétaire : « ActivityMainBinding/Greeter cannot be
  resolved » côté LSP Java, « Référence non résolue : setContentView /
  instantiate » et « beaucoup de diagnostics unresolved » côté LSP
  Kotlin sur un projet Android Compose).
  - **ADR 0215 — routage fichier → module du serveur Java (découverte
    D6)** : les moteurs PAR MODULE du lot E (racines sources + jars +
    android.jar du modèle) n'étaient JAMAIS consultés — TOUS les
    services LSP Java utilisaient le contexte DÉFAUT (android.jar
    seul). `CompilationEnvironment` gagne la couture
    `RouteurModules` + `jdtPourFichier(chemin)` (consultée à CHAQUE
    requête, repli explicite sur le défaut : routeur absent/fautif =
    comportement historique au bit près) ; `SessionLsp` branche
    `AssociationFichier` (code mort depuis le lot E) sur l'instantané
    du dépôt de modèle ; tous les handlers (diagnostics, hover,
    complétion, definition/typeDefinition/implementation/super,
    references, rename, codeAction, folding, inlayHints, codeLens,
    hiérarchies, semanticTokens) routent vers le moteur du module
    propriétaire — un type du MÊME PAQUET (`Greeter`) et un ViewBinding
    de la racine `generated` (`ActivityMainBinding`) résolvent ENFIN.
    `workspace/symbol` unifie défaut + moteurs vivants (plafond global
    dédupliqué), `decompiledSource` sonde les moteurs vivants, la
    pré-chauffe d'`initialized()` couvre défaut + modules NOURRIS,
    `EcjNavigationEngine` devient un wrapper PAR moteur (WeakHashMap),
    et le downgrade ERROR→HINT du paquet `.databinding` parle
    désormais des classes de liaison ViewBinding/DataBinding (elles y
    vivent TOUTES les deux — générées par AGP au BUILD, la sync ne les
    crée pas).
  - **ADR 0216 — membres HÉRITÉS et segments de TYPES QUALIFIÉS
    (découvertes E3/E4)** : nouveau `kt.unresolved` sans faux positifs
    sur projets Android — voie 2-ter « membres hérités » de
    `ResolutionReferences` (fermeture transitive mixte classpath+projet
    des supertypes ÉCRITS, port du GO TO Super pour les maillons
    frères, bornes honnêtes : profondeur 16, 96 sondes, anti-cycle,
    sources Java jamais traversées) : un appel NU à
    `setContentView(…)`/`instantiate(…)` dans une classe dont le
    supertype binaire porte le membre n'est PLUS signalé ; le
    quick-fix d'import partage le MÊME prédicat (jamais d'import
    proposé pour un appel résolu par héritage). Les SEGMENTS d'un type
    QUALIFIÉ écrit (`android.os.Bundle` → trois REFERENCE_EXPRESSION)
    sont mus quand le type COMPLET vit dans le classpath/projet — sinon
    seul le DERNIER segment (nom de classe) est diagnostiqué, miroir
    kotlinc. Le port vit dans `DiagnosticsSemantiques.Contexte` et la
    session fournit toujours celui du GO TO Super (arbre vif du frère
    OUVERT, lecture bornée sinon).
  - **Preuves** : 8 tests routeur (`CompilationEnvironmentRouteurTest`),
    13 tests moteur (`SessionLangageKotlinHeritageTest`), 7 scénarios
    E2E Java (`ScenariosJavaAndroidTest` — serveur stdio branché sur la
    VRAIE `SessionLsp`, sync rejouée APRÈS didOpen : le live-republish
    efface les erreurs du propriétaire sans frappe) et 4 scénarios E2E
    Kotlin (`ScenariosKotlinHeritageTest` — classpath de production
    kotlin-stdlib + android.jar 37.2). Toutes les suites historiques
    restent vertes (parité du corpus re-synchronisée : +6 fichiers de
    test).

## 1.8.0 (2026-10-24)

- **Chantier « demandes CodeIDE » D1–D5 — ADR 0211/0212/0213/0214**
  (réponse à `CodeIDE/docs/DEMANDES_CODEIDE_LSP.md`, écran d'indexation
  multi-moteurs v0.178.0 ; 1 237 tests verts : 267 moteur Kotlin +
  258 XML + 82 index + 409 Java + 221 hôte dont 71 scénarios E2E
  transport réel qui empruntent désormais le NOUVEAU flux
  `etatIndexation` de bout en bout).
  - **ADR 0211 — nourrissage moteur UNE fois par sync, supplier de
    connexion sans E/S (demande D1)** : `MoteurLangageKotlin/
    MoteurLangageXml.definirClasspath` (SPI, défaut `false` = packs
    anciens) nourrit l'état PARTAGÉ sur un fil de fond dédié ;
    `IndexAppelablesKotlin` gagne la GARDE d'empreintes (mêmes
    empreintes dans l'ordre = no-op chaud), un CACHE MÉMOIRE par
    empreinte (un segment lu ne retourne jamais au disque ; un échec
    n'y vit jamais — la reprise le retente) et le contrat `couvre`/
    `nourrissageParMoteur` ; les sessions se BRANCHENT sans re-lire
    (chaque changement d'onglet `.kt` devient un no-op chaud, l'ANR
    disparaît structurellement). Sous-lecture honnête : pendant la
    passe en vol, les diagnostics `kt.unresolved` se TAISENT sur le
    classpath (index non couvert, recalculé à CHAQUE requête) et les
    documents ouverts se REPUBLIENT quand la passe aboutit
    (`KotlinLanguageServer`/`XmlLanguageServer.republierDocumentsOuverts`,
    branchés sur l'événement final). XML (annexe D1-d) : même loi —
    `IndexRessources` observe ses passages, un AAR/values échoué n'est
    JAMAIS mis en cache mtime, la purge ne s'exécute pas sur
    annulation (le dernier-connu-bon survit), et le catalogue XE2 est
    PRÉCHAUFFÉ dans la passe (son coût quitte la première frappe).
  - **ADR 0212 — le serveur Kotlin ne bloque plus (demande D2)** : le
    FILET de scan synchrone sous verrou est SUPPRIMÉ — `declarations()`
    JOINT le WARM en vol, sinon rend le snapshot et AMORCE (une requête
    précoce répond honnêtement moins riche, jamais en bloquant tout le
    serveur) ; le drapeau `pret` distingue « passage abouti » (même
    VIDE — un projet sans `.kt` est un état légitime) d'une
    invalidation ; la marche du scan PRÉCÈDE le parse (vrai X/N). La
    session passe au verrou LECTURE-ÉCRITURE : diagnostics, complétion,
    survol et previews de documents DISTINCTS tournent EN PARALLÈLE
    (audit de pureté : helpers purs ou à état par appel ;
    `ClassesSynthetiques` synchronisé en interne — le seul point fauve).
    Deux courses de publication refermées par construction : le
    résultat du WARM se publie DANS le corps de la tâche (un lecteur
    qui joint est garanti de le voir) et le vol se libère AVANT
    l'achèvement (plus de « vol fantôme » bloquant l'amorce suivante).
  - **ADR 0213 — contrat de progression Kotlin + XML (demande D3)** :
    `SessionLspKotlin.etatIndexation` et `SessionLspXml.etatIndexation`
    — `StateFlow<EtatIndexation>`, le MÊME type public que Java,
    alimentés par les ÉVÉNEMENTS NEUTRES des moteurs (types du SPI, zéro
    kotlinx-coroutines dans les packs — observateur SAM + poignée
    `Closeable`) : phases (BIBLIOTHEQUES = segments kidx / catalogue XE2
    + AAR P3, SOURCES = scan WARM / racines `res/`), liste de travail
    par entrée (EN_ATTENTE → ACTIF → REPRIS/TERMINE/ECARTE), breakdown
    par indexeur, durées par phase, bilan du dernier passage,
    `settled`, `reprises` — la parité avec l'écran Java est TOTALE et
    l'agrégat CodeIDE branche le flux SANS autre changement côté hôte.
    Fenêtres de repli pour les packs anciens (`definirClasspath` =
    `false`) : CONSTRUCTION au départ de sync, PRET après la poussée
    synchrone — le comportement d'avant, jamais de PARTIEL inventé.
  - **ADR 0214 — contrôles de passage (demandes D4 + D5)** :
    `SessionLsp/SessionLspKotlin/SessionLspXml.annulerPassage()` et
    `.reesayerEchecs()` (retour `false` = rien à faire — l'écran
    retire/grise l'offre, jamais d'exception, packs anciens compris).
    Annulation COOPÉRATIVE : drapeau testé ENTRE les unités (segments
    Kotlin, fichiers du scan, AAR/racines XML ; côté Java les
    `ensureActive` du `Job` existant), le passage publie son ACQUIS et
    se marque PARTIEL « annulé par l'utilisateur » (`invokeOnCompletion`
    côté Java — publication sans course). Reprise CIBLÉE : le cache
    rend les unités saines REPRIS sans re-travail (visible à l'écran) ;
    côté Java, `ServiceIndexDisque.reesayerEchecs` ne relance QUE les
    positions ECARTÉES avec statistiques SEMÉES (les messages des
    retentées partent, la phase finale est recalculée) — les deux
    boutons de la maquette (« Réindexer » / « Réessayer les entrées
    échouées ») deviennent enfin DISTINCTS.

## 1.7.0 (2026-10-24)

- **Panneau GO TO complet et SYMÉTRIQUE Kotlin/Java — ADR 0208/0209/0210**
  (audit du panneau Go To des deux serveurs, caractérisation bout-en-bout,
  870 tests verts : 249 moteur + 212 hôte dont 71 scénarios E2E transport
  réel + 409 Java dont 15 scénarios d'audit façade).
  - **ADR 0208 — KDoc aux usages CROSS-FICHIERS (option 2 de l'ADR 0207)**
    (Kotlin, survol) : la déclaration d'un AUTRE fichier du projet
    retrouve sa KDoc au site d'usage — port injecté session → moteur
    (`Contexte.kdocDeclaration`) : document OUVERT → arbre VIF (aucun
    re-parse, jumelage exact par plage du nom), fichier FERMÉ → lecture
    disque + parse bornées (UNE fois par survol, budget du balayage
    d'occurrences, zéro état inter-requêtes). Correctif de dominance
    associé : `declarationsProjet`/`contexteNomDe` fusionnent désormais
    les arbres vivants de TOUS les documents ouverts (les documents
    tiers ouverts étaient servis par leur copie disque PÉRIMÉE du scan —
    plages fausses au premier go-to-definition/survol après édition).
    Sondes : D1 inversé (usage cross-fichier = MARKDOWN + KDoc) et S13
    (l'arbre vif du document tiers édité gagne sur le disque).
  - **ADR 0209 — définition/définition de type Java CROSS-FICHIERS**
    (Java, navigation) : `textDocument/definition` et `typeDefinition`
    quittaient le confinement intra-fichier — le chemin PROJET par
    bindings ecj (`findDefinitionProject`/`findTypeDefinitionProject`)
    localise la classe DÉCLARANTE (convention de chemin puis REPLI
    BALAYAGE BORNÉ pour les fichiers multi-types), borne le NOM au
    jeton, et se branche AVANT le fallback binaire (une cible projet ne
    tombe JAMAIS dans le stub décompilé). Deux bugs latents corrigés :
    (a) `EcjAst.bindingAuCaret` — la référence qualifiée `a.tag` visait
    le RÉCEPTEUR (convention ecj : premier segment dans `binding`, champ
    dans `otherBindings`) : définition, survol et navigation du caret
    suivent désormais le SEGMENT VISÉ (le renommage portait déjà la
    preuve — politique unifiée) ; (b) `locateTypeDeclaration` gagne un
    GUARD binaire (pas de balayage pour un supertype du classpath) et
    son repli balayage — `findSuper`/hiérarchies en profitent. Audit :
    `AuditNavigationJavaTest` (15 scénarios façade : méthode, méthode
    héritée, champ, type, constructeur, import statique, homonymes par
    bindings, locale intra-fichier, binaire décompilé ×2, ordre des
    chemins, robustesse).
  - **ADR 0210 — GO TO « Super » Kotlin (`textDocument/
    superDefinition`)** : l'option Super du menu contextuel unifié
    existait pour JAVA uniquement — elle est SYMÉTRIQUE désormais.
    Moteur `SuperKotlin` : (1) chemin MEMBRE — la cible occurrences
    prouve le membre (déclaration outrepassante ou usage résolu), la
    clôture TRANSITIVE des supertypes projet (BFS borné, anti-cycles)
    contribue ses homonymes de même genre (`« name  ·  Super »`) ;
    (2) chemin TYPE — les supertypes DIRECTS ÉCRITS du type en contexte
    (`« Simple  ·  pkg »`). Hôte : interface covariante `@JsonDelegate`
    (piège LSP4J des méthodes custom), DTO `NavigationTarget` PARTAGÉ
    avec le serveur Java (chemin absolu + offsets + libellé), capability
    `experimental.superDefinitionProvider`. E9 du harnais au transport
    réel par le CÂBLE cel-lsp exact — découverte au passage : les
    méthodes custom du client production vivent au SOMMET de son
    interface distante (le check « Duplicate RPC method » de lsp4j
    0.22 refuse la redéclaration covariante côté client, et la réponse
    d'une méthode inconnue du proxy se désérialise en null) ; borne O3
    verrouillée (usage ambigu d'un membre homonyme → vide, jamais un
    faux jalon).
  - TABLE_KOTLIN de la parité L0 gains `super-définition (expérimental)` ;
    baseline parité corpus re-roulée (digeste 5ea7ccb9… → v1.7.0).

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
