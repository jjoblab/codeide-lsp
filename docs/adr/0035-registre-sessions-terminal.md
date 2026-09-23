# ADR 0035 — Sessions de terminal : registre singleton, service specialUse et throttle

- **Statut** : accepté (étape 22 = Terminal T4, v0.23.0)
- **Contexte** : prompt compagnon « Terminal intégré et bootstrap natif »,
  sections 1.5, 2.2 et 4 ; ADR 0032/0033 (localisation, environnement,
  installateur) ; `applicationId` verrouillé `jo.codeide` (contrainte
  `.rodata` des binaires ELF du bootstrap).

## Décision

1. **Registre singleton Hilt lié à l'`Application`, pas au service.** Le
   prompt (section 4.2) laisse le choix entre un registre lié au service
   et un singleton Hilt : le singleton est retenu car les **deux** points
   d'entrée UI (accueil, tiroir de l'éditeur — T6) doivent lire les
   métadonnées sans attendre un bind de service, et parce qu'une session
   créée doit survivre à un écran sans service visible. Le service
   `TerminalService` ne garde qu'une responsabilité : **maintenir en vie
   hors écran** avec une notification honnête — il observe le registre et
   s'arrête de lui-même dès qu'aucune session ne vit.
2. **Mécanisme dédié, distinct de `NativeProcessLauncher`.** Une session
   shell interactive exige un pseudo-terminal (contrôle de tâches,
   couleurs, redimensionnement) : c'est le constructeur Termux
   `TerminalSession(shell, cwd, args, env, transcriptRows, client)` —
   jamais un `ProcessBuilder`. L'environnement du tableau `"KEY=VALUE"`
   provient **exactement** de `ProcessEnvironmentProvider.baseEnvironment()`
   (source unique de vérité, ADR 0032), complété de `TERM=xterm-256color`
   (capacités de couleurs annoncées au shell) ; le shell par défaut vient
   de `ToolchainLocator.defaultShell()`.
3. **Deux ports, un seul registre.** `TerminalSessionRepository` (métadonnées
   sans type Termux, dans `core:domain` — consommable par `feature:editor`
   sans dépendance `com.termux:*`) et `TerminalRuntime`
   (`sessionFor(id) → TerminalSession?`, **hors domaine**, section 4.4 du
   prompt — seul `feature:terminal` peut le consommer pour brancher le
   `TerminalView`). L'implémentation `RegistreSessionsTermux` sert les
   deux liaisons Hilt.
4. **Sémantique de liste.** Une session terminée **naturellement**
   (`exit`) reste visible avec `isAlive = false` — seul `closeSession`
   retire l'entrée et termine réellement le shell (jamais « juste masqué »,
   section 5 de fin d'onglet). La session active rebascule sur la
   dernière restante ; la première session créée devient active.
5. **Throttle de publication.** Les changements de sortie sont publiés
   après une fenêtre de conflation de **250 ms** (une rafale = une
   publication) et l'aperçu est borné à **160 caractères**, retours ligne
   replatés — la carte d'aperçu du tiroir (T6) ne doit pas être réveillée
   à chaque caractère (section 4.3 du prompt). Les terminaisons et
   renommages sont publiés immédiatement.
6. **Service foreground `specialUse`.** Aucun type normalisé ne décrit
   « garder des shells interactifs en vie » : le service déclare
   `foregroundServiceType="specialUse"` avec le sous-type
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` documenté (« Maintient des sessions
   de terminal interactives ouvertes tant que l'utilisateur ne les ferme
   pas ») ; `START_STICKY` — après une mort du processus, le service
   repart, constate un registre vide et s'arrête proprement. La décision
   notification/arrêt (`DecisionServiceTerminal`) est une fonction pure
   testée : notification tant qu'au moins une session vit, arrêt sinon.
   Permissions déclarées dans le manifeste **du module** (fusion dans
   l'app) : `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
   `POST_NOTIFICATIONS`.
7. **Indirection de test `CoquilleSession`/`FabriqueCoquilles`.** Le
   registre ne dialogue jamais avec `TerminalSession` en direct : les
   tests servent des coquilles scriptées (aucune exécution réelle de pty
   dans la suite JVM — section 10 du prompt). L'adaptateur
   `TerminalSessionClient` traduit les callbacks Termux ; les
   sollicitations de rendu (presse-papiers, cloche, couleurs) restent
   muettes dans le runtime — le rendu vit dans `feature:terminal`.

## `termux-shared` refusé pour raison de licence

Le prompt (section 5) prévoit `ExtraKeysView` de
`com.termux:termux-shared` pour le clavier étendu de T5, en assumant une
licence MIT pour `com.termux.shared.terminal.io.*`. **Vérification du
2026-09-23** (fichier `LICENSE.md` de v0.118.3) : les exceptions MIT du
dépôt ne couvrent **pas** `terminal/io/extrakeys` — l'import exposerait
du code GPLv3 à CodeIDE (« tous droits réservés » à ce stade). Décision :
le clavier étendu de T5 sera **implémenté en interne** (configuration
déclarative équivalente), sans `termux-shared`. `THIRD_PARTY_NOTICES.md`
documente ce refus ; il sera réexaminé si la licence du projet évolue.

## Alternatives rejetées

- **Registre lié au cycle de vie du service** : obligerait les deux
  points d'entrée UI à binder le service pour lire des métadonnées, et
  lierait la visibilité de la liste à la durée de vie d'un composant
  qu'on veut justement pouvoir arrêter.
- **Sessions dans des fragments/ViewModel d'écran** : contredit la
  décision « sessions globales » (section 1.5 du prompt) — la même liste
  doit être visible de l'accueil et du tiroir, et survivre aux rotations.
- **Publication à chaque caractère** : réveillerait la carte d'aperçu du
  tiroir en continu (section 4.3) — fenêtre de throttle retenue.
- **Masquer les sessions mortes** : la carte d'aperçu et les onglets ont
  besoin de distinguer « vivante »/« terminée » ; une entrée morte reste
  donc visible jusqu'à fermeture explicite.

## Conséquences

- `applicationId` reste `jo.codeide` (vérifié — contrainte `.rodata`).
- **`libtermux.so` n'est pas alignée 16 KB** (contrôle `Aligned16KB` en
  erreur lint) : vérifié le 2026-09-23 sur toutes les versions publiées —
  v0.118.3 **et** v0.119.0-beta.3 (`p_align` 4096) — le binaire est compilé
  en amont, rien n'est actionnable côté app. Exception lint ciblée et
  commentée (règle 9) dans le build du module, signalée pour suivi amont
  Termux ; à retirer dès qu'une release alignée paraît.
- **La question `targetSdk` reste ouverte** (section 1.2 du prompt) :
  l'exécution des binaires du stockage privé n'est pas testée
  empiriquement ici — les tests T4 sont JVM avec coquilles fausses ;
  l'ADR dédiée est planifiée avec les tests d'installation réels
  (ROADMAP, étape T7), la valeur actuelle restant 37 pour toute l'app.
- `core:testing` fournit désormais `FakeTerminalSessionRepository`
  (section 2.2 du prompt) pour la carte d'aperçu du tiroir (T6).
- La survie en arrière-plan (notification honnête, arrêt automatique)
  est couverte par TESTS_MANUELS E53-E56 sur appareil réel.
- Le branchement de l'app dépend de `:core:terminal-runtime`
  (agrégation Hilt + fusion du manifeste du service) dès v0.23.0, alors
  qu'aucun écran ne consomme encore le port — l'écran arrive en T5.
