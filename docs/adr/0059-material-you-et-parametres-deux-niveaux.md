# ADR 0059 — Cohérence Material You et refonte de l'écran Paramètres

- **Statut** : accepté (v0.34.0, retour utilisateur du 2026-09-26 —
  mission « cohérence Material You + refonte des Paramètres »)
- **Contexte** : retour utilisateur, quatre chantiers ordonnés :
  (1) les couleurs dynamiques ne s'appliquaient que via
  `MainActivity.appliquerApparence()` — atterrir sur `EditorActivity`,
  `TerminalActivity` ou `CrashActivity` (processus `:crash` séparé, sans
  Hilt ni DataStore) donnait un thème non respecté ;
  (2) seuls 25 rôles Material 3 officiels vivaient dans le thème —
  journaux, canaux tooling, statuts et accents par langage étaient des
  couleurs fixes, insensibles à Material You ;
  (3) ~170 littéraux hex subsistaient dans les features (`feature:editor`
  115, `feature:terminal` 43, `feature:diagnostics` 8, `newproject` 4,
  `install` 2) ;
  (4) l'écran Paramètres était un `LinearLayout` plat de 333 lignes,
  sans carte ni sous-écran, et `AppSettings` ne portait AUCUN réglage
  Éditeur, ni Terminal au-delà de la police, ni IA.

## Décisions

### 1. Point d'application unique des couleurs dynamiques (Application)

`CodeIdeApplication.onCreate()` lit un **miroir synchrone**
(`MiroirApparence`, core:data : SharedPreferences dédiées, deux clés —
mode de thème + couleurs dynamiques) AVANT le `when (processus)` : dans
**chaque** processus (principal et `:crash`), il pose
`AppCompatDelegate.setDefaultNightMode(mode)` et, si le réglage l'exige,
`DynamicColors.applyToActivitiesIfAvailable(this)` — avant
`super.onCreate()`, avant Hilt, sans `runBlocking` (règle du projet
respectée). Le miroir est réécrit à chaque persistance réussie
(`SettingsRepositoryImpl.updateSettings`) ET à chaque émission observée
(convergence d'une montée de version depuis v0.33.x dès le premier
démarrage). `MainActivity` garde le seul **changement à chaud** :
recouvrement immédiat + inscription au niveau Application à
l'activation, `recreate()` à la désactivation (l'overlay ne se retire
pas). `CrashActivity` ne change pas : le hook au niveau Application la
couvre (elle ne fixe pas de thème avant `setContentView`).

### 2. Rôles de couleur étendus, harmonisés

21 attrs de thème nouveaux dans `core:ui` (même patron que les rôles M3,
jour/nuit) : `colorJournalDebug/Info/Warn/Error`, `colorStdout/Stderr`,
`colorSucces`, `colorInfo`, `colorCanalSync/Build/Taches/Etiquette`,
`colorGitAjoute/Modifie/Supprime`,
`colorLangageKotlin/Java/Gradle/Markdown/Xml/Json`. Les teintes
**fonctionnelles** (journal, statuts Git, erreurs) restent figées — la
sévérité doit rester reconnaissable ; les teintes **de marque** (canaux,
langages, succès) passent par `ThemeHarmonizer` (core:ui) :
`MaterialColors.harmonize(base, colorPrimary du thème courant)` — avec
les couleurs dynamiques actives, un accent custom se rapproche
subtilement de la teinte du fond d'écran au lieu de jurer avec elle. Les
trois consommateurs programmatiques des canaux (étiquettes de console,
icônes de statut d'activité et de panneau) harmonisent à la lecture.

### 3. Migration complète des couleurs codées en dur

- `feature:editor` : journal/sortie/canaux → attrs étendus ; jetons de
  conception (explorateur v2, panneau inférieur, barre de symboles)
  centralisés dans `core:ui` sous préfixe `codeide_` ;
- `feature:terminal` : fond/texte/états du rendu → `codeide_terminal_*` ;
  la famille `tiroir_terminal_*` (17 jetons dupliqués à l'identique de
  l'explorateur depuis l'ADR 0053) est UNIFIÉE avec
  `codeide_explorateur_*` (un seul doublon sans équivalent devient
  `codeide_tiroir_terminal_gris_doux`) ;
- `feature:diagnostics` : `niveau_*` (valeurs identiques au journal) →
  attrs partagés, et la coloration du niveau — prévue mais jamais
  branchée (seul `tools:textColor` l'esquissait) — est maintenant
  appliquée par `EntreesJournalAdapter` ;
- `feature:newproject` : `wizard_avertissement`/`wizard_emplacement_normal`
  étaient EXACTEMENT `codeide_error`/`codeide_on_surface` → mapping
  direct, définitions locales supprimées ;
- `feature/install` : `vert_etape_faite` → `codeide_succes` (jour
  identique, nuit contrastée en prime) ;
  `gris_etape_attente` (#8A8A8A, identique jour/nuit) →
  `codeide_outline` — approximation assumée (écart imperceptible) qui
  gagne une vraie déclinaison nuit ; cas signalé ici plutôt que forcé
  silencieusement.

Les fichiers `colors.xml` des cinq features sont supprimés ; le grep
`#[0-9A-Fa-f]{6,8}` sur `feature/editor` et `feature/terminal` ne
remonte plus que les remplissages décoratifs des icônes (assets).

### 4. Écran Paramètres à deux niveaux, modèle étendu

**Écran maître** : cartes M3 groupées (Général = Apparence + Langue +
Notifications ; Modules = Éditeur + Terminal + IA ; Environnement =
Projets + Outils de développement ; Application = Sécurité + À propos +
Avancé), une rangée = icône + libellé + **sous-titre d'état** + chevron,
navigation par `AppNavigator.openSettingsSection(SectionParametres)`
(enum de core:ui, destinations du graphe dans `app`). **Un fragment par
section**, sa propre toolbar (flèche retour), le même
`SettingsViewModel` partagé au niveau activité (`activityViewModels`) —
persistance immédiate inchangée, seule la présentation est répartie.
Rayon des cartes : `shapeAppearanceMediumComponent` (12 dp), icônes
Material Symbols 24 dp, espacements `spacing_*` existants.

**État « Bientôt disponible »** : IA, Outils de développement et
Sécurité et confidentialité (contenus à valider avec Olson) restent
visibles mais atténuées (opacité réduite), puce « Bientôt » à la place du
chevron, vers `BientotFragment` — écran générique piloté par argument de
navigation (icône + titre + une phrase, AUCUNE logique métier). Le
patron est réutilisable : une future section = une entrée d'enum + une
destination, le maître ne bouge pas.

**Modèle** : `AppSettings` s'étend (enums nouveaux
`TaillePoliceEditeur`, `StyleCurseurTerminal`, `TailleTabulation`) —
Éditeur (retour à la ligne, numéros, surlignage, tabulation, sauvegarde
auto, police : réglages **persistés avant consommation**, le moteur
cel-ui s'y abonnera avec la coloration), Terminal (police migrée
d'Apparence + style du curseur + copie auto de sélection, tous deux
consommés dès maintenant), Notifications (Sync, Build, Son).

### 5. Consommation réelle des réglages Terminal et Notifications

- **Style du curseur** : le client de session Termux
  (`ClientTermux`, core:terminal-runtime) répond
  `getTerminalCursorStyle()` depuis `PorteurStyleCurseur` — singleton
  qui collecte les paramètres ; l'émulateur relit à chaque
  `setCursorStyle()` (création de session + changement constaté par
  l'écran). Au passage : l'ancien client renvoyait la constante 2 en
  l'appelant « bloc » — c'était le style BARRE de l'enum Termux ; le
  mapping est corrigé (BLOC = 0).
- **Copie automatique** : à la fin du mode sélection
  (`copyModeChanged(false)`), `ClientVueTerminal` lit le texte conservé
  par Termux (`getStoredSelectedText`), le pousse au presse-papiers et
  le consomme — plein écran comme panneaux du tiroir.
- **Notifications** : `ToolingService` filtre la décision pure par les
  interrupteurs (Sync/Build ; Taches toujours actif — le sélecteur est
  son propre résultat) ; un canal coupé ne montre ni détail ni résultat,
  l'obligation de premier plan tient avec une notification neutre. Le
  réglage Son choisit entre deux canaux (silencieux IMPORTANCE_LOW /
  sonore IMPORTANCE_DEFAULT) — jamais de mutation d'un canal existant
  (importance figée par le système après création).

## Conséquences

- Atterrir sur `EditorActivity`, `TerminalActivity` ou depuis une
  notification applique Material You sans passer par `MainActivity` ;
  `CrashActivity` respecte le thème (clair/sombre + dynamique) sans
  lire Room/DataStore/Hilt — une lecture SharedPreferences minime et
  assumée dans son processus.
- Première exécution après migration v0.33.x : le miroir vide retombe
  sur les défauts ; la première émission de `observeSettings` le
  re-converge (l'activité au premier plan est corrigée par la logique
  existante de `MainActivity`).
- Les tokens étendus sont consommables en XML (`?attr/colorJournalInfo`)
  et en Kotlin (`MaterialColors.getColor(view, R.attr.colorCanalSync)`)
  ; l'harmonisation ne concerne que les couleurs de marque.
- `feature:editor` et `feature:terminal` ne définissent plus AUCUNE
  couleur — les ressources partagées vivent dans `core:ui` (préfixe
  `codeide_`, déclinaison jour/nuit au même endroit).
- L'écran Paramètres expose des sections dont certaines ne font que
  persister (Éditeur) : c'est voulu — le modèle de données et l'écran
  existent, les moteurs s'y abonnent en arrivant, sans nouvelle migration.
- IA, Outils de développement et Sécurité : AUCUN champ inventé —
  l'écran minimal attend la confirmation du contenu (Olson).
