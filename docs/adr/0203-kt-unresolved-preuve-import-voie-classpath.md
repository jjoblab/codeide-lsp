# ADR 0203 — `kt.unresolved` : preuve d'import pour les appelables du classpath (voie 6)

- **Statut** : appliqué (24/10/2026 — audit E2E du serveur LSP Kotlin, découverte D1, scénario S5 ; mise en œuvre v1.6.0 : correctif moteur + tests, sondes S5 du harnais verrouillées sur le comportement corrigé au fil du transport réel).
- **Contexte** : la campagne d'audit (harnais stdio JSON-RPC, classpath réel stdlib +
  android.jar) montre que `fun f(): Double { return PI }` ne produit **aucun** diagnostic
  alors que `kotlinc` signale « unresolved reference: PI » — `kotlin.math` n'est pas importé
  par défaut. La cause est dans `ResolutionReferences.estResolu` (voie 6) : un nom nu est
  considéré résolu dès que l'index des appelables contient **une** entrée portant ce nom
  simple (`index.topParNom(nom)`), sans vérifier l'import écrit ni les imports par défaut de
  Kotlin. Le commentaire de la voie cite `println` — importé **par défaut** via `kotlin.io` —
  mais l'implémentation accepte n'importe quel appelable indexé, y compris `PI`, `sqrt`,
  `abs` (kotlin.math) ou tout appelable de bibliothèque tierce. S'en suit une incohérence
  visible par l'utilisateur : la COMPLÉTION propose `PI` avec `fqnImport = kotlin.math.PI`
  (l'import manque, le moteur le sait) tandis que le DIAGNOSTIC se tait. Le philosophy D3
  (« mieux vaut sous-rapporter qu'un faux positif ») est respecté à la lettre, mais le
  sous-rapport couvre ici des erreurs **garanties** par le compilateur — l'utilisateur qui
  colle du code non importé n'a ni squiggle ni quick-fix.
- **Options considérées** :
  1. **Statu quo documenté** — consigner dans `LSP_KOTLIN.md` que la voie 6 sous-rapporte
     les appelables hors imports par défaut. Coût nul, mais l'écart avec `kotlinc` et avec
     la complétion (qui, elle, exige l'import) persiste.
  2. **Filtrer la voie 6 par visibilité réelle** — un appelable top-level du classpath ne
     résout un nom nu que si son paquet est importé **par défaut** par Kotlin
     (`kotlin.*`, `kotlin.annotation.*`, `kotlin.collections.*`, `kotlin.comparisons.*`,
     `kotlin.io.*`, `kotlin.ranges.*`, `kotlin.sequences.*`, `kotlin.text.*`,
     `kotlin.jvm.*`, `java.lang.*` — le catalogue `IMPORTS_DEFAUT` déjà maintenu pour le
     contrôle KP8) **ou** si le fichier porte un import exact/joker du paquet.
  3. **Étendre aux homonymes multiples avec désambiguïsation par arité** — trop proche du
     comportement du compilateur complet pour un modèle conservateur ; hors périmètre T1.
- **Décision** : retenir l'**option 2**. La voie 6 de `estResolu` reçoit le contexte
  d'imports du fichier (déjà transporté par `ResolutionKotlin.ContexteNom`) et ne retient
  un appelable indexé que si `estEnPortee(appelable, contexte)` — le même prédicat que
  celui du survol d'appelable en portée (`InterrogationsSymboles.estEnPortee` : paquet
  courant, import exact, joker, défauts Kotlin) — est vrai pour au moins une entrée
  homonyme. Le prédicat existe déjà : la décision est un RÉUTILISAGE, pas une nouvelle
  sémantique. Le quick-fix d'import reste branché sur `importsCandidats` (déjà correct :
  il exclut les candidats déjà importés), et `K4` continue de sous-rapporter en régime
  sans classpath (garde existante).
- **Conséquences** :
  - `PI` sans import devient `kt.unresolved` → le quick-fix « Importer « kotlin.math.PI » »
    devient atteignable depuis le diagnostic (parité avec le flux codeAction éprouvé sur
    `Intent`, android.jar).
  - `println`, `emptyList`, `trim` (paquets par défaut) restent résolus — aucun faux positif
    nouveau ; la fixture KP8 existante (« imports par défaut jamais signalés ») verrouille
    ce point.
  - Les appelables de bibliothèques tierces par nom simple deviennent signalés sans import —
    alignement complet sur `kotlinc`.
  - Tests : la sonde S5 du harnais (`ScenariosSondesTest`) passe de la caractérisation
    « 0 diagnostic » à « 1 `kt.unresolved` » ; ajouter une fixture moteur
    (`SessionLangageKotlinK4Test`) « appelable kotlin.math sans import — signalé » et
    verrouiller le non-regtession sur la fixture KP8.
  - Risque : si un paquet par défaut manque du catalogue `IMPORTS_DEFAUT`, un faux positif
    apparaît — le catalogue est déjà testé par KP8 ; le risque est couvert.
