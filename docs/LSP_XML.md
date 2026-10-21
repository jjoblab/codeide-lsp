# LSP XML — cadrage de mission (v0.109.0)

> Document de cadrage. Mission démarrée sur « GO » du propriétaire. Étude
> comparative préalable : **projet de référence** `lang-xml` (2 858 lignes) — enseignements
> d'architecture uniquement (GPL), aucune réutilisation de code.
>
> Versions cibles ORIGINELLES décalées d'un cran après l'exécution (K5 a
> consommé v0.116.0) : XE1 → v0.117.0, XE2 → v0.118.0, XE3 → v0.119.0,
> XE4 → v0.120.0.

## 1. Objectif et contexte

Les fichiers XML d'un projet Android sont la **majorité silencieuse** de ce
que l'utilisateur édite : layouts, valeurs (strings/colors/dimens/styles),
manifest, drawables, menus. Aujourd'hui CodeIDE les édite en texte brut. La
mission vise un LSP XML **in-process**, « propre, robuste et professionnel »
(terminologie du propriétaire), avec l'expérience attendue d'Android Studio :
complétion contextuelle (balise, attribut, valeur), diagnostics en temps
réel, résolution des références `@string/…`/`?attr/…`, hover sur les
ressources.

Le XML Android est un cas particulier du XML générique : la sémantique
(widgets, attributs hérités, namespaces `tools:`, références de ressources)
ne vient **pas** d'un schéma XSD mais des métadonnées de la plateforme SDK
et des ressources du **projet**. C'est ce qui rend les serveurs génériques
peu adaptés, et ce qui oblige à brancher le LSP sur notre modèle de
ressources existant (ADR 0113, modèle AGP v2).

## 2. Enseignements de l'étudu projet de référence

`lang-xml` du projet de référence (2 858 lignes, aucune dépendance Eclipse) :

- **Parseur tolérant écrit main** : ne lève jamais sur un buffer à moitié
  tapé ; les balises non fermées/incohérentes se rétablissent par une pile de
  noms ouverts avec diagnostics. Un parseur strict (XmlPullParser compris)
  est inutilisable pour l'édition : il faut du tolérant.
- **DOM neutre** : les fonctionnalités de l'éditeur ne dépendent d'aucun
  parseur concret (même contrat que nos autres langages).
- **Séparation règles / hôte** : les *règles* sont des fonctions pures sur le
  DOM (namespace manquant, chaîne codée en dur, `layout_width`/`layout_height`
  manquants, attribut dupliqué, `@+id` dupliqué, `<include>` sans layout,
  `<merge>` non racine, `<fragment>` sans classe, entrée `res/values` sans
  `name` ou dupliquée) ; les deux questions de *schéma* (quels attributs un
  élément admet ; un tag nomme-t-il une classe existante) sont des coutures
  hôtes **conservatrices par construction** (« indéterminé » = rien signalé).
- **Complétion par contributeurs** : un scanner de contexte décide où est le
  caret (nom de balise, nom d'attribut, valeur d'attribut) et un service
  fusionne les candidats de contributeurs injectés — la connaissance Android
  (hiérarchie de widgets, héritage d'attributs, références de ressources)
  vit dans un contributeur piloté par les métadonnées du SDK et l'index
  des ressources, pas dans le parseur.
- **Lemminx écarté** : aucune trace d'Eclipse Lemminx ni de XSD — le poids de
  la plateforme Eclipse sur ART et le schéma non-XSD d'Android rendaient la
  voie générique inférieure.

## 3. Décisions

| # | Décision | Motif |
|---|----------|-------|
| X1 | **Moteur maison in-process**, module `:lsp:xml`, parseur tolérant écrit main (~1 200–1 800 lignes attendues). Pas de Lemminx, pas de XSD. | projet de référence le prouve à échelle réduite ; sémantique Android non schématisable ; poids Eclipse inutile. |
| X2 | **DOM neutre partagé** avec les sessions Java/Kotlin (même forme de contrat de session : parse → diagnostics → services), branché sur SessionProjet/RegistreFermeture/LSP4J. | D4 de LSP_KOTLIN (réutilisation du socle) ; un seul cycle de vie à tester. |
| X3 | **Complétion par contributeurs** : catalogue Android (balises, attributs, formats) dérivé des **données de plateforme installées** (`data/res/values/attrs.xml` + `public.txt` de `platforms/android-*`, déjà téléchargées par l'installateur) ; index des ressources du **projet** (strings/colors/dimens/ids/styles) construit depuis le modèle v2. | Connaissance Android = données, pas code ; zéro durcissement de version SDK. |
| X4 | **Règles pures + coutures conservatrices** (motif projet de référence) : les diagnostics ne signalent que ce qu'ils savent ; « classe de balise inconnue » seulement si le SDK + le classpath du projet + l'index sont tous d'accord pour dire qu'elle n'existe pas, jamais à chaud. | Anti faux-positif ; cohérent avec la philosophie W de LSP_JAVA. |
| X5 | **Résolution de références** `@type/name` et `?attr/` dans le hover/definition/complétion via l'index ressources (X3) ; `definition` saute au `res/values` déclarant. | L'attente n°1 après la complétion. |

## 4. Architecture

### 4.1 Structure du module

```
:lsp:xml
  parse/       Parseur tolérant (pile de noms ouverts, diagnostics de récupération)
  dom/         Arbre neutre XmlElement/XmlAttribute + plages
  context/     Scanner de position caret (balise/attribut/valeur/section)
  catalog/     Catalogue plateforme : attrs.xml/public.txt → hiérarchie + formats
  resources/   Index ressources projet (type → nom → localisation déclarante)
  rules/       Diagnostics purs (namespace, ids, includes, merge, values…)
  completion/  Fusion des contributeurs (catalogue + ressources + motifs)
  session/     Adaptateur LSP4J sur SessionProjet
```

### 4.2 Pipeline et tolérance

Document → tokenisation (lexer XML tolérant : entités, quotes non fermées,
commentaires) → parse (pile de noms, rejet des plages incohérentes avec
diagnostic de récupération) → DOM + diagnostics syntaxiques → règles X4 →
publication LSP4J. La re-parse est **incrémentale par élément** : une
édition ne re-tokenise que la région touchée (le XML est naturellement
régionalisable). Le catalogue plateforme est chargé paresseusement (une fois
par session, empreinte de version SDK), l'index ressources projet est
incrémental (même moteur de segments que ADR 0111, clés `res:<type>:<name>`).

### 4.3 Complétion (position → candidats)

- **Nom de balise** (enfant possible d'un parent connu) : widgets du
  catalogue compatibles avec le parent (ConstraintLayout enfant linéaire
  etc. dans un premier temps : tous les View/ViewGroup du SDK + balises
  spéciales `<include>`/`<merge>`/`<fragment>` si le parent l'admet) ;
  classes `View` du **projet** (classpath v8 : types héritant de View —
  réutilise l'index de classes du LSP Java !).
- **Nom d'attribut** : attributs du styleable de l'élément + hérités par la
  chaîne de super-classes + `xmlns` déjà déclarés ; distinction
  `android:`/`app:`/`tools:`.
- **Valeur d'attribut** : selon le format (enum/flag du catalogue ; référence
  `@type/` → noms de l'index projet + framework ; dimension/couleur →
  extraits de valeurs).
- Manifest, `res/values`, drawables : mêmes moteurs, catalogues distincts
  (périmètre : layout + values en premier, manifest ensuite).

### 4.4 Diagnostics initiaux

> **État 1.3.0 (lot L3 du plan 1.3.0, 21/10/2026)** — le serveur XML annonce et
> sert désormais, en plus du socle historique (complétion, survol, définition,
> actions de code, symboles, pliage, renommage borné, formatage) :
> **références** (`references`) et **surlignage d'occurrences**
> (`documentHighlight`) pour `@id/…`, `@+id/…`, `@string/…`, `@color/…`,
> `@dimen/…`, `@style/…`, `@drawable/…`, `?attr/…` — MÊME preuve que le
> renommage borné (déclarations de l'index, tampon ouvert puis disque, en
> ÉCRITURE ; références des documents ouverts en LECTURE, la déclarante `@+id`
> comptant comme écriture) ; **formatage de plage** (`rangeFormatting` —
> ré-indentation des seules lignes de la plage) ; **édition liée**
> (`linkedEditingRange` : nom d'ouvrante ↔ nom de fermante du même élément) ;
> **plage de sélection** (`selectionRange` : chaîne DOM du plus profond —
> attribut — au fichier entier) ; **couleurs de document** (`documentColor`/
> `colorPresentation` : littéraux `#RGB`/`#ARGB`/`#RRGGBB`/`#AARRGGBB` et
> références `@color/…` RÉSOLUES par l'index — le client ne les consomme pas
> encore, voir docs/LSP_ECARTS_CLIENT.md). Réglable : REFERENCES entre dans
> `CapacitesAnnoncees`. Preuves : tests moteur (ReferencesXmlTest,
> FormatagePlageSelectionXmlTest, CouleursDocumentXmlTest) et transport réel
> (XmlLanguageServerL3Test).

Syntaxe (balise non fermée, attribut dupliqué, `&` non échappé) ;
`xmlns:android` manquant sur la racine d'un layout ; `layout_width`/`layout_height`
absents sur un élément de layout (hint, pas error — conservatoire) ;
`@+id` dupliqué dans le fichier ; `<include>` sans `layout` ; `<merge>` non
racine ; entrée `res/values` sans `name` ou `name` dupliqué ; référence
`@color/…`/`@string/…` non résolue (projet + framework, jamais pendant que
l'index est froid — même garde « dumb mode » que projet de référence).

## 5. Étapes de livraison

| Étape | Contenu | Version cible |
|-------|---------|---------------|
| XE1 ✅ | **Livré v0.117.0** : parseur tolérant écrit main (~600 lignes, pile de noms ouverts, reprise fermante incohérente/orpheline, guillemet en frappe SANS marqueur à chaud) + DOM neutre (XmlElement/XmlAttribute + plages, parents résolus top-down) + recouvrements `xml.tagNonFermee`/`xml.tagOrpheline`/`xml.tagFermanteIncoherente`/`xml.guillemetNonFerme` + règles de structure pures (génériques : attribut dupliqué, esperluette non échappée texte+valeurs ; layout : xmlns manquant si `android:` utilisé, tailles en AVERTISSEMENT, `@+id` dupliqué, `<include>` sans layout, `<merge>` hors racine ; values : entrée sans `name`, `name` dupliqué par type) + genre déduit de l'URI + session LSP minimale (SPI `MoteurLangageXml` + ServiceLoader + descripteur de pack) + façade `SessionLspXml` et adaptateur `XmlLanguageServer` dans `:lsp:host` + éditeur trilingue (garde par langue, définition cel-lsp `xml`, registre ADR 0114) + classpath neutre stocké (XE2/XE3 le consommeront) ; corpus LayoutLib 65 fichiers re-parsé en test (0 recouvrement, auto-skip sans frère) ; 67 tests module, kover 91,1 % ligne, jar 80 Ko zéro dépendance. | v0.117.0 |
| XE2 ✅ | **Livré v0.118.0** : scanner de POSITION du curseur (lexical depuis le dernier `<`, respect des quotes — un `>` dans une valeur ne clôt pas la balise ; zones nom de balise / nom d'attribut / valeur / muet, parent et élément porteur retrouvés dans le DOM) + **catalogue plateforme** (`catalog/` : `data/widgets.txt` — vues + chaîne de super-classes ; `data/res/values/attrs.xml` — styleables + registre global formats/énumérations/fanions, PARSÉ PAR NOTRE PROPRE parseur tolérant, chargé UNE fois par plateforme via un cache partagé du moteur, budget §6 mesuré) + **complétion balises/attributs** (`completion/` + scoreur `ClassementXml` : balises = toutes les vues + spéciales, fréquentes SDK d'abord ; attributs = styleables de la chaîne de classes + `layout_*` du PARENT (sémantique Android) + `tools:` fixes + déclarations `xmlns:` en frappe ; filtrage préfixe avec/sans deux-points) ; session (genre LAYOUT seul à XE2, X4), serveur LSP4J (déclencheurs `<` `=` `:`, CompletionItemKind/sortText) ; 108 tests module, kover ≥ 80 %, vrai plateforme SDK en test optionnel auto-skippé. | v0.118.0 |
| XE3 ✅ | **Livré v0.119.0** : **index ressources projet** (`resources/IndexRessources` : scan des `res/` des racines sources — entrées nommées de values (le TAG décide le type, le `name` le nom, le déclarant pointe la plage), ressources de dossier (layout/drawable/menu/mipmap/…, le FICHIER est le déclarant) ; incrémental par mtime (un fichier inchangé n'est jamais re-parsé, un fichier changé perd ses anciennes entrées, un fichier disparu est purgé), partagé par le moteur) + **résolution de références** (`resolution/ReferencesXml` : `@type/nom`, `@+id/` déclarante, `@android:`/`@*android:` framework, `?attr/` et sa forme courte ; garde X4 — déclarante jamais signalée, framework consultée sur public-final.xml (3 515 ressources publiques parsées par notre parseur), index froid = dumb mode silencieux) + **complétion de VALEURS** (`@` → types du projet puis noms filtrés, `@android:` → types/noms du framework, `?attr/` → attributs du projet, énumérations/fanions du catalogue quand l'attribut est connu) + **SURVOL** (référence : `string « hello » — projet, strings.xml` / framework / déclarée ici ; balise : `class android.widget.TextView` ; attribut : format + enums) + **DÉFINITION** (saut au `res/values` déclarant, projet seul — la cible framework n'a pas de source locale) + **règle `xml.referenceNonResolue`** (couture index+catalogue, garde dumb mode) ; 130 tests module, serveur : survol/définition déclarés et traduits. | v0.119.0 |
| XE4 ✅ | **Livré v0.120.0** : **règles de manifest** (`xml.manifestSansPackage` — racine sans `package` ; `xml.composantSansNom` — activity/activity-alias/service/receiver/provider d'`<application>` sans `android:name`, genre MANIFEST déduit de l'URI) + **règle `xml.tagInconnu`** (couture conservatrice X4 : catalogue plateforme ET vue de noms du classpath TOUS d'accord pour dire que la classe n'existe pas, sinon silence — jamais à chaud ; balises spéciales et qualifiées jamais signalées) + **complétion du genre VALUES** (balises d'entrées nommées + `item` sous un style ; attributs `name`/`parent`/`translatable`/`format` ; valeur de `parent=` → styles du projet par l'index puis thèmes framework `@android:style/` sur public-final ; manifest sans complétion — règles seules) + **QUICK-FIXES `textDocument/codeAction`** (éditions neutres `CorrectifXml` traduites par l'hôte : `xml.manqueXmlnsAndroid` → insertion `xmlns:android` juste après le nom de racine ; `xml.tagInconnu` → « did you mean » par préfixe commun puis Levenshtein bornée, plafond 4, ouvrante ET fermante renommées) ; 152 tests module, 71 hôte, codeActionProvider déclaré. | v0.120.0 |
| XE5 ✅ | **Livré v0.121.0** (suite de mission demandée par le propriétaire) : **genres DRAWABLE/MENU** déduits de l'URI (`res/drawable*`, `res/menu*`, qualifiers compris) + **complétion drawables** (`CompletionDrawablesXml` — racines shape/selector/layer-list/ripple/vector/inset/bitmap/clip/scale/rotate/animation-list/level-list/transition/animated-vector, ENFANTS selon le parent (corners/gradient/solid/stroke/padding/size sous shape ; group/path/clip-path sous vector ; item sous selector/layer-list/ripple/… ; drawable direct sous enveloppes), attributs PAR BALISE parent-conscients pour `item` (états de selector, calques de layer-list, frames d'animation-list, niveaux de level-list), énumérations fixées (shape, type, tileMode, clipOrientation, fillType, strokeLineCap/Join, booléens) — X4 : parent inconnu = silence) + **complétion menus** (`CompletionMenusXml` — hiérarchie menu → item/group → sous-menu, trinité id/title/icon + showAsAction/actionViewClass… avec préfixe `app:` AppCompat, FANIONS showAsAction, énumérations menuCategory/checkableBehavior) + **fusion de valeurs** avec le contributeur XE3 (références `@drawable/…` projet + catalogue, dédoublonnage par libellé) + **règles étendues** (xmlns manquant et ids dupliqués aux nouveaux genres, quick-fix xmlns inchangé) ; 188 tests module, aucun changement de contrat (pack 0.121.0). | v0.121.0 |
| XE6 ✅ | **Livré v0.134.20** (lot P3 du plan de stabilisation §3.3 S3, racine de XP1) : **INDEX DES RESSOURCES DE DÉPENDANCES** — les `res/` des ARCHIVES AAR du classpath (provenance v8 `ClasspathEntry.aar`, collectée par la façade `aarsDu` sur tous les modules actifs, épurée des disparus, dédoublonnée, triée) sont indexés depuis les CONTENEURS ZIP mêmes (`IndexRessources.definirAar` : listage + parse à la volée du texte des values, budget chemin+mtime — un AAR inchangé n'est JAMAIS re-décodé ; AAR disparu = entrées purgées ; AAR illisible = sous-rapport ET doute marqué). Fidélité aapt dans le values des libs : le TAG décide le type, un `<attr>` porté par un `declare-styleable` est une ressource attr RÉELLE, un `<item type="…" name="…">` au niveau resources crée la ressource du type nommé. **Cartes séparées projet/lib** : le projet PRIME (résolution, survol, complétion, définitions), la bibliothèque COMBLE l'existence et porte son détail (nom du conteneur déclarant). **Retour de `xml.referenceNonResolue` à l'ERREUR légitime** (ADR 0120 — D-X1 assumée), message « (projet, bibliothèques et framework) », AVERTISSEMENT sous doute ; garde dumb mode et références déclarantes inchangées. **Complétion des dépendances** : types `@` fusionnés (projet ∪ bibliothèques, détail distingué), noms `@type/…` projet puis lib (dédup — le projet gagne), `?attr/…` projet puis lib (les attrs Material/AppCompat apparaissent — attrs de styleable compris), `parent=` des styles : projet puis BIBLIOTHÈQUE (les thèmes `Theme.Material3.*` sont enfin proposés) puis framework. **Survol lib** : « type « nom » — bibliothèque, material-1.12.0.aar » ; **définitions PROJET seul** (D3 : jamais d'URI factice vers un conteneur). Session (`definirClasspath`) pousse le volet lib, classe `ClasspathXml` étendue du champ `racinesAar` (défaut vide — rétrocompatible). 218 tests module (4 AAR index + 5 session P3), 2 tests hôte (collecte AAR du modèle) ; AAR réel du cache Gradle éprouvé à la main (Material 1.12). | v0.134.20 |

## 6. Cibles non fonctionnelles

- Re-parse d'une édition : < 10 ms (fichier ≤ 1 500 lignes).
- Complétion à chaud : < 50 ms.
- Catalogue plateforme chargé une fois par session (< 300 ms, paresseux).
- kover ≥ 80 % ligne sur `:lsp:xml`.

## 7. Risques et parades

| Risque | Parade |
|--------|--------|
| Dérive du catalogue attrs.xml entre plateformes (31/36… ) | Catalogue versionné par API, chargé depuis la plateforme du projet (compileSdk), pas depuis une constante. |
| Tolérance du parseur (fauxDOM) | Suite de corpus : fichiers de test cassés volontairement + le corpus de 50 layouts de LayoutLib (réutilisation directe des fixtures de validation). |
| Complétion bruyante (trop de candidats) | Classement : contexte parent + fréquence SDK + préfixe (scoreur partagé du LSP Java). |
| Qualité des données public.txt incomplet | Repli : attrs.xml brut ; rien n'est signalé sur ce qui manque (X4 conservatoire). |

## 8. Traçabilité

- Remplace ROADMAP « Phase 5 » (pas de sous-section XML — ajoutée par la
  même occasion).
- Réutilise : SessionProjet/I1-I7 (CYCLE_DE_VIE), index segments ADR 0111,
  classpath v8 ADR 0113, LSP4J, routage `projet_cible` v0.106.0.
- Alimente `docs/PREVIEW.md` §3 (le même DOM/index sert de source au
  layout editor : le panneau preview et le LSP partagent l'index ressources).
- Les 50 layouts de `validation/` de LayoutLib deviennent fixtures partagées
  (parseur ET preview).
