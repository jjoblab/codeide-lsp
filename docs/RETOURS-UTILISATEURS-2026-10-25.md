# RETOURS UTILISATEURS — programme du 25/10/2026 (à transmettre à CodeIDE)

> **Document destiné à l'agent/propriétaire du dépôt CodeIDE** : un
> programme de quatre utilisateurs simulés (projets Android réels,
> chaîne LSP de production, corpus normalisation v2) a testé les LSP
> embarqués. Voici ce que CodeIDE doit savoir, faire et surveiller.
> Correctifs correspondants : codeide-lsp `d89544d` (vague 1),
> `33688a8` (vague 2 — membres Java projet, R sans import), vague 3
> (callee suit son récepteur typé, modificateur `data` ENFIN lu,
> preuve d'absence projet) puis vague 4 (`cac026a` — membres hérités
> par la PILE des this, imbriqués de supertypes, frontière `?.` des
> sélecteurs, FQN sans import, remember générique) — CI verte
> attendue sur main.

## 1. Ce qui a été fait

| Utilisateur | Monde | Projet | Verdict initial | Après correctifs |
|---|---|---|---|---|
| Léa | Compose (Material3, ViewModel, coroutines) | MesCourses, 13 fichiers | 4/10 — 45 FP | **2 FP restants** (weight, align — extensions de SCOPE Compose, limites connues), showSnackbar (remember générique), Factory (imbriqué qualifié position type) et currency (apply) ÉTEINTS — vague 4 |
| Marc | Views/XML + Java (ViewBinding, RecyclerView) | AtelierVinyle, 11 fichiers | 5/10 — 47 FP | Java **impeccable**, complétion XML 134 items, **0 FP restant** — le contrat du test est VIDE (tout nouveau faux positif casse la CI) : membres hérités (submitList), FilterResults/values, FQN sans import (androidx…), super args, field/setter, style/Cap dans apply, chaîne de binding vers getString — vagues 2+4 |
| Nina | KMP (:shared + :app, expect/actual) | PartageBudget, 9 fichiers | 5/10 — 15 FP | cross-module **VICTOIRE** (entrée MODULE), expect/actual **PARFAIT**, **2 FP restants** (destructuring lambda, Map.entries), textSize et setPadding (héritage/chaîne) ÉTEINTS — vague 4, consulterInconnu (vrai positif) SIGNALÉ |
| Olivier | scripts .gradle.kts | usine-build, 5 scripts | 4/10 — 8 FP | complétion JavaVersion 37 items ✓, DSL blocs = backlog |

## 2. Correctifs livrés dans codeide-lsp

**Vague 1 (d89544d)** — six familles éteintes au moteur Kotlin :
constructeurs à **valeurs par défaut** (le FP n° 1 du quotidien),
**membres synthétisés** des data classes (copy/componentN), getters
**Compose** (Composer,int), noms **manglés** (inline class Dp), types
**imbriqués** (Icons.Filled, Paint.Cap), **imports kotlin.***
implicites (OptIn, Suppress) + imports **by** (getValue/setValue) +
garde erasure (Object jamais preuve d'absence).

**Vague 2 (33688a8)** — deux familles du haut du backlog :

1. **Membres des sources Java du projet** (le monde mixte de Marc,
   47 FP) : champs + méthodes de niveau 1 extraits avec conteneur =
   FQCN, propriétés synthétiques des getters (`getTitre()` →
   `titre`) — diagnostic, complétion (`vinyle.` : 0 → 21 items) et
   typage en chaîne (`v.getTitre().length`) fonctionnent ;
2. **R sans import** (même paquet) : `fqcnDuNomEcrit` sonde la VUE du
   classpath pour un nom du paquet du document — miroir kotlinc, R
   du build inclus (le manifeste AGP 9 n'a plus de `package=`, le R
   vient du R.jar du montage v2).

**Vague 3** — trois familles (récepteurs génériques, le n° 1 du
backlog) :

1. **Le callee suit son récepteur TYPÉ** (`vueModele.effets.collect
   { }`) : le callee d'un appel sélecteur dont le récepteur porte un
   TYPE sans preuve (générique, classe illisible) est MUET (D3) —
   collect ÉTEINT chez Léa et Nina ; un récepteur SANS type garde la
   voie normale (`java.io.Fichier(…)` reste rouge, miroir kotlinc) ;
2. **Le modificateur `data` est ENFIN lu** — `estData`/`estEnum`
   sondaient les jetons DIRECTS du nœud classe alors que les
   modificateurs vivent dans le MODIFIER_LIST enfant : la branche
   copy/componentN de la vague 1 était du CODE MORT. Corrigée, les
   data classes émettent leurs membres synthétisés — les FP copy de
   Léa (Article, ListeCoursesUi) et de Nina (Budget ×3) ÉTEINTS, la
   complétion `p.` propose `copy` ;
3. **Preuve d'absence PROJET** : un type du projet SANS héritage
   écrit (class/object/interface Kotlin, class/interface Java — la
   contrainte d'un paramètre générique `T extends Comparable<T>`
   n'est PAS un supertype) porte exactement ses membres DÉCLARÉS +
   les implicites de Any/Object — un membre inexistant y est
   SIGNALÉ (vrai positif rattrapé : `CalculatriceBudget
   .consulterInconnu` de Nina). Héritage écrit, enum (Comparable
   implicite), annotation (Annotation implicite), extension
   homonyme ou scan non abouti : silence D3.

**Vague 4 (cac026a)** — sept familles (items 1, 2, 3 et 4 du
backlog — le monde Views de Marc est PROPRE, liste de limitations
VIDE) :

1. **PILE des this IMPLICITES** (membres hérités du classpath,
   récepteur implicite) : l'objet ANONYME le plus interne ouvre la
   marche par SA clause écrite, PUIS les classes nommées
   englobantes prêtent chacune la leur — `submitList` nu dans un
   `object : Filter()` sous un adaptant voit Filter PUIS ListAdapter
   (miroir kotlinc, récepteurs empilés) ; submitList/getItem/
   addItemDecoration/setOnClickListener/getString ÉTEINTS ;
2. **Types IMBRIQUÉS des maillons** : `fqcn$Nom` trouvé sur la
   fermeture transitive = résolution — règle kotlinc PROUVÉE par
   expérience (hériter met les imbriqués en portée par nom simple ;
   l'IMPORT du conteneur seul ne suffit PAS) — FilterResults nu en
   signature d'override, ViewHolder/ItemDecoration/State ;
3. **Frontière `?.` des sélecteurs** (bug transversal du moteur) :
   le jeton `?.` [89..91] captait le décalage 91 — le DÉBUT du
   sélecteur suivant : la référence n'entrait JAMAIS dans la chaîne
   d'ancêtres (faux positif sur CHAQUE sélecteur d'accès sûr —
   `results?.values` ; le jeton `.` d'UN caractère ne déclenchait
   pas le bug). Priorité : contenant STRICT [debut, fin) > frontière
   fermée (curseur collé à la fin d'un mot, comportement historique
   préservé) > enfant vide (ADR 0218) ;
4. **Paramètres du constructeur PRIMAIRE** en zone de CONSTRUCTION
   (clause super, initialisateurs, init — expérience kotlinc : les
   corps de méthode et les setters custom les REFUSENT) —
   contexte/attributs/styleDef de `super(...)` ÉTEINTS, le vrai
   positif d'un paramètre hors zone RATTRAPÉ ; le setter custom
   prête son paramètre et `field` ;
5. **FQN complets sans import EN EXPRESSION** et **imbriqués
   qualifiés en POSITION DE TYPE** : le nom complet résout dans la
   vue → tous les segments muets (`androidx…LinearLayoutManager(this)`,
   `ViewModelProvider.Factory`) ; introuvable → premier et dernier
   segments faillibles (miroir kotlinc « unresolved: a »), le milieu
   muet — jamais trois rougeurs sur une seule faute ;
6. **Appels GÉNÉRIQUES liés par leur lambda** : `fun <T>
   remember(calculation: () -> T): T` top-level sans récepteur se
   lie par la DERNIÈRE expression de son littéral — showSnackbar
   ÉTEINT (le décodeur de métadonnées rend désormais le retour
   PARAMÈTRE DE TYPE « T », il était muet) ;
7. **Récepteur implicite de apply/run + unicité du nom simple dans
   la vue** : les propriétés synthétiques Java du corps d'un
   `apply { }` (style/color/strokeCap sur Paint) et les types écrits
   des fichiers DÉCLARANTS (le `TextView` du champ ViewBinding —
   chaîne de binding vers context/getString) — currency/textSize/
   setPadding ÉTEINTS.

## 3. Ce que CODEIDE doit faire

1. **Toujours envoyer la notification `initialized`** après la réponse
   `initialize` — le serveur **Java** l'EXIGE (rejette toute requête en
   état INITIALIZING), les serveurs Kotlin/XML l'ignorent. Vérifier que
   le client cel-lsp l'envoie bien sur les TROIS canaux (incohérence à
   tracer côté codeide-lsp).
2. **Le classpath v2 fait foi** : R.jar et classes compilées du module
   = ENTRÉES de classpath (JAR/DOSSIER) ; ViewBinding/BuildConfig/KSP =
   racines générées (fichiersGeneres). Le serveur tooling livre déjà
   exactement ça (ADR 0113/0224) — ne rien « arranger » en route.
3. **Surveiller les jar livrés** : un jar vide/corrompu dans le
   ClasspathResult bloque l'indexation INFINIMENT côté LSP (découverte
   U2, backlog robustesse codeide-lsp) — CodeIDE doit afficher l'état
   d'indexation (phases SyncHandler) et ne jamais rester muet sur une
   sync PARTIELLE.

## 4. Backlog partagé (priorisé, après vague 4)

1. Extensions de **scope** Compose (weight dans Row, align dans
   Box — le récepteur implicite du lambda de scope) ;
2. Destructuring de lambda `(_, total)` et `Map.entries` (KMP) ;
3. Jar illisible → **PARTIEL + avertissement** (au lieu du blocage) ;
4. Validation d'**attributs XML** des layouts (complétion existe,
   diagnostic non) ;
5. ADR 0221 lettre violée : NUS dans les lambdas `tasks.register` ;
   complétion DSL des blocs (libs. inclus).

## 5. Références

Rapports complets (expérience première personne) : session du
25/10/2026, reproduits dans les tests E2E
`lsp/host/src/test/.../utilisateurs/ExperienceU1..U4*E2ETest.kt` —
chaque test porte SON contrat : limitations connues listées en dur,
tout nouveau faux positif fait échouer la CI.
