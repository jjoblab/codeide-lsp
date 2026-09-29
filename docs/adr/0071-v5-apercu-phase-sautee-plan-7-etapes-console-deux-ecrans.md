# ADR 0071 — v5 (aperçu) : phase SAUTÉE « en cache », plan d'affichage à 7 étapes, console sans chronologie brute

- Statut : accepté (2026-09-29)
- Contexte : retour utilisateur sur la 0.39.0 — « tu n'as pas enlevé les
  anciennes écrans du console, tu dois analyser la preview pour qu'il
  correspond » ; l'aperçu interactif (`Aperçu du tooling.html`) est la
  référence visuelle du prompt §3.3.
- Décision : voir `docs/TOOLING.md` section v5 (UI)

## Contexte

Trois écarts entre la 0.39.0 et l'aperçu :

1. **La console garde ses anciens écrans** : le filtre TOUS (aucune chip)
   rend la chronologie BRUTE — stdout/stderr en liste plate, étapes en
   lignes de texte — et les vues Sync/Build mélangent encore des lignes
   brutes autour de l'arbre et des tâches. L'aperçu ne montre QUE l'arbre
   (Sync) ou les tâches (Build), chips exclusives, jamais de chronologie.
2. **Le déroulé de sync ment sur la distribution** : `SyncHandler` ouvrait
   DISTRIBUTION puis la concluant aussitôt quand elle est déjà en cache —
   la console affichait « ✓ Téléchargement de la distribution Gradle… (0 s) »
   pour un travail qui n'a pas eu lieu. L'aperçu rend la rangée en « en
   cache » atténuée et ne déroule le téléchargement (barre, octets, artefact)
   QUE si elle manque.
3. **Le plan affiché compte 8 rangères dont une reste « ○ à vie »** :
   DEPENDANCES n'est annoncée que si des téléchargements ont lieu — sur une
   sync chaude elle restait en attente pour toujours. L'aperçu déroule
   SEPT étapes, « Dépendances et modèle IDE » fusionnées, et clôt par un
   pied (« Synchronisation terminée… / Projet à jour, rien à télécharger… »).

## Décision

1. **Protocole v5** (`PROTOCOL_VERSION` 5) : `SyncProgress.sautee`
   (booléen à défaut) marque une phase satisfaite d'AVANCE. Le serveur
   (`ConteurPhasesSync.sauter`) publie la phase conclue-sans-travail (durée
   0) UNE fois, sans l'ouvrir : une phase ouverte ne peut plus être sautée
   (le travail a eu lieu), `conclureTout` ne la re-conclut pas. Dorés dorés
   régénérés (`REGENERER_DORES=1`), round-trip et client mappés
   (`EtapeSyncTooling.sautee` → `EtapeSyncAffichee.sautee`).
2. **La distribution ne se DÉROULE que si elle manque** : `SyncHandler`
   sonde `EtatsDistribution.estInstallee` AVANT l'action — installée →
   `sauter(DISTRIBUTION, element = nom du zip)` ; manquante → `ouvrir` + le
   sondeur des `.part` (octets, conclusion au marqueur `.ok`). Aucun « ✓ 0 s »
   mensonger ne peut plus paraître.
3. **Le plan d'affichage compte 7 étapes** (`EtapeConsoleSync` — UI
   seulement, les 8 phases du CÂBLE restent réelles) : MODELE_IDE et
   DEPENDANCES partagent la rangée « Dépendances et modèle IDE » — les deux
   se produisent pendant la même résolution et la fusion conclut honnêtement
   dans les deux cas (état CONSOLIDÉ : annoncé si l'une l'est, terminé quand
   toutes les annoncées le sont, durée CUMULÉE des phases terminées, détails
   de la phase VIVANTE puis du dernier compteur). `EtatGradle.numeroEtape`/
   `totalEtapes` suivent le plan affiché (« étape n/7 »), le sous-titre de
   l'en-tête projette la phase câble sur son étape affichée.
4. **La console n'a plus que DEUX écrans** : `FiltreCanalConsole` perd TOUS
   — les chips vivent dans un `ChipGroup` `singleSelection` +
   `selectionRequired` (l'une des deux TOUJOURS active, Sync par défaut,
   l'état survit à la rotation). Vue Sync : l'arbre + le DÉTAIL sous
   l'étape active + le PIED de conclusion (absent en vol/à l'échec ; «
   Projet à jour, rien à télécharger » quand aucun octet n'a été reçu) —
   plus AUCUNE ligne brute. Vue Build : les tâches seules + la synthèse —
   la rangée de tâche perd son étiquette de canal (le chip dit qui parle),
   `ligne_sortie.xml` devient `ligne_tache_console.xml`. Les sorties brutes
   restent CAPTURÉES dans l'état borné (fenêtre 2 000) mais ne sont plus un
   écran ; les diagnostics vivent dans l'onglet Problèmes, le journal
   applicatif dans l'onglet Journal.
5. **Le rendu « sautée »** : marqueur = point plein
   (`point_etape_sautee.xml`, `?attr/colorOutline` — aucune couleur dure),
   libellé atténué (alpha 0,55), « En cache » (`editor_console_etape_en_cache`)
   à la place de la durée — jamais de durée devinée (règle 9 : le travail
   n'a pas eu lieu, il n'y a RIEN à mesurer). L'arbre reste ABSENT tant
   qu'aucune sync n'a été annoncée (l'état vide parle) et couvre toujours
   le chemin complet (○ en attente).
6. **Le sous-titre de succès** passe à « N modules · N tâches · aucun
   téléchargement / classpaths prêts » (compteur final de CLASSPATHS,
   honnêteté « aucun téléchargement » = aucun octet reçu) avec repli défensif
   sur « Tâches disponibles : N ». Libellés des étapes au NOMINATIF
   (« Distribution Gradle », « Modèle des tâches »…) : le marqueur porte
   l'état, le libellé ne le répète plus.

## Conséquences

- `lignes` (fenêtre bornée) ne porte plus l'affichage des sorties brutes :
  `etapesAffichees` (lignes `Etape`) reste la source de vérité de l'arbre,
  la capture des `Sortie` est conservée (tête tronquée, rejouable côté
  client — ADR 0041) pour un usage futur explicite.
- Une sync chaude se lit comme l'aperçu : distribution « En cache » (point
  gris), « Dépendances et modèle IDE » conclue même sans téléchargement,
  pied « Projet à jour, rien à télécharger » — plus aucun ○ orphelin.
- Non livré (documenté au CHANGELOG) : « Daemon réutilisé » en rangée
  sautée — la Tooling API n'expose AUCUN signal honnête de réutilisation,
  la durée mesurée reste affichée plutôt qu'un statut deviné ; les
  téléchargements dans la vue Build (§6) et l'écran de config enrichi (§7)
  restent différés.
- Chaîne CI complète vérifiée localement (spotless, detekt,
  checkModuleDependencies, lintDebug, testDebugUnitTest, koverVerify,
  assembleDebug) ; `ActivityEditorLayoutTest` verrouille le ChipGroup
  exclusif, le marqueur sautée et la rangée de tâche.
