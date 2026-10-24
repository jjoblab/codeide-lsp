# ADR 0216 — Diagnostic `kt.unresolved` : membres HÉRITÉS et segments de TYPES QUALIFIÉS

- **Statut** : appliqué (24/10/2026 — découvertes E3 et E4 du rapport
  d'audit §14, retour propriétaire « Référence non résolue :
  « setContentView » » / « « instantiate » » et « beaucoup de
  diagnostics unresolved » sur un projet Android Compose).
- **Contexte** : deux familles de FAUX POSITIFS du diagnostic
  `kt.unresolved` sur des projets Android réels :
  - **E3 — membres hérités** : la résolution des références NUES
    (`ResolutionReferences.estResolu`, K4) couvrait les membres
    DÉCLARÉS des classes englobantes (voie 2) mais JAMAIS les membres
    HÉRITÉS — un appel NU à `setContentView(…)` dans un
    `class MainActivity : AppCompatActivity()` ou `instantiate(…)` dans
    un `FragmentFactory` était signalé « Référence non résolue » alors
    que le classpath PORTAIT la classe et sa méthode : les briques
    existaient (`chaineSupertypes`, `methodesNommees`, port fichier du
    GO TO Super) sans être branchées sur le diagnostic.
  - **E4 — segments de types qualifiés** : un type écrit QUALIFIÉ
    (`android.os.Bundle` — grammaire vendorée PSI : USER_TYPE IMBRIQUÉS
    par segment) produit TROIS `REFERENCE_EXPRESSION` (android, os,
    Bundle) que la résolution de NOM ne résout pas individuellement —
    d'où le « BEAUCOUP d'unresolved » du propriétaire (le template
    Android écrit des types qualifiés partout où les imports manquent).
- **Options considérées** :
  1. **Voie « membres hérités » par fermeture mixte classpath+projet**
     (retenue) — BFS borné sur les supertypes ÉCRITS de la classe
     englobante, résolus en FQCN par les imports du fichier DÉCLARANT :
     les maillons CLASSPATH sont sondés par
     `methodesNommees`/`champsNommes` (chaîne binaire ENTIÈRE — premier
     porteur gagne), les maillons PROJET par leurs membres DÉCLARÉS puis
     leur clause relue dans LEUR fichier (port `SuperKotlin.PortFichier`
     — arbre vif du frère OUVERT, lecture disque + parse bornées sinon).
  2. **Sous-rapport global sur tout nom nu dans une classe** : masque
     de VRAIS négatifs (typo dans un corps de classe) — rejeté.
  3. **Complétion de la résolution par types complets (typage K4
     complet)** : hors budget du modèle K3/K4 actuel (pas de sélection
     par types complets documentée LSP_KOTLIN §4.3) — rejeté pour
     maintenant.
- **Décision** : retenir l'**option 1** pour E3, plus la garde segment
  pour E4. Détails décisifs :
  - **Voie 2-ter (`estMembreHerite`)** : maillon de départ prouvé par le
    FQCN de la chaîne des classes ENGLOBANTES (paquet + imbrication) ;
    clauses ÉCRITES du nœud le plus interne (arbre vif du document,
    AUCUN port requis) ; bornes honnêtes D3 : profondeur 16 (miroir
    SuperKotlin), budget de sondages 96, garde anti-cycle par FQCN,
    source JAVA du scan jamais traversée (le moteur Kotlin ne prouve
    pas l'héritage d'une source Java). Le prédicat est PARTAGÉ par le
    diagnostic ET le quick-fix d'import (`CandidatsImport.fqcns`) : un
    appel NU déjà résolu par héritage ne propose JAMAIS d'import.
  - **E4 (`estSegmentTypeQualifie`)** : le type COMPLET (l'USER_TYPE le
    plus EXTERNE de la chaîne CONTIGUË — sans traverser
    TYPE_ARGUMENT_LIST) est éprouvé contre la VUE du classpath
    (`membresDe`), les classes projet et le scan ; s'il RÉSOUT, TOUS
    ses segments sont muets ; s'il est INTROUVABLE, seul le DERNIER
    segment (nom de classe — celui que l'utilisateur corrige) reste
    diagnostiqué par la voie normale, les segments PAQUET ne le sont
    JAMAIS (miroir kotlinc : UN segment faillible, pas trois faux
    positifs).
  - **Le port vit dans le contexte** : `DiagnosticsSemantiques.Contexte`
    gagne `portFichier` (défaut `null` = comportement antérieur — fermeture
    limitée aux clauses écrites du document) ; la session de production
    fournit TOUJOURS le port du GO TO Super
    (`portFichierDeclaration`) pour les diagnostics ET les quick-fixes.
- **Preuves** :
  - **13 tests unitaires** (`SessionLangageKotlinHeritageTest`) :
    méthodes/champs hérités directs classpath (setContentView,
    findViewById, moveTaskToBack, mFenetre, instantiate), chaîne
    PROJET→classpath (frère fermé ET ouvert — arbre vif), membres
    DÉCLARÉS du frère projet, régressions (vrai inconnu, méthode absente
    du supertype, classe sans supertype), quick-fix muet sur membre
    hérité, segments de types qualifiés existants muets / inexistant →
    UN seul segment faillible.
  - **4 scénarios E2E** (`ScenariosKotlinHeritageTest`, stdio, classpath
    de production kotlin-stdlib + android.jar 37.2) : KH1
    `MonActivite : Activity()` + `android.os.Bundle?` qualifié → AUCUN
    kt.unresolved ; KH2 régression vrai négatif ; KH3 chaîne
    Enfant→BaseActivite(ouvert)→Activity(classpath) ; KH4 sans
    supertype, la voie ne prouve rien.
- **Conséquences** :
  - Le `kt.unresolved` redevient UN diagnostic UTILE sur les projets
    Android : les faux positifs par héritage et par segments qualifiés
    disparaissent ; les vrais négatifs (typos, méthodes inexistantes)
    restent signalés.
  - Le quick-fix d'import ne propose plus d'import absurde pour un
    appel résolu par héritage.
  - La résolution reste SANS sélection par types complets (surcharges
    homonymes du supertype toutes « résolvantes » — conservateur,
    documenté) ; l'arité n'est pas contrôlée sur les membres binaires
    (D3 inchangé).
  - Aucun changement de l'API publique des façades ; le port est interne
    au moteur Kotlin.
