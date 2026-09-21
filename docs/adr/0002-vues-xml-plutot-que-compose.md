# ADR 0002 — Vues XML + ViewBinding plutôt que Jetpack Compose

- **Statut** : accepté (étape 0 — imposé par le prompt maître, section 2)
- **Contexte** : CodeIDE est un IDE embarqué, avec une interface riche mais
  essentiellement composée de listes, formulaires, assistants et visionneuses
  de texte. L'équipe doit pouvoir raisonner sur chaque écran de façon
  classique et prévisible, et l'application doit rester légère.
- **Décision** : construire l'interface avec des **vues XML + ViewBinding**,
  des **Activities + Fragments** et **Material 3** (XML), sans Jetpack
  Compose. La navigation utilise le Navigation Component ; les listes,
  RecyclerView + ListAdapter/DiffUtil ; les paginations d'assistant,
  ViewPager2 ; les transitions, l'API Material (`MaterialSharedAxis`,
  `MaterialFadeThrough`).
- **Conséquences** :
  - style d'UI déclaratif éprouvé, outillage stable, pas de runtime Compose ;
  - binding vérifié à la compilation (ViewBinding) sans reflection ;
  - coût : plomberie plus verbeuse qu'avec Compose (classes Binding,
    adapters), assumé pour la prévisibilité et la maîtrise du cycle de vie ;
  - la porte reste ouverte à une réévaluation future par l'utilisateur
    (décision réversible par écran, la logique étant dans les ViewModels).
