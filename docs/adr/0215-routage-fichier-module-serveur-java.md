# ADR 0215 — Routage fichier → module : le serveur LSP Java consulte le moteur du module propriétaire

- **Statut** : appliqué (24/10/2026 — découverte D6 du rapport d'audit
  §14, retour propriétaire « Greeter cannot be resolved to a type » /
  « ActivityMainBinding cannot be resolved to a type » sur un projet
  Android de deux fichiers du même paquet).
- **Contexte** : le lot E (docs/LSP_JAVA.md §15.7) a livré UN contexte
  de compilation PAR MODULE Gradle — `CompilationEnvironment.jdt(module)`
  construit le moteur de `:app` avec SES racines sources, SES jars et
  SON android.jar du modèle (ADR 0112) ; l'orchestrateur de l'hôte pousse
  ces contextes à chaque sync (`remplacerContexteModule`). Or TOUS les
  services du serveur LSP Java (diagnostics, hover, complétion,
  définition, typeDefinition, implementation, superDefinition,
  references, rename, codeAction, folding, inlayHints, codeLens,
  hiérarchies, semanticTokens, workspace/symbol) consultaient
  `environment.jdt()` — le contexte DÉFAUT, qui ne porte que
  l'android.jar du terminal (branché à l'ouverture, lot h4 écart 1).
  Conséquence vécue par le propriétaire : `Greeter` (type du MÊME PAQUET,
  sans import — ne résout QUE sur les racines sources du module) et
  `ActivityMainBinding` (racine `generated` du modèle — ViewBinding)
  restaient « cannot be resolved » BIEN QUE le modèle les eût nourris.
  L'association fichier → module existait côté modèle
  (`AssociationFichier.associer`, paquet `lsp:model`) sans être branchée
  NULLE PART.
- **Options considérées** :
  1. **Routage à chaque requête par couture injectable** (retenue) —
     `CompilationEnvironment.setRouteurModules(RouteurModules)` :
     l'hôte pose un routeur qui lit l'INSTANTANÉ COURANT du dépôt de
     modèle (`DepotModeleLsp.instantane` + `AssociationFichier`), et le
     nouveau point d'entrée `jdtPourFichier(chemin)` délègue à
     `jdt(module)`. Le routeur est CONSULTÉ à chaque requête (jamais mis
     en cache par l'environnement) : la sync suivante route sans re-pose.
  2. **Pousser les listes au contexte défaut** (`replaceAll` global,
     union de tous les modules) : détruit l'isolation par module du lot
     E (une complétion de :a ne doit pas bloquer le diagnostic de :b),
     mélange des android.jar de variantes, rejeté.
  3. **Routage côté app** (l'app passe le module à chaque requête) :
     double la surface d'API entre l'app et les façades pour chaque
     handler, et les serveurs LSP tiers (harnais, packs anciens) n'ont
     pas de routeur — rejeté.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - **Compatibilité intégrale** : sans routeur posé (tests directs du
    moteur, packs antérieurs, façade de test), `jdtPourFichier` délègue
    au contexte défaut — comportement HISTORIQUE au bit près. La pose du
    routeur est le SEUL changement requis côté hôte (SessionLsp, 5
    lignes).
  - **Robustesse** : un routeur fautif ne casse JAMAIS une requête —
    repli explicite sur le défaut (journal FINE). Un fichier hors
    sourcesets connus route vers le défaut (et son moteur reste
    utilisable — android.jar plateforme).
  - **Garde paresseuse par module** : `isEcjInitialisePour(chemin)`
    (miroir routé de `isEcjInitialized`) protège `didChange`/`didSave`
    d'un cold start synchrone de `buildJarIndex` sur le fil de frappe.
  - **Requêtes SANS fichier porteur** : `workspace/symbol` unifie
    DÉFAUT (paresseux, miroir historique — il peut être nourri par
    `replaceAll` sans apparaître dans la map des modules) + moteurs de
    modules VIVANTS (jamais construits pour une sonde), plafond global
    dédupliqué ; `decompiledSource(fqcn)` sonde les moteurs vivants
    (le classpath pertinent est celui du module APPELANT, perdu à cette
    frontière) ; `symboleDe` pareil pour la source attachée.
  - **Pré-chauffe** : `initialized()` → `prechaufferTousLesMoteurs()` —
    le défaut (comportement historique) + les moteurs des modules
    NOURRIS (contextes non vides) : le premier didOpen d'un fichier de
    :app ne paie plus le cold start de SON moteur.
  - **Navigation** : `EcjNavigationEngine` (balayage des racines
    sources) devient UN wrapper PAR moteur (WeakHashMap par identité —
    un moteur invalidé disparaît au GC) au lieu d'un singleton capturé
    sur le défaut.
  - **Downgrade ViewBinding** (découverte connexe E2) : les classes de
    liaison ViewBinding vivent AUSSI sous `<pkg>.databinding` — la
    sonde et le downgrade ERROR→HINT existants les couvraient déjà, le
    MESSAGE seul parlait de « Data Binding » ; il parle désormais des
    classes de liaison (ViewBinding/DataBinding), générées par AGP au
    BUILD (la sync ne les crée pas).
- **Preuves E2E** (harnais stdio, transport réel JSON-RPC —
  `ScenariosJavaAndroidTest`, serveur `ServeurStdioJava` branché sur la
  VRAIE `SessionLsp`, sync rejouée par notification
  `harnais/appliquerClasspath` APRÈS le didOpen) :
  - **D1** : avant sync, le régime défaut publie les erreurs du
    propriétaire (Greeter + ViewBinding) ; la sync → moteur :app →
    ContextListener → live-republish → PLUS AUCUN « cannot be
    resolved », sans frappe ;
  - **D2/D4** : `definition` sur `Greeter` atterrit dans Greeter.java,
    sur `ActivityMainBinding` dans la racine `generated` du module ;
  - **D3** : `references` trouve les occurrences du même paquet ;
  - **D5** : `setContentView(View)` hérité de la plateforme jamais
    signalé (bindings ecj) ;
  - **D6** : les capacités go-to (definition, typeDefinition,
    implementation, references, superDefinition expérimental) annoncées
    au fil du transport — le branchement CodeIDE
    (CoordonnateurLsp → LspProject → code-editor) consomme ces
    capacités, rétablies de bout en bout par le routage.
- **Conséquences** :
  - Les go-to Java (definition/implementation/typeDefinition/references/
    super) deviennent UTILES sur les projets réels : le moteur balaye
    LES racines du module (sources + generated), pas un contexte vide.
  - `lsp:model` : `AssociationFichier` passe de code mort à couture
    centrale (consommée par le routeur du hôte).
  - Le serveur Kotlin n'est PAS concerné (il consomme l'UNION de
    l'index fusion — la vue classepath est déjà globale par
    construction, ADR 0211).
  - Tests : 8 unitaires (`CompilationEnvironmentRouteurTest` — routage,
    instantané vivant, repli sur routeur fautif, isolation des modules,
    garde paresseuse, moteurs vivants, invalidation ciblée) + 7
    scénarios E2E.
