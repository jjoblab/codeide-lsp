# LSP XML — cadrage de mission (v0.109.0)

> Document de cadrage. Mission démarrée sur « GO » du propriétaire. Étude
> comparative préalable : **projet de référence** `lang-xml` (2 858 lignes) — enseignements
> d'architecture uniquement (GPL), aucune réutilisation de code.

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

Syntaxe (balise non fermée, attribut dupliqué, `&` non échappé) ;
`xmlns:android` manquant sur la racine d'un layout ; `layout_width`/`layout_height`
absents sur un élément de layout (hint, pas error — conservatoire) ;
`@+id` dupliqué dans le fichier ; `<include>` sans `layout` ; `<merge>` non
racine ; entrée `res/values` sans `name` ou `name` dupliqué ; référence
`@color/…`/`@string/…` non résolue (projet + framework, jamais pendant que
l'index est froid — même garde « dumb mode » que projet de référence).

## 5. Étapes de livraison (indicatif)

| Étape | Contenu | Version cible |
|-------|---------|---------------|
| XE1 | Parseur tolérant + DOM + diagnostics syntaxiques + session LSP minimale. | v0.116.0 |
| XE2 | Catalogue plateforme (attrs/public.txt) + complétion balises/attributs. | v0.117.0 |
| XE3 | Index ressources projet + complétion valeurs + résolution `@ref` + hover + definition + règles X4. | v0.118.0 |
| XE4 | Manifest + values (styles/themes) + quick-fixes (insérer xmlns, renommer « did you mean »). | v0.119.0 |

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
