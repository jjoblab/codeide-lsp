# ADR 0009 — Pipeline de journalisation asynchrone et borné

- **Statut** : accepté (étape 2)
- **Contexte** : la section 5.7 du prompt maître impose une journalisation
  maison dont l'appelant n'est **jamais bloqué** (StrictMode ne doit rien
  détecter sur le thread principal), une écriture disque groupée, un vidage
  synchrone borné réservé au futur gestionnaire de plantages, et une perte
  assumée sous surcharge. Plusieurs choix d'implémentation méritaient d'être
  consignés.

## Décision

1. **Canal borné `DROP_OLDEST` unique** entre le moteur et le sink fichier.
   Un `trySend` sur un canal saturé ne bloque ni ne suspend jamais : il
   remplace l'entrée la plus ancienne. La capacité (512) est fixée dans
   `LoggingLimits` avec les autres bornes du prompt.

2. **Boucle de consommation économe** : en rafale, le consommateur retire
   les entrées par `tryReceive` (sans machinery de coroutine) et ne
   recourt à `receive` + fenêtre temporisée (`withTimeoutOrNull`) que
   lorsque le canal est vide. Mesuré sur la machine de build (2 cœurs,
   quota CPU partagé) : un `withTimeoutOrNull` par entrée plafonnait le
   débit du consommateur à ~13 000 entrées/s contre ~50 000 au producteur —
   le canal saturait et DROP_OLDEST perdait ~60 % des entrées ; le
   vidage par `tryReceive` supprime ce goulot.

3. **Écritures groupées par lot, un seul flux ouvert** : les entrées de la
   fenêtre de 500 ms (ou déclenchées par une erreur `ERROR`, ou par un
   ordre de vidage) s'écrivent en une seule passe ; le `FileOutputStream`
   n'est rouvert qu'aux rotations. Une ouverture par ligne transformerait
   chaque lot en rafale de descripteurs.

4. **Vidage bloquant par verrou de coordination, pas de `runBlocking`** :
   `flushBlocking(timeoutMs)` envoie un ordre dans la file — donc **après**
   tout ce qui précède — et attend l'accusé via `CountDownLatch` borné.
   Interdiction du `runBlocking` respectée (règle 4 du prompt) ; si le
   consommateur est mort, l'attente expire proprement.

5. **Un seul propriétaire du disque** : `JsonlLogStore` verrouille toutes
   les opérations (écriture, lecture, effacement, rétention) ; le dépôt et
   le sink y passent, jamais directement par les fichiers. Les lignes
   corrompues (écriture interrompue d'un lancement précédent) sont
   comptées (`skippedLines`, `lastReadError`), jamais silencieusement
   avalées.

6. **Expurgation à l'écriture, idempotente** : `LogRedactor` (domaine,
   fonctions pures) s'applique aux messages et aux messages d'exception
   dès la construction de l'entrée ; le reste de la chaîne (fichiers,
   export, future visionneuse) ne manipule que du texte déjà propre. La
   ré-expurgation d'une URI déjà hachée la reconnaît et la conserve.

7. **Seuil logcat par variante installée** : `DEBUG+` en build debug,
   `WARN+` en release — détecté par `FLAG_DEBUGGABLE` (suit la variante
   réellement installée, pas une constante de compilation). La
   configuration initiale du niveau minimal suit la même détection, en
   attendant `AppSettings.logLevel` (étape 4) qui mettra à jour le
   détenteur à chaud.

8. **Export comme couture du domaine** : `LogExportWriter` est une
   interface du domaine ; l'implémentation (zip `codeide-logs-<date>.zip`
   en UTC dans `cache/exports/`, + `device-info.txt`, nettoyage des 5
   plus récents) vit dans `core:logging`. Les résumés de rapports de
   plantage rejoindront l'archive à l'étape 3 sans changer le contrat.

## Conséquences

- Sous surcharge soutenue, des entrées sont perdues **par design** ; le
  test de stress vérifie l'intégrité (aucune corruption, ordre FIFO par
  producteur, aucun échec d'écriture) et non un taux de livraison, qui
  dépend des ressources de la machine.
- Le vidage bloquant n'est sûr que hors du thread principal ; son usage
  restera réservé au gestionnaire de plantages (étape 3) et aux tests.
- Le seuil logcat et la configuration initiale vivront dans `core:logging`
  jusqu'au branchement des paramètres utilisateur (étapes 4-6).
