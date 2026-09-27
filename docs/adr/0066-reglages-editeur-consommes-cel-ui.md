# ADR 0066 — Réglages de l'éditeur consommés par cel-ui, écran en trois cartes

Date : 2026-09-27 · Étape : v0.37.0 (achèvement de la section Éditeur, ADR 0059)

## Contexte

La section Éditeur des Paramètres (ADR 0059) était née « persistée avant
consommation » : six réglages vivaient dans `AppSettings` et le DataStore,
mais **aucun n'atteignait l'éditeur**. `EditorActivity` appliquait seulement
un thème cel clair/sombre suivant le mode de l'application ; la taille de
police, le retour à la ligne et la sauvegarde automatique restaient lettres
mortes, et trois d'entre eux (numéros de ligne, surlignage de la ligne
courante, taille de tabulation) ne pouvaient même pas être honorés — la
bibliothèque `cel-ui` 3.37.0 dessine TOUJOURS la gouttière et le bandeau de
ligne courante (aucun setter), et l'indentation unitaire est auto-détectée
par fichier (`IndentDetection`).

En sens inverse, la bibliothèque exposait des capacités réelles qu'aucun
réglage ne pilotait : neuf thèmes embarqués (`EditorTheme.dark()` …
`nord()`), minimap (`setMinimapEnabled`), caractères non imprimables
(`setShowNonPrintable`), ligatures (`setFontLigatures` — avec le compromis
documenté : activer coupe la coloration syntaxique), badges de diagnostic
(`setDiagnosticChipsEnabled`) et zoom (`setFontScale`, base 14 sp, bornes
[0,6 ; 2,6]).

L'utilisateur demande l'achèvement : la section doit refléter ce que le
moteur sait réellement faire, avec un design propre et professionnel.

## Décision

1. **Chaque contrôle visible est branché sur une API publique réelle de
   cel-ui** — plus aucun réglage fantôme :
   - `editorTaillePolice` → `EditorView.setFontScale` (0,85 / 1,0 / 1,2) ;
   - `editorRetourLigne` → `setWordWrap` ;
   - `editorSauvegardeAuto` → garde de `marquerModifie` (l'onglet reste
     sale jusqu'à un enregistrement manuel quand elle est coupée) ;
   - nouveaux : `editorThemeEditeur` (`ThemeEditeur`, dix entrées — AUTO
     + les neuf fabriques), `editorMinimap`,
     `editorCaracteresNonImprimables`, `editorLigatures`,
     `editorChipsDiagnostics`.
2. **Les trois réglages jamais honorables sont retirés** (champs du modèle,
   actions du ViewModel, clés DataStore, chaînes, enum `TailleTabulation`)
   plutôt que maintenus morts : les clés orphelines sur disque sont ignorées
   par la projection tolérante (aucune migration nécessaire). Ils seront
   réintroduits si la bibliothèque expose un jour les setters
   correspondants — piste notée dans le CHANGELOG.
3. **Consommation au fil de l'eau par un détenteur process-wide** :
   `OptionsEditeur` (feature:editor), même patron que `OptionsTooling`
   (ADR 0059/0057) — une seule collecte `ObserveSettingsUseCase` en tâche
   de fond, exposée en `StateFlow`. `EditorActivity` collecte à
   `Lifecycle.State.STARTED` et applique les setters (idempotents) à chaque
   émission : un changement dans les Paramètres se voit **à l'éditeur
   ouvert**, sans recréation. Les thèmes cel sont mis en cache par valeur
   (dix instances au plus — les fabriques allouent à chaque appel).
   `themeActuel()` délègue à `OptionsEditeur.themePour(nuit)` : AUTO garde
   le comportement historique (clair/sombre de l'application), un thème
   forcé l'ignore — comme le sélecteur d'éditeur d'Android Studio.
4. **Écran en trois cartes à libellé de section** — Apparence de l'éditeur
   (thème, taille de police, ligatures), Affichage (retour à la ligne,
   minimap, caractères non imprimables, badges de diagnostic), Édition
   (sauvegarde automatique) — même langage que le maître des Paramètres :
   une `MaterialCardView` par section portant un unique conteneur vertical
   (ADR 0064), rangées à icône + libellé + légende `BodySmall`, sélecteur
   de thème en dialogue à choix unique (patron « licence par défaut »),
   sous-titre de la rangée maître devenu dynamique (« {thème} · {police} »,
   pattern Terminal).

## Alternatives écartées

- **Modifier la bibliothèque code-editor** pour exposer
  `setShowLineNumbers` / bandeau de ligne / largeur de tabulation : deux
  dépôs à coordonner et une version JitPack à publier pour trois bascules —
  le coût dépasse le bénéfice immédiat ; les pistes restent ouvertes côté
  bibliothèque.
- **Garder les réglages fantômes « pour plus tard »** : un interrupteur qui
  ne fait rien est un bug d'UX, pas une réserve de fonctionnalité.
- **Injecter `ObserveSettingsUseCase` dans `EditorViewModel` et pousser les
  réglages dans l'état** : l'état de l'espace de travail est déjà vaste
  (onglets, arborescence, panneau, tooling) ; le détenteur découple la vue
  cel du flux de réglages et sert aussi la garde de sauvegarde en lecture
  ponctuelle.

## Conséquences

- Le section Éditeur devient la deuxième section « vivante » après le
  Terminal ; l'ADR 0059 vieillit en conséquence (les réglages ne sont plus
  « persistés avant consommation » — mise à jour du KDoc d'`AppSettings`).
- `EditorTheme` n'implémente pas `equals` : les tests comparent les
  couleurs publiques (`editorBg`) et le cache d'instance (`assertSame`).
- L'import reste `jo.codeeditor.view.EditorTheme` (package de la 3.37.0
  publiée) — le dépôt code-editor a déplacé la classe vers
  `jo.codeeditor.view.chrome` APRÈS le tag v3.37.0 (« nettoyage » non
  publié) : monter en version de la bibliothèque demandera une passe
  d'imports.
- Six champs de plus dans `AppSettings`/DataStore (30 réglages au total) ;
  la projection tolérante absorbe valeurs inconnues et clés orphelines.
