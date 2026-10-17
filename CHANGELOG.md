# Changelog — codeide-lsp

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
