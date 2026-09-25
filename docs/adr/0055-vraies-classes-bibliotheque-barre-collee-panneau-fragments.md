# ADR 0055 — Vraies classes de la bibliothèque, barre collée à l'IME et panneau inférieur à fragments

- **Statut** : accepté (v0.32.4, retour d'appareil réel sur l'étape 31, suite)
- **Contexte** : retour utilisateur du 2026-09-26 sur la v0.32.3 :
  « Pourquoi tu n'as pas utilisé les classes de code-editor (symbolview
  et breadcrumb) ? La touche virtuelle n'est pas visible, elle devrait
  donner l'impression d'être collée au clavier quand c'est visible.
  Pour le bottomsheet behavior, il faut aussi utiliser de fragments au
  lieu d'empiler les vues dans le layout. » Trois points.

  Première vérification refaite : l'ADR 0054 s'appuyait sur un constat
  ERRONÉ — « ces deux classes n'existent pas dans le tag v3.37.0 ».
  Elles y existent bien, dans le paquet `jo.codeeditor.view` (elles n'ont
  été déplacées vers `view/chrome/` que plus tard sur le dépôt amont,
  dans les tags de nettoyage postérieurs — c'est là que la v0.32.3 les
  avait cherchées). La dépendance consommée (`cel-ui` 3.37.0 via
  JitPack) expose donc directement `BreadcrumbBar` et `SymbolBarView` :
  aucune transcription n'était nécessaire, aucun bump non plus.

- **Décisions** :

1. **Les classes réelles de la bibliothèque, pas des transcriptions.**
   Les vues maison `VueFilArianeEditeur` et `BarreSymbolesEditeur`
   (v0.32.3, ~460 lignes) sont supprimées ; le layout et l'activité
   consomment `jo.codeeditor.view.BreadcrumbBar` et
   `jo.codeeditor.view.SymbolBarView` telles quelles. Conséquences
   assumées : le rendu est celui de la bibliothèque (couleurs sombres
   constantes, hors thème Material de l'application ; le fil ne défile
   pas — la classe dessine sur la largeur disponible et coupe). Les
   segments restent produits côté CodeIDE : `setSegments(String[])`,
   l'API « usage manuel » documentée de la classe, alimentée par le
   scanner [SymbolesEnglobants] et le chemin relatif de l'onglet.
   `bind()` n'est PAS appelé : son propre écouteur recalculerait les
   segments via le SPI `SymbolProvider`, qui ne connaît ni le chemin
   relatif ni le scanner maison Kotlin/Java. L'écouteur de la barre est
   `SymbolBarView.OnSymbolTap` (identifiants d'action de la
   bibliothèque : `tab`, `comment`, `move_up`, `move_down`,
   `duplicate`) — mêmes commandes de session qu'avant.

2. **Barre collée au clavier : le peek porte la barre.** Cause racine
   du « pas visible » : à l'ouverture de l'IME, la v0..32.3 forçait le
   panneau en `STATE_COLLAPSED` — peek de repos 48 dp, c'est-à-dire
   l'en-tête SEUL — et la barre, placée sous l'en-tête dans le sheet,
   restait sous le bord de l'écran. Le correctif structurel : quand la
   barre est montrée, `peekHeight` = en-tête + barre (48 + 38 dp,
   constante interne de la `SymbolBarView`) ; le sheet replié étant
   déjà posé sur le haut du clavier (`adjustResize`), la barre se
   retrouve directement appliquée contre l'IME — « l'impression d'être
   collée au clavier ». À la fermeture, le peek de repos est restauré.

3. **Détection de l'IME doublée.** Les insets natifs
   (`WindowInsetsCompat.Type.ime()`, API 30+) sont enregistrés sur la
   racine TOUTES API (no-op muet avant 30) ET le rétrécissement du
   root (hauteur max moins hauteur courante, seuil 15 % de l'écran) le
   reste aussi — le OU des deux pilote la barre. Un seul détecteur se
   taisait sur certains appareils (mode resize hérité, targetSdk 28) ;
   deux détecteurs indépendants qui convergent couvrent les deux
   familles, au prix d'un signal idempotent (la visibilité est
   recalculée, jamais empilée).

4. **Le panneau inférieur passe aux fragments.** Le chrome reste à
   l'hôte (`activity_editor.xml`) : en-tête (poignée, titre, badge,
   agrandir, réduire), barre de symboles, `TabLayout` des onglets
   Console/Problèmes/Journal. Le contenu migre dans trois fragments de
   `feature:editor` — `PanneauConsoleFragment` (statut + console du
   build), `PanneauProblemesFragment` (diagnostics groupés),
   `PanneauJournalFragment` (fenêtre compacte + filtres) — chacun avec
   son layout `fragment_panneau_*.xml`, ajoutés UNE fois au
   `FragmentContainerView` puis montrés/cachés (même pattern que le
   tiroir, ADR 0052 : l'état de défilement et les plis survivent aux
   changements d'onglet). Chaque fragment collecte l'état qu'il rend
   (`activityViewModels()`, `collectWithLifecycle` sur
   `viewLifecycleOwner`) — l'activité ne collecte plus l'état tooling.

5. **Contrat intra-feature pour le saut aux diagnostics.**
   `ControleurPanneauEditeur` (interface `feature:editor`) expose
   `sauterAuProbleme(fichier, ligne)` — implémenté par `EditorActivity`
   (sélection d'onglet + défilement + curseur), récupéré par
   `PanneauProblemesFragment` par cast contrôlé dans `onAttach`. Même
   pattern que le contrat `ControleurTerminalTiroir` de l'ADR 0053,
   mais SANS passer par `core:ui` : fragment et hôte vivent dans le
   même module, la frontière de feature n'est pas traversée.

6. **Show/hide ciblés par tag.** Les fragments du tiroir (4) et ceux du
   panneau (3) cohabitent dans le `FragmentManager` de l'activité. Le
   `forEach` global de la v0.32.2 (`gestionnaire.fragments.forEach {
   … hide }`) aurait caché les fragments du panneau à chaque changement
   de destination du tiroir (et réciproquement). Les deux commutations
   ciblent désormais leurs fragments PAR TAG (`TAG_PANNEAU_*`,
   `TAG_*_TIROIR`) — le garde-fou est verrouillé par le test de layout
   (l'ordre en-tête → barre → onglets → conteneur) et les tags
   documentés en constantes.

- **Alternatives rejetées** :

  - *Bump de la dépendance vers un tag de nettoyage du dépôt amont* —
    les tags `.n`/`.n2` déplacent des centaines de classes de paquet
    (risque de régression sur tout `cel-ui`) pour un gain nul : les
    deux classes voulues existent déjà en 3.37.0.
  - *`bind()` de la `BreadcrumbBar`* — écraserait les segments par le
    SPI `SymbolProvider` (aucune langue câblée côté CodeIDE, le nom de
    fichier seul survivrait) ; `setSegments()` est l'API prévue pour
    ce cas.
  - *Barre détachée du sheet, posée sur la racine au-dessus de l'IME* —
    demanderait de piloter la marge basse de la vue au fil des insets
    animés pour suivre le clavier ; le sheet replié au peek élargi
    obtient le même collage gratuitement via `adjustResize`.
  - *`replace()` à chaque changement d'onglet du panneau* — reconstruit
    les vues et perd le défilement ; `show()`/`hide()` le conservent.

- **Conséquences** : deux classes maison et leurs tests de géométrie
  disparaissent (moins de code à maintenir, rendu = bibliothèque) ; le
  fil d'Ariane perd le défilement horizontal de la v0.32.3 (comportement
  bibliothèque, accepté) ; la barre de symboles garde le rendu sombre
  de la bibliothèque, même en thème clair (idem) ; l'activité perd
  ~230 lignes de câblage de panneau, reportées dans les fragments.
