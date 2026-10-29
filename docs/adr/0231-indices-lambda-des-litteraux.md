# ADR 0231 — Indices lambda des littéraux de fonction (chantier 2)

- **Statut** : appliqué (29/10/2026 — chantier 2 « branchement des
  indices lambda dans IndicesKotlin » de la feuille de route post-P1 ;
  verrouillé par `CapacitesL2KotlinTest` (4 nouveaux tests moteur :
  `it` implicite par fonction projet, paramètres explicites, gardes
  sans preuve/arité/typé, propriété typée + SAM projet) et le
  transport (`KotlinLanguageServerL2Test` : libellé LAMBDA rendu TEL
  QUEL après l'accolade, padding, ancre TYPE en fin de nom), suite
  sans régression, baseline de parité re-synchronisée).
- **Contexte** : l'inférence BORNÉE des paramètres de lambda existe
  depuis K4 (ADR 0217 — `ParametresLambda.typeParametre` type un
  paramètre UTILISÉ dans le corps : complétion membre, survol), mais
  les INDICES EN LIGNE (`textDocument/inlayHint`) n'en voyaient rien :
  le moteur ne rendait que les noms de paramètres d'appels (genre
  PARAMETRE) et les types certains de déclarations (genre TYPE). Or le
  contrat visible d'Android Studio pour un littéral `{ println(it) }`
  est l'indice `it: View` en pâle juste après l'accolade ouvrante — le
  nom du paramètre implicite n'existe NULLE PART dans le source, c'est
  l'information la plus précieuse du littéral. Le moteur savait déjà
  PROUVER l'attente du consommateur (propriété typée, fonction projet,
  SAM projet, appel classpath — ADR 0217 puis K5 pour le classpath) ;
  il ne manquait que le BRANCHEMENT vers `IndicesKotlin`.
- **Options considérées** :
  1. (a) **Un genre LAMBDA dédié pour le `it` implicite, le genre TYPE
     pour les paramètres explicites** (retenue) : le `it` porte un
     libellé COMPLET `it: Type` (nom ET type — le nom n'est pas écrit,
     l'indice doit le fournir) ancré sur l'accolade ouvrante ; un
     paramètre explicite `x, y ->` porte déjà son nom, seul son TYPE
     manque : un indice TYPE `: T` ancré sur son NOM, le même contrat
     que les déclarations sans type écrit ;
  2. (b) Un libellé unique complet pour TOUT le littéral (`a: Int,
     b: String` après l'accolade) — rejetée : les noms explicites
     EXISTENT déjà dans le source juste après l'accolade, l'indice les
     dupliquerait visuellement (`{ a: Int, b: String a, b -> }` — du
     bruit, pas de l'information) ;
  3. (c) Étendre l'inférence (nouveaux consommateurs : récepteurs
     `with`, imbriqués) — hors périmètre : le chantier est un
     BRANCHEMENT, l'inférence bornée reste celle de l'ADR 0217/K5 (un
     autre littéral entre le consommateur et le littéral coupe
     l'attente directe — sous-rapport D3 assumé).
- **Décision** :
  1. **Le genre LAMBDA** (`api/IndicesKotlin`) : paramètre IMPLICITE
     `it` d'un littéral SANS liste de paramètres — libellé COMPLET
     `it: TypeCourt`, ancre sur l'ACCOLADE OUVRANTE
     (`debut..debut+1`), le label se rend APRÈS elle. Exception au
     contrat des séparateurs : l'hôte le rend TEL QUEL (le nom et le
     séparateur `:` sont DANS le libellé), kind LSP Parameter, padding
     gauche ET droite (le fantôme respire entre l'accolade et le
     corps — miroir du rendu Android Studio). Garde : l'attente doit
     être UNAIRE (`types.size == 1`) — un `it` n'existe que pour un
     paramètre attendu unique.
  2. **Les paramètres explicites** : UN indice TYPE par paramètre SANS
     type écrit, ancré sur son NOM (`: Type` par l'hôte, fin d'ancre),
     libellé = nom COURT du FQCN prouvé (`android.view.View` →
     `View`). Un paramètre DÉJÀ typé n'en reçoit pas (déjà visible) ;
     l'arité du littéral doit épouser l'attente (`{ -> }` et les corps
     en édition ne prouvent rien) : à la moindre divergence, AUCUN
     indice pour le littéral (D3).
  3. **La preuve** : `ParametresLambda.typesLitteral` (nouveau, même
     moteur que `typeParametre` — l'ancre est le DÉBUT du littéral,
     son accolade ouvrante : le littéral lui-même est le plus profond
     qui la porte) rend la liste COMPLÈTE des types attendus du
     CONSOMMATEUR (propriété typée, appel projet, SAM projet, appel
     classpath qualifié), `null` sans preuve — jamais un type deviné.
  4. **L'ancre TYPE honorée par le transport** : le contrat du genre
     TYPE annonçait « le label se rend APRÈS le nom » mais le pont
     LSP (`KotlinLanguageServer.inlayHint`) positionnait TOUT à
     `debut` — le type se tissait AVANT le nom (`val : Int entier`).
     Corrigé dans le même mouvement : PARAMETRE → position `debut`
     (AVANT l'argument, inchangé), TYPE et LAMBDA → position `fin`
     (APRÈS l'ancre) — `val entier: Int = 42`, `{ it: Vue println(it)
     }` avec padding.
- **Conséquences** :
  - les indices lambda ne sortent QUE sur preuve du consommateur — un
    littéral passé à une fonction inconnue, un DSL à récepteur implicite
    (`android { … }`) ou un littéral imbriqué dans un autre restent
    MUETS (D3, miroir de l'ADR 0217) ;
  - le libellé LAMBDA est UN SEUL texte (`it: Type`) : pas de tooltip
    par part, pas de cliquabilité (I3/I4 de l'éditeur restent
    disponibles pour un enrichissement ultérieur, le pont LSP les
    transporte déjà) ;
  - le rendu côté ÉDITEUR (espaces fantômes, tissage) reste la
    responsabilité de CodeIDE (`LspInlayHintProvider` honore
    `paddingLeft`/`paddingRight`) — les incohérences de rendu restant
    côté application sont traitées par le chantier dédié de ce dépôt
    de bord ;
  - la matrice des capacités gagne la précision « indices lambda » sur
    la ligne inlayHint.
