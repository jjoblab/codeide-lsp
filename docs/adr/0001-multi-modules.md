# ADR 0001 — Organisation multi-modules Gradle

- **Statut** : accepté (étape 0)
- **Contexte** : CodeIDE est reconstruit de zéro pour disposer d'une base
  saine, modulaire, testée et maintenable. L'application accueillera ensuite
  un terminal, du tooling, des plugins et des services — des blocs aux cycles
  de vie et aux dépendances très différents. Un projet mono-module rendrait
  toute frontière poreuse et ralentirait les builds comme les tests.
- **Décision** : organiser le build en modules Gradle typés — un module
  d'application (`app`), un socle transverse (`core:model`, `core:domain`,
  `core:data`, `core:database`, `core:datastore`, `core:storage`,
  `core:logging`, `core:crash`, `core:ui`, `core:testing`) et des
  fonctionnalités (`feature:onboarding`, `home`, `newproject`, `settings`,
  `diagnostics`, `editor`). Les configurations communes sont factorisées
  dans des convention plugins (`build-logic`), les règles de dépendance
  entre modules sont vérifiées automatiquement par la tâche
  `checkModuleDependencies` qui fait échouer le build en cas de violation.
- **Conséquences** :
  - les frontières sont explicites et outillées ; une dépendance interdite
    ne peut pas passer inaperçue ;
  - les modules purs JVM (`core:model`, `core:domain`) se testent vite, sans
    émulateur ni Robolectric ;
  - les builds parallèles et le cache Gradle accélèrent le développement ;
  - coût : plus de fichiers de build et une discipline de configuration ;
  compensé par les conventions.
