# ADR 0227 — Diagnostics par déclaration sur texte vivant (chantier P1-A)

- **Statut** : appliqué (29/10/2026 — premier chantier P1 de la feuille de
  route du rapport d'analyse comparative du 28/10/2026 ; verrouillé par
  `DiagnosticsParDeclarationTest` (8 tests) + la suite existante de la
  K4 sans régression, baseline de parité re-synchronisée).
- **Contexte** : la garde historique du catalogue sémantique était TOUT
  OU RIEN — « un document portant le MOINDRE marqueur d'erreur du
  parseur n'est PAS diagnostiqué sémantiquement ». Or une frappe au bas
  d'un fichier (nouvelle déclaration en cours) posait un marqueur qui
  éteignait TOUTES les preuves du reste du document, précisément pendant
  l'édition — le moment où l'utilisateur regarde les soulignés. Le rapport
  d'analyse a qualifié ce chantier de structurel : il débloque les
  diagnostics vivants, les quick-fixes qui s'y rattachent et la moitié
  du chemin vers l'incrémentalité.
- **Options considérées** :
  1. (a) **Garde par déclaration** : ne diagnostiquer que les
     déclarations dont le SOUS-ARBRE parse proprement, avec des gardes
     d'absence dédiées à `kt.unresolved` (retenue) ;
  2. (b) Conserver le tout-ou-rien et n'agir que sur la fréquence
     (diagnostiquer à l'inactivité) — rejetée : le problème est la
     GRANULARITÉ de la preuve, pas son calendrier ;
  3. (c) Diagnostiquer tout le document dès qu'une déclaration est
     propre, sans gardes d'absence — rejetée : un nom qui ne résout pas
     parce qu'une DÉCLARATION VOISINE est en cours de frappe (paramètres
     avalés par la récupération) ou parce que la zone package/import est
     brisée serait un FAUX POSITIF — D3 l'interdit.
- **Décision** :
  1. **Régions propres** : quand l'arbre porte au moins un marqueur
     d'erreur, les familles sémantiques n'émettent que dans les
     déclarations (FUN, PROPERTY, CLASS, OBJECT_DECLARATION, TYPEALIAS,
     ENUM_ENTRY — toute profondeur) dont le sous-arbre est sans marqueur.
     Un marqueur d'erreur étant un nœud VIDE (les jetons non reconnus
     restent enfants de la production englobante), il ne peut jamais
     être l'ancêtre d'une déclaration : la propreté se joue sur le
     sous-arbre seul. Les preuves LOCALES (`kt.missingReturn`,
     `kt.unreachable`, `kt.valReassign`, `kt.typeMismatch`,
     `kt.unsafeNullable`) et les preuves ancrées sur une déclaration
     TROUVÉE (`kt.argumentCount`) y sont fondées.
  2. **Gardes d'absence pour `kt.unresolved`** (la seule famille dont la
     preuve est une ABSENCE) :
     - **noms déclarés** : un nom déclaré N'IMPORTE OÙ dans le document
       (à toute profondeur — déclaration en cours de frappe ou avalée
       par la récupération) n'est jamais signalé ;
     - **zone package/import brisée** : un marqueur sous IMPORT_LIST ou
       PACKAGE_DIRECTIVE rend les noms IMPORTÉS non fiables — silence
       sur tout le document ;
     - **en-tête de classe brisé** : un marqueur dans tout enfant hors
       CLASS_BODY d'une classe (supertypes, paramètres de type, clause
       where, constructeur primaire) rend les membres HÉRITÉS illisibles
       — les membres propres restent diagnosticables mais PAS
       absence-fiables (régions à deux niveaux).
  3. **Candidates d'arité** : une candidate du DOCUMENT COURANT hors
     région propre (surcharge en cours de frappe) n'est pas un témoin
     d'arité fiable — `kt.argumentCount` l'écarte, y compris les membres
     englobants synthétiques.
  4. **Chemin propre inchangé** : un document SANS marqueur d'erreur ne
     passe jamais par la garde (aucun coût, aucun comportement
     modifié) ; un document sans AUCUNE région propre reste muet
     (l'ancien comportement est un cas particulier).
- **Conséquences** :
  - `DiagnosticsParDeclarationTest` (8) : frappe au bas du fichier
    (les preuves du dessus survivent), preuve locale pendant l'édition
    ailleurs, membre propre dans une classe cassée, nom déclaré en
    cours de frappe jamais signalé, zone import brisée muette, en-tête
    de classe brisé muet en absence, surcharge en cours de frappe
    écartée de l'arité, document propre inchangé.
  - La suite K4 existante (`arbre en edition, tout le semantique
    ecarte` — document à déclaration UNIQUE cassée) reste VERTE sans
    modification : elle est devenue le cas particulier « zéro région ».
  - Chaîne `:lsp:kotlin` VERTE (417 tests, 0 échec) ; `:lsp:host`
    inchangé (12 échecs environnementaux préexistants au SDK du bac à
    sable, identiques avant/après) ; baseline de parité
    re-synchronisée (le corpus EST le dépôt — le nouveau fichier de
    test l'a fait passer de 298 à 299 fichiers).
  - Angle mort assumé : les déclarations de premier niveau SANS
    déclaration englobante (code nu de script hors `.kts`, fragments
    d'expression au niveau du fichier) ne sont pas des régions — elles
    restent muettes tant que le document porte une erreur
    (sous-rapport D3). L'« index en construction » de la feuille de
    route est déjà couvert par la garde ADR 0211 (index non couvert ⇒
    silence) — la garde d'édition s'y AJOUTE.
