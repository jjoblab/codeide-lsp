# ADR 0031 — Reconnaissance du type de projet à l'ouverture : lecture tolérante de `.codeide/project.json`

- **Statut** : accepté (étape 18)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et bibliothèque
  d'édition », section 6 (étape 18) — « lecture de `.codeide/project.json`
  à l'ouverture d'un dossier existant pour reconnaître le type de projet ».

## Contexte

Le registre ne connaît des dossiers **importés** que la sentinelle
`TemplateId.IMPORTED` (ADR 0015) : l'accueil et l'espace de travail
affichent au mieux « Dossier importé », jamais le **vrai modèle**. Or le
moteur de templates écrit déjà `.codeide/project.json` à la création
(étape 8 : `schemaVersion`, `templateId`, `templateVersion`, `generator`,
`parameters` — **sans donnée personnelle**). L'étape 18 demande d'exploiter
ce fichier pour afficher le type réel à l'ouverture — y compris pour un
projet importé — sans en faire une précondition de l'édition : un fichier
absent ou illisible ne doit jamais empêcher d'ouvrir un dossier.

## Décisions

1. **Un cas d'usage de lecture, tolérant par construction.**
   `ReconnaitreTypeProjetUseCase` (core:domain) lit le fichier via le port
   `FileSystem` et retourne `null` pour toute anomalie : pas de dossier
   `.codeide`, fichier absent, illisible, corrompu, schéma plus récent que
   celui connu, identifiant blanc. Aucune erreur remonte, aucun blocage —
   même philosophie que `LireEtatEspaceUseCase` (étape 17). Une exemption
   detekt `ReturnCount` ciblée et commentée couvre cette cascade de
   renvois, contrat même du cas d'usage.
2. **Une projection de lecture indépendante du DTO d'écriture.**
   `MetadataLue` (privée, kotlinx.serialization) ne retient que
   `schemaVersion`/`templateId`/`templateVersion`/`generator` et **ignore
   les clés inconnues** : le schéma du fichier est écrit par le
   `TemplateEngine`, lu ici, et l'alignement est garanti par la constante
   partagée `TemplateEngine.SCHEMA_METADATA`. Un fichier produit par une
   version future (schéma supérieur) est ignoré, pas rejeté.
3. **Le nom affichable est résolu par le catalogue, avec repli.** Le
   ViewModel interroge `ListTemplatesUseCase` dans la langue de
   l'application (i18n du moteur — « Modèle Kotlin · JVM ») et retombe sur
   l'**identifiant brut** si le catalogue ne connaît plus le modèle. La
   langue est précisée par l'action `PreciserLangue` à chaque création
   d'activité (langue par application, ADR 0013) : un changement de langue
   re-résout le nom sans relire le fichier.
4. **L'interface distingue trois cas, l'état n'en porte qu'un.**
   `EtatEditor.typeProjet` (`TypeProjetAffiche` : nom + version, ou
   `null`) suffit : reconnu → « Modèle X · v1.0.0 » ; `null` + sentinelle
   `IMPORTED` → « Dossier importé » ; `null` + projet créé → « Type de
   projet non reconnu » (fichier disparu ou illisible). La ligne est
   masquée pendant la vérification d'accès et en cas de panne.
5. **Régression du layout de l'espace de travail.** Le correctif v0.19.0
   du plantage d'ouverture (menu inline, rapport 8b5b73f1) a motivé un
   test Robolectric qui gonfle le **vrai** `activity_editor.xml` sous le
   thème de l'application — aucun élément non-Vue ne peut plus s'y glisser
   sans faire échouer la vérification.

## Conséquences

- La racine est énumérée une fois de plus à l'ouverture (recherche de
  `.codeide`) : le décompte de paresse de l'explorateur est documenté dans
  les tests (trois listages initiaux : arborescence, reprise d'espace,
  reconnaissance) — coût borné, cohérent avec l'étape 17.
- Le routage des actions du ViewModel est scindé en `onActionOnglets`
  (sélection, fermeture, enregistrement des onglets) pour rester sous le
  seuil detekt de complexité cyclomatique.
- `.codeide/project.json` reste sans donnée personnelle (contrat étape 8) :
  la reconnaissance n'expose rien de plus que le modèle déclaré.
