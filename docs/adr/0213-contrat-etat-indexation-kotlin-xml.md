# ADR 0213 — Contrat de progression d'indexation Kotlin + XML (`etatIndexation`)

- **Statut** : appliqué (24/10/2026 — demande D3 de
  `CodeIDE/docs/DEMANDES_CODEIDE_LSP.md`, écran d'indexation MULTI-MOTEURS
  de CodeIDE v0.178.0, ADR 0145 côté CodeIDE ; moteurs + façades + tests).
- **Contexte** : CodeIDE v0.178.0 livre un écran d'indexation
  MULTI-MOTEURS (Java + Kotlin + XML). Java expose déjà
  `SessionLsp.etatIndexation: StateFlow<EtatIndexation>` (RICHE : phases,
  faits/total, entrée courante, liste de travail, breakdown par indexeur,
  durées par phase, bilan du dernier passage, `settled`, `reprises`).
  Kotlin et XML n'exposaient RIEN : CodeIDE synthétisait un état honnête
  mais grossier depuis ses fenêtres de nourrissage (CONSTRUCTION
  indéterminée → PRET, jamais de PARTIEL inventé). La demande :
  `SessionLspKotlin.etatIndexation` et `SessionLspXml.etatIndexation` du
  MÊME type public que Java, alimentés par les moteurs — « brancher le
  flux remplacera la synthèse sans autre changement côté hôte ».
- **Options considérées** :
  1. **Observateur moteur à types NEUTRES + traduction façade** (retenue)
     — les packs langue restent SANS dépendance kotlinx-coroutines
     (condition pack téléchargeable D6, `:lsp:kotlin` est une entité
     indépendante) : le moteur publie des `EvenementIndexationKotlin`
     (data classes du SPI) à des `ObservateurIndexationKotlin` enregistrés
     par `MoteurLangageKotlin.enregistrerObservateurIndexation` (poignée
     `Closeable` de désinscription) ; la FAÇADE (`:lsp:host`, qui a les
     coroutines) traduit l'événement en `EtatIndexation` public et le
     publie dans son `StateFlow`.
  2. **`Flow` natif côté moteur** — `MoteurLangageKotlin.etatIndexation:
     Flow<...>` : imposerait kotlinx-coroutines au SPI du pack ET à tout
     pack tiers futur (DexClassLoader), pour un bénéfice nul (l'hôte
     n'a besoin que de l'état courant + les mises à jour, un observateur
     les donne), rejeté.
  3. **Sondage façade** (`poll()` du moteur) — progression par
     interrogation : perd les transitions fines (entrée ACTIF, annulation
     instantanée), réintroduit une horloge côté hôte, rejeté.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - **Types neutres miroir** (`jo.lspkotlin.api`, `jo.lspxml.api`) :
    `PhaseIndexation*` (INITIAL/CONSTRUCTION/PRET/PARTIEL/ERREUR — ERREUR
    est réservé par stabilité de « format », le moteur Kotlin/XML ne
    l'émet pas), `PhaseDetailleeIndexation*` (AUCUNE/BIBLIOTHEQUES/
    SOURCES), `EtatEntreeTravail*` (EN_ATTENTE/ACTIF/TERMINE/REPRIS/
    ECARTE), `EntreeTravail*`, `BreakdownIndexeur*`, `DureesPhases*`,
    `BilanDernierPassage*`, `EvenementIndexation*` (tout l'état, un
    objet), `ObservateurIndexation*` (SAM interface). Les façades
    traduisent 1:1 vers les types publics `lsp:host` (même patron de
    découplage que `SessionLsp.traduireEtatIndex`).
  - **Séquence d'événements Kotlin** (passe ADR 0211) :
    CONSTRUCTION/BIBLIOTHEQUES au fil des segments kidx (chaque entrée
    EN_ATTENTE → ACTIF → REPRIS (segment disque) / TERMINE (décodage à
    froid) / ECARTE (illisible), `entreeCourante`, faits/total) ;
    CONSTRUCTION/SOURCES au fil du scan WARM (faits/total PAR FICHIER,
    marche d'abord ADR 0212 — total connu ; les FICHIERS n'entrent pas
    dans la liste de travail, ils seraient du bruit à l'écran, miroir
    Java qui n'y liste que les jars) ; PRET/PARTIEL final (PARTIEL si
    avertissements — segments écartés ou passage annulé ADR 0214) ;
    `settled`/`reprises`/`dureesPhases`/`bilanDernierPassage` paritaires
    avec Java ; breakdown à DEUX indexeurs (`kidx-segments`, `scan-warm`).
  - **Séquence XML** : BIBLIOTHEQUES au fil des AAR (décode mtime ;
    REPRIS pour un conteneur inchangé), SOURCES au fil des racines
    `res/` du projet, catalogue XE2 PRÉCHAUFFÉ dans la passe (son coût
    quitte la première frappe, il est compté dans la phase
    bibliothèques) ; PRET/PARTIEL miroir.
  - **Fenêtres de repli pour packs anciens** : `SessionLspKotlin/
    SessionLspXml.appliquerModele` posent un CONSTRUCTION indéterminé
    (faits=0, total=0) au départ de sync ; si `moteur.definirClasspath`
    a retourné `false` (pack ancien — l'API ADR 0211 n'existe pas chez
    lui), la fenêtre referme elle-même : PRET après la poussée
    synchrone aux sessions vivantes (le comportement d'avant, sans
    PARTIEL jamais inventé) ; si `true`, les événements moteur reprennent
    la main dès le premier tick. Un pack K3 face à un hôte neuf affiche
    donc EXACTEMENT ce qu'il affichait avant cette ADR.
  - **Fil de publication** : l'observateur est appelé depuis le fil de
    fond du moteur ; la façade ne fait que `MutableStateFlow.value =`
    (atomique, tout fil) — aucune coroutine lancée, la fermeture de la
    façade DÉSINSCRIT l'observateur (poignée `Closeable`, un observateur
    mort ne reçoit plus rien).
- **Conséquences** :
  - `SessionLspKotlin.etatIndexation` et `SessionLspXml.etatIndexation`
    sont des `StateFlow<EtatIndexation>` du MÊME type que Java :
    l'agrégat CodeIDE (`MoteurIndexationPresent(moteur, phase, etat)`)
    branche le flux SANS autre changement côté hôte — la demande D3 est
    satisfaite mot pour mot, la synthèse par fenêtres peut être retirée
    par CodeIDE.
  - L'écran gagne pour Kotlin/XML : phases réelles (dont PARTIEL honnête),
    progression déterminée par entrée, liste de travail, breakdown,
    durées et bilan — la parité delangue de l'écran d'indexation est
    totale sur les trois moteurs.
  - Le coût d'observation est nul sans écran (les événements ne partent
    que vers les observateurs enregistrés ; zéro inscription, zéro
    allocation de liste de travail).
