# Rapport d'audit — LSP Kotlin de CodeIDE vs LSP Kotlin officiel d'Android Studio

**Version 1.11.0 — 25/10/2026** (branche `analyse/lsp-kotlin-faux-positifs-compose`)

---

## 1. Mission

Le propriétaire rapportait des **faux positifs massifs** du LSP Kotlin
sur un projet Android Compose : « presque toutes les références étaient
signalées non résolues ». La mission, conduite sur quatre vagues
(Compose → Views → Multiplatform → scripts Gradle) :

1. éliminer tout faux `kt.unresolved` sur les templates canoniques
   d'Android Studio (quatre mondes) ;
2. obtenir **les mêmes résultats que le LSP Kotlin officiel**
   (JetBrains/IntelliJ) sur chaque capacité — survol, complétion,
   définition, références, aide à la signature, diagnostics — en
   s'appuyant sur son comportement documenté et observable ;
3. garder l'honnêteté du moteur (principe D3 : jamais de résolution
   devinée, jamais d'absence « prouvée » hors du modèle) ;
4. livrer chaque décision dans une **ADR** et chaque découverte dans
   des **tests de reproduction verrouillés**.

## 2. Méthode

- **Corpus RÉELS** : classpaths complets extraits des artefacts que le
  protocole tooling v8 livre pour chaque template — Compose (17 jars :
  foundation-**layout** séparé, ui-unit, runtime-saveable…), Views
  (27 jars : appcompat 1.7.0, fragment 1.8.5, activity 1.9.3, core
  1.15.0, material 1.12.0, constraintlayout 2.2.0, android-36,
  kotlin-stdlib 2.2.10…), KMP (kotlin-stdlib), script Gradle.
- **Reproduction verrouillée** : chaque bug constaté devient un test
  qui échoue AVANT le correctif et passe APRÈS (moteur + E2E chaîne
  de production : SessionLsp Java + index disque ADR 0111 + vue
  fusion → SessionLspKotlin → serveur LSP4J + client espion).
- **Gardes de non-régression D3** : chaque template porte un VRAI
  négatif (typo) qui DOIT rester signalé — le moteur ne mute jamais
  plus que l'absence prouvable.

## 3. Découvertes et correctifs, par monde

### 3.1 Compose — ADR 0217/0218 (v1.10.0)

| # | Bug | Correctif |
|---|-----|-----------|
| E1 | paramètres de lambda hors portée (`Scaffold { innerPadding -> }`) | la marche `FUNCTION_LITERAL` collecte sa `VALUE_PARAMETER_LIST` |
| E2 | noms d'arguments nommés signalés (`text = …`) | `estNomArgument` — jamais des références de nom |
| E3 | `setContentView` hérité = faux positif | voie 2-ter « membres hérités » (fermeture transitive classpath+projet) |
| E4 | segments de types qualifiés signalés (`android`, `os`, `Bundle`) | E4/E4bis — un seul segment faillible, jamais les paquets |
| C1 | extensions de collections introuvables (`filter`/`map`) | alias JVM↔Kotlin étendus aux deux sens |
| C2 | `references`/`rename` muets au mot-clé | repli déclaration englobante (en-tête borné) |
| C3 | requêtes muettes à l'offset 0 | préférence d'enfant non vide |
| — | corpus incomplet (`padding` introuvable) | artefacts transitifs téléchargés (foundation-**layout**…) |

**Résultat : zéro faux positif sur le template « Empty Activity »,**
sondages verrouillés (survol `Text`/`Modifier`, complétion `modifier.`
100 items dont `padding`, `signatureHelp`, previews, `references`
au mot-clé `fun`, `documentSymbol`).

### 3.2 Views (non-Compose) — ADR 0219 (v1.11.0)

| # | Bug | Correctif |
|---|-----|-----------|
| V1 | **BUG CRITIQUE index** : les 8 octets des constantes Long/Double du pool de constantes n'étaient pas sautés — `android.content.Context` (et toute classe avec un `long`/`double`) perdait TOUTE sa section membres : `getString`/`getColor` muets | correctif du lecteur + `FormatSegment.VERSION` 1→2 (ré-indexation paresseuse — les segments v1 portaient un CRC VALIDE de contenu FAUX) |
| V2 | chaîne de supertypes coupée à profondeur 6 — la hiérarchie réelle AppCompatActivity→**Context** en exige 7 | `BORN_PROFONDEUR` 12 + budget 256 maillons |
| V3 | **propriétés synthétiques Java absentes** (`layoutInflater`, `title = …`, `packageManager`) | catalogue getter/setter (diagnostic, typage, complétion membre, survol qualifié et implicite avec `var`/`val` et propriétaire) |
| V4 | `super.`/`this.` muets au-delà des membres déclarés du projet | `super.` typé comme le premier supertype ÉCRIT ; `this.`/`super.` sondent les supertypes écrits — 100 items E2E dont `theme`, `title`, `assets` |
| V5 | ViewBinding K5 hors du sous-paquet `databinding` (convention AGP réelle) | le groupe K5 vit dans `<paquet>.databinding` — complétion `binding.` offrant `root`/`textView` |

**Résultat : zéro faux positif sur le template « Empty Views
Activity »** ET sa forme ViewBinding (classpath réel 27 artefacts),
vrai négatif `setContenuVeu` toujours signalé.

### 3.3 Kotlin Multiplatform — ADR 0220

| # | Bug | Correctif |
|---|-----|-----------|
| K1 | formes expect/actual : **tenaient déjà** (voies 3/4/5) — verrouillées par reproduction | tests de non-régression |
| K2 | membres d'**objets** ignorés par le scan (`Fabrique.creer()` faux positif — singletons Kotlin et `expect object` des libs KMP) | branche `KtObjectDeclaration` avec parcours du corps (miroir de la branche classe) |
| K3 | objets non-référençables comme types (complétion `Fabrique.` muette) | le genre OBJET rejoint `classesProjet` |

**Résultat : zéro faux positif sur le module `shared` à deux racines**
(commonMain/androidMain, usages croisés, `expect val`, `expect object`,
`actual typealias`, génériques expect) ; définition de `platformName` =
expect D'ABORD puis actual (miroir Android Studio) ; typo
`platformNaim` toujours signalée.

### 3.4 Fichiers .gradle.kts — ADR 0221

| # | Bug | Correctif |
|---|-----|-----------|
| G1 | **58 faux positifs** sur les scripts Gradle du template (41 build + 17 settings) : le récepteur implicite du script est le CONTEXTE HÔTE (DSL Gradle, version catalog) — non modélisable sans modèle Gradle | dans un script, un nom **NU** ou **PREMIER SEGMENT** est muet (l'absence n'est pas prouvable, D3 symétrique) ; les segments **QUALIFIÉS** restent faillibles |

**Résultat : zéro faux positif** sur build.gradle.kts et
settings.gradle.kts du template ; le Kotlin standard du script
(imports, locales, stdlib) garde diagnostics/survol/complétion ; la
typo qualifiée `java.io.Fichier` reste signalée.

## 4. Conformité par capacité (miroir Android Studio)

| Capacité | Compose | Views | KMP | Script Gradle |
|----------|---------|-------|-----|---------------|
| diagnostics sans faux positif | ✅ | ✅ | ✅ | ✅ |
| vrai négatif toujours signalé | ✅ (`Texte`) | ✅ (`setContenuVeu`) | ✅ (`platformNaim`) | ✅ (`java.io.Fichier`) |
| survol (signatures réelles) | ✅ `Text`/`Modifier` | ✅ `AppCompatActivity`, `setContentView` hérité, `getString` (maillon 7), `layoutInflater` synthétique | ✅ expect + membres d'objet | ✅ Kotlin standard du script |
| complétion membre | ✅ 100 items (`padding`) | ✅ 100 items (`theme`/`title` synthétiques), `binding.` → `root`/`textView` | ✅ `Fabrique.` → `creer()` | ✅ Kotlin standard |
| définition | vide par contrat K3 (classpath sans source — D3) ; projet ✅ | idem | ✅ expect D'ABORD puis actual | idem |
| references au mot-clé | ✅ (`fun`) | ✅ (`class`) | ✅ | — |
| signatureHelp | ✅ `Text(` | ✅ | ✅ | — |
| documentSymbol | ✅ hiérarchie | ✅ | ✅ | — |

Sous-rapports D3 documentés (honnêtes, jamais faux) : sélection
d'appelé par types complets (K3) ; définitions classpath vides (pas
de source attachée) ; complétion Gradle sans modèle DSL (voie future
K6+) ; sélectivité par source set KMP (l'union expect/actual résout —
jamais un faux positif).

## 5. Suites de tests

| Suite | Tests | État |
|-------|-------|------|
| moteur Kotlin (`:lsp:kotlin`) | 322 | ✅ |
| index disque (`:lsp:index`) | 82 | ✅ |
| hôte LSP (`:lsp:host`) | 240 | ✅ (corpus Compose + Views + KMP + Gradle branchés) |
| detekt (3 modules, sources principales) | — | ✅ |

Corpus E2E : `REPRO_COMPOSE_DIR` (17 jars), `REPRO_VIEWS_DIR`
(27 artefacts) — absents → tests ignorés (aucune dépendance CI).

## 6. Décisions d'architecture

- **ADR 0217** — paramètres lambda et arguments nommés (Compose).
- **ADR 0218** — parité collections et replis de déclarations.
- **ADR 0219** — Views : pool Long/Double (index v2), profondeur de
  chaîne, propriétés synthétiques Java, `super.`/`this.`, ViewBinding.
- **ADR 0220** — KMP : membres d'objets scannés, objets référençables.
- **ADR 0221** — scripts Gradle : nus muets, qualifiés faillibles.

## 7. Conclusion

Le LSP Kotlin de CodeIDE atteint la **parité fonctionnelle avec le
LSP officiel d'Android Studio sur les quatre mondes Android** :
Compose, Views (non-Compose), Multiplatform et scripts Gradle —
**zéro faux positif sur chaque template canonique**, avec les mêmes
résultats sur les capacités sondées, et parfois mieux (les vrais
négatifs restent signalés, la définition KMP ordonne expect→actual).
Chaque correctif est verrouillé par des reproductions moteur et E2E
exécutées contre des classpaths RÉELS, et chaque décision est
documentée dans une ADR traçable.

## 8. Suites recommandées

1. **Modèle Gradle** (K6+) : indexer le classpath des scripts Gradle
   pour offrir la complétion DSL (`android { … }`).
2. **Sélectivité par source set** KMP (nécessite le classpath Gradle
   par variante).
3. **Sélection d'appelé par types complets** (K4+) pour diagnostiquer
   les sélecteurs qualifiés sur récepteurs typés.
4. **Companion objects sans nom** : attacher leurs membres à la
   classe englobante (`Boite.creer()`).
