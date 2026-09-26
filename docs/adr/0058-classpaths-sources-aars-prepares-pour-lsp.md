# ADR 0058 — Classpaths, sources et AARs préparés et persistés pour les LSP

- **Statut** : accepté (v0.33.1, retour utilisateur du 2026-09-26 sur la
  v0.33.0 — complément de l'étape 32)
- **Contexte** : retour utilisateur : « Tu dois faire comme Android
  Studio, préparer et sauvegarder les classpaths, sources etc… les AARs
  pour qu'à n'importe quel moment qu'ils sont prêts les LSP puissent s'en
  servir. » L'étape 32 (ADR 0057) faisait résoudre `IdeaProject` à la sync
  d'ouverture — le modèle qui PORTE les classpaths — mais le résultat
  était JETÉ : seuls les noms des modèles résolus remontaient au client.
  Rien n'était extrait, rien n'était sauvé : un LSP qui démarrait devait
  tout re-résoudre.

## Décisions

### 1. Requête `classpath_request` dédiée, servie par le même modèle

Nouveau message du protocole (`ClasspathRequest`/`ClasspathResult`,
26e et 27e messages — fichiers dorés inclus, le catalogue passe à
10 requêtes et 17 événements) : par module, le nom, les répertoires
SOURCES (source + tests, via `IdeaContentRoot`), et les ENTRÉES du
classpath compilé — jars, AARs (nature par extension), dossiers de
classes et modules frères portés par leur nom, avec le jar de SOURCES
attaché quand Gradle le connaît (`IdeaSingleFileLibraryDependency`) et
la portée (`compile`, `test`…). `ClasspathHandler` (serveur) résout
`IdeaProject` par la connexion du pool — la sync vient de le payer, la
requête est servie par le cache.

### 2. La préparation suit la sync UTILE, dans le parcours d'ouverture

`EditorViewModel.synchroniserProjetGradle` — donc la sync d'ouverture
ADR 0057 comme le geste manuel — appelle `PreparerClasspathLspUseCase`
dès que la sync a résolu quelque chose (réussie ou partielle : on
prépare ce qui se résout, comme un IDE indexe ce qu'il peut). Échec sec :
rien à préparer. La préparation ne parle JAMAIS dans le canal Sync ni
dans l'UI : c'est un travail de fond, seul le journal en témoigne —
jamais bloquant pour l'édition.

### 3. Persistance `.codeide/local/lsp-classpath.json`

Le cas d'usage PERSISTE la réponse sous
`.codeide/local/lsp-classpath.json` de la racine du projet (même
emplacement non synchronisé que `workspace-state.json`, étape 17 ;
les `.gitignore` générés excluent déjà `.codeide/local/`) : répertoire
créé au besoin (dossier importé), ré-écriture sans doublon, schéma
versionné (`schema: 1`) pour la tolérance ascendante. `LireClasspathLspUseCase`
est la lecture tolérante (absent, illisible ou corrompu → `null`) : le
point d'entrée des LSP à venir, qui consomment le classpath PRÉPARÉ sans
solliciter l'orchestrateur — à n'importe quel moment une fois l'index
prêt.

### 4. Domaine enrichi, frontières intactes

`GradleToolingRepository` gagne `classpath(projectDir)` ; les modèles
domaine (`ClasspathProjet`, `ModuleClasspath`, `EntreeClasspath`,
`TypeEntreeClasspath`) sont `@Serializable` (le format persisté EST le
modèle domaine — pas de DTO double) et sans type tooling (règle §2.2,
même précédent que `EtatEspace`). Délais de garde alignés sur la sync
(5 min serveur et client). Les faux des tests suivent.

## Conséquences

- Le protocole compte 27 messages (10 requêtes, 17 événements) : les
  fichiers dorés et le catalogue des échantillons suivent — tout
  renommage y est vu.
- AARs : le fichier `.aar` est livré TEL QUEL (nature `aar`) —
  l'explosion du bytecode interne (classes.jar) reste le périmètre du
  LSP qui le consomme, comme l'AGP le fait côté Android Studio.
- `feature:editor` voit son test de faux étendu (`nbClasspaths`) ;
  la limite JVM documentée (dossier SAF irrésolvable en test) garde le
  parcours positif couvert par `ServeurIntegrationTest` (VRAI Gradle
  multi-module : sources livrées, module frère `:lib` dans le classpath
  de `:app`), `GradleApiImplTest` (traduction domaine) et
  `ClasspathLspUseCasesTest` (round-trip de la persistance).
- Correctif livré avec : les 15 chaînes tooling de l'étape 32 absentes
  de `values-en` (15 erreurs `MissingTranslation` qui cassaient
  `lintDebug` en CI) — parité FR/EN rétablie sur les 13 modules de
  ressources.
