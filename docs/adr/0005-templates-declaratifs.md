# ADR 0005 — Templates de projet déclaratifs

- **Statut** : accepté (étape 0 — spécifié par le prompt maître, section 11 étape 8)
- **Contexte** : la création de projet doit proposer des modèles **Kotlin**
  et **Java** en Phase 1, puis s'étendre (Python, Web, C++…) sans toucher au
  code. Un modèle codé en dur (arborescence + substitutions dans des `when`)
  rend chaque ajout risqué et chaque correction multipliée par les langages.
- **Décision** : chaque modèle est **déclaratif** — un manifeste
  `assets/templates/<id>/template.json` (avec `schemaVersion`) décrit les
  paramètres (typés : texte, booléen, choix ; visibilité conditionnelle
  `visibleWhen` ; valeurs dérivées `defaultFrom` ; validateurs nommés), les
  fichiers (chemins templatisables, `.tpl` texte ou binaires copiés, contenu
  conditionnel `when`), et les dictionnaires i18n. Le moteur (implémenté à
  l'étape 8) interprète : substitution `{{variable|filtre}}` avec filtres
  d'échappement (`kotlinString`, `javaString`, `xml`, `json`, `tomlString`,
  `md`…), conditions `{{#if expr}}`, mini-langage d'expressions **écrit à la
  main** (profondeur bornée, aucune évaluation de code arbitraire). Les
  modèles s'enregistrent via `ProjectTemplateProvider` en multibinding Hilt —
  **aucun `when(templateId)` en dur**.
- **Conséquences** :
  - ajouter un modèle = ajouter des assets, sans recompilation du moteur ;
  - les tests du moteur s'écrivent sur une *fixture* déclarative, avec des
    entrées hostiles (guillemets, `\`, `$`, emojis) ;
  - génération déterministe (mêmes entrées → mêmes octets) et vérifiable
    par *dry-run* (le plan correspond exactement à ce qui est écrit) ;
  - coût : un moteur à écrire et à tester (étape 8), compensé par la
    maîtrise totale de l'échappement et de la sécurité des chemins.
