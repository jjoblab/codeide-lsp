# ADR 0115 — LSP Kotlin : vendoring de la grammaire du compilateur (K1)

- **Statut** : accepté (v0.110.0, étape K1 de la mission « LSP Kotlin » —
  `docs/LSP_KOTLIN.md` D2, étude comparative projet de référence Task 12)
- **Contexte** : le LSP Kotlin doit être in-process (jamais de serveur
  externe, cohérence avec :lsp:java/ADR 0109) et ne doit pas embarquer le
  compilateur complet (leçon projet de référence : « le frontend FIR à chaque
  frappe est trop lourd pour un éditeur sur appareil »). L'étudu projet de référence
  (auteur tiers, GPL-3.0 — enseignements d'architecture, zéro code réutilisé)
  a montré la voie : **le parseur multiplateforme du compilateur Kotlin
  lui-même** (`compiler/multiplatform-parsing`, Apache 2.0, ~25 fichiers)
  + un modèle de symboles maison derrière une façade `Kt*`.

## Décision

**Vendorer la grammaire officielle du compilateur Kotlin dans
`:lsp:kotlin`, octet pour octet, sans AUCUNE modification locale**, et
construire dessus notre façade et notre suite de parité.

1. **Source amont** : tag **v2.3.0** (commit
   `f95cb2f7693a15966fd8fd9b1d78d440545e7b66`) — première version publiée
   contenant `compiler/multiplatform-parsing`, et surtout **compilée par
   le compilateur bootstrap 2.2.x**, exactement la chaîne d'outil de
   CodeIDE (Kotlin Gradle plugin 2.2.10) : les sources vendorées
   compilent telles quelles avec notre compilateur, sans montée de
   version. Le choix d'un tag plus récent (2.4.x) imposerait un bootstrap
   2.3+ et donc une montée d'outillage hors périmètre K1.
2. **Dépendances amont en artefacts binaires** (pas vendorées) :
   `org.jetbrains:syntax-api:0.3.340` (version épinglée par le tag amont)
   via le dépôt `intellij-dependencies` de JetBrains (accès par
   cache-redirector, filtre de contenu strict dans settings.gradle.kts —
   le filtre doit admettre les variantes KMP `-jvm` et la dépendance
   `lang-syntax`) et `org.jetbrains:annotations:26.0.2` (Central).
3. **Zéro modification locale** — plus strict que projet de référence (qui
   modifie `LightSyntaxTree.kt` pour l'accès indexé et le re-parse
   incrémental) : ces besoins vivent chez nous dans `jo.lspkotlin.parse`
   (reconstruction de l'arbre immuable via l'API PUBLIQUE de syntax-api).
   Bénéfice : un re-sync amont ne peut jamais casser le build local, il
   ne reste qu'à relancer la suite de parité.
4. **Exemptions de qualité documentées** (précédent :lsp:java/ADR 0108) :
   - `explicitApi()` et `allWarningsAsErrors` ne s'appliquent pas au
     module (les sources amont n'ont ni API explicite ni garantie
     « zéro avertissement » sous notre compilateur) ;
   - detekt et spotless **excluent `src/main/vendor`** : on ne reformate
     jamais l'octet identique (la traçabilité du vendoring en dépend) ;
   - kover exclut `org.jetbrains.kotlin.kmp.*` de la vérification de
     couverture : la grammaire est attestée par la **suite de parité**
     (corpus du dépôt + fixtures + tolérance aux fautes + digeste de
     baseline), pas par notre couverture de lignes ; la couverture reste
     ≥ 80 % sur NOTRE code (mesurée K1 : 91,7 %).
5. **Suite de parité obligatoire** (D2 : « la parité devient une
   propriété vérifiée ») :
   - sweep du corpus : TOUT fichier `.kt` du dépôt (818 fichiers à K1)
     doit parser sans marqueur d'erreur ;
   - digeste structurel SHA-256 du corpus épinglé dans une baseline
     (`src/test/resources/parite/digeste-corpus.txt`) — régénération
     délibérée `-Dparite.majBase=true`, le diff fait partie de la revue
     d'un re-sync ;
   - tolérance : troncatures à chaque position, injections hostiles,
     suppressions pseudo-aléatoires déterministes — le parseur ne lève
     JAMAIS (D3 : les erreurs se transportent dans l'arbre) ;
   - budget de performance large sur le sweep (garde-fou contre les
     régressions algorithmiques).
6. **Découplage « pack langue » dès K1** (D6, décision propriétaire
   2026-10-13) : le module ne dépend d'AUCUN module interne
   (checkModuleDependencies) ; l'app ne le voit que par le SPI
   `jo.lspkotlin.api.MoteurLangageKotlin` derrière ServiceLoader
   (descripteur `META-INF/services`), et le descripteur de pack
   `jo/lspkotlin/pack/version.properties` (id, version, version minimale
   d'app, version de grammaire) est déjà embarqué. Le découplage RÉEL
   (pack téléchargeable) reste différé à post-stabilisation — cette
   architecture est le coût quasi nul qui le rendra peu coûteux.
7. **Mesure de taille dès K1** (risque §8) : tâche `rapportTaille` —
   jar K1 : **257 Ko** (66 classes vendorées, 30 classes nôtres) ; la
   cible T1 ≤ 4 Mo est respectée avec une marge très large (syntax-api et
   lang-syntax s'ajoutent côté APK, reste ≪ cible).
8. **Adaptation des tests amont** : l'algorithme de reconstruction de
   l'arbre depuis les marqueurs de production est adapté de
   `compiler/multiplatform-parsing/jvm/test/.../NewTestParser.kt`
   (Apache 2.0, JetBrains) — notice dans `docs/THIRD_PARTY_NOTICES.md`,
   licence et copyright amont copiés dans le répertoire vendor.

## Alternatives écartées

- **fwcd/kotlin-language-server** (processus externe + LSP + embarque le
  compilateur) : contraire à l'architecture in-process et à la leçon
  projet de référence (D1 de LSP_KOTLIN).
- **Analysis API K2 par frappe** : rejetée par projet de référence après l'avoir
  essayée (« too heavy ») ; réduite chez nous à un éventuel mode
  diagnostic profond à la demande.
- **Réécriture d'une grammaire maison** (ANTLR/tree-sitter) : la valeur
  du parseur est d'ÊTRE la grammaire du compilateur — une divergence
  apparaîtrait comme un faux diagnostic sous du code correct (leçon
  projet de référence, K1 de leur kotlin-syntax).
- **Vendoring 2.4.x** : imposerait une montée d'outillage Kotlin
  (bootstrap 2.3+) hors périmètre K1 ; différée jusqu'à nécessité.

## Conséquences

- Un re-sync amont (2×/an) est scripté
  (`scripts/vendor-grammaire-kotlin.sh`) : remplacement de répertoire +
  suite de parité + mise à jour de version de syntax-api.
- La façade `Kt*` (`jo.lspkotlin.parse.kt`) épingle le vocabulaire du
  compilateur (KtFile, KtClass, KtFunction…) : elle grandira avec K2/K3
  sans changer de convention.
- Découvertes de parité PSI documentées : une `PACKAGE_DIRECTIVE` vide
  est toujours présente ; les imports vivent sous `IMPORT_LIST` ; les
  jetons blancs sont des feuilles de l'arbre (à sauter dans les lectures).
- `:lsp:host` branchera la session Kotlin (K2) par le SPI, jamais par
  type concret — le pack future sera chargé par DexClassLoader avec le
  même descripteur.
