# ADR 0051 — Complétion SAF des extensions développeur et tap d'onglet consommé

- **Statut** : accepté (v0.31.7, correction après retour utilisateur)
- **Contexte** : septième retour de terrain (app v0.31.6, moto g06 /
  Android 15, suite du rapport 4a4526aa). Deux familles :
  1. **`Storage(reason=AlreadyExists, details=README.md)`** — l'écran
     d'échec v0.31.6 (détails techniques visibles, copiables) a fait
     exactement son travail : le piège `.gitattributes` (premier
     fichier du plan des modèles JVM) est CORRIGÉ — le pré-vol passe,
     le dossier racine est créé, les fichiers cachés
     (`.gitattributes`, `.gitignore`, `.editorconfig`, type privé
     v0.31.6) s'écrivent sous leur nom exact… et l'échec a PROGRESSÉ
     au fichier suivant : `README.md`. La complétion d'extension SAF
     frappe donc AUSSI les noms **avec** extension.
  2. **« Quand j'appuie sur le tab layout l'onglet pour changer de
     session, rien ne se passe »** — le tap sur un onglet de session
     du terminal est sans effet (frappe, zoom et création de session
     v0.31.5 fonctionnent ; l'écran ne plante plus depuis v0.31.6).

- **Diagnostic** :

1. **La table d'extension du fournisseur ne connaît pas les extensions
   de développement.** Le comportement réel d'`ExternalStorageProvider`
   (`FileUtils.buildUniqueFile`/`splitFileName`, AOSP) : quand
   l'extension du nom demandé est absente de la table système
   (`MimeTypeMap`), le fournisseur APPEND l'extension canonique du
   type MIME demandé. Or v0.31.6 avait constaté la complétion sur un
   nom SANS extension réelle (`.gitattributes` + `text/plain` →
   `.gitattributes.txt`) et en avait déduit — à tort — qu'un nom AVEC
   extension était protégé par celle-ci. Le retour v0.31.7 prouve le
   contraire : l'extension `md` est absente de la table de l'appareil
   (comme `kts`, `kt`, `properties`, `pro`, `gradle`, `toml`… la table
   varie par version d'Android et par OEM et n'a JAMAIS garanti les
   extensions de code) → `README.md` + `text/plain` est créé
   `README.md.txt` → le contrôle du nom retourné lit un renommage
   hostile (intolérable à tolérer : la tolérance v0.31.6 ne couvre que
   les noms SANS extension réelle, et c'est VITAL — un projet généré
   avec `build.gradle.kts.txt` casserait Gradle en silence) → document
   fraîchement créé supprimé + `AlreadyExists("README.md")` → rollback
   complet → même message que v0.31.6, un fichier plus loin. TOUT le
   plan des modèles JVM est piégé après le premier caché réussi :
   `README.md`, `build.gradle.kts`, `settings.gradle.kts`,
   `gradle.properties`, `proguard-rules.pro`, les sources `*.kt`…
2. **Une vue `longClickable` consomme les taps simples.** La racine de
   la vue d'onglet de session portait un écouteur d'appui long seul
   (menu renommer/dupliquer/fermer, `configurerOnglet`).
   `View.onTouchEvent` retourne `true` dès que la vue est clickable
   **ou** longClickable — le geste est consommé par la racine, dont le
   `performClick()` (tap) ne fait RIEN (aucun écouteur de clic posé) ;
   le `TabView` parent ne reçoit jamais l'événement, la sélection du
   TabLayout ne se déclenche jamais. D'où « rien ne se passe » :
   déterministe, sur tout tap d'onglet, indépendant de la version
   d'Android. Le bouton « + » (simple `ImageView`, non consommatrice)
   et le bouton de fermeture (écouteur propre) fonctionnaient, de même
   que la sélection programmatique du diff — seul le TAP utilisateur
   était mort.

- **Décisions** :

1. **`mimeFichierTexte` répond le type privé pour TOUT fichier texte**
   (`core:domain`, `FileSystem.kt`) : la décision ne dépend plus du
   nom — `text/x-codeide` (inconnu de la table système, donc sans
   extension canonique : le fournisseur ne complète RIEN, pour AUCUN
   nom — comportement prouvé sur l'appareil depuis v0.31.6 pour les
   noms sans extension). La preuve empirique du retour : les cachés
   (type privé) passent, `README.md` (text/plain) échoue. Le paramètre
   `chemin` est conservé pour la stabilité des appelants (suppression
   justifiée, documentée dans le KDoc). `CreateProjectUseCase.mimePour`
   et `EditorViewModel.creerFichier` suivent automatiquement (même
   fonction partagée).
2. **La tolérance `estAchevementExtension` reste bornée aux noms sans
   extension réelle** (inchangée, v0.31.6) : une complétion sur un nom
   AVEC extension reste un échec honnête (nettoyage + `AlreadyExists`
   au nom exact, détails visibles) — jamais une corruption silencieuse
   du plan de fichiers.
3. **Le faux fournisseur reproduit la règle RÉELLE**
   (`FauxFournisseurDocuments`) : complétion si l'extension du nom est
   inconnue de la table du fournisseur (inverse de la table canonique
   `text/plain→txt`, `image/png→png`… — `md`, `kts`… absents, comme
   sur l'appareil), JAMAIS pour un type sans extension canonique
   (privé). L'ancienne règle « sans extension réelle » (v0.31.6) ratait
   la famille du retour.
4. **La vue d'onglet gère elle-même son clic**
   (`brancherInteractionsOnglet`, `feature:terminal`) : la racine prend
   un écouteur de clic (ouvrir la session) en plus de l'appui long
   (menu) — même architecture que Termux, dont les vues d'onglet
   gèrent leur propre clic. L'écouteur `OnTabSelectedListener` du
   TabLayout reste pour les sélections extérieures à la vue
   (navigation clavier, zone hors vue personnalisée). La fonction est
   interne au module, testée avec le VRAI layout (tap → ouvrir,
   bouton → fermer, appui long → menu).

- **Alternatives rejetées** :

1. *Tolérer la complétion pour tout nom* — corruption silencieuse :
   le plan annonce `build.gradle.kts`, le disque porterait
   `build.gradle.kts.txt` ; Gradle ne trouverait jamais ses fichiers
   de build. Rejeté (honnêteté avant tout).
2. *Liste blanche d'extensions « sûres »* (`txt`, `xml`, `json`… garder
   `text/plain` pour elles) — fragile : la table varie par version
   d'Android et par OEM (la preuve : `md` absent d'un Android 15
   Motorola) ; toute future extension oubliée rouvrirait le piège. Le
   type privé est sûr pour TOUS les noms, sans maintenance.
3. *Retirer l'appui long de la racine* (le rendre non consommateur) —
   impossible : un écouteur d'appui long rend la vue `longClickable`
   par contrat (`setOnLongClickListener`), la consommation du tap est
   inhérente ; et le menu contextuel sur l'onglet est une exigence de
   l'écran (renommage, duplication).
4. *Compter sur le `OnTabSelectedListener` du TabLayout en rendant la
   vue non cliquable* — même en supprimant l'appui long (rejeté
   ci-dessus), toute vue personnalisée POSANT des écouteurs consomme ;
   la robustesse exige que la vue agisse sur ce qu'elle consomme.
5. *`DocumentFile`/chemins `File` pour contourner SAF* — interdit par
   l'ADR 0003 (URI, jamais `File`) ; le contournement ne résoudrait
   rien (la complétion est un comportement du fournisseur, pas de
   l'API d'accès).

- **Conséquences** :

- Les fichiers texte créés par l'application portent le MIME privé
  `text/x-codeide` dans leur colonne `COLUMN_MIME_TYPE` — sans effet
  pour l'application (aucune décision ne repose sur le MIME lu des
  `FileStat` ; le contenu fait foi) ni pour SAF (types arbitraires
  permis).
- La création de projet des modèles JVM écrit désormais TOUT son plan
  sous les noms exacts (cachés, `README.md`, `build.gradle.kts`,
  `*.kt`, `gradle.properties`…) : le premier build du projet généré
  trouve ses fichiers.
- Le changement de session par onglet répond au premier tap ; l'appui
  long garde son menu ; la fermeture directe reste sur le bouton.
- À surveiller au prochain retour d'appareil : la création complète
  d'un projet (jusqu'au projet listé et ouvrable), le tap d'onglet
  PENDANT une commande (le diff ne reconstruit plus les vues —
  v0.31.5), et l'éventuel détail technique d'un échec résiduel (si un
  fournisseur exotique complétait malgré le type privé, l'échec
  resterait honnête et diagnostiquable).
