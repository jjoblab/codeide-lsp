# ADR 0026 — Espace de travail : tiroir permanent sur grand écran et dépendance cel-ui

- **Statut** : accepté (étape 13)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et
  bibliothèque d'édition », sections 1 à 3 et 5 — fondations de
  l'espace de travail.

## Contexte

`EditorActivity` organise le travail en trois zones (tiroir de
navigation, zone centrale, panneau inférieur). Sur grand écran, le
prompt compagnon propose un tiroir « ancré en permanence (façon IDE de
bureau) » — décision à documenter, testée aux deux formats. Par
ailleurs, la bibliothèque d'édition `code-editor` (JitPack) devient la
première dépendance externe directement consommée par une
fonctionnalité : une exception aux règles de dépendance à inscrire
formellement.

## Décisions

1. **Tiroir permanent sur grand écran (sw600dp+).** Le `DrawerLayout`
   est verrouillé ouvert (`LOCK_MODE_LOCKED_OPEN`) dès que la plus
   petite largeur mesurée atteint 600 dp : le tiroir devient un panneau
   fixe, le geste de bord et le bouton ☰ disparaissent, la zone centrale
   occupe le reste. En dessous, comportement standard : tiroir
   masqué, ouvert par ☰ ou geste de bord. Un seul layout sert les deux
   formats — la bascule est programmatique et testable.
2. **`cel-ui` via JitPack : exception documentée.** `feature:editor`
   dépend de `com.github.jjoblab.code-editor:cel-ui:3.37.0` (dernier
   tag stable vérifié le 2026-09-23 ; résolution JitPack vérifiée :
   aar + pom + module) — c'est une **bibliothèque de composants d'UI au
   même titre que Material Components**, pas une source de données :
   elle ne crée pas de dépendance entre fonctionnalités et ne viole
   pas la règle « les features ne parlent qu'aux interfaces du
   domaine ». Le dépôt `maven { url = uri("https://jitpack.io") }` est
   ajouté au gestionnaire de résolution global ; l'alternative GitHub
   Packages (`gpr.user`/`gpr.key` locaux, jamais versionnés) est
   documentée dans `docs/ENVIRONNEMENT.md`. Les règles ProGuard de la
   bibliothèque (prompt compagnon, section 2.3) sont ajoutées dès
   maintenant à `app/proguard-rules.pro` — leur vérification en
   conditions réelles (`assembleRelease`) a lieu à l'étape 18.
3. **Pas de logique à l'étape 13.** Les trois zones sont posées vides :
   tiroir (en-tête seul — l'explorateur arrive à l'étape 14), zone
   centrale (état vide), panneau inférieur replié à trois onglets vides
   (contenu à l'étape 16). Le `EditorViewModel` ne sait faire qu'une
   chose : charger le projet reçu par l'intention (via
   `SavedStateHandle`) et le suivre au registre.
4. **Navigation par-dessus la pile.** `AppNavigator.openEditor(projectId)`
   lance l'activité par-dessus la pile courante : l'accueil survit en
   dessous, y revenir ne recharge rien. « Ouvrir » (accueil) et
   « Ouvrir le projet » (succès du wizard) marquent `lastOpenedAt`
   **avant** de naviguer — le tri des récents de l'accueil est déjà à
   jour au retour.

## Conséquences

- Le retour système ferme le tiroir s'il est ouvert, sinon quitte —
  un `OnBackPressedCallback` conditionnel, jamais de pile manuelle.
- L'identifiant du projet transite par l'extra d'intention puis le
  `SavedStateHandle` : rotation et mort du processus relancent le
  chargement sans état global.
- Un projet supprimé pendant l'édition fait passer l'écran en état
  « Projet introuvable » (le flot du registre émet `null`) — jamais de
  crash, jamais d'état fantôme.
- `cel-lsp` (serveurs de langage) n'est **pas** ajouté : hors périmètre
  Phase 1 (section 13 du prompt maître), le point d'ancrage est noté
  pour la Phase 2.

## Alternatives rejetées

- **Tiroir masqué sur tous les formats** : gâche l'écran d'une tablette
  en paysage, contredit l'usage IDE de bureau visé par le prompt
  compagnon.
- **Deux layouts distincts** (`layout` + `layout-sw600dp`) : duplication
  de tout l'arbre pour une bascule verrou/geste — la décision
  programmatique est plus courte et testable.
- **GitHub Packages par défaut** : exigerait un jeton personnel même
  pour un dépôt public — le build de l'agent ne serait plus autonome.
