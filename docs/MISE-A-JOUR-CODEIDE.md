# Mise à jour de CodeIDE — notes de passage des versions de codeide-lsp

> Ce document s'adresse à l'agent/propriétaire de CodeIDE : chaque section
> décrit ce qu'une version de codeide-lsp apporte et ce que CodeIDE doit
> faire pour en profiter. Les versions sont publiées par
> `scripts/bump-frere.sh lsp minor` DEPUIS CodeIDE (jamais depuis ce dépôt).
>
> **Trou 1.4.0 → 1.12.0** : ces versions ont été publiées sans note de
> passage ici (l'agent lsp consolidait dans le CHANGELOG du dépôt) —
> consulter `CHANGELOG.md` de codeide-lsp pour le détail, en particulier
> 1.8.0 (demandes D1–D5 de `DEMANDES_CODEIDE_LSP.md`), 1.11.0 (monde
> Views/XML) et 1.12.0 (companion objects ADR 0222, sélection par types
> complets ADR 0223).

## 1.22.0

### 1. État

- `main` de codeide-lsp : chantier **3 « quick fixes complémentaires
  (wrap / cast / surround) »** (ADR 0232, feuille de route post-P1).
- **Ce que ça change** : `textDocument/codeAction` rend de NOUVELLES
  actions sur deux diagnostics existants —
  - `kt.unsafeNullable` (appui sur le récepteur souligné) :
    « Remplacer « . » par « ?. » » (le point devient `?.`),
    « Encadrer le récepteur de « !! » » (`s` → `s!!`),
    « Entourer d'un test de nullité « if (s != null) » » (l'instruction
    englobante est wrappée, multi-ligne si elle possède sa ligne) ;
  - `kt.typeMismatch` (appui sur le littéral souligné) : « Changer le
    type en « X » » (le type écrit est remplacé par celui du littéral)
    et « Encadrer d'une conversion « .toX() » » (`"5"` → `"5".toInt()`
    — uniquement les conversions standard du langage).
  Les actions sont ordonnées du plus sûr au plus structurel, chacune
  reliée à son diagnostic (kind QuickFix, `edit` = un WorkspaceEdit).

### 2. Ce que CodeIDE doit faire

- **Rien de bloquant** : les actions traversent `codeAction` existant,
  aucune capability ne change. Le panneau d'ampoule/quickfix rend déjà
  les CodeActions des imports — les nouvelles s'y mélangent.
- À VÉRIFIER côté rendu : les éditions d'entourage sont MULTI-LIGNES
  (remplacement d'une plage par trois lignes indentées) — appliquer
  l'édition telle quelle puis laisser le formateur/viewport faire son
  travail ; l'undo doit revenir au texte d'origine en UNE étape.
- Nuance honnête (D3) : le moteur RE-PROUVE à la requête — si le texte
  a changé depuis la publication du diagnostic (frappe rapide), la
  palette peut être VIDE sur un diagnostic encore affiché : c'est le
  comportement attendu, jamais de correctif sur une preuve périmée.

### 3. Scénarios de vérification (démo)

1. `fun demo(s: String?) { s.length }` : ampoule sur `s` → trois
   actions ; « ?. » rend `s?.length`, « !! » rend `s!!.length`,
   l'entourage rend `if (s != null) { s.length }` (en ligne, la
   déclaration partage sa ligne) puis multi-ligne sur la forme
   indentée.
2. `val n = s.length` dans un corps : l'entourage enveloppe TOUTE la
   déclaration (`if (s != null) { val n = s.length }`).
3. `val x: Int = "5"` : « Changer le type en « String » » et « Encadrer
   d'une conversion « .toInt() » » ; `val c: Int = 'a'` ne propose QUE
   le changement de type (pas de conversion dépréciée).
4. `val ok: Long = 1` (conversion littérale légale) : aucune action —
   pas de diagnostic, pas de correctif.

## 1.21.1 (républication de 1.21.0 — celle-ci est CONSOMMABLE)

> **1.21.0 ne doit PAS être consommée** : sa publication GitHub
> Packages a été interrompue en plein vol (annulation concurrence au
> re-tag) — le module `index` y est PARTIEL (jar sans pom, non
> résoluble) et host/kotlin/xml y sont absents. Le contenu de 1.21.1
> est IDENTIQUE (chantier 2, ADR 0231) et publié intégralement.

### 1. État

- `main` de codeide-lsp : chantier **2 « indices lambda des littéraux
  de fonction »** (ADR 0231, feuille de route post-P1).
- **Ce que ça change** : `textDocument/inlayHint` rend de NOUVEAUX
  indices sur les lambdas — le `it` IMPLICITE porte un indice au
  libellé COMPLET `it: Type` (kind Parameter, `paddingLeft` ET
  `paddingRight` à true — le fantôme respire entre l'accolade et le
  corps), positionné JUSTE APRÈS l'accolade ouvrante ; chaque
  paramètre EXPLICITE sans type écrit porte un indice `: Type` (kind
  Type) positionné en FIN de nom. CORRECTION de rendu au passage : les
  indices TYPE des déclarations (`val entier = 42` → `: Int`) sont
  désormais positionnés APRÈS le nom (fin d'ancre) — ils se tissaient
  AVANT (`val : Int entier`), contredisant le contrat du genre.

### 2. Ce que CodeIDE doit faire

- **Rien de bloquant** : les indices traversent `inlayHint` existant,
  aucune capability ne change. Le pont `LspInlayHintProvider` honore
  déjà `paddingLeft`/`paddingRight` (le texte tissé gagne ses espaces
  de respiration).
- À VÉRIFIER côté rendu : un indice positionné à une offset donné se
  tisse À cet offset — le rendu `entier: Int = 42` (après le nom) et
  `{ it: View println(it) }` (après l'accolade, avec padding) doit
  être visuellement cohérent ; c'est la première surface qui utilise
  les indices APRÈS ancre côté Kotlin (le Java ECJ le faisait déjà
  pour `var`).

### 3. Scénarios de vérification (démo)

1. `fun repeter(n: Int, action: (String) -> Unit) = Unit` puis
   `repeter(2) { println(it) }` : l'indice `it: String` apparaît en
   pâle juste après l'accolade, `n =` avant le `2`.
2. `val rappel: (Int) -> Unit = { println(it) }` : `it: Int` après
   l'accolade de l'initialiseur.
3. `combiner(1, 2) { x, y -> x + y }` (attente `(Int, Int) -> Int`) :
   `x` et `y` portent `: Int` ; un paramètre déjà typé
   (`{ x: Int, y -> }`) n'en porte pas, `y` garde le sien.
4. Un littéral passé à une fonction INCONNUE (`inconnu(2) { … }`) ne
   rend RIEN (preuve par le consommateur, D3).
5. `val entier = 42` : `: Int` se rend APRÈS le nom (plus jamais
   avant).

## 1.20.0

### 1. État

- `main` de codeide-lsp : chantier **P1-D « fiche classpath au survol,
  KDoc aux signatures »** (ADR 0230, quatrième chantier P1 de la
  feuille de route du rapport d'analyse du 28/10/2026).
- **Ce que ça change** : le survol d'un type du CLASSPATH (Android,
  stdlib) rend une FICHE — `public class android.widget.TextView
  extends android.view.View`, puis `Interfaces : …`, `Constantes : …`
  (enum) ou `Champs publics : Type nom, …` (plafonds 20/8, le marqueur
  `INSTANCE` d'un objet et les synthétiques `$` exclus) ; le genre
  (`interface`/`enum`/`class`/`object`) est prouvé par les drapeaux
  du bytecode. L'aide à la signature accompagne ses candidates PROJET
  de leur KDoc (document courant, autre fichier du projet ouvert ou
  fermé — lecture bornée) ; les candidates du classpath restent sans
  doc (le bytecode n'en porte pas).

### 2. Ce que CodeIDE doit faire

- **Rien** : les deux surfaces traversent les canaux existants
  (`textDocument/hover` — la fiche vit dans la fence `kotlin` avec
  ses sections en markdown dessous ; `textDocument/signatureHelp` —
  le champ `documentation` de `SignatureInformation`, en plaintext,
  déjà transporté). Aucune capability ne change.
- Optionnel (rendu) : le panneau de signature peut afficher la
  documentation sous l'étiquette quand le champ est présent (le
  client cel le rend déjà pour le survol) ; sans le champ, rien à
  faire (jamais vide).

### 3. Scénarios de vérification (démo)

1. Survoler `TextView` dans `val v: TextView = …` (projet Android
   avec android.jar au classpath) : la fiche dit `public class
   android.widget.TextView extends android.view.View` puis
   `Champs publics : …` ; survoler une interface (`CharSequence`) :
   `public interface java.lang.CharSequence` + `Interfaces : …`,
   jamais de `extends`.
2. Survoler le nom d'une enum du classpath : `Constantes : ROUGE,
   VERT, BLEU` (plafond 20 au-delà, « … » en fin).
3. Dans un fichier appelant une fonction KDoc-ée d'un AUTRE fichier
   du projet (ouvert ou fermé), ouvrir la signature de l'appel : la
   KDoc accompagne la candidate dans le panneau ; appeler une méthode
   du classpath (`v.setText(`) : signature seule, jamais de doc
   inventée.

## 1.19.0

### 1. État

- `main` de codeide-lsp : chantier **P1-C « implémenter les membres
  manquants »** (ADR 0229, troisième chantier P1 de la feuille de route
  du rapport d'analyse du 28/10/2026) — premier correctif Kotlin
  AU-DELÀ des imports.
- **Ce que ça change** : sur une classe/objet Kotlin (n'importe où
  dans son en-tête ou son corps), le menu quick-fix propose
  « Implémenter le membre manquant » / « Implémenter les N membres
  manquants » : les stubs des membres abstraits de la clôture de ses
  supertypes, non déjà implémentés — sources PROJET avec noms réels et
  paramètres de type substitués (`Depot<String>` rend
  `fun lire(id: String)`), sources CLASSPATH en vue JVM (getters/
  setters appariés en `val`/`var`), sous garde générique (un membre
  portant l'attribut `Signature` du bytecode est écarté : son rendu
  érasé ne compilerait pas).

### 2. Ce que CodeIDE doit faire

- **Rien d'obligatoire** : l'action traverse `textDocument/codeAction`
  existant (kind QuickFix, SANS diagnostic lié — le moteur n'a pas de
  diagnostic d'exhaustivité d'héritage, l'action est bornée à la
  déclaration englobante). L'édition est un `WorkspaceEdit` standard
  (une seule édition de remplacement, offsets → plages LSP).
- Optionnel (mise en avant) : le quick-fix est offert à TOUTE position
  dans la classe — si le client filtre les code actions par « sur un
  souligné », prévoir une voie d'accès au caret nu (menu bulb/menu
  contextuel) pour que l'action soit atteignable sans diagnostic.

### 3. Scénarios de vérification (démo)

1. Déclarer `interface Depot { fun lire(id: String): String; val
   taille: Int }` puis `class Memoire : Depot { }` — curseur dans la
   classe, menu quick-fix : « Implémenter les 2 membres manquants »
   insère les deux stubs `= TODO()` indentés dans le corps vide.
2. Implémenter partiellement (`override fun lire…` présent) : l'action
   n'offre plus que `taille`, inséré après le dernier membre.
3. `class Repo : Depot<String>` sur une interface générique
   `Depot<T>` : le stub rend `fun lire(id: String): String`
   (substitution) ; `class RepoNu : Depot` (clause nue) ne propose
   RIEN pour le membre générique (muet, D3).
4. Une interface du CLASSPATH (par ex. `View.OnClickListener`) : le
   stub `override fun onClick(p0: View) = TODO()` ; une interface
   générique (`Comparable`) ne propose rien pour son membre générique
   (garde `Signature`).

## 1.18.0

### 1. État

- `main` de codeide-lsp : chantier **P1-B « complétion contextuelle
  vague 1 »** (ADR 0228, deuxième chantier P1 de la feuille de route du
  rapport d'analyse du 28/10/2026).
- **Ce que ça change** : la complétion Kotlin sert trois CONTEXTES
  DÉDIÉS dans les positions à forte valeur de frappe — **arguments
  nommés** de l'appel englobant (`foo(na|` → `nom = `, noms des
  surcharges prouvées, déjà fournis exclus, fusionnés aux noms ordinaires),
  **constantes d'enum** des branches d'un `when` à sujet enum
  (`when (c) { RO|` → `ROUGE`…, contexte exclusif), **attributs
  d'annotation** (`@Anno(lev|` → `level = `, contexte exclusif).

### 2. Ce que CodeIDE doit faire

- **Rien d'obligatoire** : les contextes traversent le canal
  `textDocument/completion` existant. Le nouveau champ
  `insertText` des candidats (« nom = » affiché, `nom = ` inséré) est
  déjà la sémantique du client cel-lsp (insertion du texte quand
  présent, repli sur le libellé) — le séparateur s'insère avec le
  candidat, l'utilisateur n'a plus qu'à taper la valeur.
- Optionnel (mise en avant) : les items `nom =` / `level =` portent le
  kind LSP `Variable` / `Field` — si le popup éditeur affiche des badges
  par kind, ils héritent des glyphes existants, aucun réglage nouveau.

### 3. Scénarios de vérification (démo)

1. Dans un fichier Kotlin du projet, taper un appel de fonction du
   PROJET à plusieurs paramètres puis le préfixe d'un nom de paramètre
   dans la liste d'arguments : la liste de complétion offre `nom =`
   (séparateur inclus) à côté des locales de portée.
2. Dans un `when` dont le sujet est une énumération du projet, placer
   le curseur dans une condition de branche : seules les CONSTANTES de
   l'enum sont offertes (aucun mot-clé) ; dans le corps d'une branche,
   la complétion redevient ordinaire.
3. Sur une annotation du classpath (par ex. `@Deprecated(`), taper le
   préfixe d'un attribut : `level = ` / `replaceWith = ` etc. sont
   offerts, insérés avec «&#160;= ».

## 1.17.0

### 1. État

- `main` de codeide-lsp : chantier **P1-A « diagnostics par déclaration »**
  (ADR 0227, premier chantier P1 de la feuille de route du rapport
  d'analyse du 28/10/2026).
- **Ce que ça change** : sur un document en cours d'édition (marqueurs
  d'erreur de parse), les diagnostics sémantiques ne sont plus éteints
  EN BLOC — seules les déclarations dont le sous-arbre parse proprement
  sont diagnostiquées, avec des gardes d'absence dédiées à
  `kt.unresolved` (nom déclaré dans le document jamais signalé, zone
  package/import brisée muette, en-tête de classe brisé muet en
  absence) et l'écarter des témoins d'arité des surcharges en cours de
  frappe.

### 2. Ce que CodeIDE doit faire

- **Rien** : la garde vit dans le moteur, les diagnostics traversent le
  canal push existant (`textDocument/publishDiagnostics`) sans changement
  de protocole. L'éditeur observera simplement DES DIAGNOSTICS DE PLUS
  pendant la frappe (le comportement attendu : soulignés qui restent
  vivants au lieu de clignoter).
- Optionnel (mise en avant) : le rendu des soulignés pendant la frappe
  peut être revu côté éditeur si un lissage/débounce visuel était
  appliqué pour compenser l'ancien clignotement.

### 3. Scénarios de vérification (démo)

1. Ouvrir un fichier Kotlin propre (des soulignés habituels), puis
   taper une nouvelle déclaration INCOMPLÈTE au bas du fichier
   (`fun nouveau(`) : les soulignés EXISTANTS restent (l'ancien
   comportement les faisait disparaître jusqu'à complétion de la
   syntaxe).
2. Casser une seule méthode dans une classe : les autres membres
   restent diagnostiqués.
3. Un nom référencé dont la DÉCLARATION est en cours de frappe n'est
   jamais souligné non résolu (garde d'absence).

## 1.16.0

### 1. État

- `main` de codeide-lsp : release `v1.16.0`, CI **verte** attendue.
- **Aide de signature robuste + complétion contextuelle** : réponse aux
  deux défauts majeurs du retour du 28/10 (popup de signature vide
  pendant la frappe ; complétion ignorant la position — import, corps
  de classe, clause extends…). Détail complet dans `CHANGELOG.md`
  § 1.16.0.

### 2. Changements de comportement visibles

- `textDocument/signatureHelp` ne rend plus systématiquement vide sur
  le texte en cours de frappe : appels non refermés, chaînes ouvertes
  et arguments à moitié tapés sont servis (variantes réparées en
  interne, aucun changement de protocole) ;
- `new Foo(` énumère maintenant TOUTES les surcharges de constructeur
  (une seule signature avant) ;
- `textDocument/completion` filtre selon la position grammaticale
  (extends/implements/throws/catch/new/@annotation), propose des
  stubs d'override en corps de classe, des noms de variables en
  déclaration (`Button but|`), les constantes d'enum dans les `case`
  et les valeurs d'attributs d'annotation. Les items d'override
  voyage en `insertTextFormat: Snippet` (format déjà consommé par
  les templates live/postfix).

### 3. Actions pour CodeIDE

- Aucune action obligatoire : même version de protocole LSP, même SPI
  `IndexNoms`. Recommandé : monter la dépendance `codeide-lsp` en
  1.16.0 (`scripts/bump-frere.sh lsp minor` depuis CodeIDE).

### 4. Scénarios de vérification

- taper `sb.append(` sans refermer → le popup de surcharges apparaît ;
- taper `add(1, ` (virgule finale) → popup + paramètre 2 actif ;
- `class C extends Runn|` → Runnable absent ; `implements Runn|` →
  présent ;
- `void m() throws Ex|` → Exception oui, Executor non ;
- `@Func|` au-dessus d'une classe → FunctionalInterface, pas Function ;
- `toStr|` dans le corps d'une classe → stub toString() complet ;
- `Button ` (locale ou champ, caret après l'espace) → button, b ;
- `case RE|` dans un switch sur enum → RED ;
- `@Anno(severity = ` (attribut enum) → les constantes.

## 1.15.0

### 1. État

- `main` de codeide-lsp : release `v1.15.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.15.0`), CI **verte**
  attendue sur `main`.
- **Vague 5 du programme utilisateurs** : les items 1 et 2 du backlog
  — les QUATRE mondes du programme sont à **ZÉRO faux positif
  déclaré** : le monde Compose de Léa et le monde KMP de Nina
  rejoignent celui de Marc (leurs contrats E2E sont VIDES : tout
  nouveau FP casse la CI). Détails dans `CHANGELOG.md` § 1.15.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2.

### 2. Changements de comportement visibles

- **Kotlin** : les extensions de **SCOPE Compose** résolvent —
  `Modifier.weight(1f)` dans `Row { … }`, `Modifier.align(…)` dans
  `Box { … }` (le récepteur de portée du lambda, candidates PROJET
  et CLASSPATH, pile des littéraux emboîtés) ; les paramètres
  DESTRUCTURÉS de lambda (`sortedByDescending { (_, total) ->
  total }`) sont en portée ; les propriétés builtins de Map
  (`totaux.entries`, `.keys`, `.values` → `entrySet()`/`keySet()`/
  `values()`) et `buildList { add(…) }` (alias `Mutable*` ADR 0218).
- **Vrais positifs gardés** : un membre inconnu DANS le scope reste
  signalé (`grincher`), un nom inconnu dans un corps de lambda
  destructuré reste signalé (`totalInconnu`).

### 3. API/contrat modifiés

- **AUCUN** : la vague est interne au moteur (résolution, portée
  locale, chaîne des collections). La montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (la section du registre TESTS_MANUELS suit).
2. **Rejouer les scénarios d'appareil des sections 1.13.0/1.14.0 §5**
   (ils restent valides) PLUS : un écran Compose complet avec
   `Row { Text(Modifier.weight(1f)) }` et `Box {
   Modifier.align(Alignment.BottomCenter) }` — aucun diagnostic
   attendu sur fichier correct ; une erreur volontaire dans le
   scope (`Modifier.wight(1f)`) doit rester ROUGE.
3. **Surveiller le backlog partagé** (RETOURS §4) : jar illisible →
   PARTIEL en tête, puis attributs XML, NUS des lambdas Gradle,
   complétion du récepteur de portée et typage des composants
   destructurés.

## 1.14.0

### 1. État

- `main` de codeide-lsp : release `v1.14.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.14.0`), CI **verte**
  attendue sur `main`.
- **Vague 4 du programme utilisateurs** (`cac026a`) : les items 1 à 4
  du backlog — le monde Views/XML de l'utilisateur Marc atteint ZÉRO
  faux positif (le contrat E2E de son test est VIDE : tout nouveau FP
  casse la CI). Détails dans `CHANGELOG.md` § 1.14.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2.

### 2. Changements de comportement visibles

- **Kotlin** : les membres HÉRITÉS du classpath résolvent depuis les
  types du projet (`submitList` nu dans un adaptant, `getString`
  dans une Activity, y compris depuis un objet anonyme — la PILE des
  this) ; les types IMBRIQUÉS des supertypes par nom simple
  (`FilterResults` dans un `object : Filter()`) ; les sélecteurs
  d'accès sûr `results?.values` ne bavent PLUS (bug de frontière
  transversal) ; les paramètres du constructeur primaire sont lus
  dans la zone de construction (`super(contexte, attributs,
  styleDef)`, initialisateurs, `init`) ; `field` et le paramètre des
  setters custom résolvent ; les FQN complets sans import en
  expression (`androidx.recyclerview…LinearLayoutManager(this)`) et
  les imbriqués qualifiés en position de type
  (`ViewModelProvider.Factory`) ; `remember { X() }.membre` après une
  locale générique (`hoteSnackbar.showSnackbar`) ; les propriétés
  synthétiques Java dans `apply { }` (`style =`, `color =` sur Paint)
  et les chaînes de binding (`liaison.texteAnnee.context.getString`).
- **Vrais positifs rattrapés** : un paramètre du constructeur primaire
  utilisé HORS zone de construction (dans un corps de méthode) est
  SIGNALÉ, miroir kotlinc ; le PREMIER segment d'une chaîne qualifiée
  inconnue (`ArticleRepoInconnu.tenter()`) est signalé.

### 3. API/contrat modifiés

- **AUCUN** : la vague est interne au moteur (diagnostic, typage,
  décodage des métadonnées). La montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (la section du registre TESTS_MANUELS suit).
2. **Rejouer les scénarios d'appareil de la section 1.13.0 §5** (ils
   restent valides) PLUS : ouvrir un adaptant RecyclerView complet
   (objets anonymes `Filter`, `submitList`, `super(...)` à trois
   args, `apply` sur Paint) — aucun diagnostic attendu sur fichier
   correct ; un `results?.values` ne doit plus baver.
3. **Surveiller le backlog partagé** (RETOURS §4) : extensions de
   SCOPE Compose (weight/align) en tête, puis destructuring de lambda
   et `Map.entries`.

## 1.13.0

### 1. État

- `main` de codeide-lsp : release `v1.13.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.13.0`), CI **verte**
  sur `main` (chaque lot poussé validé par un run CI vert).
- **Correctifs du programme utilisateurs du 25/10/2026** : trois vagues
  qui éteignent les familles de faux positifs n° 1 du quotidien —
  détails dans `CHANGELOG.md` § 1.13.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md`.

### 2. Changements de comportement visibles

- **Kotlin** : les constructeurs à VALEURS PAR DÉFAUT ne sont plus
  signalés à arité courte ; les data classes résolvent `copy`/
  `componentN` (et les PROPOSENT en complétion) ; `collect` après un
  récepteur typé (`vueModele.effets.collect { }`) ne produit plus de
  faux `kt.unresolved` ; les getters Compose (`MaterialTheme
  .typography`, `Arrangement.spacedBy`), les inline classes manglées
  et les types imbriqués (`Icons.Filled`, `Paint.Cap`) résolvent ;
  `@OptIn`/`@Suppress` et les imports `by` (`getValue`/`setValue`)
  ne sont plus marqués inutilisés.
- **Projets mixtes Java/Kotlin** : les membres des sources JAVA du
  projet résolvent depuis le Kotlin (`getTitre()` → complétion,
  typage en chaîne, propriété synthétique `titre`) ; `R` sans
  import (même paquet) résout.
- **Vrais positifs rattrapés** : un membre inexistant sur un type du
  projet SANS héritage écrit est SIGNALÉ (preuve d'absence projet) —
  comportement plus proche d'Android Studio sur les deux tableaux.

### 3. API/contrat modifiés

- **AUCUN** : les trois vagues sont internes au moteur (diagnostic,
  complétion, typage). Aucune signature publique, aucune entrée
  d'énumération, aucun SPI ne bougent — la montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (fait à la publication de cette note — la ligne
   `codeide-lsp` du catalogue suit).
2. **Rejouer les scénarios d'appareil des quatre mondes** (§5) — les
   fichiers qui bavaient du faux positif ne doivent plus rien
   signaler, les erreurs volontaires restent attrapées.
3. **Surveiller le backlog partagé** (RETOURS §4) : membres hérités du
   classpath (`submitList`/`getString`) en TÊTE, erasure après
   `remember { }`, extensions de scope Compose — les prochaines
   vagues côté lsp.

### 5. Scénarios d'appareil

1. **Compose** : ouvrir un écran avec data class (`data class
   Article(...)`) — `article.copy(...)` passe sans diagnostic, la
   complétion après `article.` propose `copy` ; un `collect { }` sur
   un `StateFlow` du ViewModel reste muet.
2. **Mixte Java/Kotlin** : depuis un `.kt`, utiliser un champ/méthode
   d'une classe `.java` du projet — complétion et survol vivants.
3. **Constructeur à défauts** : appeler un constructeur Compose sans
   ses paramètres optionnels — aucun « 4 fourni(s), 6 attendu(s) ».
4. **Vrai positif** : taper `article.prixInconnu` — SIGNALÉ (le type
   est du projet, sans héritage écrit).

## 1.3.0

### 1. État

- `main` de codeide-lsp : **6735890** (à la rédaction — voir le SHA réel au
  moment de la publication), CI **verte** sur `main` (chaque lot poussé a
  été validé par un run CI vert ; l'historique des runs est public).
- **Non publié** : lancer `scripts/bump-frere.sh lsp minor` **depuis
  CodeIDE** (version attendue : **1.3.0** — `version.properties` reste à
  1.2.0, le bump est l'affaire du script frère). Le dépôt est laissé
  PROPRE : tout commité, `main` à jour, CI verte.

### 2. Capacités par langage (avant → après)

Les interrupteurs de l'écran « Intelligence du code » sont alimentés par la
table `CapacitesAnnoncees` (dérivée des `ServerCapabilities` réelles,
parité verrouillée par test) :

| Interrupteur (`CapaciteLsp`) | Java | Kotlin | XML |
|---|---|---|---|
| COMPLETION, SURVOL, DIAGNOSTICS, DEFINITION, RENOMMAGE, ACTIONS_RAPIDES | ✅ (inchangé) | ✅ (inchangé) | ✅ (inchangé) |
| SIGNATURES | ✅ | ✅ | ❌ (inchangé) |
| REFERENCES | ✅ | ✅ | ✅ **NOUVEAU 1.3.0** |
| FORMATAGE | ✅ | ✅ **NOUVEAU 1.3.0** | ✅ (inchangé) |
| INDICES_EN_LIGNE | ✅ | ✅ **NOUVEAU 1.3.0** | ❌ |

Capacités NON réglables (annonces serveur pures, sans interrupteur) :

| Capacité | Java | Kotlin | XML |
|---|---|---|---|
| Symboles de document, pliage, surlignage, prepareRename | ✅ | ✅ | ✅ |
| Définition de type, implémentations | ✅ | ✅ **NOUVEAU** | ❌ |
| Formatage de plage | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Symboles d'espace (`workspace/symbol`) | ✅ | ✅ **NOUVEAU** | ❌ |
| Plage de sélection | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Édition liée (balises) | ✅ | ❌ | ✅ **NOUVEAU** |
| Couleurs de document | ✅ | ❌ | ✅ **NOUVEAU** |
| Formatage à la frappe, lentilles, hiérarchies d'appels/types | ✅ | ❌ | ❌ |
| Jetons sémantiques | ✅ | ❌ (non annoncé — D3) | ❌ |
| Super-définition, source décompilée (expérimentales) | ✅ | ❌ | ❌ |

### 3. Changements de comportement visibles

- **Diagnostics Kotlin/XML rafraîchis sur modification externe** : un
  fichier `.kt`/`.kts`/`.xml` modifié hors éditeur (build Gradle,
  terminal) rafraîchit les diagnostics des documents OUVERTS de ces
  langages (lot L1) — auparavant, seul Java en bénéficiait ; le
  rafraîchissement n'attendait plus que l'enregistrement.
- **Kotlin** : « Formater le document » ré-indente (ré-indentation seule,
  ADR 0202 — le contenu n'est jamais réécrit) ; de nouveaux indices en
  ligne (noms de paramètres, types certains) apparaissent si l'éditeur
  les rend ; la palette « aller au symbole » et l'expansion de sélection
  deviennent disponibles côté serveur.
- **XML** : « Trouver les références » sur `@id/…`, `@string/…`, etc.
  fonctionne (déclarations + usages) ; le surlignage d'occurrences
  couvre les ressources ; couleurs et édition liée prêtes côté serveur.
- **Aucune capacité n'a cessé d'être annoncée** (L4 n'a rien retiré —
  aucun stub trouvé côté Java).

### 4. API/contrat modifiés

- **Aucune rupture** attendue : `ConfigurationLsp`, `SessionLsp*` et
  `CapaciteLsp` gardent leurs signatures.
- AUCUNE entrée d'énumération `CapaciteLsp` n'a été AJOUTÉE en 1.3.0 :
  `FORMATAGE`, `INDICES_EN_LIGNE` et `REFERENCES` existaient déjà —
  seules les TABLES par langage (`CAPACITES_KOTLIN`, `CAPACITES_XML`)
  gagnent des entrées. **Le miroir `core:domain` de CodeIDE n'a RIEN à
  mettre à jour pour l'énumération** ; en revanche, les écrans de
  réglages afficheront trois NOUVELLES lignes d'interrupteur effectives
  (REFERENCES pour XML, FORMATAGE et INDICES_EN_LIGNE pour Kotlin) sans
  changement de code si la liste est dérivée de `capacites(langage)`
  (elle l'est — LSP_REVUE_REGLAGES du lot LSP-1).
- Moteurs Kotlin/XML : le contrat de session `SessionLangageKotlin`/
  `SessionLangageXml` gagne des méthodes AVEC DÉFAUTS HONNÊTES VIDES
  (formatage, navigation de types, indices, symboles d'espace, sélections
  / références, surlignage, plage liée, couleurs) — un pack 1.2.0 face à
  un hôte 1.3.0 dégrade proprement (tolérance de version D6, aucun crash).

### 5. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh lsp
   minor` (calcule 1.3.0, commit, tag `v1.3.0`, push, attend
   `publish.yml`, vérifie le registre, met à jour le catalogue).
2. **Vérifier les écrans de réglages LSP** : trois nouvelles lignes
   d'interrupteur effectives (voir tableau §2) — vérifier libellés et
   état par défaut.
3. **Ajuster les textes « non pris en charge »** pour Kotlin/XML : le
   formatage Kotlin, les indices Kotlin et les références XML sont
   désormais servis — tout texte statique qui dit le contraire doit
   être retiré.
4. **Exécuter les scénarios d'appareil** (§8 ci-dessous).

### 6. Dépendances avec les autres agents

- **code-editor 3.74.0** (agent 10-a) : le formatage de PLAGE n'est
  consommé qu'une fois 3.74.0 livrée et montée (le serveur la sert dès
  1.3.0 pour les trois langages). Sans 3.74.0 : le formatage du document
  ENTIER fonctionne déjà.
- **Utilisable dès maintenant avec le client actuel** : formatage Kotlin
  du document entier, références/surlignage XML, indices Kotlin (le
  client les consomme déjà pour Java), rafraîchissement des diagnostics
  sur modification externe.
- **Phase 2 CodeIDE** : les capacités listées dans
  `docs/LSP_ECARTS_CLIENT.md` (palette, sélection, couleurs, édition
  liée, lentilles, hiérarchies, formatage à la frappe, méthodes
  expérimentales) attendent leur consommation client — chaque fiche
  décrit l'API minimale à ajouter.

### 7. Capacités en service côté serveur mais hors service côté éditeur

Voir **[docs/LSP_ECARTS_CLIENT.md](LSP_ECARTS_CLIENT.md)** : formatage de
plage (jusqu'à 3.74.0), formatage à la frappe, plage de sélection, palette
`workspace/symbol`, lentilles de code, hiérarchies d'appels et de types,
couleurs de document XML, édition liée XML, super-définition et source
décompilée (expérimentales, Java). Chaque fiche : requête LSP, serveur,
effet utilisateur, API minimale côté client.

### 8. Scénarios d'appareil

1. **Modification hors éditeur** : ouvrir un `.kt` et un `.xml` dans
   l'éditeur ; depuis un terminal (ou un build Gradle), modifier le
   fichier sur disque ; les diagnostics des documents ouverts se
   rafraîchissent SANS enregistrement (les trois langages).
2. **Formater un `.kt`** : ouvrir un fichier Kotlin mal indenté (corps
   décalé, chaîne brute mal alignée) ; « Formater le document » → la
   ré-indentation s'applique, le contenu de la chaîne brute reste
   INTACT ; reformater ne change rien (idempotence).
3. **Références d'un `@id/…` XML** : ouvrir un layout, mettre le caret
   sur `@+id/valider` puis sur un `@id/valider` ; « Trouver les
   références » → la déclaration et les usages (y compris dans les
   autres documents ouverts) sont listés ; le surlignage couvre les
   occurrences du document courant.
4. **Changement rapide d'onglet Java → Kotlin → XML** : ouvrir les
   trois, basculer rapidement ; chaque langue répond (complétion,
   survol, diagnostics) sans latence perceptible ni fuite (le test de
   fuite de tâches est dans la suite).
5. **Indices Kotlin** : écrire `somme(1, 2)` sur une fonction
   `fun somme(a: Int, b: Int)` déclarée dans le projet ; les indices
   `a =`/`b =` s'affichent devant les arguments si l'éditeur rend les
   inlay hints (déjà consommés pour Java).
