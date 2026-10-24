# Réponses aux demandes de `DEMANDES_CODEIDE_LSP.md` — codeide-lsp v1.8.0

> **Note de réponse** (agent du dépôt `codeide-lsp`, 24/10/2026) : les
> cinq demandes du chantier indexation (CodeIDE v0.178.0, ADR 0145)
> sont LIVRÉES dans **codeide-lsp 1.8.0** (ADR 0211–0214, 1 237 tests
> verts dont 71 scénarios E2E transport réel qui empruntent le nouveau
> flux de bout en bout). Le dépôt CodeIDE étant interdit au push côté
> agent, la table des statats ci-dessous est PRÊTE À COLLER dans
> `docs/DEMANDES_CODEIDE_LSP.md`.

## Table des statuts (à reporter dans le journal des demandes)

| # | Demande | Statut | Livré dans |
|---|---------|--------|-----------|
| D1 | Supplier de connexion sans nourrissage lourd (Kotlin + XML) | ✅ livré (24/10/2026) | codeide-lsp **1.8.0**, ADR **0211** |
| D2 | Pas de scan synchrone sous verrou ; lectures parallélisables | ✅ livré (24/10/2026) | codeide-lsp **1.8.0**, ADR **0212** |
| D3 | `etatIndexation` Kotlin + XML (flux réel de progression) | ✅ livré (24/10/2026) | codeide-lsp **1.8.0**, ADR **0213** |
| D4 | `reesayerEchecs()` ciblé | ✅ livré (24/10/2026) | codeide-lsp **1.8.0**, ADR **0214** |
| D5 | `annulerPassage()` coopératif | ✅ livré (24/10/2026) | codeide-lsp **1.8.0**, ADR **0214** |

## D1 — ANR `.kt`/`.kts` : nourrissage moteur, supplier gratuit

- **D1-a** ✅ : `MoteurLangageKotlin.definirClasspath` (nouveau au SPI)
  nourrit l'index PARTAGÉ **UNE fois par sync** sur un fil de fond
  dédié ; `IndexAppelablesKotlin` retient les empreintes du snapshot
  (**garde** : mêmes empreintes dans l'ordre = no-op chaud) et garde un
  **cache mémoire par empreinte** (un segment lu ne retourne JAMAIS au
  disque). `creerSession()` + `definirClasspath` de session ne font que
  **brancher** (comparaison de 47 chaînes — microsecondes).
- **D1-b** ✅ (combiné) : pendant la passe en vol les requêtes
  SOUS-LISENT — les diagnostics `kt.unresolved` se taisent sur le
  classpath (impossible de prouver une absence ; recalcul à CHAQUE
  requête, donc la publication de la passe rend l'index à la requête
  suivante SANS nouvelle poussée), et les documents ouverts sont
  **REPUBLIÉS** quand la passe aboutit (le client reçoit la vérité dès
  qu'elle existe).
- **D1-c** ✅ : plus AUCUNE E/S dans le supplier — le branchement est
  structurellement gratuit ; un pack ancien (SPI par défaut `false`)
  retombe sur le monde d'avant, sans exception.
- **D1-d** ✅ : `MoteurLangageXml.definirClasspath` suit la même loi —
  scans `res/` + AAR sur fil dédié, **catalogue XE2 préchauffé** (son
  coût quitte la première frappe de diagnostic), sessions branchées
  sans re-balayage, échecs JAMAIS mis en cache mtime (la reprise les
  retente toujours).

## D2 — Verrou unique : filet supprimé, lectures parallèles

- **D2-a** ✅ : le filet synchrone est SUPPRIMÉ — `declarations()`
  JOINT le WARM en vol (attente bornée par le fil de fond lancé à la
  sync), sinon rend le snapshot courant et AMORCE (sous-lecture honnête
  d'UNE requête). Le drapeau `pret` distingue « un passage a ABOUTI »
  (même vide ou partiel) d'une invalidation — un projet sans `.kt` ne
  ré-amorce plus en boucle.
- **D2-b** ✅ (étudié ET livré) : la session Kotlin passe au verrou
  LECTURE-ÉCRITURE — diagnostics, complétion, survol et previews de
  documents DISTINCTS tournent EN PARALLÈLE ; seules
  `definirClasspath`/didOpen/didChange/didClose écrivent. L'audit de
  pureté a verrouillé les helpers (purs ou à état par appel) et
  synchronisé `ClassesSynthetiques` en interne (le seul point fauve).

## D3 — `etatIndexation` Kotlin + XML : même type public que Java

✅ `SessionLspKotlin.etatIndexation` et `SessionLspXml.etatIndexation` :
`StateFlow<EtatIndexation>` — le MÊME type public que
`SessionLsp.etatIndexation` (Java). Alimenté par les événements NEUTRES
des moteurs (types du SPI, **zéro kotlinx-coroutines dans les packs** —
observateur SAM + poignée `Closeable`, désinscrite à la fermeture) :
- phases détaillées **BIBLIOTHEQUES** (segments kidx X/N côté Kotlin ;
  catalogue XE2 + AAR P3 côté XML) puis **SOURCES** (fichiers du scan
  WARM X/N — marche d'abord, total CONNU ; racines `res/` côté XML) ;
- **PRET / PARTIEL / ERREUR** (PARTIEL honnête : entrées écartées ou
  passage annulé — jamais inventé) ;
- **liste de travail** par entrée (EN_ATTENTE → ACTIF →
  REPRIS/TERMINE/ECARTE), **breakdown** par indexeur, **durées par
  phase**, **bilan du dernier passage**, `settled`, `reprises` —
  parité TOTALE avec l'écran Java.
- L'agrégat CodeIDE branche le flux **sans autre changement côté
  hôte** : `sessionKotlin.etatIndexation`/`sessionXml.etatIndexation`
  remplacent les fenêtres synthétisées (`MoteurIndexationPresent(moteur,
  phase, etat)` peut porter l'état RICHE des trois langues).
- **Fenêtres de repli** pour les packs anciens : CONSTRUCTION au départ
  de sync, PRET après la poussée synchrone (le comportement d'avant).

## D4 — `reesayerEchecs()` : les deux boutons deviennent distincts

✅ Livré sur les TROIS moteurs — `false` quand il n'y a rien à
retenter (l'écran grille le bouton sans deviner) :
- **Kotlin/XML** : rejouer le passage ne re-paye QUE les échecs — les
  unités saines vivent en cache (REPRIS massifs, visibles à l'écran),
  les échecs n'y vivent JAMAIS (toujours retentés).
- **Java** : `ServiceIndexDisque.reesayerEchecs` ne relance QUE les
  positions ECARTÉES de la liste de travail, statistiques SEMÉES du
  passage précédent (les entrées saines gardent leur état, les
  messages des retentées partent, la phase finale est recalculée) —
  test prouve que la saine n'est PAS re-analysée.
- `reindexer()` reste l'action LOURDE (« tout revalider ») :
  « Réessayer les entrées échouées » est enfin l'action DOUCE.

## D5 — `annulerPassage()` : drapeau coopératif, PARTIEL annulé

✅ Livré sur les TROIS moteurs — `false` hors vol (l'écran retire
l'offre) :
- **Kotlin/XML** : drapeau testé ENTRE les unités (segments, fichiers
  du scan, AAR, racines) ; le passage publie son ACQUIS et se marque
  **PARTIEL « annulé par l'utilisateur »** (settled=false) ; la purge
  XML ne s'exécute PAS sur annulation (le dernier-connu-bon survit) ;
  la reprise D4 complète le passage.
- **Java** : la construction EST un `Job` annulable (points
  `ensureActive` existants) ; `invokeOnCompletion` publie PARTIEL
  « annulé par l'utilisateur » sans course de fin de coroutine.

## Compatibilité CodeIDE

- **Aucune casse** : toutes les APIs sont ADDITIVES (`etatIndexation`,
  `annulerPassage()`, `reesayerEchecs()` ; les SPI moteur ont des
  DÉFAUTS — un pack 1.7.0 face à un hôte 1.8.0 se comporte comme
  avant).
- **Branchement conseillé côté CodeIDE** (une fois la montée
  codeide-lsp 1.8.0 effectuée) : remplacer les fenêtres
  `fenetreIndexationKotlin/Xml` par `session?.etatIndexation` dans
  `EditorViewModel.etatsMoteursIndexation` (l'agrégat accepte déjà
  l'état riche), et brancher les deux boutons de la maquette sur
  `annulerPassage()` / `reesayerEchecs()` (retour `false` = offre
  retirée).
