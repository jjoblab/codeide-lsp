# ADR 0077 — Wizard enrichi : sections Android, aperçu renommable et dépendances (phase 4 du roadmap)

- Statut : accepté (2026-10-01)
- Contexte : phase 4 du roadmap (`docs/ROADMAP.md`) — 4.1 sections Android
  (minSdk, targetSdk, applicationId), 4.2 aperçu de structure modifiable,
  4.3 choix de dépendances communes

## Contexte

La phase 3 a fait grandir le catalogue (7 modèles, variantes Android,
Java, `.aar`, plugin Gradle — ADR 0076) mais le wizard n'a pas suivi :
`minSdk` n'offre qu'une valeur, `targetSdk` (28 en dur) et
`applicationId` (confondu avec le package) ne se choisissent pas,
l'aperçu de l'arborescence est en lecture seule et aucune dépendance
commune ne se coche. Le roadmap demande un wizard à la hauteur des
modèles.

## Décisions

### 1. Sections Android : `minSdk` élargi, `targetSdk` et `applicationId`

`minSdk` passe à quatre valeurs (24, 26, 29, 34 — défaut 26 inchangé) ;
le plancher 24 vient de `androidx.navigation` 2.10.2 (manifest merger,
constat empirique). `targetSdk` devient un `CHOICE` (34–37, défaut 37 =
compileSdk) et `applicationId` un `TEXT` `INFORMATION` dérivé par la
sixième fonction `defaultFrom` **`applicationIdFromPackageName`** — la
chaîne `appName → packageName → applicationId` suit ses sources jusqu'à
saisie manuelle, puis devient libre (resynchronisable). `android-library`
aligne son `minSdk` (24–34) ; pas d'`applicationId` pour une bibliothèque.

**`targetSdk = 37` par défaut ne contredit pas l'ADR 0045** : le 28
délibéré de CodeIDE protège les binaires du bootstrap extraits dans
`filesDir` (W^X) — c'est une contrainte de l'**app hôte**, pas des
**projets générés**, applications ordinaires qui visent le Play Store
(cible 35+ exigée pour publier en 2026).

### 2. Aperçu renommable : renommages par identité originale

L'invariant de l'ADR 0017 (« ce qui est planifié est ce qui est écrit »)
est **préservé** : les renommages ne touchent pas le plan après coup,
ils entrent dans la requête. `CreateProjectRequest.cheminsRenommes`
(clé = chemin original du nœud — fichier exact ou préfixe de dossier —,
valeur = **nouveau nom du segment**, jamais un chemin complet) traverse
`RequeteGeneration.renommages` ; le moteur substitue chaque segment dont
le préfixe est une clé (les préfixes se lisent sur les segments
**originaux**, les renommages de dossier et de fichier se composent),
puis revalide chaque chemin final par la garde existante et le contrôle
des doublons. `PlannedFile.cheminOriginal` (renseigné seulement quand un
renommage s'applique) est l'**identité stable** du nœud : renommer une
seconde fois cible toujours l'original, l'arbre du récapitulatif
(`Arborescence.depuisPlan`) place les nœuds par chemin final en gardant
les identités — structure identique par construction (un renommage ne
change qu'un segment).

Gardes en trois couches : le dialogue valide localement (vide,
séparateur, parent, doublon de frère), le ViewModel ignore les cibles
`.codeide/` (métadonnées intouchables), le moteur **échoue
explicitement** (métadonnées, nom multi-segments, doublon après
renommage). Un renommage rejeté par le domaine est **annulé** et le plan
restauré — l'erreur s'affiche, « Réessayer » replanifie avec l'ancienne
carte : jamais d'impasse. Les clés devenues obsolètes (arborescence
changée en revenant en arrière) sont ignorées, exactement comme les
valeurs de paramètres obsolètes. Les renommages survivent à la mort du
processus (`SavedStateHandle`, listes parallèles) et sont emportés par
la création — le harnais `:tools:generateur` les accepte aussi
(`kt-app-renoms` : README.md → NOTES.md, build + exécution vérifiés).

UX : un crayon par ligne de l'arbre (`MaterialAlertDialog` + champ
unique, prérempli), masqué pour `.codeide/` ; le dialogue ne se referme
que sur un nom valide.

### 3. Dépendances communes : un interrupteur par bibliothèque

Pas de nouveau type de paramètre moteur : chaque dépendance est un
`BOOLEAN` (défaut faux, section `CONFIGURATION`) rendu par l'interrupteur
existant — le câblage vit dans les modèles (`{{#if}}` dans le build, le
catalogue `libs.versions.toml`, le manifeste) et le wizard suit
gratuitement (rendu dynamique, `visibleWhen`/`defaultFrom`/`validator`
déjà éprouvés par les tests du moteur).

- **Android** (v1.3.0) : Coroutines 1.11.0, Retrofit 3.0.0 (+Gson),
  Navigation 2.10.2, Room 2.8.5, Hilt 2.59.2 — les trois fichiers
  d'exemple (`BddLocale` avec entité/DAO/base, `{{appName|resourceName}}
  Application` Hilt relié au manifeste, miroirs `.java` en **trois
  fichiers** : Java exige une classe publique par fichier et le code KSP
  généré expose ces types).
- **Spring Boot** (v1.2.0) : JPA (+H2 `runtimeOnly`), Security,
  Actuator, Validation — alias de catalogue **sans version** (la BOM
  4.1.1 gère), note de sécurité dans le README (endpoints 401 par défaut).
- **KMP** (v1.2.0) : serialization 1.11.0 (+plugin Kotlin 2.2.21),
  coroutines 1.11.0, datetime 0.8.0 — un fichier d'usage par dépendance
  (les imports restent indépendants), `analyserInstant` porte
  `@OptIn(kotlin.time.ExperimentalTime)` : `Instant` est ponté vers
  l'API stdlib expérimentale (Kotlin 2.2.21).

### 4. KSP 2.3.12 avec le Kotlin intégré d'AGP

Room et Hilt exigent un processeur d'annotations. Constats empiriques
(sondes sur projet généré) : KSP 2.2.21-2.0.5 **refuse** le Kotlin intégré
d'AGP (« KSP is not compatible with Android Gradle Plugin's built-in
Kotlin ») ; la voie de contournement officielle (`android.builtInKotlin=
false` + `kotlin("android")` externe) est **infranchissable** sous AGP
9.4.1 — le KGP du classpath se heurte à `BaseExtension` supprimé, et un
KGP versionné est refusé (déjà présent sans version). **KSP 2.3.12**
(nouvelle ligne de versionnement sans préfixe Kotlin) accepte le Kotlin
intégré : `BddLocale_Impl` et l'arbre de composants Hilt sont générés,
dexés dans l'APK, tests verts — en Kotlin **et** en Java. Le plugin
`ksp` du catalogue n'est déclaré que si Room ou Hilt est coché
(`avecKsp = avecRoom || avecHilt`, expression `||` du moteur).

## Conséquences

- Les renommages sont limités au **segment** (pas de déplacement
  inter-dossiers) — le UX du dialogue y gagne en simplicité, le moteur
  garde ses invariants de sécurité ; renommer un fichier de code peut
  désynchroniser nom de fichier et classe (liberté de l'utilisateur, le
  domaine ne réécrit jamais le contenu).
- `.codeide/` est intouchable au renommage (le fichier de suivi du
  projet ne doit pas disparaître) ; la licence, elle, suit les
  renommages comme n'importe quel fichier.
- Les versions des dépendances sont figées dans les catalogues et
  vérifiées par `scripts/verify-templates.sh` — **37 combinaisons**
  (32 + 5 phase 4 : `andr-deps`, `andr-deps-java`, `sb-deps`,
  `kmp-deps`, `kt-app-renoms`), toutes avec build réel, tests et
  exécution ; `kotlinx-datetime` 0.8.0 impose l'opt-in, les artefacts
  `-compat` ont été écartés (échafaudage transitoire).
- `TemplateDefaultFunctionsTest` ancre désormais **six** fonctions ;
  `ModelesPhase4Test` (10 tests) éprouve sections, dépendances et
  renommages sur les vrais assets embarqués.
