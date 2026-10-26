# Mise à jour de CodeIDE — notes de passage des versions de codeide-lsp

> Ce document s'adresse à l'agent/propriétaire de CodeIDE : chaque section
> décrit ce qu'une version de codeide-lsp apporte et ce que CodeIDE doit
> faire pour en profiter. Les versions sont publiées par
> `scripts/bump-frere.sh lsp minor` DEPUIS CodeIDE (jamais depuis ce dépôt).
>
> **Trou 1.4.0 → 1.12.0** : ces versions ont été publiées sans note de
> passage ici (l'agent lsp consolidait dans le CHANGELOG du dépôt) —
> consulter `CHANGELOG.md` de codeide-lsp pour le détail, en particulier
> 1.8.0 (demandes D1–D5 de `DEMANDES_CODEIDE_LSP.md`), 1.11.0 (monde
> Views/XML) et 1.12.0 (companion objects ADR 0222, sélection par types
> complets ADR 0223).

## 1.15.0

### 1. État

- `main` de codeide-lsp : release `v1.15.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.15.0`), CI **verte**
  attendue sur `main`.
- **Vague 5 du programme utilisateurs** : les items 1 et 2 du backlog
  — les QUATRE mondes du programme sont à **ZÉRO faux positif
  déclaré** : le monde Compose de Léa et le monde KMP de Nina
  rejoignent celui de Marc (leurs contrats E2E sont VIDES : tout
  nouveau FP casse la CI). Détails dans `CHANGELOG.md` § 1.15.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2.

### 2. Changements de comportement visibles

- **Kotlin** : les extensions de **SCOPE Compose** résolvent —
  `Modifier.weight(1f)` dans `Row { … }`, `Modifier.align(…)` dans
  `Box { … }` (le récepteur de portée du lambda, candidates PROJET
  et CLASSPATH, pile des littéraux emboîtés) ; les paramètres
  DESTRUCTURÉS de lambda (`sortedByDescending { (_, total) ->
  total }`) sont en portée ; les propriétés builtins de Map
  (`totaux.entries`, `.keys`, `.values` → `entrySet()`/`keySet()`/
  `values()`) et `buildList { add(…) }` (alias `Mutable*` ADR 0218).
- **Vrais positifs gardés** : un membre inconnu DANS le scope reste
  signalé (`grincher`), un nom inconnu dans un corps de lambda
  destructuré reste signalé (`totalInconnu`).

### 3. API/contrat modifiés

- **AUCUN** : la vague est interne au moteur (résolution, portée
  locale, chaîne des collections). La montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (la section du registre TESTS_MANUELS suit).
2. **Rejouer les scénarios d'appareil des sections 1.13.0/1.14.0 §5**
   (ils restent valides) PLUS : un écran Compose complet avec
   `Row { Text(Modifier.weight(1f)) }` et `Box {
   Modifier.align(Alignment.BottomCenter) }` — aucun diagnostic
   attendu sur fichier correct ; une erreur volontaire dans le
   scope (`Modifier.wight(1f)`) doit rester ROUGE.
3. **Surveiller le backlog partagé** (RETOURS §4) : jar illisible →
   PARTIEL en tête, puis attributs XML, NUS des lambdas Gradle,
   complétion du récepteur de portée et typage des composants
   destructurés.

## 1.14.0

### 1. État

- `main` de codeide-lsp : release `v1.14.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.14.0`), CI **verte**
  attendue sur `main`.
- **Vague 4 du programme utilisateurs** (`cac026a`) : les items 1 à 4
  du backlog — le monde Views/XML de l'utilisateur Marc atteint ZÉRO
  faux positif (le contrat E2E de son test est VIDE : tout nouveau FP
  casse la CI). Détails dans `CHANGELOG.md` § 1.14.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md` §2.

### 2. Changements de comportement visibles

- **Kotlin** : les membres HÉRITÉS du classpath résolvent depuis les
  types du projet (`submitList` nu dans un adaptant, `getString`
  dans une Activity, y compris depuis un objet anonyme — la PILE des
  this) ; les types IMBRIQUÉS des supertypes par nom simple
  (`FilterResults` dans un `object : Filter()`) ; les sélecteurs
  d'accès sûr `results?.values` ne bavent PLUS (bug de frontière
  transversal) ; les paramètres du constructeur primaire sont lus
  dans la zone de construction (`super(contexte, attributs,
  styleDef)`, initialisateurs, `init`) ; `field` et le paramètre des
  setters custom résolvent ; les FQN complets sans import en
  expression (`androidx.recyclerview…LinearLayoutManager(this)`) et
  les imbriqués qualifiés en position de type
  (`ViewModelProvider.Factory`) ; `remember { X() }.membre` après une
  locale générique (`hoteSnackbar.showSnackbar`) ; les propriétés
  synthétiques Java dans `apply { }` (`style =`, `color =` sur Paint)
  et les chaînes de binding (`liaison.texteAnnee.context.getString`).
- **Vrais positifs rattrapés** : un paramètre du constructeur primaire
  utilisé HORS zone de construction (dans un corps de méthode) est
  SIGNALÉ, miroir kotlinc ; le PREMIER segment d'une chaîne qualifiée
  inconnue (`ArticleRepoInconnu.tenter()`) est signalé.

### 3. API/contrat modifiés

- **AUCUN** : la vague est interne au moteur (diagnostic, typage,
  décodage des métadonnées). La montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (la section du registre TESTS_MANUELS suit).
2. **Rejouer les scénarios d'appareil de la section 1.13.0 §5** (ils
   restent valides) PLUS : ouvrir un adaptant RecyclerView complet
   (objets anonymes `Filter`, `submitList`, `super(...)` à trois
   args, `apply` sur Paint) — aucun diagnostic attendu sur fichier
   correct ; un `results?.values` ne doit plus baver.
3. **Surveiller le backlog partagé** (RETOURS §4) : extensions de
   SCOPE Compose (weight/align) en tête, puis destructuring de lambda
   et `Map.entries`.

## 1.13.0

### 1. État

- `main` de codeide-lsp : release `v1.13.0` (tag de publication — le
  SHA courant est celui du commit `release: v1.13.0`), CI **verte**
  sur `main` (chaque lot poussé validé par un run CI vert).
- **Correctifs du programme utilisateurs du 25/10/2026** : trois vagues
  qui éteignent les familles de faux positifs n° 1 du quotidien —
  détails dans `CHANGELOG.md` § 1.13.0 et
  `docs/RETOURS-UTILISATEURS-2026-10-25.md`.

### 2. Changements de comportement visibles

- **Kotlin** : les constructeurs à VALEURS PAR DÉFAUT ne sont plus
  signalés à arité courte ; les data classes résolvent `copy`/
  `componentN` (et les PROPOSENT en complétion) ; `collect` après un
  récepteur typé (`vueModele.effets.collect { }`) ne produit plus de
  faux `kt.unresolved` ; les getters Compose (`MaterialTheme
  .typography`, `Arrangement.spacedBy`), les inline classes manglées
  et les types imbriqués (`Icons.Filled`, `Paint.Cap`) résolvent ;
  `@OptIn`/`@Suppress` et les imports `by` (`getValue`/`setValue`)
  ne sont plus marqués inutilisés.
- **Projets mixtes Java/Kotlin** : les membres des sources JAVA du
  projet résolvent depuis le Kotlin (`getTitre()` → complétion,
  typage en chaîne, propriété synthétique `titre`) ; `R` sans
  import (même paquet) résout.
- **Vrais positifs rattrapés** : un membre inexistant sur un type du
  projet SANS héritage écrit est SIGNALÉ (preuve d'absence projet) —
  comportement plus proche d'Android Studio sur les deux tableaux.

### 3. API/contrat modifiés

- **AUCUN** : les trois vagues sont internes au moteur (diagnostic,
  complétion, typage). Aucune signature publique, aucune entrée
  d'énumération, aucun SPI ne bougent — la montée de version est un
  remplacement de coordonnées pur.

### 4. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh
   lsp minor` (fait à la publication de cette note — la ligne
   `codeide-lsp` du catalogue suit).
2. **Rejouer les scénarios d'appareil des quatre mondes** (§5) — les
   fichiers qui bavaient du faux positif ne doivent plus rien
   signaler, les erreurs volontaires restent attrapées.
3. **Surveiller le backlog partagé** (RETOURS §4) : membres hérités du
   classpath (`submitList`/`getString`) en TÊTE, erasure après
   `remember { }`, extensions de scope Compose — les prochaines
   vagues côté lsp.

### 5. Scénarios d'appareil

1. **Compose** : ouvrir un écran avec data class (`data class
   Article(...)`) — `article.copy(...)` passe sans diagnostic, la
   complétion après `article.` propose `copy` ; un `collect { }` sur
   un `StateFlow` du ViewModel reste muet.
2. **Mixte Java/Kotlin** : depuis un `.kt`, utiliser un champ/méthode
   d'une classe `.java` du projet — complétion et survol vivants.
3. **Constructeur à défauts** : appeler un constructeur Compose sans
   ses paramètres optionnels — aucun « 4 fourni(s), 6 attendu(s) ».
4. **Vrai positif** : taper `article.prixInconnu` — SIGNALÉ (le type
   est du projet, sans héritage écrit).

## 1.3.0

### 1. État

- `main` de codeide-lsp : **6735890** (à la rédaction — voir le SHA réel au
  moment de la publication), CI **verte** sur `main` (chaque lot poussé a
  été validé par un run CI vert ; l'historique des runs est public).
- **Non publié** : lancer `scripts/bump-frere.sh lsp minor` **depuis
  CodeIDE** (version attendue : **1.3.0** — `version.properties` reste à
  1.2.0, le bump est l'affaire du script frère). Le dépôt est laissé
  PROPRE : tout commité, `main` à jour, CI verte.

### 2. Capacités par langage (avant → après)

Les interrupteurs de l'écran « Intelligence du code » sont alimentés par la
table `CapacitesAnnoncees` (dérivée des `ServerCapabilities` réelles,
parité verrouillée par test) :

| Interrupteur (`CapaciteLsp`) | Java | Kotlin | XML |
|---|---|---|---|
| COMPLETION, SURVOL, DIAGNOSTICS, DEFINITION, RENOMMAGE, ACTIONS_RAPIDES | ✅ (inchangé) | ✅ (inchangé) | ✅ (inchangé) |
| SIGNATURES | ✅ | ✅ | ❌ (inchangé) |
| REFERENCES | ✅ | ✅ | ✅ **NOUVEAU 1.3.0** |
| FORMATAGE | ✅ | ✅ **NOUVEAU 1.3.0** | ✅ (inchangé) |
| INDICES_EN_LIGNE | ✅ | ✅ **NOUVEAU 1.3.0** | ❌ |

Capacités NON réglables (annonces serveur pures, sans interrupteur) :

| Capacité | Java | Kotlin | XML |
|---|---|---|---|
| Symboles de document, pliage, surlignage, prepareRename | ✅ | ✅ | ✅ |
| Définition de type, implémentations | ✅ | ✅ **NOUVEAU** | ❌ |
| Formatage de plage | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Symboles d'espace (`workspace/symbol`) | ✅ | ✅ **NOUVEAU** | ❌ |
| Plage de sélection | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Édition liée (balises) | ✅ | ❌ | ✅ **NOUVEAU** |
| Couleurs de document | ✅ | ❌ | ✅ **NOUVEAU** |
| Formatage à la frappe, lentilles, hiérarchies d'appels/types | ✅ | ❌ | ❌ |
| Jetons sémantiques | ✅ | ❌ (non annoncé — D3) | ❌ |
| Super-définition, source décompilée (expérimentales) | ✅ | ❌ | ❌ |

### 3. Changements de comportement visibles

- **Diagnostics Kotlin/XML rafraîchis sur modification externe** : un
  fichier `.kt`/`.kts`/`.xml` modifié hors éditeur (build Gradle,
  terminal) rafraîchit les diagnostics des documents OUVERTS de ces
  langages (lot L1) — auparavant, seul Java en bénéficiait ; le
  rafraîchissement n'attendait plus que l'enregistrement.
- **Kotlin** : « Formater le document » ré-indente (ré-indentation seule,
  ADR 0202 — le contenu n'est jamais réécrit) ; de nouveaux indices en
  ligne (noms de paramètres, types certains) apparaissent si l'éditeur
  les rend ; la palette « aller au symbole » et l'expansion de sélection
  deviennent disponibles côté serveur.
- **XML** : « Trouver les références » sur `@id/…`, `@string/…`, etc.
  fonctionne (déclarations + usages) ; le surlignage d'occurrences
  couvre les ressources ; couleurs et édition liée prêtes côté serveur.
- **Aucune capacité n'a cessé d'être annoncée** (L4 n'a rien retiré —
  aucun stub trouvé côté Java).

### 4. API/contrat modifiés

- **Aucune rupture** attendue : `ConfigurationLsp`, `SessionLsp*` et
  `CapaciteLsp` gardent leurs signatures.
- AUCUNE entrée d'énumération `CapaciteLsp` n'a été AJOUTÉE en 1.3.0 :
  `FORMATAGE`, `INDICES_EN_LIGNE` et `REFERENCES` existaient déjà —
  seules les TABLES par langage (`CAPACITES_KOTLIN`, `CAPACITES_XML`)
  gagnent des entrées. **Le miroir `core:domain` de CodeIDE n'a RIEN à
  mettre à jour pour l'énumération** ; en revanche, les écrans de
  réglages afficheront trois NOUVELLES lignes d'interrupteur effectives
  (REFERENCES pour XML, FORMATAGE et INDICES_EN_LIGNE pour Kotlin) sans
  changement de code si la liste est dérivée de `capacites(langage)`
  (elle l'est — LSP_REVUE_REGLAGES du lot LSP-1).
- Moteurs Kotlin/XML : le contrat de session `SessionLangageKotlin`/
  `SessionLangageXml` gagne des méthodes AVEC DÉFAUTS HONNÊTES VIDES
  (formatage, navigation de types, indices, symboles d'espace, sélections
  / références, surlignage, plage liée, couleurs) — un pack 1.2.0 face à
  un hôte 1.3.0 dégrade proprement (tolérance de version D6, aucun crash).

### 5. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh lsp
   minor` (calcule 1.3.0, commit, tag `v1.3.0`, push, attend
   `publish.yml`, vérifie le registre, met à jour le catalogue).
2. **Vérifier les écrans de réglages LSP** : trois nouvelles lignes
   d'interrupteur effectives (voir tableau §2) — vérifier libellés et
   état par défaut.
3. **Ajuster les textes « non pris en charge »** pour Kotlin/XML : le
   formatage Kotlin, les indices Kotlin et les références XML sont
   désormais servis — tout texte statique qui dit le contraire doit
   être retiré.
4. **Exécuter les scénarios d'appareil** (§8 ci-dessous).

### 6. Dépendances avec les autres agents

- **code-editor 3.74.0** (agent 10-a) : le formatage de PLAGE n'est
  consommé qu'une fois 3.74.0 livrée et montée (le serveur la sert dès
  1.3.0 pour les trois langages). Sans 3.74.0 : le formatage du document
  ENTIER fonctionne déjà.
- **Utilisable dès maintenant avec le client actuel** : formatage Kotlin
  du document entier, références/surlignage XML, indices Kotlin (le
  client les consomme déjà pour Java), rafraîchissement des diagnostics
  sur modification externe.
- **Phase 2 CodeIDE** : les capacités listées dans
  `docs/LSP_ECARTS_CLIENT.md` (palette, sélection, couleurs, édition
  liée, lentilles, hiérarchies, formatage à la frappe, méthodes
  expérimentales) attendent leur consommation client — chaque fiche
  décrit l'API minimale à ajouter.

### 7. Capacités en service côté serveur mais hors service côté éditeur

Voir **[docs/LSP_ECARTS_CLIENT.md](LSP_ECARTS_CLIENT.md)** : formatage de
plage (jusqu'à 3.74.0), formatage à la frappe, plage de sélection, palette
`workspace/symbol`, lentilles de code, hiérarchies d'appels et de types,
couleurs de document XML, édition liée XML, super-définition et source
décompilée (expérimentales, Java). Chaque fiche : requête LSP, serveur,
effet utilisateur, API minimale côté client.

### 8. Scénarios d'appareil

1. **Modification hors éditeur** : ouvrir un `.kt` et un `.xml` dans
   l'éditeur ; depuis un terminal (ou un build Gradle), modifier le
   fichier sur disque ; les diagnostics des documents ouverts se
   rafraîchissent SANS enregistrement (les trois langages).
2. **Formater un `.kt`** : ouvrir un fichier Kotlin mal indenté (corps
   décalé, chaîne brute mal alignée) ; « Formater le document » → la
   ré-indentation s'applique, le contenu de la chaîne brute reste
   INTACT ; reformater ne change rien (idempotence).
3. **Références d'un `@id/…` XML** : ouvrir un layout, mettre le caret
   sur `@+id/valider` puis sur un `@id/valider` ; « Trouver les
   références » → la déclaration et les usages (y compris dans les
   autres documents ouverts) sont listés ; le surlignage couvre les
   occurrences du document courant.
4. **Changement rapide d'onglet Java → Kotlin → XML** : ouvrir les
   trois, basculer rapidement ; chaque langue répond (complétion,
   survol, diagnostics) sans latence perceptible ni fuite (le test de
   fuite de tâches est dans la suite).
5. **Indices Kotlin** : écrire `somme(1, 2)` sur une fonction
   `fun somme(a: Int, b: Int)` déclarée dans le projet ; les indices
   `a =`/`b =` s'affichent devant les arguments si l'éditeur rend les
   inlay hints (déjà consommés pour Java).
