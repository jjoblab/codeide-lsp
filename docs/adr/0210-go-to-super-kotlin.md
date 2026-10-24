# ADR 0210 — GO TO « Super » Kotlin (`textDocument/superDefinition`)

- **Statut** : appliqué (24/10/2026 — panneau GO TO symétrique aux deux langues ;
  moteur + hôte + tests, scénario E9 du harnais verrouillé au fil du transport
  réel avec le câble cel-lsp exact).
- **Contexte** : le menu contextuel unifié GO TO de CodeIDE (miroir du NavMenu
  du projet tiers de référence) offre « Implementations » et « Super » sur les
  fichiers JAVA depuis la v0.134.x — `textDocument/superDefinition`, méthode
  LSP PERSONNALISÉE (aucun équivalent standard), annoncée dans
  `experimental.superDefinitionProvider` et consommée par cel-lsp. Les
  fichiers KOTLIN n'avaient AUCUNE option Super : la découverte
  `implementations`/`typeDefinition` existait (preuve par FQCN), mais le
  « même-nommé dans le supertype » — le geste de navigation quotidienne du
  `@Override` — manquait. Le moteur Kotlin (parseur vendoré, sans bindings)
  ne pouvait pas copier le chemin ecj : il lui fallait la PREUVE
  `fqcnDuNomEcrit` et une lecture bornée des fichiers déclarants —
  l'infrastructure des ADR 0208 (port de lecture) et 0205 (alias) venait
  justement d'être posée.
- **Options considérées** :
  1. **Moteur par preuve FQCN + port de fichiers + miroir LSP du serveur Java**
     — `SuperKotlin` : (1) chemin MEMBRE, la cible occurrences prouve le membre
     (déclaration outrepassante ou usage résolu), chaque supertype TRANSITIF
     projet de la clôture BFS (garde anti-cycle, plafonds profondeur/cibles)
     contribue ses homonymes de même genre (libellé `« name  ·  Super »`) ;
     (2) chemin TYPE, les supertypes DIRECTS ÉCRITS du type en contexte
     (référence de type, à défaut classe englobante — miroir `contextType`
     ecj), libellé `« Simple  ·  pkg »`. L'hôte sert la méthode custom par le
     MÊME transport que le serveur Java (interface covariante
     `@JsonDelegate` serveur-côté, DTO `NavigationTarget` partagé).
  2. **Attacher les supertypes au scan** — rendre le scan porteur des clauses
     pour tout prouver sans lecture : alourdit `DeclarationProjet` pour un
     consommateur et se périmerait aux éditions (le même argument que l'ADR
     0208, option 2) — la clause ÉCRITE vit dans l'arbre, seule la vérité du
     moment la prouve.
  3. **Ne pas livrer** — l'option Super est un geste de premier plan
     (`@Override` → déclaration parent) : non recevable.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - Serveur : `ServiceDocumentsNavKotlin` (interface `@JsonRequest` covariante,
    `getTextDocumentService()` redéclaré `@JsonDelegate` — le piège LSP4J des
    méthodes custom, miroir de `NavLanguageServer`) ; réponse
    `List<NavigationTarget>` (chemin ABSOLU + plage du nom en OFFSETS +
    libellé — aucun round-trip Position↔offset côté client, contrat Java) ;
    capability `experimental.superDefinitionProvider` (clé que cel-lsp
    recherche, pattern jdt.ls).
  - Client (cel-lsp, découvert en éprouvant le transport réel) : les méthodes
    custom du client production vivent AU SOMMET de son interface
    (`CodeIdeLanguageServer extends LanguageServer` + `@JsonRequest`) — PAS de
    redéclaration covariante `@JsonDelegate` côté client (le check
    « Duplicate RPC method », apparu dans lsp4j 0.22, la refuse ; la réponse
    d'une méthode inconnue du proxy se désérialise en null). Le harnais
    (`NavServeurKotlinHarnais`) en est le miroir exact — appel TYPÉ
    `superDefinition(params)`.
  - Bornes honnêtes (D3) : supertype du CLASSPATH sans source exclu (comme
    ecj) ; sources JAVA du scan jamais candidates (mêmes bornes que
    `implementations`) ; usage AMBIGU d'un membre homonyme → vide (le moteur
    ne présage jamais le membre d'un récepteur de type projet — borne O3,
    miroir du renommage) ; surcharges homonymes du supertype toutes listées
    (le libellé disambiguïse).
- **Conséquences** :
  - Le panneau GO TO de CodeIDE est SYMÉTRIQUE Kotlin/Java sur les quatre
    gestures : Déclaration, Type, Implémentations, Super (+ références) —
    `experimental.superDefinitionProvider` annoncé par les deux serveurs, le
    même câble cel-lsp le consomme.
  - Coût : lecture bornée d'UN fichier par maillon de chaîne projet
    (plafonnée), arbre vif pour les documents ouverts — le budget du port
    ADR 0208, zéro état inter-requêtes.
  - Tests : moteur `SuperKotlinTest` (10 : direct, transitif deux niveaux,
    interface, usage ambigu—borne O3, usage unique, propriété non
    outrepassante→retombée type, référence de type, racine sans clause,
    classpath, inconnu/fermé) ; hôte L2 in-process (capability +
    transport) ; harnais E9 (méthode + propriété + garde au fil du transport
    réel, appel par le câble cel-lsp) ; TABLE_KOTLIN de la parité L0 gains
    `super-définition (expérimental)`.
  - Le chemin MEMBRE se fonde sur la preuve occurrences : tout renforcement
    futur de la résolution de récepteurs de type projet (O3) étend
    MÉCANIQUEMENT la couverture du Super aux usages qualifiés.
