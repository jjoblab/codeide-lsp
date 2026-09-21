# ADR 0003 — Storage Access Framework plutôt que java.io.File

- **Statut** : accepté (étape 0 — imposé par le prompt maître, sections 2 et 5.6)
- **Contexte** : les projets de l'utilisateur vivent dans un dossier qu'il
  choisit lui-même. Sur Android moderne, le stockage est cloisonné : écrire
  des `java.io.File` hors du stockage privé exige des permissions larges
  (`MANAGE_EXTERNAL_STORAGE`) que le prompt interdit, ou n'est tout simplement
  pas possible (carte SD, stockages secondaires, dossiers protégés).
- **Décision** : tout accès aux fichiers des projets passe par le **Storage
  Access Framework** — des URI de documents, des permissions persistantes
  (`takePersistableUriPermission`) et l'implémentation `SafFileSystem` du
  domaine, construite sur `DocumentsContract` et des requêtes groupées
  (jamais de boucle sur `DocumentFile`). Le modèle `StorageLocation`
  distingue l'URI qui détient la permission du dossier cible. Les projets
  créés dans le dossier de travail héritent de son accès (URI enfant), sans
  nouvelle permission.
- **Conséquences** :
  - aucune permission de stockage sensible ; les dossiers refusés par
    Android 11+ (racine, `Download`, `Android/data`…) sont détectés et
    expliqués ;
  - l'accès est **plus lent** qu'en `File` : compensé par des requêtes
    groupées et une interface `FileSystem` testable avec un fake mémoire ;
  - conséquence assumée pour l'avenir (terminal, compilation) : exécuter du
    code pourra exiger de **copier** des fichiers vers le stockage interne —
    le point d'ancrage `FileSystem` abstrait rend cette évolution possible
    sans refonte ;
  - le bit exécutable ne peut pas être positionné via SAF : la documentation
    des projets générés le prend en compte (`sh gradlew` / `chmod +x`).
