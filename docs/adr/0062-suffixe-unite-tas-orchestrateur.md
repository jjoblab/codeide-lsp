# ADR 0062 — Suffixe d'unité du tas de l'orchestrateur : `-Xmx256m`, pas `-Xmx256`

- **Statut** : accepté (v0.35.2, retour utilisateur du 2026-09-27 —
  journal de terrain : la VM de l'orchestrateur ne démarrait pas, cinq
  relances pour rien)
- **Contexte** : journal de terrain v0.35.1 (moto g06, Android 15) :
  après `orchestrateur lancé (pid …)`, le tag `gradle-server` livre
  `Error occurred during initialization of VM` puis `Too small maximum
  heap`, et ce à CHAQUE tentative (`relance 1/5` → `4/5`). La promesse
  de l'ADR 0061 tient : le stderr/stdout drainé dès le lancement a
  révélé la cause racine dans le PREMIER journal après le correctif
  (sur cet appareil, les lignes de mort de la VM arrivent même sur
  stdout — les DEUX flux sont branchés, d'où leur visibilité).
  Reproduction desktop immédiate : `java -Xmx256 -version` → exit 1,
  exactement les deux lignes du journal ; `java -Xmx256m -version` →
  exit 0. La JVM lit un nombre NU en OCTETS : la production passait
  `-Xmx256` = 256 o, sous le minimum de la VM — mort avant toute
  connexion au socket. La spécification disait pourtant partout
  `java -Xmx256m -jar` (ADR 0042, `docs/TOOLING.md`, AGENTS.md) :
  les tests écrivaient le drapeau À LA MAIN avec le suffixe
  (`BoutEnBoutTest`, `ChaosToolingTest`), aucun ne rejouait la commande
  de production (`commandeParDefaut`), et le test unitaire assertait le
  string bogué TEL QUEL (`-Xmx${TAS_MO}`). Un seul caractère manquait
  — invisible à toute la suite de tests.

## Décisions

1. **Suffixe d'unité OBLIGATOIRE** (`DaemonManager.commandeParDefaut`) :
   la taille du tas est apposée avec son suffixe (`-Xmx${TAS_MO}m`,
   256 Mio) ; `TAS_MO` porte la discipline dans son contrat (un nu
   serait lu en octets). La KDoc de la commande documente le défaut et
   son symptôme exact.
2. **Source unique de vérité** : `BoutEnBoutTest` et `ChaosToolingTest`
   ne citent plus la taille en dur — ils référencent `TAS_MO`, la MÊME
   constante que la production : un changement de taille ne peut plus
   diverger entre les deux.
3. **Régression sur VRAIE JVM** (`DaemonManagerTest`, « l argument de
   tas de la commande production est accepte par une vraie JVM ») :
   le test extrait l'argument `-Xmx` de la VRAIE commande de production
   et le fait exécuter par la JVM qui fait tourner les tests
   (`java <tas> -version`, sortie fusionnée, code attendu 0). Une
   taille invalide — nu, suffixe oublié, unité absurde — est refusée
   par la VM elle-même AVANT l'appareil : c'est la seule autorité qui
   valide réellement la ligne de commande de lancement.

## Conséquences

- La VM de l'orchestrateur démarre : un tas de 256 Mio se réserve sans
  peine et la fenêtre de connexion de 30 s (ADR 0061) couvre
  largement le démarrage à froid du JDK du bootstrap sur appareil.
- Toute future taille de tas invalide échoue en CI sur le poste de
  développement, plus jamais en silence sur l'appareil.
- Les warnings `JDK introuvable` du même journal de terrain sont
  l'ÉTAT conçu (ADR 0042) : le daemon démarre au processus principal,
  le JDK s'installe pendant l'onboarding, la prochaine demande de
  démarrage re-teste — ce n'est pas un bug, le journal de terrain le
  confirme (l'orchestrateur EST lancé après l'installation).
