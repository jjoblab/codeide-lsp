# ADR 0027 — Explorateur de fichiers : arborescence paresseuse et bandeau d'accès dans le tiroir

- **Statut** : accepté (étape 14)
- **Date** : 2026-09-23
- **Contexte** : prompt compagnon « EditorActivity, GitHub et
  bibliothèque d'édition », section 5.3 — explorateur de fichiers
  (tiroir de navigation).

## Contexte

Le tiroir gauche de l'espace de travail doit présenter l'arborescence du
projet : énumération au dépliement (jamais tout l'arbre d'un coup — un
projet Gradle complet compte des centaines de fichiers), tri stable
dossiers puis fichiers puis alphabétique, icônes par extension, gestion
des erreurs d'accès (`ProjectAccessState`), et une barre de navigation
basse à trois destinations dont une seule est active. Deux bugs
d'appareil réel sont corrigés dans la foulée (SAF et finalisation de
l'assistant — voir CHANGELOG 0.15.0).

## Décisions

1. **Arborescence paresseuse, cache dans le `EditorViewModel`.** Chaque
   dossier n'énumère ses enfants (`FileSystem.list`, requête groupée
   SAF) qu'à son **premier dépliement** ; le résultat vit dans une
   carte privée du ViewModel pour toute la vie de l'écran — refermer
   puis rouvrir un dossier ne re-questionne pas le stockage. L'état
   observable (`EtatEditor.noeuds`) est la **liste aplatie des nœuds
   visibles**, reconstruite du cache à chaque mutation : le
   `RecyclerView` ne fait que diffuser cette liste (`ListAdapter` +
   `DiffUtil` sur l'URI).
2. **Tri au moment du cache.** Dossiers d'abord, puis fichiers, puis
   nom insensible à la casse — appliqué quand le résultat d'une
   énumération entre en cache, jamais à l'affichage : la liste
   aplatie est déjà dans l'ordre, le tri n'est pas re-testé à chaque
   reconstruction.
3. **Erreurs d'accès : deux niveaux, un bandeau.** Une permission
   perdue ou un dossier racine introuvable fait disparaître
   l'arborescence au profit d'un **bandeau dans le tiroir**
   (`ProjectAccessState`, réutilisé tel quel) : l'action « Résoudre à
   l'accueil » referme l'espace de travail — la résolution réelle
   (Relocaliser / Retirer) vit sur la carte du projet à l'accueil, où
   l'utilisateur est ramené. Une erreur passagère d'un dossier **non
   racine** (supprimé entre deux énumérations, E/S) est signalée par
   sa ligne : replié et marqué, un nouvel appui **réessaie**
   l'énumération au lieu de le replier.
4. **Le bouton Actualiser de l'en-tête du tiroir** revérifie l'état
   d'accès **puis** recharge l'arborescence : cache, dépliements et
   erreurs sont oubliés. La vérification d'accès initiale suit le
   même chemin — un seul code, deux déclencheurs.
5. **Barre de navigation basse du tiroir.** `BottomNavigationView`
   (rendu Material3 sous le thème de l'application — la classe
   `NavigationBar` n'existe pas dans Material 1.14) : Explorateur
   active, Recherche et Git visibles mais désactivées, avec la mention
   « Bientôt disponible » en `contentDescription`. Un appui sur une
   destination active ne change que le contenu central — le tiroir
   reste ouvert.
6. **Icônes par extension dans `core:ui`.** Ressources vectorielles
   maison (badges colorés : Kotlin, Java, Gradle, XML, Markdown, JSON,
   dossier, fichier générique) et mapping `IconesFichiers` — le repli
   générique ne bloque jamais l'affichage d'une ligne. Les couleurs
   sont fixes (lisibles clair/sombre) : ces icônes ne sont pas teintées
   par le thème, contrairement aux icônes de la barre basse.
7. **Pas d'ouverture de fichier à cette étape.** Un appui sur un
   fichier n'a volontairement aucun effet : l'ouverture en onglet
   arrive à l'étape 15 (prompt compagnon 5.3) — la ligne n'offre
   aucun retour d'appui tant qu'il n'y a rien à ouvrir.

## Conséquences

- La relocalisation du projet (URI de document changée) ou sa
  suppression réinitialise cache, dépliements et bandeau : l'écran
  suit le registre sans rechargement manuel.
- Un dossier supprimé après énumération reste visible jusqu'au
  dépliement (vue potentiellement périmée) : c'est le dépliement qui
  révèle, honnêtement, la disparition — l'appui réessaie.
- L'accessibilité : lignes ≥ 48 dp, `contentDescription` complet par
  nœud (nom, type, profondeur, état de pli ou d'échec), destinations
  de la barre basse ≥ 48 dp avec description y compris l'état désactivé.
- Le compteur d'appels `FakeFileSystem.appelsList` (core:testing)
  verrouille le contrat « une seule énumération par dossier » en test.
