# ADR 0212 — Le serveur Kotlin ne bloque plus : filet de scan supprimé, verrou lecture-écriture

- **Statut** : appliqué (24/10/2026 — demande D2 de
  `CodeIDE/docs/DEMANDES_CODEIDE_LSP.md` ; moteur Kotlin + tests de
  concurrence).
- **Contexte** : `SessionLangageKotlinParse` sérialisait TOUT
  (diagnostics, complétion, survol, previews, didOpen/didChange) sous UN
  `synchronized(verrou)` ; or `diagnostics()` → `declarationsProjet()` →
  `ScanProjet.declarations()` → `scannerAuBesoin()` dont le FILET
  historique pouvait scanner TOUT le projet SOUS le verrou (si le WARM
  n'était pas en vol et le snapshot vide — `ScanProjet.kt` l.236-250 au
  moment de la demande). Pendant ce temps, CHAQUE requête LSP Kotlin
  attendait, y compris didOpen. La demande D2-a : « le filet synchrone ne
  doit JAMAIS scanner sous verrou : joindre le WARM en vol, sinon rendre
  le snapshot VIDE et laisser le WARM publier » ; D2-b : « étudier un
  `ReadWriteLock` — les lectures sont majoritaires et parallélisables ;
  seules `definirClasspath`/didChange écrivent ».
- **Options considérées** :
  1. **Supprimer le filet + drapeau « prêt » + `ReentrantReadWriteLock`**
     (retenue) — `scannerAuBesoin` devient `assurerSnapshot` : joindre le
     WARM en vol (attente BORNÉE par le fil de fond déjà lancé à la sync,
     jamais le fil appelant qui scannerait), sinon si un passage A ABOUTI
     pour les racines courantes (`pret`, y compris un résultat VIDE — un
     projet sans `.kt` est un état légitime, pas une invalidation), sinon
     AMORCER le WARM et rendre le snapshot courant (sous-lecture honnête
     d'UNE requête, le WARM publie pour les suivantes). La session passe
     au verrou lecture-écriture : didOpen/didChange/didClose/
     `definirClasspath`/close écrivent, toutes les requêtes lisent EN
     PARALLÈLE.
  2. **Garder le filet mais hors verrou** — sortir `declarations()` du
     bloc synchronisé : expose la paire (documents, scan) à une course
     didOpen concomitant (le document ouvert DOIT dominer le scan au
     moment de la lecture), subtile et non testable, rejeté.
  3. **Filet avec délai d'essai** — scanner synchroniquement « seulement
     si le WARM tarde » : réintroduit exactement le blocage dénoncé, borné
     seulement par une horloge, rejeté.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - **`pret` distingue « passage abouti » de « snapshot vide »** : sans
    lui, un projet sans `.kt` (ou un scan annulé) ré-amorcerait un WARM à
    CHAQUE requête ; avec lui, l'invalidation (nouvelles racines,
    `rafraichir`, annulation ADR 0214) abaisse `pret` et la prochaine
    requête ré-amorce — une requête précoce répond honnêtement moins
    riche, jamais en bloquant tout le serveur (le texte EXACT de la
    demande).
  - **Marche d'abord, parse ensuite** dans `ScanProjet.scanner` : le
    listage complet des racines précède le parse fichier par fichier —
    l'ordre des déclarations est inchangé (racines dans l'ordre, noms
    triés), mais la progression devient un VRAI X/N (total connu, ADR
    0213) au lieu d'un compte aveugle.
  - **Audit de pureté avant le verrou partagé** : les helpers de requête
    (`CompletionKotlin`, `InterrogationsSymboles`, `OccurrencesKotlin`,
    `SuperKotlin`, `FormateurKotlin`, `PreviewsDocumentKotlin`,
    `DocumentationKotlin`) sont pures ou portent leur état par APPEL
    (`FormateurKotlin.Analyse`, `EtatReconstruction` du parseur sont des
    classes privées créées À CHAQUE appel) ; `IndexAppelablesKotlin`
    publie un snapshot volatil immuable ; `ScanProjet` et
    `CataloguesPartages` synchronisent en interne. LE point fauve était
    `ClassesSynthetiques` (cache paresseux `if (!calcule)` non
    synchronisé, MUTABLE sous lectures parallèles) : il gagne une
    synchronisation interne dédiée (le calcul reste paresseux, une seule
    construction à la fois, les lecteurs parallèles attendent au pire UNE
    construction).
  - **Pas de réentrance lecture→écriture** : aucune requête ne mute
    l'état de session (les ports KDoc/Super ADR 0208/0210 ne font que
    LIRE des documents ouverts et du disque) ; le verrou est
    `ReentrantReadWriteLock` standard sans tentative d'upgrade.
  - **Borne documentée** : joindre un WARM en vol reste une ATTENTE (le
    fil de fond travaille, l'appelant ne travaille pas) — la fenêtre se
    réduit au temps restant d'un scan lancé À LA SYNC ; l'annulation
    coopérative ADR 0214 la raccourcit encore (le scan s'arrête entre
    deux fichiers).
- **Conséquences** :
  - Plus AUCUN chemin de requête ne peut parser le projet sur le fil
    appelant ; un diagnostic précoce sous-lit (symboles projet seuls) au
    lieu de geler l'éditeur — miroir exact du repli « requêtes sous-lisent
    tant que la construction n'est pas prête » de la vue fusion Java.
  - Deux documents différents se diagnosent, se complètent et se survolent
    EN PARALLÈLE (le pool LSP4J de l'hôte l'exploite immédiatement) ;
    didOpen n'attend plus derrière le diagnostic d'un autre onglet.
  - `declarationsProjet` garde la dominance des documents OUVERTS (arbres
    vivants) : la parallélisation ne change AUCUNE sémantique de preuve,
    seulement l'ordonnancement — verrouillé par les tests existants plus
    les nouveaux tests de concurrence (diagnostics parallèles sans
   _course, synthétiques sous pluie de requêtes).
