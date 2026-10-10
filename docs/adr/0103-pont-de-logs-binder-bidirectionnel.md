# ADR 0103 — Pont de logs de l'app exécutée : Binder bidirectionnel, zéro permission ajoutée

- Statut : accepté (2026-10-10) — phase R0 de la mission « Exécuter »
  (décisions posées ; l'implémentation est la phase R2)
- Contexte : voir les logs de l'app **lancée** depuis l'IDE, sans adb.
  Étude mesurée des deux références (dépôts GPL — concepts retenus,
  **code intégralement réécrit** : CodeIDE n'est pas sous GPL) :

| Critère | AndroidIDE | projet de référence (mesuré) |
|---|---|---|
| Transport | AIDL (contrôle) + socket TCP localhost | Binder `oneway` pur, lots de trames |
| Permissions ajoutées à l'app cible | INTERNET, FOREGROUND_SERVICE, permission propre | **aucune** |
| Service dans l'app cible | service de premier plan | **aucun** (ContentProvider amorce seul) |
| Protection du point d'entrée | permission `normal` (falsifiable) | service exporté sans permission, filtre par paquet |
| Sources | `logcat -v threadtime` seul | logcat du PID + tee System.out/err + exceptions non interceptées |
| Injection | plugin Gradle (classes **internes** AGP) | réécriture du manifeste (moteur maison) |
| Contrôle IDE→app | oui (ping, startReader, disconnect) | non |
| Contre-pression | aucune explicite | file 4096 drop-oldest, lots ≤ 256, flush 100 ms, anneau 5000 |
| Lignes perdues comptées | non | non (perte silencieuse) |

## Décision — le meilleur des deux, amélioré

1. **Binder seulement, mais bidirectionnel.** Un service exporté côté
   IDE ; à la connexion, l'app **confie son propre binder de
   contrôle** (idée AndroidIDE), puis envoie des **lots de trames
   structurées par Binder `oneway`** (idée projet de référence). Résultat :
   **aucune permission ajoutée à l'app cible** (ni INTERNET, ni
   FOREGROUND_SERVICE, ni permission propre), **aucun service de
   premier plan**, aucun port local ouvert (LocalSocket est de toute
   façon refusé par SELinux entre apps non privilégiées — constat
   projet de référence).
2. **Authenticité réelle par UID.** L'IDE compare
   `Binder.getCallingUid()` à l'UID du paquet qu'il vient d'installer
   et lancer (`PackageManager.getPackageUid`), et rejette tout le
   reste — la permission `normal` d'AndroidIDE est auto-accordée à
   toute app, l'identité déclarée dans la trame HELLO y est donc
   falsifiable ; l'UID du noyau, lui, ne l'est pas. Défense en
   profondeur côté app : le pont vérifie
   `ApplicationInfo.FLAG_DEBUGGABLE` à l'exécution et ne fait **rien**
   hors debug (si la bibliothèque fuyait dans un build release).
3. **Fin de processus détectée immédiatement** : `linkToDeath` **des
   deux côtés** (l'IDE sait aussitôt que l'app est morte ; l'app sait
   que l'IDE a disparu et se met en veille) — remplace tout ping
   périodique.
4. **Capture plus complète** : logcat du PID (repli par filtrage pid si
   `--pid` indisponible — une app non privilégiée ne lit que son propre
   UID), tee de `System.out`/`System.err` avec dé-doublonnage contre le
   miroir logcat, gestionnaire d'exceptions non interceptées (trace
   transmise **puis** délégation au gestionnaire précédent — l'app
   plante normalement), et **`ApplicationExitInfo`** (API 30+) lu au
   démarrage suivant du pont : raison de la mort du processus
   précédent (plantage natif avec trace via `getTraceInputStream`, ANR,
   manque mémoire, arrêt demandé) — **ce qu'aucune des deux références
   ne montre**.
5. **Résilience visible** : anneau borné dans l'app (vidé à la
   connexion — les lignes émises pendant que l'IDE était en arrière-plan
   ne sont pas perdues), **compteur de lignes perdues affiché** en cas
   de débordement (projet de référence jette en silence), lots sous la limite
   de taille des transactions Binder (~1 Mo mesuré côté projet de référence),
   limitation de débit, troncature des messages gigantesques.
6. **Multi-processus.** Le `ContentProvider` d'amorçage s'exécute dans
   **chaque processus** de l'app cible : un pont par processus, clé de
   session **(paquet, pid, nom de processus)**, sélecteur de processus
   dans l'interface (AndroidIDE : un émetteur par paquet — le second
   processus déconnecte le premier ; projet de référence : une seule session).
7. **Injection propre.** Script d'initialisation Gradle (`-I`) géré par
   `tooling/server`, appliquant un petit plugin Kotlin qui n'utilise
   **que des API publiques** : la variante déboguable est repérée par
   l'API publique `androidComponents.beforeVariants`, et la
   dépendance d'exécution est ajoutée par la configuration
   **`<variante>RuntimeClasspath`** du projet (API Gradle publique —
   **jamais** de conversion vers `ApplicationVariantImpl`, la casse
   mesurée chez AndroidIDE, compilé contre AGP minimum 7.3.3). La
   bibliothèque est **livrée avec CodeIDE** (dépôt local `maven`
   généré à l'installation, `--offline`) — jamais téléchargée, jamais
   dans les sources de l'utilisateur, fichiers du projet **jamais
   modifiés**. Interrupteur : propriété Gradle
   `codeide.applog.isEnabled` (défaut : activé) + réglage utilisateur
   documenté (« ajoute une bibliothèque de débogage à vos builds
   debug »). `<queries>` (paquet de l'IDE) et règles de consommation
   R8 pour les builds debug minifiés inclus dans la bibliothèque.
8. **Économie.** L'IDE ne se lie à l'app que **pendant qu'une app du
   projet tourne** ; aucun thread de polling côté app (émissions
   poussées par lots).
9. **Historique des sessions** entre deux redémarrages de CodeIDE :
   borné, stockage privé, **exclu de tout partage** sauf export
   explicite avec avertissement (les logs peuvent contenir des données
   sensibles).
10. **Réduction à la source** (optionnel) : le binder de contrôle
    permet à l'IDE de demander au pont de relever le niveau minimal ou
    de se mettre en pause.

### Limites assumées — affichées dans l'interface

Sans adb : logs de **l'app du projet, en debug**, **depuis la création
de son processus** (rien avant le provider d'amorçage) ; ni les logs
système, ni ceux d'autres apps (cela exigerait `READ_LOGS`, accordée
seulement par adb). Pas de vrai « Arrêter » : on ne peut que **cesser
d'écouter** — l'onglet Logcat le dit tel quel. Ces limites sont des
textes d'interface, pas des notes de bas de page de doc.

## Conséquences

- Aucune permission ajoutée à l'app de l'utilisateur ; aucune donnée
  de logs ne sort de l'appareil ; le contenu reçu est **non fiable**
  (analyseur défensif, trames bornées).
- Bibliothèque `applog-runtime` : module Android **Java pur, sans
  dépendance externe**, jamais active hors debug — code original.
- Analyse des trames **pure et testée en JVM** (format tabulaire à
  message-reste, inspiré de la mesure projet de référence, réécrit) ; transport
  isolé derrière un **port dans `core:domain`** pour que l'interface et
  les tests n'en dépendent pas.
- Phases : R2 (bibliothèque + service IDE + injection), R3 (onglet
  Logcat — façon Android Studio), R4 (traces cliquables), R5
  (finitions). Aucune de ces phases n'ajoute de dépendance externe.
