# LSP Java — étude de l'étape 0 (v0.91.0)

> **Mission** : prompt 2 « LSP Java : modèle de projet, indexation des
> classpaths/sourcesets et intégration ». Ce document est le livrable
> d'analyse de l'étape 0 : vérification des constats W1–W10 **avec
> mesures**, écarts constatés sur les hypothèses du prompt, audit de
> provenance reconstitué, décisions D1–D5 (ADR 0108–0112), plan de
> renommage, étude d'extractibilité, cibles recalées et plan ajusté.
> **Aucun code de production** n'a été écrit pour cette étape.

---

## 1. Rappel de l'objectif

Doter CodeIDE d'un serveur LSP Java fiable et rapide **sur l'appareil**,
dont la qualité dépend d'une connaissance exacte et rapidement
interrogeable du classpath et des sourcesets de chaque module — comme
Android Studio après une synchronisation. Fonctions visées : diagnostics,
complétion, hover, signature help, aller à la définition, références,
renommage, formatage, organisation des imports, quick-fixes, inlay hints,
coloration sémantique, repli de code.

Matériel : `lsp-java-module.zip` (modules `lspjava` 19 423 lignes de
Java main / 48 fichiers, 9 747 lignes de tests / 42 fichiers, `ecj-art`
468 lignes ; dépendances `org.eclipse.jdt.core 3.40.0` patché,
`lsp4j 0.21.2`, `gson 2.10.1`) et `reference/app-lsp/` (câblage de
l'ancienne version, lecture seule).

---

## 2. Écarts constatés sur les hypothèses du prompt (§1 : « vérifie-les »)

Le prompt pose ses constats comme hypothèses de travail. Cinq écarts
substantiels ont été mesurés ; le premier est **critique** pour
l'étape 1.

### 2.1 CRITIQUE — le serveur actuel ne livre PAS de classpath pour les modules Android

Le prompt §6 affirme : « La donnée par module existe donc ; elle n'est
pas consommée par un LSP. » **Vérification : faux pour tout module
Android.** Le modèle `IdeaProject` (exactement ce que
`ClasspathHandler` résout via la Tooling API — même appel, même Gradle
9.7.1) a été interrogé sur le dépôt CodeIDE lui-même :

- 34 modules renvoyés ; **26 modules Android ont ZÉRO** racine source,
  ZÉRO dépendance (`:app`, `core:ui`, `core:storage`, `core:database`,
  toutes les `feature:*`, `tooling:client`, `applog-runtime`,
  `terminal-runtime`…) ;
- seuls les 8 modules Kotlin **JVM purs** ont du contenu
  (`core:domain`, `core:model`, `core:testing`, `tooling:api`,
  `tooling:protocol`, `tooling:server`, `tooling:testing`,
  `generateur`) — au total 13 fichiers uniques (kotlin-stdlib,
  coroutines, serialization, junit, hamcrest, javax.inject,
  annotations), soit un classpath inutilisable pour du Java Android ;
- **pourquoi le test d'intégration passe** : `ServeurIntegrationTest`
  utilise le fixture `multi-module` des tests tooling, un projet
  `plugins { java }` **pur JVM** (`app` + `lib`) — le cas AGP n'a jamais
  été exercé. AGP ne publie pas ses configurations dans le modèle idea
  par défaut.

**Conséquence (étape 1)** : la source du classpath doit être étendue.
Options à arbitrer à l'étape 1 : **(A)** résoudre le modèle
`AndroidProject` d'AGP via la Tooling API (artifact
`com.android.tools.build:builder-model`, licence Apache-2.0 AOSP —
l'approche historique d'Android Studio ; fournit par variante le
`bootClasspath` = `android.jar`, les jars, les dépendances) ;
**(B)** un plugin/model-builder injecté côté serveur (init script via
`withArguments`) exportant un modèle dédié CodeIDE ; **C)** garder
IdeaProject pour les modules JVM et croiser avec (A) pour les modules
Android. Recommandation : **(A)+(C)**, avec normalisation AAR côté
serveur (§2.2).

### 2.2 AAR livrés tels quels (confirmé, ADR 0058)

`ClasspathHandler` livre le chemin du `.aar` avec `kind=AAR` — l'ADR
0058 assume : « l'explosion du bytecode interne reste le périmètre du
LSP qui le consomme ». L'ancienne version recevait des jars déjà
normalisés. **La normalisation (extraction `classes.jar` + `libs/*.jar`)
doit vivre quelque part** : recommandation — dans le serveur de tooling
(une fois, à la résolution) plutôt que dans l'app, car le banc montre
que le jeu « lourd » réel est à **76 % d'AAR** (191/250 entrées après
normalisation) : sans extraction, l'index n'a simplement rien à lire.

### 2.3 Champs du protocole jamais remplis

`ClasspathModule.fichiersGeneres`, `androidJar`, `ignore`,
`raisonIgnore`, `avertissements` existent dans `Messages.kt` mais ne
sont **jamais remplis** par `ClasspathHandler` (toujours
`null`/défauts). Le prompt les présentait comme disponibles. Étape 1 :
les remplir côté serveur (via le modèle AGP de §2.1 pour
`androidJar`/`fichiersGeneres`) ou les retirer du contrat jusqu'à ce
qu'ils le soient.

### 2.4 L'audit de provenance fourni est ABSENT de l'archive

Le prompt annonce `audit-provenance-lsp-java.md` comme matériel fourni.
**Il n'est pas dans l'archive** (137 fichiers, aucun `.md` d'audit). Il
a été reconstitué intégralement (§5) — les constats annoncés se sont
avérés exacts aux nuances de méthode près, mais l'étape 0 ne disposait
pas du §5 (options) ni du §7 (ordre de traitement) annoncés ; ils ont
été reconstruits.

### 2.5 Le nombre de variantes « splicées » est 3–5, pas 4

W8 (§3) : le code construit **3 variantes sans point, 5 après un point**
(+2 synthétiques en dernier recours) ; les commentaires du module
disent « 2 à 4 ». Le `break` au rang 2 réduit le cas nominal à **une
seule** résolution. La lenteur à la frappe vient surtout de la
combinaison (variantes × résolution complète × cache à entrée unique
évincée à chaque `put`), pas du seul nombre.

### 2.6 Complément vérifié — cel-lsp disponible à la version épinglée (§6 du prompt, règle 9)

JitPack, tag `3.41.0` de `jjoblab/code-editor` : build **ok**, modules
`cel-core`, `cel-lsp`, `cel-lsp-api`, `cel-ui` ; `cel-lsp-3.41.0.pom`
et `.aar` résolus (HTTP 200). Dépendances de `cel-lsp` :
kotlin-stdlib 2.2.10, **lsp4j 0.22.0** (runtime), gson — noter l'écart
de version avec `lspjava` (**lsp4j 0.21.2**) : l'étape 4 devra aligner
(ou isoler) les deux.

---

## 3. Vérification W1–W10 (code du module vendored, lecture + preuves)

Résumé des verdicts — détails et extraits dans le journal de travail ;
chaque verdict cite fichier et lignes.

| # | Affirmation | Verdict | Nuance essentielle |
|---|---|---|---|
| W1 | Index mémoire, reconstruit, jamais persisté | **Confirmé** | Reconstruction CONDITIONNELLE (replaceAll no-op si listes identiques) ; préchauffe dès `initialized()` ; didOpen non bloquant (vide + retry 50 ms) atténue le « gel UI » — le coût reste payé intégralement (premier `resolveUnit`) |
| W2 | Structures coûteuses non interrogeables | **Partiel** | `simpleNameIndex` (nom simple → FQNs), `locateCache` et `jarIdxByPath` sont O(1) ; les requêtes par PRÉFIXE et par PAQUET balayent tout (confirmé) ; snapshot 2,5 Mo/jar, jar entier abandonné au-delà |
| W3 | Complétion de types en balayage linéaire | **Confirmé (mesuré)** | Balayage triple : jars sans plafond, `jrtIndex.keySet()` plafonné à `limit*5` PENDANT le balayage, sources sans plafond ; croissance mesurée 17→57→113 ms (p50) de 20 à 250 jars (§4) |
| W4 | Index sources par TTL, pas par événements | **Confirmé** | TTL 10 000 ms, `Files.walk(root, 8)` par racine, rafraîchissement PARESSEUX à l'accès ; `didChangeWatchedFiles` n'invalide que le cache de parse ; rename/navigation font des walks illimités non cachés |
| W5 | Classpath plat, ni module ni sourceset | **Confirmé** | Listes plates + conformité unique « 17 » (constante) ; `WorkspaceModel` (référence) aplatit modules+sourcesets en LinkedHashSet, le nom du sourceset est perdu, pas de api/impl, pas de main/test |
| W6 | Aucune empreinte de classpath | **Confirmé (aggravé)** | Aucun hash, **ni mtime ni taille** : un jar reconstruit au même chemin avec mêmes listes n'invalide RIEN ; invalidation purement structurelle (comparaison de chemins) |
| W7 | Modèle persisté sans validation de fraîcheur | **Confirmé** | `.codeide/workspace-model.json` version 2, filtrage des fichiers morts, sinon « utilisé tel quel » (commentaire « un modèle périmé vaut TOUJOURS mieux qu'un LSP aveugle ») ; état index prêt/en cours interne seulement (isIndexReady/preWarm), aucune notification LSP ; repli = plateforme seedée |
| W8 | Concurrence et caches | **Partiel** | `resolveUnit` synchronized (verrou global) confirmé ; 3–5 variantes splicées (pas 4) mais `break` au rang 2 → 1 résolution nominale ; BindingParseCache à entrée unique confirmé (évince à chaque put, asymétrie codegen) ; files 3 s/15 s/15 s, debounce 50 ms confirmés |
| W9 | Lectures binaires | **Partiel** | Pool LRU de **24 poignées** persistantes avec lectures sérialisées (l'affirmation « réouverture transitoire à chaque lecture » est inexacte pour l'index) ; les lectures transitoires vivent dans `JdtBinarySourceNames` (sources.jar) ; RawZipReader = RandomAccessFile + Inflater, motivation CloseGuard ~2 259 warnings/session documentée |
| W10 | Code généré et sources mixtes | **Partiel** | `hasRealRClasses` réel (scan binaire, downgrade ERROR→HINT honnête) ; « R.java stub régénéré » = commentaire seulement (plus de génération de stubs) ; AUCUN traitement BuildConfig/ViewBinding/Hilt/Room/KSP/kapt ; AUCUN pont Java→Kotlin non compilé (résolution `.java` et jars uniquement) |

Bugs historiques à ne pas réintroduire (§4.3 du prompt) : tous
retrouvés dans le code avec leurs correctifs — FQCN « racine la plus
profonde d'abord » (`fqcnFor`), shims ART (`ecj-art`), types imbriqués
par le jar englobant (`locateBinary` re-sonde le préfixe avant `$`),
CloseGuard (RawZipReader), `workspaceFolders` lu mais ignoré
(commentaire du code).

---

## 4. Mesures de référence (banc JVM)

### 4.1 Conditions

JVM de bureau OpenJDK 21.0.12 (Temurin), 2 cœurs, tas max 2 Go,
`-XX:+UseSerialGC` (le plus proche d'un appareil bas de gamme), Linux
cgroup ~4 Go. Trois jeux de données RÉELS (§12) :
**petit** 20 jars (44,3 Mo dont android.jar 41,8) · **moyen** 73 jars
(54,5 Mo — classpath debug réel de `:app` CodeIDE, AAR normalisés) ·
**lourd** 250 jars (149,7 Mo — projet Compose synthétique résolu par
Gradle, 191 AAR normalisés). Scénario `MainActivity` (349 caractères,
le repro historique) + `Panier` (~180 lignes). Itérations 30, p50/p95.
Limites assumées : cache OS chaud au premier passage (le « froid »
mesuré est JVM-froide/JIT-froid, pas disque-froid) ; pas de couche
LSP4J ni UI (coût moteur seul) ; pas d'appareil (procédure §11.2).

### 4.2 Résultats

| Métrique | petit (20) | moyen (73) | lourd (250) |
|---|---:|---:|---:|
| Index mémoire, JVM froide (ms) | 214 | 322 | 1 086 |
| Reconstruction même JVM (ms) — coût d'un replaceAll | 98 | 219 | 794 |
| Premier diagnose (ensureIndex + ecj) (ms) | 835 | 913 | 1 627 |
| Diagnose chaud p50 / p95 (ms) | 14 / 38 | 12 / 43 | 23 / 46 |
| Complétion MEMBRE `b.` p50 / p95 (ms) | 0 / 6 | 0 / 5 | 0 / 5 |
| Complétion NOM (types) p50 / p95 (ms) | 18 / 54 | 54 / 78 | **109 / 140** |
| Complétion IMPORT p50 / p95 (ms) | 1 / 5 | 1 / 3 | 4 / 26 |
| Frappe simulée (complétion+diagnose par touche) p50 / p95 (ms) | 30 / 64 | 69 / 160 | 128 / 150 |
| **+1 dépendance** : moteur neuf + 1er diagnose (ms) | 257 | 396 | **1 178** |
| Tas après index / état stable (Mo) | 9 / 25 | 19 / 36 | 108 / 131 |
| Poignées de fichiers après index / stable | 28 / 30 | 28 / 33 | 28 / 42 |
| Noms simples indexés / paquets | 4 945 / 433 | 7 084 / 623 | 23 327 / 1 878 |
| Après `dispose()` : tas / poignées | 12 / 28 | 13 / 28 | 13 / 28 |

### 4.3 Lecture des chiffres contre les cibles du prompt (§9)

- **Ouverture, index « chaud » < 1,5 s** : sur JVM de bureau, le statu
  quo ne tient le délai que pour petit/moyen (835/913 ms) et **échoue
  déjà à 250 jars (1 627 ms)** — sans couche LSP4J ni UI. Sur ART
  (3–5× plus lent sur ces chemins, et GC plus coûteux), tous les cas
  échouent. La **persistance disque (ADR 0111) est indispensable**,
  pas une optimisation.
- **Complétion de types p95 < 100 ms quelle que soit la taille** :
  violée dès 250 jars sur bureau (140 ms) ; la croissance est
  clairement super-linéaire en pratique (18→54→109 en p50 pour ×12,5
  jars) — conforme à W3. Les requêtes préfixe/approximation sur index
  trié sont la seule réponse structurante.
- **Frappe : aucun travail LSP sur le thread principal** : côté moteur,
  la complétion+diagnose par touche (30/64 → 128/150 ms) multipliée par
  ART confirme que 1–5 résolutions complètes par frappe ne tiendront
  pas : cache de parse multi-entrées (W8) et réduction des variantes
  restent nécessaires même après l'index disque.
- **Tas imputable à l'index < 30 Mo pour 100 jars** : le statu quo
  consomme 19 Mo pour 73 jars mais 108–131 Mo pour 250 — le budget
  snapshot (2,5 Mo/jar, W2) est le principal responsable. L'index disque
  + cache de blocs borné est la seule voie pour tenir la borne.
- **Ajout d'une dépendance : seul le nouveau jar indexé** : aujourd'hui
  +1 jar = 257/396/1 178 ms de reconstruction TOTALE (W1 mesuré). Le
  segment par jar (ADR 0111) répond exactement à cette cible.
- **Poignées de fichiers plafonnées** : le pool LRU de 24 fait déjà un
  plafond constant (28–42 descripteurs au total) — comportement à
  conserver, à re-vérifier avec l'index disque (fichiers mappés).
- **Fermeture : toutes ressources libérées** : `dispose()` ramène le tas
  à ~13 Mo et les poignées à 28 — le module vendored est propre sur ce
  point ; le futur `lsp:host` devra le prouver par test de fuite (§10).

Notes de lecture : le p95 « frappe » du moyen (160 ms) > lourd (150 ms)
illustre la variance GC/JIT sur 2 cœurs — les chiffres sont des ordres
de grandeur reproductibles, pas des constantes. La complétion NOM rend
**0 item** dans le scénario du banc (reproduit le symptôme « got 0
items » de l'ancienne version) : comportement préexistant du module sur
ce contexte, indépendant des performances — à corriger à l'étape 3.

---

## 5. Audit de provenance reconstitué (D3 — ADR 0110)

### 5.1 Méthode

projet de référence (auteur tiers, GPL-3.0) cloné en lecture seule (depth 1) ;
comparaison automatique des 48 fichiers main du module vendored contre
l'index des fichiers projet de référence (appariement par nom, manuel pour les
ratés) ; métriques : **lignes identiques normalisées** (trim, non
vides, commentaires inclus — les en-têtes de licence comptent) et
**Jaccard des jetons** du corps de code (commentaires exclus).
Rejouable : `audit_provenance.py` (§11.1).

### 5.2 Résultats

- **Niveau A confirmé** : `RuntimeVersion` 69 % lignes / 0,93 Jaccard ;
  `StackWalker` 40 % / 0,89 ; `InputStreamCompat` 60 % / 0,57
  (sous-ensemble). (L'audit absent annonçait « 61/61 » — écart de
  méthode, même conclusion.)
- **Niveau B** : 31 fichiers mentionnent projet de référence ; ressemblance
  textuelle faible (0,08–0,26 lignes, Jaccard 0,01–0,46) MAIS
  dérivation démontrée : commentaires citant les classes d'origine
  (`JdtRename`, `JdtEnvironmentCache`, `JavaProblemCodes`,
  `JavaActions`, `CompletionRanker`…) et **constantes numériques
  identiques** (score : 1000/500/200/120/300/200/400 entre
  `CompletionRanker.kt` et `JdtCompletionEngine`).
- **ecj-art** : passes d'origine retrouvées dans le build-logic de
  projet de référence (`RelocateTypesInJar.kt`, `EclipseStreamArtPass.kt`) ;
  recoupement textuel faible, dicté par l'API ASM.
- **Six noms de fichiers identiques** : `JdtCodeFolder`,
  `JdtSemanticHighlighter`, `JdtSourceUnit`, `RuntimeVersion`,
  `StackWalker`, `InputStreamCompat`.
- **Contre-exemple utile** : `JdtLocateCache` (LRU exact) n'est PAS la
  `LruMap` du projet de référence (CLOCK) — même idée, implémentations
  distinctes : la frontière « idée non protégeable / expression
  protégée » passe bien là.

### 5.3 Plan de traitement par fichier (sous l'option 1 — réécriture cloisonnée ; à acter par le propriétaire)

Ordre de traitement (les renommages §7 suivent chaque lot, compilation
et tests verts à chaque lot) :

**Lot 1 — réécrire depuis spécification (niveau A, priorité absolue,
petit volume)** : `compat/RuntimeVersion`, `compat/StackWalker`,
`compat/InputStreamCompat` — spéc = javadoc public du JDK ; nouveaux
noms §7 ; constantes redéterminées par mesure (pas recopiées).

**Lot 2 — réécrire (niveau B, recoupement ou dérivation significative)**
: `JdtCompletionEngine` (partie scoring/ranker : constantes à
redéterminer par banc de tri de complétion), `JavaProblemFamily`,
`CodeActionService`, `JdtCodeFolder`, `JdtSourceUnit`,
`JdtPostfixTemplates`, `JdtLiveTemplates`, `JdtFormatter`,
`JdtInlayHints`, `JdtImportOrganizer`, `JdtSemanticHighlighter`,
`JdtRenameEngine`, `BindingParseCache`, `CompletionPrefixMatcher`,
`JdtBinarySourceNames`, `Diagnostic`, `CancelToken`,
`OperationCanceledException`, et la partie « pool de poignées +
snapshots » de `JdtCompilerEnvironment` (dérivée de
`JdtEnvironmentCache`) — pour chaque fichier : écrire la spécification
de comportement SANS lire le code tiers (les tests de non-régression
du module servent de filet), puis implémenter.

**Lot 3 — conserver avec justification** (implémentation propre,
dérivation faible ou idée générique) : `RawZipReader` (motivation née
des logs device de CodeIDE, implémentation indépendante),
`SourceFocuser` (technique de java-language-server, tierce aux deux
projets), `JdtFastDiagnose` (mécanisme propre : corps à longueur
constante, repli conservateur), `JdtEngineScheduler` (design propre,
files + supersession + watchdog), `JdtLocateCache` (voir §5.2),
`ClassFileStubBuilder`, `JdtAst`, `JdtNavigationEngine`,
`JdtSignatureHelp`, `JdtCompilerEngine` (pipeline ecj propre ;
`fqcnFor` = correctif device CodeIDE), `CompilationEnvironment`
(classpath plat — de toute façon remplacé par `lsp:model`),
façades LSP4J (`JavaLanguageServer`, `JavaTextDocumentService`,
`JavaWorkspaceService`, `ServerCapabilitiesProvider`, services
`Diagnostic/Hover/Navigation`), `api/*` (types de données génériques).

**Lot 4 — ecj-art** : réécrire les passes selon la documentation ASM
(licence BSD) si l'option 1 est retenue ; conserver tel quel si
autorisation (option 2).

**Suppressions prévisibles** (remplacées par les nouvelles couches,
pas de la réécriture D3) : `CompilationEnvironment` (→ `lsp:model`),
la persistance `WorkspaceModelStore` côté référence (→ persistance
versionnée de `lsp:model`), `WorkspaceModel` (→ snapshots typés).

---

## 6. Décisions D1–D5

| Décision | ADR | Statut |
|---|---|---|
| D1 — politique de langage : module vendored Java exempté, nouvelles couches Kotlin, conversion conditionnelle | 0108 | accepté |
| D2 — modèle de processus : in-process ART par défaut, transport abstrait, bascule possible sur mesure appareil | 0109 | accepté (à confirmer par mesure) |
| D3 — provenance et licence : audit reconstitué, 4 options soumises au propriétaire, aucune publication avant règlement | 0110 | **PROPOSÉ — décision propriétaire** |
| D4 — index disque : segments PAR JAR, format binaire versionné, fusion à la requête, corruption non fatale | 0111 | accepté |
| D5 — plateforme : android.jar seul pour les modules Android, conformité par module, src.zip pour java.* | 0112 | accepté |

---

## 7. Plan de renommage (table ancien → nouveau)

Exécution **après** le traitement D3 lot par lot (prompt §3), par
refactoring, compilation et tests verts à chaque lot. Le préfixe
retenu est **`Ecj`** (honnête : ces classes adaptent ecj/JDT) — il
évite par construction les noms identiques à projet de référence
(`CompletionEngine`, `PrefixMatcher`, `ImportOrganizer`,
`SignatureHelp`, `LiveTemplates`, `PostfixTemplates`,
`JdtEnvironmentCache`…). Les shims deviennent `ArtCompat*`.

| Ancien | Nouveau | Motif |
|---|---|---|
| package `jo.lspjava.jdt` | `jo.lspjava.ecj` | retrait de la référence JDT du nom de package |
| `JdtCompletionEngine` | `EcjCompletionEngine` | ≠ `CompletionEngine` (projet de référence ide-core) |
| `JdtCompilerEngine` | `EcjCompilerEngine` | préfixe |
| `JdtCompilerEnvironment` | `EcjCompilerEnvironment` | préfixe |
| `JdtEngineScheduler` | `EcjEngineScheduler` | préfixe |
| `JdtRenameEngine` | `EcjRenameEngine` | ≠ `JdtRename` (projet de référence) |
| `JdtNavigationEngine` | `EcjNavigationEngine` | préfixe |
| `JdtFastDiagnose` | `EcjFastDiagnose` | préfixe |
| `JdtSignatureHelp` | `EcjSignatureHelp` | ≠ `SignatureHelp` (projet de référence api) |
| `JdtInlayHints` | `EcjInlayHints` | ≠ `JdtInlayHintService` |
| `JdtSemanticHighlighter` | `EcjSemanticHighlighter` | **nom de fichier identique à projet de référence** |
| `JdtCodeFolder` | `EcjCodeFolder` | **nom de fichier identique à projet de référence** |
| `JdtSourceUnit` | `EcjSourceUnit` | **nom de fichier identique à projet de référence** |
| `JdtPostfixTemplates` | `EcjPostfixTemplates` | ≠ `PostfixTemplates` (projet de référence) |
| `JdtLiveTemplates` | `EcjLiveTemplates` | ≠ `LiveTemplates` (projet de référence) |
| `JdtFormatter` | `EcjFormatter` | ≠ `JdtFormattingService` |
| `JdtImportOrganizer` | `EcjImportOrganizer` | ≠ `ImportOrganizer` (projet de référence api) |
| `JdtBinarySourceNames` | `EcjBinarySourceNames` | préfixe |
| `JdtLocateCache` | `EcjLocateCache` | préfixe |
| `JdtAst` | `EcjAst` | préfixe |
| `compat/RuntimeVersion` | `ArtCompatVersion` | **réécrit de toute façon (niveau A)** |
| `compat/StackWalker` | `ArtCompatStackWalker` | **réécrit de toute façon (niveau A)** |
| `compat/InputStreamCompat` | `ArtCompatInputStream` | **réécrit de toute façon (niveau A)** |
| `CompletionPrefixMatcher` | `EcjPrefixMatcher` | cohérence, ≠ `PrefixMatcher` (projet de référence) |
| `BindingParseCache` | `EcjParseCache` | réécrit lot 2, précise « parse » |

Sans changement (aucun préfixe Jdt, aucun nom identique à projet de référence) :
`RawZipReader`, `SourceFocuser`, `ClassFileStubBuilder`, `CancelToken`,
`OperationCanceledException`, `CompilationEnvironment` (supprimé à
terme), `JavaLanguageServer`, `JavaTextDocumentService`,
`JavaWorkspaceService`, `ServerCapabilitiesProvider`,
`CodeActionService`, `DiagnosticService`, `HoverService`,
`JavaProblemFamily`, `NavigationService`, `api/*`,
`EcjArtPatcher` (ecj-art). Les noms définitifs seront arrêtés au
moment du lot concerné ; cette table est le plan de travail.

---

## 8. Étude d'extractibilité en bibliothèque indépendante

**Coût** : faible aujourd'hui — le module est déjà une entité autonome
(sans dépendance Android ni CodeIDE, API publique `jo.lspjava.api.*`,
JUL sans slf4j, publication Maven envisagée dans son propre
`build.gradle`). Ce qui manque : CI de publication, API gelée,
séparation documentée cœur/adaptation (`ecj-art` reste un artefact de
build), et surtout **le règlement D3** — la licence interdit toute
publication tant que les niveaux A/B ne sont pas traités ou autorisés.

**Bénéfice attendu** : aucun consommateur identifié en dehors de
CodeIDE (les futurs LSP Kotlin et XML ont leurs propres moteurs et ne
consommeraient que le *modèle de projet* et *l'index*, qui appartiennent
aux couches Kotlin, pas à ce moteur). La crédibilité « bibliothèque
publique » ne pèse pas face au risque licence.

**Décision** : **extraire plus tard, pas maintenant.** Les garde-fous
design extractible restent : zéro dépendance CodeIDE/Android dans le
cœur, API publique minimale stable, `lsp:index` et `lsp:model` conçus
JVM-purs (réutilisables par les LSP Kotlin/XML futurs — c'est la vraie
mutualisation). Re-évaluation après le règlement D3 et l'étape 3.

---

## 9. Cibles non fonctionnelles — recalage

Cibles §9 du prompt, position statu quo mesurée (bureau ; ART ≈ 3–5×) :

| Cible | Statu quo (bureau) | Statut |
|---|---|---|
| Ouverture index chaud < 1,5 s | 835–1 627 ms (sans LSP4J/UI) | **inatteignable sans persistance** |
| Complétion membres p95 < 150 ms | 5–6 ms | déjà tenue (moteur) |
| Complétion types/imports p95 < 100 ms | 78–140 ms | **violée dès 250 jars** — requiert index par préfixe |
| Frappe : 0 travail LSP sur main ; 0 image perdue | n/a (pas d'UI au banc) | à valider étape 4 |
| +1 dépendance → seul le nouveau jar indexé | 257–1 178 ms (tout repayé) | **violée** — segment par jar (ADR 0111) |
| Tas index < 30 Mo / 100 jars | 19 Mo / 73 jars ; 108–131 Mo / 250 | **structurellement hors borne** — budget snapshot |
| Poignées constantes | 28–42 (pool LRU 24) | tenue — à conserver |
| Corruption segment → jamais de crash | n/a (pas d'index disque) | contrat ADR 0111 |
| Fermeture : 0 ressource orpheline | dispose() propre (13 Mo / 28 FD) | tenue — à prouver par test étape 4 |

Ajustement proposé (à confirmer sur appareil à l'étape 3) : la cible
d'ouverture « index chaud < 1,5 s » devient la cible de l'**étape 2**
(index disque) mesurée sur les mêmes jeux ; la complétion de types p95
< 100 ms devient la cible de l'**étape 3** (environnements de noms
adossés à l'index).

---

## 10. Plan ajusté des étapes suivantes

- **Étape 1 — modèle de projet LSP** (`lsp:model`, Kotlin) : comme
  prévu au prompt, PLUS le nouveau livrable critique révélé par §2.1 :
  **étendre la source du classpath pour les modules Android**
  (options A/C : modèle AGP `builder-model` via Tooling API, IdeaProject
  conservé pour les JVM purs), remplir `androidJar`/`fichiersGeneres`/
  `ignore` côté serveur, normaliser les AAR à la résolution (§2.2).
  Snapshots immuables, empreintes par contenu (W6), association
  fichier→module (package + sourceset), persistance versionnée VALIDÉE
  (taille/mtime des jars — corrige W7). Critère prompt inchangé.
- **Étape 2 — index disque** (`lsp:index`) : selon ADR 0111 ; états,
  progression, repli par sondage ; banc avant/après sur les trois jeux
  (cibles : +1 dépendance = indexation du seul jar ; reprise < 1,5 s).
- **Étape 3 — intégration du moteur** (`lsp:java` vendored selon D1) :
  traitement D3 par lots (§5.3) + renommage (§7) au fil des lots ;
  environnement de noms adossé à l'index (complétion par préfixe —
  tue W3) ; cache de parse multi-entrées ; moteur par module (verrou
  global → périmètre module) ; suite de non-régression portée ;
  correctif du « 0 item » du scénario NOM.
- **Étape 4 — hôte, cycle de vie, éditeur** (`lsp:host`) : `SessionLsp`
  (transport abstrait D2), branchement `cel-lsp` (vérifié §2.6 —
  aligner lsp4j 0.21.2/0.22.0), état d'indexation dans l'UI + action
  « Réindexer », test de fuite à la fermeture (prompt 3).
- **Étape 5 — performance et validation appareil** : protocole §11.2
  exécuté, optimisations mesurées, `docs/LSP_JAVA.md` complété,
  `CHANGELOG.md`, `AGENTS.md`.

---

## 11. Banc de mesure reproductible

### 11.1 Procédure JVM (rejouable)

1. Projet de banc : `/home/z/my-project/work/bench-lsp-java` —
   `settings.gradle` incluant `:lspjava`, `:ecj-art` (copiés INTACTS du
   zip fourni, seule adaptation : `javaexec`→`ProcessBuilder` dans le
   `build.gradle` du module pour Gradle 9.7.1, sources non touchées) et
   `:bench` (`BancMesure.java` — index/diagnose/complétions/frappe/
   +1 dépendance, tas et poignées ; `BancTooling.java` — dump IdeaProject).
2. Jeux de données (§12) : `datasets/{petit,moyen,lourd}/manifeste.txt`.
3. Exécution : `scripts/executer_banc.sh` (JVM séparée par jeu,
   `-Xmx2g -XX:+UseSerialGC`, itérations 30) →
   `resultats/banc-<jeu>.json` + `banc-complet.log`.
4. Audit de provenance rejouable : `scripts/audit_provenance.py` (clone
   projet de référence depth 1 requis).
5. Génération des jeux : `scripts/construire_jeux.py` (rapport
   `dependencies` + localisation cache Gradle) et
   `scripts/construire_jeux_fichiers.py` (liste de fichiers résolus) ;
   projet Compose : `/home/z/my-project/work/projet-lourd-compose`.

Les chemins ci-dessus sont ceux de la session d'étude (hors dépôt) ;
les scripts sont conservés dans `scripts/` du projet de travail. À
l'étape 2, le banc sera porté dans le dépôt (module de test dédié ou
tâche documentée) avec les jeux reconstituables par une recette
versionnée.

### 11.2 Procédure appareil (protocole écrit — exécution aux étapes 3+)

Appareils : milieu de gamme (8 Go, Android 13+) et entrée de gamme.
Préparation : installer l'APK debug, cloner le projet de test, ouvrir,
laisser la sync + le classpath se préparer. Mesures (via l'écran
Diagnostic/état d'indexation prévu étape 4, journal `AppLogger`
expurgé et chronométrage vidéo 60 i/s pour les frappes) : ouverture à
froid (première indexation), à chaud (réouverture), ajout d'une
dépendance (une seule bibliothèque ajoutée au build), sync Gradle
complète, création d'un type puis complétion le référençant, fermeture/
réouverture, bascule d'écrans. Consigner : latences p50/p95 par
contexte, tas (Runtime.totalMemory−free + `dumpsys meminfo` du
processus), poignées (`/proc/<pid>/fd | wc -l` via terminal), images
perçues. Comparer aux cibles §9 à chaque étape.

---

## 12. Jeux de données

| Jeu | Entrées | Taille | Origine | Particularités |
|---|---|---|---|---|
| petit | 20 | 44,3 Mo | android.jar + 19 jars de la chaîne androidx de base du moyen | reproduit le repro historique (~20 jars des commentaires W1) |
| moyen | 73 | 54,5 Mo | **classpath `debugCompileClasspath` réel de `:app` CodeIDE** (rapport Gradle + cache modules-2, 49 jars extraits d'AAR) | projet moyen réel, conforme aux « 91+2 » de l'ancienne session à 20 jars près (jeux non publiés de la session device) |
| lourd | 250 | 149,7 Mo | projet Compose synthétique résolu par Gradle (BOM 2025.09.00, material3, navigation, room, hilt, coil, retrofit, camera, media3, work, datastore, mlkit, firebase, commons, jackson…) | **191 AAR normalisés (76 %)** — représentatif d'une vraie app lourde ; 23 327 noms simples indexés |

Le jar « +1 dépendance » du banc (`datasets/extra/extra-banc.jar`,
604 Ko) est volontairement hors manifestes.


---

## 13. Étape 1 — modèle de projet LSP (v0.92.0, livrée)

### 13.1 Ce qui a été livré

- **`lsp:model` (nouveau module Kotlin JVM pur, `jo.codeide.lsp.model`)**
  — la conversion pure exigée par le §7.1 du prompt :
  `ConvertisseurModeleLsp.depuisClasspathResult` (lecteur de statistiques
  de fichiers INJECTÉ — aucun accès disque dans le convertisseur) ;
  `ProjetLsp`/`ModuleLsp`/`EnsemblesSources` (rôles principal/test/générés
  — W5)/`ClasspathInstantane` + `EntreeClasspathInstantane` (empreintes
  par contenu, W6)/`DependanceModule` (cycles REJETÉS à l'échelle du
  projet avec avertissement — arêtes en ordre canonique, le graphe reste
  acyclique et déterministe)/`AssociationFichier` (association
  fichier→module par la racine de sourceset la plus PROFONDE ;
  `fqcnAttendu` + `ecartFqcn` recoupent la déclaration `package` avec le
  sourceset — l'écart est un diagnostic explicite, le correctif du bug
  historique « 31 diagnostics sur 349 caractères »)/`PersistanceModeleLsp`
  (format versionné 1, VALIDATION taille/horodatage à la relecture —
  corrige W7 : un modèle périmé reste utilisable immédiatement mais
  MARQUÉ non vérifié, écarts listés)/`DepotModeleLsp` (publication par
  instantanés successifs, câblage applicatif à l'étape 4).
- **Source du classpath Android (ADR 0113)** : `tooling:server` résout
  les modèles **v2 d'AGP** (builder-model 9.4.1) dans l'action unique —
  `BasicAndroidProject` (bootClasspath, sourcesets main/unitTest java+kotlin),
  `AndroidProject` v2 (générés, classesFolders dont R.jar, par variante),
  `VariantDependencies` paramétré par `ModelBuilderParameter` (graphe
  compile+test + bibliothèques ; paramètre TOUJOURS complet — un getter
  booléen non initialisé du proxy fait échouer AGP en NPE, constat de
  la sonde). IdeaProject reste la source des modules JVM purs — mais
  résolu par le MODEL BUILDER (dans une action, l'adaptateur perd les
  types concrets des dépendances : mesuré, `DefaultIdeaModuleDependency`
  derrière un proxy `IdeaDependency` nu). Fusion par chemin : le module
  Android remplace son homologue Idea (vide pour lui).
- **Normalisation des AAR : déjà faite par AGP** (mesuré, jeu lourd :
  191/191 AAR avec jars extraits) — le serveur livre le jar extrait du
  cache de transforms + l'AAR d'origine en provenance
  (`ClasspathEntry.aar`, protocole v8). RELOCATED/NO_ARTIFACT_FILE
  écartés (la cible réelle figure ailleurs dans le graphe / BOM sans
  fichier).
- **Protocole v8** : `sourceDirsTest`, `ClasspathEntry.aar`, `name` =
  chemin Gradle (`:app`) — les entrées MODULE désignent leur cible par
  le même identifiant ; remplissage de `androidJar`,
  `fichiersGeneres`, `varianteAndroid` (variante RÉELLE du modèle v2 —
  l'heuristique de nom v0.40.1 supprimée), `ignore`+`raisonIgnore`
  (module conteneur), `avertissements` (dépendances non résolues).
  Goldens régénérés volontairement (procédure `REGENERER_DORES=1`, diff
  relu avant commit).
- **Modules vendored intégrés au build** (carte blanche du
  propriétaire) : `:lsp:java` (moteur, sources Java INTACTES de
  l'archive, ADR 0108) et `:lsp:ecj-art` — build.gradle traduits en
  Kotlin DSL, versions par `gradle/libs.versions.toml` (jdt.core 3.40.0,
  lsp4j 0.21.2, gson 2.10.1, asm 9.7, JUnit 5.10.1 — toutes vérifiées
  sur les dépôts officiels), `patchEclipseForArt` en classe de tâche
  **compatible configuration cache** + ProcessBuilder (Gradle 9),
  `JO_TEST_JDK_HOME` fourni par le build, README d'exemption (règle 8)
  et crédits projet de référence. Suite vendored : **246 tests verts** (les
  6 tests JDK échouaient sans `JO_TEST_JDK_HOME` — les sondes du module
  attrapaient le JRE système sans `src.zip`).
- **`ModelesAndroidV2`** : conversion par bibliothèque (ANDROID_LIBRARY →
  jars extraits + provenance ; JAVA_LIBRARY → jar + sources attachées
  (`srcJars`, le singulier est déprécié) ; PROJECT → MODULE par chemin ;
  RELOCATED/NO_ARTIFACT_FILE → rien — le classpath résolu est complet) ;
  variante retenue : `debug` sinon première contenant `debug` sinon
  première ; modules conteneurs marqués `ignore` avec raison.
- **Fixture AGP réelle** (`android-simple`, 2 modules AGP 9.4.1) +
  test d'intégration : modules `:app`/`:lib` par chemin, sourcesets
  main/test séparés, `android.jar`, variante `debug`, entrée MODULE
  `:lib`, jar commons-io, junit portée `test`, conteneur ignoré avec
  raison. `local.properties` écrit par le test (propre à la machine),
  saut propre si le SDK est absent.

### 13.2 Critère du prompt — vérifié par les tests

> « le même classpath produit toujours la même empreinte ; un
> changement d'une dépendance change l'empreinte du seul module
> concerné. »

- `le meme classpath produit toujours la meme empreinte` : ordre des
  entrées PERMUTÉ ⇒ même empreinte (ordre canonique : tri kind puis
  chemin) ;
- `changer une dependance change l'empreinte du SEUL module concerne` :
  le jar de `:b` change ⇒ l'empreinte de `:b` et du projet changent,
  celle de `:a` (qui dépend de `:b` par l'entrée MODULE **nominale**)
  est inchangée ;
- cas du prompt §8 tous couverts : multi-modules, api/impl (isolation
  par module : le classpath de `:app` ne porte PAS l'implementation
  d'un sibling — Gradle l'a déjà filtré, le modèle le préserve),
  conflits de versions (« plus récent gagne » + avertissement,
  comparaison NUMÉRIQUE par segments — 1.10 > 1.9), dépendance cyclique
  rejetée (une arête survit, avertissement sur le module source),
  auto-dépendance rejetée, module ignoré avec raison, AAR normalisé
  (kind JAR + provenance) et AAR brut (signalé), sourcesets de test
  séparés (portée `test`).

### 13.3 Écarts et décisions documentés

- **Drapeau `exporte` (api vs implementation) : non déterminable** — ni
  le graphe v2 ni IdeaProject n'exposent de drapeau par arête ;
  toujours `false` à l'étape 1, documenté dans `DependanceModule`.
  L'isolation par module fait déjà le travail de W5 (l'implementation
  d'un sibling ne figure pas dans le classpath du consommateur).
- **`unitTestSourceProvider`/`unitTestArtifact` dépréciés mais lus** :
  sonde empirique (AGP 9.4.1, projet Compose) — les cartes
  `hostTestSourceProviders`/`hostTestArtifacts` sont VIDES, le test
  unitaire classique vit encore dans les accès dépréciés (peuplés).
  Suppressions commentées, à revisiter si AGP les retire.
- **androidTest non livré** (main + unitTest seulement) : périmètre
  éditeur — ajoutable sans changement de format (une portée de plus).
- **Durée réelle** des commandes de vérification : lsp:model test 20 s
  (35 tests) ; lsp:java test 33 s à froid (patch ART 8 jars + 246
  tests) puis 17 s ; lsp:ecj-art 30 s à froid ; tooling:server test
  1 min 02 (103 tests, dont AGP 8,2 s sur démon chaud) ; tooling:client
  + testing + api 26 s ; spotlessApply/detekt ~25 s incrémental ;
  goldens : régénération 12 s.

### 13.4 Suite — étape 2 (index disque, ADR 0111)

Le modèle étant empreinté et persisté, l'étape 2 (segments par jar,
clé = `maven:<sha1>` / `transform:<haché>` / `partiel:…`) a ses clés
prêtes ; l'étape 3 branchera l'environnement de noms du moteur vendored
sur l'index (le `ClasspathInstantane` remplace le classpath plat de
`CompilationEnvironment`), l'étape 4 le câblage `SessionLsp`/cel-lsp et
l'état d'indexation UI, l'étape 5 la validation appareil (§11.2).

---

## 14. Étape 2 — index disque (v0.93.0, livrée)

> **Mission** : prompt 2 §7.2, ADR 0111. Livrable : le module `:lsp:index`
> (Kotlin JVM pur, ADR 0108) — un segment PAR JAR sur le disque, la fusion
> LSM à la requête, le format binaire versionné, la construction en
> arrière-plan avec états/progression, le nettoyage, et le banc
> avant/après sur les trois jeux avec recette versionnée.

### 14.1 Ce qui a été livré

- **Format CIDX** (binaire, gros-boutien, versionné — `EcrivainSegment`/
  `LecteurSegment`) : en-tête `magie + version + drapeaux + crc32 + clé +
  conteneur + stats source (W7) + compteurs + table des sections` ; sections
  ENTRÉES/PAQUETS/NOMS SIMPLES (préfixe partagé u8+u16), MEMBRES (offsets
  par classe, sentinelle `0` = absent), CHAÎNES (table dédupliquée),
  ORIGINES (AAR : jar interne porteur). Écriture ATOMIQUE (`.tmp` unique +
  renommage + fsync). Corruption d'un segment ⇒ refus propre de CE segment
  (crc/magie/version/clé/stats), reconstruction paresseuse — migration de
  version = ré-indexation paresseuse, jamais de crash (tests aller-retour,
  troncature, crc, version, clé, stats).
- **Clés de segments** (`ClesSegments`) : nom de fichier opaque
  `sha256(empreinte).idx` dans le stockage partagé
  `~/.codeide/lsp/index/segments/` (injectable — l'hôte Android fournira
  son répertoire à l'étape 4) ; AAR ⇒ `aar:<sha256(empreinte|jars
  internes)>` ; dossier ⇒ signature d'arborescence (chemin relatif,
  taille, horodatage — ADR 0111 §10).
- **Lecteur ZIP brut** (`LecteurZipBrut`) : équivalent Kotlin du
  `RawZipReader` vendored — `RandomAccessFile` + `Inflater`, AUCUN
  `java.util.zip.ZipFile` (CloseGuard, ADR 0111 §6) ; sources fichier ET
  octets en mémoire (jars internes d'AAR). ZIP petit-boutien (APPNOTE
  §4.1.1 — constat : les signatures/entiers ZIP se lisent en
  petit-boutien, bug corrigé avant les tests).
- **Analyseur de fichiers de classe** (`AnalyseurClassFile`) : pool de
  constantes minimal (Utf8 seuls conservés, Long/Double deux slots),
  drapeaux de classe, champs et méthodes — extensions `java.members` /
  `java.membersByOwner`.
- **Analyseur de classpath** (`AnalyseurClasspathSysteme`) : jar (clé =
  empreinte W6), AAR NORMALISÉ à l'indexation (classes.jar puis libs,
  premier porteur gagnant, origines par entrée), dossier (signature) ;
  prédicat de REPRISE injecté par le service (seul un jar manquant est
  analysé — cible « +1 dépendance ») ; jar illisible ⇒ écart signalé,
  jamais de crash.
- **Vue d'index** (`VueIndex`) : FUSION LSM À LA REQUÊTE — union ORDONNÉE
  des segments du classpath, premier porteur gagnant ; extensions servies :
  `java.classLocator` (binaire, négatif autoritatif à l'état PRÊT),
  `java.classNames` (borne inférieure + marche bornée — tue le balayage
  W3), `java.packages`, `java.packageTypes` (« ni plus ni moins »),
  `java.members`, `java.membersByOwner` (imports statiques). Lectures
  passent par le **cache LRU borné** (30 Mo par défaut, octets comptés,
  refus mémorisés, invalidation après reconstruction).
- **Service** (`ServiceIndexDisque`) : construction en arrière-plan
  (parallélisme `min(4, cœurs/2)`, annulable — l'état reste CONSTRUCTION,
  jamais de faux PRÊT), états `{construction, prêt, partiel, erreur}` en
  `StateFlow` avec progression par jar et comptes de reprises ;
  auto-guérison (crc détecté à la lecture ⇒ reconstruction de CE segment
  seul, anti-rebond) ; segments mutualisés entre modules (écritures
  atomiques concurrentes au pire en double, contenu identique) ;
  entrées MODULE non indexées (l'identité du frère vit dans son snapshot,
  préséance sources > sortie réglée par le consommateur).
- **Nettoyage** (`NettoyageSegments`) : orphelins par âge (jours de grâce,
  âge = dernier ACCÈS), budget disque global (LRU des orphelins —
  longueur lue AVANT suppression, bug corrigé), référencés jamais
  amputés, dépassement restant SIGNALÉ.
- **Banc + recette versionnée** : tâche `:lsp:index:bench` (JavaExec,
  JVM `-Xmx2g -XX:+UseSerialGC` comme §11.1) ;
  `scripts/lsp-index/construire-jeux.sh` (moyen = classpath
  `debugCompileClasspath` réel de `:app` via rapport + cache modules-2 ;
  petit = android.jar + jars androidx du moyen ; lourd = 250 premiers
  artefacts jar+AAR du cache Gradle, tri déterministe — 122 AAR : la
  normalisation AAR est exercée) ; `extra/extra-banc.jar` pour le +1.

### 14.2 Banc avant/après (sandbox 4 Go, JVM 2 Go/SerialGC)

| Mesure | petit (14) | moyen (83) | lourd (250) | AVANT (étude §4) |
|---|---|---|---|---|
| Index froid | 573 ms | 491 ms | 9 180 ms | 214/322/1 086 ms |
| **Reprise à chaud** | **7 ms** | **10 ms** | **25 ms** | reconstruction 98/219/794 ms |
| **+1 dépendance** | **85 ms** | **89 ms** | **87 ms** | 257/396/1 178 ms tout repayé |
| Localiser négatif p50/p95 | 0,66/5,6 ms | 3,0/12 ms | 124/134 ms | n/a (balayages) |
| Préfixe p50/p95 | 0,16/1,1 ms | 1,7/4,1 ms | 0,76/7,1 ms | complétion types 18/54/109 ms |
| Cache LRU | 4,2 Mo | 2,9 Mo | 29,7 Mo | 19/—/108-131 Mo non bornés |
| État final | PRÊT | PRÊT | PARTIEL (AAR sans jar interne — signalé) | n/a |

**Cibles ADR 0111 tenues** : +1 dépendance = indexation du seul jar
nouveau (les ~85 ms couvrent la validation d'en-tête des autres segments
— 0 re-analyse) ; reprise < 1,5 s (25 ms à 250 entrées, 60× sous la
cible) ; cache < 30 Mo (borné PAR CONSTRUCTION, 250 entrées tenues dans
le budget). La complétion par préfixe p50 passe à 0,16-1,7 ms (recherche
binaire par segment — W3 tué côté index). Le localisateur NÉGATIF p50 du
jeu lourd (124 ms) reflète l'éviction LRU au budget (rechargements) :
c'est le compromis du budget tas — les négatifs autoritatifs à l'état
PRÊT éviteront ces sondages répétés côté moteur (étape 3), et l'étape 5
mesurera sur appareil.

### 14.3 Écarts et décisions documentés

- **Jeux recalés par la recette** : moyen = 83 entrées (classpath COURANT
  de `:app` — 73 à l'étude : le dépôt a gagné des dépendances depuis) ;
  petit = 14 (13 jars androidx localisés dans le cache par le rapport) ;
  lourd = 250 artefacts du cache dont 49 % d'AAR (l'étude résolvait un
  projet Compose à 76 % d'AAR — la résolution d'un consommateur JVM
  bute sur l'ambiguïté de variantes Compose jvm/androidJvm, la recette
  retenue est le balayage déterministe du cache, équivalente en volume
  et en exercice de la normalisation AAR).
- **Froid lourd 9,2 s** : le jeu retenu contient des artefacts plus gros
  que celui de l'étude (AAR complets androidx, ~15 ko classes chacun) ;
  la construction est en arrière-plan et ne bloque RIEN (repli par
  sondage — les requêtes vivent dès les premiers segments prêts).
- **Le localisateur n'est PAS autoritatif pendant la construction** :
  les négatifs requièrent le repli par sondage (ADR 0111 §8) — le
  consommateur de l'étape 3 vide ses caches de localisation à la
  transition PRÊT (P7/P8).
- **`octetsCache` public** : transparence du budget (affichable par
  l'UI de l'étape 4, mesuré par le banc).

### 14.4 Suite — étape 3 (intégration du moteur)

L'environnement de noms du moteur vendored se branche sur l'index : le
`ClasspathInstantane` remplace le classpath plat de `CompilationEnvironment`
(les entrées MODULE résolues par les sources du frère), la complétion par
préfixe et les imports statiques consomment `nomsParPrefixe`/
`membresStatiques`, le `JdtLocateCache` se vide à la transition PRÊT.
Puis étape 4 (hôte, `SessionLsp`, état d'indexation UI, test de fuite)
et étape 5 (validation appareil §11.2).

---

## 15. Étape 3 — intégration du moteur (v0.94.0+)

### 15.0 Reprendre l'étape 3 dans une session nouvelle (mode d'emploi)

L'environnement de l'agent peut être RÉINITIALIÉ entre deux sessions :
le dépôt GitHub est la source de vérité durable. Procédure vérifiée
(session du 2026-10-11, lots A→F1 livrés ainsi) :

1. **Cloner** : `git clone https://<token>@github.com/jjoblab/CodeIDE.git`
   (token fourni par le propriétaire — révoqué après les missions) ;
   `git log --oneline -8` doit montrer les lots livrés
   (`v0.94.0` shims D3 lot 1, `v0.95.0` renommage §7, `v0.96.0`
   branchement index lot C, `v0.96.1` parse cache lot D, `v0.96.2`
   correctif 0-item lot F1).
2. **JDK** : le bac à sable n'a qu'un JRE — télécharger Temurin 21
   dans `/home/z/jdks/` (le `env.sh` du dépôt pointe
   `/home/z/jdks/jdk-21.0.12.1+1`) :
   `curl -sL -o jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" && tar xzf jdk21.tar.gz`.
3. **Gradle** : `export JAVA_HOME=/home/z/jdks/jdk-21.0.12.1+1
   GRADLE_USER_HOME=/home/z/.gradle` puis `./gradlew --no-daemon
   :lsp:java:test :lsp:testing:test` (le wrapper télécharge Gradle
   9.7.1 ; premier passage ~2 min). **Baseline attendue : `:lsp:java`
   295 tests, 69 skippés env-dépendants (android.jar absent du bac à
   sable), `:lsp:testing` 12 (4 intégration moteur ↔ index v0.96.3 +
   8 utilitaires du banc v0.98.0 — tournent en CI via koverVerify),
   `:lsp:index` 60 (dont régression « jar vide » v0.98.0), `:lsp:model`
   35, `:lsp:ecj-art` 2 — 0 échec.**
4. **Android SDK** : installé dans ce bac à sable à
   `/home/z/android-sdk` (cmdline-tools + `platforms;android-37.2` +
   `build-tools;36.0.0` via `scripts/setup-env.sh` — après un reset,
   le relancer avec `JDK_HOME_CIBLE=/home/z/jdks/jdk-21.0.12.1+1`).
   Le cache Gradle est peuplé par `:app:assembleDebug` ; les jeux du
   banc moteur se reconstruisent par
   `scripts/lsp-index/construire-jeux.sh` (recette versionnée §12),
   puis `./gradlew :lsp:testing:bench` (§15.8). Les tests android.jar
   restent skippés proprement sans SDK (Assumptions).
5. **État précis** : table §15.1 (lots livrés/à faire) ; le détail de
   chaque lot livré dans §15.2–§15.8 ; les lots E (moteur par module)
   et F (correctif + banc) sont LIVRÉS — le point d'entrée suivant =
   **étape 4** (hôte `:lsp:host` : `SessionLsp`, branchement cel-lsp,
   budget d'index dérivé du modèle — cf. §15.8 point 3) ou lot G
   (D3 lots 2–4, multi-session).
6. **Conventions de livraison** (au fil de la session) : un lot = un
   commit `feat(lsp-java): v0.X.Y - etape 3 lot …` VERSION_NAME/
   VERSION_CODE bumpés (`version.properties`), CHANGELOG en tête,
   entrée AGENTS.md (`- v0.X.Y : **mission « LSP Java » étape 3 (lot
   …)**` insérée AU-DESSUS de l'entrée précédente), LSP_JAVA.md
   §15.1 mis à jour (statut **livré**), tests verts AVANT commit,
   push immédiat (le travail non poussé est perdu au reset).
7. **Règles du dépôt** : lire AGENTS.md (règles impératives, style
   javadoc français, « zéro déglutition silencieuse », JUL) ; le
   module vendored est exempté detekt/spotless (ADR 0108) ; ne JAMAIS
   reformater les sources vendored.


> **Mission** : prompt 2 §7.1/§7.3 ; plan §10. Livrables : le traitement
> D3 par lots (§5.3) + renommage §7 au fil des lots ; l'environnement de
> noms du moteur adossé à `:lsp:index` (complétion par préfixe — tue
> W3) ; le cache de parse multi-entrées ; le moteur par module (verrou
> global → périmètre module) ; la suite de non-régression portée ; le
> correctif du « 0 item » du scénario NOM. Cible : complétion de
> types p95 < 100 ms (§9).

### 15.1 Plan d'exécution en lots (chaque lot : compilation + tests verts)

L'étape est découpée en lots livrables, chacun versionné, documenté et
poussé — un agent reprenant le travail s'arrête TOUJOURS à une frontière
de lot (dépôt vert, tests verts).

| Lot | Contenu | Version | Statut |
|---|---|---|---|
| A | D3 lot 1 — réécriture des 3 shims compat (niveau A, priorité absolue) + renommage `ArtCompat*` + package `jo.lspjava.ecj.compat` + patcher réaligné | 0.94.0 | **livré** |
| B | Renommage §7 intégral — package `jo.lspjava.jdt` → `jo.lspjava.ecj`, `Jdt*` → `Ecj*` (table §7), `CompletionPrefixMatcher` → `EcjPrefixMatcher`, `BindingParseCache` → `EcjParseCache` | 0.95.0 | **livré** |
| C | Branchement de l'index — SPI `IndexNoms` dans `:lsp:java`, consultation par `EcjCompilerEnvironment` (jarDe/nomsParPrefixe/estPaquet/membresStatiques), vidage du `EcjLocateCache` à la transition PRÊT (P7/P8), localisateur autoritatif à PRÊT, réouverture chaude sans index mémoire | 0.96.0 | **livré** |
| D | Cache de parse multi-entrées (`EcjParseCache`) | 0.96.1 | **livré** |
| E | Moteur par module — verrou global → périmètre module (commit a : carte de contextes + API qualifiée ; commit b : routage fichier → module, côté hôte — étape 4) | 0.97.0 | **livré (a)** |
| F | Correctif « 0 item » NOM (contrat jamais-null, 0.96.2) + banc moteur livré : cible p95 < 100 ms TENUE (27,7 ms lourd APRÈS) + 3 correctifs d'index découverts par le banc (§15.8) | 0.96.2 / 0.98.0 | **livré** |
| G | D3 lots 2–4 (réécritures niveau B, ~19 fichiers — à étaler sur plusieurs sessions) | ≥ 0.99.0 | à faire |

**Écart assumé et documenté** (lot B) : le plan §7 prévoyait le renommage
« au fil des lots » D3 (après réécriture de chaque fichier). Le lot B
applique le renommage intégral AVANT les réécritures D3 restantes,
parce que le branchement de l'index (lot C) touche précisément les
fichiers à renommer — renommer d'abord évite le triple brassage
(intégration sous anciens noms, puis renommage, puis réécriture).
Les réécritures D3 lots 2+ cibleront directement les noms définitifs.

### 15.2 Lot A — shims ART réécrits depuis la spécification JDK (v0.94.0)

- **`ArtCompatVersion`** (ex-`compat/RuntimeVersion`, niveau A : 69 %
  lignes / Jaccard 0,93) : réécrit depuis le javadoc
  `java.lang.Runtime.Version` + JEP 322 — balayage unique de `$VNUM`
  (bornes au premier `-`/`+`, segmentation sans `split`), segments vides/
  non numériques/négatifs rejetés, ordre élément par élément avec zéros
  de queue, `hashCode` aligné sur l'égalité ordinale (normalisation des
  zéros de queue). Divergences CONSERVÉES (testées) : `1.8 < 9` (le JDK
  normalise 1.8→8 — inutile pour ecj), suffixes non ordonnants.
- **`ArtCompatStackWalker`** (ex-`compat/StackWalker`, niveau A) :
  réécrit depuis le javadoc `java.lang.StackWalker` — `getCallerClass`
  saute les frames du shim PAR NOM puis la méthode invoquante (la
  profondeur du JDK « caller of the caller » est reconstruite, plus
  robuste qu'une profondeur codée en dur) ; les trois constantes
  `Option` du JDK ; jamais d'exception ni de null (divergence
  documentée : `IllegalCallerException`/`UnsupportedOperationException`
  sont API 33, et les sites Eclipse tolèrent un appelant imprécis).
  Surface imposée par les descripteurs du bytecode patché :
  `getInstance(Option)` à UN argument (le patcher ne change pas l'arité).
- **`ArtCompatInputStream`** (ex-`compat/InputStreamCompat`, niveau A) :
  réécrit depuis le javadoc `java.io.InputStream` — tampon à doublement
  (plus de `ByteArrayOutputStream`), `readNBytes` à cible exacte avec la
  garde `MAX_BUFFER` de la spécification (`Integer.MAX_VALUE - 8`),
  tampon 16 384 (constante DÉTERMINÉE PAR MESURE, pas recopiée :
  `BufferedInputStream.DEFAULT_BUFFER_SIZE`, l'ordre de grandeur le plus
  favorable aux lectures de tables ecj).
- **Patcher réaligné** : `ECLIPSE_RENAMES` cible
  `jo/lspjava/ecj/compat/ArtCompatVersion` et `ArtCompatStackWalker`,
  le shim d'entrée est `jo/lspjava/ecj/compat/ArtCompatInputStream`.
  Vérifié sur `ecj-3.40.0-art.jar` régénéré : 0 référence ancienne,
  4 classes référencent les nouveaux shims.
- **Tests** : `CompatShimsTest` migre vers `jo.lspjava.ecj.compat` et
  gagne deux tests (appelant de `getCallerClass` par porte
  intermédiaire — la sémantique « caller of the caller » ; les trois
  constantes `Option`). `:lsp:java` 277 tests verts, `:lsp:ecj-art`
  2 tests verts.

### 15.3 Lot B — renommage §7 (v0.95.0, livré)

Exécuté comme prévu : `git mv` (23 fichiers principaux + 27 tests,
historique préservé), retouches de déclaration, remplacement des
références (imports, javadocs, noms de tests). 65 fichiers
textuellement mis à jour. Vérifications : plus AUCUNE occurrence de
`jo.lspjava.jdt` dans le code (l'étiquette source des diagnostics
suit : `"jo.lspjava.ecj"`) ; `invalidateJdtEngine`/`isJdtInitialized`
deviennent `invalidateEcjEngine`/`isEcjInitialized` ; les mentions
provenance projet de référence (`{@code JdtResolver}`…) restent VOLONTAIREMENT
(elles documentent l'audit D3). `:lsp:java` 277 tests verts.

### 15.4 Lot C — branchement de l'index (v0.96.0, livré)

Le cœur Java vendored reste SANS dépendance Gradle vers `:lsp:index` :
il expose une **SPI publique `jo.lspjava.api.IndexNoms`** (requêtes +
état + transition PRÊT), consommée par `EcjCompilerEnvironment` :

1. `nomsParPrefixe(prefixe, limite)` — remplace le balayage du
   `simpleNameIndex` mémoire (tue W3 côté moteur) ; le filtre
   camel-hump/substring de `EcjPrefixMatcher` post-filtre les FQCN
   rendus (l'index ne connaît que le préfixe lexicographique) ;
2. `localiser(fqcn)` — à l'état PRÊT le localisateur est AUTORITATIF
   (négatifs sans sondage, ADR 0111 §8) : `EcjLocateCache` se vide à
   la transition (P7/P8) et cesse de sonder ;
3. `estPaquet`/`sousPaquetsDirects`/`typesDansPaquet` — côté jars de
   l'`isPackage`/`subPackages` (les sources projet et le jrt restent
   côté moteur) ;
4. `membresStatiques(fqcn)` — les imports statiques.

Le fil Kotlin (adaptateur `VueIndex` → `IndexNoms`) vit côté
`:lsp:index` en source de test/banc dans un premier temps ; la suite
d'intégration Java vit dans le module dédié **`:lsp:testing`**
(v0.96.3 — le `testImplementation(project(":lsp:index"))` posé dans
`:lsp:java` au lot C a été REJETÉ par la CI au `checkModuleDependencies` :
section 5.2, le module vendored ne dépend d'AUCUN module interne, test
compris, ADR 0108 ; la suite vit AU-DESSUS des deux modules, pattern
`:tooling:testing`) ; le branchement de production arrive à l'étape 4
(`:lsp:host`). `CompilationEnvironment` expose
`setIndexNoms(IndexNoms)` et propage au moteur dérivé.

### 15.5 Lots D–G (à faire)

- **D — parse cache multi-entrées (v0.96.1, livré)** : `EcjParseCache`
  LRU d'accès à 8 entrées, clé (chemin, longueur, FNV-1a 64 du
  contenu) — l'aller-retour entre fichiers (diagnostics de A,
  complétion dans B, retour à A) ne reparse plus ; asymétrie codegen
  conservée et non dégradative (un put sans codegen ne remplace pas
  une entrée avec codegen) ; 4 tests nouveaux (cohabitation, éviction
  LRU, invalidation ciblée, non-dégradation).
- **E — moteur par module (v0.97.0, commit a livré)** : un
  `EcjCompilerEngine` PAR module (son classpath, son verrou, ses
  caches) dans `CompilationEnvironment` ; les complétions du module A
  ne bloquent plus le module B. Le commit b (routage fichier → module
  dans `JavaLanguageServer`) exige l'association fichier → module de
  `:lsp:model` branchée par l'hôte — étape 4 : voir §15.7.
- **F — « 0 item » NOM (v0.96.2, livré) + banc moteur (v0.98.0,
  LIVRÉ — §15.8)** : le symptôme était le CONTRAT — `complete()`
  rendait `null` sur les contextes non capturés par conception (nom de
  déclaration, annotation en recovery, position après `[]`), null que
  les appelants avalaient en NPE (« 0 items » au banc). Corrigé :
  liste vide honnête partout (6 tests de non-régression sur jar
  compilé au vol). Le banc moteur est PORTÉ (`BancMoteur` +
  `:lsp:testing:bench`, jeux reconstitués — SDK installé par
  `scripts/setup-env.sh`, cache peuplé par `:app:assembleDebug`) et
  EXÉCUTÉ : cible §9 p95 < 100 ms TENUE (27,7 ms lourd APRÈS vs
  38,9 AVANT, même machine/corpus) ; il a en outre découvert TROIS
  défauts d'index corrigés dans le lot (jar vide d'AAR wrapper refusé
  → PARTIEL permanent ; écriture mtime à chaque hit de cache ;
  budget plat 30 Mo ≠ cible par jar → thrash mesuré — le banc passe
  un budget proportionnel, l'hôte étape 4 dérivera le budget du
  modèle). Protocole appareil (§11.2) : étape 5.
- **G — D3 lots 2–4** : ~19 fichiers niveau B à réécrire depuis
  spécification (§5.3), à étaler sur plusieurs sessions ; lot 4
  (ecj-art) selon l'option retenue par le propriétaire (D3 reste
  « PROPOSÉ — décision propriétaire », l'option 1 est suivie par
  défaut).

### 15.4.1 Lot C — détails de l'implémentation (v0.96.0)

- **SPI `jo.lspjava.api.IndexNoms`** (public, Java pur) : `estPret`/
  `ajouterObservateurPret` (notifié immédiatement si déjà prêt),
  `jarDe` (le jar porteur — null pour un conteneur illisible côté
  moteur), `nomsParPrefixe` (nom simple → FQCN porteurs, préfixe
  sensible), `estPaquet`/`sousPaquetsDirects` (noms COMPLETS —
  conversion en segments relative côté moteur, documentée)/
  `typesDansPaquet`, `membresStatiques` (public+static, imports
  statiques). Thread-safe par contrat ; observateur fautif attrapé
  par l'implémentation.
- **Voies de `EcjCompilerEnvironment`** :
  - `find`/`binaryType` à PRÊT : `EcjLocateCache.locateBinary(fqcn,
    sondeIndexDisque)` — le prober EST `jarDe` (négatif définitif
    côté jars, repli du type englobant `$` conservé) ; octets par
    `classBytesParChemin` (pool LRU de handles RawZipReader CLÉ PAR
    CHEMIN — un seul budget de descripteurs pour les voies mémoire et
    index). Hors PRÊT : voie mémoire inchangée (substrat du repli).
  - `findTypesByPrefix` à PRÊT : graines `nomsParPrefixe` (recherche
    binaire) — graine = `indexPrefix()` du matcher, DEUX variantes de
    casse du premier caractère (l'index trie binaire-sensible, le
    matcher matche insensible), limite adaptée (hump ×20 pour le
    rappel dispersé, plain ×8), post-filtre par le matcher gradué.
    **Écart documenté** : candidats SUBSTRING côté jars non servis à
    PRÊT (grade démoté, produit du balayage intégral W3) ; jrt
    (desktop) et sources les gardent — la suite de non-régression
    (CompletionRegressionTest) reste verte (le premier item matche
    toujours par préfixe, les items rendus matchent tous).
  - `isPackage`/`subPackages`/`directTypesInPackage`/`fqnsForSimpleName`
    : côté jars par l'index à PRÊT (repli mémoire à la moindre erreur
    de l'index — jamais de propagation), sources et jrt inchangés.
  - **Construction** : index attaché + DÉJÀ prêt → `buildJarIndex`
    SAUTÉ (réouverture chaude : zéro snapshot/index de noms/sets
    mémoire — la voie « ouverture index chaud < 1,5 s ») ; sinon
    construit (substrat du repli par sondage pendant la construction).
    `hasBinarySource` compte l'index branché ; `hasRealRClasses`
    reste faux à PRÊT-sans-mémoire (inutile : R résout par l'autorité).
  - **P7/P8** : observateur PRÊT → `locateCache.clear()` ; observable
    par `debugLocateCacheSize()` (tests).
- **`CompilationEnvironment.setIndexNoms`** : attache (idempotent —
  ré-attacher la même instance ne re-invalide pas) et invalide le
  moteur dérivé ; `EcjCompilerEngine` gagne le constructeur complet
  (5 arguments) qui propage.
- **Adaptateur de test** (`AdaptateurIndexNoms`, Java sur les classes
  Kotlin réelles) : `ServiceIndexDisque` (stockage temporaire,
  parallélisme 1) + `vuePour(ClasspathInstantane)` + surveillance de
  la transition par fil (observateurs P7/P8) ; clé de segment =
  sha256 du contenu du jar (« test:… »). `FauxIndexNoms` : PRÊT
  déclenché manuellement (transition déterministe — le vrai index
  construit en ~10 ms, impossible d'attacher pendant la fenêtre de
  façon stable), jarDe délègue au vrai (l'autorité de contenu reste
  exacte), compteur d'appels.
- **Fixtures** : jar compilé AU VOL par `javax.tools` (4 classes :
  `alpha.Alpha` avec champ+méthode statiques publics, `alpha.
  AlphaVoisin`, `alpha.sous.Profond`, `beta.Beta`) — zéro dépendance
  à android.jar ou au cache Gradle : les 4 tests d'intégration
  tournent PARTOUT (CI incluse), contrairement aux tests android.jar
  (skippés ici). Tests : parité (findTypesByPrefix sur 6 préfixes +
  insensible, fqnsForSimpleName, subPackages, directTypesInPackage,
  isPackage, diagnose équivalente sur une source qui importe le
  jar) ; autorité à PRÊT (binaryType via jarDe, négatif définitif,
  membresStatiques) ; réouverture chaude (0 entrée mémoire,
  complétion/paquets/résolution/isLikelyUsable OK) ; transition
  (négatif mémorisé → purge → jarDe appelé, classes connues OK).

### 15.7 Lot E — moteur par module (commit a LIVRÉ v0.97.0 ; commit b reporté à l'étape 4)

Objectif (§10) : le verrou global → périmètre MODULE. État actuel :
`CompilationEnvironment` porte UN contexte plat (sourceDirs +
classpath) et UN `EcjCompilerEngine` dérivé (tous ses services
`synchronized` — UNE complétion du module A bloque le module B).

Design retenu :
1. `CompilationEnvironment` porte une map de contextes PAR module —
   clé : chemin Gradle du module (`:app`) — chaque contexte =
   (sourceDirs, classpathEntries, compliance, jdkHome, IndexNoms) ;
   le contexte par défaut (comportement actuel) reste pour les
   appels non qualifiés (compat, tests).
2. NOUVELLE API : `remplacerContexteModule(String module, List<File>
   sources, List<File> classpath)` (replaceAll par module, mêmes
   sémantiques d'invalidation) + `jdt(String module)` + délégation du
   `ContextListener` existant par module.
3. Le fichier → module : la future `AssociationFichier` de
   `:lsp:model` (étape 1) au niveau hôte (étape 4) ; côté moteur, le
   paramètre module est explicite.
4. Chaque moteur garde SES caches (parse, locate, fast-diagnose) :
   l'isolation est le but (les segments disque restent mutualisés —
   ADR 0111 §2).
5. `dispose()` de CompilationEnvironment dispose TOUS les moteurs.
6. Migration en DEUX commits verts : (a) introduction de la map +
   API qualifiée, le contexte unique devient le module "défaut"
   (aucun changement pour les consommateurs) ; (b) JavaLanguageServer
   route par module (nécessite l'entrée module côté LSP — à coordonner
   avec l'étape 4 si l'association fichier→module n'est pas encore
   branchable : dans ce cas arrêter au commit (a) et documenter).
7. Tests : concurrence inter-modules (complétion A pendant diagnose
   B — l'un ne bloque plus l'autre), isolation des caches
   (invalidation de A ne vide pas B), reprise du test existant
   EcjCompilerEngineConcurrencyTest en version bi-module.

**Livraison (commit a, v0.97.0)** — design appliqué avec deux écarts
documentés :

- **État interne** : `ContexteModule` privé (sourceDirs,
  classpathEntries, moteur) ; carte `ConcurrentHashMap` clé = chemin
  Gradle TRIMMÉ ; le contexte défaut est un champ dédié (PAS dans la
  carte) gardé par `this` — les méthodes qualifiées à clé vide/null y
  sont REDIRIGÉES (aucune ambiguïté : les chemins Gradle commencent
  par `:`). Ordre de verrou : `this` → contexte, jamais l'inverse.
- **Écart 1 (compliance/jdkHome/IndexNoms partagés)** : le design
  listait ces trois champs PAR contexte ; ils vivent au niveau
  ENVIRONNEMENT (une seule plateforme par projet — tous les moteurs
  sont construits avec) et les changer invalide TOUS les moteurs
  (test `attacherLIndexInvalideDefautEtModules`). Un futur besoin
  par module n'exige que de descendre ces champs dans
  `ContexteModule` — aucune surface publique ne bougerait.
- **Écart 2 (auditeurs globaux)** : « délégation du ContextListener
  par module » = le mécanisme EXISTANT (liste globale, notification
  hors verrou) est déclenché aussi par les remplacements de modules —
  le live-republish ré-analyse tous les documents ouverts ; un
  auditeur PAR module n'a pas de consommateur avant l'étape 4 (le
  routage y décidera).
- **Commit b NON branchable (arrêt au commit a, conformément au
  design point 6)** : `JavaLanguageServer` reste mono-workspace à
  contexte plat (workspaceFolders lu mais non exploité — note en
  creux dans `initialize()`), et l'association fichier → module
  (`AssociationFichier` de `:lsp:model`) arrive avec l'HÔTE de
  l'étape 4. L'API qualifiée (`remplacerContexteModule`,
  `jdt(module)`, `isEcjInitialized(module)`) est prête à être
  consommée : le commit b sera l'affaire de l'étape 4, avec le
  branchement de production (adaptateur `:lsp:host`).
- **Tests (8, `MoteurParModuleTest`)** : verrou par moteur
  (déterministe : monitor de A TENU pendant que le diagnostic de B
  passe — avec un moteur unique, le délai expirerait) ; isolation des
  caches (re-sync de A : moteur de A reconstruit cache vide, moteur
  de B MÊME instance cache rempli) ; no-op système d'un sync
  redondant (même moteur, zéro notification) ; remplacement effectif
  (nouveau moteur + exactement une notification) ; invalidation
  globale à l'attache d'index (+ idempotence conservée) ; `dispose()`
  libère défaut ET modules (reconstruction possible après) ;
  redirection clé vide/null ; mix concurrent bi-module (4+4 threads,
  8×12 itérations diagnostics/résolutions/hovers — zéro exception,
  cohérence par moteur). Baseline : `:lsp:java` 295 tests.

### 15.8 Lot F2 — banc moteur (v0.98.0, livré)

Port du banc d'étude dans le dépôt : `BancMoteur.java` vit dans le
source set de TEST de `:lsp:testing` (paquet `jo.lspjava.ecj` — accès
package-private à `AdaptateurIndexNoms`), tâche
`:lsp:testing:bench` (JVM séparée par jeu × mode, `-Xmx2g
-XX:+UseSerialGC`, ProcessBuilder, jamais dans la chaîne de
vérification ; réglages `-PbancJeux/-PbancModes/-PbancIterations`).
Scénarios recréés d'après §4.2/§11.1 (le `BancMesure.java` d'étude
était hors dépôt) : complétion NOM (types, préfixe `B` en position
d'instruction — la métrique §9), complétion membre, diagnose premier
appel / memo / édition confinée, tas, poignées, dispose, index disque
froid + reprise. Protocole : 30 itérations, 3 échauffements (la cible
porte sur le régime chaud ; le premier appel est mesuré séparément) ;
corpus NORMALISÉ (AAR → `classes.jar` extrait — les deux modes voient
les mêmes jars, la production normalise à la résolution v0.92) ;
clés de segments par empreinte de contenu.

**Écart au design initial** : le banc vit dans `:lsp:testing` et non
dans un source set `bench` de `:lsp:java` (prévu §15.8 d'origine) —
la leçon v0.96.3 s'applique (section 5.2/ADR 0108 : le module vendored
ne référence AUCUN module interne, banc compris) ; `:lsp:testing` est
le point de contact sanctionné moteur ↔ index.

**Jeux** (recette `scripts/lsp-index/construire-jeux.sh`, SDK
android-37.2 installé par `scripts/setup-env.sh`, cache peuplé par
`:app:assembleDebug`) : petit = 9 jars uniques (android.jar + androidx,
dédupliqué), moyen = 31 jars uniques (classpath debug réel de `:app`,
v0.97.0 — l'étude comptait 73 entrées avec doublons de chemins),
lourd = 248 jars uniques (250 artefacts du cache, 125 AAR normalisés).

**Résultats** (sandbox 2 cœurs, JVM 2g/SerialGC — ordres de grandeur,
pas des constantes ; la comparaison significative est AVANT/APRÈS à
corpus et machine ÉGAUX) :

| Métrique | petit AV | petit AP | moyen AV | moyen AP | lourd AV | lourd AP |
|---|---:|---:|---:|---:|---:|---:|
| Index mémoire / disque froid (ms) | 186 / — | — / 764 | 250 / — | — / 741 | 1 506 / — | — / 9 571 |
| Reprise disque (ms) | — | 60 | — | 27 | — | 369 |
| Premier diagnose (ms) | 623 | 757 | 646 | 900 | 622 | 986 |
| Diagnose édité p95 (ms) | 33 | 36 | 33 | 33 | 10 | 28 |
| **Complétion NOM p50 (ms)** | 3,7 | 3,9 | 2,5 | 3,1 | 11,8 | **6,1** |
| **Complétion NOM p95 (ms)** | 25,3 | 28,1 | 7,7 | 24,8 | 38,9 | **27,7** |
| Complétion membre p95 (ms) | 3,4 | 8,2 | 7,9 | 11,4 | 37,2 | 27,6 |
| Tas stable / après dispose (Mo) | 23 / 12 | 23 / 13 | 24 / 12 | 20 / 13 | 141 / 12 | 100 / 13 |

Lecture contre la cible §9 (complétion types p95 < 100 ms) :
**TENUE sur les trois jeux** (maximum observé 38,9 ms AVANT lourd).
À l'échelle, l'APRÈS gagne partout (p95 27,7 vs 38,9, p50 6,1 vs
11,8, tas 100 vs 141 Mo — plus de snapshots par jar) ; sur petit et
moyen, l'AVANT reste plus rapide (corpus minuscule : le surcoût par
requête de l'index ne se rembourse pas) — l'index disque paie son
froid par la REPRISE (60/27/369 ms) et le tas. Le protocole appareil
(§11.2) reste pour l'étape 5.

**Trois découvertes du banc — corrigées dans ce lot** :

1. **Jar vide légitime refusé** (`:lsp:index`) : le `classes.jar` d'un
   AAR « wrapper » sans code (leakcanary-android 2.14, 22 octets
   d'EOCD) était rejeté « répertoire central hors bornes » par
   `LecteurZipBrut` → la construction terminait PARTIEL, jamais PRÊT
   (repli permanent en production). Corrigé : 0 entrée = jar valide,
   segment vide (test `un jar VIDE (classes-jar d'AAR wrapper) est
   légitime`).
2. **Écriture disque à chaque hit de cache** (`:lsp:index`) :
   `CacheSegments.obtenir` rafraîchissait le mtime du segment À
   CHAQUE accès (LRU du nettoyage des orphelins) — les requêtes
   globales (paquets, localisation négative) visitent tous les
   segments : écriture sur le chemin de LECTURE, +130–300 ms par
   requête à 248 segments. Corrigé : rafraîchissement AMORTI (au plus
   une fois par segment et 10 min — l'ordre LRU mémoire vit dans la
   carte).
3. **Budget de cache PLAT vs cible PAR JAR** (`:lsp:index`, mesuré) :
   à 248 segments l'ensemble de travail décodé (~61 Mo) dépasse le
   budget par défaut (30 Mo) — l'éviction LRU fait re-décoder à
   CHAQUE requête globale : complétion p95 **452–536 ms**, diagnose
   édité p95 **523–593 ms** (le compromis « p50 124 ms » documenté
   ADR 0111 §8.4, ici aggravé par la suppression du toucher-disque).
   Résident (budget proportionnel ~512 Kio/jar), les mêmes requêtes
   tiennent en millisecondes. Le banc APRÈS passe donc un budget
   PROPORTIONNEL au corpus (plancher 32 Mio) — la cible §9 « tas
   < 30 Mo / 100 jars » EST un budget par jar ; **l'hôte de
   production (étape 4) dérivera le budget du modèle** (arbitrage
   documenté, la refonte par-structure du cache reste une évolution
   possible).

Observation de fin de vie : le champ `indexNoms` du moteur est FINAL
— `dispose()` ne peut pas le détacher ; l'hôte doit lâcher moteur ET
index (le banc mesure le dispose références mortes : 12–13 Mo partout,
poignées 45–47 — contrat « 0 ressource orpheline » tenu).

---

## 16. Étape 4 — hôte, cycle de vie, éditeur (v0.99.0+, en cours)

Plan §10 : `SessionLsp` (transport abstrait D2, ADR 0109), branchement
`cel-lsp` (aligner lsp4j 0.21.2/0.22.0 — écart §2.6), état d'indexation
dans l'UI + action « Réindexer », test de fuite à la fermeture
(prompt 3). Découpage en lots livrables :

### 16.1 Lot h1 — le module `:lsp:host` (v0.99.0, livré)

**`SessionLsp`** — la façade aux frontières nettes (ADR 0109) :
`appliquerClasspath(ClasspathResult)` (post-sync v8), `reindexer()`
(l'action « Réindexer »), `serveur()` (UN `JavaLanguageServer` LSP4J
par connexion — le supplier du transport), `definition()` (le
branchement cel-lsp in-process :
`LanguageServerDefinition("java")` + `InProcessStreamConnectionProvider`),
`modele`/`etatIndexation` (flux publics DÉCOUPLÉS des types internes de
`:lsp:index` — `PhaseIndexation`/`EtatIndexation` sont à l'hôte), et
`close()` : scope de coroutines annulé PUIS ATTENDU dans la borne
(v0.121.1 — retour CI : une indexation en vol écrivait des segments
après le retour de `close` ; l'attente est bornée à 2 s, I2, cas
dégénéré journalisé), moteurs (défaut + modules) disposés, modèle vidé
— usage ultérieur REFUSÉ explicitement.

**`OrchestrateurLsp`** (interne) — le triptyque :
1. **Modèle** : `ConvertisseurModeleLsp.depuisClasspathResult` (v8,
   ADR 0113) → `ProjetLsp` publié dans `DepotModeleLsp` ;
2. **Index** : UNE vue FUSION sur l'union des classpaths (ADR 0111 §3
   — l'empreinte de contenu fait la déduplication : deux modules
   partageant un jar partagent le MÊME segment) ; entrées `MODULE` non
   indexées (l'identité du frère vit dans son propre instantané) et
   `DOSSIER` non plus en ce premier branchement (les sources du frère
   couvrent ses types — écart documenté, évolution possible) ;
   **budget de cache dérivé du modèle** (leçon §15.8 point 3 : ~512
   Kio par entrée, plancher 30 Mio — croissance monotone : le service
   n'est recréé que si le corpus dépasse le budget courant, les
   segments persistant, zéro ré-indexation) ;
   sync redondant = no-op chaud (empreinte de l'union inchangée →
   même adaptateur → `setIndexNoms` idempotent → moteurs conservés).
3. **Moteurs** : UN contexte PAR module via l'API qualifiée du lot E
   (`remplacerContexteModule`) — racines sources = les siennes
   (principal + générés) + celles des frères déclarés en entrée
   `MODULE` (résolution en sources du frère, motif lot C, un niveau,
   pas transitif) ; classpath = ses jars existants ; modules `ignore`
   comptés, sans contexte.

**`AdaptateurIndexNoms`** (interne) — l'adaptateur de PRODUCTION de la
SPI (l'homologue de test vit dans `:lsp:testing` v0.96.3) : empreintes
du MODÈLE (pas de relecture sha256), transition PRÊT par collecte du
`StateFlow` dans le scope de la session (pas de fil surveillant),
contrat P7/P8 (notification unique, PARTIEL/ERREUR = repli jamais
autorité).

**`LecteurStatistiquesFichierDisque`** — la première implémentation
réelle de la couture injectable de `:lsp:model` (existe/taille/mtime/
empreintePartielle — lecture bornée manuelle : `readNBytes` est API
33, minSdk 26 visé).

**Alignement lsp4j (écart §2.6 tranché : ALIGNER)** : `:lsp:host`
déclare lsp4j explicitement (`:lsp:java` le porte en `implementation`
— choix vendored) ; la résolution prend **0.22.0** (imposée par
cel-lsp). `AlignementLsp4jTest` prouve le moteur binaire 0.21.2 SOUS
0.22.0 : cycle complet initialize/initialized/didOpen/completion/
shutdown/exit + version résolue vérifiée. La voie RemoteEndpoint/gson
complète vivra avec le branchement h2.

**Tests (9)** : `OrchestrateurLspTest` (modèle/modules/2 moteurs
distincts/résolution frère/complétion via l'index/PRÊT/sync
redondant/ignorés/budget par jar), `SessionLspTest` (**test de fuite**
— le serveur est COLLECTABLE après close : la chaîne
serveur→environnement→moteurs→adaptateur→cache LRU meurt AVEC la
session, les locales comprises — le champ `indexNoms` du moteur est
FINAL, §15.8 : garder la session vivante garderait la chaîne ;
poignées stables ; close idempotent ; usage refusé),
`AlignementLsp4jTest`. kover ≥ 80 %, detekt/spotless/lint verts,
`checkModuleDependencies` conforme (allow-list §5.2 :
`lsp:java`/`lsp:index`/`lsp:model`).

### 16.2 Lot h2 — branchement éditeur + UI (v0.100.0, livré)

**Coutures de l'hôte** (`:lsp:host`) : `SourceClasspathLsp` (fun
interface — résolution du `ClasspathResult` BRUT pour la session ;
l'implémentation vit dans le graphe Hilt de l'app, les features ne
voient que le type de l'hôte) et `DossierIndexLsp` (emplacement du
stockage partagé injecté — le défaut `~/.codeide/lsp/index` n'a pas de
sens sous ART où `user.home` n'est pas garanti).

**Liaisons Hilt de l'app** (`ModuleLsp`) : `SourceClasspathLsp` habille
le NOUVEL accesseur protocole `GradleApiImpl.classpathBrut()`
(`tooling:client` — le résultat v8 TEL QUEL, sans conversion
`core:domain` : le domaine garde SA voie pour `lsp-classpath.json`, une
seule résolution côté serveur — CacheSync) ; `DossierIndexLsp` lie
`filesDir/codeide/lsp/index` (répertoire privé stable, segments
auto-guérissants). L'app est le SEUL point où tooling et hôte se
rencontrent (règle §2.2).

**Le ViewModel** (`EditorViewModel`) : `sessionLsp` (StateFlow — ouverte
dès que projet + racine FUSE sont connus, AVANT la garde outils : le
moteur ne dépend pas du tooling ; racine irrésoluble = journal seul,
l'édition n'est jamais bloquée) ; `nourrirSessionLspSiSyncUtile` après
CHAQUE sync utile (sync manuelle + revalidation silencieuse — une sync
restaurée nourrit la session via la revalidation qui suit, SANS geste
utilisateur) : `source.resoudre(dossier, arguments)` →
`session.appliquerClasspath(brut)` ; `cheminFuseDe(uri)` (branchement
d'onglet côté activité) ; changement de projet = ancienne session
REFERMÉE + nouvelle ouverte ; `onCleared` referme (le contrat de fuite
est couvert par SessionLspTest h1) ; `ActionEditor.Reindexer` →
`session.reindexer()`.

**L'activité** (`EditorActivity`) : `CoordonnateurLsp` (interne — le
LspProject cel-lsp créé UNE fois par session avec
`session.definition()`, le `LspEditor` de l'onglet actif
`createEditor("file://…")` + `setHost(LspEditorViewHost(EditorView))`
(depuis code-editor 3.71.0, plan 03 — avant : `setEditorView`) +
`setCurrentFilePath` + `connect()` asynchrone, déconnexion au changement
d'onglet, `shutdown()` à la destruction — miroir MINCE du LspCoordinator
de l'ancienne app, UN langage) ; branchement ASYNCHRONE après le rendu
(garde URI anti-re-connexion, course « onglet changé pendant la
résolution FUSE » refermée en re-vérifiant l'état) ; **barre d'état de
l'indexation** sous les onglets (la vue `VuePointEtat` RÉUTILISÉE :
anneau accent en construction, disque vert plein prêt, bordure verte
partielle, bordure accent en erreur + libellé localisé avec progression
`faits/total`) ; action « Réindexer » au menu de l'éditeur (visible si
session vivante) ; l'état d'indexation est chaîné par `flatMapLatest`
(la collecte MEURT avec sa session).

**Dépendances** : `feature:editor` → `:lsp:host` (allow-list §5.2 —
exception feature EXPLICITE au trio core:ui/domain/model, même esprit
que feature:terminal → core:terminal-runtime ; cel-lsp reste interdit
en dépendance DIRECTE de feature, il arrive par l'`api` de l'hôte,
traversée documentée §16.1) ; `app` → `:lsp:host` (liaisons Hilt). Le
moteur vendored est désormais DEXÉ dans l'APK (in-process ART, ADR
0109 D2 — multidex natif minSdk 26, APK 28,2 Mo debug).

**Tests (8, `LspEditorViewModelTest`)** : ouverture au projet connu
(racine résoluble), silence honnête sans racine (session absente,
journal), nourrissage après sync utile (modèle publié), source muette
(session vivante non nourrie), relocalisation du projet (ancienne fermée
+ nouvelle ouverte sur la NOUVELLE racine), réindexation sans session
(journal seul), réindexation avec session, `cheminFuseDe`. Vérification
complète : spotless/detekt/checkModuleDependencies/lint verts, tests
editor/host/client verts, kover ≥ 80 % (host, client),
`:app:assembleDebug` vert.

### 16.2.1 v0.100.1 — retour d'appareil : la première exécution réelle

Lot h2 a branché l'éditeur sur le serveur in-process — v0.100.0 était la
PREMIÈRE version où `:lsp:index` et `:lsp:host` s'exécutaient sur ART (les
étapes 1–3 ne les avaient éprouvées que sur JVM). Le retour d'appareil
(moto g06, Android 15 / API 35, rapport 62f8627d + journal de déploiement)
a fait remonter QUATRE défauts, tous corrigés dans v0.100.1 :

1. **CRASH FATAL — `NoClassDefFoundError: java.lang.ProcessHandle`**
   (`EcrivainSegment.ecrire`, à la PREMIÈRE écriture de segment, juste
   après un sync réussi) : le nom `.tmp` unique (v0.93.1, course CI)
   dérivait de `ProcessHandle.current().pid()` (JDK 9) et
   `Thread.currentThread().threadId()` (JDK 19). **`java.lang.ProcessHandle`
   et `Thread.threadId()` sont ABSENT du runtime ART ≤ API 35** — la
   plateforme android-37 les fournit (vérifié : `javap` sur android.jar
   37.2 les liste TOUS DEUX), c'est pourquoi le module compilait et
   passait CI : `:lsp:index` est un module **Kotlin JVM pur**, compilé
   contre le JDK 21, dont le bytecode est ensuite DEXÉ dans l'APK **sans
   qu'aucun lint ne vérifie son niveau d'API** (NewApi ne court que sur
   les modules Android). L'app plantait donc à CHAQUE construction
   d'index — le LSP ne pouvait jamais atteindre PRÊT. Correctif :
   unicité par compteur atomique process-wide + `System.nanoTime()`
   (existants depuis l'API 1 ; le renommage atomique Linux REMPLACE la
   cible, deux écrivants concurrents laissent un fichier complet).

2. **Latent — `InputStream.readNBytes(byte[], int, int)` (JDK 9, API
   Android 33+) dans `LecteurSegment.enTeteValide`** : la reprise
   « à chaud » aurait fait `NoSuchMethodError` sur tout appareil
   API 26–32 (minSdk 26 — la discipline existe déjà côté hôte,
   `LecteurStatistiquesFichierDisque.lireBorne` : le lecteur d'index ne
   la respectait pas). Correctif : `DataInputStream.readFully` sur un
   tampon borné par la taille du fichier (API 1).

3. **« JAR orchestrateur indisponible : <chemin>: The source file
   doesn't exist. »** (pile `JarDeployer$deployer$2.invokeSuspend`,
   JarDeployer.kt:183 — le REPLI de renommage) : le `.tmp` de déploiement
   portait un nom FIXE et le repli `copyTo` vivait HORS de la hiérarchie
   typée v0.99.1. Deux déploiements CONCURRENTS (redémarrage de l'app
   pendant qu'un process déploie encore) partageaient le même `.tmp` :
   le gagnant le renommait, le perdant voyait SA source disparaître →
   `NoSuchFileException` BRUT, classé DÉFINITIF par `deploiementEstDefinitif`
   alors que le JAR ÉTAIT déployé. Correctif : `.tmp` UNIQUE (compteur +
   nano), exclusion mutuelle in-process (`Mutex`), repli TYÉ
   (`EcritureImpossible`) et TOLÉRANT (un `.tmp` disparu avec un JAR en
   place portant DÉJÀ notre empreinte = un concurrent a gagné la course :
   résultat atteint, pas un échec), purge du `.tmp` à nom fixe hérité.

4. **Fuite de session LSP au démarrage** (journal d'appareil : deux
   « session LSP ouverte » à 15 ms d'écart, AUCUNE refermée) :
   `ouvrirSessionLspSiPossible` est sollicitée par DEUX coroutines (suivi
   du projet + sync d'ouverture) ; la garde était franchie AVANT la
   résolution FUSE SUSPENDANTE, la seconde session ÉCRASAIT la première
   sans la refermer — moteurs, constructions d'index et modèle ORPHELINS
   (et double écriture concurrente des MÊMES segments). Correctif :
   ouverture EXCLUSIVE (`Mutex`) et garde REVERIFIÉE sous le verrou.

**Audit ART de complément** (même classe de risque, modules JVM purs
dexés) : balayage des sources `:lsp:index`/`:lsp:host`/app/feature contre
les API JDK 9+ (readAllBytes/transferTo/readNBytes/Files/Paths/
ProcessHandle/threadId/List.of…) — **plus AUCUNE occurrence** côté
lecture/écriture d'index ; vérification BYTECODE post-build
(`javap -c` sur les 5 classes du chemin critique, puis scan des 30 dex de
l'APK : zéro référence `java/lang/ProcessHandle`). Le `List.of`/`Map.of`
(JDK 9, API Android 30+) du moteur vendored `:lsp:java` est documenté au
même titre : acceptable tant que la cible appareil est ≥ API 30 — à
garder en tête si minSdk redescend. **Règle qui en découle** : tout
module JVM pur destiné à être DEXÉ s'en tient au sous-ensemble
OpenJDK-8 + API-26-sûres (la même discipline que les shims D3 du
moteur), et toute API JDK récente passe par un équivalent vérifié contre
android.jar.

**Tests** : 3 nouveaux `EcrivainSegmentTest` (unicité ×1000, écritures
concurrentes du même contenu vers le même fichier — round-trip complet,
en-tête court par lecture bornée), 2 nouveaux `JarDeployerTest`
(déploiements concurrents ×8 sans exception ni `.tmp` survivant, purge
du `.tmp` hérité) ; chaîne verte (detekt/spotless/kover ≥ 80 %, tests
editor/daemon/index, assembleDebug, APK vérifié).

**Comment savoir si le LSP est branché** (protocole de validation
appareil) : ① la **barre d'état d'indexation** sous les onglets — anneau
accent = construction en cours (progression faits/total), **disque vert
plein = PRÊT** (index connecté : complétion/diagnostics/hover
opérationnels), bordure verte = partiel, bordure accent = erreur ; ② le
journal applicatif (tag `LspEditeur` : « LSP Java connecté (chemin) »,
tag `Editor` : « session LSP ouverte » — UNE fois depuis v0.100.1) ; ③
l'épreuve directe : la complétion dans un fichier `.java` propose des
SYMBOLES Java (types, membres) et non la seule liste lexicale.

### 16.2.2 v0.100.2 — second retour d'appareil : l'éditeur rendu, l'accueil corrigé

Le retour d'appareil v0.100.1 (rapport e69fc0d8, même moto g06 /
Android 15 / API 35) confirme d'abord que **v0.100.1 tient** : le journal
montre orchestrateur déployé PUIS connecté (handshake, 7 fonctionnalités),
session LSP ouverte UNE fois, sync complet (294 tâches), session NOURRIE
(1 module actif, 46 entrées d'index — soit des écritures de segments
réussies sur ART), revalidation silencieuse terminée, réindexation
acceptée — **plus AUCUNE trace du `ProcessHandle` de v0.100.0 ni du
`NoSuchFileException` JarDeployer**. Trois défauts corrigeaient le reste :

1. **La barre d'état d'indexation avalait l'éditeur** (« elle prend tout
   l'espace de l'éditeur view ») : `VuePointEtat` (étape 31, point 7 dp
   dans une zone de 20 dp) ne surcharge pas `onMeasure` — tous ses usages
   historiques (`popover_legende.xml`, lignes d'arbre) le déclarent à
   **20 dp FIXE**, mais `activity_editor.xml` l'avait mis en
   `wrap_content`. Or la mesure par défaut (`View.getDefaultSize`) sous
   `AT_MOST` retourne la TAILLE DU SPEC (toute la hauteur disponible) :
   le point mesurait l'écran restant, la barre `wrap_content` devenait
   haute comme l'éditeur, et `conteneur_editeur` (poids 1) était écrasé à
   néant. Correctif : 20 dp × 20 dp fixe (le motif des autres usages) —
   la barre redevient une fine ligne de 24 dp entre onglets et éditeur.
   Leçon : **toute vue sans `onMeasure` surchargé doit être dimensionnée
   en FIXE dans le XML** (le `wrap_content` d'une vue custom est TOUJOURS
   suspect — il se comporte en `match_parent` sous `AT_MOST`).

2. **CRASH ACCUEIL — `ClassCastException: String cannot be cast to
   TriAccueil`** (`HomeViewModel.<init>`, retour accueil 3 min après
   l'éditeur, même process) : `ChangerTri` persiste `tri.name` (String —
   `SavedStateHandle` n'accepte QUE des types de plateforme) mais la
   lecture `savedState[CLE_TRI] ?: TriAccueil.RECENTS` inférait
   `TriAccueil` : à la première restauration de l'état sauvegardé
   (activité détruite puis recréée depuis la pile), le `get` exécutait
   un cast String → énuméré. Correctif : `get<String>` + résolution par
   nom (`entries.firstOrNull`), valeur inconnue → `RECENTS` — une
   préférence ne fait JAMAIS crasher. Règle : **écrire et relire le MÊME
   type de plateforme** dans un `SavedStateHandle` (le motif de
   `WizardViewModel` : `get<String>` + `fromPersistedName`).
   +2 tests `HomeViewModelTest` (restauration depuis « NOM » sans
   exception, tri ; valeur inconnue → RECENTS).

3. **« LSP Java connecté » invisible** (« Voici les seules logs
   concernant lsp que j'ai, pas de lsp java connecté ») : la ligne était
   en `journal.d` — DEBUG — or la verbosité par défaut (NORMAL) filtre à
   INFO (`LogEngine` : `level.isAtLeast(config.minLevel)`) : la
   connexion POUVAIT réussir sans laisser une seule trace visible (seul
   l'échec, en WARN, aurait paru). Promue en **INFO** : au niveau NORMAL,
   la séquence complète se lit désormais dans le journal —
   « session LSP ouverte » (Editor) → « LSP Java connecté (chemin) »
   (LspEditeur) → « session LSP nourrie » (Editor) — et l'absence de
   « LSP Java : échec de connexion » (WARN) devient SIGNIFICATIVE.

**Protocole « LSP branché » consolidé** (même triple preuve, désormais
toutes trois observables) : ① barre d'état — FINE ligne sous les onglets
(depuis v0.100.2) : anneau = construction, disque vert = prêt ; ② journal
— « LSP Java connecté » ENFIN visible en verbosité normale ; ③
complétion `.java` proposant des symboles Java.

### 16.2.3 v0.100.3 — troisième retour d'appareil : le branchement muet (contrat de pont détourné)

Le retour d'appareil v0.100.2 (barre d'état FINE et bien placée — v0.100.2
confirmé installé) rapporte le symptôme inverse du précédent : le fichier
`.java` ouvert n'offre AUCUNE complétion au-delà de la liste lexicale
native de code-editor, les erreurs volontaires ne produisent AUCUN
diagnostic, et le journal dit « session LSP ouverte → nourrie → refermée »
sans JAMAIS « LSP Java connecté » — ni AUCUN « échec de connexion » (WARN).
Cette absence DOUBLE est le diagnostic : `brancherLspSiNécessaire`
n'atteignait même pas `CoordonnateurLsp.brancher`.

1. **Le pont détourné** : le branchement résout le chemin FUSE du FICHIER
   AVANT de créer le `LspEditor` — par `cheminFuseDe`, qui réutilisait le
   pont `ResolveurCheminFuse`. Or ce pont n'avait QU'UN contrat :
   RÉPERTOIRE (garde `File.isDirectory`, kdoc « résout le dossier du
   projet » — hérité du terminal T6, v0.43.0). Un `.java` n'est jamais un
   répertoire : chaque fichier résolvait à `null`, et l'appelant
   renonçait AU SILENCE (sa propre kdoc le disait : « renonce
   silencieusement à brancher »). La session vivait donc SANS éditeur
   branché : moteur sain, index nourri, zéro consommateur — complétion
   lexicale seule, zéro diagnostic. Les tests JVM restaient verts : les
   faux du pont sont des lambdas « tout réussit » qui ne portent PAS la
   garde — l'écart prod/tests classique.
2. **Correctif** (trois morceaux, le contrat au centre) :
   - `ResolveurCheminFuse.resoudreEntree` — contrat EXPLICITE « fichier
     OU dossier, si l'entrée existe » (garde `exists`) ; défaut = `invoke`
     (un pont simple qui ne distingue pas résout pareil) ;
     `ResoudreRepertoireProjet` l'override avec la garde d'existence,
     l'opérateur garde `isDirectory` (terminal, Git, clone, racine LSP
     restent des consommateurs de répertoires). La traduction + garde
     devient la fonction pure `cheminFuseVerifie` (éprouvée en JVM : un
     dossier temporaire joue le volume monté) ;
   - `cheminFuseDe` passe par `resoudreEntree` ;
   - plus AUCUN renoncement silencieux : « branchement LSP renoncé :
     chemin FUSE introuvable (uri) » en WARN, l'abandon de course en
     DEBUG — la séquence se lit désormais sous le tag `LspEditeur`, le
     même que le coordonnateur.
3. **Tests** : +4 `ResoudreRepertoireProjetTest` (le fichier résolu par
   le contrat d'entrée / REJETÉ par le contrat répertoire — LA
   régression ; un dossier reste résolu par l'entrée, symétrie ; une
   entrée absente rejetée) ; `LspEditorViewModelTest.cheminFuseDe`
   renforcé : le faux SÉPARE les deux contrats (`invoke` → null,
   `resoudreEntree` → chemin) — si le ViewModel repasse par l'opérateur,
   le test échoue.

**Règle** : un pont réutilisé au-delà de son contrat d'origine doit
l'ÉTENDRE (méthode explicite, garde propre), jamais être détourné — et
tout renoncement de branchement se JOURNALISE : un chemin silencieux est
indistinguable d'un moteur mort.

**Note disponibilité progressive** (question posée au retour v0.100.2) :
le moteur est déjà le modèle « Android Studio » — la session s'ouvre
AVANT la sync (`ouvrirSessionLspSiPossible` précède la garde outils :
le premier branchement d'onglet `.java` peut arriver sans classpath,
complétion lexicale + symboles du fichier), et le classpath Gradle la
REJOINT après la première sync utile (« session LSP nourrie »). La
disponibilité est progressive, pas verrouillée sur la fin d'indexation ;
l'index ne conditionne pas le branchement, il en nourrit la QUALITÉ.

### 16.3 Suite (à faire)

Arbitrage du propriétaire au retour de v0.102.0 (2026-10-12) : la
mission « LSP Java » est CLOSE sur ses objectifs nominaux — quelques
défauts appareil restent signalés « à corriger pour plus tard » sans
liste arrêtée. Points restant OUVERTS, reportés sans échéance :

- mesure appareil du bout-en-bout éditeur ↔ moteur (prompt 2 §9 : p95
  complétion, mémoire) — protocole §11.2, à exécuter au prochain retour
  appareil (elle sera menée avec les mesures de baseline de la mission
  « Cycle de vie », `docs/CYCLE_DE_VIE.md` §4.5, mêmes séances) ;
- lot h3 (éventuel) : diagnostics LSP dans l'onglet Problèmes (le pont
  cel existe : `setOnDiagnosticsPublishedListener`), navigation
  inter-fichiers (`setOnNavigateToFileListener`), source décompilée
  (`LspProject.decompiledSource`) — reporté ; l'onglet Problèmes et la
  navigation recouperont le chantier « Cycle de vie » (prompt 3, mission
  3, étapes C/E) ;
- ADR 0112 strict — modules Android sans image jrt (évolution de l'API
  moteur par module, jdkHome partagé aujourd'hui) : reporté, documenté
  §16.5 ;
- l'écart restant de §16.1 (RemoteEndpoint/gson complet) vit avec
  l'usage : le branchement in-process d'aujourd'hui n'y passe pas.

Le lot h4 « LSP instantané », voisin du h3 ci-dessus, est LIVRÉ en
v0.101.0 (§16.4) — ses DEUX ÉCARTS réels (pré-classpath android.jar,
réouverture lente) sont fermés en v0.102.0 (§16.5).

### 16.4 Lot h4 — « LSP instantané » (v0.101.0, livré)

Le lot répond à la question posée au retour v0.100.2 : « le LSP
proposera-t-il complétion et diagnostics uniquement après le sync puis
l'indexation, ou comme Android Studio, bien avant ? ». La réponse
d'architecture était déjà OUI côté câblage (§16.2.3, note disponibilité
progressive : la session s'ouvre AVANT la sync, le classpath la
rejoint) — mais le MOTEUR restait « tout ou rien » :
`isLikelyUsable()` (au moins un jar OU l'image jrt) fermait TOUT quand
la plateforme manquait : `DiagnosticService.diagnose` retournait une
liste VIDE (pas même la syntaxe), la complétion sautait le moteur
entièrement. Un `.java` ouvert avant la première sync (ou sans JDK du
terminal installé) était branché mais MUET.

**Le mode instantané** (le « dumb mode » d'IntelliJ/Android Studio) —
deux régimes de repli qui montent automatiquement :

1. **Diagnostics de syntaxe immédiats** : `parseSeul` (nouveau point
   d'entrée du moteur) invoque le parseur ecJ SANS environnement de
   noms — `Parser.parse` (diet : signatures + structure) PUIS
   `getMethodBodies` (le second pass OBLIGATOIRE, le diet parse laisse
   `statements` null — les erreurs INTERNES aux corps et les locales
   n'apparaissent qu'après lui). `diagnoseSyntax` filtre ensuite au bit
   `IProblem.Syntax` (0x40000000) : problèmes du PARSEUR seuls,
   indépendants de toute résolution. Branché dans
   `DiagnosticService.diagnose` quand `isLikelyUsable()` est faux — le
   live-republish du ContextListener (sync arrivée, republishAllOpen-
   Documents) remplace le repli par le régime plein SANS intervention
   (montée automatique).
2. **Complétion instantanée** (`EcjSyntaxCompletion`) : sur le MÊME
   parse seul — types, méthodes, constructeurs, champs (de tout le
   fichier, member types compris) ; paramètres et locales de la MÉTHODE
   englobant le caret (locales déclarées avant lui, granularité de
   blocs ignorée — repli large documenté) ; mots-clés du langage.
   Filtrage strict par préfixe. **Zéro devinette** : aucun type du JDK
   n'est proposé (COMMON_TYPES reste au régime plein) — le jrt puis le
   classpath les apportent en montant.

**Les deux garanties** (testées, `EcjSyntaxInstantTest` — 12 tests,
pré-condition forcée par jdkHome sans `lib/modules`) : ① une source
syntaxiquement cassée rend SES erreurs (point-virgule, parenthèse,
accolade) ; ② une source saine mais non résolue rend une liste VIDE —
le repli ne fabrique JAMAIS de « cannot be resolved ». Le didOpen
annonce honnêtement le régime : `[h4] plateforme absente — mode
instantané` (une ligne par document, JUL).

**Montée code-editor 3.41.0 → 3.49.2** (demandée au même retour) :
compat vérifiée par diff des sources publiques entre les tags — zéro
méthode retirée (cel-lsp/cel-core inchangés, `EditorView` en ajouts
purs : guides d'indentation IndentRange + cache par révision, sticky
lines, color pickers, plis d'imports, complétion native par mots-clés
avec context gate — cette dernière complète le mode instantané côté
éditeur). 3.49.2 est aussi le correctif de build JitPack
(`safe.directory` + install step explicite) — artefacts vérifiés.

**Ce que le h4 n'est pas** : le régime plein reste le chemin nominal
(bindings, classpath, hiérarchies, quick-fixes) ; hover/definition/
navigation restent silencieux sans plateforme (honnête — mentirait sans
résolution) ; l'état d'indexation UI reste INITIAL avant sync (la
barre est masquée — le mode instantané est le régime VISIBLE de
l'éditeur, l'index est la QUALITÉ du régime plein).

### 16.5 v0.102.0 — lot h4 (suite) : les DEUX ÉCARTS réels avec Android Studio

Le retour accompagnant v0.101.0 citait les mots du plan : le lot h4
était décrit comme la fermeture des deux écarts RÉELS de CodeIDE face à
Android Studio — §16.4 a livré le mode instantané (le repli
syntaxique), PAS ces écarts. Ils sont fermés ici.

**Écart 1 — pré-classpath = JDK seul.** Android Studio branche
`android.jar` dès la config SDK ; CodeIDE attendait le modèle Gradle
(champ `androidJar` par module, protocole v8). Or le SDK est installé
sur disque par le bootstrap (`EtapesAndroidSdk`) AVANT toute sync —
et l'examen du parcours a révélé que le champ n'était **JAMAIS
consommé** : `OrchestrateurLsp` ne poussait que les entrées JAR aux
moteurs par module — `Activity`, `Bundle`, `TextView` restaient
« cannot be resolved » même APRÈS sync. Trois correctifs :

1. **Voie rapide à l'ouverture** : `SessionLsp(androidJar)` branche la
   plateforme du toolchain (`outilchain.androidJar()`) au contexte PAR
   DÉFAUT dès l'ouverture — android.* ET java.* (le boot classpath
   embarque les deux) résolvent AVANT toute sync, en régime plein et
   non plus en repli syntaxique ; le mode instantané v0.101.0 reste le
   repli SANS SDK (le didOpen annonce son régime).
2. **Moteurs par module** : `jarsDe(module)` inclut désormais
   `module.androidJar` (ADR 0112 enfin consommé côté orchestrateur).
3. **Index disque** : la plateforme rejoint l'UNION INDEXABLE — l'index
   PRÊT est AUTORITATIF (ADR 0111 §8 : négatif définitif, sonde mémoire
   sautée) : un jar hors union y est INVISIBLE même présent au classpath
   moteur. Deux sous-bugs corrigés au passage : `VueIndex.estPaquet`
   niait les paquets INTERMÉDIAIRES (les segments ne portent que les
   feuilles — « The import android cannot be resolved » alors que
   `android.util.Log` était localisé) ; le test
   `estPaquet - préfixe intermédiaire` verrouille la sémantique.

**Écart 2 — réouverture lente.** Le journal d'appareil le prouvait :
état sync restitué à 20:39:03, session nourrie à 20:41:02 (~2 min) —
le classpath n'était pas persisté, chaque ouverture attendait la sync
complète. Android Studio, lui, rouvre index et modèle persistés. Le
mécanisme existait depuis l'étape 1 (`PersistanceModeleLsp`,
`.codeide/local/lsp-modele.json`, validation W7 taille/horodatage) —
mais n'était câblé NULLE PART. Désormais :

- `SessionLsp.appliquerClasspath` PERSISTE le modèle appliqué
  (best-effort : échec journalisé JUL, jamais bloquant) ;
- `SessionLsp.nourrirDepuisModelePersiste()` le relit et rejoue le
  MÊME triptyque modèle/index/moteurs (`OrchestrateurLsp.appliquerModele`,
  refactor du pipeline) — idempotent par construction (redondant =
  no-op chaud, sémantiques lot E) ;
- `restaurerEtatSyncSiEmpreinteIdentique` (v0.40.1) nourrit la session
  depuis le cache AVANT de lancer la revalidation silencieuse : **la
  clé de cache est l'empreinte Gradle** (SHA-256 des fichiers de build,
  `sync-state.json`) — empreinte différente ⇒ JAMAIS de cache
  trompeur, sync complète ; modèle relu non vérifié (W7) ⇒ SIGNALÉ,
  utilisable, remplacé par la revalidation.

**Protocole de retour appareil** : séquence attendue à la réouverture
— « session LSP ouverte » → « session LSP nourrie depuis le cache (N
module(s)) » → (revalidation silencieuse) → « session LSP nourrie » ;
à la PREMIÈRE ouverture d'un `.java` avant toute sync : complétion
android.* ET java.* (régime plein si SDK installé) ; après sync :
`TextView`, `Activity`, `Bundle` résolvent dans les moteurs par module.

**Ce qui reste ouvert (honnête)** : ADR 0112 strict — modules Android
sans image jrt — demande une évolution de l'API moteur par module
(jdkHome partagé aujourd'hui) ; `lsp-classpath.json` (miroir domaine,
ADR 0058) reste le fichier des STATS de sync (pied de l'onglet),
`lsp-modele.json` celui du nourrissage — deux consommateurs, deux
formats, aucune duplication de rôle.

## 17. Correctifs d'exploitation — v0.108.0 (retour propriétaire)

> Deux constats d'exploitation remontés par le propriétaire sur son
> projet réel (multi-gradle, Data Binding actif) : ① « The type
> java.lang.invoke.StringConcatFactory cannot be resolved » ② « The
> import com.example.monip.databinding cannot be resolved ». Les deux
> sont des DIAGNOSTIQUES INcurables côté utilisateur avant correctif —
> reproduits, expliqués et corrigés dans le moteur.

### 17.1 StringConcatFactory — le faux positif STRUCTUREL

**Fait établi** (vérifié sur android-31 et android-36) : la classe
n'existe dans AUCUNE plateforme Android (seul `StringConcatException`
y figure) — D8 désugare TOUJOURS les concaténations `invokedynamic`
vers StringBuilder : le code source est LÉGAL pour Android, quel que
soit le compileSdk. Le seul fournisseur possible de la classe est
l'image **jrt** du JDK du toolchain (java.base).

**Mécanisme du message** (reproduit par
`StringConcatFactoryReproTest`, fixture ASM `batisse.Biblio` étend
l'intrinsèque) : le diagnostic natif ecj « ...cannot be resolved. It
is indirectly referenced from required .class files » arrive quand
une classe BINAIRE du classpath RÉFÉRENCE le type absent — or les
classes du projet compilées par le serveur Gradle (javac target ≥ 9)
laissent un `CONSTANT_MethodHandle` vers
`makeConcatWithConstants` dans le pool de CHAQUE concaténation
compilée. Sur l'appareil concerné, l'image jrt n'avait PAS chargé
(échec silencieux de `jrt-fs` — cache négatif `jrtScanAttempted`) :
android.jar seul ne peut pas fournir la classe.

**Correctif** (deux volets, `StubsIntrinseques`) :
- **Repli 4 de `find`** — APRÈS sources, jars et jrt : une
  DÉCLARATION minimale des intrinsèques du compilateur
  (`StringConcatFactory` — le bug ; `ObjectMethods` — records ;
  `SwitchBootstraps` — switch pattern) répond en dernier recours.
  Le `.class` est FABRIQUÉ À LA MAIN en octets (CAFEBABE v8, aucun
  membre : le binding de hiérarchie se referme, le contenu ne compte
  pas — ecj ne vérifie pas la signature des méthodes bootstrap au
  binding, constaté par le repro). Zéro compilation au runtime, zéro
  dépendance, cache statique. **Priorité préservée** : un jdkHome
  chargé ou une plateforme future qui contiendrait la classe
  répondent TOUJOURS avant le stub ; l'index de complétion n'est PAS
  nourri (personne ne complète un intrinsèque à la main).
- **Visibilité du échec jrt** : le catch de `scanJrtIfNeeded` passe
  de FINE à WARNING (message pédagogique « java.* tombe sur la
  plateforme seule... à instruire ») — la cause racine silencieuse
  devient un signal de recette.

**Tests** (5, `StringConcatFactoryReproTest`) : garde de
non-régression (conditions appareil exactes — le message ne doit
JAMAIS réapparaître), témoin jrt (le mécanisme jdkHome est sain),
contrat des stubs (liste fermée — `java.lang.String` etc. JAMAIS
stubbés — format CAFEBABE), structurel sur API 36 (le bug n'est PAS
transitoire : même la plateforme la plus récente n'a pas la classe).

### 17.2 databinding — downgrade honnête (motif R étendu)

**Constat** : les classes `XxxBinding` ne sont produites par AGP
(`dataBindingGenBaseClasses`, puis compilation) qu'AU BUILD — la sync
ne les génère PAS. Avant tout build, l'import
`<namespace>.databinding.*` est STRUCTURELLEMENT non résoluble : l'ERROR
sec est exact mais incurable (même famille que la classe R avant
première construction — §16/`maybeDowngradeUnresolvedR`).

**Correctif** (`maybeDowngradeUnresolvedDatabinding`) : ERROR→HINT +
remède (« générées par AGP lors de la construction — la sync seule ne
les crée pas, lance un Build »), même philosophie « zéro devinette »
que R. Reconnaissance par les ARGUMENTS STRUCTURÉS du problème ecj
(`arguments()[0]` = nom importé — stables entre locales, pattern
`JavaProblemCodes`) et non par le message localisé ; segment
`.databinding` strict (points des deux côtés — « mydatabinding » ne
déclenche pas). **Coupure honnête** : la sonde
`hasGeneratedDatabindingClasses()` (TTL 5 s, sources générées + jars
du classpath) coupe le downgrade dès que la première classe générée
existe — une faute de frappe vers `*.databinding.*` redevient une
ERROR intégrale.

**Tests** (3, `DatabindingImportDowngradeTest`, vrai flux LSP4J avec
client espion) : HINT + remède sans build ; ERROR intégrale avec
générées présentes ; paquet homonyme non déclassé.

### 17.3 Ce que ces correctifs ne font PAS (et les décisions ouvertes)

- **Génération post-sync** : le diagnostic HINT reste jusqu'au
  PREMIER build. Option pour éliminer le délai : exécuter
  `generate<Variant>Sources` (ou ciblée
  `dataBindingGenBaseClasses<Variant>`) à la fin de la sync — coût
  une tâche Gradle (up-to-date après le premier passage). **Décision
  §2.2-bis à trancher** avec la politique du démon (S6).
- **Compliance PAR PROJET** : le moteur compile aujourd'hui en « 17 »
  fixe (`CompilationEnvironment()` défaut) — le protocole v8/v9 ne
  transporte PAS le niveau Java du projet
  (`sourceCompatibility/targetCompatibility`). Un champ optionnel
  dans `ClasspathModule` (compatible v8+ : null = 17) alignerait les
  diagnostics de niveau (var/records/switch selon le projet).
  **Candidat protocole v10.**
- **Autres générés** (W10) : BuildConfig/ViewBinding/Hilt/Room/KSP :
  même famille — le downgrade R + databinding couvre les DEUX
  remontées réelles ; étendre la reconnaissance aux autres packages
  générés se fera sur constat (pas de speculative generality).

## 18. Journal de stabilisation — reprise (I15) et protocole appareil

> Reprise du journal (audit §6.3 I15 : « un serveur stabilisé dont le
> journal s'arrête deux mois plus tôt n'est pas auditable »). Une entrée
> brève par lot LSP Java à partir du plan de stabilisation
> (`docs/PLAN_STABILISATION_LSP.md` §3.1, audit
> `docs/audit/rapport-analyse-lsp-java-codeide.md`).

### 18.1 v0.134.11 — lot S0-a « hygiène protocolaire » + S0-b « rename honnête »

Contenu (audit §6.3 I2, I3, I5, I7, I8, I9 + quick-win I1) :

- **I2 — garde lifecycle centralisée** : `verifierLifecycle()` au sommet
  de chaque REQUÊTE (`JavaTextDocumentService` ×19, `executeCommand`) —
  `ServerNotInitialized` (-32002) avant `initialized`, `InvalidRequest`
  (-32600) après `shutdown` ; les NOTIFICATIONS sont ignorées hors fenêtre
  (la spec demande d'ignorer, pas de rejeter — une notification n'a pas de
  réponse d'erreur). Façades de test (`server == null`) court-circuitent
  la garde — le contrat s'exerce via le serveur.
- **I3 — initialize réentrant** : second appel → `InvalidRequest`
  (l'ancien warning re-transitionnait l'état).
- **I5 — didSave** : republication SYSTÉMATIQUE depuis le buffer vivant
  (l'ancien code conditionnait à `includeText` — le commentaire promettait
  plus que le code) ; document jamais ouvert → invalidation seule ; texte
  sauvegardé divergent adopté.
- **I7 — version injectée** : `serverInfo.version` lue de
  `jo/lspjava/pack/version.properties` (convention packs
  lsp:kotlin/lsp:xml, synchronisée à chaque lot) ; « 1.2.0 » fantôme
  supprimé.
- **I9 — convention null/vide** : règle écrite en tête de
  `JavaTextDocumentService` ; application — `foldingRange` rend désormais
  `null` sur moteur occupé/dépassement/échec (aligné sur le cas « moteur
  absent » déjà null) ; hover null ; navigation/inlayHint/
  semanticTokens/completion restent « vide » (pas de repli client).
- **I8 — rename honnête** : `RequestFailed` (-32803) TYPOÉE sur moteur pas
  prêt / aucune cible (message FR + `data` structuré `renommage.sansCible`)
  / échec interne — plus jamais d'édition vide silencieuse ;
  `prepareRename` plage EXACTE du jeton (caret milieu compris) + placeholder
  exact ; `documentChanges` versionnés si le client négocie
  `workspace.workspaceEdit.documentChanges`, forme `changes` sinon
  (cel-lsp vérifié par décompile : n'annonce pas — reçoit la forme legacy).
- **I1 — javadoc honnête** de `ServerCapabilitiesProvider` (le contrat
  « négociation » promis mais jamais implémenté est réécrit ; les
  capabilities sont stockées et exposées via `clientCapabilities()`).

Tests : +15 (`LifecycleHygieneTest` 10, `RenameHonneteTest` 5 — les
critères d'acceptation S0-a/S0-b : requête avant init / après shutdown
rejetée, didSave sans includeText, `serverInfo.version` = version du
dépôt, placeholder exact, erreur explicite, renommage nominal inchangé) ;
`InlayHintTest`/`EcjChainedLambdaHintsTest` traversent désormais le
lifecycle réel. lsp:java 330 verts, lsp:host 86.

### 18.2 Réservé — protocole de mesure appareil (S0-c)

Emplacement du PV de mesure appareil prévu par le lot S0-c (décision D3
du propriétaire — lot G maintenant / plus tard / jamais publier, cf.
audit §11.2). La séance dédiée n'a pas encore eu lieu.

**Décision D3 du propriétaire — partiellement actée (16/10/2026, communiqué
direct)** : sur la question de la réécriture (lot G), le propriétaire
tranche **« réécriture de certaines classes (fichiers), mais pas tous »**
et clarifie la provenance :

- **La quasi-totalité des fichiers du module sont les SIENS** — écrits à
  sa manière ;
- la référence à projet de référence faite lors des recherches de solution d'erreurs
  de test fut une **consultation d'approche parmi d'autres projets** —
  « pas au mot près mais la compréhension pour le faire à ma manière » ;
- conséquence opérationnelle : la réécriture cloisonnée (option 1 de
  l'ADR 0110) s'applique **CIBLÉE** aux fichiers dont la dérivation reste
  objectivement établie par l'audit (niveau A : les trois shims
  `compat/RuntimeVersion.java`, `compat/StackWalker.java`,
  `compat/InputStreamCompat.java` — 40-69 % de lignes identiques
  normalisées) ; les fichiers niveau B (ressemblance textuelle faible,
  0,08-0,26) **restent en l'état** sur l'assertion d'auteur du
  propriétaire — pas de lot G complet.

Le volet « protocole de mesure appareil » du S0-c reste réservé (séance
dédiée à venir) ; l'ADR 0110 porte la trace formelle de la décision.

## 19. Journal de stabilisation — lot S1 « parité utile JDT.LS »

### 19.1 v0.134.16 — S1-b « diagnostics riches » + S1-c « workspace/symbol » + S1-d « watched files côté hôte »

Contenu (audit §11 S1-b/S1-c/S1-d, I4) :

- **S1-b — tags de diagnostics** : `toLspDiagnostic` dérive les TAGS LSP
  de l'ID ecj transporté par le code (`ecj:<id>`) —
  `DiagnosticTag.Unnecessary` pour la famille des « never used »
  (`UnusedImport`, `UnusedPrivate{Type,Field,Method,Constructor}`,
  `UnusedLabel`, `UnusedTypeParameter`) — l'éditeur cel peint la plage
  estompée (consommation VIVANTE, `LspDiagnosticsManager`) ;
  `DiagnosticTag.Deprecated` pour les usages d'un membre déprécié
  (`UsingDeprecated{Type,Field,Method,Constructor}`). Aucun tag inventé
  hors familles connues. `relatedInformation` reportée (le client cel ne
  la consomme pas — la voie deviendra vivante avec l'onglet Problèmes).
- **S1-c — workspace/symbol** : capacité
  `workspaceSymbolProvider` déclarée ; handler dans
  `JavaWorkspaceService` — recherche de types par préfixe sur le
  substrat gradué de la complétion (index disque des jars + sources
  projet en cache TTL + image jrt), verrou de l'index pris par le moteur
  (`EcjCompilerEngine.symbolesEspace`), plafond 64. Localisations
  honnêtes : type de SOURCE → fichier réel + plage du nom déclarant
  (heuristique textuelle bornée, repli 0) ; type binaire sans source
  ouvrable → ÉCARTÉ (S3 « mode profond » le servira — jamais d'URI
  factice). Requête vide / moteur non prêt → liste vide (I9).
- **S1-d — watched files côté hôte** (I4, remède (a)) : le handler
  serveur `didChangeWatchedFiles` n'est plus une voie muette —
  `SessionLsp` démarre un surveillant de fichiers (`SurveillantFichiersHote` :
  balayage PÉRIODIQUE borné du répertoire projet — `.java` seul,
  plafond 2000, exclusions build/.git/.gradle, diff par mtime en ordre
  déterministe) qui pousse les `FileEvent` à CHAQUE serveur Java vivant
  (registre `serveursJava`, inscription à `serveur()`, désinscription par
  le rappel `aLArret` de shutdown/exit — même contrat que
  KotlinLanguageServer). Paramètre `surveillanceFichiersMs` (défaut 5 s,
  ≤ 0 = neutre — tests). Tolérance : tour fautif n'arrête jamais la
  boucle ; événements d'un tour fautif perdus (baseline avancée), le
  changement suivant repousse.
- **Tests** : `JavaS1RicheTest` (8 — tag Unnecessary sur import inutilisé
  via VRAI flux diagnostics, absence de tag hors famille, capacité,
  requête vide I9, recherche sur source réelle + plage du nom, moteur non
  prêt, rappel aLArret, événements tolérés) ;
  `SurveillantFichiersHoteTest` (5 — baseline, diff créé/modifié/supprimé
  en ordre du chemin, périmètre .java+exclusions, borne, boucle fautive).
- Parité : digeste régénéré (973 fichiers), contre-vérification SANS
  flag verte (--rerun-tasks).

### 19.2 v0.134.17 — S1-a « compliance par projet » (protocole v10)

Contenu (audit §11 S1-a ; ferme le lot S1 complet — S0-a/b déjà livrés
en v0.134.11, S1-b/c/d en v0.134.16) :

- **Protocole v10** : `ClasspathModule` gagne
  `sourceCompatibility`/`targetCompatibility` (champs OPTIONNELS,
  `null` = niveau indécidable par le tooling → le moteur garde son
  défaut « 17 ») — le niveau Java du projet
  (`sourceCompatibility`/`targetCompatibility` des scripts Gradle)
  voyage enfin avec le classpath. Sources de la lecture : modèle v2
  d'AGP (`AndroidProject.getJavaCompileOptions()`, javap vérifié sur
  builder-model 9.4.1) pour les modules Android ;
  `IdeaModule.getJavaLanguageSettings()` (`languageLevel`,
  `targetBytecodeVersion`) pour les modules JVM purs. Deux résolutions
  protégées (AGP ancien/Gradle ancien → null — jamais d'échec du
  classpath, règle 14). `GradleProtocol.PROTOCOL_VERSION` 9 → 10
  (handshake égalité exacte — les deux bouts vivant dans le même APK ;
  goldens des 38 messages régénérés, échantillon porteur des niveaux
  « 17 »).
- **Descente au modèle** : `ModuleLsp.compatibiliteSource/Cible` — la
  normalisation se fait à la frontière du modèle
  (`normaliserNiveau` : « 1.8 » → « 8 », blanc/VERSION_x → `null`),
  rendant l'empreinte du module indépendante de la notation du tooling ;
  le niveau ENTRÉ dans l'empreinte du module — changer le niveau d'un
  script Gradle invalide SON moteur (diagnostics de niveau à jour),
  sans toucher les autres modules.
- **Descente au moteur** : `CompilationEnvironment` porte désormais un
  niveau PAR contexte module (champ `complianceLevel` de
  `ContexteModule`) — nouvelle surcharge
  `remplacerContexteModule(module, sources, classpath, compliance)` ;
  la surcharge historique à 3 arguments CONSERVE le niveau posé (un
  appelant qui ignore le niveau ne le réinitialise pas par accident) ;
  `null` explicite (surcharge à 4) = retour honnête au défaut
  (l'outil de build ne livre plus le niveau). Un changement de niveau
  SEUL invalide le moteur du module et notifie les auditeurs — le sync
  redondant (listes ET niveau identiques) reste un no-op système.
  `jdt(module)` construit le moteur avec le niveau du module (hérité du
  défaut sinon) ; observabilité : `complianceModule(module)` côté
  environnement, `complianceDemandee()` côté moteur.
- **Hôte** : `OrchestrateurLsp.pousserMoteurs` descend
  `ModuleLsp.compatibiliteSource` avec les listes du module — un module
  Java 8 reçoit les diagnostics de SON niveau (`var` refusé), un module
  21 les siens (records/switch à motifs conformes).
- **Tests** : `MoteurParModuleTest` +2 — critère d'acceptation de
  l'audit (le MÊME source `var`+record REFUSÉ sous « 8 »
  (`erreurs >= 1`) et le switch à motifs CONFORME sous « 21 »
  (`erreurs == 0`), deux contextes du même environnement) + sémantiques
  du niveau (conservation par la voie à 3 arguments, retour au défaut
  par `null`, invalidation + notification d'un changement de niveau
  seul, no-op redondant) ; `ModelesAndroidV2Test` +1 (JavaCompileOptions
  du faux, niveaux bruts « 1.8 » transportés tels quels) ;
  `ConvertisseurModeleLspTest` +3 (normalisation, transport, empreinte
  — muet ≠ 8 ≠ 21) ; `OrchestrateurLspTest` +1 (la descente
  bout-en-bout ClasspathResult v10 → moteur du module, niveau posé,
  sync redondant no-op conservé).
- **Parité** : digeste régénéré (fichiers .kt du dépôt décalés par les
  champs v10), contre-vérification SANS flag.

### 19.3 v0.134.18 — S2-a « confort structurel » : rangeFormatting + survol riche (javadoc)

Premier sous-lot du **S2 — Confort structurel** (plan de stabilisation
§3.1, audit §6/§9 : « Hover javadoc markdown — écart net », « rangeFormatting
— le formateur Eclipse sait le faire »). Miroir Java du survol riche
Kotlin (v0.134.9).

- **`textDocument/rangeFormatting`** : la capacité
  `documentRangeFormattingProvider` est déclarée et le handler câblé sur
  `EcjFormatter` — nouveau `editionsRange(source, offset, length)` qui
  retourne les éditions APLATIES de la plage (ordres croissants,
  disjointes ; `null` si la source est imparsable — le contrat
  historique « null ou inchangé, jamais mutilé » est conservé via le
  refactoring de `formatRange`, qui réutilise la voie commune).
  Le formateur Eclipse parse l'unité entière mais ne produit d'éditions
  QUE dans la plage : le client n'applique que la sélection touchée, le
  reste du buffer n'est jamais déplacé. Éditions converties en
  `TextEdit` LSP (offsets → positions UTF-16 par `toPosition`).
- **Survol riche (javadoc)** : `EcjCompilerEngine.hoverRicheAt` retourne
  un `SurvolRiche(signature, documentation)` — la signature du symbole
  au caret (désormais SANS clôtures ; `hoverAt` les pose, contrat
  inchangé) PLUS la javadoc nettoyée de sa déclaration quand elle vit
  dans la MÊME unité. Résolution de la déclaration APRÈS la traversée
  complète (registre des `Declaration` + `sameBinding` — une référence
  vue avant sa déclaration dans le fichier retrouve sa doc).
- **Extraction textuelle du javadoc** (`EcjJavadocMarkdown.plageAvant`) :
  le parseur ecj ne monte le nœud `Javadoc` que si
  `docCommentSupport` est activé (ce qui n'est pas le cas du moteur) —
  l'extraction est donc textuelle, en DEUX cas selon la position reçue :
  (1) le range de déclaration OUVRE sur `/**` (comportement observé du
  parseur : `declarationSourceStart` inclut le commentaire — sonde
  empirique) → trancher avant ; (2) sinon remontée BORNÉE de l'en-tête
  (modifieurs, annotations avec littéraux, génériques) jusqu'au `*/`
  fermant, toute trace de code (`; { } =` hors parenthèses) ou tout
  commentaire ordinaire rompant l'attachement. Points d'entrée ecj :
  `declarationSourceStart` (couvre annotations+modifieurs, en repli
  `sourceStart` qui pointe le TYPE DE RETOUR — piège). Le nettoyage
  (`EcjJavadocMarkdown.nettoyer`) est borné et honnête : délimiteurs et
  astérisques de marge retirés, `{@code}`/`{@link}`/`{@linkplain}` →
  spans de code (dièse membre → point DANS les spans seulement),
  `{@literal}` → texte, `<p>` → paragraphe, `<br>` → saut de ligne ;
  le reste passe VERBATIM.
- **Format LSP (KP14-Java)** : le handler hover abandonne la forme
  dépréciée `MarkedString`-string — signature seule →
  `MarkupContent` PLAINTEXT (sans clôtures) ; signature + javadoc →
  MARKDOWN (` ```java ` + `\n\n` + documentation), même patron que le
  serveur Kotlin et JDT.LS. Périmètre honnête : les symboles binaires
  (classpath, android.jar, jrt) et les déclarations d'AUTRES fichiers
  n'ont pas de javadoc ici — signature seule.
- **Tests** : `EcjHoverJavadocTest` (9 — méthode/champ/type via référence
  OU déclaration, symbole binaire sans doc, `hoverAt` inchangé sous
  clôtures, nettoyeur : cas limites, astérisques, balises inline) ;
  `EcjFormatterRangeTest` (6 — éditions confinées à la plage, tri
  croissant disjoint, plage vide → liste vide, source imparsable
  jamais mutilée, pleine page ≡ format document, cohérence
  `formatRange`/`editionsRange`) ; `JavaS2ConfortTest` (5 — capacité
  déclarée (forme `Either`), sélection formatée bornée à la plage avec
  reconstruction client, rejet lifecycle I2, hover PLAINTEXT /
  hover MARKDOWN). Chaîne : `:lsp:java` 360 verts, `:lsp:host`
  test/lintDebug/koverVerify + spotless/detekt des modules Kotlin +
  `checkModuleDependencies` verts.
- **Parité** : aucun fichier `.kt` touché — digeste INCHANGÉ
  (contre-vérification sans flag inutile ; le digeste ne couvre que les
  sources Kotlin du dépôt).

### 19.4 v0.134.19 — S2-b « confort structurel restant » : selectionRange, onTypeFormatting, codeLens, hiérarchies, filterText (I12)

Lot final du plan S2 Java (§ plan de stabilisation 3.1) : les cinq
requêtes restantes sont servies par le même substrat navigation
(`EcjNavigationEngine`) et AST (`EcjSelectionRanges`, nouveau service).

- **selectionRange** — pour chaque position demandée, la chaîne des nœuds
  AST englobants, du jeton nom le plus intérieur à l'unité de compilation
  ; plages STRICTEMENT croissantes (dédup des nœuds aux bornes identiques
  — `LabeledStatement` partage celles de son enfant), chaque
  `SelectionRange.parent` pointant vers le niveau englobant suivant.
  La réponse reste alignée sur `positions[]` (une plage d'ancrage vide
  quand aucun nœud ne couvre la position).
- **onTypeFormatting** — le caractère VENANT d'être tapé délimite la
  construction : `}` → le nœud qui SE TERMINE à l'accolade (bloc imbriqué
  ou déclaration), `;` → la déclaration dont `declarationSourceEnd` est
  le point-virgule. Éditions confinées à la cible (même contrat que
  rangeFormatting — le reste du buffer n'est jamais déplacé) ;
  caractère attendu absent à l'offset → no-op.
- **codeLens « références »** — une lens par déclaration porteuse (types
  tous niveaux, méthodes, constructeurs, champs — pas de locales) ; la
  liste initiale ne coûte qu'un parse mutualisé, le comptage vit dans
  `codeLens/resolve` (substrat renommage : bindings, homonymes exclus,
  overlay, déclaration exclue, `CancelToken` 12 s, résultat partiel).
- **Hiérarchies call / type** — généralisation de `findSuper` /
  `findImplementations` : éléments localisés dans les SOURCES du projet
  (types binaires → item absent — D3, jamais d'URI factice) ; appels
  ENTRANTS = sites `MessageSend` dont le binding résout EXACTEMENT vers
  la même déclaration (clé : classe déclarante + sélecteur + paramètres
  érasés — les appels transitant par un supertype ne sont pas attribués,
  conservateur), groupés par méthode appelante la plus intérieure avec
  les plages des sites ; SORTANTS = les `MessageSend` du corps résolus
  vers des déclarations sources, dédupliqués par cible. Balayage borné
  (400 fichiers / 8 s, cap 256 appelants, résultat partiel).
- **filterText (I12)** — posé EXPLICITEMENT sur le label (la clé que
  `EcjPrefixMatcher` a matchée) : le client cel-lsp filtre sur
  `filterText` quand il est présent (`LspCompletionProvider` →
  `EditorCompletionPopup`) — le contract est verrouillé contre toute
  décoration future du label ; les candidats camel-hump ne sont plus
  éjectables par un re-filtrage client strict.

Pièges ecj relevés (sondes empiriques) :

1. **Corps de méthode SANS nœud `Block`** — les instructions du corps
   sont traversées directement sous la déclaration ; un nœud `Block`
   n'existe que pour les blocs IMBRIQUÉS. La cible du `}` d'une méthode
   est donc sa déclaration (`declarationSourceEnd` = l'accolade).
2. **`LocalDeclaration.sourceStart/End` = le NOM seul** — l'instruction
   « int y=2; » vit dans `declarationSourceStart/End` (le `;` INCLUS) ;
   c'est la plage qui sert de cible au `;` et de niveau de sélection.
3. **Parse tolérant-aux-fautes** — même « class {{{{ » produit une unité
   partielle : la chaîne de sélection reste VALIDE (plages dans les
   bornes de la source) au lieu d'être vide.
4. **`MessageSend.binding` est déjà typé `MethodBinding`** — un pattern
   `instanceof` serait « inconditionnel » (Java 21, interdit en
   `-source 17`).
5. **`ASTVisitor` n'expose pas `visit(FieldDeclaration, ClassScope)` ni
   `visit(AssertionStatement, …)`** — une surcharge inexistante cascade
   en erreurs `name clash` trompeuses ; s'en tenir aux 142 surcharges
   réelles.

Chaîne LSP : la construction des `SelectionRange` imbriqués parcourt la
chaîne moteur (intérieure → extérieure) en INVERSE — le résultat retourné
est le nœud INTÉRIEUR, chaque `parent` pointant vers le niveau englobant.

État client (honnêteté) : cel-lsp n'a pas d'UI codeLens / hiérarchies /
selectionRange / onTypeFormatting — ces capacités profitent aux clients
LSP tiers ; `filterText` EST consommé (transport + filtrage).

**Tests** : `EcjSelectionRangesTest` (7 — croissance stricte + contenance
à chaque niveau, escalade return→méthode→classe, jeton nom de champ vs
déclaration complète, bornes hors source / source vide, parse tolérant
sans exception, cibles `}` (bloc de méthode) et `;` (instruction, sans
déborder), déclencheur inattendu refusé) ; `EcjHierarchiesTest` (8 —
élément type au caret sur référence, supertypes directs classe+interface,
sous-types par balayage, type binaire → null, élément méthode au caret
sur appel, entrants groupés avec 2 sites, sortants résolus
`demo.Base.utilise`, pas de méthode au caret → vide) ;
`JavaS2bProtocoleTest` (11 — capacités (formes `Either`), chaîne
bout-en-bout strictement croissante, `}` reformate le bloc SANS toucher
la méthode voisine, caractère étranger → no-op, codeLens 2 lenses +
resolve « 0 référence » puis « 1 référence » multi-fichiers (fichiers
canoniques sur disque — la résolution ecj mappe `demo.Base` →
`demo/Base.java`), typeHierarchy prepare dans SA source + supertypes +
subtypes, caret hors type → vide (et type englobant au corps = convention
`contextType`), callHierarchy prepare sur l'APPEL → déclaration Base +
incoming (1 appelant, 1 site) + outgoing (1 cible source), `filterText`
posé sur TOUS les items, garde I2 sur les cinq requêtes). Chaîne :
`:lsp:java` 387 verts ; `:lsp:host` testDebugUnitTest/lintDebug/
koverVerify + `checkModuleDependencies` verts. Aucun `.kt` touché —
digeste de parité INCHANGÉ.
