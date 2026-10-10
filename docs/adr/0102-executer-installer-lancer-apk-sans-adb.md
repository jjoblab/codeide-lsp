# ADR 0102 — Exécuter sans adb : installer et lancer l'APK (PackageInstaller)

- Statut : accepté (2026-10-10) — phase R0 de la mission « Exécuter »
- Contexte : l'utilisateur veut le bouton « Run » d'Android Studio sur
  téléphone : compiler, **installer**, **lancer**, sans adb ni
  ordinateur. Étude des références (lecture de code, dépôts GPL —
  ré-implémentation intégrale, aucune recopie : CodeIDE n'est pas sous
  GPL) :
  - **AndroidIDE** : `ApkInstaller` avec tampon 8 Ko + `fsync`,
    PendingIntent broadcast, récepteur **exporté à action FIXE**
    (forgeable), **aucune relance** de lancement, aucune gestion de
    `canRequestPackageInstalls()`.
  - **projet de référence** : pré-vérification `canRequestPackageInstalls()`
    avec écran système guidé et **reprise automatique** (attente
    bornée 5 min, poll 300 ms), session `MODE_FULL_INSTALL` + `fsync`,
    récepteur **non exporté à action unique par session**, relances de
    lancement **10 × 200 ms** sur le fil principal, lancement relayé au
    **processus d'interface** (un service en arrière-plan ne peut pas
    démarrer une activité — Android 10+).
  - Aucun des deux n'utilise
    `SessionParams.setRequireUserAction(false)` (API 31+).

## Décision

### Port et implémentation

Un **port `ApkInstaller` dans `core:domain`** (faux pour les tests JVM :
`FakeApkInstaller` dans `core:testing`), implémentation Android dans le
**module `app`** (process principal, `Context` requis), liée dans
`DomainBindingsModule`. Le lancement d'activité et l'installation
restent **dans le processus d'interface** — le serveur de tooling est
un process JVM séparé qui n'a pas le droit de démarrer une activité.

### Flux complet de « Exécuter » (R1)

1. **Compiler** : `assembleDebug` par la chaîne existante
   (`ExecuterTachesUseCase` → tooling client-serveur) — aucun
   changement de protocole : le chemin de l'APK est **déterministe**
   (`<projet FUSE>/app/build/outputs/apk/debug/app-debug.apk`,
   comme `scripts/verify-templates.sh`), et l'`applicationId` se lit
   dans `output-metadata.json` du même dossier.
2. **Attendre la fin du build** (succès) — l'état de build existe déjà
   (`GradleService`, `BuildFinished` via le protocole actuel).
3. **Vérifier la permission « sources inconnues »** (API 26+) :
   `canRequestPackageInstalls()` faux → snackbar avec action
   « Autoriser » ouvrant
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES?package=<codeide>`, **reprise
   automatique** de l'installation au retour (attente bornée 5 min,
   poll 300 ms — le Run n'est jamais perdu, leçon projet de référence).
4. **Installer** : session `PackageInstaller` en `MODE_FULL_INSTALL`,
   ouverture « base.apk », copie par blocs de 8 Ko, `fsync` explicite,
   `commit(PendingIntent)` — PendingIntent broadcast
   `FLAG_UPDATE_CURRENT | FLAG_MUTABLE` (le système remplit le status
   — mutabilité requise depuis API 31). Récepteur enregistré
   `RECEIVER_NOT_EXPORTED` avec une **action unique par session**
   (auto-réservée à l'app — non forgeable).
5. **Traiter le statut** : `STATUS_PENDING_USER_ACTION` → relancer
   l'`EXTRA_INTENT` de confirmation du système (l'installation passe
   TOUJOURS par la confirmation système — exigence de sécurité) ;
   `STATUS_SUCCESS` → lancement ; échec → message français avec
   **action correctrice** (voir typologie).
6. **Lancer** : `getLaunchIntentForPackage` puis repli
   `queryIntentActivities(MAIN/LAUNCHER)` → intent **explicite**
   (ComponentName), **10 relances espacées de 200 ms** (le
   `PackageManager` du processus appelant peut ne pas voir le paquet
   juste après l'installation), fil principal, depuis le processus
   d'interface.

### Améliorations par rapport aux deux références (mesurées)

- `setRequireUserAction(false)` **dès que l'API 31 est disponible** :
  la confirmation système est demandée la première fois, puis les
  mises à jour du même paquet s'installent sans interaction (reprise
  de l'intention d'Android 12 ; gardée silencieuse uniquement quand le
  système l'accepte — sinon la confirmation s'affiche, honnêteté
  d'abord).
- Récepteur **non exporté + action par session** : ni AndroidIDE
  (exporté) ni projet de référence (action dérivée de l'identifiant de session,
  même approche — nous adoptons la leur, prouvée).
- Échecs **typés en français** avec action correctrice :
  - signature différente → proposer **désinstaller puis réinstaller**
    en nommant la **perte de données** (confirmation obligatoire) ;
  - `versionCode` plus ancien → proposer la désinstallation d'abord ;
  - espace insuffisant / APK invalide → message du système traduit et
    action « Ouvrir les Paramètres du projet ».

### Permission `REQUEST_INSTALL_PACKAGES`

Ajoutée au manifeste de `app` : permission **restreinte Google Play**
(usage déclaré obligatoire en distribution Play). CodeIDE se distribue
aujourd'hui **hors Play** (dépôt GitHub, APK signé) : impact nul
maintenant, à documenter le jour d'une distribution Play. Manifeste :
ajout du bloc `<queries>` (intent `MAIN`/`LAUNCHER`) pour la
**visibilité des paquets** Android 11+ — nécessaire à
`getLaunchIntentForPackage` de l'app fraîchement installée.

### Hors périmètre (assumé, affiché honnêtement)

Pas d'émulateur, pas de débogueur, pas de `adb` sans fil, pas de
désinstallation automatique silencieuse. `targetSdk = 28` (W^X, ADR
0045) n'entrave rien : `PackageInstaller` et la permission « sources
inconnues » sont des API d'exécution, indépendantes du target.

## Conséquences

- Aucune nouvelle dépendance (API framework uniquement) — pas d'ADR de
  dépendance.
- Tests : `ExecuterApplicationUseCase` en JVM pur avec
  `FakeApkInstaller` (APK absent, permission refusée puis reprise,
  confirmation système, succès + relances, échec signature) ;
  analyse d'`output-metadata.json` pure et testée.
- Phases suivantes : R2 (pont de logs, ADR 0103), R3 (onglet Logcat),
  R4 (traces cliquables), R5 (finitions).
