# Écarts côté client — capacités servies, non consommées

> Livrable du lot L6 du plan 1.3.0 (critère 5 de l'ADR 0201 : une capacité est
> « en service » seulement si le client l'émet). Ce document recense, pour chaque
> capacité EN SERVICE côté serveur (annoncée, implémentée, câblée, testée E2E —
> voir docs/LSP_MATRICE_CAPACITES.md), l'écart de consommation par le client
> actuel (`cel-lsp` de code-editor ≤ 3.74.0) : la requête LSP concernée, le
> serveur qui la sert, l'effet utilisateur attendu et la forme d'API minimale à
> ajouter au client. Il nourrira un futur prompt `code-editor`.
>
> État au 21/10/2026. Les requêtes consommées aujourd'hui (jamais listées ici) :
> complétion, survol, aide à la signature, définition, définition de type,
> implémentations, références, surlignage, symboles de document, renommage +
> prepareRename, actions de code, formatage (document entier), indices en ligne
> (+ résolution), pliage, jetons sémantiques, executeCommand, synchronisation,
> didChangeConfiguration/WatchedFiles (côté hôte seulement — le client ne
> l'émet jamais, le surveillant de l'hôte couvre le besoin, lot L1).

## Formatage

| Capacité | Requête LSP | Servie par | Effet utilisateur attendu | API minimale côté client |
|---|---|---|---|---|
| Formatage de plage | `textDocument/rangeFormatting` | Java, Kotlin (lot L2), XML (lot L3) | « Formater la sélection » dans le menu : seule la sélection se ré-indente | Un point d'entrée `formaterPlage(uri, range)` du service LSP du client + élément de menu ; l'éditeur ouvre déjà la requête document entier — même mécanique avec la plage de la sélection |
| Formatage à la frappe | `textDocument/onTypeFormatting` | Java | À la frappe de `}` ou `;`, le bloc ou l'instruction se ré-indente automatiquement | Abonnement à `documentOnTypeFormattingProvider` des capacités du serveur + envoi de la requête après chaque frappe de déclencheur (le serveur annonce `};`) |

## Navigation d'espace et structure

| Capacité | Requête LSP | Servie par | Effet utilisateur attendu | API minimale côté client |
|---|---|---|---|---|
| Palette « aller au symbole » | `workspace/symbol` | Java, Kotlin (lot L2) | Une palette de recherche par nom dans TOUT le projet (classes, fonctions, propriétés) —aller au symbole sans connaître le fichier | Champ de recherche + liste des `SymbolInformation` rendues ; l'ouverture utilise le `location` (même chemin que la définition) |
| Plage de sélection | `textDocument/selectionRange` | Java, Kotlin (lot L2), XML (lot L3) | Expansion progressive de la sélection (double-clic étendu : mot → expression → déclaration → fichier) | Envoyer les positions du caret à `selectionRange` et chaîner `parent` à chaque geste d'« étendre la sélection » |
| Lentilles de code | `textDocument/codeLens` (+ `resolve`) | Java | Au-dessus des déclarations, le NOMBRE de références cliquable | Rendu de lentilles au-dessus des lignes (calque de décoration) + `codeLens/resolve` au clic ; l'ouverture réutilise le chemin références |
| Hiérarchie d'appels | `callHierarchy/prepare`+`incoming`+`outgoing` | Java | Qui appelle cette méthode / qu'appelle-t-elle — exploration en arbre | Un panneau hiérarchie : `prepare` au caret puis `incomingCalls`/`outgoingCalls` à l'expansion d'un nœud |
| Hiérarchie de types | `typeHierarchy/prepare`+`supertypes`+`subtypes` | Java | La chaîne d'héritage d'un type (parents/enfants) en arbre | Même panneau que les appels : `prepare` au caret puis `supertypes`/`subtypes` à l'expansion |

## Rendu riche XML

| Capacité | Requête LSP | Servie par | Effet utilisateur attendu | API minimale côté client |
|---|---|---|---|---|
| Couleurs de document | `textDocument/documentColor` + `colorPresentation` | XML (lot L3) | Pastille de couleur sur `#RRGGBB`/`@color/…`, ouverture d'un sélecteur, réécriture au choix | Après diagnostics : envoyer `documentColor`, rendre les plages en pastille (RGBA → teinte), au clic ouvrir le sélecteur natif puis `colorPresentation` pour l'édition |
| Édition liée des balises | `textDocument/linkedEditingRange` | XML (lot L3) | Renommer la balise ouvrante renomme la fermante en même temps (multi-curseur synchronisé) | Envoyer `linkedEditingRange` au caret sur un nom de balise ; si réponse, poser les deux curseurs liés (le noyau d'édition synchronise les deux plages) |

## Méthodes expérimentales (extensions propres, hors spec LSP)

| Capacité | Requête | Servie par | Effet utilisateur attendu | API minimale côté client |
|---|---|---|---|---|
| Super-définition | `textDocument/superDefinition` (clé expérimentale `superDefinitionProvider`) | Java | GO TO « Super » : depuis une redéfinition, sauter à la méthode/classe de base | Élément de menu « Aller au super » + requête typée (le miroir des types `NavigationTarget` existe côté client) |
| Source décompilée | `textDocument/decompiledSource` (clé expérimentale `decompiledSourceProvider`) | Java | Voir la source d'un type binaire (JDK, jars) en lecture seule — survol/définition sur `String` ouvre un buffer `jdt://decompiled/…` | À la définition dont l'URI est `jdt://decompiled/<fqcn>.java`, appeler `decompiledSource(fqcn)` et matérialiser le buffer read-only |

## Ordre suggéré (valeur/utilisateur d'abord)

1. Formatage de plage (le geste « formater la sélection » manque le plus — la 3.74.0
   de code-editor l'embarque déjà si l'agent 10-a l'a livré).
2. Palette « aller au symbole » (`workspace/symbol` — les deux serveurs majeurs la servent).
3. Plage de sélection (expansion de sélection — confort immédiat, trois langages).
4. Couleurs XML + édition liée (le XML est le plus « visuel »).
5. Lentilles, hiérarchies, formatage à la frappe, méthodes expérimentales (publics avertis).
