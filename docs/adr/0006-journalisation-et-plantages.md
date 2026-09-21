# ADR 0006 — Journalisation maison et gestion des plantages en processus séparé

- **Statut** : accepté (étape 0 — spécifié par le prompt maître, sections 5.7 et 5.8)
- **Contexte** : CodeIDE doit diagnostiquer ses propres problèmes **sans
  réseau** (aucune permission `INTERNET`, aucun envoi automatique — ni
  Crashlytics, ni Sentry). Timber & co n'apportent pas les garanties exigées :
  écriture asynchrone bornée, rotation contrainte, expurgation des données
  personnelles **à l'écriture**, breadcrumbs, et un écran de plantage qui
  fonctionne même quand l'application est morte.
- **Décision** : implémenter une **journalisation maison** (module
  `core:logging`, sans Timber) : `AppLogger` à évaluation paresseuse,
  `LogRedactor` (URI, chemins absolus, e-mails) appliqué à l'écriture, sinks
  Logcat + fichier JSONL avec rotation (1 Mo, 5 archives, 7 jours), file
  d'attente asynchrone bornée avec flush immédiat sur ERROR, tampon circulaire
  de 200 breadcrumbs, export zip via FileProvider. La **gestion des
  plantages** (module `core:crash`) capture dans un `try/catch` global avec
  garde de ré-entrance, écrit un rapport JSON **atomique** (via `org.json`
  du framework — zéro dépendance sur le chemin critique), puis affiche une
  `CrashActivity` **dans un processus séparé `:crash`**, sans Hilt, sans
  Room, sans DataStore. La liaison entre les deux modules se fait par
  **lambdas** (breadcrumbs, flush) fournies par `app` — `core:crash` ne
  dépend jamais de `core:logging`.
- **Conséquences** :
  - aucune donnée personnelle dans les journaux ni les rapports ; export
    propre et volontaire ;
  - l'utilisateur garde la main (« Redémarrer », « Copier », « Partager »,
    « Enregistrer ») ;
  - après un plantage géré, le gestionnaire **ne délègue pas** au
    gestionnaire système : pas de boîte de dialogue « a cessé de
    fonctionner », mais `ApplicationExitInfo` et les statistiques vitales
    diffèrent — documenté et assumé ;
  - coût : deux modules à écrire et tester finement (concurrence, limites de
    taille, boucle de plantages) — c'est l'objet des étapes 2 et 3.
