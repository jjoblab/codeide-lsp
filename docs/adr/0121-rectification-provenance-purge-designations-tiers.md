# ADR 0121 — Rectification de provenance : purge des désignations du projet tiers de référence

- **Statut** : accepté (19/10/2026, décision directe du propriétaire,
  exécutée lors de la stabilisation post-extraction codeide-lsp).
- **Contexte** : l'ADR 0110 a documenté l'audit de provenance du module
  `lsp:java` et la décision du propriétaire (16/10/2026) : réécriture
  ciblée du niveau A (les trois shims `compat/`, exécutée v0.94.0
  depuis la spécification publique du JDK — javadoc + JEP 322 — sans
  lecture du code tiers), conservation du niveau B sur l'attestation
  de paternité du propriétaire (« la quasi-totalité sont mes fichiers »,
  consultation du projet de référence « pas au mot près »). La
  séparation du dépôt (2026-10-17) et la publication GitHub Packages
  (v1.0.0+) ont rendu le statut « vendored privé » obsolète, tandis que
  les désignations du projet tiers de référence subsistaient dans
  ~290 sites : commentaires de code (portage, motifs, parités),
  documentation (ADRs, LSP_JAVA.md, LSP_KOTLIN.md, LSP_XML.md, plans),
  build-logic et messages de commits. Ces désignations décrivent
  désormais faussement la généalogie du code : le niveau B est le
  travail original du propriétaire et le niveau A est une réécriture
  depuis la spécification publique.

## Décision

Le propriétaire tranche : **rectification de l'attribution et purge
complète des désignations** du projet tiers de référence.

1. **Attestation reconfirmée (19/10/2026)** : les fichiers de niveau B
   sont le travail original du propriétaire ; les mentions du projet
   tiers dans les commentaires étaient des notes de recherche obsolètes,
   pas des titres de propriété.
2. **Purge de l'arbre courant** : remplacement systématique par la
   désignation neutre « projet tiers de référence » (76 fichiers,
   290 occurrences) ; les URL et identifiants du projet tiers sont
   retirés ; les notes de gouvernance (ADRs) restent en place mais
   anonymisées — la trace de la décision de provenance est préservée
   (ADR 0110 « Clôture », présent ADR).
3. **Purge de l'historique** : réécriture des blobs et des messages de
   commits par `git filter-repo` (`--replace-text` +
   `--replace-message`), tags v1.0.x réécrits et forcés. Les
   coordonnées des commits (auteurs, dates) sont inchangées.
4. **Licence du dépôt** : Apache-2.0 à la racine (cohérent avec
   l'écosystème `cel-*` et le vendoring grammaire Kotlin) ;
   `docs/THIRD_PARTY_NOTICES.md` continue de recenser les dépendances
   tierces réelles (ECJ/EPL-2.0, grammaire Kotlin/Apache-2.0, lsp4j).

## Options écartées

- **Conserver les désignations** : contredit l'attestation de paternité
  et décrit faussement la généalogie du code publié.
- **Réécriture du niveau B** : contredit la décision du propriétaire
  (16/10/2026) — pas de lot G complet.
- **Suppression des ADRs de provenance** : priverait le dépôt de sa
  trace de gouvernance ; l'anonymisation est préférable.

## Conséquences

- Positives : généalogie du code exacte ; publication sans équivoque ;
  recherche de code (« projet de référence ») ne remonte plus de faux positifs
  documentaires ; dette D3 clôturée.
- Négatives : réécriture d'historique — les clones locaux antérieurs
  doivent être re-clonés ; les artefacts GitHub Packages déjà publiés
  proviennent des tags d'origine (les coordonnées binaires sont
  inchangées, seule la provenance source bouge).
- Neutres : les citations de classes historiques (`Jdt*`) dans les
  commentaires désignent l'architecture propre du module (préfixe
  renommé `Ecj*` en v0.95.0).

## Références

- ADR 0110 (provenance, décision 16/10/2026, clôture 19/10/2026)
- `docs/LSP_JAVA.md` §5 (méthode d'audit), §7 (renommage), §18.2 (PV)
- Commits : v0.94.0 (réécriture shims), v0.95.0 (renommage `Jdt* → Ecj*`)
