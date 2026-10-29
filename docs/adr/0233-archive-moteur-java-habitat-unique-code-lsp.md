# ADR 0233 — Archivage du moteur Java vendored : code-lsp devient l'habitat unique

- **Statut** : appliqué (30/10/2026 — instruction explicite du propriétaire :
  « Je veux que ce soit le lsp java de code-lsp qui soit utilisé uniquement
  dans CodeIDE. Je t'autorise à archiver le lsp java de codeide-lsp » ;
  suite directe de la bascule §8.5 signée le 30/10/2026 dans le dépôt
  `jjoblab/code-lsp`, dont la checklist 6/6 était verte).
- **Contexte** : la bascule du moteur Java est consommée côté fournisseur —
  `code-lsp` **v1.0.0 est publiée** sur GitHub Packages
  (`jo.codeide.lsp.java:jo-codeide-lsp-java:1.0.0` + compagnon
  `jo-codeide-lsp-java-ecj-art:1.0.0`, tag `v1.0.0`, workflow
  « Publication » vert, artefacts re-téléchargés et vérifiés —
  `docs/BASCULE-CODEIDE.md` §1 du dépôt code-lsp) et les rails de
  consommation sont poussés dans `jjoblab/CodeIDE` (commit `8450bb7` :
  dépôt Packages + filtres `includeGroup` + mavenLocal filtré + catalogue +
  composite sur opt-in). Le propriétaire a déclaré `code-lsp` « le nouveau
  et seul habitat des LSP de CodeIDE », la migration ou réécriture complète
  des LSP **Kotlin** et **XML** restant à sa main. Le moteur vendored
  `:lsp:java` (19 423 lignes de Java main / 48 fichiers, ADR 0108) n'a plus
  vocation à évoluer ; il reste consommé par `:lsp:host` au travers de la
  façade `jo.lspjava.api.*` jusqu'à l'adaptation de l'hôte à la façade
  neuve (`jo.codeide.lsp.java.*`) — le chaînon restant, surface mesurée à
  4 fichiers main + 5 fichiers de test, estimé 3–6 j-h
  (`docs/BASCULE-CODEIDE.md` §3.2.2 du dépôt code-lsp).
- **Options considérées** :
  1. (a) **Archiver maintenant : statut ARCHIVÉ, module gelé mais laissé au
     build** (retenue) — l'archivage est un ÉTAT (gel documenté, référence
     de remplacement publiée), pas un retrait : `:lsp:host` dépend toujours
     de `project(":lsp:java")`, et le moteur vendored reste l'oracle en
     boîte noire des bancs croisés de `code-lsp` pendant la période de
     recouvrement ;
  2. (b) Retirer immédiatement le module du build — rejetée : le
     remplaçant n'est pas encore branché côté hôte (chaînon restant) ; le
     retrait précéderait le remplaçant et casserait la chaîne
     CodeIDE → `:lsp:host` → moteur ;
  3. (c) Ne rien marquer et laisser le double habitat — rejetée : la
     divergence est certaine (deux moteurs à faire évoluer en parallèle,
     coût double sur chaque capacité), et la règle « 2 versions sans
     régression » héritée de l'extraction (ADR 0012–0014) exigera de toute
     façon un gel pendant la période de recouvrement — autant le déclarer
     dès maintenant.
- **Décision** :
  1. `:lsp:java` est **ARCHIVÉ le 30/10/2026**. Le module est **gelé** :
     aucune nouvelle capacité, aucune évolution. Un correctif ne peut
     intervenir que sur instruction explicite du propriétaire, en dernier
     recours, si la bascule n'est pas encore effective et qu'aucun repli ne
     tient (le repli documenté est la substitution inverse — une ligne dans
     `CodeIDE/settings.gradle.kts`, BASCULE-CODEIDE.md §3.3).
  2. La référence de remplacement est la coordonnée publiée
     `jo.codeide.lsp.java:jo-codeide-lsp-java:1.0.0` (compagnon
     `runtimeOnly` `jo-codeide-lsp-java-ecj-art:1.0.0`) sur
     `maven.pkg.github.com/jjoblab/code-lsp`.
  3. Le module **reste au build, compilable et publiable** tant que
     `:lsp:host` consomme la façade `jo.lspjava.api.*` — l'hôte reste
     ACTIF (c'est le véhicule de l'intégration du moteur neuf) ; sa
     migration est le chaînon restant de la bascule.
  4. `:lsp:ecj-art` est **gelé solidairement** (il ne sert que le patch ART
     du moteur vendored, jamais dans l'APK) ; la part « moteur ↔ index » de
     `:lsp:testing` porte le même statut. `:lsp:model`, `:lsp:index`,
     `:lsp:host`, `:lsp:kotlin` et `:lsp:xml` restent **ACTIFS** — ce dépôt
     concentre son évolution sur les LSP Kotlin et XML et sur l'hôte.
  5. Le **retrait physique** du module suivra la règle « 2 versions sans
     régression » (héritage ADR 0012–0014) : il ne sera effectué qu'après
     deux versions de CodeIDE servies par le moteur neuf sans régression.
     Jusque-là, le vendored reste joignable comme **oracle en boîte noire**
     pour les bancs croisés de `code-lsp` (épinglage `cb809e14`) — le
     clean-room de code-lsp interdit la lecture de ses sources : l'oracle
     s'exécute et se compare, il ne se lit pas.
  6. La section Java de la matrice des capacités
     (`docs/LSP_MATRICE_CAPACITES.md`) devient un **document d'époque** :
     la référence vivante des capacités du moteur Java est désormais le
     `USAGE.md` du dépôt `code-lsp` (surface LSP exacte, extensions
     expérimentales comprises — `superDefinition`, `decompiledSource`).
- **Conséquences** : marquages posés partout où le module se découvre
  (README racine, `docs/LSP_JAVA.md`, `lsp/java/README.md`, en-tête de
  `lsp/java/build.gradle.kts`, CHANGELOG) ; l'effort moteur Java bascule
  intégralement vers `code-lsp`, les demandes de capacités Java nouvelles
  sont redirigées vers ce dépôt ; aucune incidence sur les artefacts publiés
  de ce dépôt (le gel est déclaratif — le contenu des coordonnées
  `com.github.jjoblab.codeide-lsp:*` est inchangé) ; l'adaptation de l'hôte
  et la bascule de consommation restent suivies dans
  `docs/BASCULE-CODEIDE.md` §3.2.2 et `docs/INTEGRATION-CODEIDE.md` du
  dépôt `code-lsp`.
