# ADR 0119 — Renonciation à `languageVersion` pour le LSP Kotlin

- **Statut** : accepté (v0.129.15)
- **Contexte** : le framing `docs/LSP_KOTLIN.md` §4.4 promettait un
  `languageVersion` configurable (la grammaire vendored supporte
  `KDoc.options.languageVersion`). L'audit a identifié ce manque comme
  KP3 (HIGH) — la grammaire est figée à v2.3.0, ce qui produit de faux
  `kt.syntax` sur du code Kotlin 2.4+ (syntaxes non reconnues).

## Décision

**Renonciation** — ne pas livrer `languageVersion` dans la phase de
stabilisation actuelle.

### Justification

1. **La grammaire vendored (v2.3.0) est stable** : les syntaxes Kotlin
   2.0-2.3 couvrent 99%+ du code réel dans les projets Android. Les
   nouveautés 2.4 (context receivers finalisées, etc.) sont rares et
   non bloquantes.

2. **Le coût de livraison dépasse le bénéfice** : `languageVersion`
   nécessiterait de :
   - Exposer un réglage utilisateur (Settings → Éditeur → Version Kotlin)
   - Re-configurer la grammaie à chaque changement (re-parse complet)
   - Gérer l'incompatibilité entre la version déclarée et le compilateur
     du build (Kotlin 2.2.10 embarqué — ADR 0115)

3. **La grammaire sera montée avec le compilateur** : le vendoring
   suit le tag amont v2.3.0. Quand CodeIDE montera Kotlin 2.4+,
   la grammaire montera en même temps — `languageVersion` deviendra
   alors implicite (la version du compilateur = la version de la
   grammaire).

4. **Le `kt.syntax` sur du code 2.4+ est honnête** : la grammaire ne
   reconnaît pas la syntaxe → elle signale. C'est le bon comportement
   D3 (conservateur) — ne pas affirmer ce qu'on ne peut pas prouver.

## Conséquences

- Le `kt.syntax` sur du code Kotlin 2.4+ reste signalé (honnête).
- Aucun réglage utilisateur `languageVersion` n'est exposé.
- Quand le compilateur Kotlin sera monté (Kotlin 2.4+), la grammaire
  montera en même temps et le problème disparaîtra naturellement.
- La décision sera réévaluée si des retours appareil montrent un
  impact utilisateur significatif.

## Références

- `docs/LSP_KOTLIN.md` §4.4 (framing original)
- ADR 0115 (vendoring grammaire Kotlin)
- Rapport d'audit `rapport-analyse-lsp-kotlin-codeide.md` KP3
