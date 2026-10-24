# ADR 0206 — Occurrences des propriétés déclarées dans le constructeur primaire

- **Statut** : appliqué (24/10/2026 — audit E2E du serveur LSP Kotlin, découverte D4, scénarios S7/S8 ; mise en œuvre v1.6.0 : correctif moteur + tests, sondes S7 du harnais verrouillées sur le comportement corrigé au fil du transport réel).
- **Contexte** : le scan de projet classe les paramètres du constructeur primaire — y
  compris ceux qui portent `val`/`var` (`class Boite(private val contenu: Int)`) — en
  `GenreDeclaration.PARAMETRE`. Or les candidates d'occurrences excluent explicitement les
  `PARAMETRE` (`cibleAuCurseur`, voie 3) : l'usage non qualifié `contenu` dans
  `fun lire(): Int = contenu` ne prouve donc JAMAIS sa cible — `documentHighlight` rend la
  déclaration seule (S7 : 1) quand la même propriété déclarée dans le CORPS de la classe
  rend déclaration + usage (S8/F3 : 2). C'est la forme de déclaration la plus idiomatique
  de Kotlin (et la plus fréquente en Android : `class Vue(private val ctx: Context)`) :
  surlignage, références et renommage sont incomplets précisément là où le code réel vit.
  Le paramètre-propriété est DEUX choses en sémantique Kotlin : un paramètre (arité,
  portée) et une PROPRIÉTÉ membre de la classe (occurrences, complétion membre) — le scan
  ne modélise que la première.
- **Options considérées** :
  1. **Double déclaration** — le scan émet, pour chaque paramètre de constructeur portant
     `val`/`var`, l'entrée `PARAMETRE` existante (arité K4, signée) PLUS une entrée
     `PROPRIETE` membre conteneur = classe, même plage de nom. Les occurrences voient une
     candidate ; l'arité garde son marquage (`kt.argumentCount` ignore déjà les candidates
     marquées `estJava`, le marquage paramètre reste distinct).
  2. **Relaxer le filtre** — accepter `PARAMETRE` comme cible quand il porte `val`/`var` :
     contourne le modèle plutôt que de le compléter ; le `GenreDeclaration` unique ment
     (une cible « paramètre » n'a pas de conteneur — la preuve par classe englobante
     `occurrenceMembreEnglobant` exige un conteneur).
  3. **Statu quo documenté** — consigner l'écart dans la matrice : le sous-rapport frappe
     la forme la plus courante du langage ; le rename partiel qu'il induit (déclaration
     renommée, usages épargnés) est du même ordre de gravité que la découverte D2.
- **Décision** : retenir l'**option 1** — le scan (et la lecture vive
  `declarationViveAuCurseur` par le même fait) émet la déclaration `PROPRIETE` supplémentaire
  pour les paramètres `val`/`var` du constructeur primaire, avec `conteneur` = classe
  englobante qualifiée (convention `DeclarationProjet`) et la plage du nom du paramètre.
  La déclaration `PARAMETRE` signée est conservée pour l'arité et la signature — les deux
  vives ensemble, la désambiguïsation d'occurrences préfère la `PROPRIETE` (conteneur
  renseigné). La preuve d'usage passe alors par `occurrenceMembreEnglobant` (classe
  englobante == conteneur), déjà éprouvée par S8/F3.
- **Conséquences** :
  - `documentHighlight`/`references`/`rename` couvrent les propriétés de constructeur :
    déclaration + usages non qualifiés dans la classe ; le qualifié (`b.contenu`) suit la
    voie sélecteur typé déjà en place.
  - `documentSymbol` peut afficher les propriétés de constructeur comme enfants de la
    classe (miroir des intentions de l'éditeur) — bonus gratuit, non bloquant.
  - Risque de DOUBLON d'occurrence (la déclaration apparaît comme paramètre ET propriété à
    la même plage) : les occurrences dédupliquent déjà par plage (`sortedBy debut` + le
    jeton déclaré rendu une fois par la branche plage) — à verrouiller par fixture.
  - Tests : S7 passe de « 1 » à « 2 » ; ajouter au moteur
    (`SessionLangageKotlinOccurrencesTest`) « propriété de constructeur : usage non
    qualifié prouvé » et « renommage : déclaration + usage édités ».
