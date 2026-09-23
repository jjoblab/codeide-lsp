# ADR 0028 — Onglets d'édition : sessions au ViewModel, un seul EditorView, sauvegarde suspendue sous confirmation

- **Statut** : accepté (étape 15)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et bibliothèque
  d'édition », sections 2.4, 5.2 et 5.4 — intégration de l'éditeur cel-ui
  et des onglets de fichiers.

## Contexte

La zone centrale de l'espace de travail doit éditer les fichiers du
projet : ouverture depuis l'explorateur, onglets multiples, coloration
syntaxique, sauvegarde automatique et manuelle, fermeture sans perte
silencieuse, aucune fuite (le fil de restyle de cel-core doit être
libéré). L'API de cel-core/cel-ui a été **vérifiée dans l'AAR 3.37.0
avant implémentation** (javap) : `EditorDocument.of`/`getText`,
`EditorSession.setLanguage`/`getText`/`addOnTextEditListener`/`dispose`,
`EditorView.setSession`/`setTheme`/`setFileName`, `EditorTheme.light()`/
`dark()`.

## Décisions

1. **Les sessions vivent dans le `EditorViewModel`, jamais dans l'état.**
   Chaque onglet détient une `EditorSession` (classe pure de cel-core)
   dans une carte privée du ViewModel — `EditorTabState` n'est que la
   vue observable (uri, chemin, nom, langage, isDirty). L'activité
   rebranche **l'`EditorView` unique** sur la session de l'onglet actif
   (`setSession`), et applique `EditorTheme.light()`/`dark()` selon le
   mode de l'application — un seul `EditorView` en mémoire, jamais un par
   onglet (prompt compagnon 5.4).
2. **`SessionSuivie` : libération observable par test.** `dispose()` est
   impératif à chaque fermeture d'onglet et à la destruction
   (`onCleared` libère tout) — sinon fuite du thread de restyle
   (LeakCanary). L'enveloppe interne compte la libération : les tests
   vérifient `liberee` sans toucher à la classe cel ; le dialogue
   d'intégration (`SessionEditionTest`) éprouve de **vraies** sessions
   pures (aller-retour du texte, langue, repli neutre, notification
   d'édition, `dispose` idempotent).
3. **Déclencheur de modification = l'auditeur de session.** Le
   ViewModel enregistre `addOnTextEditListener` à la création de chaque
   session : toute édition rend l'onglet sale et (re)programme
   l'auto-sauvegarde (délai d'inactivité de 1,5 s, temps virtuel des
   tests). La sauvegarde manuelle (toolbar) écrit l'onglet actif. Toute
   écriture passe par `FileSystem.writeText`, **verrou par fichier**
   (`Mutex`) — jamais deux écritures concurrentes du même fichier.
4. **L'auto-sauvegarde est suspendue sous confirmation.** Quand la
   fermeture d'un onglet sale — ou la sortie avec des onglets sales —
   demande Enregistrer / Ne pas enregistrer / Annuler, les
   auto-sauvegardes en attente des onglets concernés sont **annulées** :
   « Ne pas enregistrer » doit pouvoir gagner, jamais écrire sous la
   question. Une annulation du dialogue laisse l'onglet sale sans
   auto-sauvegarde programmée (prochaine frappe ou bouton Enregistrer) —
   préférer un onglet sale affiché à une écriture non consentie.
5. **Fermeture groupée : les propres partent, les sales confirment.**
   « Fermer les autres » et « Fermer tout » ferment immédiatement les
   onglets propres et confirment les sales (agrégés dans un seul
   dialogue). Un échec d'enregistrement pendant « Enregistrer puis
   fermer » **annule la fermeture** des onglets concernés (et la sortie)
   — jamais de perte silencieuse.
6. **Binaires détournés, repli neutre.** Une extension binaire connue ne
   s'ouvre jamais en onglet : effet « Ouvrir avec » (ACTION_VIEW +
   FLAG_GRANT_READ) ; le reste ouvre en texte, avec langage reconnu ou
   repli neutre (`setLanguage` tolère un nom inconnu — vérifié dans le
   bytecode : simple affectation, aucune levée).
7. **Mort du processus : onglets rouverts, contenu relu.** Les chemins
   des onglets et l'index actif vivent dans le `SavedStateHandle` ; le
   ViewModel recréé relit chaque fichier (jamais sale a priori). Le
   fichier de reprise par projet (`workspace-state.json`, non
   synchronisé) reste à l'étape 17 ; le raccourci clavier
   d'enregistrement (EditorKeymap) est différé — l'action de la toolbar
   et l'auto-sauvegarde couvrent la Phase 1.
8. **Réordonnancement par menu, pas par glisser.** `TabLayout` n'offre
   pas de glisser-déposer natif : le menu contextuel propose « Déplacer
   à gauche / à droite » — simple, accessible, testé au ViewModel.

## Conséquences

- Le retour système ferme le tiroir s'il est ouvert ; sinon il demande
  la sortie — avec confirmation agrégée si des onglets sont sales.
- L'auditeur d'édition de cel-core peut être appelé depuis le fil de
  restyle : la carte des auto-sauvegardes est concurrente, l'état ne
  mute que par `MutableStateFlow.update` (thread-safe).
- Ouvrir dix onglets puis les fermer libère dix sessions — vérifié par
  test ; l'audit mémoire sur appareil réel (LeakCanary) reste le
  critère final (procédure E17 des tests manuels).
