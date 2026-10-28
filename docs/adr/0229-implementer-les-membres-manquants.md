# ADR 0229 — Implémenter les membres manquants (chantier P1-C)

- **Statut** : appliqué (29/10/2026 — troisième chantier P1 de la feuille
  de route du rapport d'analyse comparative du 28/10/2026 ; verrouillé
  par `MembresManquantsTest` (10 tests, octets de classe synthétiques
  avec attribut `Signature` réel) + le transport hôte
  (`KotlinLanguageServerTest`, 3 tests), suite sans régression,
  baseline de parité re-synchronisée).
- **Contexte** : le serveur Kotlin ne servait que des correctifs
  d'IMPORT — le rapport d'analyse qualifie les actions rapides de
  « retard majeur » et cite le correctif « implémenter les membres
  manquants » comme le premier à porter : il réutilise la chaîne de
  supertypes que la complétion membre parcourt déjà, et le moteur Java
  sert des stubs d'override depuis la 1.16.0 (gabarit, jamais le code
  du projet tiers).
- **Options considérées** :
  1. (a) **Correctif SANS diagnostic** : l'action est offerte sur la
     classe/objet ENGLOBANT le curseur, le moteur rend l'édition
     concrète (retenue) ;
  2. (b) Attendre un diagnostic d'exhaustivité d'héritage (comme le
     compilateur) et n'offrir le correctif que dessus — rejetée : le
     moteur n'a pas ce diagnostic (il exigerait la clôture COMPLÈTE
     des supertypes à chaque frappe) et l'action bornée à la
     déclaration englobante ne peut pas se tromper de cible ;
  3. (c) Stubs depuis les seules sources PROJET (classpath ignoré) —
     rejetée : la moitié des cas réels (`Runnable`, `OnClickListener`)
     vit dans le classpath.
- **Décision** :
  1. **Déclencheur** : la classe/objet le plus profond englobant le
     curseur (n'importe où dans son en-tête ou son corps — c'est ELLE
     que l'action édite). Hors classe/objet, INTERFACE, classe
     ABSTRAITE (rien n'est exigé), sans supertype écrit, tout déjà
     implémenté : `null` (l'action n'apparaît pas).
  2. **La clôture** (miroir du GO TO Super, ADR 0210) : chaque
     supertype ÉCRIT résolu par les imports du fichier déclarant
     (preuve FQCN) ; un lien PROJET se lit dans SON arbre (port de la
     session — arbre vif, sinon lecture disque bornée) et prouve SA
     propre clause ; un lien CLASSPATH parcourt sa chaîne BINAIRE
     (vue JVM). Sources JAVA du scan : jamais traversées. Profondeur
     12, volume 64 stubs, cycles coupés par FQCN.
  3. **Fidélité des stubs** :
     - PROJET : noms et types RÉELS — les paramètres de type du
       supertype sont SUBSTITUÉS par les arguments ÉCRITS de la clause
       (`Depot<String>` rend `fun lire(id: String)`), la substitution
       se COMPOSE le long de la clôture ; une clause NUE sur un type
       générique rend le membre MUET s'il mentionne le paramètre
       (jamais deviné) ;
     - CLASSPATH : vue JVM érasée — paramètres `p0, p1…`, types
       `renduCourt` ; les getters/setters abstraits sont APPARIÉS en
       propriétés (`getX()` seul → `override val x`, avec `setX()` →
       `override var x` et ses deux accesseurs).
  4. **La garde générique** (l'apport décisif du chantier) : un membre
     du classpath portant l'attribut `Signature` du bytecode (JLS
     §4.7.9 — présent dès que la déclaration touche une variable de
     type OU un type paramétré) est ÉCARTÉ : son rendu érasé
     (`Comparable`, `List` nu) ne compilerait PAS contre la vraie
     signature — un stub faux est pire que pas de stub (D3). Nouvelle
     sonde [LecteurClassBrut.methodesGeneriques] (octet par octet,
     maison, `null` sur octets illisibles : le lien entier se tait).
  5. **Exclusions** : fonctions du corps par NOM+ARITÉ, propriétés par
     NOM (constructeur primaire compris, ADR 0206) ; un membre fourni
     CONCRÈTEMENT quelque part dans la clôture n'est jamais collecté
     (seuls les ABSTRAITS le sont) ; déduplication par clé
     `f:nom/arité` / `p:nom`.
  6. **L'édition** : stubs indentés comme le corps (indentation du
     premier membre, sinon unité heuristique — indentation de
     l'en-tête, sinon 4 espaces). Corps VIDE : le blanc entre
     accolades est REMPLACÉ ; corps PEUPLÉ : le blanc avant l'accolade
     fermante (un commentaire entre les deux force l'INSERTION, rien
     n'est détruit) ; corps ABSENT : créé en fin d'en-tête
     (`class A : B` → `class A : B { … }`). Corps des stubs : `=
     TODO()` (fonctions), `get() = TODO()` (val), accesseurs complets
     (var).
  7. **Contrat neutre** : `SessionLangageKotlin.membresManquants(uri,
     decalage): CorrectifMembresManquantsKotlin?` (défaut `null` —
     tolérance de version des packs) ; l'hôte n'ajoute que le TITRE
     (« Implémenter le membre manquant » / « les N membres manquants »)
     et l'enveloppe `WorkspaceEdit` — kind QuickFix, SANS diagnostic
     lié.
- **Conséquences** : le serveur Kotlin sert son premier correctif
  au-delà des imports ; la sonde `Signature` ouvre la voie aux stubs
  classpath honnêtes pour les futurs correctifs (override explicite,
  implémentation sélective). Les pesées `suspend` / `@RequiresApi` /
  garde SDK_INT restent liées à leurs diagnostics — chantier
  diagnostics de la même feuille de route.
