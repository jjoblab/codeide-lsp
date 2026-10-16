# ADR 0120 — Gravité de `xml.referenceNonResolue` : retour à l'ERREUR après l'index AAR

- **Statut** : accepté (v0.134.20)
- **Contexte** : la décision D-X1 de l'audit XML
  (`rapport-analyse-lsp-xml-codeide.md` §10) réservait le retour de
  `xml.referenceNonResolue` à l'ERREUR au terme du chantier S3/P3
  « index des ressources de dépendances ». Tant que les AAR n'étaient
  pas indexés, une référence `@style/Theme.Material3.DayNight` ou
  `@string/material_app_name` (ressources Material/AppCompat/AndroidX
  vivant dans les `res/` des dépendances) était un FAUX positif — la
  règle avait été rétrogradée en AVERTISSEMENT (lot XML S0, XP1) pour
  protéger la confiance utilisateur.

## Décision

Avec l'index AAR livré (lot P3, v0.134.20), `xml.referenceNonResolue`
**remonte à ERREUR** — sauf doute.

### Justification

1. **Les trois sources de vérité sont couvertes** : l'index PROJET
   (racines sources, XE3), l'index BIBLIOTHÈQUES (les `res/` des AAR du
   classpath — listage + parse à la volée, budget chemin+mtime) et le
   catalogue FRAMEWORK (public-final.xml). Une référence résolue nulle
   est désormais un VRAI positif : aapt la rejetterait au build.

2. **La garde dumb mode reste intacte** : index froid (aucune racine
   scannée) → SILENCE TOTAL. L'ERREUR ne s'affiche qu'une fois le
   classpath poussé et les trois sources consultées.

3. **Le doute X4 reste honnête** : si au moins UN AAR de la dernière
   passe est illisible (conteneur corrompu), l'index pourrait manquer —
   la gravité redescend en AVERTISSEMENT tant que le doute dure
   (`IndexRessources.douteux`, le doute persiste tant que l'AAR ne
   change pas). Un ERROR sous doute serait peut-être un faux positif ;
   un AVERTISSEMENT sous doute est un sous-signal acceptable.

4. **Le message suit la couverture** : « référence « type/nom »
   introuvable (projet, bibliothèques et framework) » — l'utilisateur
   sait d'où vient le verdict.

## Conséquences

- Les faux positifs Material/AppCompat/AndroidX disparaissent (la
  racine XP1) ; les références réellement cassées redeviennent
  bloquantes visuellement (ERREUR).
- `ReglesReferences` consulte `IndexRessources.douteux` — nouveau
  contrat interne du module (aucune fuite vers l'API neutre).
- Le message de diagnostic change (« projet, bibliothèques et
  framework ») — assumé ici et dans le CHANGELOG.
- Les définitions restent PROJET seul (D3 conservateur : jamais d'URI
  factice vers un conteneur AAR) — le survol porte le détail
  « bibliothèque, <nom d'AAR> ».

## Références

- `docs/LSP_XML.md` §5 XE6 (livraison P3)
- `docs/audit/rapport-analyse-lsp-xml-codeide.md` §10 S3, décision D-X1
- `docs/PLAN_STABILISATION_LSP.md` §3.3 (S3 XML)
