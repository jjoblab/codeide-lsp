# ADR 0232 — Correctifs d'expression wrap / cast / surround (chantier 3)

- **Statut** : appliqué (29/10/2026 — chantier 3 « quick fixes complémentaires
  (wrap / cast / surround) » de la feuille de route post-P1 ; verrouillé par
  `CorrectifsTexteTest` (13 tests moteur par le SPI : les trois correctifs
  de l'accès non sûr aux éditions EXACTES — point, assertion, entourage
  multi-ligne et en ligne —, la matrice de conversion fermée, les gardes
  D3 — accès sûr, garde de nullité, premier niveau sans bloc, type
  nullable, cible non primitive —, l'alignement correctif ⟺ diagnostic et
  le diagnostic périmé) et le transport (`KotlinLanguageServerTest` :
  4 tests — trois actions titrées reliées au diagnostic, deux actions du
  littéral avec l'édition de la plage écrite, moteur en échec honnête,
  diagnostic dupliqué dédoublonné), suite sans régression (kotlin 469,
  host 257), baseline de parité re-synchronisée.
- **Contexte** : après les imports (K4) et les membres manquants (P1-C,
  ADR 0229), le moteur Kotlin reste muet sur les deux diagnostics les
  plus ACTIONNABLES au quotidien : `kt.unsafeNullable` (le message
  lui-même dit « utiliser « ?. » ou « !! » » — l'éditeur doit pouvoir le
  FAIRE) et `kt.typeMismatch` (littéral d'une famille affecté au type
  écrit d'une autre). Android Studio offre là-dessus ses correctifs les
  plus utilisés : replace with `?.`, wrap with `!!`, surround with null
  check, change variable type, wrap with conversion. Le chantier 3 les
  rejoint — sur les seules preuves que le moteur possède DÉJÀ.
- **Options considérées** :
  1. (a) **Dériver les correctifs des MÊMES marches que les diagnostics**
     (retenue) : `typesIncompatibles` et `accesNonSurs` deviennent le
     MAPPING de collecteurs internes (`litterauxIncompatibles`,
     `accesNonSursProuves` — les nœuds ET les faits dérivés), que les
     quick-fixes réutilisent ; l'alignement correctif ⟺ diagnostic est
     PAR CONSTRUCTION (mêmes prédicats, mêmes gardes, mêmes régions
     propres P1-A) ;
  2. (b) Dupliquer les prédicats dans l'objet de correctifs — rejetée :
     la divergence est certaine (le jour où le diagnostic affine sa
     garde, le correctif offrirait des éditions sur du code légal, la
     pire trahison d'un quick-fix) ;
  3. (c) Offrir aussi un cast `as` sur le littéral incompatible —
     rejetée : le diagnostic ne vit que sur des familles DISJOINTES du
     type écrit (`"s"` → `Int`) ; le compilateur Kotlin REJETTE le cast
     entre types finaux sans relation (« incompatible types ») — le
     correctif produirait du code cassé (D3). Le « cast » du chantier
     vit côté nullité : `!!` (T? → T).
- **Décision** :
  1. **L'API neutre** : `GenreCorrectifKotlin` (APPEL_SUR,
     ASSERTION_NON_NULLE, TEST_NULLITE, CHANGEMENT_TYPE, CONVERSION) +
     `CorrectifTexteKotlin` (genre, plage, texte, argument) — l'édition
     CONCRÈTE calculée sur l'arbre COURANT ; le moteur ne connaît ni
     LSP ni titre (miroir ADR 0229). Le contrat de session gagne
     `correctifs(uri, decalage)` au DÉFAUT VIDE (tolérance de version —
     un pack antérieur dégrade honnêtement).
  2. **`kt.unsafeNullable` → trois correctifs** : `?.` (insertion du
     `?` devant le JETON point de CETTE expression — `s.length.trim()`
     corrige le point de SON segment), `!!` (insertion après le
     récepteur), et l'entourage `if (nom != null) { … }` de
     l'instruction englobante — l'enfant DIRECT du bloc le plus
     profond (chaîne des ancêtres) ; MULTI-LIGNE quand l'instruction
     possède sa ligne (le corps gagne un pas de 4 espaces, la garde
     ferme à l'indent de l'instruction — miroir Android Studio), EN
     LIGNE sinon (`if (s != null) { … }` — jamais de reformatage
     surprise d'une ligne partagée). SANS bloc ancêtre (premier
     niveau), l'entourage est MUET : `if` est illégal au sommet d'un
     fichier Kotlin.
  3. **`kt.typeMismatch` → deux correctifs** : le changement du type
     ÉCRIT pour le représentant de la famille du littéral
     (`famille.first()` sans le préfixe `kotlin.` — `String`, `Int`,
     `Float` pour un littéral `2.5f`…) — toujours légal, le littéral
     affecte son propre type ; et l'encadrement d'une conversion
     STANDARD `.toX()` quand le FQCN écrit est une primitive qui
     l'admet — la matrice est FERMÉE par famille : numérique →
     numérique/Char/String, chaîne → numérique (parse) et Boolean
     (total), booléen → String, caractère → String ; caractère →
     numérique EXCLU (`Char.toInt()` déprécié depuis Kotlin 1.5 — un
     correctif déprécié est une régression déguisée, D3).
  4. **Le re-prouve à la requête** : l'hôte interroge à l'offset de
     chaque diagnostic ciblé du contexte (`kt.unsafeNullable`,
     `kt.typeMismatch`, plages dédoublonnées) ; le moteur re-dérive de
     l'arbre COURANT — un diagnostic périmé (texte édité depuis la
     publication) rend VIDE : la preuve est structurelle, jamais une
     mémoire du diagnostic publié. Moteur en échec : rien (journal
     WARNING, jamais d'exception vers le client).
  5. **Le transport** : `textDocument/codeAction` traduit chaque
     correctif en CodeAction QuickFix relié à SON diagnostic, titre
     dérivé du genre et de l'argument (« Remplacer « . » par « ?. » »,
     « Encadrer le récepteur de « !! » », « Entourer d'un test de
     nullité « if (s != null) » », « Changer le type en « String » »,
     « Encadrer d'une conversion « .toInt() » ») — aucun changement de
     capability (codeActionProvider déjà annoncé depuis K4).
- **Conséquences** :
  - les correctifs ne s'offrent QUE là où le diagnostic peut
    s'exprimer (régions propres P1-A comprises) — jamais sur du code
    légal, jamais sur une région brisée en édition ;
  - l'ordre des actions est STABLE : du plus sûr au plus structurel
    (`?.` avant `!!` avant l'entourage ; changement de type avant la
    conversion), le client rend la palette dans cet ordre ;
  - les éditions sont des textes REPLACEMENT bruts : l'entourage
    multi-ligne détecte l'indentation de la ligne de l'instruction et
    ajoute UN pas de 4 espaces (convention Android Studio) — le
    formateur du client reste maître du rendu final ;
  - les collecteurs internes (`litterauxIncompatibles`,
    `accesNonSursProuves`, `regionsPropres`) deviennent le terrain
    partagé diagnostic/quick-fix : toute évolution future de la preuve
    (famille élargie, garde affinée) porte les DEUX surfaces ensemble ;
  - hors périmètre (sous-rapport D3 assumé) : les diagnostics que le
    moteur n'a pas (`kt.argumentCount` n'a pas de correctif de
    paramètres manquants — l'arité du projet ne dit pas la VOLONTÉ de
    l'appel), le surround générique Ctrl+Alt+T (if/try/while/run/let —
    un refactoring SANS diagnostic, un autre chantier), et le cast
    d'expressions non littérales (le moteur ne prouve pas leur type).
