# ADR 0117 — Écran d'indexation LSP : pastille de toolbar + bottom sheet de détail

- **Statut** : brouillon (phase A, v0.124.0 — non finalisé tant que
  l'utilisateur n'a pas donné le GO pour la phase B). Voir
  `docs/audit/INDEXATION_PHASE_A.md` pour l'audit complet, le plan
  d'implémentation et la maquette HTML.
- **Contexte** : l'écran d'indexation actuel de CodeIDE est une ligne
  unique `barre_etat_lsp` (`activity_editor.xml` L72) : point d'état
  20 dp + texte « Indexation Java : 12/191 ». Cette ligne (a) consomme
  de la hauteur dans la colonne centrale (incident v0.100.2 déjà
  documenté), (b) n'affiche ni les avertissements ni les erreurs du
  modèle `EtatIndex` (pourtant disponibles), (c) ne couvre que Java
  (l'index Kotlin n'est pas publié en flux), (d) ne donne aucun détail
  « qu'est-ce qui est en train d'être indexé ? » ni « quel index coûte
  le plus de temps ? ».

## Décision recommandée

**Pastille de toolbar + bottom sheet de détail** (option C de l'audit).

1. **Pastille de toolbar** : indicateur animé + pourcentage pendant la
   construction, coche discrète au repos (masquée sur écran étroit),
   triangle d'avertissement en partiel, icône d'erreur en erreur.
2. **Bottom sheet de détail** (métaphore déjà utilisée par le panneau
   inférieur) : en-tête, barre de progression, compteur, liste de
   travail défilante, vue par moteur (Java + Kotlin), bilan de dernier
   passage, boutons Réindexer / Réessayer les échecs.
3. **Modèle d'état étendu** : ajouter à `EtatIndex` (lsp:index) les
   champs `listeTravail`, `breakdownIndexeurs`, `dureesPhases`,
   `bilanDernierPassage`, `settled`. Étendre `EtatIndexation` (lsp:host)
   pour transporter ces champs (ne plus perdre de données à la
   frontière). Créer `SessionLspKotlin.etatIndexation` (parallèle à
   Java).
4. **Présentateur pur** (patron `PresentationTooling.kt`) :
   `PresentationIndexation` transforme `EtatIndexation` (Java) +
   `EtatIndexationKotlin` (Kotlin) en `ModeleAffichageIndexation`
   (immutable, testé unitairement, sans référence Android).
5. **Vues XML + ViewBinding** (pas de Compose, ADR 0002).
6. **Pas de logique dans les vues** : libellés transportés par
   énumérations (jamais par chaînes comparables).
7. **Suppression de `barre_etat_lsp`** dans `activity_editor.xml`
   (uniquement ce retrait, signalé au prompt 1 pour ne pas marcher
   dessus).

## Options écartées

- **A — Ne rien changer** : ne répond pas au besoin.
- **B — Étendre la ligne** : reste limitée à une dimension.
- **D — Écran dédié plein écran** : perturbe le flux utilisateur.

## Conséquences

- **Positives** : le modèle riche `EtatIndex` devient visible. Aucun
  saut de mise en page. Vue par langage. Bilan persisté.
- **Négatives** : travail d'implémentation substantiel (cf. plan en
  8 lots, `docs/audit/INDEXATION_PHASE_A.md` §5).
- **Neutres** : la phase B devra décider de la fiabilité de
  l'estimation du temps restant.

## Hors périmètre

- L'agencement bottom sheet / clavier / barre de navigation
  d'`EditorActivity` (prompt 1).
- La logique interne de construction des index.
- La bibliothèque `code-editor`.

## Références

- `docs/audit/INDEXATION_PHASE_A.md` — audit complet (cartographie,
  inventaire, données disponibles/manquantes, contraintes
  d'architecture, impact, plan en 8 lots, maquette, questions
  ouvertes).
- ADR précédents : 0108 (mission LSP Java), 0109 (modèle processus
  in-process), 0110 (provenance licence), 0111 (index disque par
  segments par jar), 0112 (plateforme compilation android.jar), 0113
  (source classpath android modèle v2 AGP), 0114 (session projet
  registre fermeture), 0115 (lsp kotlin vendoring grammaire).
- Code source : `lsp/index/src/main/kotlin/jo/codeide/lsp/index/EtatIndex.kt`,
  `lsp/host/src/main/kotlin/jo/codeide/lsp/host/SessionLsp.kt` (L129, L156,
  L360-388, L437-476),
  `feature/editor/src/main/kotlin/jo/codeide/feature/editor/EditorActivity.kt`
  (L1861, L1930),
  `feature/editor/src/main/res/layout/activity_editor.xml` (L72).
- Maquette : `docs/mockups/indexation/indexation.html`.
