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
| C | Branchement de l'index — SPI `IndexNoms` dans `:lsp:java`, consultation par `EcjCompilerEnvironment` (localiser/nomsParPrefixe/estPaquet/membresStatiques), vidage du `EcjLocateCache` à la transition PRÊT (P7/P8), localisateur autoritatif à PRÊT | 0.96.0 | à faire |
| D | Cache de parse multi-entrées (`EcjParseCache`) | 0.96.x | à faire |
| E | Moteur par module — verrou global → périmètre module | 0.97.0 | à faire |
| F | Correctif « 0 item » NOM + banc moteur (cible p95 < 100 ms) + suite de non-régression portée | 0.98.0 | à faire |
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

### 15.4 Lot C — branchement de l'index (à faire, architecture arrêtée)

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
`:lsp:index` en source de test/banc dans un premier temps (tests
d'intégration Java dans `:lsp:java`, `testImplementation(project(
":lsp:index"))`) ; le branchement de production arrive à l'étape 4
(`:lsp:host`). `CompilationEnvironment` expose
`setIndexNoms(IndexNoms)` et propage au moteur dérivé.

### 15.5 Lots D–G (à faire)

- **D — parse cache multi-entrées** : `EcjParseCache` (ex
  `BindingParseCache`) garde N unités récemment parsées (clé :
  chemin + hachage du contenu) — W8, la frappe multi-fichiers ne
  reparse plus tout.
- **E — moteur par module** : un `EcjCompilerEngine` PAR module (son
  classpath, son verrou) dans `CompilationEnvironment` ; les
  complétions du module A ne bloquent plus le module B.
- **F — « 0 item » NOM + banc moteur** : reproduire au banc porté
  (rouge), corriger, mesurer la cible p95 < 100 ms.
- **G — D3 lots 2–4** : ~19 fichiers niveau B à réécrire depuis
  spécification (§5.3), à étaler sur plusieurs sessions ; lot 4
  (ecj-art) selon l'option retenue par le propriétaire (D3 reste
  « PROPOSÉ — décision propriétaire », l'option 1 est suivie par
  défaut).
