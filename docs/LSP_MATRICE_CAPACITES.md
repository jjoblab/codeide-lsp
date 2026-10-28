# Matrice des capacités LSP — Java, Kotlin, XML

> Livrable du lot L0 du plan 1.3.0. Une capacité est **en service** seulement si les six
> critères de la définition (ADR 0201) sont vrais : (1) annoncée dans `ServerCapabilities`
> à l'`initialize`, (2) implémentée honnêtement (pas de stub), (3) câblée dans l'hôte
> (alimentée par la session moteur), (4) testée de bout en bout par le transport réel,
> (5) consommée par le client (`cel-lsp` de code-editor), (6) réglable si pertinent
> (table `CapacitesAnnoncees`, parité verrouillée par test).
>
> Légende des cases : ✅ = les six critères vrais (le 5 cité renvoie à
> docs/LSP_ECARTS_CLIENT.md) ; ⚠️ = servie côté serveur, **hors service côté éditeur**
> (critère 5 faux — le client n'émet pas la requête) ; ✝️ = annoncée mais conservatrice
> par conception (le sous-rapport D3 est documenté) ; ❌ = non annoncée (case vide dans
> le protocole). La colonne « preuve » cite le test qui verrouille la case (test de
> parité W4 pour l'annonce, test moteur/session pour l'implémentation, test E2E du
> serveur pour le transport réel).
>
> État au 21/10/2026 (lots L2 et L3 livrés ; colonnes auditées par le test de parité
> L0 — la colonne Java sera affinée par le lot L4, ses preuves E2E case par case).

## Synchronisation et diagnostics

| Capacité | Java | Kotlin | XML | Preuves |
|---|---|---|---|---|
| Synchronisation de document (didOpen/Change/Save/Close) | ✅ incrémentale | ✅ complète | ✅ complète | CapacitesLspPariteTest (tables) ; *LanguageServerTest par langage |
| Diagnostics poussés (publishDiagnostics) | ✅ | ✅ | ✅ | KotlinLanguageServerTest, XmlLanguageServerTest, JavaS1RicheTest |
| didChangeWatchedFiles (événements de fichiers externes) | ✅ | ✅ | ✅ (lot L1) | SurveillanceFichiersLangagesTest |

## Navigation

| Capacité | Java | Kotlin | XML | Preuves |
|---|---|---|---|---|
| Définition (`textDocument/definition`) | ✅ | ✅ | ✅ | JdtHoverDefinitionCompletionTest ; SessionLangageKotlin*Test, XmlLanguageServerTest |
| Définition de type (`typeDocument/typeDefinition`) | ✅ | ✅ (lot L2) | ❌ | TypesKotlinTest (moteur) ; KotlinLanguageServerL2Test (E2E) |
| Implémentations (`textDocument/implementation`) | ✅ | ✅ (lot L2) | ❌ | TypesKotlinTest (moteur, preuve FQCN) ; KotlinLanguageServerL2Test (E2E) |
| Références (`textDocument/references`) | ✅ | ✅ | ❌ (lot L3 : prévu) | SessionLangageKotlinOccurrencesTest |
| Surlignage d'occurrences (`documentHighlight`) | ✅ | ✅ | ❌ (lot L3 : prévu) | SessionLangageKotlinOccurrencesTest |
| Renommage + prepareRename | ✅ | ✅ | ✅ | RenameHonneteTest, JavaL4BoutEnBoutTest (E2E) ; SessionLangageKotlinOccurrencesTest, XmlLanguageServerTest |
| Symboles de document (`documentSymbol`) | ✅ | ✅ | ✅ | JavaL4BoutEnBoutTest (E2E) ; KotlinLanguageServerTest, XmlLanguageServerTest |
| Symboles d'espace (`workspace/symbol`) | ✅ | ✅ (lot L2) ⚠️ | ❌ | SymbolesEspace via CapacitesL2KotlinTest ; KotlinLanguageServerL2Test (E2E) |
| Plage de sélection (`selectionRange`) | ✅ | ✅ (lot L2) ⚠️ | ❌ (lot L3 : prévu) | CapacitesL2KotlinTest ; KotlinLanguageServerL2Test (E2E) |

## Édition assistée

| Capacité | Java | Kotlin | XML | Preuves |
|---|---|---|---|---|
| Complétion (`completion`) | ✅ | ✅ (contextuelle vague 1 : arguments nommés, enum de `when`, attributs d'annotation — ADR 0228) | ✅ | CompletionKotlin + ContextesCompletionKotlin + tests flux client XML/Java |
| Survol (`hover`) | ✅ | ✅ | ✅ | InterrogationsSymboles (tests moteur), XmlLanguageServerTest |
| Aide à la signature (`signatureHelp`) | ✅ | ✅ | ❌ | SessionLangageKotlinSignatureTest |
| Actions de code (`codeAction`) | ✅ | ✅ (imports + implémenter les membres manquants, ADR 0229) | ✅ | KotlinLanguageServerTest (quick-fixes), XmlLanguageServerTest ; MembresManquantsTest (moteur) |
| Formatage (`formatting`) | ✅ | ✅ (lot L2, ADR 0202 — ré-indentation seule) | ✅ | EcjFormatterTest, JavaL4BoutEnBoutTest (E2E) ; FormateurKotlinTest ; KotlinLanguageServerL2Test ; XmlLanguageServerTest |
| Formatage de plage (`rangeFormatting`) | ✅ | ⚠️ (servi ; consommation client prévue 3.74.0) | ✅ (lot L3) ⚠️ | FormatagePlageSelectionXmlTest ; XmlLanguageServerL3Test (E2E) ; KotlinLanguageServerL2Test |
| Formatage à la frappe (`onTypeFormatting`) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 (annonces) |
| Édition liée (`linkedEditingRange`) | ✅ | ❌ | ✅ (lot L3) ⚠️ | FormatagePlageSelectionXmlTest ; XmlLanguageServerL3Test (E2E) |
| Renommage préparé : voir Navigation | — | — | — | — |

## Rendu riche

| Capacité | Java | Kotlin | XML | Preuves |
|---|---|---|---|---|
| Pliage (`foldingRange`) | ✅ | ✅ | ✅ | PlisDocumentKotlin, XmlLanguageServerTest |
| Indices en ligne (`inlayHint`) | ✅ ⚠️ | ✅ (lot L2) ⚠️ | ❌ | IndicesKotlin via CapacitesL2KotlinTest ; KotlinLanguageServerL2Test (E2E) |
| Jetons sémantiques (`semanticTokens`) | ✅ | ❌ (NON annoncée — distinction non fiable au niveau parseur, D3 ; dette consignée) | ❌ | Table de parité W4 (absence verrouillée) |
| Lentilles de code (`codeLens`) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 |
| Hiérarchie d'appels (`callHierarchy`) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 |
| Hiérarchie de types (`typeHierarchy`) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 |
| Couleurs de document (`documentColor`) | ✅ ⚠️ | ❌ | ✅ (lot L3) ⚠️ | CouleursDocumentXmlTest ; XmlLanguageServerL3Test (E2E) |

## Méthodes expérimentales (extensions propres)

| Capacité | Java | Kotlin | XML | Preuves |
|---|---|---|---|---|
| Super-définition (expérimental) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 |
| Source décompilée (expérimental) | ✅ ⚠️ | ❌ | ❌ | Table de parité W4 |

## Capacités réglables (critère 6)

| Interrupteur (`CapacitesAnnoncees`) | Java | Kotlin | XML | Preuve de parité |
|---|---|---|---|---|
| COMPLETION, SURVOL, DIAGNOSTICS, SIGNATURES, DEFINITION, REFERENCES, RENOMMAGE, ACTIONS_RAPIDES | ✅ | ✅ | ✅ (SIGNATURES/REFERENCES exclues XML) | SessionLspReglagesTest + CapacitesLspPariteTest |
| FORMATAGE | ✅ | ✅ (lot L2) | ✅ | SessionLspReglagesTest |
| INDICES_EN_LIGNE | ✅ | ✅ (lot L2) | ❌ | SessionLspReglagesTest |

## Dettes et écarts consignés

- **Inventaire L5 (chaque case non vide ↔ preuve E2E)** : les capacités annoncées des
  trois serveurs ont toutes un aller-retour LSP4J réel — Java (JavaL4BoutEnBoutTest,
  JavaS2bProtocoleTest, JavaS1RicheTest, JavaS2ConfortTest, LifecycleHygieneTest,
  RenameHonneteTest, InlayHintTest), Kotlin (KotlinLanguageServerL2Test + suites
  K3/K4/K5/Occurrences/Signature/Parse), XML (XmlLanguageServerL3Test + XmlLanguageServerTest).
  Dettes E2E résiduelles (capacités Java couvertes par les tests MOTEUR mais sans
  aller-retour serveur dédié dans les suites actuelles, preuve moteur citée) :
  typeDefinition/implementation côté serveur (NavigationFacadeTest, moteur), survol
  (EcjHoverJavadocTest, moteur), complétion (JdtTemplatesTest/Ecj*, moteur ; filtre
  filterText couvert S2b), signatureHelp (JdtInlayAndSignatureTest, moteur), inlayHint
  (InlayHintTest, moteur), pliage (FoldingRangeFacadeTest, moteur), jetons sémantiques
  (LifecycleHygieneTest E2E ✓), symboles d'espace (JavaS1RicheTest E2E ✓),
  formatage de plage (JavaS2ConfortTest E2E ✓), formatage à la frappe (S2b E2E ✓),
  lentilles (S2b E2E ✓), hiérarchies appels/types (S2b E2E ✓).

- **Jetons sémantiques Kotlin** : non annoncés — la distinction types/fonctions/propriétés
  n'est pas fiable au niveau du parseur vendoré sans résolution complète (D3 : mieux vaut
  ne rien proposer qu'un faux positif). Dette consignée, à rouvrir si la résolution
  gagne en preuve.
- **Formatage à la frappe, lentilles, hiérarchies, couleurs (Java)** : annoncées et
  servies côté Java mais **jamais consommées** par le client actuel — voir
  docs/LSP_ECARTS_CLIENT.md (lot L6).
- **Symboles d'espace / plage de sélection Kotlin** : servis côté serveur dès le lot L2 ;
  la consommation par le client (palette, expansion de sélection) est un écart consigné
  (lot L6).
- **XML (lot L3 livré)** : références/surlignage (même preuve que le renommage borné),
  formatage de plage, édition liée, plage de sélection et couleurs de document sont
  servis et annoncés — preuves moteur + E2E citées. La consommation client de
  l'édition liée, de la sélection et des couleurs reste un écart (lot L6).
