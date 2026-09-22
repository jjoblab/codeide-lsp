# ADR 0010 — Partage des archives de plantage depuis le processus `:crash`

- **Statut** : accepté (étape 3)
- **Contexte** : `CrashActivity` vit dans un processus séparé `:crash`
  (section 5.8, ADR 0006) — le processus principal est **mort** au moment
  où l'utilisateur consulte l'écran de plantage. L'action « Partager
  (archive) » doit pourtant produire une URI `content://` partageable via
  `FileProvider`. De plus, le manifeste fusionné porte déjà un
  `FileProvider` pour les exports de journaux (section 5.7) : deux
  fournisseurs ne peuvent pas partager le même `android:name` sans
  conflit de fusion.

## Décision

1. **Un FileProvider dédié au processus `:crash`** :
   `jo.codeide.core.crash.CrashFileProvider` — sous-classe triviale de
   `androidx.core.content.FileProvider`, déclarée dans le manifeste de
   `core:crash` avec `android:process=":crash"` et l'autorité
   `${applicationId}.crashprovider`. La sous-classe n'est pas cosmétique :
   le fusionneur de manifestes distingue les nœuds par `android:name`, et
   deux `<provider>` de la même classe (journaux ici, plantages là) ne
   peuvent coexister sans ce nom dédié.

2. **Portée minimale** : `res/xml/crash_file_paths.xml` n'expose que
   `cache/crash_exports/` — jamais le répertoire des rapports
   (`filesDir/crashes/`), jamais le cache entier. Une URI forgée hors de
   l'écran dédié ne mène nulle part.

3. **Résurrection impossible** : le fournisseur s'exécute dans le
   processus `:crash` déjà vivant — partager une archive ne relance jamais
   le processus principal (ni son `FileSink`, ni Room, ni DataStore),
   conformément à l'initialisation minimale de `CodeIdeApplication` dans
   ce processus.

4. **Écriture juste avant le partage** : l'archive
   (`codeide-crash-<id>.zip` : `report.json`, `rapport.txt`,
   `device-info.txt`) vit en mémoire puis dans `cache/crash_exports/` au
   moment du geste utilisateur — jamais à l'avance (le cache peut être
   vidé à tout moment, et un rapport se partage rarement).

## Conséquences

- Le code de partage de `CrashActivity` ne connaît que son autorité
  (`packageName + ".crashprovider"`) : aucune dépendance au module
  `core:logging` (section 5.2) — les deux FileProviders vivent leur vie.
- L'enregistrement SAF (`ACTION_CREATE_DOCUMENT`) écrit directement vers
  l'URI choisie : il ne transite pas par le fournisseur.
- Un repli texte est prévu si le fournisseur est introuvable (le partage
  doit toujours réussir, même dégradé).
