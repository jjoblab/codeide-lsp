# ADR 0015 — Ouvrir un dossier existant : héritage de la permission du dossier de travail

- Date : 2026-09-22 (étape 7, v0.8.0)
- Statut : accepté

## Contexte

L'étape 7 introduit l'action « Ouvrir un dossier existant » (FAB de
l'accueil) : l'utilisateur choisit un dossier via le sélecteur SAF et
CodeIDE l'ajoute au registre des projets. Le sélecteur
`ACTION_OPEN_DOCUMENT_TREE` rend une **URI d'arborescence enracinée au
dossier choisi** (`…/tree/<id du dossier choisi>`), accompagnée d'une
permission persistable à prendre.

Or un choix très probable est un dossier **qui vit déjà dans l'arbre du
dossier de travail** (l'utilisateur y a placé ses projets). Prendre une
permission supplémentaire sur un sous-arbre d'une permission déjà tenue
pose deux problèmes :

1. **Gaspillage** — le système plafonne les permissions persistantes
   (512 sur Android 11+, section 5.6 du prompt maître) : « ne persister
   que le nécessaire ».
2. **Incohérence silencieuse** — la règle de l'étape 6 (« ne libérer
   l'ancienne permission du dossier de travail que si aucun projet n'en
   dépend ») compare les URI **de document** par préfixe. Une URI de
   document reconstruite depuis l'arborescence propre du sous-dossier
   (`…/tree/A%2FMonProjet/document/…`) ne partage pas le préfixe de
   l'arbre du dossier de travail (`…/tree/A/document/…`) : le projet ne
   serait pas vu comme dépendant, et la permission du dossier de travail
   serait libérée sous ses pieds.

Enfin, un projet importé n'a été généré par aucun modèle connu :
`.codeide/project.json`, qui permettra de reconnaître le type à
l'ouverture (étape 13), n'est pas encore lu.

## Décision

1. **Héritage de la permission.** `ImportExistingFolderUseCase` (et
   `RelocalizeProjectUseCase`, même parcours) résout la politique
   d'emplacement avant toute prise de permission :
   - le dossier choisi vit dans l'arbre du dossier de travail (racine
     comprise, comparaison sur les identifiants de document **décodés**) :
     **aucune permission propre** — le projet enregistre le
     `grantUri` du dossier de travail, et son `documentUri` est
     **réadressé dans cet arbre** via le port `ArborescencesSaf`
     (`uriDocumentDansArbre`) pour que la règle de libération
     conditionnelle sache le comparer ;
   - le dossier vit ailleurs : permission persistante propre, prise
     après le refus plateforme (dossiers interdits Android 11+), suivie
     d'un test d'écriture témoin — permission **relâchée à tout échec**
     et, en cas d'échec du registre (y compris « déjà présent »),
     uniquement si plus personne ne la référence.

2. **Sentinelle de modèle.** Les projets importés portent
   `TemplateId.IMPORTED` (`"imported"`) : l'accueil affiche la pastille
   générique « dossier », et l'étape 13 remplacera cette sentinelle par
   le vrai modèle lu dans `.codeide/project.json` quand il existe.

3. **Nom et description.** Le libellé initial du projet importé est le
   nom du dossier (repli : dernier segment de l'identifiant), la
   description est vide ; l'utilisateur peut renommer ensuite (ADR
   0012 : libellé en base uniquement).

## Conséquences

- `ArborescencesSaf` gagne une troisième décomposition
  (`uriDocumentDansArbre`), implémentée par `SafArborescences`
  (`DocumentsContract.buildDocumentUriUsingTree` ré-encode les segments)
  et par le fake de test.
- La validation du dossier d'import est identique à celle du dossier de
  travail (`testerEcriture` partagé) : refus plateforme, permission,
  témoin d'écriture — un comportement unique, testé une fois.
- « Retirer de la liste » d'un projet importé hors du dossier de
  travail libère sa permission (ADR 0016) ; dans l'arbre du dossier de
  travail, il n'y a jamais rien à libérer.
- Le nom des projets importés peut entrer en collision avec un projet
  existant : c'est un libellé, l'unicité du registre porte sur le
  dossier (index unique sur `document_uri`), pas sur le nom.
