# ADR 0214 — Contrôles de passage : `reesayerEchecs()` ciblé et `annulerPassage()` coopératif

- **Statut** : appliqué (24/10/2026 — demandes D4 et D5 de
  `CodeIDE/docs/DEMANDES_CODEIDE_LSP.md` ; moteurs Kotlin/XML + index
  Java + façades + tests).
- **Contexte** : la maquette de l'écran d'indexation (docs/mockups/
  indexation/) propose « Réessayer les entrées échouées » et « Annuler le
  passage » ; or `SessionLsp.reindexer()` fait revalidation + auto-guérison
  de TOUT (les deux boutons mappent sur le MÊME appel, documenté ADR 0145
  côté CodeIDE), et AUCUN point d'annulation n'existe côté orchestrateur
  ni côté index. La demande : soit livrer les DEUX APIs, soit répondre
  officiellement « non distinguable » / « non annulable » pour que l'écran
  retire l'offre.
- **Options considérées** :
  1. **Livrer les deux, par moteur** (retenue) — chaque moteur sait ce
     qu'il a ÉCHOUÉ et où se COUPER : Kotlin/XML par la construction
     ADR 0211 (passe unique, fil dédié, entrées comptées), Java par son
     `Job` de construction DÉJÀ annulable et sa liste de travail qui
     marque déjà les entrées ECARTEES.
  2. **Répondre « non distinguable »** : honnête pour l'index Java d'il y
     a un an (aucun échec tracé), mais les trois moteurs TRACENT déjà
     leurs échecs (avertissements par entrée, `doutesAar` XML, segments
     écartés Kotlin, `EtatEntreeTravail.ECARTE` Java) — l'excuse
     n'existe plus, rejeté.
  3. **Annulation destructive** (`Process.destroy`, interruption de fil)
     : D5 demande un drapeau COOPÉRATIF testé ENTRE les entrées (une
     annulation propre laisse le passage PARTIEL, pas corrompu), rejeté.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - **`reesayerEchecs()` — le cache rend la reprise ciblée GRATUITE** :
     rejouer la définition complète ne re-paye QUE les échecs, parce que
     les succès vivent en cache (Kotlin : cache mémoire par empreinte
     ADR 0211 — les entrées saines deviennent REPRIS sans toucher le
     disque ; XML : empreintes mtime/AAR — seuls les dossiers et AAR non
     en cache sont re-balayés ; les ILLISIBLES ne sont jamais mis en
     cache, donc TOUJOURS retentés). Le moteur ne re-passe donc QUE les
     échecs, et l'événement ADR 0213 le MONTRE (REPRIS massifs = reprise
     ciblée visible). Retour `false` quand il n'y a RIEN à retenter
     (aucun écart au dernier passage, ou AUCUN dernier passage) —
     l'écran peut griser le bouton sans deviner.
  - **`annulerPassage()` — drapeau volatile testé ENTRE les entrées** :
     Kotlin (segments ET fichiers du scan), XML (racines `res/` et AAR)
     consultent le drapeau entre deux unités de travail ; le passage
     publie ce qui est ACQUIS (snapshot partiel immable — les requêtes
     sous-lisent le reste) et se marque PARTIEL avec l'avertissement
     « annulé par l'utilisateur » (settled = false). Retour `false`
     quand aucun passage n'est en vol.
  - **Java** : `ServiceIndexDisque.annulerPassage` n'a pas de drapeau à
     poser — la construction EST un `Job` structuré (`ensureActive()`
     entre les entrées, arrêt propre par `CancellationException` qui se
     propage, jamais avalée) ; le service ATTACHE au lancement un
     `invokeOnCompletion` qui publie PARTIEL « annulé par l'utilisateur »
     si la cause est une annulation (la publication ne se court-circuite
     jamais avec une course de fin de coroutine). `reesayerEchecs`
     filtre les positions ECARTEES de la liste de travail courante et ne
     relance QUE celles-là, en SEMANT les statistiques du passage
     précédent (les entrées saines gardent leur état, les retentées
     repassent ACTIF → TERMINE/ECARTE, les avertissements obsolètes des
     réussites partent, la phase finale est recalculée sur l'ensemble).
  - **APIs exposées** : `SessionLsp.annulerPassage()/reesayerEchecs()`
     (Java), `SessionLspKotlin.…`, `SessionLspXml.…` (façades) ; SPI
     moteur `MoteurLangageKotlin/MoteurLangageXml.annulerPassage()/
     reesayerEchecs()` avec DÉFAUTS `false` (pack ancien = l'écran
     retire l'offre SANS crash — tolérance de version, miroir des
     défauts vides du SPI).
  - **Bornes honnêtes** : une annulation ne RÉTABLIT pas les entrées non
     traitées (le snapshot partiel est la vérité, la reprise le
     complète) ; le PLAFOND de concurrence Java (min(4, cœurs/2))
     s'applique à la reprise comme au passage complet ; côté orchestrateur
     Java, l'annulation INTERNE (relance de sync qui remplace une
     construction) produit le MÊME PARTIEL transitoire — conflated par
     le `StateFlow`, la construction neuve le recouvre au tick suivant
     (documenté, sans effet visible durable).
- **Conséquences** :
  - L'écran multi-moteurs peut brancher ses DEUX boutons : « Réessayer
     les entrées échouées » n'invalide plus les segments sains (la
     reprise est visible et bon marché), « Annuler le passage » rend la
     main en moins d'une unité de travail et laisse un état PARTIEL
     exploitable — le contrat exact de la maquette.
  - Pour CodeIDE, `reindexer()` reste l'action LOURDE (« tout
     revalider »), `reesayerEchecs()` l'action DOUCE — les deux boutons
     de la maquette deviennent enfin DISTINCTS.
  - Un pack ancien répond `false` aux deux : l'écran retire les offres
     au lieu de planter — la voie officielle « sinon l'écran retire
     l'offre » de la demande est honorée sans casse.
