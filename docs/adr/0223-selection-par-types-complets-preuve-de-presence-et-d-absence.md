# ADR 0223 — Sélection d'appelé par types complets : prouver la présence ET l'absence

- **Statut** : appliqué (25/10/2026 — suites recommandées n° 3 du
  rapport d'audit §8 et « sélection d'appelé par types complets » citée
  par LSP_KOTLIN §4.3 depuis K4 ; la sonde empirique du moteur a
  démontré le faux positif LATENT : `layoutInflater.inflate(1, null,
  null)` sur un récepteur typé LIISIBLE était diagnostiqué
  `kt.unresolved` — le callee membre d'instance n'est PAS un appelable
  de portée, la résolution par nom seul ne pouvait pas le trouver).
- **Contexte** : le diagnostic `kt.unresolved` traitait les sélecteurs
  qualifiés (`a.b`) en tout-ou-rien :
  - **Callee d'un appel qualifié** (`layoutInflater.inflate(…)`,
    `tv.setText(…)`) : la garde `estSelecteurQualifie` S'ARRÊTE au
    CALL_EXPRESSION — le callee tombait dans la résolution de NOMS,
    qui ne connaît ni les membres d'instance du type du récepteur ni
    les companions : FAUX POSITIF dès que le récepteur type (corrigé
    par cette ADR) ou sous-rapport (récepteur non typable).
  - **Sélecteur NU** (`tv.texte`, `binding.roote`) : muté
    inconditionnellement (« le récepteur peut être inconnu ») : VRAI
    NÉGATIF perdu — Android Studio signale la faute de frappe en rouge.
- **Options considérées** :
  1. **Sonde par types complets à deux issues** (retenue) — quand le
     RÉCEPTEUR d'une sélection type (valeur ou CLASSE/statique), la
     cascade du SURVOL qualifié (champ → méthode → getter/setter
     synthétique → extension sur le récepteur, étendue du compagnon
     ADR 0222 et du membre du projet) décide : membre PROUVÉ → la
     référence RÉSOUT (répare le callee) ; absence TOTALEMENT prouvée
     → le SÉLECTEUR redevient diagnostiquable (miroir Android Studio) ;
     sinon → silence D3 inchangé.
  2. **Diagnostiquer tout sélecteur à récepteur typé sans le garde
     extension** — rejetée : la preuve d'absence d'EXTENSION exige la
     complétude des jars d'extensions ; sans elle, `Modifier.padding`
     sur un corpus sans foundation-layout serait un FAUX positif.
  3. **Étendre la résolution de noms (voie 7 « membre du récepteur
     typé »)** — rejetée : `estResolu` est un prédicat PAR NOM sans
     position de récepteur — la sélection qualifiée exige la chaîne
     d'ancêtres, un prédicat séparé est le miroir honnête du survol.
- **Décision** : retenir l'**option 1** :
  - `DiagnosticsSemantiques.selectionParTypes` (issues
    `MEMBRE_TROUVE` / `ABSENCE_PROUVEE` / `INCONNU`) : localise
    l'expression qualifiée PORTEUSE (la remontée TRAVERSE le CALL qui
    porte la référence comme callee, S'ARRÊTE aux arguments — un
    argument n'est pas une sélection), type le récepteur
    (`typeExpression` puis `typeStatiqueDe`), puis la cascade
    complète de preuve.
  - L'ABSENCE n'est prouvée que si : la classe du récepteur est
    LISIBLE dans la vue (`membresDe != null` — une classe projet ou
    illisible ne prouve rien), l'INDEX PORTE des entrées
    (`connaitDesEntrees` — une session nue ne connaît AUCUNE
    extension : l'absence n'y est pas prouvable) et AUCUNE extension
    homonyme n'existe NULLE PART dans l'index (la preuve d'absence
    d'extension exige la complétude des jars : un seul homonyme — tout
    récepteur — impose le silence).
  - `fqcnDuNomEcrit` NORMALISE le nom écrit (`String?`, `List<Int>` →
    la classe cible) — miroir `traduireTypeEcrit` : sans elle, TOUT
    récepteur local de type nullable ou générique restait muet
    (`binding?.root`, `items.size`).
  - `IndexAppelablesKotlin.connaitDesEntrees` : le snapshot porte-t-il
    au moins une entrée de classpath.
  - Les tests K4 qui épinglaient l'ancien sous-rapport sont RÉÉCRITS
    sur le nouveau contrat (le sélecteur introuvable sur récepteur
    typé EST signalé).
- **Preuves** :
  - **Moteur** : `SessionLangageKotlinK4Test` (4 tests réécrits) —
    `s.inexistant` SIGNALÉ (récepteur kotlin.String, stdlib réelle),
    `s.trimInexistant()` SIGNALÉ (callee), `s.plus("!")` RÉSOUT
    (callee membre — le faux positif latent), récepteur NON typable
    → sélecteur MUET (D3 inchangé).
  - **Moteur** : `ReproductionCompanionTest.garde D3` — le membre
    introuvable du compagnon (`Regex.echappe`) reste signalé.
  - **E2E modèle Gradle** (`ReproductionGradleModeleE2ETest`) : le
    script à classpath Gradle réel — zéro faux positif, le statique
    `JavaVersion.VERSION_11` survolé et complété (les QUALIFIÉS
    prouvent leur présence contre le classpath fourni — miroir
    Android Studio : avec le classpath donné, l'absent est rouge).
