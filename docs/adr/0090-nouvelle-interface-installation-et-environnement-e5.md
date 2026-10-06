# ADR 0090 — Nouvelle interface d'installation et écran Environnement (E5)

- **Statut** : accepté (étape E5 de la refonte ; projette l'état du
  parcours établi par les ADR 0085/0087/0088/0089 — le prompt impose
  « maquettes validées, puis nouvelle interface d'installation et écran
  Environnement »)
- **Contexte** : cahier des charges § 7 (nouvelle interface et design),
  § 8 (diagnostic), § 11-E5. Le cadre est livré depuis E2 : orchestrateur
  `StateFlow`, service de premier plan avec action Annuler, journal
  expurgé borné à 200 lignes, `install-state.json` schéma 2.

## Maquettes

`docs/preview/installation-environnement.html` (HTML statique, comme
l'existant) couvre : téléphone — installation en cours (vitesse et temps
restant du téléchargement), consentement licence, échec avec sortie
réelle jointe, récapitulatif final, écran Environnement ; tablette
sw600dp — stepper à gauche, journal à droite. Le cahier demande des
maquettes « validées par l'utilisateur » avant l'implémentation ;
l'utilisateur a demandé la poursuite des étapes restantes sans
interruption : les maquettes sont donc **livrées avec** l'implémentation
dans la même version, toute retouche demandée sera intégrée à l'itération
suivante. Les captures de la maquette restent la référence visuelle des
états ci-dessous.

## Décision

### 1. Écran d'installation — projection pure d'un état déjà vivant

`InstallationFragment` + `InstallationViewModel` (feature/install,
vues XML + ViewBinding + Material 3, jetons du thème core:ui — aucune
couleur ni dimension hors ressources ; `installation_carte_marge` dans
`dimens.xml`).

- **Le ViewModel ne décide rien** : il expose `state`/`journal` de
  l'orchestrateur et traduit. Toute la logique de parcours vit dans
  core:bootstrap (testée, ADR 0087) ; l'écran survit à la rotation, à la
  mort du processus et à la fermeture de l'app parce que l'installation
  vit dans le service de premier plan — l'écran n'est qu'un observateur.
- **Stepper vertical de 4 cartes** (`carte_phase_installation.xml`) :
  titre, état (`En attente` / `En cours` / `Terminée` / `Dégradée` /
  `Échec`), détail (versions vérifiées une fois terminée, avertissements
  non critiques si dégradée, cause en clair si échec — jamais un échec
  sans sa sortie, critère § 13). Les cartes ne portent **pas** d'état
  « ignorée car déjà vérifiée » distinct : une phase déjà vérifiée
  s'affiche `Terminée` dès l'ouverture (verify-first, ADR 0087) — même
  rendu, information honnête.
- **Progression globale** : barre + « Étape N sur 4 » (N compte
  `Succeeded` et `Degraded`).
- **Journal en direct repliable** (lecture seule, monospace,
  sélectionnable donc copiable) dans le corps de l'écran ; bascule par
  bouton.
- **Tablette sw600dp** (`layout-sw600dp/fragment_installation.xml`,
  mêmes identifiants — une seule classe de liaison) : stepper à gauche,
  journal à droite **toujours visible** ; le bouton de bascule y est
  masqué (§ 7 « repliable » concerne le téléphone).
- **Actions contextuelles, masquées jamais grisées** : Annuler (exécution
  en cours), Réessayer cette phase (échec sans exécution), Copier le
  diagnostic (toujours), Ouvrir le terminal (toujours), Créer mon
  premier projet (parcours terminé).
- **Récapitulatif final** : versions vérifiées des phases + bouton
  « Créer mon premier projet » (navigation vers le wizard existant).
- TalkBack : icônes décoratives `contentDescription="@null"`, états
  portés par du texte, actions réelles (boutons) ; aucune animation
  ajoutée (« réduire les animations » respecté par construction).

### 2. Consentement licence — la phase 4 ne démarre jamais sans lui

Carte dédiée (lien vers la licence, résumé, case à cocher) affichée
entre la phase JAVA vérifiée et la phase `ANDROID_SDK` non commencée,
tant que `sdkLicenseAcceptedAtMillis` est `null` (§ 12.5, état persisté
E2). Le bouton « Installer le SDK Android » n'est activé qu'une fois la
case cochée — c'est le seul bouton activable du cahier (une action de
consentement se DOIT d'être verrouillée avant acceptation ; toutes les
autres actions restent masquées hors contexte, jamais grisées). La case
n'est réinitialisée qu'à l'**apparition** de la carte, jamais pendant
qu'elle est affichée : une ré-émission d'état ne décoche pas la case que
l'utilisateur vient de cocher.

### 3. Vitesse et temps restant — mesurés, jamais extrapolés

La maquette promet une « estimation du temps restant » globale et une
vitesse de téléchargement. Le cahier impose parallèlement de ne jamais
deviner (§ 0.2) et de ne présenter comme fait rien de non vérifié
(§ 14). Décision :

- **Vitesse et temps restant du téléchargement courant** : mesurés dans
  `InstallationViewModel` sur deux échantillons consécutifs de la même
  sous-étape (`EchantillonTelechargement` → `estimer`, fonction pure
  testée). Affichés (« 1,2 Mio/s — environ 1 min restante ») sous la
  barre globale. Sans mesure exploitable (premier échantillon,
  progression non croissante, temps nul), **rien n'est affiché** — pas
  de vitesse fabriquée.
- **Estimation GLOBALE du temps restant : divergence assumée** avec la
  maquette. Extrapoler « ~4 minutes restantes » exigerait des durées
  historiques de phases par appareil/réseau que le parcours ne possède
  pas ; une moyenne fabriquée serait une devinette présentée comme un
  fait (interdit § 14). L'entête affiche donc la phase courante, le
  compteur d'étapes et, quand elle est mesurée, l'estimation du
  téléchargement en cours — jamais une estimation globale inventée.
  Signalé au propriétaire dans le compte rendu E5.

### 4. Écran Environnement (Paramètres)

`EnvironnementFragment` + `EnvironnementViewModel` + `ComposantsEnvAdapter`
(feature/settings), destination `settings_environnement` du graphe.

- **Rangée du maître** : la section « Outils de développement — bientôt
  disponible » (placeholder `SectionParametres.OUTILS`, écran « bientôt »)
  est **remplacée** par la section réelle `SectionParametres.ENVIRONNEMENT`
  dans la carte Environnement du maître. L'enum `OUTILS` et l'écran
  « bientôt outils » associé sont supprimés (pas de code mort) ; `IA` et
  `Sécurité` restent « bientôt ».
- **Composants** : une rangée par composant du magasin (quadruplets du
  schéma 2) avec **taille réelle mesurée sur disque** par le port
  `AuditeurComposants` (core:domain, implémentation Android dans
  core:bootstrap : marche récursive de l'`installPath` sous la racine du
  SDK, lecture seule — l'orchestrateur reste le seul décideur). La
  présence réelle de l'`installPath` fait foi pour l'état « vérifié » ;
  une taille inconnue s'affiche « absent du disque », jamais « 0 Mio ».
- **Rangée JDK** : le JDK n'est pas un composant du manifeste (installé
  par `pkg` en phase 3) ; une rangée informative est projetée depuis la
  phase JAVA vérifiée (version mesurée par `java -version`, E3), taille
  réelle du `JAVA_HOME` résolu (règle unique `LocalisationOutils`),
  libellée « paquet APT », **non désinstallable** depuis cet écran (un
  `pkg uninstall` relève du terminal, pas d'un bouton de réglages).
- **Actions** : Vérifier (légère) et Vérification approfondie
  (`verify(deep)` — projet généré + `assembleDebug` réel, ADR 0089),
  Réparer (première phase non vérifiée, puis première phase en échec),
  Désinstaller par composant **avec confirmation**
  (`MaterialAlertDialogBuilder`), Copier le diagnostic.
- **Désinstallation** : port interne `DesinstalleurComposants`
  (suppression récursive de l'`installPath`, § 12.3 « désinstaller =
  supprimer l'installPath », idempotent), orchestrée par
  `EnvironmentSetupOrchestrator.uninstallComponent` — le retrait du
  quadruplet de l'état persisté et le journal passent par l'orchestrateur
  seul décideur ; une phase porteuse n'est jamais retirée : le composant
  se réinstalle par `repair` (§ 12.4, péremption par écart au plan).

### 5. Diagnostic copiable partagé, en codes neutres

`DiagnosticInstallation` (core:domain, testé) produit le diagnostic
presse-papiers : journal intégral (expurgé par le cadre) puis une ligne
par phase en **codes techniques neutres** (`ANDROID_SDK : DEGRADED
(cmdline-tools)`). Décision de localisation : le diagnostic est un
rapport technique ; les libellés localisés vivent dans les resources des
features pour l'interface, mais aucun texte métier n'est codé en dur en
Kotlin — les codes d'enum et mots-clés SUCCEEDED/DEGRADED/FAILED/
RUNNING/NOT_STARTED sont volontairement en anglais technique, comme les
journaux. Les deux écrans partagent ce formateur (pas de duplication).

### 6. Ce que cet écran ne fait pas

- Il ne déclenche **pas** d'installation au seul fait d'être ouvert
  (le service de premier plan et l'accueil restent les déclencheurs,
  ADR 0087) ; il propose « Démarrer l'installation » quand rien n'est
  vérifié et rien ne tourne.
- L'ancien écran (`InstallFragment`, `ClientTerminalMini`,
  `fragment_install.xml`, `InstallViewModel`) reste compilable et
  **inatteignable** (la destination `installation` du graphe pointe
  désormais vers `InstallationFragment`) ; sa suppression est planifiée
  en E6 avec le reste de l'ancien parcours (§ 9) — pas de double
  suppression d'étape.

## Conséquences

- Deux nouveaux points d'entrée UI pour un état unique : tout passe par
  `EnvironmentSetupOrchestrator` (Hilt singleton de processus) — aucune
  divergence possible entre écrans.
- `SectionParametres.OUTILS` supprimé : les préférences sauvegardées ne
  référencent que des noms de sections pour l'écran « bientôt » ; aucun
  `valueOf` persistant ne peut rencontrer `OUTILS` (l'argument de
  navigation est jeté à la fermeture, repli défensif `else` conservé).
- Tests : traduction pure du ViewModel d'installation (compteur,
  sous-étape, vitesse/ETA, diagnostic — 13 tests), projection du
  ViewModel Environnement (composants, tailles, JDK, actions — 7 tests),
  formateur de diagnostic du domaine (6 tests) ; fakes partagés enrichis
  (`FakeEnvironmentSetupOrchestrator` compte les vérifications,
  `FakeAuditeurComposants` neuf).
- Non vérifié sur appareil dans cet environnement (pas d'appareil
  aarch64, règle 2 du cahier) : rendu TalkBack réel, pli du journal sur
  tablette, boîte de confirmation — procédures ajoutées à
  `docs/TESTS_MANUELS.md` pour E5.
