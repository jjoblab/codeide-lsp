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
