# ADR 0038 — Carte d'aperçu du terminal dans le tiroir et pont SAF → FUSE

- **Statut** : accepté (étape 24 = Terminal T6, v0.25.0)
- **Contexte** : prompt compagnon « Terminal intégré et bootstrap
  natif », sections 7-8 (points d'entrée accueil et tiroir) et 1.5 ;
  ADR 0003 (stockage SAF, jamais `File`), ADR 0035 (registre global),
  ADR 0036 (écran plein écran).

## Décision

1. **Le tiroir montre une carte, pas un terminal.** La quatrième
   destination de la barre basse (« Terminal », active) remplace
   l'explorateur par une carte de métadonnées : nombre de sessions
   actives (pluriels), libellé + dernière sortie de la session active
   (monospace, 3 lignes), pastille d'état (vivante = `colorPrimary`,
   terminée = `colorOutline`), bouton d'agrandissement. Toute la carte
   et le bouton appellent `AppNavigator.openTerminal(chemin)` — le
   **même** écran plein écran que l'accueil, la même liste de sessions
   (critère d'acceptation T6).
2. **`feature:editor` ne gagne aucune dépendance.** La carte consomme
   `TerminalSessionRepository` (core:domain) uniquement — aucun
   `com.termux:*`, aucun `core:terminal-runtime`, aucun
   `feature:terminal` (règle `checkModuleDependencies` inchangée).
   L'état (`EtatTerminalTiroir`) vit dans son propre `StateFlow`,
   combiné depuis `observeSessions()` + `observeActiveSessionId()` :
   la carte se met à jour en direct, sessions créées d'où qu'elles
   viennent.
3. **Pont SAF → FUSE : `ResoudreRepertoireProjet` (core:domain).**
   Un shell du bootstrap est un processus fils de l'app — même UID,
   même vue FUSE : `cd /storage/emulated/0/…` y fonctionne pour les
   arborescences dont l'app tient la permission. Le cas d'usage traduit
   l'identifiant de document SAF (`primary:CodeIDE/Projet`, décodé par
   le port existant `ArborescencesSaf`) en chemin réel
   (`/storage/emulated/0/CodeIDE/Projet` ; volumes amovibles par UUID),
   avec durcissement anti-traversée (`.`/`..`/segments vides rejetés) et
   **garde « répertoire fantôme »** (volume démonté → `null`, jamais de
   session dans un dossier inexistant). Pure fonction JVM testée à part
   (discipline T1). Le futur tooling (exécution Gradle sur l'appareil)
   réutilise ce cas d'usage : une seule traduction arborescence →
   chemin réel.
4. **Création depuis l'état vide : le tiroir crée, puis ouvre.**
   « Nouvelle session dans ce projet » appelle `createSession(File(chemin),
   nom du projet)` **avant** d'ouvrir l'écran plein écran dessus (le
   registre démarre le service foreground, ADR 0035). Si le chemin réel
   est introuvable (fournisseur non stockage, volume démonté), la
   session n'est pas créée ici : l'écran terminal s'ouvre et son propre
   état vide crée dans le `HOME` canonique — la règle de repli reste à
   une seule place (TerminalViewModel).
5. **Garde-fou bootstrap symétrique de l'accueil (section 7).** Sans
   bootstrap installé, la carte affiche « outils non installés » +
   « Installer les outils » → `openBootstrapInstall()` ; la création
   directe est refusée (une session sans shell meurt immédiatement —
   jamais d'UI mensongère). L'action « Terminal » de la toolbar de
   l'accueil suit la même règle : écran d'installation si le bootstrap
   est absent, jamais un terminal non fonctionnel.
6. **Icône `ic_terminal` partagée dans `core:ui`** (contour, même
   langage que `ic_explorateur`) : toolbar de l'accueil (les deux
   variantes téléphone et sw600dp) et destination du tiroir.

## Alternatives rejetées

- **Terminal embarqué dans le tiroir** : contredirait la section 8 du
  prompt (carte demandée) et lierait `feature:editor` au rendu Termux.
- **Passer le `displayPath` du projet comme répertoire suggéré** :
  libellé lisible, pas un chemin — le shell ne pourrait pas `cd`
  dessus ; mentir à l'UI est exactement ce que la section 7 interdit.
- **Calculer le HOME côté éditeur pour la création** : dupliquerait la
  logique de repli du terminal (deux sources de vérité) — refusé au
  profit du repli déporté à l'écran terminal.
- **Carte cachée tant que le bootstrap est absent** : moins honnête
  qu'un état explicite « installer les outils » — la carte explique
  pourquoi au lieu de disparaître sans raison.

## Conséquences

- L'état vide de la carte n'apparaît que s'il n'existe **aucune**
  session (vivante ou terminée) : les sessions terminées restent
  visibles avec pastille neutre et libellé « — terminée ».
- `EditorViewModel` gagne trois dépendances (registre, résolution,
  localisateur) — l'exemption detekt documentée couvre déjà la classe.
- Le test Robolectric du menu du tiroir attend désormais **quatre**
  destinations (mise à jour avec la carte).
- La résolution FUSE dépend du volume `primary` monté par
  `ExternalStorageProvider` : fait de plateforme stable, documenté ici
  comme tel (pas une heuristique de code).
