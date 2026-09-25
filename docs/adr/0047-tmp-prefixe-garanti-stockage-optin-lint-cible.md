# ADR 0047 — `tmp` du préfixe garanti, stockage partagé opt-in et double exception lint targetSdk

- **Statut** : accepté (v0.31.3, correction après retour utilisateur)
- **Contexte** : troisième retour de terrain (app v0.31.2, même appareil
  moto g06 / Android 15). L'installation du bootstrap échoue à « la
  configuration des paquets » : `apt update` sort en **code 100** avec

  ```
  E: Unable to mkstemp /data/data/jo.codeide/files/usr/tmp/clearsigned.message.XPzFqH
     - GetTempFile (2: No such file or directory)
  W: Conflicting distribution: https://jjoblab.github.io/codeide-packages/apt/codeide-main
     stable Release (expected stable but got)
  ```

  et l'utilisateur conclut « il faut demander les permissions de lecture
  et d'écriture de stockage ». Par ailleurs la CI GitHub échoue depuis
  v0.31.1 sur `:app:lintDebug` :

  ```
  Error: Google Play requires that apps target API level 33 or higher.
  [ExpiredTargetSdkVersion]
  ```

- **Diagnostic** :

1. **`mkstemp` échoue en ENOENT (errno 2), pas en EACCES (errno 13)** :
   le répertoire visé, `$PREFIX/tmp`, **n'existe pas**. Ce n'est pas un
   refus de permission — le stockage privé de l'application
   (`filesDir`/`$PREFIX`) ne demande RIEN à personne (ADR 0003/0034).
   `EnvironnementProcessus` exporte `TMPDIR=$PREFIX/tmp` depuis toujours
   ; l'archive publiée par `codeide-packages`, elle, **n'embarque pas
   l'entrée `tmp/`** — constat du 2026-09-25 par comparaison des listes
   d'entrées : le bootstrap officiel Termux en contient 280
   répertoires dont `tmp/`, la publication n'en a que 107, sans `tmp/`.
   La FAQ Termux documente la même panne quand un utilisateur supprime
   `$PREFIX/tmp` : apt devient inutilisable. Le nom de fichier temporaire
   (`clearsigned.message`) désigne le découpage d'un fichier signé
   clair par apt — quelle que soit l'étape précise, chaque opération
   temporaire d'apt vise un répertoire absent.
2. **Le warning « Conflicting distribution » est un artefact
   secondaire** : le `Release` servi en ligne porte bien
   `Suite: stable` et `Codename: stable` (re-vérifié le 2026-09-25) ;
   le « got » vide ne peut venir d'un `Release` lu vide ou tronqué par
   le même pipeline temporaire cassé. Après correction du répertoire,
   le warning doit disparaître ; s'il persistait, le journal
   d'installation en direct (v0.31.2, ADR 0046) donnerait la ligne
   exacte sur l'appareil.
3. **L'hypothèse « permissions de stockage » est techniquement fausse
   pour CETTE erreur** — mais exprime un besoin réel et légitime : lire
   et écrire le stockage partagé (`/storage/emulated/0`) **depuis le
   terminal** (photos, téléchargements…). Termux, sous la même
   contrainte cible 28 (W^X, ADR 0045), répond exactement par ce trio :
   READ+WRITE pour l'accès legacy, « Tous les fichiers »
   (`MANAGE_EXTERNAL_STORAGE`) pour Android 11+, jamais exigés.
4. **La CI échoue sur `ExpiredTargetSdkVersion` alors que v0.31.1 a
   désactivé `ExpiringTargetSdkVersion`** : ce sont DEUX issues lint
   distinctes — la première (sévérité ERREUR directe : « Google Play
   requires »), la seconde (conseil, sévérité avertissement, montée en
   erreur par `warningsAsErrors`). L'exception v0.31.1 n'a couvert que
   la seconde : `:app:lintDebug` reste rouge depuis.

- **Décisions** :

1. **`tmp` créé à l'extraction** : `ExtracteurBootstrap` crée
   explicitement `staging/tmp` (idempotent si une future archive
   l'embarque) — la bascule atomique le porte dans `$PREFIX`. Test de
   régression : une archive sans entrée `tmp/` produit quand même un
   répertoire `tmp` dans le staging.
2. **`tmp` garanti à chaque lancement** (défense en profondeur) :
   `EnvironnementProcessusFournisseur.baseEnvironment()` appelle
   `assurerRepertoiresProcessus(racine)` — recréation idempotente de
   `$PREFIX/tmp` et `$HOME` avant TOUT sous-processus. Couvre les
   préfixes posés avant v0.31.3 et le `tmp` supprimé à la main (panne
   documentée par la FAQ Termux). Tout passe par là : apt du
   configurateur, second stage, sessions du terminal, daemon du
   tooling. Fonction de fichier testable sans Android ; échec
   volontairement silencieux (l'échec réel du sous-processus remonte
   avec son message propre).
3. **Stockage partagé OPT-IN** (répond au souhait utilisateur sans
   trahir ADR 0003/0034) : manifeste enrichi du trio
   READ/WRITE_EXTERNAL_STORAGE (legacy, Android < 11) +
   MANAGE_EXTERNAL_STORAGE (« Tous les fichiers », Android 11+) avec
   `requestLegacyExternalStorage` ; la page Notifications de
   l'assistant gagne une section « facultative » : bouton de demande
   (requête runtime sous Android 11, réglage « Tous les fichiers »
   au-delà), état RÉEL relu au `onResume` (`isExternalStorageManager`
   ou permission WRITE — jamais supposé), repli par les réglages.
   Aucun parcours ne l'exige : « Suivant » passe sans rien accorder.
   L'ADR 0046 rejetait READ/WRITE « par précaution » — l'opt-in pour
   le terminal répond au besoin exprimé, pas à une prudence
   générique ; ce rejet est levé sur ce point précis.
4. **Double exception lint** : `ExpiredTargetSdkVersion` (erreur)
   rejoint `ExpiringTargetSdkVersion` (avertissement) dans les
   désactivations du plugin d'application — même justification
   (cible 28 délibérée, W^X, ADR 0045 ; application chargée par
   side-loading, l'exigence Play ne s'applique pas), mais les deux ID
   doivent être listés.

- **Alternatives rejetées** :

- *Bump targetSdk 33* (supprimerait le souci lint à la racine) :
  réactivait W^X — interdit (ADR 0045, confirmé par le diagnostic
  v0.31.1).
- *Demander les permissions en dur / obligatoires* : rien dans le
  fonctionnement de base ne les justifie (stockage privé + SAF) ;
  contradiction frontale avec ADR 0003/0034 — l'opt-in est le
  compromis qui honore la demande utilisateur.
- *`TMPDIR` vers le cache de l'application* : le système peut purger
  `cacheDir` pendant que l'application tourne (tuerait un `apt
  install` en plein vol) ; s'écarte des conventions Termux que suit
  tout l'écosystème embarqué ; `$PREFIX/tmp` est la localisation
  attendue par les scripts du bootstrap.
- *Corriger uniquement l'archive côté `codeide-packages`* (re-publier
  avec `tmp/`) : souhaitable en amont mais hors de notre contrôle
  immédiat — et ne couvrirait pas le `tmp` supprimé après coup ;
  les deux couches applicatives restent nécessaires de toute façon.
- *Vider les listes apt avant `apt update`* (`rm -rf
  var/lib/apt/lists/*`) : traiterait le warning « Conflicting
  distribution » sans toucher la cause (répertoire absent) — le
  warning est un symptôme, pas la panne.

- **Conséquences** : le premier `apt update` d'une installation
  dispose d'un `TMPDIR` réel (la cause du code 100 disparaît) ; les
  préfixes antérieurs se réparent seuls au prochain sous-processus ;
  l'utilisateur qui veut le stockage partagé au terminal l'obtient par
  un opt-in explicite et réversible ; la CI redevient verte
  (`lintDebug` couvert par la désactivation paire). Le re-publiage de
  l'archive avec `tmp/` embarqué reste un souhait côté dépôt de
  paquets — le comportement applicatif n'en dépend plus. Chaînes de la
  page Notifications traduites en anglais au passage (les chaînes
  v0.31.2 n'avaient pas été traduites).
