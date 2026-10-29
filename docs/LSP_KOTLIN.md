# LSP Kotlin — cadrage de mission (v0.109.0)

> Document de cadrage. La mission elle-même (K1–K5 ci-dessous) démarre sur
> « GO » du propriétaire. Étude comparative préalable : projet **tiers de référence**
> (auteur tiers, cloné en référence, GPL-3.0 — aucune ligne de code réutilisée,
> enseignements d'architecture seulement).

## 1. Objectif et contexte

Offrir aux projets **Kotlin** de CodeIDE la même qualité d'expérience que le
LSP Java (v0.91.0→v0.108.0) : diagnostics en temps réel, complétion
membre/nom/type, hover, go-to-definition, quick-fixes — **in-process**, sans
processus externe, dans l'esprit « Android Studio sur appareil » demandé par
le propriétaire. Le LSP Java a posé l'infrastructure réutilisable
(SessionProjet, RegistreFermeture, classpath modèle v8, index disque segments
ADR 0111, LSP4J, routage `jo.codeide.projet_cible`) ; le LSP Kotlin s'y
branche au lieu de la re-dériver.

La contrainte structurante est double : un appareil Android courant plafonne
le tas applicatif (`dalvik.vm.heapsize`, ~576 Mo mesurés par le projet tiers de référence), et
la taille de l'APK de CodeIDE doit rester maîtrisée (§6 : distribution en
« pack langue » téléchargeable, différée à post-stabilisation sur décision du
propriétaire).

## 2. Enseignements de l'étude du projet tiers de référence

Le projet tiers de référence est l'IDE Android embarqué le plus avancé publiquement documenté
sur ce problème. Faits établis par lecture directe du dépôt :

- **Aucun LSP** (aucun lsp4j) : SPI maison in-process (`LanguageBackend` →
  `SourceAnalyzer` sur un DOM neutre). Le protocole LSP est jugé inutile en
  in-process.
- **Éditeur Kotlin = parseur seul + modèle de symboles propre** (~31 000
  lignes `commonMain`). Citation : « Running the Kotlin compiler's frontend
  (FIR/resolution) for every keystroke is too heavy for an on-device editor ».
  Toute la sortie FIR/résolution du compilateur est jetée.
- **Évolution en trois temps** : (1) `kotlin-compiler-embeddable` comme hôte
  de parse + K2 pour le build, l'Analysis API dans un classloader isolé (deux
  plateformes d'IDE = enfer de classloaders) ; (2) unification en un split
  `-for-ide` **non-relocalisé** + plateforme d'IDE réelle, fusionnés en un
  jar unique de 65 Mo / ~30 000 classes ; (3) abandon de l'arbre syntaxique d'IDE pour
  l'édition : **parseur multiplateforme vendoré** — la grammaire du compilateur
  elle-même (`compiler/multiplatform-parsing`, ~10 216 lignes, Apache 2.0) +
  la bibliothèque de syntaxe du compilateur (dépendance réellement déclarée, Apache 2.0), derrière une façade `Kt*` de ~1 971 lignes.
- **Parité syntaxique vérifiée, pas visée** : suite différentielle sur le
  corpus du compilateur (719/774 fichiers, écarts tracés dans une baseline qui
  ne peut que rétrécir) et 2381/2381 sur leur propre dépôt.
- **Décodage du classpath** : `kotlin-metadata-jvm` pour les bibliothèques
  Kotlin (extensions, nullabilité, arguments par défaut), ASM pour les types
  Java/Android ; index disque à clés taggées (`top:<nom>`,
  `ext:<receiverFqn> <nom>`) → complétion O(matchs), tas plat.
- **Classes synthétiques** injectées par point d'extension pour `R`,
  ViewBinding, `serializer()` : elles résolvent **avant tout build**, sans
  génération — voie directe pour notre dette databinding (§5-K5).
- **Le compilateur sur ART coûte cher** : 9 passes ASM au dex (ClassValue,
  VarHandle, `java.io` API 33, dispatch fastutil, caffeine, `PathUtil`,
  Introspector, Unsafe, FastJarFS), `kotlinc-resources.zip` extrait au runtime,
  stdlib embarquée en ressource, et **fork de VM persistante** pour compiler
  (tas plafonné). Conclusion : le compilateur ne doit **jamais** être sur le
  chemin critique de l'édition.
- **L'Analysis API JetBrains** n'y est plus utilisée que par KSP2, jamais par
  l'éditeur.

## 3. Décisions

| # | Décision | Motif |
|---|----------|-------|
| D1 | **Option « parseur vendoré + modèle de symboles propre »**, en deux temps : T1 éditeur (cette mission), T2 compilation K2 (différée au tooling). L'option « serveur de langage Kotlin externe » est **écartée** (processus externe + LSP + embarque tout le compilateur : contraire à l'architecture in-process et à la leçon du projet tiers de référence). L'option Analysis API K2 est **réduite** à un éventuel « mode diagnostic profond » à la demande, jamais par frappe. | Comparaison §2 ; mémoire ART ; cohérence LSP Java. |
| D2 | **Parseur = grammaire officielle du compilateur, vendorée** (`compiler/multiplatform-parsing` + la bibliothèque de syntaxe du compilateur (dépendance réellement déclarée, Apache 2.0), Apache 2.0), jamais réécrite. Suite de parité obligatoire dès K1. | La parité devient une propriété vérifiée ; le projet tiers de référence a gratté ce coût avant d'y renoncer. |
| D3 | **Modèle de symboles maison conservateur** : périmètre T1 volontairement réduit (voir §4) ; toute forme non modélisée **sous-rapporte** (pas de faux positif), philosophie identique aux diagnostics W de LSP_JAVA.md. | Un sous-ensemble correct bat un modèle complet faux ; l'utilisateur a un build pour la vérité. |
| D4 | **Réutilisation maximale de l'infra LSP Java** : SessionProjet/RegistreFermeture/I1-I7, classpath v8 (ADR 0113), index disque segments par jar (ADR 0111), LSP4J, routage `projet_cible`, journal `SessionProjet`. | Évite la dérive « deuxième tour de roue ». |
| D5 | **Classes synthétiques** pour `R`, `BuildConfig`, ViewBinding et DataBinding dans le modèle de symboles, généralisant la sonde `hasGeneratedDatabindingClasses` (v0.108.0). Objectif : éliminer le délai « databinding/R invisibles avant build », remplacer à terme le downgrade HINT par une résolution réelle. | Preuve projet tiers de référence (EP synthétiques) ; ferme la décision ouverte ① de LSP_JAVA §17.3 dans le sens le plus fort. |
| D6 | **Distribution « pack langue » téléchargeable, différée** : l'APK embarque T1 (estimation ~2–4 Mo) ; l'architecture est dès K1 conçue pour un découpage ultérieur en pack téléchargé (SPI mince, module isolé, aucune référence directe depuis l'app). Le découpage réel n'aura lieu qu'**après stabilisation** (§6). Décision du propriétaire (2026-10-13). | Taille APK maîtrisée ; pas de premature abstraction. |
| D7 | **Licence** : zéro ligne GPL du projet tiers de référence. Vendoring Apache 2.0 depuis les sources JetBrains amont (licite, notice `THIRD_PARTY_NOTICES.md` + `docs/adr` de vendoring). | Conformité. |
| D8 | **T2 hors périmètre de cette mission** : la compilation Kotlin (K2 in-process, split `-for-ide` fusionné, recettes projet tiers de référence : passes ASM, resources-zip, worker forké → lien S6) sera cadrée dans `docs/TOOLING.md` quand le tooling compilera le Kotlin. | Une mission = un objectif. |

## 4. Architecture T1 — module `:lsp:kotlin`

Parallèle de `:lsp:java` (mêmes conventions : kover ≥ 80 % ligne, detekt,
spotless, TDD, diagnostics documentés).

### 4.1 Structure proposée

```
:lsp:kotlin
  parse/       Vendoring + façade Kt* (parsage tolérant, LightSyntaxTree)
  symbols/     Modèle de symboles neutre : KotlinSymbol, KotlinType, portées
  classpath/   Décodeurs : kotlin-metadata-jvm (libs Kotlin), ASM (Java/Android)
  index/       KotlinCallableIndex disque : clés taggées top:/ext: (ADR 0111)
  resolve/     Résolution déclarative + inférence bornée + smart casts positionnels
  completion/  Marqueur dummy-identifier, classement, auto-import
  diagnostics/ Catalogue kt.* conservateur, downgrade/datation (cf. v0.108.0)
  synthetics/  R, BuildConfig, ViewBinding, DataBinding (sonde réutilisée)
  session/     Adaptateur LSP4J sur SessionProjet (même cycle de vie que lsp:java)
```

### 4.2 Pipeline

Document LSP → parse incrémental (façade `Kt*`, re-parse du sous-arbre
modifié, repli parse complète) → DOM neutre → services (complétion,
diagnostics, hover, définition) → publication LSP4J `publishDiagnostics`.
Le classpath v8 (déjà transporté par la session) alimente `classpath/` :
scan paresseux persistant par jar (empreinte de contenu, segments ADR 0111),
`@Metadata` décodée seulement pour les jars qui ont un module Kotlin
(saut des autres sans décoder une seule classe).

### 4.3 Périmètre sémantique T1 (réduit, extensible)

- **Résolution** : portées depuis les parents syntaxiques ; locaux typés par
  leur initialiseur ; retours de membres et d'appels ; constructeurs ; chaînes
  `a.b().c` ; surcharges par nom + arité (pas par types complets en K3) ;
  paramètres/récepteurs de lambda et SAM en K4.
- **Smart casts positionnels** : branches `is`/`!is` des formes `if`, `when`,
  `&&`/`||`, garde à sortie précoce (K4).
- **Complétion** : membre après `.`/`?.` (membres ∪ extensions par chaîne de
  supertypes), nom/porte, type, infix ; `expect`ed-type (Boolean/enum) ;
  mots-clés par position grammaticale. **Complétion contextuelle vague 1**
  (ADR 0228, 1.18.0) : ARGUMENTS NOMMÉS de l'appel englobant (noms des
  surcharges prouvées, déjà fournis exclus, fusionnés au régime de nom),
  CONSTANTES d'enum des branches d'un `when` à sujet enum (projet ∪
  classpath, contexte exclusif), ATTRIBUTS d'annotation non fournis
  (`@Anno(lev|` → `level = `, contexte exclusif) — le séparateur voyage
  avec le candidat (`texteInsertion` → `insertText` LSP).
- **Diagnostics initiaux** : `kt.unresolved`, `kt.typeMismatch` (cas simples),
  `kt.argumentCount`, `kt.unsafeNullable`, `kt.missingReturn`,
  `kt.unreachable`, `kt.valReassign`, `kt.unusedImport`,
  `kt.conflictingImport` — chacun avec sa fixture TDD et son test de
  sous-rapport.
- **Quick-fixes** : import (un correctif par FQN candidate, position triée) ;
  **implémenter les membres manquants** (P1-C, ADR 0229 — stubs de la
  clôture des supertypes, projet substitué + classpath sous garde
  générique, correctif SANS diagnostic) ; **correctifs d'expression**
  (chantier 3, ADR 0232 — `kt.unsafeNullable` → `?.` sur le point /
  `!!` sur le récepteur / entourage `if (nom != null)` de l'instruction
  englobante ; `kt.typeMismatch` → changement du type écrit pour le
  représentant de la famille / conversion standard `.toX()` — matrice
  fermée, jamais de cast `as` illégal ; dérivés des MÊMES marches que
  les diagnostics, re-prouvés à la requête).

### 4.4 Protocole et complétude des capacités

> **État 1.3.0 (lot L2 du plan 1.3.0, 21/10/2026)** — au-delà de la
> complétion/survol/définition historiques, le serveur Kotlin annonce et sert
> désormais : **formatage BORNÉ** (`textDocument/formatting` et
> `rangeFormatting` — ré-indentation canonique SEULE, ADR 0202 : le contenu des
> lignes n'est jamais réécrit, refus honnête sur document en erreur, chaînes
> brutes/KDoc/commentaires intacts, idempotent), **définition de type**
> (`typeDefinition`) et **implémentations** (`implementation`) par PREUVE FQCN
> (le nom du type résout par les imports du fichier du curseur ; chaque
> héritier candidat prouve son supertype par les imports de SON fichier —
> jamais d'homonymie), **indices en ligne** (`inlayHint` : noms de paramètres
> aux appels positionnels PROUVÉS par la chaîne de l'aide à la signature,
> types CERTAINS des déclarations sans type écrit — littéraux canoniques et
> constructeurs résolus, jamais un type inféré deviné — et indices de
> LITTÉRAUX DE FONCTION, ADR 0231 : le `it` implicite rend `it: Type` après
> l'accolade ouvrante, les paramètres explicites sans type écrit rendent
> `: Type` sur leur nom, preuve par l'attente du consommateur du littéral), **symboles d'espace**
> (`workspace/symbol` : déclarations nommées du projet, requête contient,
> locales exclues, plafonnées) et **chaînes de sélection** (`selectionRange` :
> nœuds englobants du plus profond au fichier entier). Les JETONS SÉMANTIQUES
> ne sont PAS annoncés (la distinction qu'ils portent n'est pas fiable au
> niveau du parseur — D3, dette consignée dans la matrice des capacités).
> Réglables : FORMATAGE et INDICES_EN_LIGNE entrent dans `CapacitesAnnoncees`
> (table `CAPACITES_KOTLIN`, parité verrouillée). Preuves : tests moteur
> (FormateurKotlinTest, TypesKotlinTest, CapacitesL2KotlinTest) et transport
> réel (KotlinLanguageServerL2Test).

La session Kotlin expose `textDocument/completion`, `hover`, `definition`,
`publishDiagnostics` — mêmes formes que la session Java. Le routage v0.106.0
(`extra jo.codeide.projet_cible`) est réutilisé tel quel. La **compliance par
projet** (décision ouverte ② de LSP_JAVA §17.3) est reportée au protocole v10
de la mission Kotlin : champ `languageVersion` du module dans le
`ClasspathModule` (la grammaire vendorée est indépendante du niveau de
langage ; seul le modèle de symboles en dépend marginalement).

## 5. Étapes de livraison (indicatif)

| Étape | Contenu | Version cible |
|-------|---------|---------------|
| **K1** ✅ | **Livré v0.110.0** : vendoring grammaire **v2.3.0** (bootstrap 2.2.x = notre outillage 2.2.10 — ADR 0115, zéro modification locale, VENDOR.md + `scripts/vendor-grammaire-kotlin.sh`), façade `Kt*` + `ParseurKotlin` + arbre immuable tolérant, suite de parité (corpus dépôt **818 fichiers, 0 marqueur d'erreur** + digeste baseline + fixtures + tolérance troncature/injection), SPI ServiceLoader `MoteurLangageKotlin` + descripteur de pack `version.properties`, rapport de taille (**jar 257 Ko**, cible ≤ 4 Mo), kover 91,7 %, notices tierces. | v0.110.0 |
| K2 ✅ | **Livré v0.112.0** : session LSP Kotlin minimale — contrat de session DERRIÈRE le SPI (`SessionLangageKotlin` : ouvrir/modifier/fermer + diagnostics NEUTRES `DiagnosticKotlin`/`SeveriteKotlin`, le moteur ne voit pas LSP4J — D6) ; diagnostics **syntaxiques** conservateurs : `kt.syntax` (marqueurs d'erreur du parseur, message amont repris), `kt.unusedImport` (nom effectif absent des identifiants du fichier — joker et directives en édition jamais signalés), `kt.conflictingImport` (même nom effectif, noms qualifiés distincts — signalé sur le plus tardif) ; cycle de vie complet : `:lsp:host` porte l'adaptateur LSP4J (`KotlinLanguageServer` — sync complète, publication traduite offsets→positions UTF-16, plages vides élargies, capacités honnêtes : sync seule) et la façade `SessionLspKotlin` (définitions cel-lsp `kt`/`kts`, UNE session moteur par connexion, suivies et refermées exhaustivement) ; éditeur : session Kotlin ouverte dans le MÊME verrou que Java (registre Fermable I2/I7, DELAI_FERMETURE_LSP partagé), LspProject unique portant les définitions des DEUX langues (routage par extension cel-lsp), `estKotlin()` (.kt/.kts), dégradation honnête (moteur absent → coloration seule, jamais bloquant). 25 tests (16 moteur via SPI + 9 hôte en vrai flux LSP4J client espion) ; baseline parité repoussée à **835 fichiers**. | v0.112.0 |
| K3 ✅ | **Livré v0.113.0** : symboles + classpath + les TROIS requêtes — **complétion** (membre après `.`/`?.` : membres DÉCLARÉS de la chaîne de supertypes (vue JVM de l'hôte) ∪ membres du PROJET (déclarations) ∪ EXTENSIONS de l'index (récepteur + supertypes + alias JVM/Kotlin) ; noms : locales > membres de l'englobante > premier niveau > projet (auto-import) > types du classpath (auto-import) > appelables de premier niveau > mots-clés ; imports : sous-paquets/types du paquet tapé), **survol** (chaîne de résolution : nom déclaré → locale → membre qualifié (propriétaire classpath) → déclaration projet → type → appelable en portée), **définition** (PROJET seul à K3 — document courant + racines sources ; les cibles classpath n'ont pas de source attachée, le survol porte leur FQN, sources-jars = K4+). Infra : **vue de classpath NEUTRE** (`VueClasspathKotlin` — D4 : la vue d'index fusion ADR 0111 est PARTAGÉE avec le LSP Java par `SessionLsp.vueClasspathKotlin()`, adaptateur côté hôte, cache de ZipFiles borné) ; **lecteur de class-files maison** (pool, supertypes, `@Metadata` reconstruite — sans ASM) ; **décodeur jars/AAR** (`kotlin-metadata-jvm` 2.2.10, porte `META-INF/*.kotlin_module`, facades + parties multi-fichiers) ; **index disque des appelables** (`kotlin-appelables/*.kidx` par empreinte W6, clés taggées `top:`/`ext:`, écriture atomique, auto-guérison — un jar inchangé n'est jamais redécodé) ; **scan paresseux des racines sources** (partagé par le moteur, documents ouverts dominants) ; résolution conservatrice (locaux par initialiseur, retours écrits, constructeurs, chaînes `a.b().c`, supertypes par lecture brute, alias `kotlin.String`↔`java.lang.String`). Le contrat de session gagne `definirClasspath/completion/survol/definitions` avec **défauts vides** (un pack K2 face à un hôte K3 dégrade honnêtement). 27 tests (définition/décodeur sur le stdlib RÉEL, index disque, requêtes via SPI + vrai flux LSP4J). | v0.113.0 |
| **K4** ✅ | **Livré v0.115.0** : **smart casts positionnels** (branche THEN d'un `if`, entrée de `when` SANS sujet, opérande droit d'un `&&`, garde `nom !is T` à sortie précoce return/throw dans le bloc englobant ; un `||` dans la condition annule toute déduction, l'ombrage par une locale déclarée APRÈS la condition est tranché par les portées, les génériques sont rabattus sur le nom de base) ; **inférence bornée des paramètres de lambda** (attente du consommateur PROJET : paramètre à type de fonction écrit d'une fonction candidate, SAM d'une interface à méthode unique, initialiseur de propriété au type de fonction écrit — `it` implicite compris ; récepteurs `with` et attentes du classpath = K5+, sous-rapport) ; **diagnostics sémantiques** — `kt.unresolved` (chaîne complète de résolution K3/K4 : portées, projet, classes, appelables de l'index ; sélecteurs qualifiés et `it` jamais signalés, régime sans classpath exclu ; texte vivant = garde PAR DÉCLARATION (ADR 0227) : seules les déclarations propres sont diagnostiquées, un nom déclaré dans le document ou une zone package/import/en-tête de classe brisée coupe la preuve d'absence)), `kt.typeMismatch` (familles de littéraux vs primitives écrites, conversions littérales légales admises), `kt.argumentCount` (candidates du PROJET, plage légale élargie par les défauts, vararg inéligible, constructeurs primaires compris), `kt.unsafeNullable` (récepteur simple au type écrit nullable, gardes `!= null` et `== null` précoce honorées), `kt.missingReturn`, `kt.unreachable` (première déclaration morte d'un bloc), `kt.valReassign` (locale/paramètre/membre, affectation simple seule) ; **quick-fixes d'import** : `importsCandidats` au contrat de session (FQCN du classpath portant EXACTEMENT le nom non résolu, déjà importés exclus, défaut VIDE — tolérance de version du pack) + `textDocument/codeAction` côté hôte (un CodeAction par FQN, `kt.unresolved` du contexte ciblé, insertion à l'heuristique partagée après le dernier import, plafond 8). Correctifs de fond : `classesProjet` du contexte K3 toujours VIDE (double garde `!= CLASSE`/`!= INTERFACE` rejetait tout), `octetsClasse` de l'adaptateur hôte corrigé pour les conteneurs COMPOSITES `aar!classes.jar` (v0.114.1). 36 nouveaux tests (31 moteur par SPI + 5 hôte en vrai flux LSP4J), kover moteur 84,1 %, baseline parité repoussée à 868 fichiers. | v0.115.0 |
| **K5** ✅ | **Livré v0.116.0** : **classes synthétiques** (`synthetics/`, décision D5 — généralisation de la sonde `hasGeneratedDatabindingClasses` v0.108.0) : `R` (sous-objets par famille démontrée + constante `Int` par entrée — layouts/ids/`values`/noms de fichiers), `BuildConfig` (type seul — les champs du build ne sont JAMAIS inventés), ViewBinding (`ns.<Layout>Binding` : champs des `@+id` typés par le porteur — composants `android.*` connus mappés, tags qualifiés pris tels quels, inconnu rabattu sur `View` — plus `root`, `inflate`/`bind`), DataBinding (racine `<layout>` = la PREUVE ; classe sous `ns.databinding`, variables `<data>` comprises) — tout cela **avant tout build**, COUPÉ dès qu'une classe générée réelle homonyme existe dans le scan (groupe entier effacé, la sortie du build est la source de vérité) ; sources : racine `src/main/java\|kotlin` → frères `src/main/res` + `AndroidManifest.xml` (convention AGP, manifeste sans `package` = aucun synthétique, zéro FQCN inventé), DOM borné partout. **Multi-langue Java↔Kotlin** : `session/SourcesJava.kt` extrait les types de premier niveau des `.java` (neutralisation commentaires/chaînes, profondeur 0 seule — une imbriquée ne devient JAMAIS top-niveau) → complétion auto-import, résolution, survol, go-to-definition vers le `.java` ; marqueur `estJava` : `kt.argumentCount` IGNORE ces candidates (arité non extraite — jamais de faux « 0 attendu »). **Récepteurs `with`** (reliquat K4) : `resolve/RecepteursWith.kt` — le littéral le plus profond gagne, les emboîtés TRAVERSENT (chaîne des récepteurs implicites Kotlin) : complétion de nom, typage des noms nus, résolution `kt.unresolved` (membres classpath + projet). **Attentes de lambda du CLASSPATH** (reliquat K4) : `vue.setOnClickListener { v -> }` — type du récepteur → surcharge → paramètre → SAM Java à méthode abstraite unique → types des paramètres du littéral. **Correctifs de fond** : typage STATIQUE des chaînes qualifiées (`R.layout.x` — récepteur-référence résolu comme TYPE, membres projet compris) et membres du PROJET dans le typage de sélecteur ; `kt.unresolved` ne signale plus les SEGMENTS d'import/package (« com » de `import com.ex.app.R` — faux positif multi-segments découvert par les fixtures K5). **Correctif éditeur (ANR e7dd5a7, retour appareil v0.115.0)** : le nourrissage LSP (réouverture depuis modèle persisté + post-sync) reconstruit les index Java + Kotlin (décodage kotlin-metadata de chaque jar, ≥ 10 s sur appareil d'entrée de gamme) — il vit désormais sur `DispatcherProvider.io` INJECTÉ dans `EditorViewModel`, le thread principal rend la main immédiatement (test dédié : thread d'exécution observé ≠ principal). 17 nouveaux tests moteur + 1 éditeur ; baseline de parité repoussée à 873 fichiers (re-sync délibéré). | v0.116.0 |
| **C1** ✅ | **Livré v1.4.0 (preview Compose — PREVIEW.md §4.4 de CodeIDE, consommé par la carte « goutte d'eau » de l'éditeur)** : **découverte `@Preview @Composable` par le SPI** — `previews(uri)` (DÉFAUT VIDE, tolérance de version) rend un modèle NEUTRE `PreviewKotlin` : nom + plage, paramètres de l'annotation (`widthDp`, `device`…) TRANCHÉS dans la source, et le CORPS réduit en ARBRE D'APPELS (`AppelFonctionKotlin` / `ExpressionNonInterpreteeKotlin` — jamais l'arbre, jamais un type Compose, frontière D6) ; preuve FQCN PAR LES IMPORTS (`fqcnDuNomEcrit` — un nom DÉJÀ QUALIFIÉ est comparé tel quel, un homonyme sans import est ignoré, D3) ; arguments LITTÉRAL/RÉFÉRENCE/AUTRE distingués (`a.b` = référence, `a.b()` = AUTRE), lambda final = contenu, lambdas passés en ARGUMENT hors interprétation C1 ; **inlining des composables du PROJET** (candidate top-level prouvée `@Composable` par les imports de SON PROPRE fichier — document ouvert d'abord, disque du scan ensuite — SANS paramètres en C1 ; la fonction racine est SEMÉE dans la pile : l'auto-récursion est un CYCLE typé, jamais une boucle infinie ; budgets 500 nœuds / profondeur 6, marche TRONQUÉE et signalée `coupureBudget` — garde-fous PC4 côté moteur, échecs TYPIQUES `PROJET_CYCLE`/`PROJET_BUDGET`/`PROJET_PARAMETRES`/`PROJET_ILLISIBLE`) ; façade annotations (`parse/kt/KtAnnotationEntry.kt` : `KtAnnotationEntry` + `KtDeclaration.annotations()` + `KtFunction.corps()`) ; passe-plat façade `SessionLspKotlin.previews(uri)` (la session détenant le document répond, panne absorbée). +17 tests (session 13, façade 3, hôte 1) ; baseline de parité re-synchronisée (239 fichiers — nouveaux sources). | 1.4.0 |
| T2 | (Différé, hors mission) Compilation K2 via tooling. | — |

Découvertes de parité PSI à retenir pour K2+ (constatées K1, épinglées en
tests) : une `PACKAGE_DIRECTIVE` **vide** est toujours présente dans
l'arbre ; les imports vivent sous un conteneur `IMPORT_LIST` (lui aussi
toujours présent) ; les jetons blancs sont des feuilles de l'arbre (à
sauter dans les lectures séquentielles).

## 6. Distribution — « pack langue Kotlin » (différé, décision propriétaire)

**Problème** : T1 embarqué est estimé à ~2–4 Mo (grammaire vendorée compilée
+ façade + kotlin-metadata-jvm + ASM déjà présent). C'est acceptable dans
l'APK. Mais T2 (jar compilateur non-relocalisé ~65 Mo dexé, ses ressources et
ses shims) ne le sera **jamais**. Le propriétaire a tranché (2026-10-13) :
le LSP Kotlin doit être architecturé pour devenir une **lib/dépôt que
l'utilisateur télécharge**, mais seulement **après stabilisation**.

**Ce qui est fait dès K1 (coût quasi nul, impossible à retrofitter à moindre
frais)** :

- `:lsp:kotlin` reste un module Gradle **sans dépendance depuis l'app** ;
  l'app ne voit qu'un SPI mince (le même contrat que `:lsp:java` : session,
  capacités, publication diagnostics) derrière `ServiceLoader`.
- Aucune classe du module n'est référencée nommément hors du module ;
  résolution des services par descripteur, pas par type concret.
- Dépendances embarquées confinées au module (kotlin-metadata-jvm n'a pas de
  transitif gênant ; ASM partagé avec lsp:java via la même version épinglée).
- `version.properties` du pack prévu dès K1 (identifiant, version minimale
  d'app, empreinte) même si l'APK l'embarque encore.

**Options de distribution au moment venu** (recommandation : ①) :

1. **Pack maison via l'installateur bootstrap existant** (ADR 0085/0033) :
   téléchargement opt-in post-installation (motif des plateformes Android
   E5), stockage sous `filesDir/packs/kotlin-lsp/`, chargement par
   `DexClassLoader` avec classloader parent contrôlé (motif
   `ArtKotlinPluginLoader` du projet tiers de référence), signature + version minimale +
   désinstallation propre. Avantages : hors Play Store, cohérent avec
   l'installation des outils existante, réutilise l'UI d'installation.
2. Dynamic Feature Modules (Play) : propre mais **exige le Play Store** et
   son pipeline — incompatible avec l'installation directe de CodeIDE.
3. Artefact Maven/JitPack consommé par le build : simple pour le build,
   n'adresse pas le runtime on-device.

**Critères de déclenchement du découpage** (à revoir avec le propriétaire) :
deux versions sans correctif structurel sur `:lsp:kotlin`, retours appareil
réel de F/baseline satisfaisants, et/ou l'arrivée de T2 (le pack devient alors
la seule voie d'hébergement du compilateur).

## 7. Cibles non fonctionnelles

- Latence complétion à chaud : < 100 ms (fichier ≤ 2 000 lignes, index chaud).
- Premier diagnostic d'un fichier ouvert : < 250 ms après parse.
- Tas : plat en régime établi (index disque, aucun scan classe entier résident).
- Taille ajoutée à l'APK (T1 embarqué) : ≤ 4 Mo.
- kover ≥ 80 % ligne sur `:lsp:kotlin`, comme partout.

## 8. Risques et parades

| Risque | Parade |
|--------|--------|
| La grammaire vendorée dérive du compiler amont (2×/an) | Script de re-sync + suite de parité en CI (baseline rétrécissante). |
| Sous-ensemble sémantique décevant vs Android Studio | Contractuel : D3 (sous-rapport), feu de route K3/K4 incrémentale ; « mode profond » Analysis API documenté comme extension possible. |
| Conflits de dépendances (ASM, kotlinx) | Versions épinglées partagées avec `:lsp:java` ; audit `checkDuplicateClasses` en CI. |
| la bibliothèque de syntaxe du compilateur (dépendance réellement déclarée, Apache 2.0) indisponible (hors Central) | Vendoring du **source** amont (défense déjà adoptée par projet tiers de référence), pas de dépendance à un dépôt tiers au runtime. |
| Taille/mémoire réelle > estimation | Mesure dès K1 (tâche de rapport de taille du module) ; le pack §6 est la soupape. |

## 9. Traçabilité

- Remplace ROADMAP « Phase 5.1 LSP Kotlin » (serveur de langage externe —
  écarté).
- Ferme la décision ouverte ① de LSP_JAVA.md §17.3 (génération post-sync →
  remplacée par classes synthétiques D5, plus forte).
- Prépare la décision ouverte ② (compliance par projet → protocole v10,
  §4.4).
- Prérequis de `docs/PREVIEW.md` §4 (preview Compose) pour la partie
  résolveur.
- Cycle de vie : la session Kotlin est un `SessionProjet` de plus dans le
  RegistreFermeture (I7) — tests de rotation/close hérités du socle v0.107.0.
