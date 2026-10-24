# ADR 0207 — Rendre la KDoc aux sites d'usage du survol

- **Statut** : appliqué (24/10/2026 — audit E2E du serveur LSP Kotlin, découverte D3, scénario S4 ; mise en œuvre v1.6.0 : correctif moteur + tests, sondes S4 du harnais verrouillées sur le comportement corrigé au fil du transport réel).
- **Contexte** : la chaîne de survol n'attache la KDoc que par `survolNomDeclare` — quand
  le curseur est **sur le nom déclaré**. Un usage, même dans le **document ouvert**,
  passe par `survolDeclarationProjet`, qui rend la signature seule : l'audit a caractérisé
  (S4) MARKDOWN+KDoc sur la déclaration contre PLAINTEXT sur l'appel, dans le même
  fichier ouvert. Le commentaire du code documente le choix pour les déclarations du
  SCAN (« re-parser un fichier tiers pour son doc n'est pas un coût K3 ») — raison
  valable — mais elle ne s'applique pas au document COURANT, dont l'arbre est déjà vif en
  mémoire. Tous les serveurs de référence (fwcd, jdt.ls, LSP JetBrains) montrent la
  documentation aux sites d'usage : c'est l'attente première du survol d'appel (lire la
  doc sans sauter à la déclaration). D1 du cadrage (LSP_KOTLIN §2) montre que la valeur
  d'un éditeur tient au confort de consultation, pas seulement à la navigation.
- **Options considérées** :
  1. **KDoc du document courant aux usages** — dans `survolDeclarationProjet` (ou en amont
     dans `survol`), quand la déclaration élue vit dans le document du curseur, retrouver
     l'élément VIF par (uri + plage du nom) et lui appliquer `DocumentationKotlin.kdocDe`
     (le même appel que `survolNomDeclare`). Coût : un parcours d'ancêtres par survol
     d'usage — négligeable (le résolveur fait déjà plus lourd par requête).
  2. **KDoc du scan en mémoire** — faire porter au scan l'offset de la KDoc de chaque
     déclaration et relire le fichier à la demande : couvre le cross-fichier, coût d'un
     listage/parse par survol de déclaration tierce, contre la justification K3
     documentée. À différer tant que le confort du document courant n'est pas acquis.
  3. **Statu quo** — le nom déclaré porte la doc, l'usage porte la signature :
     fonctionnellement honnête, mais l'UX diverge de toutes les références pour un coût
     de correction quasi nul (option 1).
- **Décision** : retenir l'**option 1** maintenant, l'**option 2** en second temps
  (dépendance : profiler le coût du re-parse du fichier tiers — il existe déjà un chemin
  de lecture bornée par requête pour les définitions, réutilisable). Concrètement :
  `survolDeclarationProjet` reçoit l'uri du document courant, et quand la déclaration élue
  a `uri == document courant`, la déclaration VIVE correspondante (recherche par plage du
  nom dans l'arbre déjà en mémoire) fournit la KDoc ; sinon signature seule (comportement
  actuel conservé pour le scan).
- **Conséquences** :
  - Survol d'un appel dans le document ouvert : MARKDOWN (bloc ```kotlin + signature +
    KDoc), miroir du survol du nom déclaré ; PLAINTEXT conservé quand aucune KDoc
    n'existe (règle existante : documentation vide → plaintext).
  - Le client cel-lsp consomme déjà les deux kinds (le MARKDOWN est servi aujourd'hui par
    la voie nom déclaré) — aucun changement de transport.
  - Tests : S4 du harnais inverse sa caractérisation b) (usage → MARKDOWN) ; au moteur
    (`SessionLangageKotlinK3Test`), « survol usage dans le document courant avec KDoc de
    la déclaration vive » ; conserver « déclaration du SCAN : signature seule » comme
    garde du comportement cross-file tant que l'option 2 n'est pas décidée.
  - L'option 2 rouvrira ce dossier pour le cross-fichier ; elle exigera sa propre ADR
    (coût du re-parse, budget mémoire du pack).
