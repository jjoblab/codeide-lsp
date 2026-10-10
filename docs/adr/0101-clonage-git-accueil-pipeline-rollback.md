# ADR 0101 — Clonage Git depuis l'accueil : pipeline à rollback

- Statut : accepté (2026-10-10)
- Contexte : retour utilisateur — « ajouter dans l'écran home un bouton
  git près du bouton terminal pour pouvoir cloner des dépôts » ;
  recherche d'interface : « Get from VCS » d'Android Studio.

## Décision

Le clonage est un **cas d'usage du domaine** (`ClonerDepotUseCase`,
`core:domain`) branché sur un bouton icône de l'accueil
(`feature:home`), pas une fonctionnalité du tiroir Git : c'est un point
d'entrée de PROJET (« je récupère du code »), comme le wizard et
l'import de dossier — l'accueil est la porte.

### Pipeline (même esprit que `CreateProjectUseCase`)

1. dossier de travail depuis les Paramètres — non configuré : échec
   explicite (jamais de destination libre en v1 : le clone vit sous
   l'arbre dont l'app détient déjà la permission, ADR 0015) ;
2. nom du dossier cible : validateur partagé `file-name`
   (`EvaluerNomFichierUseCase`) — une seule source de vérité avec le
   wizard ; l'UI pré-remplit depuis l'URL (dernier segment sans
   `.git`) et ne récrase pas une saisie manuelle ;
3. cible vérifiée (`VerifyCreationTargetUseCase`) : joignable + aucun
   homonyme (SAF refuse les collisions) ;
4. dossier créé via SAF (`FileSystem.createDirectory`) — l'URI
   retournée fait loi ;
5. `git clone` via le port `MoteurGit` sur le chemin FUSE du dossier ;
6. registre en dernier (`ProjectRepository.addProject`,
   `TemplateId.IMPORTED`).

**Rollback honnête** : tout échec APRÈS l'étape 4 supprime le dossier
(`FileSystem.delete`) et le résultat typé (`ResultatClonage`) porte
l'issue du nettoyage — `rollback = false` (verrou, volume parti) est
affiché en suffixe : un résidu n'est jamais silencieux. Le message brut
de git (stderr — réseau, authentification, dépôt introuvable) est
affiché tel quel : c'est lui qui dit vrai.

Succès : projet marqué ouvert + éditeur ouvert — comme Android Studio
qui charge le projet fraîchement cloné (« Trust and open »).

### Nouveau port `ResolveurCheminFuse`

Le pont « URI de document → chemin FUSE » existant
(`ResoudreRepertoireProjet`) est un cas d'usage CONCRET final (classe
finale, garde « répertoire fantôme » via `File.isDirectory()` —
impossible à satisfaire en JVM de test). Le clonage dépend du PORT
minimal (`fun interface ResolveurCheminFuse`, `core:domain`) :
- production : `@Binds ResoudreRepertoireProjet → ResolveurCheminFuse`
  (`app/di/DomainBindingsModule`) — la garde est incluse ;
- tests JVM : résolveur factice déterministe.

Même motif que les autres ports du domaine (une interface pure, une
liaison dans `app`), l'API publique de `ResoudreRepertoireProjet` est
inchangée.

## Justification

- **Cohérence** : git CLI via `MoteurGit` (ADR 0092), SAF pour le
  disque, registre en dernier — aucun mécanisme nouveau.
- **Testabilité** : pipeline entièrement rejouable en JVM avec les
  fakes (`FakeMoteurGit` rejoint `core:testing`, prévu par l'ADR
  0092) ; 9 tests cas d'usage + 4 tests ViewModel (progression,
  ouverture, échecs, rollback, résidu signalé).
- **Honnêteté** : pas d'état intermédiaire caché — le bandeau de
  progression est indéterminé (la progression fine suivra avec le
  parsing de stderr), les erreurs sont typées et actionnables.

## Alternatives écartées

- **JGit embarqué** : écarté pour les mêmes raisons qu'en ADR 0092
  (+5-8 Mo APK, double moteur).
- **Clonage dans le stockage privé de l'app** : contredirait ADR 0003
  (SAF, projets visibles/partageables) ; le dossier de travail est LA
  destination des projets.
- **Destination libre (sélecteur SAF par clone)** : v1 volontairement
  limitée — la permission du dossier de travail suffit ; l'option
  suivra si le besoin apparaît (même évolution que le wizard).

## Conséquences

- `feature:home` gagne le bouton + dialogue + état `clonageEnCours` ;
  les échecs typés (`NomClonageRefuse`, `EchecClonage`,
  `EchecClonageGit`) sont traduits en snackbar (le rollback échoué
  est suffixé).
- Authentification privée (PAT HTTPS) : HORS v1 — l'utilisateur clone
  les dépôts publics ou règle ses credentials dans le terminal
  (`~/.git-credentials`), même garde qu'ADR 0092. Le clone
  superficiel (`--depth`) suivra.
- `git` doit être installé (`pkg install git`, ADR 0092) — l'échec
  explicite du moteur l'affiche déjà.

## Références

- `core/domain/src/main/kotlin/jo/codeide/core/domain/ClonerDepotUseCase.kt`
- `core/domain/src/main/kotlin/jo/codeide/core/domain/ResoudreRepertoireProjet.kt` (port)
- `feature/home/src/main/kotlin/jo/codeide/feature/home/HomeFragment.kt` (dialogue)
- ADR 0092 (moteur git CLI), ADR 0095 (tiroir Projet), ADR 0003 (SAF)
