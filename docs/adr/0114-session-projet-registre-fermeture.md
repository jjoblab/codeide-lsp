# ADR 0114 — Mission « Cycle de vie » B : session de projet et registre de fermeture

- **Statut** : accepté (v0.103.0, étape B du prompt 3 — §5.1)
- **Contexte** : l'audit d'étape A (CYCLE_DE_VIE.md §4) a établi qu'il
  n'existe AUCUN endroit unique où « fermer le projet » : la fermeture
  réelle est éparpillée entre `EditorViewModel.onCleared` (sessions LSP,
  sessions d'édition, auto-saves), `EditorActivity.onDestroy` (LspProject
  cel-lsp, sans garde `isChangingConfigurations`) et RIEN du tout pour le
  fond process-wide (connexions Tooling, CacheSync, console Gradle — E9).
  Les deux sessions simultanées du cas dégénéré P4 violent I1, la
  fermeture n'est bornée par aucun délai (E7) et l'annulation des
  sauvegardes sans flush (E2) viole I7.

## Décision

1. **Un module Kotlin JVM pur `:core:session`** (règle 1 : le contrat de
   domaine ne connaît ni Activity, ni ViewModel, ni Hilt) porte :
   - `Fermable` — le contrat d'abonnement d'une ressource (§5.5) ;
   - `RegistreFermeture` — fermeture en **ordre inverse du démarrage**,
     **délai maximal par élément**, drapeau **« ne pas annuler »**,
     tolérante aux pannes partielles, rapport de durée par élément ;
   - `SessionProjet` — la machine à états observable
     `{Fermee, Ouverture, Ouverte, Fermeture}` ;
   - `GestionnaireSessionProjet` — le garant d'I1 : ouvrir B pendant que
     A existe **ferme A d'abord** (séquentiellement, délais compris),
     puis ouvre B.
2. **Les paramètres vivent à l'enregistrement, pas dans la ressource** :
   `enregistrer(fermable, delai, annulable)`. L'ordre de fermeture est
   l'**ordre inverse d'enregistrement** (l'ordre de démarrage observé) ;
   pas de priorité numérique explicite — l'étape A a montré que les
   ressources démarrent déjà dans un ordre significatif (§4.8 : le
   registre reste MINIMAL, il ne réinvente pas la propriété des
   ressources).
3. **Le drapeau « ne pas annuler » protège I7** : un élément non
   annulable (le flush des sauvegardes, en étape C) s'exécute dans un
   contexte `NonCancellable` — même si l'appelant annule la fermeture,
   l'écriture a lieu. Les éléments annulables restants sont alors marqués
   `Annule` dans le rapport et l'annulation est **relancée** à la fin
   (`FermetureInterrompue`, sous-classe de `CancellationException` —
   règle projet : l'annulation ne s'avale jamais).
4. **Le délai est l'« arrêt forcé » du niveau domaine** : un élément qui
   dépasse son délai est abandonné et journalisé WARN (« dépassement de
   délai ») ; les autres continuent. L'interruption de thread
   (`runInterruptible`) relève des adaptateurs d'étape C, pas du contrat.
5. **Réentrance détectée, jamais bloquée** : un `Fermable` qui rappelle
   `fermer()` depuis sa propre libération reçoit un échec typé
   (`AppError.Session(Reentrance)`) — le verrou de session ne peut pas
   être réentrant, la détection par filiation de `Job` évite le
   blocage mortel. Un appel **concurrent** (autre coroutine) rejoint la
   fermeture en vol et reçoit le même rapport (promesse partagée).
6. **Un seul `SessionProjet` par processus, réutilisé** — le
   gestionnaire le détient ; les observateurs (ViewModels, étape C)
   observent `etat` (`StateFlow`) sans jamais en être propriétaires
   (§5.1 : « les ViewModels observent la session »).
7. **Erreurs typées** : refus d'ouverture sur session vivante, refus
   d'enregistrement hors session ouverte, fermeture en cours —
   `AppError.Session` (nouvelle variante de `core:model`), traduisible
   par l'UI en étape C.

## Alternatives écartées

- **Priorités numériques explicites** (genre `priorite: Int`) : le prompt
  demande une « priorité d'ordre » ; l'ordre d'enregistrement EST cette
  priorité. Des nombres auraient créé un second axe à maintenir en
  cohérence avec l'ordre réel de démarrage — le risque d'inversion
  silencieuse dépasse le bénéfice.
- **Fermeture non-suspend (callback)** : impossible de borner par délai
  ni de garantir l'ordre séquentiel I1 sans structure de coroutines.
- **`java.lang.AutoCloseable`** : n'apporte rien (pas de contexte, pas
  d'annulation) et induit un `try-with-resources` trompeur — la
  fermeture n'est PAS locale à une portée lexicale.
- **Gestionnaire par `launchMode` + extra de routage** (§2.6 b) :
  l'option (a) est retenue à court terme — le gestionnaire garantit I1
  au niveau sessions sans toucher au manifeste ; l'option (b) reste
  possible à l'étape E si les mesures montrent des empilements réels.

## Conséquences

- L'étape C branchera les premières ressources réelles : `SessionLsp`
  (adaptateur `Fermable` bornant `close()`), flush des sauvegardes
  (élément NON annulable — I7), `LspProject` (garde rotation). L'étape D
  ajoutera la libération par projet côté serveur via un message de
  protocole.
- La durée mesurée du rapport passe par `TimeProvider` (testable) ; le
  rapport part dans `AppLogger` (seule voie autorisée, règle 14) avec
  identifiants seuls (règle 15).
- Un `Fermable` bloquant SANS suspendre ne peut pas être interrompu par
  le délai — les adaptateurs doivent suspendre (`delay`) ou utiliser
  `runInterruptible` ; documenté dans le KDoc du contrat.
- La fermeture doit être lancée depuis une portée qui SURVIT à l'écran
  (sinon les éléments non annulables eux-mêmes ne partent pas) — le
  gestionnaire d'étape C sera branché sur une portée application.
