# ADR 0228 — Complétion contextuelle Kotlin, vague 1 (chantier P1-B)

- **Statut** : appliqué (29/10/2026 — deuxième chantier P1 de la feuille
  de route du rapport d'analyse comparative du 28/10/2026 ; verrouillé par
  `SessionLangageKotlinCompletionContextuelleTest` (14 tests) + le
  transport hôte (`KotlinLanguageServerTest`, 2 tests), suite sans
  régression, baseline de parité re-synchronisée (302 fichiers).
- **Contexte** : le rapport d'analyse a qualifié la complétion
  contextuelle de « retard majeur » — le moteur de référence classe la
  position en quatre régimes et y ajoute une couche de CONTEXTES DÉDIÉS
  (arguments nommés, constantes d'enum dans un `when`, attributs
  d'annotation) que notre moteur ne servait pas, alors que notre propre
  moteur **Java** les sert depuis la 1.16.0. Le rapport borne la vague 1 :
  les trois contextes Java existants, portés au Kotlin — les pesées par
  type attendu et par contexte Compose (typage d'expression à étendre)
  restent un chantier à part entière.
- **Options considérées** :
  1. (a) **Portage des trois contextes** avec deux positions EXCLUSIVES
     (annotation, `when` enum) et une FUSION (arguments nommés) —
     retenue ;
  2. (b) Tout fusionner au régime de nom sans exclusivité — rejetée : une
     position d'attribut d'annotation ou de condition de branche
     n'écrit PAS d'autre nom (le moteur Java rend « les attributs non
     fournis, RIEN d'autre » depuis la 1.16.0 — le miroir est la bonne
     frontière) ;
  3. (c) Attendre le typage d'expression complet (pesée par type attendu)
     et tout livrer d'un bloc — rejetée : les trois contextes ne
     demandent QUE des preuves déjà disponibles (résolution d'appel de
     l'aide à la signature, typage du sujet, membres binaires de la vue).
- **Décision** :
  1. **Arguments nommés** (`foo(na|` → `nom = `) : les noms de paramètres
     des surcharges PROUVÉES de l'appel englobant — réutilisation du
     graphe de l'aide à la signature (`SignaturesAppels.nomsParametresToutes`,
     chaque surcharge contribue ses noms, une candidate JVM sans noms —
     les descripteurs ne les portent pas — ne contribue RIEN). FUSIONNÉS
     au régime de nom : un argument accepte toute expression, la locale
     `nom` reste offerte à côté de `nom =`. Les arguments déjà fournis en
     forme nommée sont exclus ; les positions de VALEUR (littéral,
     expression composée, après `=`) sont muettes. La frontière droite
     FERMÉE compte pour l'appel englobant (le curseur collé à la fin de
     l'identifiant en cours de frappe est LA position de la complétion) —
     mais une liste REFERMÉE garde le curseur hors contexte.
  2. **Constantes d'enum** (`when (c) { RO| ->`) : les branches d'un
     `when` à sujet enum écrivent les constantes NON QUALIFIÉES — les
     entrées de l'énumération du sujet (PROJET : le scan émet chaque
     entrée comme déclaration sous le conteneur qualifié de l'enum, le
     drapeau « sans héritage écrit » distingue un éventuel objet nié au
     même conteneur ; CLASSPATH : champs statiques dont le type déclaré
     est l'enum elle-même, miroir du moteur Java). Position EXCLUSIVE :
     condition de branche (dans un `WHEN_CONDITION_EXPRESSION` ou
     position vide entre les entrées) — le corps d'une branche et une
     garde ne le sont pas ; le curseur DANS le sujet ne l'est pas.
     Sujet non enum : repli honnête sur le régime de nom (les conditions
     `is`/`in` et les valeurs calculées y vivent).
  3. **Attributs d'annotation** (`@Anno(lev|` → `level = `) : les méthodes
     publiques ABSTRAITES du type d'annotation (résolu par les imports du
     fichier, nom qualifié écrit = FQCN), exclus des attributs déjà
     fournis en forme nommée, insérées avec «&#160;= » — le séparateur
     VOYAGE avec le candidat. Position EXCLUSIVE (miroir du `@Anno(` du
     moteur Java) ; annotation non résolue : repli honnête.
  4. **Transport** : `PropositionCompletionKotlin.texteInsertion`
     (défaut `null` = le client insère le libellé, contrat historique) —
     l'hôte le traduit en `insertText` LSP, miroir du `CompletionItem`
     du moteur Java. Tolérance de version : le champ est optionnel, un
     pack ancien dégrade honnêtement.
  5. **Bornes honnêtes (D3)** : un appel inconnu n'offre AUCUN argument
     nommé (jamais un nom inventé) ; le filtrage par préfixe est
     `startsWith` case-SENSIBLE partout (patron du moteur) ; les
     `when` sans sujet et les sujets propriété (`when (val x = …)`) sont
     hors contexte (non typés ici).
- **Conséquences** : la complétion Kotlin sait maintenant OÙ elle est
  dans trois positions à forte valeur de frappe ; le champ
  `texteInsertion` ouvre la voie aux candidats à insertion enrichie sans
  toucher au contrat libellé. La vague 2 (pesée par type attendu,
  contexte Compose, KDoc, labels, `::`, infix) suppose le typage
  d'expression — chantier séparé de la feuille de route.
