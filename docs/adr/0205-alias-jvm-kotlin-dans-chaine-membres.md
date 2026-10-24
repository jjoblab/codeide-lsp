# ADR 0205 — Sonder l'alias JVM↔Kotlin dans la chaîne de membres (String, Int, Long, Boolean)

- **Statut** : appliqué (24/10/2026 — audit E2E du serveur LSP Kotlin, découverte D5, scénarios S6/S9/S10/S12 ; mise en œuvre v1.6.0 : correctif moteur + tests, sondes S6/S9/S10 du harnais verrouillées sur le comportement corrigé au fil du transport réel).
- **Contexte** : l'audit montre que le survol de `s.length` (récepteur `String`) rend
  **toujours** `null` — locale à type écrit, locale par littéral, paramètre, et même avec
  `import kotlin.String` explicite — tandis que `v.requestLayout()` sur `TextView` rend la
  signature complète **avec le propriétaire hérité** (`android.view.View`) et que le survol
  du type `Intent` importé rend `class android.content.Intent`. La cause est dans
  `ResolutionKotlin.chaineSupertypes` : elle demande `vue.octetsClasse("kotlin.String")` —
  **aucun fichier `.class` ne porte ce nom** (fiction Kotlin : le bytecode est
  `java.lang.String`) — donc la chaîne s'arrête au premier maillon et `membresDe` rend
  `null`. Les cartes d'alias `ALIAS_JVM_KOTLIN`/`ALIAS_KOTLIN_JVM` existent déjà dans
  `ResolutionKotlin` mais ne sont sondées que par `variantesRecepteur`, réservé aux
  **extensions** — d'où le paradoxe observable : `s.trim()` (extension stdlib) résout,
  `s.length` (méthode déclarée) non. Sont touchés `String`, `Int`, `Long`, `Boolean`,
  `Comparable` — les types les plus sollicités du langage. Le trou est invisible en surface
  (les extensions couvrent l'essentiel des usages stdlib) mais il frappe toutes les
  **méthodes déclarées** de ces types, y compris la complétion membre.
- **Options considérées** :
  1. **Sonder l'alias dans `chaineSupertypes`** — au premier échec de `octetsClasse(fqcn)`,
     retenter le FQCN aliasé (`ALIAS_KOTLIN_JVM[fqcn] ?: ALIAS_JVM_KOTLIN[fqcn]`) avant
     d'arrêter la chaîne ; le maillon rendu au completion/survol reste le FQCN d'ORIGINE
     (rendu utilisateur inchangé : « kotlin.String »), seul l'accès aux octets passe par
     l'alias.
  2. **Normaliser en entrée** — convertir `kotlin.String` → `java.lang.String` à la frontière
     de `ResolutionKotlin` (tous les appelants) : plus invasif, change les clés de cache
     plat (`cacheSupertypes`) et les rendus (« — java.lang.String » au lieu de
     « — kotlin.String » dans le survol).
  3. **Côté hôte** — faire sonder les deux clés dans `AdaptateurVueClasspath.octetsClasse` :
     répare AUSSI le LSP Java non visé (la vue est partagée), fige la connaissance d'alias
     Kotlin dans un adaptateur générique ; l'alias est une sémantique KOTLIN, sa place est
     dans le moteur Kotlin (frontière D6).
- **Décision** : retenir l'**option 1** — le sondage d'alias vit dans `chaineSupertypes`
  (et donc dans `membresDe` qui la suit) : `visiter` tente d'abord `octetsClasse(fqcn)`, et
  à défaut `octetsClasse(aliasDe(fqcn))` ; le FQCN du maillon reste celui demandé (les
  rendus et les clés de cache ne changent pas), seul le chargement des octets passe par la
  variante aliasée. Les **extensions** continuent d'utiliser `variantesRecepteur` (les deux
  clés y sont déjà sondées) — aucun changement pour elles.
- **Conséquences** :
  - `s.length`, `s.toCharArray()`, `(1).toString()`, `listOf(...).toString()` résolvent au
    survol ET à la complétion membre ; le rendu porte le FQCN Kotlin (« — kotlin.String »)
    comme le fait déjà le survol d'extension.
  - Effet de bord positif : le survol d'un nom de TYPE aliasé (`String` dans
    `val s: String`) reprend la voie `survolTypeClasspath` (aujourd'hui masquée par l'échec
    `membres`, l'audit l'a vu rendre la fonction stdlib `String(...)` — S11).
  - La chaîne lue depuis `java.lang.String` poursuit vers `java.lang.Object` :
    `equals`/`hashCode`/`toString` deviennent servables pour les récepteurs aliasés —
    convergencé avec le besoin de O3 (membres hérités) sans la couvrir (récepteurs PROJET
    toujours sans bytecode).
  - Tests : S6/S9/S10 du harnais passent de la caractérisation `null` à un survol rendu ;
    ajouter au moteur (`SessionLangageKotlinK3Test`) « survol membre déclaré sur récepteur
    kotlin.String via la vue réelle » ; les fixtures existantes (TextView/View) restent les
    gardes du cas non aliasé.
