# ADR 0106 — Historique local : capture au port FileSystem + détection externe au plus juste

- Statut : accepté (2026-10-10) — phase H0 de la mission « Historique
  local »
- Contexte : IntelliJ capte par **écouteurs du VFS** (BulkFileListener :
  événements AVANT écriture/suppression, renommages) et **commandes**
  (frontières de groupes), rafraîchit depuis le disque pour les
  changements externes. CodeIDE n'a pas de VFS d'écoute : le point de
  passage unique des écritures est le **port `FileSystem`**
  (`core:domain`), implémenté par `SafFileSystem` (projets) et
  `FileSystemPrive` (privé) — mais le terminal, git et le
  remplacement multi-fichiers de la recherche écrivent **directement
  en FUSE** (même UID, ADR 0038), en contournant le port.

## Décision

### (a) Décorateur du port — capture TRANSPARENTE

`HistoriqueFileSystem(delegue: FileSystem, moteur: HistoriqueLocal,
source: SourceProjetHistorique, arborescences: ArborescencesSaf)` :
implémente le port en déléguant TOUT, et enregistre une entrée pour
chaque opération qui modifie :

| Opération | Entrée | Contenu AVANT |
|---|---|---|
| `writeText` / `writeBytes` | MODIFICATION (ou CRÉATION si lecture vide) | lu via le délégué AVANT l'écriture |
| `createFile` | CRÉATION | — |
| `delete` (fichier) | SUPPRESSION (pierre tombale) | lu AVANT la suppression |
| `rename` | RENOMMAGE (+ ancien nom) | lu après (le nouveau) — même empreinte, blob DÉDUPLIQUÉ |
| `createDirectory` / lectures / permissions | — | aucune entrée |

La capture est **silencieuse par contrat** : un échec d'enregistrement
n'échoue JAMAIS l'opération du délégué (l'historique est un filet, pas
une dépendance) ; il est journalisé. Aucun appelant ne change — le
décorateur remplace la liaison `FileSystem` dans `StorageBindsModule`
(`core:storage`), l'arbre PRIVÉ reste non décoré.

### (b) Le projet courant désigne la cible

`SourceProjetHistorique` : singleton à `@Volatile` mis à jour par
l'espace d'édition à l'ouverture du projet (URI de document racine).
Le décorateur résout le **chemin relatif** en décodant l'URI via
`ArborescencesSaf` (identifiant de document « volume:chemin/relatif »)
et le compare à la racine courante — hors racine : aucune capture (les
autres projets et l'arbre privé ne sont pas capturés par le projet
ouvert ; couverture documentée : les écritures de l'application passent
par l'espace du projet ouvert). La clé de stockage est l'empreinte de
la racine — stable entre sessions.

### (c) Modifictions externes : détection AU PLUS JUSTE, jamais de scrutation

Trois moments, aucun balayage permanent (batterie) :

1. **à l'ouverture d'un fichier** dans l'éditeur : le contenu lu est
   comparé à la dernière révision du même chemin — écart → entrée
   EXTERNE (« Changement externe », contenu AVANT = dernière révision
   connue, déjà stockée — aucune lecture supplémentaire) ;
2. **au rafraîchissement de l'explorateur** (surveillance d'arbre
   existante, période 4 s) : un horodatage/une taille modifiée sur un
   fichier CONNU de l'historique déclenche la comparaison (v1 H1 :
   prévu par le port, branché par H2/H3 sur la sonde existante) ;
3. **au retour au premier plan** : idem, borné aux fichiers ouverts.

Le terminal et git restent **hors capture directe** en v1 (écriture
FUSE) : leurs effets apparaissent par (1)/(2) — c'est exactement le
modèle IntelliJ « Changement externe », sans coût permanent. Divergence
assumée, affichée dans la spec.

### (d) Étiquettes : point d'extension sans couplage

`HistoriqueLocal.etiqueter(nom, chemin?)` crée une entrée
d'étiquette (utilisateur). Un petit `EtiqueteurHistorique` (fonction
du domaine, appelable avant une action risquée) permet aux futures
fonctions (compilation, Git, remplacement multi-fichiers) de poser
« Avant compilation », « Avant bascule de branche » SANS dépendre du
module historique — elles appellent le cas d'usage du domaine, pas
l'inverse (H4).

## Conséquences

- Le décorateur est testé en JVM pur avec `FakeFileSystem` +
  `FakeArborescencesSaf` et un moteur réel sur dossier temporaire :
  capture de toutes les mutations, silenciosité sur échec, exclusions
  (secrets, build/), pas d'entrée à contenu identique.
- La restauration (H2) écrit via le PORT → le décorateur crée
  AUTOMATIQUEMENT la révision « avant restauration » : l'opération est
  annulable par construction (modèle IntelliJ du revert-annulable,
  sans undo provider dédié en v1).
