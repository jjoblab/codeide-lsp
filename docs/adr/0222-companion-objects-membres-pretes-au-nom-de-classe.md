# ADR 0222 — Companion objects : membres prêtés au nom de la classe

- **Statut** : appliqué (25/10/2026 — suite recommandée n° 4 du rapport
  d'audit §8 ; le patron `Boite.creer()` de toute classe Kotlin
  idiomatique était un non-fonctionnement de bout en bout : le survol,
  la complétion, la signature et le typage des chaînes restaient muets,
  et le compagnon ANONYME était enregistré sous le nom de son PREMIER
  MEMBRE).
- **Contexte** : le nom d'une classe Kotlin PRÊTE les membres de son
  `companion object` (miroir kotlinc : `Boite.creer()` appelle le
  membre d'instance du compagnon, `Int.MAX_VALUE` lit le champ de
  `kotlin.Int$Companion`, `Regex.escape("a")` appelle la méthode du
  compagnon du stdlib). Le moteur ne modélisait RIEN de ce prêt :
  - **Projet** : `SymbolesProjet.extraire` passait par le repli générique
    `nomJeton()` — pour un compagnon ANONYME, celui-ci tombe sur le
    PREMIER IDENTIFIER descendant (le nom d'un MEMBRE) : `companion
    object { fun creer() }` était enregistré comme OBJET « creer » et
    ses membres sous le conteneur FAUX « pkg.Boite.creer ». Pire : la
    même heuristique poisonnait `classeEnglobante`
    (TypageExpression), `nomConteneur` (OccurrencesKotlin) et
    `estMembreHerite` (ResolutionReferences) — le this implicite du
    CORPS du compagnon ne résolvait que par symétrie accidentelle des
    deux bugs.
  - **Classpath** : les compagnons des bibliothèques vivent en classes
    imbriquées par `$` (`kotlin.text.Regex$Companion`,
    `kotlin.UInt$Companion`) — la vue expose leurs membres, mais
    AUCUN sondage ne cherchait `fqcn$Companion`.
- **Options considérées** :
  1. **Double émission + sonde `$Companion`** (retenue) — côté projet :
     émettre les membres du compagnon DEUX FOIS (sous l'objet qualifié
     `pkg.Boite.Companion` ET sous la classe englobante `pkg.Boite`),
     avec le nom effectif « Companion » pour les anonymes ; côté
     classpath : sonder `fqcn + "$Companion"` sur les récepteurs
     STATIQUES (typage des chaînes, complétion membre, survol,
     signatures, occurrences, diagnostic de sélection par types).
  2. **Fusionner le compagnon dans membresDe(fqcn)** — rejetée : la vue
     membre d'une classe resterait mélangée (les membres d'instance du
     compagnon apparaîtraient sur les récepteurs d'INSTANCE —
     `boite.creer()` est une ERREUR Kotlin, l'offrir serait un mensonge).
  3. **Ne toucher qu'au nommage (Companion) sans double émission** —
     rejetée : `Boite.creer()` (accès par le nom de la classe, LA forme
     idiomatique) resterait muet — seul `Boite.Companion.creer()`
     fonctionnerait.
- **Décision** : retenir l'**option 1** :
  - `KtObjectDeclaration.estCompanion()` : le modificateur `companion`
    vit dans le MODIFIER_LIST enfant — la grammaire vendorée le porte
    soit en JETON feuille, soit en NŒUD REPLIÉ dont le TYPE est celui
    du jetet (sondé : les jetons descendants de MODIFIER_LIST sont
    VIDES dans le cas replié) : la sonde regarde les ENFANTS DIRECTS.
  - `KtObjectDeclaration.nomObjet()` : jeton IDENTIFIER DIRECT
    (le nom propre d'un objet nommé), SANS repli descendant.
  - `SymbolesProjet.extraireObjet` : nom effectif = nom propre ou
    « Companion » (anonyme en classe) ; l'OBJET est émis sous la
    classe englobante ; ses membres sous `pkg.Boite.Companion` (accès
    explicite + this implicite du corps) ET sous `pkg.Boite` (accès
    par le nom de la classe).
  - `classeEnglobante` / `nomConteneur` / `estMembreHerite` : le même
    nommage companion-aware (nom propre, sinon « Companion »).
  - `TypageExpression` : la branche qualifiée distingue récepteur
    VALEUR (membres d'instance) et récepteur CLASSE (statique : la
    classe, puis SON compagnon du classpath via
    `typeMembreCompanionDe`) ; `typeStatiqueDe` devient internal
    (partagé avec le diagnostic ADR 0223 et les consommateurs).
  - `CompletionKotlin.propositionsMembre` : régime statique — les
    membres d'instance de `fqcn$Companion` s'offrent après le nom de
    la classe (statiqueSeul assoupli pour CE porteur seul : les membres
    du compagnon ne portent pas le drapeau statique JVM).
  - `survolMembreQualifie` / `candidatesQualifie` /
    `occurrenceSelection` : le récepteur STATIQUE (nom de classe)
    devient un récepteur légitime — repli `typeStatiqueDe` + cascade
    compagnon ; la preuve d'occurrence accepte le conteneur IMBRIQUÉ
    sous le type statique (`pkg.Boite.Companion`).
- **Preuves** :
  - **Moteur** : `ReproductionCompanionTest` (7 tests) — compagnons
    anonyme/nommé (zéro faux positif, survol, complétion `Boite.`
    offre `creer`/`TAILLE`/`ouvrir`, signature, chaîne typée
    `Boite.creer().ouvrir()`), this implicite du corps, non-régression
    de l'objet simple (ADR 0220), compagnons du CLASSPATH
    (`Regex.escape` survol+complétion, `UInt.MAX_VALUE` champ typé) et
    la garde D3 (`Regex.echappe` reste signalé).
  - **Baseline de parité** régénérée (fichiers du dépôt modifiés).
