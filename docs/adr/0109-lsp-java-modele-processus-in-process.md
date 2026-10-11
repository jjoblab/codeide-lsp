# ADR 0109 — D2 : moteur LSP Java in-process (ART), transport abstrait

- **Statut** : accepté par défaut, à confirmer par mesure comparative sur
  appareil (v0.91.0, étape 0 — prompt 2 §3 D2)
- **Contexte** : deux modèles possibles pour exécuter ecj sur l'appareil :
  **in-process** (comme projet de référence et l'ancienne version : latence
  minimale, tas partagé) ou **sous-processus JDK** (comme le démon de
  tooling : HotSpot, isolation, arrêt franc). Le banc JVM de l'étape 0
  (docs/LSP_JAVA.md §4) mesure 131 Mo de tas stable pour 250 jars et des
  latences de complétion de 0–140 ms selon le contexte — sur JVM de
  bureau ; ART est typiquement 3–5× plus lent sur ces chemins.

## Décision

1. **In-process (ART) par défaut**, comme l'ancienne version et
   projet de référence : zéro coût IPC pour chaque frappe, démarrage instantané,
   pas de dépendance au JDK du bootstrap pour ÉDITER.
2. **Transport abstrait obligatoire** : l'hôte (`lsp:host`) isolera le
   moteur derrière une interface (`SessionLsp`) aux frontières nettes
   (démarrer/arrêter, requêtes, flux d'état). Le branchement LSP4J de
   `lspjava` (déjà in-process, `InProcessStreamConnectionProvider` côté
   ancienne app) en est UNE implémentation ; un transport par socket
   vers un sous-processus JDK reste possible sans réécriture du moteur.
3. **La comparaison sera tranchée par mesure sur appareil** (étape 3 ou
   5) : latence de complétion p95, mémoire pointe, démarrage à froid.
   Critère de bascule : si la complétion membre p95 dépasse la cible
   (§9 du prompt) de façon reproductible ET imputable au runtime ART
   (GC, JIT), le sous-processus devient l'option par défaut.
4. **Garde-fous in-process** : tas de l'index borné par construction
   (ADR 0111 : index disque + cache de blocs), surveillance mémoire
   existante du démon tooling comme modèle, arrêt propre exhaustif
   (aucune ressource orpheline — lié au prompt 3).

## Alternatives écartées (pour l'instant)

- **Sous-processus JDK d'emblée** : surcoût mémoire (un JDK complet
  résident en plus de l'app), démarrage, dépendance au bootstrap pour
  la simple édition ; l'ancienne version a démontré qu'in-process
  fonctionne pour les diagnostics. À garder comme plan B mesuré.
- **Mixte par module** : complexité de cycle de vie sans bénéfice
  démontré. Écarté.

## Conséquences

- `lsp:host` est le point unique de changement de transport.
- La mémoire est le risque principal in-process : l'objectif « tas
  imputable à l'index < 30 Mo pour 100 jars » devient une contrainte de
  conception de `lsp:index` (ADR 0111), pas un réglage a posteriori.
