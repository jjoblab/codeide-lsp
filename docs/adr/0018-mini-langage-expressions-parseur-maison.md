# ADR 0018 — Mini-langage d'expressions : parseur maison, borné, sans évaluation de code

- Date : 2026-09-22 (étape 8, v0.9.0)
- Statut : accepté

## Contexte

Les manifestes de modèles (`template.json`) doivent exprimer des
**conditions** (visibilité d'un paramètre, inclusion d'un fichier) et des
**variables calculées** (ex. `estApplication = projectType == "application"`).
Le texte des fichiers `.tpl` porte des balises `{{#if expr}}`.

Le langage nécessaire est minuscule : identifiants, littéraux chaîne et
booléen, `==`, `!=`, `&&`, `||`, `!`, parenthèses. Mais il est alimenté par
des **assets** — des fichiers, pas du code compilé. Toute approche
générique d'évaluation (moteur d'expression en réflexion, `ScriptEngine`,
`eval`) transformerait un manifeste hostile en exécution arbitraire.

## Décision

1. **Parseur écrit à la main** (`ExpressionParser`) : lexique puis descente
   récursive sur une grammaire explicite (`ou → et → égalité → unaire →
   primaire`), documentée dans `Expression.kt`. Aucune dépendance de
   parsing générique, aucune réflexion, aucune évaluation de code fourni
   par le manifeste.

2. **Bornes de sécurité vérifiées avant la moindre récursion** :
   - longueur maximale d'une expression : 512 caractères ;
   - nombre maximal de jetons : 128 ;
   - profondeur d'imbrication maximale : 16 (chaque parenthèse ou négation
     consomme un cran — 15 parenthèses imbriquées passent, la 16e échoue).
   Un manifeste hostile ne peut ni saturer la pile ni diverger.

3. **Typage strict à l'évaluation** (`ExpressionEvaluator`) : valeurs
   `Chaine`/`Booleen` uniquement ; `&&`/`||`/`!` exigent des booléens,
   `==`/`!=` refusent les types mêlés — toutes les erreurs sont typées
   (`ExpressionException`) avec position, et listent les identifiants
   disponibles.

4. **Les erreurs sont françaises et positionnées** : elles alimentent les
   journaux et le bloc « copier les détails » ; l'UI (étape 10) les
   remplacera par des ressources.

5. **Même grammaire partout** : `visibleWhen` des paramètres, `when` des
   fichiers, `computed` du manifeste et `{{#if}}` des templates passent
   par le même analyseur — un seul dialecte à documenter
   (`docs/TEMPLATES.md`).

6. **Le curseur de jetons est sûr en fin de flux** : au-delà du dernier
   jeton, il tient la fin pour acquise (`getOrElse { Fin }`) — une
   expression tronquée échoue avec `ExpressionException` positionnée,
   jamais avec `IndexOutOfBoundsException`.

## Conséquences

- Le rendu ne peut échouer que sur des **valeurs** (identifiant inconnu à
  l'exécution, clé i18n absente) — toujours explicitement, jamais de
  `{{…}}` résiduel dans la sortie.
- Étendre le langage (nombres, comparaisons ordonnées) passera par la
  grammaire et ses tests exhaustifs — pas par une dépendance externe.
- Les manifestes des étapes futures (modèles Kotlin/Java, plugins)
  bénéficient d'un contrat stable et vérifié au chargement.
