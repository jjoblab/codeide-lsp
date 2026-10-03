# ADR 0081 — Lecture par fil dédié : le réveil appartient au noyau, pas au scheduler

- Statut : accepté (2026-10-04)
- Contexte : retour utilisateur v0.49.0 — « je constate une amélioration » :
  plus aucun « orchestrateur muet », aucun kill 143, aucune relance, écarts
  de transport passés de 19-24 s minimum (jusqu'à 28 minutes) à 1-14 s
  maximum. Mais la latence de transport reste marquée « ÉLEVÉE » (résumés
  reçus 2 s et 19 s après la fin des builds), et l'étendue des écarts d'un
  même build (12 955 ms pour un build de 2 370 ms) prouve que les lignes
  s'égouttent frame par frame au lieu d'affluer.

## Contexte

La v0.49.0 (ADR 0080) avait sorti la lecture du goulot « canal plein » et
le pong de tout ensevelissement — le terrain le confirme (zéro muet, zéro
kill). Mais la boucle de lecture restait une COROUTINE :

```kotlin
flow {
    while (true) {
        val payload = withContext(Dispatchers.IO) { readFrame(...) }  // hop 1
        emit(decoder(payload))                                        // hop 2
    }
}
```

Deux changements de contexte de scheduler PAR FRAME. La voie
`Dispatchers.IO.limitedParallelism(3)` du collecteur est une LIMITE de
concurrence, pas une RÉSERVE de fils : ses fils viennent du pool partagé
(64 au plus) — le même pool que le scanning classpath LSP, les diagnostics
post-sync et le surlignage de l'app. Pendant les builds, ce travail charge
le scheduler et le CPU : chaque hop coûte de 0,1 à 2 s, et la sortie
s'égoutte — 16 lignes étalées sur 13-20 s. Les indices convergents du
terrain v0.49.0 :

1. **L'étendue des écarts dépasse la durée du build** (12 955 ms pour
   2 370 ms) : les frames sont reçues bien plus lentement qu'elles
   n'arrivent — elles dorment dans le tampon UDS pendant que la coroutine
   de lecture attend son tour de scheduler.
2. **Le pong passait toujours** (aucun muet alors que les écarts atteignent
   14 037 ms, juste sous les 15 000 du watchdog) : le ping/pong ne traverse
   qu'un à deux hops par cycle de 5 s — il subit le même scheduler mais
   cent fois moins souvent. Le lecteur, lui, paie deux hops PAR FRAME.
3. **Côté orchestrateur, tout était déjà immédiat** : file d'événements à
   0-1 en fin de build, aucun avertissement de profondeur, tampon UDS
   (~200 Ko) jamais rempli par 16 lignes et leurs ticks — le fil écrivain
   v0.49.0 écrivait tout au fil de l'eau. Le retard vit intégralement dans
   la lecture/routage côté app.

## Décision

### La lecture du socket quitte le monde des coroutines

La session réelle (`SessionSocketAndroid`) lit dans UN FIL DÉDIÉ
(« tooling-lecteur », `Thread.NORM_PRIORITY + 1`, démon) :

```kotlin
while (true) {
    val payload = FrameCodec.readFrame(socket.inputStream)  // syscall bloquant
    cheminRapide?.invoke(decoder(payload))                  // router, non suspendant
    canal.trySend(evenement)                                // signal de fin uniquement
}
```

Le `read()` bloquant dort en appel système ; le NOYAU réveille le fil à
l'arrivée des octets. Zéro ordonnancement coroutine entre l'arrivée d'une
frame et son routage : aucune saturation du pool IO, aucune famine CPU du
scheduler de coroutines ne peut plus retarder la lecture. La priorité
relevée d'un cran atténue la famine CPU de l'OS elle-même (le fil reste
préemptible, il ne bride pas l'app — il ne fait que lire et router).

### Le router s'exécute au point de lecture

`GradleApiImpl.router` est non suspendant PAR CONSTRUCTION (v0.49.0) :
`trySend` vers voies non bornées, écritures atomiques (`StateFlow`,
`AtomicLong`), promesses complétées, `ConcurrentHashMap` — tout thread-safe.
Il s'exécute DANS le fil de lecture : le pong et la latence de transport
sont marqués au VRAI point de réception (avant toute file coroutine), et
les voies build/sync se remplissent au rythme du noyau. Les consommateurs
de voies (envois suspendants vers les canaux bornés aval) restent des
coroutines — leur retard éventuel est celui de la PUBLICATION, mesuré par
la sonde de `GradleService` : les deux moitiés du tuyau restent
diagnostiquables séparément.

### Le contrat de session : `acheminerVia`

```kotlin
fun acheminerVia(chemin: (ToolingEvent) -> Unit): Boolean = false
```

La session réelle stocke le chemin et retourne `true` (elle route
elle-même, depuis son fil) ; la collecte du flux `evenements` ne route
PLUS — elle reste le signal de fin de flux (EOF → complétion → ménage du
`finally`, sémantique inchangée, courses de remplacement de session
préservées). Les sessions factices des tests héritent du défaut `false` :
la collecte route, comme avant — aucun test ne change de sémantique. Le
test bout-en-bout du daemon embranche le VRAI fil via son miroir JVM
(`SessionSocketJvm`), réécrit en miroir exact.

### Ce qui ne change pas

- **L'orchestrateur est inchangé** (fil écrivain + file prioritaire pong,
  ADR 0080) : le terrain v0.49.0 a prouvé qu'il écrivait déjà tout
  immédiatement. `ServerVersion` est aligné sur 0.50.0 pour la livraison
  uniquement.
- **L'envoi des requêtes** reste `withContext(Dispatchers.IO)` sous verrou
  (opérations ponctuelles : clic utilisateur, ping toutes les 5 s) — le
  terrain montre qu'elles passent (préparation de build 62-77 ms). La
  resynchronisation d'horloge entre l'orchestrateur et l'app reste celle
  du système (même machine, `System.currentTimeMillis()`).

## Conséquences

- La latence de transport attendue redevient celle d'un UDS réel :
  millisecondes, même sous charge — les résumés `ConsoleLatence` doivent
  désormais afficher des écarts min/max ≈ quelques dizaines de ms.
- Le fil de lecture est un fil de plus dans l'app (un par session, une
  session à la fois) — daemon, nommé, terminé par la fermeture du socket
  (`fermer()` → `read()` lève → fin du fil ; `interrupt()` seul ne sort
  pas d'un read bloquant, c'est la fermeture qui débloque).
- Si un jour le router devait redevenir suspendant, la session le
  détecterait à la compilation (le contrat du chemin est `(ToolingEvent)
  -> Unit`, non suspendant par construction) — le compilateur garde la
  promesse.
- La sonde de publication (GradleService) devient la seule voix audible
  d'un retard résiduel : si elle seule parle, le coupable est la console
  aval (vidanges, zone texte) — plus jamais la lecture.
