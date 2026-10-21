# Mise à jour de CodeIDE — notes de passage des versions de codeide-lsp

> Ce document s'adresse à l'agent/propriétaire de CodeIDE : chaque section
> décrit ce qu'une version de codeide-lsp apporte et ce que CodeIDE doit
> faire pour en profiter. Les versions sont publiées par
> `scripts/bump-frere.sh lsp minor` DEPUIS CodeIDE (jamais depuis ce dépôt).

## 1.3.0

### 1. État

- `main` de codeide-lsp : **5d488b5** (à la rédaction — voir le SHA réel au
  moment de la publication), CI **verte** sur `main` (chaque lot poussé a
  été validé par un run CI vert ; l'historique des runs est public).
- **Non publié** : lancer `scripts/bump-frere.sh lsp minor` **depuis
  CodeIDE** (version attendue : **1.3.0** — `version.properties` reste à
  1.2.0, le bump est l'affaire du script frère). Le dépôt est laissé
  PROPRE : tout commité, `main` à jour, CI verte.

### 2. Capacités par langage (avant → après)

Les interrupteurs de l'écran « Intelligence du code » sont alimentés par la
table `CapacitesAnnoncees` (dérivée des `ServerCapabilities` réelles,
parité verrouillée par test) :

| Interrupteur (`CapaciteLsp`) | Java | Kotlin | XML |
|---|---|---|---|
| COMPLETION, SURVOL, DIAGNOSTICS, DEFINITION, RENOMMAGE, ACTIONS_RAPIDES | ✅ (inchangé) | ✅ (inchangé) | ✅ (inchangé) |
| SIGNATURES | ✅ | ✅ | ❌ (inchangé) |
| REFERENCES | ✅ | ✅ | ✅ **NOUVEAU 1.3.0** |
| FORMATAGE | ✅ | ✅ **NOUVEAU 1.3.0** | ✅ (inchangé) |
| INDICES_EN_LIGNE | ✅ | ✅ **NOUVEAU 1.3.0** | ❌ |

Capacités NON réglables (annonces serveur pures, sans interrupteur) :

| Capacité | Java | Kotlin | XML |
|---|---|---|---|
| Symboles de document, pliage, surlignage, prepareRename | ✅ | ✅ | ✅ |
| Définition de type, implémentations | ✅ | ✅ **NOUVEAU** | ❌ |
| Formatage de plage | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Symboles d'espace (`workspace/symbol`) | ✅ | ✅ **NOUVEAU** | ❌ |
| Plage de sélection | ✅ | ✅ **NOUVEAU** | ✅ **NOUVEAU** |
| Édition liée (balises) | ✅ | ❌ | ✅ **NOUVEAU** |
| Couleurs de document | ✅ | ❌ | ✅ **NOUVEAU** |
| Formatage à la frappe, lentilles, hiérarchies d'appels/types | ✅ | ❌ | ❌ |
| Jetons sémantiques | ✅ | ❌ (non annoncé — D3) | ❌ |
| Super-définition, source décompilée (expérimentales) | ✅ | ❌ | ❌ |

### 3. Changements de comportement visibles

- **Diagnostics Kotlin/XML rafraîchis sur modification externe** : un
  fichier `.kt`/`.kts`/`.xml` modifié hors éditeur (build Gradle,
  terminal) rafraîchit les diagnostics des documents OUVERTS de ces
  langages (lot L1) — auparavant, seul Java en bénéficiait ; le
  rafraîchissement n'attendait plus que l'enregistrement.
- **Kotlin** : « Formater le document » ré-indente (ré-indentation seule,
  ADR 0202 — le contenu n'est jamais réécrit) ; de nouveaux indices en
  ligne (noms de paramètres, types certains) apparaissent si l'éditeur
  les rend ; la palette « aller au symbole » et l'expansion de sélection
  deviennent disponibles côté serveur.
- **XML** : « Trouver les références » sur `@id/…`, `@string/…`, etc.
  fonctionne (déclarations + usages) ; le surlignage d'occurrences
  couvre les ressources ; couleurs et édition liée prêtes côté serveur.
- **Aucune capacité n'a cessé d'être annoncée** (L4 n'a rien retiré —
  aucun stub trouvé côté Java).

### 4. API/contrat modifiés

- **Aucune rupture** attendue : `ConfigurationLsp`, `SessionLsp*` et
  `CapaciteLsp` gardent leurs signatures.
- AUCUNE entrée d'énumération `CapaciteLsp` n'a été AJOUTÉE en 1.3.0 :
  `FORMATAGE`, `INDICES_EN_LIGNE` et `REFERENCES` existaient déjà —
  seules les TABLES par langage (`CAPACITES_KOTLIN`, `CAPACITES_XML`)
  gagnent des entrées. **Le miroir `core:domain` de CodeIDE n'a RIEN à
  mettre à jour pour l'énumération** ; en revanche, les écrans de
  réglages afficheront trois NOUVELLES lignes d'interrupteur effectives
  (REFERENCES pour XML, FORMATAGE et INDICES_EN_LIGNE pour Kotlin) sans
  changement de code si la liste est dérivée de `capacites(langage)`
  (elle l'est — LSP_REVUE_REGLAGES du lot LSP-1).
- Moteurs Kotlin/XML : le contrat de session `SessionLangageKotlin`/
  `SessionLangageXml` gagne des méthodes AVEC DÉFAUTS HONNÊTES VIDES
  (formatage, navigation de types, indices, symboles d'espace, sélections
  / références, surlignage, plage liée, couleurs) — un pack 1.2.0 face à
  un hôte 1.3.0 dégrade proprement (tolérance de version D6, aucun crash).

### 5. Ce que CodeIDE doit faire

1. **Monter codeide-lsp dans le catalogue** : `scripts/bump-frere.sh lsp
   minor` (calcule 1.3.0, commit, tag `v1.3.0`, push, attend
   `publish.yml`, vérifie le registre, met à jour le catalogue).
2. **Vérifier les écrans de réglages LSP** : trois nouvelles lignes
   d'interrupteur effectives (voir tableau §2) — vérifier libellés et
   état par défaut.
3. **Ajuster les textes « non pris en charge »** pour Kotlin/XML : le
   formatage Kotlin, les indices Kotlin et les références XML sont
   désormais servis — tout texte statique qui dit le contraire doit
   être retiré.
4. **Exécuter les scénarios d'appareil** (§8 ci-dessous).

### 6. Dépendances avec les autres agents

- **code-editor 3.74.0** (agent 10-a) : le formatage de PLAGE n'est
  consommé qu'une fois 3.74.0 livrée et montée (le serveur la sert dès
  1.3.0 pour les trois langages). Sans 3.74.0 : le formatage du document
  ENTIER fonctionne déjà.
- **Utilisable dès maintenant avec le client actuel** : formatage Kotlin
  du document entier, références/surlignage XML, indices Kotlin (le
  client les consomme déjà pour Java), rafraîchissement des diagnostics
  sur modification externe.
- **Phase 2 CodeIDE** : les capacités listées dans
  `docs/LSP_ECARTS_CLIENT.md` (palette, sélection, couleurs, édition
  liée, lentilles, hiérarchies, formatage à la frappe, méthodes
  expérimentales) attendent leur consommation client — chaque fiche
  décrit l'API minimale à ajouter.

### 7. Capacités en service côté serveur mais hors service côté éditeur

Voir **[docs/LSP_ECARTS_CLIENT.md](LSP_ECARTS_CLIENT.md)** : formatage de
plage (jusqu'à 3.74.0), formatage à la frappe, plage de sélection, palette
`workspace/symbol`, lentilles de code, hiérarchies d'appels et de types,
couleurs de document XML, édition liée XML, super-définition et source
décompilée (expérimentales, Java). Chaque fiche : requête LSP, serveur,
effet utilisateur, API minimale côté client.

### 8. Scénarios d'appareil

1. **Modification hors éditeur** : ouvrir un `.kt` et un `.xml` dans
   l'éditeur ; depuis un terminal (ou un build Gradle), modifier le
   fichier sur disque ; les diagnostics des documents ouverts se
   rafraîchissent SANS enregistrement (les trois langages).
2. **Formater un `.kt`** : ouvrir un fichier Kotlin mal indenté (corps
   décalé, chaîne brute mal alignée) ; « Formater le document » → la
   ré-indentation s'applique, le contenu de la chaîne brute reste
   INTACT ; reformater ne change rien (idempotence).
3. **Références d'un `@id/…` XML** : ouvrir un layout, mettre le caret
   sur `@+id/valider` puis sur un `@id/valider` ; « Trouver les
   références » → la déclaration et les usages (y compris dans les
   autres documents ouverts) sont listés ; le surlignage couvre les
   occurrences du document courant.
4. **Changement rapide d'onglet Java → Kotlin → XML** : ouvrir les
   trois, basculer rapidement ; chaque langue répond (complétion,
   survol, diagnostics) sans latence perceptible ni fuite (le test de
   fuite de tâches est dans la suite).
5. **Indices Kotlin** : écrire `somme(1, 2)` sur une fonction
   `fun somme(a: Int, b: Int)` déclarée dans le projet ; les indices
   `a =`/`b =` s'affichent devant les arguments si l'éditeur rend les
   inlay hints (déjà consommés pour Java).
