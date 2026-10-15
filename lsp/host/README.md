# :lsp:host — hôte LSP Java

Mission « LSP Java » **étape 4** (docs/LSP_JAVA.md §16, ADR 0109 D2) :
l'hôte isole le moteur vendored (`:lsp:java`) derrière des frontières
nettes — c'est le **point unique de changement de transport**
(in-process ART par défaut ; un transport socket vers un sous-processus
JDK resterait branchable sans réécriture).

## Contenu (h1, v0.99.0)

- **`SessionLsp`** — façade de cycle de vie : `appliquerClasspath`
  (post-sync), `reindexer` (action « Réindexer » de l'UI),
  `serveur()` (UN `JavaLanguageServer` LSP4J par connexion),
  `definition()` (branchement cel-lsp in-process), `modele` et
  `etatIndexation` (flux publics, découplés des types internes de
  l'index), `close()` (arrêt exhaustif — test de fuite, prompt 3).
- **`OrchestrateurLsp`** (interne) — `ClasspathResult` (protocole v8)
  → `ProjetLsp` (`:lsp:model`) → moteurs PAR module (API qualifiée du
  lot E, frères `MODULE` résolus en sources du frère) ; vue d'index
  FUSION sur l'union des classpaths (ADR 0111) ; **budget de cache
  dérivé du modèle** (leçon du banc lot F2 : la cible §9 est un budget
  PAR JAR — croissance monotone, recréation du service seulement si le
  corpus dépasse le budget courant).
- **`AdaptateurIndexNoms`** (interne) — adaptateur de PRODUCTION de la
  SPI `jo.lspjava.api.IndexNoms` : empreintes du MODÈLE (pas sha256 de
  test), transition PRÊT par collecte du `StateFlow` (P7/P8).

## Dépendances (section 5.2)

`lsp:java`, `lsp:index`, `lsp:model` (+ `cel-lsp` externe — réservé à
ce module, les features ne voient que `cel-ui`).

## Écart documenté

Les entrées `DOSSIER` du modèle ne sont pas indexées en ce premier
branchement (les sources du frère couvrent ses types) — évolution
possible, cf. LSP_JAVA.md §16.

## Branchement éditeur (h2)

`LspProject(racine)` + `project.addServerDefinition(session.definition())`
+ `project.createEditor(fichier)` → `LspEditor.setHost(LspEditorViewHost(EditorView))`
(depuis code-editor 3.71.0, plan 03 — l'adaptateur cel-ui implémente le
contrat `EditorHost` de `cel-lsp-api` ; `setEditorView` n'existe plus)
→ `connect()` — cf. LSP_JAVA.md §16.
