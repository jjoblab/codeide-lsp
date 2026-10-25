# RETOURS UTILISATEURS — programme du 25/10/2026 (à transmettre à CodeIDE)

> **Document destiné à l'agent/propriétaire du dépôt CodeIDE** : un
> programme de quatre utilisateurs simulés (projets Android réels,
> chaîne LSP de production, corpus normalisation v2) a testé les LSP
> embarqués. Voici ce que CodeIDE doit savoir, faire et surveiller.
> Correctifs correspondants : codeide-lsp `d89544d` (vague 1) puis
> `33688a8` (vague 2 — membres Java projet, R sans import), CI verte
> attendue sur main.

## 1. Ce qui a été fait

| Utilisateur | Monde | Projet | Verdict initial | Après correctifs |
|---|---|---|---|---|
| Léa | Compose (Material3, ViewModel, coroutines) | MesCourses, 13 fichiers | 4/10 — 45 FP | **12 FP restants** (limites connues listées) |
| Marc | Views/XML + Java (ViewBinding, RecyclerView) | AtelierVinyle, 11 fichiers | 5/10 — 47 FP | Java **impeccable**, complétion XML 134 items, **membres Java projet + R sans import ÉTEINTS** (vague 2) — 29 FP restants (membres hérités classpath, FQN sans import, super args, field/setter) |
| Nina | KMP (:shared + :app, expect/actual) | PartageBudget, 9 fichiers | 5/10 — 15 FP | cross-module **VICTOIRE** (entrée MODULE), expect/actual **PARFAIT** |
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

## 4. Backlog partagé (priorisé, après vague 2)

1. Récepteurs **génériques** (collect/showSnackbar/copy après
   remember/List<T>/Flow<T>) — reproduction prête
   (`ReproductionObjetsClasspathTest`, ignorée) ;
2. Membres **hérités du classpath** sur chaînes profondes
   (submitList/getString/setOnClickListener/addItemDecoration) et
   ViewHolder dans les clauses supertype ;
3. Noms **QUALIFIÉS complets sans import** (`androidx.recycler...` en
   expression) ;
4. Jar illisible → **PARTIEL + avertissement** (au lieu du blocage) ;
5. Extensions de **scope** Compose (weight/align), args de
   super(...), field/setter, propriétés Java dans apply ;
6. Validation d'**attributs XML** des layouts (complétion existe,
   diagnostic non) ;
7. ADR 0221 lettre violée : NUS dans les lambdas `tasks.register` ;
   complétion DSL des blocs (libs. inclus).

## 5. Références

Rapports complets (expérience première personne) : session du
25/10/2026, reproduits dans les tests E2E
`lsp/host/src/test/.../utilisateurs/ExperienceU1..U4*E2ETest.kt` —
chaque test porte SON contrat : limitations connues listées en dur,
tout nouveau faux positif fait échouer la CI.
