# ADR 0226 — Typealias du classpath : exposer les alias publics par l'index (rejeu §4)

- **Statut** : appliqué (27/10/2026 — rejeu du non-concluant §4 du rapport
  `docs/RAPPORT-FAUX-POSITIFS-2026-10-26.md`, mission fiabilisation ; le
  correctif est verrouillé par sa reproduction E2E sur corpus réel — TDD
  maison : la reproduction PRÉCÈDE la correction, ses assertions décrivent
  le comportement de kotlinc).
- **Contexte** : l'étape A laissait le `typealias` de bibliothèques NON
  CONCLUANT — « aucun typealias public JVM du stdlib n'est isolable par la
  sonde (l'écart de noms métadonnées les mélange aux propriétés). À rejouer
  en étape B sur un corpus kotlinx ». Le rejeu (sonde
  `SondeTypealiasKotlinxTest` sur corpus réels Maven Central) a établi :
  1. **kotlinx-serialization-core 1.9.0 (corpus cité par le rapport) porte
     ZÉRO typealias public** — l'hypothèse n'y est PAS éprouvable ;
  2. **kotlinx-datetime 0.6.1 porte TROIS alias publics** (`DayOfWeek` =
     java.time.DayOfWeek, `Month` = java.time.Month, `ZoneOffset` =
     FixedOffsetTimeZone — cible DANS le corpus) : c'est le corpus du rejeu ;
  3. **FP CONFIRMÉ sur DEUX formes d'usage** : le type QUALIFIÉ complet
     (`kotlinx.datetime.Month` — la garde E4 ADR 0216 rend le DERNIER
     segment faillible quand le FQCN ne vit pas dans la vue, et un alias
     n'y vit JAMAIS : aucun miroir bytecode) et le type NU derrière un
     import JOKER (`import kotlinx.datetime.*` + `ZoneOffset` — la voie 5
     joker sonde `typesDansPaquet`, classes seules ; la voie 6 sonde
     l'index des appelables, alias ignorés par le décodeur) ;
  4. **Non-FP PAR HASARD sur la moitié du corpus** : `Month`/`DayOfWeek`
     sont des expect classes à actual typealias — le compilateur génère
     des FONCTIONS USINE top-level (`fun Month(number: Int)`) que la voie
     6 résout derrière un joker ; `ZoneOffset` (alias pur, aucune usine)
     restait rouge. La correction ne peut pas s'appuyer sur ces usines :
     elles ne couvrent QUE leurs propres expect classes.
  5. L'import EXACT reste muet par la voie 5 (`importsParNom` rend le
     FQCN ÉCRIT sans sondage — comportement inchangé, aucun correctif requis).
- **Options considérées** :
  1. (a) Émettre les typealiases publics depuis le décodeur, dans une
     carte DÉDIÉE de l'index, sondée par la voie 6 et les gardes E4/E4-ter
     (retenue) ; (b) exposer les alias par la VUE (contrat hôte, une
     méthode « typealiases du paquet ») — rejetée : la vue est le miroir
     de l'index disque des CLASSES (ADR 0111), un alias n'y a pas sa
     place et l'ajouter toucherait l'API SPI de l'hôte pour un besoin
     moteur ; (c) réputation des alias connus côté moteur (miroir
     `APPELABLES_INTRINSEQUES`) — rejetée : les alias sont INFINIS en
     pratique (chaque bibliothèque en déclare), contrairement aux
     intrinsèques du compilateur qui sont un ensemble FERMÉ ;
     (d) suivre la CIBLE de l'alias dans la résolution de membres — hors
     périmètre : les usages cassés sont des NOMS et des TYPES, pas des
     membres ; le suivr de cible est un GAIN survol/complétion (sous-rapport
     accepté), jamais un faux positif.
  2. Représentation : (a) réutiliser `AppelableKotlin` avec un genre
     `ALIAS` (retenue — le vecteur porte nom/paquet/conteneur/type rendu,
     et le format de segment l'accueille SANS nouveau champ) ;
     (b) classe dédiée `AliasKotlin` — rejetée : un troisième type à
     sérialiser dans les segments et à dupliquer dans les snapshots pour
     UN usage (la preuve d'existence).
  3. Segments persistés : (a) BUMP `VERSION` 1 → 2 (retenue — rejet des
     segments v1, re-décodage à froid : auto-guérison ADR 0111 §4, miroir
     `FormatSegment` v2 / ADR 0219) ; (b) garder la version et tolérer les
     segments sans alias — rejetée : un projet aux segments v1 resterait
     SOURD aux alias alors même que le correctif est livré — l'index est
     un CACHE, jamais une autorité.
- **Décision** :
  1. **`DecodeurMetadonnees`** émet `kmPackage.typeAliases` PUBLICS
     (`visibility == PUBLIC`, miroir `estIndexable`) comme entrées de
     genre `ALIAS` : `recepteur = null` (un typealias n'est pas une
     extension), `typeRetour` = type CIBLE rendu (`ZoneOffset` →
     `FixedOffsetTimeZone` — le survol peut l'afficher), `parametres`
     vides.
  2. **`GenreAppelable.ALIAS`** ajouté en FIN d'énum (ordinal 2) : les
     ordinaux FONCTION/PROPRIETE sont inchangés, la relecture des segments
     v1 restait valide — le bump de version reste requis pour le CONTENU
     (point 3 des options).
  3. **`IndexAppelablesKotlin`** sépare les alias dans une carte DÉDIÉE
     du snapshot (`aliasParNom`) : `parNom`/`extensions`/`nomsTries`
     restent des APPELABLES purs — complétion, survol et signatures
     d'appel ne voient JAMAIS un alias (la complétion de TYPES est servie
     par la vue, un alias n'y apparaît pas : sous-rapport accepté).
     Nouvelles requêtes : `aliasTopParNom(nom)` (voie 6) et
     `estTypeAliasConnu(fqcn)` (gardes E4/E4-ter : le FQCN complet
     désigne-t-il un alias public ?). Segments en VERSION 2.
  4. **`ResolutionReferences.estResolu`** (voie 6) : après les appelables,
     sondage des alias avec le MÊME prédicat de portée ADR 0203
     (`appelableEnPortee` : paquet courant, import exact, joker, paquet
     par défaut) — un alias en portée prouve la résolution du nom.
  5. **`DiagnosticsSemantiques`** : la garde E4 (`estSegmentTypeQualifie`)
     et la garde E4-ter (`estSegmentExpressionQualifiee`) sondent
     `estTypeAliasConnu(nomComplet)` APRÈS la vue et le projet : le FQCN
     complet d'un alias public est un accès qualifié LÉGAL sans import,
     TOUS les segments sont muets — miroir exact de la classe réelle
     (`membresDe(nomComplet) != null`).
- **Preuves** :
  - `SondeTypealiasKotlinxTest` (2) : le fait du corpus est VERROUILLÉ
    (kotlinx-datetime porte les 3 alias attendus, leurs CIBLES recoupées ;
    kotlinx-serialization-core porte ZÉRO alias public — verdict du corpus
    cité par le rapport).
  - `ReproductionTypealiasClasspathTest` (5, corpus réel kotlinx-datetime
    0.6.1, vue factice FIDÈLE — classes top-level du jar, jamais les
    alias) : AVANT correctif, `« Month »`+`« ZoneOffset »` rouges en
    QUALIFIÉ et `« ZoneOffset »` rouge en JOKER (impressions du rapport
    d'exécution) ; APRÈS : import exact muet, qualifié complet muet,
    joker muet ; CONTRÔLES : classe réelle du corpus muette aux DEUX
    formes (`DateTimePeriod`), le vrai négatif `Inexistant` reste SIGNALÉ
    en qualifié (D3 : le dernier segment d'un FQCN inconnu n'est jamais
    éteint).
  - `IndexAppelablesKotlinTest` (9) : la carte DÉDIÉE est verrouillée —
    `aliasTopParNom("ZoneOffset")` en genre ALIAS, `topParNom` et
    `nomsTopParPrefixe` ne rendent JAMAIS les alias,
    `estTypeAliasConnu` exact sur les 3 FQCN du corpus + négatifs
    (inconnu, nom sans paquet).
  - Chaîne `:lsp:kotlin` + `:lsp:index` (test, detekt, spotlessCheck,
    koverVerify) VERTE — 409 tests `:lsp:kotlin` (7 nouveaux), 0
    régression ; baseline de parité re-synchronisée (le corpus EST le
    dépôt).
  - Angle mort assumé : la complétion ne propose PAS les alias (carte
    dédiée) et la résolution de MEMBRES ne suit PAS la cible
    (`ZoneOffset.UTC` reste sous-rapporté si l'usage passe par un membre
    statique) — sous-rapport D3, jamais de rouge mensonger.
