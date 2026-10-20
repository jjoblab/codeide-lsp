# Plan de stabilisation LSP — Java / Kotlin / XML

> **Document de planification** (v0.129.2, 14/10/2026). Synthèse des trois
> rapports d'analyse LSP (`rapport-analyse-lsp-java-codeide.md`,
> `rapport-analyse-lsp-kotlin-codeide.md`,
> `rapport-analyse-lsp-xml-codeide.md`). Audit conduit sur v0.124.7
> (commit `bd6f458`).
>
> Chaque module a été audité avec la même méthodologie : lecture du
> framing doc, lecture du code, tests, comparaison avec les LSP de
> référence (JDT.LS / fwcd+JetBrains / LemMinX), et sonde SPI empirique
> (harness Java hors dépôt驱动ant le moteur via son SPI public).

## 1. État global

| Module | Santé architecturale | Tests | Bloqueur critique | Faux positifs prouvés |
|---|---|---|---|---|
| **XML** | ✅ La plus disciplinée (zéro dépendance, parser tolérant, X4 rigoureux) | 188 module + 17 host, verts | XP1/XP2/XP3 (faux positifs sur références) | Oui (probe B/D/E) |
| **Kotlin** | ✅ Saine (grammaire officielle vendored, D3 conservateur) | 118 tests, 1 échec (baseline) | KP1 (baseline rouge) + KP8 (faux `kt.unresolved`) | Oui (`ArrayList`, `StringBuilder`) |
| **Java** | ✅ Riche (ecj vendored, index disque, completion, diagnostics) | 498 tests, 0 échec, 76 ignorés | D3 — clôturé (ADR 0110/0200) | Non (niveau A réécrit, niveau B attesté) |

## 2. Synthèse transversale — problèmes communs

| Problème | Java | Kotlin | XML | Effort |
|---|---|---|---|---|
| `didSave` no-op | I5 | KP9 | XP7 | ~1h chacun |
| `didChangeWatchedFiles` muet | I4 | KP10 | XP8 | 1-2j |
| Conversions offset↔position O(n) sans cache | I11 | KP13 | XP15 | 2h |
| Hover `MarkedString` déprécié | — | KP14 | — | 30min |
| Diagnostics sans `tags`/`relatedInformation` | — | KP15 | implicite | 2h |
| Pas de négociation `capabilities` | I1 | — | XP9 | 1h |
| `serverInfo.version` incorrect | I7 | KP4 (0.113.0) | OK (0.121.0) | 5min |

## 3. Plan par module

### 3.1 LSP Java — Plan S0 → S3

#### S0 — Stabiliser (2-3 lots)
- **S0-a** : hygiène protocole (lifecycle guard centralisé, `prepareRename`, version injectée, convention null/empty uniforme)
- **S0-b** : `prepareRename` honnête (range + placeholder, error typée, `documentChanges` versionné)
- **S0-c** : **décision D3** — CLÔTURÉE (niveau A réécrit v0.94.0, niveau B attesté, ADR 0110/0200) ; protocole appareil §11.2 toujours applicable

#### S1 — Parité utile avec JDT.LS (3-4 lots)
- Conformité par-projet (`sourceCompatibility`/`targetCompatibility` dans `ClasspathModule`)
- Diagnostics riches (tags `Unnecessary`/`Deprecated`, `relatedInformation`)
- `workspace/symbol`
- Watched-files côté hôte

#### S2 — Confort structurel — ✅ LIVRÉ (audit 19/10 : câblé dans
`JavaTextDocumentService` — rangeFormatting, selectionRange,
onTypeFormatting, codeLens, inlayHint, semanticTokens, highlight,
references, rename, folding ; les hiérarchies call/type restent le
seul item S2 ouvert, coût élevé côté ECJ)
- `rangeFormatting`, `selectionRange`, `onTypeFormatting`, `codeLens`
- Hiérarchies call/type (restant — deferred)
- Hover javadoc, `filterText`

#### S3 — Structurel/deferred
- Lot G (rewrite D3 — multi-session, déclenché par S0-c)
- ADR 0117 phase B
- Socket transport (ADR 0109 plan B)
- Extraction bibliothèque (après D3)

**Bloqueur critique — CLÔTURÉ (19/10/2026, ADR 0110/0200)** : D3/projet
de référence GPL — niveau A réécrit depuis la spécification publique du
JDK (v0.94.0), niveau B attesté travail original par le propriétaire,
désignations purgées du code, de la documentation et de l'historique.
La publication est autorisée (GitHub Packages depuis v1.0.0).

### 3.2 LSP Kotlin — Plan S0 → S3

#### S0 — Incident + processus + faux positifs (~2 jours)
1. **Régénérer baseline** (`-Dparite.majBase=true`) — 10 min
2. **KP8 default imports** : wildcards implicites des packages default-imported dans `ContexteNom` + extension `IMPORTS_DEFAUT` + filtre `CandidatsImport` — 1 jour
3. Bumper `version.properties` — 5 min
4. **Mettre gate sur CI** : `:lsp:kotlin:test` sur tout commit touchant `.kt`
5. **KP2** : callback `onClasspathApplied` republication documents ouverts — ½-1 jour
6. KP6 (sync `ScanProjet`), KP11 (`isIncomplete`), KP12 (trigger `?`)

#### S1 — Requêtes haute valeur + hygiène (2-3 jours)
- `textDocument/documentSymbol` (depuis `SymbolesProjet.declarations`)
- `textDocument/signatureHelp` (réutilisation extraction paramètres)
- `didSave` republication, tags `Unnecessary`, `relatedInformation`
- KP3 (`languageVersion` OU ADR renonciation), KP5 (fallback sans session Java)

#### S2 — Confort (1-2 semaines) — ✅ LIVRÉ (audit 19/10 : tout est
dans l'historique et câblé hôte — `KotlinLanguageServer`)
- `references` + `documentHighlight` via index inverse (OccurrencesKotlin)
- `rename` borné (miroir Java "honest rename" — plageRenommable/renommage)
- Hover riche (**KDocParser vendored consommé** — DocumentationKotlin)
- `foldingRange` depuis arbre immuable (PlisDocumentKotlin)

#### S3 — Deferred
- T2 K2 compilation par pack
- Re-parse incrémental sous-arbre
- Sources-jars/décompilé pour classpath

**Bloqueurs critiques** :
- KP1 : baseline rouge (933 vs 931) — 10 min à corriger
- KP8 : faux `kt.unresolved` sur `ArrayList`/`StringBuilder` — casse D3

### 3.3 LSP XML — Plan S0 → S3

#### S0 — Éteindre faux positifs (~2 jours)
1. **XP2** : filtre namespace dans `ReglesReferences` — ignorer `tools:*`/`xmlns:*` — TDD
2. **XP1** : dégrader `xml.referenceNonResolue` en WARNING tant que deps non indexées
3. **XP3** : re-scan au `didSave` (re-push classpath idempotent)
4. **XP6** : hover dumb-mode → `null` au lieu de "framework android" (L186-187)
5. Robustesse : GenreDocument AAR (XP13), `plageFermante` (XP14)

#### S1 — Vrai catalogue tags + palier LSP (~4 jours)
1. **XP4** : enrichir catalogue depuis `attrs.xml` (598 styleables — LinearLayout/FrameLayout/ListView manquants)
2. **XP5** : contributeur "vues projet" dans `CompletionXml.balises`
3. `documentSymbol` (DOM → DocumentSymbols hiérarchiques)
4. `didChangeWatchedFiles` (re-push classpath sur événements `res/`)

#### S2 — Confort & périmètre (~5 jours) — ✅ LIVRÉ (audit 19/10)
- `foldingRanges` (ranges déjà dans DOM — PlisDocumentXml, XP11)
- Cache ligne partagé + LRU pour définition (XP15 — ConvertisseurPositions)
- Complétion valeurs littérales (XP16 — CompletionValeursXml)
- Genres étendus `res/color`, `res/xml`, `res/navigation` (XP12)

#### S3 — Fidélité Android Studio (~2-3 semaines)
- **Index ressources de dépendances (AAR)** ✅ **LIVRÉ v0.134.20** (XE6) : archive listing + parse à la volée, fusion dans `IndexRessources` (cartes projet/lib séparées, projet prime) ; `xml.referenceNonResolue` ERREUR légitime (ADR 0120) ; complétion styles/couleurs Material
- **Rename borné des entrées values** — ✅ LIVRÉ 19/10 (lot 1.1.0 :
  RenommageXml — déclarations index + références des ouverts, miroir
  « rename honnête » Java/Kotlin)
- **Formateur XML** — ✅ LIVRÉ 19/10 (lot 1.1.0 : FormatXml —
  ré-indentation canonique bornée, contenu jamais réécrit)

**Bloqueurs critiques** :
- XP1 : faux positifs sur références de dépendances (Material/AppCompat/AndroidX)
- XP2 : `tools:` attributes sondés comme références réelles
- XP4 : catalogue de 37 vues obsolète (pas de LinearLayout !)

## 4. Priorisation consolidée

| Priorité | Module | Travail | Effort | Impact |
|---|---|---|---|---|
| **P0 — Immédiat** | Kotlin | Régénérer baseline (KP1) | 10 min | Débloque CI |
| **P0 — Immédiat** | Kotlin | Corriger faux positifs default imports (KP8) | 1 jour | Restaure D3 |
| **P0 — Immédiat** | XML | Filtre `tools:` + downgrade XP1 + hover dumb-mode | ½ jour | Plus de faux positifs |
| **P1 — Court terme** | Kotlin | Republication classpath (KP2) + `didSave` | 1 jour | Diagnostics à jour |
| **P1 — Court terme** | XML | Enrichir catalogue tags (XP4) | 2 jours | Completion utile |
| **P1 — Court terme** | Java | `prepareRename` (S0-b) | ½ jour | Rename non silencieux |
| **P2 — Moyen terme** | Tous | `documentSymbol` (Kotlin S1, XML S1) | 2 jours | Navigation structure |
| **P2 — Moyen terme** | Tous | `didChangeWatchedFiles` (commun) | 1-2 jours | Refresh auto |
| **P2 — Moyen terme** | XML | Vues projet en completion (XP5) | 1 jour | Completion riche |
| **P3 — Long terme** | Java | Décision D3 + lot G — ✅ clôturés (ADR 0110/0200) | — | Publication autorisée |
| **P3 — Long terme** | XML | Index ressources AAR (S3) | ✅ Livré v0.134.20 | Fidélité AS |
| **P4 — Confort** | XML | Rename borné values + formateur | ✅ Livré 19/10 (1.1.0) | Geste de renommage + format |
| **P4 — Confort** | Tous | Audit S2 : Kotlin/Java/XML câblés | ✅ Confirmé 19/10 | — |

## 5. Références

- `docs/LSP_JAVA.md` (1 806 lignes, framing W1-W10)
- `docs/LSP_KOTLIN.md` (framing K1-K5, D1-D8)
- `docs/LSP_XML.md` (155 lignes, framing XE1-XE5)
- ADR : 0108 (Java), 0109 (in-process), 0110 (provenance), 0111 (index segments), 0112 (android.jar), 0113 (classpath v2), 0114 (session), 0115 (Kotlin vendoring)
- Rapports originaux : `rapport-analyse-lsp-java-codeide.md`, `rapport-analyse-lsp-kotlin-codeide.md`, `rapport-analyse-lsp-xml-codeide.md`
