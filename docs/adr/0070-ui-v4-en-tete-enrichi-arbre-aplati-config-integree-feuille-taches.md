# ADR 0070 — UI v4 : en-tête enrichi, console en arbre aplati, configuration intégrée, feuille des tâches

- Statut : accepté (2026-09-29)
- Contexte : prompt « CodeIDE — tooling Gradle professionnel » §3.3 (étape 5)
- Décision : voir `docs/TOOLING.md` section v4 (UI)

## Contexte

Les fondations v4 (ADR 0069) ont livré l'état (`EtatGradle.etapesAffichees`,
`tachesDisponibles`), le présentateur (`EtatEnteteTooling`) et le contrôleur
(`PanneauToolingController`) — mais l'en-tête du sheet restait une ligne de
texte à progression indéterminée, la console une liste plate sans filtre de
canal, la configuration un dialogue PLEIN ÉCRAN au-dessus de l'éditeur, et
le sélecteur de tâches une `MaterialAlertDialog.setItems` alimentée par un
aller-retour serveur de 30 s. Le prompt §3.3 demande la fenêtre Build
d'Android Studio : toujours savoir ce qui se passe, jamais d'échec muet.

## Décision

1. **L'arbre de la console est une liste APLATIE de rangées typées**
   (`RangeeConsole`), pas un arbre de vues : l'indentation porte la
   hiérarchie (détail de téléchargement sous l'étape active), le `DiffUtil`
   garde la mise à jour EN PLACE (clés stables `etape-PHASE`,
   `detail-PHASE`, `ligne-ID`, `synthese-build`). Le constructeur
   (`construireRangeesConsole`) est une fonction PURE testée ; les 8 phases
   du plan restent TOUJOURS visibles (○ en attente) — le chemin complet se
   lit comme la vue Build, la durée ne s'affiche que MESURÉE (jamais
   devinée en cours — règle 9 du prompt).
2. **Le filtre de canal (chips Sync/Build) est un état de VUE** porté par
   le fragment (survit à la rotation par l'état d'instance), pas un état
   d'application : le ViewModel reste celui du tooling. Chips exclusives :
   Sync = arbre, Build = tâches + synthèse, aucune = chronologie.
3. **Le statut visuel de la pastille vit dans le présentateur**
   (`StatutEntete` : EN_VOL / SUCCES / ECHOUE / NEUTRE) — pur, testé ; les
   couleurs se résolvent au rendu (canal harmonisé `ThemeHarmonizer`,
   `?attr/colorSucces`, `?attr/colorError` — suivent les 8 palettes et la
   nuit, aucune couleur dure dans la feature). Le spinner en vol et la
   progression DÉTERMINÉE (octets recus/total, pleine au succès) remplacent
   l'indéterminé permanent.
4. **La configuration vit DANS le conteneur de la console** :
   `PanneauConfigToolingFragment` (fragment enfant ajouté une fois puis
   montré/caché) remplace le `DialogueConfigToolingFragment` plein écran —
   flèche retour en tête, retour système intercepté LIFO
   (`OnBackPressedCallback` armé pendant l'affichage), bouton d'accès
   LIBELLÉ. Même contrat de réglages (rendu idempotent DataStore,
   correctif n°10 conservé) : seul le conteneur change.
5. **La feuille des tâches est alimentée par le CACHE** :
   `ouvrirSelecteurTaches` répond d'abord depuis `tachesDisponibles`
   (aucun aller-retour — correctif n°6), les tâches voyagent dans les
   ARGUMENTS (la feuille survit à la mort du processus), l'échec de
   listage remonte par effet (`ErreurListageTaches` → snackbar +
   « Réessayer ») — plus d'échec avalé. Recherche en direct + récentes en
   chips + groupes Gradle dans l'ordre d'apparition, constructeur PUR
   (`construireRangeesTaches`) testé.
6. **Aucune `!!` ajoutée** : les canaux non nuls passent par des locales
   (`val canal = etat.canalActif ?: etat.canalDernierResultat` + null
   check), exemptions detekt ciblées seulement là où le dépôt a un
   précédent (fragments de câblage, présentateur multi-cascades — chacune
   documentée et testée).

## Conséquences

- L'UI « raconte » le déroulé v4 en continu : OUTILS → DISTRIBUTION (Mo
  reçus) → … → CLASSPATHS, sans période muette (critère d'acceptation §5) ;
  « Synchronisé » ne s'affiche qu'après les classpaths, le bouton Tâches
  s'active sur ce FAIT et s'ouvre sans latence.
- Les exemptions detekt `TooManyFunctions` documentées s'ajoutent aux
  précédents du dépôt (TerminalTiroirFragment, InstallFragment,
  GradleService) — mêmes justifications de contrat de câblage.
- L'écran de configuration ENRICHI du §7 (affichage, exécution,
  environnement, orchestrateur, commande effective, recherche, 2 colonnes)
  reste différé et documenté dans le CHANGELOG ; l'écran intégré livré
  garde les réglages v3.
