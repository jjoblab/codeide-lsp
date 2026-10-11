# ADR 0111 — D4 : index disque en segments PAR JAR, format binaire versionné

- **Statut** : accepté (v0.91.0, étape 0 — prompt 2 §3 D4 et §7.2)
- **Contexte** : l'index actuel du module vendored est purement mémoire
  (W1 confirmé par mesures : reconstruction intégrale à chaque
  remplacement de contexte — 98/219/794 ms pour 20/73/250 jars même
  JVM ; +1 dépendance = 257/396/1178 ms tout repayé). projet de référence
  persiste des segments par *(extension, classpath entier)* : ajouter
  UNE dépendance reconstruit tout le classpath.

## Décision

1. **Un segment PAR JAR**, clé = **identité du contenu** :
   - privilégier l'identité DÉJÀ dérivable des chemins du cache Gradle
     (`modules-2/files-2.1/<groupe>/<artefact>/<version>/<sha1>` et
     `transforms-*`) — le sha1 du fichier est dans le chemin ;
   - sinon (jars hors cache : AAR extraits, `R.jar`, `android.jar`)
     fallback *(taille, horodatage, empreinte partielle)* des N· premiers
     Ko, avec constante de format documentée.
2. **Stockage partagé entre projets** dans un répertoire de cache unique
   (pas dans le projet) : `~/.codeide/lsp/index/` (emplacement à aligner
   sur les répertoires existants à l'étape 2). Segments mutualisés entre
   modules et entre projets.
3. **Fusion à la requête** (principe LSM) : le classpath d'un module =
   l'union ordonnée de ses segments ; aucun « gros index » reconstruit.
   Ajouter une dépendance n'indexe QUE le nouveau jar (cible §9).
4. **Format binaire versionné** : en-tête magique + version de format ;
   migration = ré-indexation paresseuse (les segments vieux format sont
   reconstruits, jamais fatals). Corruption d'un segment ⇒ reconstruction
   silencieuse de CE segment, jamais de crash (tolérance W-cibles).
5. **Extensions nommées** (catalogue ouvert, départ) :
   `java.classLocator` (FQCN → jar+entrée), `java.classNames`
   (préfixe/approximation sur noms simples), `java.packages`,
   `java.packageTypes` (sous-paquets DIRECTS uniquement — règle « ni
   plus ni moins » de la suite de tests), `java.members`,
   `java.membersByOwner` (imports statiques), symboles de sources et
   Javadoc de sources (§7.3 de l'étude pour l'incrémental).
6. **Lecture** : fichier mappé ou lecture par blocs avec **cache de
   blocs borné** (budget tas explicite, cible < 30 Mo pour 100 jars) ;
   **aucun `java.util.zip.ZipFile`** (conservation de `RawZipReader`
   ou équivalent — CloseGuard, ADR de l'ancienne version).
7. **Construction en arrière-plan** : priorité basse, parallélisme
   `min(4, cœurs/2)`, annulable, reprise, **progression par jar**,
   jar illisible ignoré et SIGNALÉ (index partiel honnête).
8. **États** : `{construction, prêt, partiel, erreur}` en flux ; index
   non prêt ⇒ **repli par sondage** (le LSP fonctionne sans index) ;
   index prêt ⇒ localisateur **autoritatif** (négatifs sans sondage).
   Les caches de localisation en mémoire sont VIDÉS à la transition
   « prêt » (un négatif obtenu pendant la fenêtre périmée doit se
   guérir — P7/P8 du prompt).
9. **Nettoyage** : segments orphelins (aucune référence depuis un
   classpath connu depuis N jours) supprimés ; budget disque global.
10. **AAR et dossiers** : les AAR sont NORMALISÉS (extraction
    `classes.jar` + `libs/*.jar`) au moment de l'indexation — le serveur
    actuel les livre tels quels (ADR 0058, écart §2 de l'étude). Les
    entrées `DOSSIER` (sorties de build) : segment invalidé par
    signature d'arborescence ; **préséance des sources du module sur sa
    propre sortie compilée**.

## Alternatives écartées

- **Clé par classpath entier** (modèle projet de référence) : ajouter une
  dépendance reconstruit tout — précisément ce que les mesures W1
  condamnent. Écartée (c'est l'amélioration demandée par le prompt).
- **Base de données embarquée** (SQLite/…) : dépendance lourde sur ART,
  GC de disque opaque ; le format binaire dédié reste simple et
  borné. Écartée.
- **Index JSON/texte** : taille et parse coûteux pour des dizaines de
  milliers d'entrées (23 327 noms simples pour le seul jeu « lourd » du
  banc). Écartée.

## Conséquences

- `lsp:index` (Kotlin, ADR 0108) naît avec ce contrat ; l'étape 2
  livre le format, les tests d'aller-retour, corruption, concurrence et
  migration, plus le banc avant/après.
- Les empreintes de classpath (W6) vivent dans `lsp:model` (étape 1) :
  l'empreinte d'un module = la combinaison ordonnée des clés de ses
  segments.

## Mise en œuvre (v0.93.0 — étape 2, livrée)

Le module `:lsp:index` implémente ce contrat (détail : docs/LSP_JAVA.md
§14). Points arrêtés pendant l'implémentation :

1. **Emplacement du stockage partagé** : `~/.codeide/lsp/index/segments/`
   en JVM (l'emplacement est INJECTÉ par le constructeur — l'hôte
   Android de l'étape 4 fournira le répertoire applicatif, jamais un
   chemin deviné).
2. **Format** : « CIDX », gros-boutien, sections numérotées (catalogue
   ouvert — `java.sources` de §7.3 restera pour l'étape 3), membres
   décodés PARESSEUSEMENT (le bloc reste brut — un gros segment ne
   déclenche pas des centaines de milliers d'objets), offsets de membres
   décalés de 1 (la sentinelle 0 = absent ne peut pas confondre le
   premier bloc réel).
3. **Reprise rapide** : l'en-tête SEUL est validé à la re-ouverture
   (magie, version, clé, stats W7 — pas le crc) : 250 segments se
   valident en 25 ms (cible tenue avec 60× de marge) ; la corruption
   est attrapée à la lecture complète et AUTO-GUÉRIE (reconstruction de
   CE segment, anti-rebond, refus mémorisé entre-temps).
4. **Lecture bornée** : cache LRU par segment DÉCODÉ (octets comptés,
   budget 30 Mo) — le compromis mesuré au jeu lourd : les négatifs en
   éviction rechargent des segments (p50 124 ms) ; à l'état PRÊT le
   consommateur cesse de sonder et les négatifs deviennent gratuits.
5. **Le lourd du banc** (recette versionnée) : 250 artefacts du cache
   Gradle (49 % d'AAR — la normalisation §10 est exercée) ; la
   résolution d'un consommateur JVM d'un projet Compose bute sur
   l'ambiguïté de variantes `jvm`/`androidJvm` des artefacts Compose —
   le balayage déterministe du cache est retenu (équivalent en volume,
   documenté dans LSP_JAVA.md §14.3).
6. **ZIP petit-boutien** : les signatures et entiers du format ZIP se
   lisent en PETIT-boutien (APPNOTE §4.1.1) — le `LecteurZipBrut` Kotlin
   implémente les deux boutismes explicitement (`OctetsIndex`), le
   format CIDX reste gros-boutien (`DataOutputStream`).

Résultats du banc (§14.2) : reprise 7/10/25 ms ; +1 dépendance
85/89/87 ms (vs 257/396/1 178 ms tout repayé avant) ; cache borné
4,2/2,9/29,7 Mo.
