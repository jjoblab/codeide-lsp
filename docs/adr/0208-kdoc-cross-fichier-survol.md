# ADR 0208 — KDoc aux sites d'usage CROSS-FICHIERS (option 2 de l'ADR 0207)

- **Statut** : appliqué (24/10/2026 — option 2 de l'ADR 0207, demandée à la suite de
  la mise en œuvre v1.6.0 ; moteur + tests, scénario D1 inversé et sonde S13 du
  harnais verrouillés au fil du transport réel).
- **Contexte** : la v1.6.0 (ADR 0207, option 1) attache la KDoc aux usages quand la
  déclaration vit dans le **document courant** — l'arbre vif est déjà en mémoire. La
  déclaration d'un **AUTRE fichier** du projet (le cas le plus fréquent d'un appel
  inter-fichiers : `additionner(1, 2)` dans `Main.kt`, déclaré dans `Calcul.kt`)
  gardait la signature seule : l'UX divergeait d'Android Studio, de jdt.ls et du LSP
  JetBrains précisément sur le cas d'usage n° 1 (lire la doc SANS sauter). L'ADR
  0207 avait différé ce chantier en exigeant une ADR propre (coût du re-parse,
  budget mémoire) — la voici. Le mémoire n'est PAS en jeu : aucun état n'est gardé
  entre requêtes (lecture bornée à la demande).
- **Options considérées** :
  1. **Port injecté session → moteur** — `Contexte.kdocDeclaration` (une fonction
     `(DeclarationProjet) -> String?`) fournie par la session : document OUVERT →
     son arbre VIF (aucun re-parse, jumelage EXACT par plage du nom) ; fichier
     FERMÉ → lecture disque + parse bornées. Le moteur reste sans E/S (D6), les
     tests moteur purs passent `null` (dégradation honnête documentée).
  2. **Offset KDoc porté par le scan** — faire voyager la KDoc (ou son offset)
     dans `DeclarationProjet` dès le scan : couvre aussi le scan SANS coût par
     requête, mais alourdit CHAQUE déclaration d'un champ texte (budget mémoire
     du pack, le point que l'ADR 0207 voulait éviter) et périmétrise le scan pour
     un seul consommateur.
  3. **Statu quo** — signature seule cross-fichier : fonctionnellement honnête,
     mais l'option 1 est le miroir exact de ce que l'option 1 de l'ADR 0207 a fait
     pour le document courant, à coût borné identique.
- **Décision** : retenir l'**option 1** (port injecté). Concrètement :
  `InterrogationsSymboles.Contexte` gagne `kdocDeclaration` (défaut `null`) ;
  `survolDeclarationProjet` branche le port quand `elue.uri != uri` ; la session
  injecte `kdocDeDeclaration` — documents ouverts d'abord (arbre vif), disque
  ensuite (`File(URI).readText()` + `parseur.analyser`, le MÊME budget que le
  balayage d'occurrences d'UN fichier, UNE fois par survol tiers).
  **Correctif de dominance associé** (découvert par le test « tierce OUVERTE ») :
  `declarationsProjet`/`contexteNomDe` ne fusionnaient les arbres vivants QUE du
  document courant — les documents tiers OUVERTS étaient servis par leur copie
  DISQUE du scan (plages PÉRIMÉES dès la première édition : go-to-definition,
  survol et occurrences visés par un fichier ouvert tiers tombaient sur les
  ANCIENNES positions). La règle documentée « les documents OUVERTS dominent »
  s'applique désormais à TOUS les documents ouverts (`autresDeclarationsOuvertes`
  + filtrage du scan par URIs ouvertes).
- **Conséquences** :
  - Survol d'un usage inter-fichiers : MARKDOWN (bloc ```kotlin + signature +
    KDoc), fichier déclarant ouvert OU fermé ; PLAINTEXT conservé sans KDoc.
  - Le document TIERS ouvert édité → sa KDoc VIVE gagne (jamais le disque
    périmé) — sonde S13 du harnais verrouille ce contrat au transport réel.
  - Coût : un listage/parse par survol de déclaration FERMÉE (borné par le
    plafond du scan), zéro pour un document ouvert — budget documenté, aucun
    état inter-requêtes.
  - Tests : moteur (K3) — fermée, ouverte-vive, sans KDoc, illisible ;
    harnais — D1 inversé (MARKDOWN + KDoc cross-fichier) et S13 (arbre vif
    contre disque) ; la garde v1.6.0 « déclaration du SCAN : signature seule »
    est REMPLACÉE par ces verrous.
  - L'infrastructure du port (lecture bornée par déclaration) sert AUSSI le
    GO TO Super Kotlin (ADR 0210) — même budget, même frontière session/moteur.
