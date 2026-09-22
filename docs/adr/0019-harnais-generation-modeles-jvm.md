# ADR 0019 — Validation réelle des modèles : harnais JVM dédié et plan figé déversé sur disque

- Date : 2026-09-22 (étape 9, v0.10.0)
- Statut : accepté

## Contexte

La section 11 du prompt maître exige, pour les modèles embarqués
(`kotlin-jvm`, `java`), une validation en **deux niveaux** :

1. *Génération* : tests exhaustifs sur `FakeFileSystem` (portés par
   `ModelesEmbarquesTest`, module `app`) ;
2. *Build réel* : `scripts/verify-templates.sh` génère sur **disque** un jeu
   couvrant de combinaisons, puis compile, teste, exécute et publie chaque
   projet avec les vrais outils (Gradle, Maven, `javac`).

Le prompt laisse le choix du harnais de génération sur disque : « Robolectric
ou module JVM dédié — choix de l'agent, à documenter dans un ADR ».

## Décision

1. **Module JVM dédié** `:tools:generateur` (convention `codeide.kotlin.library`,
   plugin `application`). Motivations :
   - invocation directe depuis le script (`./gradlew :tools:generateur:run`),
     protocole de sortie explicite (`OK <id>` / `ECHEC <id>` / `GENERE n/m`),
     code de sortie exploitable — sans machinerie Android dans la boucle ;
   - réutilisation **du vrai moteur** (`core:domain`) au-dessus d'un port
     d'assets sur fichiers (`FichierTemplateAssetsSource`) : ce que le harnais
     écrit est exactement ce que l'application écrirait — les garanties de
     sécurité du port (traversées refusées) sont identiques à celles de
     l'implémentation Android `AssetTemplateAssetsSource` ;
   - testable unitairement (port d'assets, combinaisons, pipeline complet sur
     un modèle *fixture* en répertoire temporaire).

2. **Le harnais déverse le plan figé de `PlanProjectCreationUseCase`** (ADR
   0017) plutôt que de réimplémenter une écriture : *le plan est ce qui est
   écrit*, à l'octet près — il n'existe qu'une seule définition de la
   génération.

3. **Le module reçoit une exemption detekt ciblée et documentée**
   (`config/detekt/detekt-sortie-console-autorisee.yml`) : sa sortie standard
   est le **résultat** de l'outil (protocole lu par le script), pas une
   journalisation — la règle 14 (tout trace passe par `AppLogger`) vise le
   code applicatif. Aucune autre vérification n'est desserrée.

4. **Choix des JDK proposés : 17 et 21 uniquement.** La version 25 (LTS la
   plus récente) a été testée **avant** d'être proposée : `jvmToolchain(25)`
   avec Kotlin 2.2.21 échoue (« Kotlin does not yet support 25 JDK target »,
   repli JVM_24 incompatible). Conformément à la règle « une JDK n'est
   proposée que si tout le projet généré compile et passe ses tests avec
   elle », elle n'est pas offerte. Le choix sera réévalué à chaque montée de
   version de la chaîne (procédure dans `docs/TEMPLATES.md`).

## Conséquences

- Le module n'est pas référencé par la table d'autorisation des dépendances
  du `ModuleRulesPlugin` (extensibilité) : il ne dépend que de
  `:core:domain` et de `kotlinx-serialization-json`.
- `verify-templates.sh` pilote tout : matrice des 18 combinaisons (source
  unique, générée en Python), invocation du harnais, vérifications
  structurelles (aucun `{{` résiduel, pas de BOM, LF/CRLF, checksum du
  `gradle-wrapper.jar`), builds réels par système de build, exécution des
  applications avec comparaison de sortie, publication des bibliothèques,
  tableau final.
- Les projets générés **sans wrapper** sont construits avec la distribution
  Gradle locale du poste (comme un utilisateur qui n'embarque pas le
  wrapper) ; les builds de vérification passent en `--no-daemon` pour
  rester dans le budget mémoire d'une machine à 2 CPU / 4 Go.
- Le `none`+Java est vérifié au `javac -Xlint:all -Werror` ; le `none`+Kotlin
  par copie dans un projet Gradle jetable (les deux options expressément
  permises par la section 11).
