# ADR 0039 — Protocole du tooling Gradle : framing, catalogue doré et rejet avant allocation

- **Statut** : accepté (étape 25 = Tooling G1, v0.26.0)
- **Contexte** : prompt compagnon « Tooling Gradle (client-serveur) »,
  sections 3 et 9.1 ; prélude exigé par le prompt : **aucune ligne** de
  `tooling:server`/`tooling:client` avant que les tests du protocole
  ne soient verts.

## Décision

1. **Framing inchangé dans le principe** (§3.1) : 4 octets de longueur
   gros-boutiste + payload JSON. `FrameCodec` reste agnostique du
   contenu (`ByteArray`) — un format binaire partiel restera possible
   sans toucher au framing.
2. **Rejet avant allocation** : une longueur annoncée au-delà de
   `MAX_FRAME_SIZE` (16 Mo) lève `FrameTropGrandeException` **sans lire
   ni allouer** — c'est la garde DoS mémoire du prompt, éprouvée par un
   test dédié (un flux annonçant 16 Mo + 1 est rejeté avant que le
   démon ne touche aux octets suivants).
3. **Trois échecs de framing typés, EOF distingué** :
   `FrameTropGrandeException` (garde DoS), `FrameTronqueeException`
   (en-tête ou payload coupé — le message dit exactement combien
   d'octets étaient arrivés), `FrameInvalideException` (longueur nulle
   ou négative). Une fin de flux **dès le premier octet** propage
   l'`EOFException` telle quelle : c'est une déconnexion propre, pas
   une corruption — le dispatcher (§4.5) compte sur cette distinction.
4. **Catalogue complet des 24 messages** (§3.2) : 9 requêtes, 15
   événements, `ErrorResponse` à `ErrorCode` **typé** (9 codes), enums
   `StreamKind`/`DiagnosticSeverity` sérialisées par `@SerialName`.
   Les champs des résultats (`TaskInfo`, `DependencyInfo`,
   `PartialSyncResult`) restent volontairement simples : les modèles
   riches vivront dans `tooling:api` (G2), le protocole ne transporte
   que du sérialisable.
5. **Fichiers dorés = format câble figé** : un JSON canonique par
   message, commis dans `src/test/resources/golden/`. Deux tests les
   gardent : décodage (golden → instance attendue) et adéquation
   sémantique (encodage ↔ golden, comparaison `JsonElement` — l'ordre
   des champs et la mise en forme ne comptent pas, le contenu oui).
   Renommer, retirer ou changer le type d'un champ **fait échouer le
   build** : le format est un contrat entre deux process de versions
   différentes.
6. **Compatibilité ascendante éprouvée** (§3.3) : `ignoreUnknownKeys`
   + un test qui décode un golden enrichi d'un champ du futur
   (`champDuFutur`) — un client v2.1 dialogue avec un serveur v2.0.
7. **Fixtures jamais construites en place** : les quatre mini-projets
   Gradle (minimal, erreur de compilation, multi-module, tâche longue)
   vivent en ressources de `tooling:testing` et se **copient** vers un
   répertoire temporaire (`FixturesGradle.copier`) — un build y écrirait
   des artéfacts et corromprait la ressource pour les tests suivants.
   La tâche longue est bornée par défaut (10 s) : un test en échec se
   termine seul.
8. **Règles de dépendance gelées dès G1** (`ModuleRulesPlugin`) :
   `tooling:protocol` sans dépendance interne, `tooling:api` →
   protocol, `server` → protocol+api, `client` → protocol+api+domain,
   `daemon` → protocol+domain+client, `tooling:testing` consommable
   uniquement en configuration de test (même statut que `core:testing`).
   `tooling:protocol`/`api`/`server` sont des modules Kotlin JVM purs.

## Alternatives rejetées

- **Un `DataOutputStream.writeInt` pour l'en-tête** : gros-boutiste par
  coïncidence — la version octet-par-octete explicite documente le
  contrat ET évite le piège `OutputStream.write(Int)` qui n'écrit que
  l'octet de poids faible (piège attrapé par les tests du round-trip,
  voir leçon AGENTS.md).
- **Golden files générés à l'exécution** : ils ne garderaient rien —
  commis et confrontés à l'encodage, ils détectent la dérive.
- **Comparaison d'octets avec la sortie de l'encodeur** : couplerait le
  contrat à la mise en forme/à l'ordre des champs de kotlinx — la
  comparaison sémantique (`JsonElement`) cible le contenu.
- **Protocole binaire partiel dès G1** : prématuré — le framing le
  permet, le volume actuel de messages ne le justifie pas (§1.5).

## Conséquences

- Ajout d'un commentaire de détection `/*` imbriqué dans les KDoc (le
  joker `golden/*.json`) — la leçon des commentaires imbriqués Kotlin
  rejoint celle du `*/` prématuré dans AGENTS.md.
- `tooling:protocol` n'a aucune dépendance Android : il est testable et
  exécutable sur poste et CI sans émulateur (§2.1) — consommé tel quel
  par le futur process JVM.
- Le secret de handshake voyage dans `HelloRequest` mais sa
  vérification est du ressort de G3 (`GradleSocketServer`) — le
  protocole ne fait que le transporter.
