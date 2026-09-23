# ADR 0036 — Écran du terminal : un rendu unique, clavier interne et police dédiée

- **Statut** : accepté (étape 23 = Terminal T5, v0.24.0)
- **Contexte** : prompt compagnon « Terminal intégré et bootstrap natif »,
  section 5 ; ADR 0035 (registre des sessions, `termux-shared` refusé).

## Décision

1. **Un seul `TerminalView`, rebranché.** Changer d'onglet appelle
   `attachSession(session)` sur la **même** vue — jamais un rendu par
   onglet : même principe que l'éditeur (ADR 0028), la mémoire en
   dépend. Le rebranchement est déclenché par le changement
   d'**identifiant** de session active (pas à chaque émission d'état) ;
   l'identifiant transite par le ViewModel, l'objet réel Termux par
   `TerminalRuntime.sessionFor` — le ViewModel ne connaît jamais les
   types Termux.
2. **Clavier étendu interne, déclaratif.** `termux-shared` étant refusé
   (ADR 0035), la rangée (Tab, Ctrl, Alt, Échap, flèches — « au
   minimum ») est une vue maison : liste déclarative de définitions de
   touches, boutons Material simples. Les touches directes écrivent des
   séquences de contrôle (`\t`, `ESC`, `ESC[A`…) dans la session ;
   **Ctrl/Alt sont des bascules persistantes** lues par le
   `TerminalViewClient` (`readControlKey`/`readAltKey`) — le mécanisme
   conçu par Termux pour ses touches virtuelles, appliqué par la vue à
   l'entrée du clavier : aucune réimplémentation du traitement de
   frappes.
3. **Thème clair/sombre par les couleurs de l'émulateur.** La palette
   courante du rendu vit dans `TerminalEmulator.mColors.mCurrentColors`
   (disposition jackpal : 259 entrées, indices 256/257/258 =
   premier plan/arrière-plan/curseur). L'écran réécrit ces trois
   entrées depuis les ressources `values`/`values-night` (fond quasi
   blanc / texte sombre en clair ; quasi noir / texte clair en sombre)
   à chaque branchement et à chaque `onEmulatorSet`. Une séquence OSC
   du shell peut re-réécrire les couleurs : c'est le comportement d'un
   vrai terminal, assumé.
4. **Police à chasse fixe : réglage dédié minimal.** L'application
   n'ayant aucun réglage de police (l'éditeur `cel-ui` gère le sien en
   interne), le prompt demande un réglage dédié : `TaillePoliceTerminal`
   (PETITE/MOYENNE/GRANDE) dans `AppSettings` (DataStore, section
   Apparence des Paramètres), appliqué en dp → pixels via
   `TerminalView.setTextSize` avec garde anti-re-création de fonte.
5. **Fermeture d'un onglet : heuristique « au prompt ».** Heuristique
   simple exigée par le prompt : session vivante **et** fin de l'aperçu
   sans indicateur de prompt (`$`, `#`, `%`, `>`) → dialogue de
   confirmation (« une commande semble en cours ») ; sinon fermeture
   directe. La fermeture termine **réellement** le shell (port du
   domaine, ADR 0035) — jamais « juste masqué ».
6. **Retour système = fermer l'écran.** Le bouton retour n'est jamais
   mappé sur Échap (`shouldBackButtonBeMappedToEscape = false`) :
   les sessions survivent via le service foreground (ADR 0035).
7. **Navigation.** `AppNavigator.openTerminal(suggestedWorkingDirectory)`
   ouvre l'activité par-dessus la pile ; le répertoire suggéré transite
   par l'intent puis le `SavedStateHandle` (survit à la rotation). Les
   points d'entrée UI (accueil, tiroir) arrivent en T6 — l'écran est
   fonctionnel dès T5.

## Alternatives rejetées

- **Un `TerminalView` par session** (visibilité basculée) : n instances
  de vue et n rendus vivants — contredit le « un seul rendu, limiter la
  mémoire » du prompt et la leçon de l'éditeur (ADR 0028).
- **`ExtraKeysView` de termux-shared** : GPLv3 (exceptions MIT ne
  couvrant pas `terminal/io/extrakeys`, vérifié v0.118.3 — ADR 0035).
- **Réimplémenter la gestion des modificateurs Ctrl/Alt nous-mêmes**
  (interception de l'entrée IME) : fragile et redondant — la vue Termux
  expose déjà `readControlKey`/`readAltKey` exactement pour cela.
- **Pinch-to-zoom de la police** : non exigé (le réglage persistant
  suffit), `onScale` volontairement inerte en T5.

## Conséquences

- `feature:terminal` est la **seule** feature dépendant de
  `core:terminal-runtime` et de `com.termux:*` (règle vérifiée par
  `checkModuleDependencies`, section 2.3 du prompt).
- Exception lint `Aligned16KB` dupliquée dans ce module (même
  justification qu'ADR 0035 : `libtermux.so` transitive non alignée en
  amont, aucune release corrigée publiée).
- Le POM JitPack de `terminal-view` ne publie pas sa dépendance à
  `terminal-emulator` (portée projet) : les deux artefacts sont déclarés
  explicitement — le second est déjà documenté dans
  `THIRD_PARTY_NOTICES.md` (ajout de la ligne `terminal-view`).
- Le pincement (zoom) et le copier-coller étendu restent ouverts pour
  T7/finitions s'ils se révèlent utiles sur appareil.
