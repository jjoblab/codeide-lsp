# ADR 0202 — Formateur Kotlin borné (ré-indentation canonique seule)

- **Statut** : accepté (21/10/2026, lot L2 du plan 1.3.0).
- **Contexte** : le lot L2 du plan 1.3.0 demande un formateur Kotlin miroir de celui du
  XML : ré-indentation canonique calculée depuis l'arbre syntaxique. Le moteur Kotlin est
  un parseur sur la grammaire vendorée (arbitrage K1-K3, Analysis API K2 écartée D1) : il
  ne possède pas de formateur sémantique complet, et le principe X4/D3 du dépôt — honnêteté
  avant couverture — interdit de servir ce qui n'est pas prouvé. Un formateur complet du
  langage (alignements, espaces canoniques, retours à la ligne) exigerait des choix de
  STYLE non prouvables par le moteur et réécrirait le CONTENU des lignes — le risque le
  plus grave d'un formateur est de casser ce qu'il ne comprend pas.
- **Décision** : le formateur Kotlin est BORNÉ à la ré-indentation canonique, miroir du
  formateur XML S3 (ADR sous-jacent du lot S3 XML) :
  1. seul le BLANC DE TÊTE d'une ligne est remplacé, jamais le contenu — la profondeur
     cible vient des jetons de l'arbre : accolades ouvertes terminées avant la ligne,
     MOINS les fermantes en tête de ligne (le fermant vit à la profondeur de son bloc),
     PLUS UN pour la continuation de liste non fermée (paramètres/arguments multi-lignes,
     la ligne qui ferme la liste revenant à la base) ;
  2. INTOUCHABLES (refus honnête D3) : document portant des marqueurs d'erreur du parseur
     (liste d'éditions VIDE — la profondeur d'un arbre incomplet serait une invention) ;
     ligne DANS un jeton multi-ligne (bloc de commentaire, KDoc) ; ligne de CHAÎNE BRUTE
     (le lexer vendoré découpe la chaîne ligne par ligne : le blanc de tête d'une ligne
     interne est du CONTENU de la chaîne, jamais du code) ; continuation d'EXPRESSION
     libre en tête (`.` chaîné, `?:`, opérateur binaire, `->`) hors liste ouverte — un
     choix de style, pas une structure prouvable ; ligne vide ;
  3. IDEMPOTENCE : une ligne déjà juste ne produit aucune édition (reformater un document
     formaté ne change rien) ;
  4. FORMATAGE DE PLAGE : seules les lignes qui INTERSECTENT la plage demandée sont
     candidates et toute édition reste DANS la plage (une édition hors plage décalerait le
     buffer du client) ;
  5. INDENTATION PILOTÉE PAR LE CLIENT : `FormattingOptions.tabSize` du protocole (défaut
     4 espaces) ; le moteur n'émet que des espaces (pas de tabulations).
- **Conséquences** : l'utilisateur obtient une indentation structurelle fiable SANS aucun
  risque de modification de contenu — chaînes brutes, commentaires et KDoc intacts par
  construction, prouvé par tests (absence de recouvrement d'édition avec les jetons
  multi-lignes, FormateurKotlinTest) ; la capacité est annoncée comme « formatage » au
  sens LSP et la documentation LSP_KOTLIN.md dit explicitement « ré-indentation seule » ;
  le formatage à la frappe et la granularité tabulations/espaces restent HORS périmètre ;
  toute extension future (espaces canoniques autour des opérateurs, par exemple) exigera
  une nouvelle décision — le présent ADR verrouille le périmètre borné.
- **Mise en œuvre** : `jo.lspkotlin.format.FormateurKotlin` (moteur), surcharges
  `formatage`/`formatage(plage)` de `SessionLangageKotlinParse` (session), gestionnaires
  `formatting`/`rangeFormatting` de `KotlinLanguageServer` (hôte, lot L2 partie 3) ;
  preuves : FormateurKotlinTest (moteur/session, 9 tests) et KotlinLanguageServerL2Test
  (transport réel, 2 tests).
