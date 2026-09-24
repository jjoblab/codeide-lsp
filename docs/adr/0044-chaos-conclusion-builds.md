# ADR 0044 — Chaos réel, conclusion des builds orphelins et audit final (G6)

- **Statut** : accepté (étape G6, v0.31.0)
- **Contexte** : prompt compagnon Tooling, §7.5 — la robustesse se prouve
  au chaos : process tué en plein build, socket perdue, version
  incompatible, JDK introuvable ; timeouts « partout, jamais absents » ;
  `docs/TOOLING.md` devient la référence finale. L'audit absorbe aussi
  les points T7 — sauf ceux qui exigent un appareil réel.

- **Décisions** :

1. **Le chaos est RÉEL, pas simulé**. `ChaosToolingTest` (tooling:daemon)
   réutilise le harnais du bout-en-bout G4 (VRAI sous-processus `java`
   sur VRAI socket Unix) et le frappe : `kill -9` du process en plein
   build long, rupture du canal côté « app » (socket perdue). Les fakes
   des tests d'états (G3/G4) prouvent les machines d'états ; eux seuls
   ne prouvent ni les EOF réels, ni les courses du pompe, ni la sortie
   du process — le harnais devient `internal` et s'instrumente
   (registre des process lancés, dernière session acceptée).

2. **Découverte du chaos : les builds orphelins pendaient à jamais**.
   À la perte de session, `romprePromesses` résolvait les requêtes en
   attente — mais un build EN COURS restait EN COURS à vie : aucun
   `BuildFinished` n'arriverait jamais, l'état observé par l'onglet
   Sortie ne changerait plus et son canal resterait ouvert (collecteur
   suspendu indéfiniment). **Correctif** : `GradleApiImpl
   .rompreBuildsEnCours()` — à la fin du pompe (même garde de course
   `session === nouvelleSession`), chaque build EN COURS passe `ECHOUE`
   (« connexion avec l'orchestrateur perdue ») et son canal se ferme :
   même sémantique de clôture que `pomperFin`, un collecteur tardif
   draine puis complète. Prouvé au niveau unitaire (fake EOF) ET réel
   (kill -9 en plein build).

3. **Socket perdue = sortie seule, code 0, aucun orphelin**. L'app EST
   le serveur du socket : à sa mort (ou à la rupture du canal), le
   process orchestrateur voit l'EOF et `ServerMain` retourne 0 — il
   sort SEUL, avant même que le health check du daemon ne s'en mêle.
   Le test prouve la promptitude (budget 15 s < délai muet 15 s) et le
   code de sortie exact.

4. **Timeouts : inventaire plutôt que réécriture**. Le chaos a trouvé
   un trou ; les délais, eux, étaient déjà « partout » : client (sync
   5 min, tâches 30 s, connexion 10 s) et serveur (build 30 min avec
   annulation forcée, sync 5 min, tâches/dépendances 30 s, modèle
   5 min) — G2/G3 les avaient posés. G6 les consolide en table de
   référence dans `docs/TOOLING.md` (frontière par frontière, effet au
   dépassement). Pas de timeout client sur le build : il est
   feu-and-forget (§5.3), son état s'observe — c'est le serveur qui
   borne à 30 min.

5. **`ServerVersion` inchangée (0.30.0)** : G6 ne modifie PAS
   l'orchestrateur (le correctif vit côté client) — le JAR n'est pas
   redélivré, la constante d'orchestrateur ne bouge pas ; la version
   d'étape vit dans `version.properties` (0.31.0) comme à G3/G4.

6. **Points T7 exigeant l'appareil : explicitement différés** (ADR
   targetSdk sur appareil réel, revue mémoire LeakCanary des sessions
   d'édition) — documentés comme tels dans le ROADMAP et TOOLING,
   aucun faux « terminé » : ils exigent le même moment de vérité
   (l'appareil) et ne peuvent pas être simulés ici.

- **Alternatives rejetées** : chaos sur fakes seuls (ne prouve pas les
  EOF/races réels) · conclure les builds à `marquerEchouee` (couvre
  l'épuisement des relances, pas la perte simple de session — l'EOF
  arrive AVANT toute décision du daemon) · timeout client sur le build
  (double-garde inutile : le serveur annule déjà à 30 min, l'état est
  observé, pas attendu) · bumper `ServerVersion` sans changement serveur
  (affirmerait une livraison d'orchestrateur qui n'a pas eu lieu).

- **Conséquences** : l'onglet Sortie conclut TOUJOURS (réussi, échoué,
  annulé, ou connexion perdue) — aucune suspension infinie n'est
  possible côté UI ; le chaos réel entre dans la suite daemon (la CI
  l'éprouve à chaque poussée) ; `docs/TOOLING.md` devient la référence
  d'exploitation du tooling (architecture livrée, délais, chaos,
  journalisation, CI) ; la mémoire de la suite locale reste contrainte
  (les classes de test réel se lancent une à une sur la machine à
  4 Go — la CI enchaîne sans état partagé).
