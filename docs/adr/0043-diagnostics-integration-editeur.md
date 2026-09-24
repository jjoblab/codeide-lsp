# ADR 0043 — Diagnostics du tooling et intégration éditeur (G5)

- **Statut** : accepté (étape G5, v0.30.0)
- **Contexte** : prompt compagnon Tooling, section 6 — le client (G3) et
  le daemon (G4) existent, l'éditeur a des onglets vides depuis 0.17.0
  (ADR 0029 : « point d'ancrage `session.setDiagnostics` documenté, non
  câblé »). G5 referme la boucle UI : onglet **Sortie** fonctionnel,
  onglet **Problèmes**, diagnostics **inline** dans les onglets ouverts,
  actions **Synchroniser** / **Exécuter**. Le serveur n'émettait AUCUN
  événement Diagnostic (constat G2) : il faut d'abord un producteur.

- **Décisions** :

1. **Producteur = extraction depuis stderr, pas une requête dédiée**.
   Les compilateurs (javac, kotlinc) écrivent leurs positions sur
   stderr, ligne par ligne, aux formats stables et documentés
   (`/chemin/F.java:12:3: error: message` et
   `e: file:///chemin/F.kt:12:3 message`) — le message d'échec final
   du build, lui, ne porte AUCUNE position. La sortie arrive déjà ligne
   par ligne (`StreamingOutputStream`, G2) : une couture `observateur`
   reçoit chaque ligne décodée AVANT publication, `BuildHandler` y
   branche `ParseurDiagnostics` et publie l'événement `Diagnostic` du
   protocole G1 (aucun message nouveau — le catalogue de 24 est
   inchangé, les fichiers dorés restent valides). Une ligne sans
   position complète (contexte, carets, notes de tâches) est ignorée :
   jamais de demi-renseignement (§1.6).

2. **Le domaine ne connaît que des use cases de délégation**.
   `SynchroniserProjetUseCase`, `ExecuterTachesUseCase`,
   `AnnulerBuildUseCase`, `ListerTachesProjetUseCase` délèguent au port
   `GradleToolingRepository` posé en G3 (zéro type tooling, règle §2.2).
   La résolution du dossier réel reste à l'APPELANT via
   `ResoudreRepertoireProjet` (SAF → FUSE) — exactement la traduction
   du terminal T6 : une seule source de vérité, les use cases restent
   JVM purs et testables. Journalisation identifiante : le dossier
   n'apparaît jamais dans le journal (règle 15).

3. **`GradleService` : détenteur d'état pur, fenêtre de sortie bornée**.
   Même précédent que `FiltrageProjets` (accueil) : classe pure sans
   Android ni Hilt, le ViewModel publie, l'activité observe. La console
   n'est qu'une VUE : fenêtre bornée à 2 000 lignes (tête tronquée) —
   la sortie complète vit dans le canal rejouable du client (ADR 0041,
   tampon pré-abonnement) ; un build bavard ne mange pas la mémoire de
   l'appareil. Le build SUIVI seul alimente la console (les autres
   builds sont ignorés), l'état porte statut/durée/échec + synchronisation.

4. **Diagnostics inline par SUFFIXE de chemin relatif** (point d'ancrage
   ADR 0029 enfin câblé) : chaque onglet ouvert dont le chemin relatif
   est le suffixe d'un fichier diagnostiqué reçoit ses soulignés
   cel-ui (`session.setDiagnostics`, `DiagnosticShift` — sévérités
   1 info / 2 avertissement / 3 erreur), les autres onglets sont
   nettoyés à chaque publication. Le suffixe suffit parce que
   l'espace ne construit qu'UN projet à la fois et que le dossier FUSE
   réel peut être encore inconnu quand l'onglet est déjà ouvert
   (résolution différée) — un préfixe exigerait une résolution qu'on
   n'a pas. Offsets bornés au document (ligne, colonne, longueur).

5. **Onglet Problèmes : groupes aplatis, saut au document**. Les
   diagnostics sont groupés par fichier (en-tête replié nom + compte,
   tri par ligne), aplatís en rangées pour un `ListAdapter`. L'appui sur
   un diagnostic sélectionne l'onglet concerné, `scrollToLine` la ligne
   (bornée) et pose le curseur à son début (`Selection.cursor` sur
   `lineStart`) — même mécanique que la recherche, aucune API nouvelle.

6. **Actions de la toolbar + sélecteur de tâches**. Synchroniser et
   Exécuter… sont des items de menu (icônes maison, `ifRoom`) ;
   « Exécuter… » ouvre le sélecteur alimenté par
   `ListerTachesProjetUseCase` (le protocole documente `TasksResult`
   « prêt à afficher dans un sélecteur ») via un effet unidirectionnel —
   jamais d'accès direct au domaine depuis l'activité. L'annulation
   (bouton Arrêter de l'onglet Sortie, visible en vol seul) traverse le
   port jusqu'au jeton Gradle (`CancellationTokenSource`, §4.3 — posé
   en G2). Le journal unifié `gradle-server` du prompt est couvert par
   construction : le daemon G4 journalise déjà stderr/stdout du process.

- **Alternatives rejetées** : parser le message d'échec final (aucune
  position dedans — découverte d'ingénierie de cette étape) · une
  requête « diagnostics » dédiée dans le protocole (une 25ᵉ message
  pour répliquer ce que stderr transporte déjà) · publier les
  diagnostics seulement à la fin du build (l'utilisateur veut le retour
  PENDANT le build, la sortie est déjà ligne à ligne) · appariement par
  préfixe absolu (exigerait la résolution du dossier avant tout
  affichage) · fenêtre de console non bornée (la sortie complète vit
  dans le canal rejouable du client — la console n'est qu'une vue).

- **Conséquences** : l'éditeur consomme le tooling de bout en bout —
  les trois onglets du panneau inférieur (Sortie, Problèmes, Journal)
  sont vivants ; G6 hérite d'une boucle fermée à éprouver (chaos :
  process tué en build, socket perdue, version incompatible —
  `ServerVersion` 0.30.0 sonne déjà l'écart G2 → G5) ; la CI couvre le
  kover des modules touchés (seuil 80 %, core:domain et tooling:server
  modifiés — logique graduée Vérification-1 §2.6) ; la distance
  onglet ↔ diagnostic reste un suffixe, à durcir en G6 si la
  multi-ouverture d'espaces venait à exister.
