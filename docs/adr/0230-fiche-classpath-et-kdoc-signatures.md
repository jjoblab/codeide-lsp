# ADR 0230 — Fiche classpath au survol, KDoc aux signatures (chantier P1-D)

- **Statut** : appliqué (29/10/2026 — quatrième chantier P1 de la feuille
  de route du rapport d'analyse comparative du 28/10/2026 ; verrouillé
  par `FicheClasspathTest` (11 tests, octets de classe synthétiques
  super/interfaces réels) + le transport hôte (`KotlinLanguageServerTest`,
  1 test de la doc en plaintext ; `ScenariosSondesTest` S11 re-synchronisé
  sur la fiche), suite sans régression, baseline de parité
  re-synchronisée).
- **Contexte** : le rapport d'analyse qualifie la DOCUMENTATION de
  « retard visible » sur les deux surfaces où l'utilisateur l'attend :
  le survol d'un type du classpath ne rendait que `class fqcn` (une
  ligne sèche, quand Android Studio sert genre, hiérarchie et membres),
  et l'aide à la signature servait ses candidates SANS KDoc — le champ
  `documentation` du contrat neutre (`SignatureKotlin`) était
  structurellement `null` : « la doc des candidates hors document
  courant exigerait de re-parser des fichiers tiers, coût non assumé ».
  Or le MOTEUR a déjà tout ce qu'il faut : le survol projétaire porte
  sa KDoc depuis S2 (document courant) et ADR 0208 (port cross-fichier
  — arbre vif du document ouvert, sinon lecture disque + parse bornées,
  UNE fois par interrogation tierce) ; le lecteur de class-files maison
  sait lire superclasse et interfaces directes (ADR 0205/0219) ; la vue
  JVM porte champs et drapeaux. Il ne manquait que le BRANCHEMENT.
- **Options considérées** :
  1. (a) **Fiche structurelle binaire + réutilisation du port KDoc**
     (retenue) : le survol de type classpath rend une FICHE prouvée par
     la lecture brute du `.class` (genre, héritage direct, interfaces,
     constantes d'enum / champs publics) ; les candidates de signature
     PROJET réutilisent TEL QUEL le port `kdocDeclaration` de l'ADR 0208 ;
  2. (b) Servir la Javadoc des sources-jars — rejetée : les sources-jars
     ne sont pas attachées au classpath Android courant (K3+ les a
     écartées des définitions pour la même raison), et la dépendre
     rendrait la fiche vide dans l'usage dominant ;
  3. (c) Enrichir la fiche jusqu'aux méthodes et signatures complètes —
     rejetée : le survol de MEMBRE qualifié sert déjà les signatures
     (`fun requestLayout(): Unit — android.view.View`), la fiche de TYPE
     qui listerait TOUT dupliquerait la complétion membre au prix d'un
     parsing d'intégralité du `.class` par survol.
- **Décision** :
  1. **La fiche** (`resolve/FicheClasspath.kt`) : première ligne
     `[public] genre FQCN [extends super]` — genre lu des DRAPEAUX JVM
     (`interface`, `enum`, `class`), un `object` Kotlin détecté par son
     champ `INSTANCE` statique final (convention du compilateur, preuve
     binaire — pas une devinette) ; l'héritage ne s'affiche que pour un
     class/object dont la superclasse DIRECTE est lisible et DIFFÉRENTE
     des racines universelles (`java.lang.Object` pour tous, `java.lang.Enum`
     pour les enums — du bruit, jamais une information) ; une interface
     n'`extends` rien (ses interfaces sont servies en SECTION). Les
     lignes suivantes : `Interfaces : …` (directes, FQCN), puis pour une
     enum `Constantes : …` (champs `ACC_ENUM`, plafond 20) ou pour une
     classe `Champs publics : Type nom, …` (plafond 8) ; membres
     synthétiques (`$`) et marqueur `INSTANCE` exclus (le genre le dit
     déjà). Nouvelle sonde `ResolutionKotlin.infosClasse` (miroir de
     `membresDe` : variante aliasée JVM sondée au premier échec, `null`
     sur octets illisibles).
  2. **La garde D3** : sans `.class` lisible, la fiche rend le genre
     (drapeaux de la vue) et les champs, mais PAS d'héritage ni
     d'interfaces — chaque ligne née d'une preuve, jamais une ligne
     inventée ; le bytecode ne portant NI KDoc NI Javadoc, la fiche est
     STRUCTURELLE (c'est dit dans la doc du contrat).
  3. **La KDoc des signatures** (`resolve/SignaturesAppels.kt`) : le
     contexte gagne le port `kdocDeclaration` (même signature que celui
     du survol, ADR 0208 — injecté par la session, `null` par défaut
     pour les tests moteur purs et les packs K3) ; la candidate
     FONCTION de premier niveau, la candidate EXTENSION sur récepteur
     résolu et le MEMBRE VIF de la classe englobante portent leur KDoc
     (membre vif : KDoc de l'arbre courant, sans port — elle vit DANS
     le document). Les candidates du CLASSPATH (méthodes JVM, index
     d'extensions) restent sans doc : le bytecode et l'index érasé
     n'en portent pas. Le contrat `SignatureKotlin.documentation` est
     ré-ouvert (il annonçait « toujours null à S1 ») — l'hôte servait
     DÉJÀ le champ en plaintext quand présent, aucun changement de
     transport.
  4. **Le branchement session** : les TROIS points de construction du
     contexte de signatures (signature, indices en ligne, complétion
     contextuelle) filent le port — la même lambda
     `kdocDeDeclaration` (document ouvert → arbre vif, fichier fermé →
     lecture disque + parse bornées, miroir du coût du balayage
     d'occurrences d'UN fichier, une fois par candidate tierce).
- **Conséquences** : le survol d'un type binaire parle (TextView dit
  qu'il extends View et porte son champ public), l'aide à la signature
  accompagne la candidate projet de sa documentation — Android Studio,
  jdt.ls et le LSP JetBrains servent cette doc aux usages sans sauter
  à la déclaration. La fiche ouvre la voie aux indices lambda (chantier
  suivant : les types attendus des littéraux réutilisent la même
  lecture de preuve). Le champ `documentation` du survol classpath
  étant servi par l'hôte en MARKDOWN (fence `kotlin` + sections), le
  client éditeur le rend sans changement.
