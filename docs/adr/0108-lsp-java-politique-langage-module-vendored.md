# ADR 0108 — D1 : LSP Java en module JVM vendored Java, nouvelles couches en Kotlin

- **Statut** : accepté (v0.91.0, étape 0 de la mission « LSP Java » —
  prompt 2 §3 D1)
- **Contexte** : `AGENTS.md` impose « Kotlin 100 %, aucun Java écrit à la
  main ». Le matériel fourni (`lsp-java-module.zip`) est un moteur de
  19 423 lignes de Java (48 fichiers, plus 9 747 lignes de tests sur 42
  fichiers et 468 lignes pour `ecj-art`) bâti sur ecj/JDT, avec des
  correctifs de comportement ART éprouvés sur appareil (shims
  `Runtime$Version`/`StackWalker`/`readAllBytes`, `RawZipReader` contre
  les rafales CloseGuard, FQCN « racine la plus profonde d'abord »).

## Décision

**(a) Conserver `lsp-java` comme module JVM vendored en Java, exempté de
la règle « Kotlin 100 % » par le présent ADR**, avec :

1. **Exemption ciblée et commentée** (règle 8 d'AGENTS.md) : le
   `README`/`Module.md` du module portera l'exemption `@Suppress`
   équivalente en prose — détect/detekt ne s'appliquent pas au Java du
   module vendored tant qu'il n'est pas réécrit.
2. **Toutes les NOUVELLES couches en Kotlin** : `lsp:model`, `lsp:index`,
   `lsp:host`, l'adaptateur `cel-lsp` et l'UI. Le cœur Java est consommé
   derrière une façade (`jo.lspjava.api.*` déjà publique).
3. **Conversion progressive, conditionnelle** : un fichier Java passe en
   Kotlin uniquement s'il doit de toute façon être réécrit (plan D3) ou
   profondément modifié, ET si les tests de non-régression restent verts
   ET si aucune dégradation de latence/mémoire n'est mesurée sur ART.
   Le reste reste inchangé tant qu'aucune mesure ne l'impose.
4. La recommandation de **différer** « Kotlin à 100 % » (décision du
   propriétaire) est ainsi concrétisée : la valeur du module est dans ses
   ~42 fichiers de tests et ses correctifs ART, pas dans sa syntaxe.

## Alternatives écartées

- **(b) Réécriture Kotlin intégrale** : coût élevé (≈20 k lignes), risque
  de régression fonctionnelle sur des comportements subtils (négatifs
  mémorisés, types imbriqués par nom binaire, replis conservateurs),
  aucun bénéfice mesuré sur ART. Écartée.
- **(c) Conversion mécanique progressive inconditionnelle** : churn sans
  bénéfice, contamine l'historique git pendant le chantier D3. Écartée.

## Conséquences

- La règle « Kotlin 100 % » s'applique à tout NOUVEAU code du dépôt ;
  le module vendored est l'exception documentée.
- Le renommage D3 (retrait du préfixe `Jdt`) et le plan de traitement
  par fichier précèdent toute conversion (inutile de convertir ce qui
  sera réécrit — prompt 2 §3).
- `detekt`/`spotless` du dépôt ne formatent pas les sources vendored
  Java ; le module garde son propre style (Javadoc français, JUL).
