# Rapport faux positifs `kt.unresolved` — étape A (mesurer et expliquer avant de corriger)

**Mission** : fiabiliser le LSP Kotlin (`PROMPT-AGENT-LSP-KOTLIN`, étape A).
**État mesuré** : HEAD `44e47bb`, v1.15.0, 2026-10-26.
**Commits de l'étape** : `6cb15ee` (A.1 instrumentation), `550e53e` + `bab4dd9` (A.2 corpus),
`7bf4fe4` (A.3 différentiel + mesures), ce rapport (A.4).
**Vérification** : `./gradlew :lsp:kotlin:test :lsp:kotlin:detekt :lsp:kotlin:spotlessCheck
:lsp:kotlin:koverVerify :lsp:index:test :lsp:index:detekt :lsp:index:spotlessCheck
:lsp:index:koverVerify` — **vert** (401 tests `:lsp:kotlin`, dont 29 nouveaux ; 0 régression).
La matrice des capacités (`docs/LSP_MATRICE_CAPACITES.md`) ne bouge pas : l'étape A n'ajoute
aucune capacité, elle mesure et instrumente. Le `CHANGELOG` sera consolidé à la livraison de la
mission (le versionnement du dépôt est piloté par release).

## 0. Ce que l'étape a produit

1. **Instrumentation « explain » (A.1)** — pour chaque `kt.unresolved` ÉMIS, un journal
   `java.util.logging` niveau FINE (**jamais actif en production par défaut**, construction
   paresseuse : coût nul quand le niveau est filtré) rend : la forme syntaxique de la référence
   (nom nu, sélecteur qualifié, callee d'appel, premier segment qualifié), l'issue de la
   sélection par types complets (ADR 0223 — la « voie de preuve » qui a conclu à l'absence),
   le récepteur TYPÉ, et l'état de l'univers des preuves (scan projet complet, index portant
   des entrées, régime script). Rendu UNE ligne greppable `clé=valeur`.
   - Code : `diagnostics/ExplicationsNonResolus.kt` ; la cascade porteur+typage du récepteur de
     `selectionParTypes` est extraite telle quelle dans `typeDuRecepteurSiSelection`
     (SANS changement de comportement) pour être partagée avec le journal.
   - **La « commande qui rend le journal »** : `ExplicationNonResoluTest` (capture d'un
     `java.util.logging.Handler` sur le logger `…diagnostics.ExplicationsNonResolus`, niveau
     FINE). En production : brancher un handler FINE sur ce logger produit le même journal.
   - Exemple de ligne : `explain: kt.unresolved « champInconnu » 5:16 —
     forme=selecteur-qualifie-absence-prouvee ; selection=ABSENCE_PROUVEE ;
     recepteur=com.ex.app.Boite ; scan=complet ; index=portant ; script=non`.
2. **Corpus de reproduction (A.2)** — 8 fichiers de tests (29 cas) couvrant chaque hypothèse du
   prompt, chacun portant son CONTRÔLE (vrai négatif ou non-régression) — les faux positifs
   confirmés sont DOCUMENTÉS comme comportement actuel : le correctif de l'étape B INVERSERa
   leurs assertions, ils sont ses tests de reproduction.
3. **Différentiel du lecteur de classes (A.3)** — contre la réflexion JDK (classes chargées,
   jamais initialisées) sur **android.jar réel** (AOSP API 16, Maven Central — le SDK Android
   est absent des JVM de test) et le **kotlin-stdlib** du classpath de test.
4. **Mesures de performance** — reparse complet et diagnostics sur un gros fichier généré.

## 1. Verdicts par hypothèse du prompt (§2 du prompt, mesurés)

| # | Hypothèse | Verdict | Preuve (test) |
|---|---|---|---|
| 1 | Troncation à `BORN_FICHIERS = 2000` : univers incomplet, `estUniversConna()` ne le reflète pas | **CONFIRMÉE** (deux lacunes) | `ReproductionTroncationScanTest` |
| 2 | Sources générées (KSP/Hilt/Room) invisibles | **CONFIRMÉE** | `ReproductionSourcesGenereesTest` |
| 3 | Membres de classes Java du projet non modélisés | **PARTIELLE** (voir détail) | `ReproductionJavaImbriqueTest` |
| 4 | Garde ADR 0211 incomplète (segment partiel/périmé, jar ajouté) | **INFIRMÉE** (scénarios testés) | `ReproductionSegmentsPersistantsTest` |
| 5 | Écarts du lecteur de classes (membres default, `$`, `entries`…) | **Lecture brute : INFIRMÉE. Moteur : 2 confirmées** (voir §3) | `LecteurClassBrutDifferentielTest`, `AnalyseurClassFileDifferentielTest`, `ReproductionMembresClasspathTest`, `ReproductionAppelablesBuiltinTest` |
| 6 | `typealias` de bibliothèques Kotlin | **CONFIRMÉE au rejeu §4, ÉTEINTE (ADR 0226)** | `SondeTypealiasKotlinxTest`, `ReproductionTypealiasClasspathTest` |
| 7 | Un marqueur d'erreur du parseur coupe tout le sémantique | **CONFIRMÉE par lecture** — comportement VOULU (D3), pas un faux positif | `DiagnosticsSemantiques.kt:101` (KDoc) |
| 8 | `diagnostics(uri)` recalcule tout, sans cache, sync complète | **CONFIRMÉE + CHIFFRÉE** | `MesuresPerfDiagnosticsTest` |

### Détail des hypothèses confirmées

**H1 — troncation du scan (la cause à plus fort impact).** Deux lacunes mesurées : (a) la MARCHE
elle-même est bornée — le fichier au-delà de la borne n'est même pas COMPTÉ (`statsDernierPassage`
rend `total` = borne : la troncation est indétectable côté observateur ADR 0213) ; (b) le drapeau
`pret` est posé sur un passage tronqué (ADR 0212 : « un résultat partiel est un état abouti ») et
`estUniversConna()` répond VRAI. Conséquence : une référence à un type déclaré derrière la borne
produit un **faux `kt.unresolved`** — les voies 4/5 de `estResolu` ne sont pas gardées par la
complétude du scan (seule `selectionProjet` l'est, vague 3). La couture de test
(`ScanProjet(parseur, bornFichiers)`) reproduit la troncation sans semer 2 000 fichiers ; le
parcours étant DÉTERMINISTE (tri par nom), la cible sort toujours derrière la borne.

**H2 — sources générées.** Le scan exclut `build/` (ADR 0212) — exactement là où vivent les
sources générées. Le parent `Hilt_MainActivity` d'un `@AndroidEntryPoint` déclaré dans
`build/generated/ksp/...` est invisible → **faux `kt.unresolved`**. Les synthétiques D5
(R, BuildConfig, ViewBinding, DataBinding) ne couvrent PAS les conventions KSP/Hilt/Room.

**H3 — Java mixte (frontière réelle, mesurée).** Niveau 1 : couvert depuis la vague 2
(non-régression verrouillée). **Niveau 2 : RÉSOUT** — l'imbriquée est extraite comme DÉCLARATION
MEMBRE du conteneur (`PROPRIETE:Bac`, conteneur `com.ex.app.Aquarium`) : l'hypothèse « imbriquées
absentes » est infirmée à ce niveau ; le genre approximatif borne la complétion/navigation, pas le
diagnostic. **Niveau 3 : FAUX `kt.unresolved`** en position de TYPE (dernier segment d'un type
qualifié — E4) ; en position d'EXPRESSION, la garde sélecteur (récepteur non typable → INCONNU,
ADR 0223) couvre.

**H4 — segments persistants (infirmée).** Le format `.kidx` porte magie + version + empreinte
recoupée : un segment CORROMPU ou de VERSION PÉRIMÉE est rejeté puis re-décodé depuis le jar
(auto-guérison ADR 0111 §4) — l'index reste PORTANT (PI signalé, println muet). Un jar ABSENT rend
l'entrée ÉCARTÉE : le snapshot ne couvre plus les empreintes et la garde ADR 0211 rend l'index
MUET (aucun `kt.unresolved`, même le vrai négatif — contrat « index froid = silence », déjà
verrouillé par `SessionLangageKotlinA211Test`).

## 2. Différentiel du lecteur de classes (A.3) — résultats

**Structure** (`LecteurClassBrut`, `:lsp:kotlin`) : 1 007 classes `android.*` (android.jar) +
678 classes `kotlin.*` (stdlib) → **0 classe illisible, 0 écart de superclasse, 0 écart
d'interfaces**. Le correctif ADR 0219 (pool Long/Double) tient ; la seule normalisation est le
super des interfaces (`java.lang.Object` par contrat du fichier de classe, réflexion rendant
`getSuperclass()` null). *Écart au prompt : `LecteurClassBrut` ne lit PAS les membres (il les
saute — il sert la structure + `@Metadata`) ; le différentiel des membres vise donc le VRAI
lecteur de la vue.*

**Membres** (`AnalyseurClassFile`, `:lsp:index` — l'extension `java.members` qui nourrit la vue) :
1 007 + 681 classes → **0 illisible, 0 écart de champs, 0 écart de méthodes** (noms déclarés).
Normalisations du contrat : le lecteur lit les constructeurs (`<init>`) que
`getDeclaredMethods` ne rend pas ; l'agent de couverture Kover/IntelliJ injecte un champ
`__$hits$__` dans les classes CHARGÉES (javap : absent du class file) — artefact de la référence.
La famille V1 (membres perdus silencieusement) est éteinte sur ces corpus.

**Métadonnées** (`DecodeurMetadonnees` vs façades `*Kt` en réflexion) : 3 925 appelables lus ;
fonctions usuelles et extensions réputées présentes. La divergence par NOMS est structurelle
(propriétés top-level → champs statiques, typealias → aucun miroir bytecode, extensions → clé
récepteur) : les statistiques sont imprimées par le test pour la traçabilité, sans assertion.

## 3. Découvertes du différentiel (faux positifs supplémentaires, non listés par le prompt)

1. **`arrayOf` / `intArrayOf` manquent de l'index des appelables** → **faux `kt.unresolved`** en
   session réelle avec le VRAI stdlib (`val x = arrayOf(1, 2)`). Reproduit et documenté par
   `ReproductionAppelablesBuiltinTest` (contrôles : `listOf`, `emptyArray`, `println` muets).
   Correctif étape B : builtins réputées côté moteur (miroir `IMPORTS_DEFAUT`) ou décodage.
2. **`MonEnum.entries` (Kotlin 1.9+, `EnumEntries`)** : le getter synthétique `getEntries()` est
   STATIQUE et `getterSynthetique` n'accepte que les accesseurs NON statiques (KDoc
   `ResolutionKotlin.kt:322-325`) → l'absence est prouvée (ADR 0223) → **faux `kt.unresolved`**
   sur toute écriture `.entries` d'enum du classpath. Reproduit par
   `ReproductionMembresClasspathTest` (vue modélisant le bytecode réel d'un enum 1.9+ ; `ROUGE`,
   `values()`, `valueOf()` muets ; un membre réellement absent reste signalé). Correctif étape B :
   accepter le getter statique quand le récepteur est la CLASSE (miroir ADR 0222).
3. **`let`/`also`/`apply`/`run`/`takeIf`/`takeUnless`** (récepteur PARAMÈTRE DE TYPE) : indexées
   comme appelables TOP-LEVEL du paquet `kotlin` (`recepteurFqcn` rend null pour `T.`) — la voie
   6 + les imports PAR DÉFAUT les résout → **AUCUN faux positif** (mesuré, mécanisme VERROUILLÉ
   par `ReproductionExtensionsRecepteurNulTest`). L'écart ne dessert que la complétion
   membre-extension (famille du backlog « complétion des membres du récepteur de portée »).

## 4. Non concluants et angles morts assumés

- **`typealias` de bibliothèques — REJOUÉ en étape B, verdict CONCLUANT** (ADR 0226) : le
  corpus CITÉ par ce rapport (`kotlinx.serialization`) porte **ZÉRO typealias public**
  (1.9.0, sonde `SondeTypealiasKotlinxTest` — l'hypothèse n'y est pas éprouvable) ; le
  corpus du rejeu est **kotlinx-datetime 0.6.1** qui en porte TROIS (`DayOfWeek`, `Month`,
  `ZoneOffset`). **FP CONFIRMÉ sur deux formes d'usage** : le type QUALIFIÉ complet
  (`kotlinx.datetime.Month` — dernier segment faillible de la garde E4, le FQCN d'un
  alias ne vit JAMAIS dans la vue) et le type NU derrière un import JOKER
  (`import kotlinx.datetime.*` + `ZoneOffset`, alias pur SANS fonction usine
  expect/actual — `Month`/`DayOfWeek` n'étaient sauvés QUE par leurs usines
  `fun Month(int)` : un hasard du mécanisme expect class, pas une résolution d'alias).
  **Correctif livré** : décodeur → genre `ALIAS` dans une carte DÉDIÉE de l'index
  (segments v2), voie 6 avec le prédicat ADR 0203, gardes E4/E4-ter sur le FQCN
  complet. Verrouillé par `ReproductionTypealiasClasspathTest` (5 tests, corpus réel).
- **Interfaces `default` des jars** : le différentiel des MEMBRES les couvre implicitement
  (noms déclarés identiques à la réflexion, méthodes `default` comprises — elles sont des
  méthodes déclarées ordinaires du class file).
- **Corpus E2E par variables d'environnement** (`REPRO_COMPOSE_DIR`, `REPRO_VIEWS_DIR`) :
  indisponibles dans cet environnement (aucune dépendance CI, tests ignorés) — les découvertes
  ci-dessus le sont sur le stdlib RÉEL + android.jar, sans dépendance d'environnement.

## 5. Mesures de performance (entrée de décision de l'étape D)

Document généré déterministe de **1 803 lignes / 150 fonctions**, VRAI stdlib (index nourri),
JVM de test (2 cœurs CI) — `MesuresPerfDiagnosticsTest` (chiffres imprimés à chaque exécution) :

| Opération | À froid | À chaud (min de 5) |
|---|---|---|
| Reparse complet (`ouvrirDocument` / `modifierDocument`, sync Full) | 31 ms | 21 ms |
| Diagnostics complets (recalcul intégral, sans cache) | 469 ms | 147 ms |

Lecture : sur un gros fichier, le reparse intégral est négligeable (~20 ms) et les diagnostics
complets restent sous la demi-seconde à chaud. **L'étape D (diagnostics incrémentaux) n'a pas
d'urgence mesurée à cette échelle** ; la décision de la lancer devra reposer sur des fichiers
plus grands ou des corpus réels (≥ 3 000 lignes, sources générées) — recommandation : mesurer
encore avant d'investir.

## 6. Exemples réels de la section 1 du prompt

La section était vide au lancement ; le corpus s'est donc construit depuis les hypothèses et les
templates, comme le prompt le prévoit. Les anecdotes utilisables proviennent de
`docs/RETOURS-UTILISATEURS-2026-10-25.md` : tous les faux positifs cités y sont ÉTEINTS (les 4
contrats utilisateurs sont VIDES) — aucune reproduction supplémentaire n'en découle. Le backlog
priorisé §4 de ce document reste la feuille de route partagée (jar illisible → partiel +
avertissement ; validation d'attributs XML ; NUS des lambdas `tasks.register` + complétion DSL ;
complétion des membres du récepteur de portée ; typage des composants destructurés).

## 7. Recommandations pour l'étape B (par ordre d'impact mesuré)

1. **`estUniversConna` doit refléter la troncation** (H1) : la MARCHE doit compter AU-DELÀ de la
   borne (statistiques honnêtes : `total` réel, pas borné) et un état « univers tronqué » doit
   couper la preuve d'absence des voies 4/5 — miroir de la garde `scanProjetComplet` de
   `selectionProjet`, mais au niveau d'`estResolu`. Tests à inverser :
   `ReproductionTroncationScanTest`.
2. **Builtins de tableaux** (`arrayOf`, `intArrayOf`, vraisemblablement les autres
   `*ArrayOf` à récepteur vide) : réputation côté moteur ou correction du décodeur. Tests :
   `ReproductionAppelablesBuiltinTest`.
3. **`Enum.entries`** : accesseur synthétique STATIQUE accepté quand le récepteur est la classe
   (miroir ADR 0222). Tests : `ReproductionMembresClasspathTest`.
4. **Sources générées** (H2) : racines `build/generated` en lecture seule pour la résolution, ou
   silence sur les conventions de nommage générées (Hilt_, _Impl, BindingImpl). Tests :
   `ReproductionSourcesGenereesTest`.
5. **Imbriquées Java de niveau ≥ 3** (H3) : extraction récursive bornée dans `SourcesJava` (et,
   à l'occasion, le genre exact des imbriquées de niveau 2 pour la complétion). Tests :
   `ReproductionJavaImbriqueTest`.

Chaque correctif suivra le TDD de la maison : le test de reproduction existe DÉJÀ (assertions
inversées par le correctif), l'ADR **0225** est le premier numéro libre pour les décisions
structurantes.
