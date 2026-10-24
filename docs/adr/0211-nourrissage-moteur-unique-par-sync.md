# ADR 0211 — Nourrissage moteur UNE fois par sync, supplier de connexion sans E/S

- **Statut** : appliqué (24/10/2026 — demande D1 de
  `CodeIDE/docs/DEMANDES_CODEIDE_LSP.md`, chantier indexation v0.178.0 ;
  moteur Kotlin + moteur XML + façades hôte + tests).
- **Contexte** : le supplier de connexion cel-lsp
  (`InProcessStreamConnectionProvider.start()`) tourne sur le fil qui appelle
  `LspEditor.connect()` — dans CodeIDE, potentiellement le fil PRINCIPAL.
  `SessionLspKotlin.serveur()` y exécutait SYNCHRONÉMENT
  `moteur.creerSession()` puis `sessionLangage.definirClasspath(classpathCourant)`
  → `IndexAppelablesKotlin.definir` : lecture de CHAQUE segment
  `kotlin-appelables/*.kidx` du classpath (47 entrées sur le projet témoin) ou
  DÉCODAGE À FROID kotlin-metadata du jar ENTIER au premier passage
  (≥ 10 s mesuré sur appareil d'entrée de gamme — correctif v0.116.0
  « ANR e7dd5a7 »), PLUS la reconstruction des maps d'appelables. Chaque
  changement d'onglet `.kt` re-payait TOUT (chaque connexion crée une session
  NEUVE). Même motif côté XML (`SessionLspXml.serveur()` →
  `SessionLangageXmlImpl.definirClasspath` : re-balayage `res/` + contrôle
  mtime de chaque AAR par connexion — légers à chaud mais re-payés, plus le
  catalogue XE2 chargé paresseusement à la PREMIÈRE frappe de diagnostic).
- **Preuve que le partage existait déjà À MOITIÉ** : `MoteurKotlinVendore`
  possède déjà UN `IndexAppelablesKotlin` et UN `ScanProjet` partagés entre
  sessions (une session les reçoit par constructeur) ; `MoteurXmlMaison`
  partage déjà `CataloguesPartages` et `IndexRessources`. Ce qui manquait :
  (1) la DÉFINITION était DÉCLENCHÉE par session (donc par connexion) au lieu
  d'être déclenchée par SYNC au niveau moteur ; (2) `IndexAppelablesKotlin.
  definir` relisait TOUS les segments à chaque appel, même à empreintes
  identiques ; (3) rien ne permettait à une session de se BRANCHER à bon
  marché sur un snapshot déjà construit.
- **Options considérées** :
  1. **Passe moteur asynchrone + garde d'empreintes + branchement de session**
     (retenue) — le moteur expose `MoteurLangageKotlin.definirClasspath
     (classpath): Boolean` (façade = une fois par sync, retour `false` =
     pack ancien qui ne la surcharge pas) ; la passe tourne sur un fil démon
     DÉDIÉ du moteur, publie un snapshot IMMABLE, et la session se branche
     par `definirClasspath` qui devient QUASI GRATUIT quand le snapshot
     couvre déjà les empreintes. `IndexAppelablesKotlin` gagne (a) une garde
     « mêmes empreintes dans l'ordre ⇒ no-op chaud », (b) un CACHE MÉMOIRE
     par empreinte (un segment lu une fois ne retourne JAMAIS au disque,
     même après invalidation partielle), (c) `couvre(entrees)` et
     `nourrissageEnVol` consultés par la session.
  2. **Paresseux pur sans garde** — laisser chaque session re-définir mais
     compter sur le cache disque : re-payait la lecture des 47 segments à
     CHAQUE connexion (le mal dénoncé par la demande), rejeté.
  3. **Snapshot par chaîne de classpath côté façade** — déplacer l'état dans
     la façade hôte : casserait la frontière SPI D6 (le pack langue
     téléchargeable ne doit vivre que par `MoteurLangageKotlin`), rejeté.
- **Décision** : retenir l'**option 1**. Détails décisifs :
  - **Kotlin** : `MoteurKotlinVendore.definirClasspath` nourrit l'index
    partagé sur le fil dédié (progression/annulation/reprise = ADR 0213/
    0214) puis amorce le scan WARM des racines ; `SessionLangageKotlinParse.
    definirClasspath` garde son contrat synchrone POUR LES USAGES DIRECTS
    (tests, packs anciens) mais devient un BRANCHEMENT quand le snapshot
    couvre (`couvre`) ou quand une passe moteur est en vol
    (`nourrissageEnVol`) : il pose classpath/vue/racines, ne RE-LIT RIEN.
    La façade `SessionLspKotlin.appliquerModele` appelle
    `moteur.definirClasspath` UNE fois par sync ; `serveur()` ne paie plus
    que le branchement (comparaison de 47 chaînes — microsecondes).
  - **Sous-lecture honnête** (le « sous-lisent tant que la définition n'est
    pas prête » de la demande D1-b) : pendant la passe en vol, les
    diagnostics SÉMANTIQUES reçoivent `index = null` (index non couvert ⇒
    AUCUN faux `kt.unresolved` — silence conservateur, miroir de la garde
    « index froid = silence » du LSP XML) ; la complétion/survol sous-rapportent
    simplement les candidats du classpath et se soignent à la frappe
    suivante. Le drapeau est recalculé À CHAQUE requête (`indexSiPret()` —
    jamais d'état périmé après publication).
  - **Packs anciens** : `definirClasspath` retourne `false` par défaut dans
    le SPI ⇒ la façade retombe sur le monde d'avant (poussée synchrone aux
    sessions vivantes, fenêtres CONSTRUCTION→PRET de la façade) ; le
    comportement d'un pack K3 face à un hôte neuf est donc STRICTEMENT
    inchangé, la tolérance de version est préservée dans les DEUX sens.
  - **XML** (annexe D1-d, même loi) : `MoteurXmlMaison.definirClasspath`
    nourrit `IndexRessources` (racines `res/` + AAR) sur un fil dédié et
    PRÉCHAUFFE le catalogue XE2 de la plateforme (la lecture `data/*.xml`
    quitte la première frappe de diagnostic) ; `IndexRessources` gagne
    `couvre(racines, aars)` + la mémoire du dernier passage ; la session
    se branche sans re-balayage quand c'est couvert ou en vol.
  - **Bornes honnêtes** : la garde d'empreintes compare la LISTE ordonnée
    (l'ordre du classpath fait la préséance des candidats) ; le cache
    mémoire par empreinte est BORNÉ (au-delà de 256 empreintes il est
    vidangé — une sync qui enchaîne des classpaths massivement différents
    paie au plus une relecture complète) ; un changement de `dossierIndex`
    n'invalide rien (l'empreinte EST l'identité du contenu, où que le
    segment dorme).
- **Conséquences** :
  - La première ouverture `.kt` post-sync ne paie plus le nourrissage sur
    le fil de connexion : l'ANR disparaît structurellement (D1-c), la
    latence d'ouverture devient celle du branchement seul ; chaque
    changement d'onglet devient un no-op chaud (D1-a).
  - Le coût lourd migre sur le chemin de SYNC (une fois, fil de fond du
    moteur) — exactement la loi demandée ; l'écran d'indexation de CodeIDE
    le rend observable (ADR 0213).
  - Un pack K3 antérieur garde l'ancien comportement (dégradation
    documentée, jamais une exception) ; les tests directs de
    `SessionLangageKotlin.definirClasspath` restent SYNCHRONES et verts
    (contrat inchangé hors couverture).
