# ADR 0053 — Terminal du tiroir : rendu réel, split view et plein écran intégré

- **Statut** : accepté (v0.32.2, retour d'appareil réel sur l'étape 31)
- **Contexte** : la v0.32.0 avait migré la carte d'aperçu T6 **telle
  quelle** dans `TerminalTiroirFragment` (ADR 0052, décision 1 :
  métadonnées seules, zéro dépendance Termux côté `feature:editor`).
  Retour utilisateur du 2026-09-26 : (1) la poignée ⋮ n'avait pas
  l'animation de la maquette (`.poignee.pendant` : bordure accent-fort,
  fond actif, points accent, `scale(1.08)` — l'écouteur tactile consommant
  tout, l'état pressé du sélecteur ne s'activait JAMAIS, la poignée
  restait figée pendant le glissement) ; (2) le tiroir chevauchait la
  barre de navigation (la v0.32.1 n'avait traité que la barre de statut,
  en marge haute — la barre de navigation restait un rembourrage bas, le
  tiroir peignait derrière) ; (3) le terminal du tiroir devait montrer
  l'entête de la maquette AVEC des boutons de **split view** (vertical ou
  colonnes) et, sur chaque carte de session, des boutons pour **agrandir
  la session dans le tiroir** ou **l'ouvrir en plein écran** — l'appui sur
  la carte ne doit plus naviguer d'office vers l'écran plein écran.

- **Décisions** :

1. **Le fragment Terminal du tiroir rend de VRAIES sessions et déménage
   dans `feature:terminal`.** Voir et interagir avec le contenu des
   sessions dans le tiroir impose `TerminalView` (com.termux) et le
   `TerminalRuntime` — réservés à `feature:terminal` par la règle des
   modules (Terminal-1 § 2.3, `ModuleRulesPlugin`). Le fragment y vit
   donc (`jo.codeide.feature.terminal.TerminalTiroirFragment`), et
   l'activité d'édition le crée sans connaître la classe via une
   **fabrique Hilt** : interface `FabriqueFragmentTerminalTiroir`
   (`core:ui`), liée `@Binds` dans le module terminal, injectée dans
   `EditorActivity`. Les features ne se référencent toujours pas.

2. **Contrat d'hôte `ControleurTerminalTiroir` (core:ui).** Les commandes
   du fragment qui réclament l'état de l'espace de travail — ouvrir le
   plein écran (dossier réel du projet via FUSE), créer une session dans
   le dossier du projet, ouvrir l'installation du bootstrap — partent
   vers ce contrat, implémenté par `EditorActivity` et résolu par cast
   en `onAttach` (un hôte qui ne l'implémente pas voit les actions
   tomber sur le repli navigateur ou se taire). Nouvelle action
   `ActionEditor.CreerSessionTerminal` : crée dans le dossier du projet
   **sans naviguer** (l'ancienne `NouvelleSessionTerminal` = même code,
   `ouvrirEnPleinEcran = true`) — la session apparaît dans le tiroir,
   l'utilisateur reste maître du mode.

3. **Modes d'affichage (ViewModel dédié).** `TerminalTiroirViewModel`
   expose `sessions` (registre global) + `mode` : `Liste` (cartes —
   défaut), `SplitVertical` (panneaux empilés, hauteurs égales),
   `SplitColonnes` (panneaux côte à côte, largeurs égales),
   `PleinEcranDansTiroir(sessionId)` (un panneau remplit le tiroir,
   bouton retour liste). Un mode plein écran pointant une session
   disparue **retombe sur la liste** (jamais de panneau orphelin). Les
   boutons de split de l'entête ne s'activent qu'avec ≥ 2 sessions
   (alpha atténuée + indice « le split nécessite au moins deux
   sessions »). Toucher une carte l'agrandit dans le tiroir ; les
   boutons explicites font l'un (agrandir) ou l'autre (plein écran).

4. **Un panneau = une session AFFICHÉE.** Chaque panneau
   (`vue_panneau_terminal.xml`) est un mini-entête (libellé monospace,
   retour/agrandir/plein écran) au-dessus de SA `TerminalView` branchée
   par `attachSession` — le molette mémoire reste bornée par le nombre
   de sessions affichées, la liste reste des cartes légères, et l'écran
   plein écran garde son TerminalView unique rebranché (ADR 0050).
   Les reconstructions ne surviennent que sur changement de mode ou de
   liste (empreinte = ids + libellés) — les émissions throttlées de
   sortie (250 ms pendant une commande) ne reconstruisent RIEN : le
   signal `observeSorties` repeint les vues branchées et **rattrape**
   les attachements manqués (émulateur absent → re-branche). Le clavier
   étendu est **partagé** : une seule rangée sous le corps, la séquence
   part au panneau **focalisé** ; `ClientVueTerminal` lit désormais
   Ctrl/Alt par lambdas (`lireCtrl`/`lireAlt`) au lieu de tenir la vue —
   l'écran plein écran et le tiroir partagent le même client. Le thème
   de rendu (palette 256/257/258) est extrait dans
   `RenduTerminal.appliquerThemeRendu()`, partagé.

5. **Poignée : l'état « pendant » posé à la main (v0.32.2).** L'écouteur
   tactile de la poignée retourne `true` dès `ACTION_DOWN` — le
   `onTouchEvent` de la vue ne court jamais, `isPressed` ne s'active
   pas, le sélecteur `state_pressed` (fond actif + bordure accent-fort,
   déja conforme à `.poignee.pendant`) restait invisible. Le
   glissement pose/retire désormais explicitement : `isPressed` (couleurs
   du sélecteur), teinte d'image = points ⋮ accent, et **grossissement
   1.08 animé sur 150 ms** (pivot au centre de la poignée, à cheval sur
   le rebord — moitié dedans, moitié dehors, § 13 inchangé). Retour
   symétrique au relâchement.

6. **Tiroir au-dessus de la barre de navigation (marge basse).**
   `applySystemBarsInsetsTopMargin` (v0.32.1) devient
   `applySystemBarsInsetsMargins` : barre de statut ET barre de
   navigation deviennent des **marges** verticales du tiroir — il ne
   peint plus rien derrière l'une ni l'autre, le rail du tiroir
   s'arrête au-dessus des gestes (`DrawerLayout` respecte les deux
   marges — vérifié au bytecode v0.32.1).

- **Conséquences** :
  - `feature:editor` ne référence plus `TerminalTiroirFragment` ni
    `fragment_terminal_tiroir.xml` (supprimés) ; les chaînes du tiroir
    terminal déménagent dans `feature:terminal` (FR + EN) ;
    `EditorViewModel.etatTerminal` (T6) reste la garde-fou bootstrap des
    actions du contrôleur et garde ses tests — le fragment ne le
    consomme plus (son état vient du registre + locator, côté module
    terminal).
  - La règle des modules tient sans exception nouvelle : la fabrique et
    le contrôleur vivent dans `core:ui` (déjà dépendant de fragment),
    le fragment et sa vue dans `feature:terminal` (déjà autorisé au
    runtime Termux).
  - Écarts assumés : la taille de police des panneaux du tiroir démarre
    au réglage « moyenne » (15 dp) avec zoom pincé par panneau — le
    tiroir ne suit pas encore le réglage global (l'écran plein écran le
    fait) ; la fermeture d'une session depuis le tiroir n'est pas
    exposée (cartes sans croix, comme la maquette) — l'écran plein écran
    garde le renommage/duplication/fermeture.
  - Les tests : `TerminalTiroirViewModelTest` (modes, repli orphelin,
    suivi de liste, fakes du domaine — zéro dépendance Termux),
    `TiroirTerminalLayoutTest` (gonflage des trois layouts sous le thème
    réel, régression 7842f130), deux tests d'action dans
    `CarteTerminalEditorViewModelTest` (créer sans naviguer + garde-fou
    bootstrap), `ActivityEditorLayoutTest` ajusté (le layout terminal
    n'est plus dans le module éditeur).
