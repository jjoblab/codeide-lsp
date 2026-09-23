# ADR 0029 — Panneau inférieur : journal compact en fenêtre mémoire, trois états pilotés par l'état du ViewModel

- **Statut** : accepté (étape 16)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et bibliothèque
  d'édition », sections 5.5 et 6 (étape 16) — panneau inférieur à trois
  états, journal applicatif fonctionnel, stubs explicites Sortie/Problèmes.

## Contexte

Le panneau inférieur de `EditorActivity` doit passer de sa coquille vide
(étape 13) à un panneau fonctionnel : trois états d'ouverture
(replié/mi-hauteur/étendu), en-tête à poignée/titre/badge/actions, onglet
**Journal applicatif** compact branché sur le moteur de journalisation
existant (`LogRepository`, prompt maître section 5.7), onglets **Sortie**
et **Problèmes** en stub explicite (le tooling de compilation est hors
périmètre Phase 1). L'état du panneau et l'onglet actif doivent survivre à
la rotation. La contrainte structurante : réutiliser `feature:diagnostics`
est impossible (features isolées), donc le journal compact doit vivre dans
`feature:editor` sans dupliquer la logique du domaine.

## Décisions

1. **Fenêtre mémoire, pas d'historique disque.** Le journal compact
   collecte `ObserveLogsUseCase(FENETRE_JOURNAL)` (200 entrées, capacité
   du tampon *breadcrumbs*) — il montre **le flux vivant de la session**.
   La lecture de l'historique complet (`ReadAllLogsUseCase`), les exports
   et l'effacement restent le propre de l'écran Diagnostic, accessible par
   le lien « Ouvrir le journal complet » (`AppNavigator.openDiagnostics`).
   Aucune duplication : le domaine et `core:logging` ne bougent pas.
2. **Filtres par niveau identiques à l'étape 12.** Même sémantique que
   `EtatJournal.filtresNiveaux` : un ensemble **vide** affiche tous les
   niveaux, un niveau coché est **retenu**. Les puces filtres sont
   re-rendues depuis l'état sous une garde programmatique (jamais de
   renvoi d'action au ViewModel pendant une mise à jour de l'UI).
3. **L'état d'ouverture du panneau vit dans `EtatEditor` et le
   `SavedStateHandle`.** L'activité traduit `EtatPanneau`
   (REPLIE/MI_HAUTEUR/ETENDU) vers les états du `BottomSheetBehavior` et
   **remonte uniquement les transitions stabilisées** (COLLAPSED/
   HALF_EXPANDED/EXPANDED — jamais DRAGGING/SETTLING) ; le ViewModel
   ignore le rejou de l'état courant : aucune boucle, la gestuelle et les
   boutons passent par le même chemin unidirectionnel.
4. **`behavior_fitToContents=false` + `halfExpandedRatio=0.5`.** Seule
   combinaison qui rend `STATE_HALF_EXPANDED` réel pour une feuille à
   contenu variable ; la hauteur de survol (peek) reste l'en-tête seul
   (48 dp).
5. **Badge de compte sur l'onglet Journal.** Le badge affiche le nombre
   d'entrées de la fenêtre filtrée (l'équivalent « nombre de problèmes »
   quand les diagnostics existeront) ; masqué sur les stubs — pas
   d'apparence de fonctionnalité cassée.
6. **Retour système : le panneau étendu se réduit d'abord** (prompt
   compagnon 5.1) — avant le tiroir et la confirmation des onglets sales.
7. **Stubs honnêtes.** Sortie et Problèmes affichent un message explicite
   (« La console apparaîtra ici une fois le tooling de compilation
   disponible ») ; le point d'ancrage des diagnostics inline
   (`session.setDiagnostics`) est documenté en commentaire, non câblé.

## Conséquences

- Le journal compact et la visionneuse Diagnostic rendent les mêmes
  entrées de deux façons (fenêtre vivante vs historique paginé) — assumé,
  ce sont deux usages distincts (suivi en direct vs enquête).
- Chaque entrée du journal recompose `EtatEditor` : la fenêtre est bornée
  (200) et remplacée en bloc — coût constant, sans conflit de fusion.
- Les couleurs et libellés de niveaux sont dupliqués dans
  `feature:editor` (features isolées, ressources non partagées) : même
  valeurs, commentaire de traçabilité — un futur socle commun
  (`core:ui`) pourra les unifier si le besoin grandit.
