# Changelog — codeide-lsp

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
