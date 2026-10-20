# Changelog — codeide-lsp

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
