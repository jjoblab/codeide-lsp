# ADR 0201 — Définition de « capacité en service » et matrice des capacités

- **Statut** : accepté (21/10/2026, lot L0 du plan 1.3.0).
- **Contexte** : le plan 1.3.0 demande de COMPLÉTER les fonctionnalités des serveurs
  Kotlin et XML et de PROUVER que chaque capacité annoncée est réellement en service. Les
  constats d'audit (W1-W4) ont montré des annonces sans preuve possible : un serveur peut
  annoncer une capacité dont le gestionnaire est un stub, ou cesser d'annoncer une
  capacité servie sans que personne ne le voie (la parité ne verrouillait que les dix
  capacités RÉGLABLES). Le propriétaire exige le principe « rien d'annoncé qui ne soit
  servi, rien de servi qui ne soit annoncé » — il faut une définition opérationnelle,
  vérifiable, de « en service ».
- **Décision** : une capacité LSP d'un serveur du dépôt est dite **EN SERVICE** si et
  seulement si les SIX critères suivants sont vrais :
  1. **annoncée** : posée dans les `ServerCapabilities` de `initialize`, et seulement si
     elle est servie — un serveur n'annonce jamais ce qu'il ne sait pas faire ;
  2. **implémentée** : le gestionnaire fait un vrai travail (jamais un stub qui renvoie
     toujours vide) et respecte le contrat d'honnêteté D3 (un cas non compris rend un
     résultat VIDE, jamais un faux positif) ;
  3. **câblée** : alimentée par la session du moteur (modèle, index, classpath, événements
     de fichiers), pas orpheline ;
  4. **testée de bout en bout** : par le MÊME transport que la production (aller-retour
     LSP4J réel : initialize → didOpen → requête → vérification → didClose), pas
     seulement par appel direct du moteur ;
  5. **consommée** : émise par le client (`cel-lsp` de code-editor) — sinon la capacité
     est « branchée côté serveur, HORS SERVICE côté éditeur », un écart consigné, pas un
     mensonge d'annonce ;
  6. **réglable si pertinent** : si l'utilisateur peut la couper, elle figure dans la
     table `CapacitesAnnoncees` (énumération `CapaciteLsp`), miroir du `core:domain` de
     CodeIDE, parité verrouillée par test.
- **Mise en œuvre** : la preuve de la définition est la **matrice des capacités**
  (docs/LSP_MATRICE_CAPACITES.md) — une ligne par capacité LSP, une colonne par serveur
  (Java, Kotlin, XML), chaque case citant ses preuves (test de parité pour l'annonce,
  tests moteur/session pour l'implémentation, tests E2E du serveur pour le transport
  réel). Le garde-fou automatique est le test de parité ÉTENDU (W4,
  `CapacitesLspPariteTest`) : il compare les `ServerCapabilities` RÉELLES de chaque
  `initialize` à une table COMPLÈTE (pas seulement les réglables) et signale toute
  capacité annoncée dont le gestionnaire serait un stub — ajouter ou retirer une
  capacité sans mettre la table à jour fait échouer le test. Les écarts du critère 5
  (capacités servies mais non consommées) sont consignés dans
  docs/LSP_ECARTS_CLIENT.md (lot L6), jamais résorbés en dé-annonçant : le serveur est
  prêt, le client suit.
- **Conséquences** : chaque case de la matrice est AUDITABLE (le test cité existe et
  passe) ; une capacité peut être « en service côté serveur » sans être « en service
  côté éditeur » (critère 5 faux) — la matrice distingue les deux par une marque
  dédiée ; l'absence d'annonce est une DÉCISION (le cas des jetons sémantiques Kotlin,
  distinction non fiable — D3), pas un oubli : la table de parité verrouille l'absence
  au même titre que la présence ; l'ajout d'une entrée à `CapaciteLsp` exige la mise à
  jour du miroir `core:domain` de CodeIDE (la parité le détecte).
