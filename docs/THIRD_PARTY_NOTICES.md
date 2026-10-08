# Notices tierces

Bibliothèques tierces distribuées dans les binaires de CodeIDE, avec
leur licence et leur usage. Chaque entrée est vérifiée à la date
d'ajout contre les sources officielles.

| Bibliothèque | Version | Licence | Usage | Modules |
|---|---|---|---|---|
| `com.github.jjoblab.code-editor:cel-ui` | 3.37.0 | Apache-2.0 (dépôt jjoblab/code-editor) | Éditeur de code, onglets, coloration | `feature:editor` (depuis l'étape 13) |
| `com.github.termux.termux-app:terminal-emulator` | v0.118.3 | Apache-2.0 — code dérivé de [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator), exception explicite du dépôt termux-app (racine GPLv3) | Sessions shell interactives via pseudo-terminal | `core:terminal-runtime` (étape T4), `feature:terminal` (T5) |
| `com.github.termux.termux-app:terminal-view` | v0.118.3 | Apache-2.0 — même exception que `terminal-emulator` (code jackpal) | Rendu du terminal (`TerminalView`) | `feature:terminal` (étape T5) |
| Icônes IntelliJ Platform New UI | 2024.1+ | Apache-2.0 — dépôt [`JetBrains/intellij-community`](https://github.com/JetBrains/intellij-community) | VectorDrawable de l'explorateur (`ic_fichier_kotlin`, `ic_fichier_gradle_kts`, `ic_fichier_gradle`, `ic_fichier_properties`, `ic_fichier_toml`, `ic_fichier_java`, `ic_fichier_xml`, `ic_fichier_manifest`, `ic_fichier_markdown`, `ic_fichier_json`, `ic_fichier_config`, `ic_dossier`, `ic_gradle`) | `core:ui` (depuis v0.63.0, C0) |
| Icônes Android Studio | 2024.1+ | Apache-2.0 — dépôt [`JetBrains/android`](https://github.com/JetBrains/android) | VectorDrawable spécifiques Android (`android-file`, `android-module`, `manifest-file`, etc. — utilisation prévue pour C1+) ) | `core:ui` (depuis v0.63.0, C0) |

## Notes de vérification (2026-09-23)

- **`terminal-emulator`** : la racine du dépôt `termux/termux-app` est
  GPLv3, mais son `LICENSE.md` excepte explicitement `terminal-view` et
  `terminal-emulator` (code jackpal, Apache-2.0). L'artifact JitPack
  `com.github.termux.termux-app:terminal-emulator:v0.118.3` a été
  résolu et son POM inspecté.
- **Alignement 16 KB** (vérifié 2026-09-23, T4) : `libtermux.so` de
  v0.118.3 — comme v0.119.0-beta.3 — garde un `p_align` de 4096 ;
  aucune release amont n'est alignée 16 KB à ce jour (exception lint
  documentée dans le build du module, ADR 0035).
- **`termux-shared` n'est volontairement PAS utilisé** : sa licence est
  GPLv3 (exceptions MIT partielles qui ne couvrent pas
  `terminal/io/extrakeys` — vérifié sur le `LICENSE.md` de v0.118.3),
  alors que CodeIDE est pour l'instant « tous droits réservés ». Le
  clavier étendu de l'étape T5 est donc implémenté en interne (ADR à
  l'étape T5). Ce choix sera re-considéré si la licence du projet
  évolue.
