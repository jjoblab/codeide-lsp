# ADR 0025 — Visionneuse de diagnostics : fusion tampon/disque et partage via AppNavigator

- **Statut** : accepté (étape 12)
- **Date** : 2026-09-23
- **Contexte** : section 5.7 du prompt maître, étape 12 — visionneuse de
  journaux performante dans `feature:diagnostics`.

## Contexte

L'écran Diagnostic doit afficher « les 500 dernières entrées,
chargement des plus anciennes au défilement », un mode « suivre en
direct », des actions de partage/enregistrement et l'historique des
rapports de plantage. Deux sources de journaux coexistent : le
**tampon mémoire** (`LogRepository.observeRecent`, fenêtre des ~200
derniers filons de pain) et les **fichiers persistés**
(`LogRepository.readAll`, historique complet, rétention bornée). Les
entrées non encore vidées sur disque (au plus ~500 ms) n'existent que
dans le tampon.

Par ailleurs, le partage d'une archive exige une URI `FileProvider` dont
l'autorité et le répertoire exposé appartiennent au module `app` — hors
de portée d'une fonctionnalité.

## Décisions

1. **Fusion par dédoublonnage d'égalité, relecture unique.** L'historique
   persisté est lu **une fois** à l'ouverture
   (`ReadAllLogsUseCase`) ; le flot du tampon fusionne ensuite les
   nouveautés : une entrée déjà connue (égalité structurelle de la data
   class `LogEntry`) n'est jamais ajoutée deux fois. Si le tampon
   arrive *avant* la lecture disque (processus lent), les entrées du
   tampon s'affichent d'abord, puis la lecture complète les remplace —
   les entrées du tampon absentes du disque (non encore vidées)
   reviennent par la ré-émission suivante du flot. L'état final est
   toujours complet, sans doublon.
2. **Pagination en mémoire.** Les « plus anciennes » révélées au
   défilement proviennent de la liste déjà chargée (paliers de 500) —
   jamais de relecture disque. Le coût borne l'affichage, pas la
   lecture.
3. **Recherche normale insensible aux accents** (NFD, casse pliée),
   appliquée au message, à l'étiquette et à la classe d'exception —
   même règle que la recherche de projets de l'accueil (étape 7).
4. **Partage par `AppNavigator.partagerArchive(nomFichier,
   emplacementInterne)`.** Les fonctionnalités manipulent des
   descriptifs opaques (`ExportedLogs` du domaine) ; l'implémentation
   applicative seule connaît l'autorité du FileProvider, vérifie que
   l'archive vit dans `cache/exports/` (seul répertoire exposé) et
   ouvre la feuille de partage. L'« Enregistrer » écrit directement à la
   destination SAF choisie (`LogExportWriter.write(entries, uri)` /
   `CrashReportsExportWriter.write(reports, uri)`) — aucun fichier
   intermédiaire.
5. **Réglage de verbosité = persistance puis application.**
   `SetLogVerbosityUseCase` écrit `AppSettings.logLevel` **puis** bascule
   le moteur via le port `LogVerbosityApplier` (implémenté par la façade
   `LogLevelApplier` de `core:logging`, désormais sous-type du port). En
   cas d'échec d'écriture, le moteur reste intact — un niveau actif sans
   sauvegarde serait un mensonge au redémarrage.
6. **Menu debug déménagé.** Le bouton flottant de `MainActivity`
   (étape 3) disparaît au profit d'un bouton dans la section
   « Informations » de l'écran Diagnostic — source set `debug` de
   `feature:diagnostics`, no-op en release de même signature. Les
   outils de développement vivent avec les outils de diagnostic, jamais
   sur les écrans de production.

## Conséquences

- La visionneuse n'a jamais deux fois la même entrée à l'écran, mais
  deux entrées **réellement identiques** (même milliseconde, même
  message) vues par le tampon puis par le disque ne comptent qu'une
  fois — perte acceptable pour un outil de consultation.
- `AppNavigator` gagne une action « feuille de partage » : c'est la
  frontière app/feature la plus étroite pour le FileProvider, dans
  l'esprit d'`openCrashReport` (étape 3).
- Le répertoire d'export `cache/exports/` devient un contrat partagé
  journaux/plantages (`file_paths.xml` du prompt) — la constante vit en
  double (`LoggingLimits`, `CrashLimits`) avec un commentaire croisé,
  les deux modules ne pouvant pas partager la leur.

## Alternatives rejetées

- **Tout recharger à chaque émission du tampon** : relecture disque à
  chaque filon de pain, absurde pour un outil de consultation.
- **`ViewPager2` recréant les fragments** : état de recherche/filtres
  perdu à chaque changement d'onglet — les `SavedStateHandle` des
  ViewModels par page couvrent déjà la rotation et la mort de processus.
- **Partage par autorité FileProvider recalculée dans la fonctionnalité**
  (`packageName + ".fileprovider"`) : couplage par convention non
  vérifiable au build, violation de la séparation app/feature.
