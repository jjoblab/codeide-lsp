# ADR 0209 — Définition et définition de type Java CROSS-FICHIERS

- **Statut** : appliqué (24/10/2026 — audit du panneau GO TO du LSP Java
  (caractérisation `AuditNavigationJavaTest`, 15 scénarios façade) ; moteur +
  façade + tests).
- **Contexte** : le panneau GO TO du LSP Java était le seul NON SYMÉTRIQUE du
  dépôt — `implementation`/`superDefinition`/`references` balayaient déjà les
  racines sources (pattern renommage), mais `definition` et `typeDefinition`
  étaient confinés au FICHIER COURANT (`EcjCompilerEngine.findDefinition`/
  `findTypeDefinition` : « dans CE fichier ») : cliquer sur un symbole déclaré
  dans un AUTRE fichier du projet rendait une liste VIDE (le fallback binaire
  `isBinaryType` refusant à juste titre les types projet), quand Android Studio
  et le LSP Kotlin sautent à la déclaration. L'infrastructure manquante était
  pourtant LÀ : `locateTypeDeclaration` (convention de chemin + parse, overlay
  des tampons ouverts), `methodNameRange`, `nameRange` — le moteur de
  navigation ne les exposait que pour les hiérarchies.
  L'audit a aussi découvert DEUX bugs latents sur le chemin existant :
  (a) **référence qualifiée** — la convention ecj porte le binding du PREMIER
  segment dans `QualifiedNameReference.binding` (le récepteur `a` de `a.tag`),
  le champ résolu vivant dans `otherBindings` : `findDefinition` sur un champ
  qualifié sautait au RÉCEPTEUR (le paramètre local) — le moteur de RENOMMAGE
  avait déjà la preuve (`lastSegmentBinding`) mais hover/définition ne
  l'exploitaient pas ; (b) **localisation par convention seule** — un type ne
  portant pas le nom de son fichier (plusieurs types top-level, classe non
  publique) échouait à `locateTypeDeclaration`.
- **Options considérées** :
  1. **Chemin projet par bindings + localisation** — un nouveau
     `EcjNavigationEngine.findDefinitionProject` : le binding ecj au caret
     prouve la cible (méthode/champ/type, jamais un nom deviné), la classe
     DÉCLARANTE est localisée par `locateTypeDeclaration`, le NOM y est borné
     au jeton. Branché dans la façade ENTRE l'intra-fichier et le fallback
     binaire — une cible PROJET ne doit JAMAIS atterrir dans le stub décompilé.
  2. **Étendre le balayage complet à la définition** — le pattern
     `findImplementations` (balayage de 400 fichiers par requête) : couvre tout
     mais coûte un balayage complet là où le BINDING désigne déjà le déclarant
     — le balayage n'est légitime qu'en REPLI de la convention de chemin.
  3. **Statu quo** — la définition cross-fichier est le geste n° 1 d'un éditeur
     (avant Super et Implémentations) : non recevable.
- **Décision** : retenir l'**option 1**, avec les deux correctifs d'audit :
  (a) `EcjAst.bindingAuCaret(QNR, offset)` — le binding du SEGMENT visé par le
  caret (`sourcePositions`, plage par maillon ; caret sur le récepteur → la
  locale, sur le champ → le champ, sur le point → le dernier chaînon),
  politique UNIFIÉE avec le renommage (`lastSegmentBinding` délègue) ;
  branchée dans `scanUnit` (hover/définition intra) et `resolveContext`
  (navigation) ; (b) `locateTypeDeclaration` gagne un GUARD binaire
  (`isBinaryType` — évite un balayage complet pour un supertype du classpath)
  et un REPLI balayage BORNÉ (pré-filtre textuel sur le nom simple, budget
  `MAX_WALK_FILES`/`WALK_BUDGET_MS`) — le repli profite AUSSI à
  `findSuper`/hiérarchies. `findTypeDefinitionProject` suit (type du symbole :
  référence de type, type de variable, type de retour — miroir
  `typeBindingOf`).
- **Conséquences** :
  - `definition` : méthode/champ/constructeur/type d'un AUTRE fichier du
    projet → Location dans le fichier DÉCLARANT (plage du nom) ; homonymes
    départagés PAR BINDINGS (deux `run` dans deux classes : chaque invocation
    vise SON déclarant, l'arité départage les surcharges) ; import STATIQUE
    résolu ; locale toujours INTRA-fichier (elle y vit).
  - `typeDefinition` : le TYPE d'une variable/d'un retour d'appel d'un autre
    fichier projet → sa déclaration ; `String` reste décompilé
    (`jdt://decompiled/java.lang.String`) — les DEUX régimes coexistent sans
    fuite (test d'ordre : projet AVANT binaire, binaire quand pas projet).
  - Garde honnête : un type non conforme au nom de fichier que l'ENVIRONNEMENT
    de noms ecj ne résout PAS reste hors d'atteinte (`new PorteurUn()` où
    `PorteurUn` vit dans un fichier d'un autre nom → binding invalide) — borne
    PRÉEXISTANTE du name env source (convention de chemin), consignée, hors
    périmètre.
  - Tests : `AuditNavigationJavaTest` (15 scénarios façade bout-en-bout :
    méthode/méthode héritée/champ/type/constructeur/import statique/
    homonymes/locale/binaire×2/typeDefinition×2/super×2/ordre/robustesse) ;
    les garde-fous existants (navigation, rename, hover) restent verts — le
    correctif QNR corrige AUSSI le hover d'un champ qualifié (`a.tag` montre
    désormais le champ, plus la locale).
