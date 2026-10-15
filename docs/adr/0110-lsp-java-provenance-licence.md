# ADR 0110 — D3 : provenance du module lsp-java et licence (décision du propriétaire requise)

- **Statut** : **ACCEPTÉ PARTIELLEMENT — décision ciblée du propriétaire
  (16/10/2026)** — voir « Décision du propriétaire » plus bas ; le
  protocole de mesure appareil (second volet du S0-c) reste en attente.
  (v0.91.0, étape 0 — prompt 2 §3 D3)
- **Contexte** : le prompt 2 annonce un audit de provenance fourni
  (`audit-provenance-lsp-java.md`). **Ce fichier est ABSENT de
  l'archive livrée** (137 fichiers, aucun audit) — l'audit a donc été
  RECONSTITUÉ intégralement pour cette étape (méthode et mesures dans
  `docs/LSP_JAVA.md §5`). projet de référence (auteur tiers) est sous GPL-3.0
  (seuls `plugin-api` et `platform-core` ont l'exception de linkage) ;
  le `README` de CodeIDE revendique « tous droits réservés pour
  l'instant ».

## Mesures reconstituées (résumé)

- **Niveau A — copies quasi littérales, CONFIRMÉ** :
  `compat/RuntimeVersion.java` (69 % de lignes identiques normalisées,
  Jaccard de jetons 0,93), `compat/StackWalker.java` (40 %, 0,89),
  `compat/InputStreamCompat.java` (60 %, 0,57 — sous-ensemble). L'audit
  absent annonçait « 61/61 lignes » pour RuntimeVersion : ma méthode
  (lignes non vides, trim) donne 69 % — cohérent, écart de méthode.
- **Niveau B — ~31 fichiers « portage » déclarés, ressemblance
  textuelle FAIBLE** (0,08–0,26 de lignes identiques, Jaccard
  0,01–0,46) **mais dérivation confirmée** : commentaires citant les
  classes d'origine, et **constantes numériques identiques** entre
  `CompletionRanker.kt` (projet de référence) et `JdtCompletionEngine` (1000,
  500, 200, 120, 300, 200, 400 — bonus/penalités de score).
- **`ecj-art`** : l'approche est déclarée reprise du projet de référence et les
  passes d'origine existent dans son build-logic
  (`RelocateTypesInJar.kt`, `EclipseStreamArtPass.kt`) ; recoupement
  textuel faible (dicté par l'API ASM).
- **Six noms de fichiers identiques** à projet de référence : `JdtCodeFolder`,
  `JdtSemanticHighlighter`, `JdtSourceUnit`, `RuntimeVersion`,
  `StackWalker`, `InputStreamCompat` — le renommage (docs/LSP_JAVA.md
  §7) s'ajoute au traitement du contenu ; renommer ne résout rien seul.
- **Nuance trouvée** : `JdtLocateCache` (LRU exact par
  `removeEldestEntry`) n'est PAS la `LruMap` du projet de référence (CLOCK,
  second chance) — même idée d'index borné, implémentations distinctes.

## Options soumises au propriétaire (ne pas trancher seul)

1. **Réécriture cloisonnée** (recommandée par l'ingénierie) : réécrire
   les niveaux A et B depuis des spécifications de comportement séparées
   (écrites sans lire le code tiers), constantes redéterminées par
   mesure, ordre du plan par fichier (docs/LSP_JAVA.md §5.3). Coût :
   le plus élevé en ingénierie ; bénéfice : statut de propriété propre.
2. **Autorisation de l'auteur** : demander à auteur tiers une
   autorisation écrite (MIT/Apache sur les fichiers concernés, ou
   dérogation). Coût : une démarche ; bénéfice : conservation du code ;
   risque : refus ou silence.
3. **Conformité GPL** : assumer GPL-3.0 pour les fichiers dérivés (et
   donc publier leurs sources — fait déjà — mais GPL est
   **contaminante** pour l'ensemble lié). Incompatible avec « tous
   droits réservés » actuel ; non recommandé.
4. **Remplacement complet du moteur** : jeter les ~20 k lignes et
   repartir de zéro sur ecj. Coût maximal ; écarté sauf refus des
   options 1–2.

## Décisions d'ingénierie prises (dans l'attente du choix du propriétaire)

- **Aucune publication en bibliothèque indépendante** avant règlement
  (reprise de la décision différée du prompt : « aucune publication
  avant le règlement du point licence »).
- **Aucun nouveau copiage/traduction** du projet de référence : les idées
  architecturales (segments par jar, snapshots immuables, négatifs
  mémorisés) s'inspirent de la DOCUMENTATION d'architecture, jamais du
  code ; les nouvelles couches (`lsp:model`, `lsp:index`, `lsp:host`)
  sont écrites à partir du présent ADR et de docs/LSP_JAVA.md.
- Le plan de renommage (§7 de l'étude) sera exécuté APRÈS le traitement
  des contenus, lot par lot, compilation et tests verts à chaque lot.

## Conséquences

- Tant que l'option 1 ou 2 n'est pas actée, le module reste vendored
  privé, non publié, non extrait (voir étude d'extractibilité, §8).
- Les trois shims du niveau A seront réécrits en priorité (petits,
  spécification publique du JDK suffit).

## Décision du propriétaire (16/10/2026 — S0-c, communiqué direct)

Sur la question posée (« réécriture de certains fichiers — laquelle ? »),
le propriétaire tranche :

> « Oui, je suis pour la réécriture de certaines classes (fichiers) mais
> pas tous. La quasi-totalité sont mes fichiers ; c'est simplement lors
> des tests j'ai eu des erreurs, alors dans mes recherches pour les
> solutionner j'ai référencé projet de référence seulement pour me rappeler, parmi
> les recherches de solutions dans divers autres projets, que c'était
> leur approche que j'ai utilisé — pas au mot près, mais la compréhension
> pour le faire à ma manière. »

Décision actée (trace de ce PV : `docs/LSP_JAVA.md §18.2`) :

1. **Option 1 (réécriture cloisonnée) retenue de façon CIBLÉE** : ne
   s'applique qu'aux fichiers dont la dérivation est objectivement
   établie par l'audit reconstitué — le **niveau A** (les trois shims
   `compat/RuntimeVersion.java`, `compat/StackWalker.java`,
   `compat/InputStreamCompat.java`, 40-69 % de lignes identiques
   normalisées). La réécriture se fera depuis la spécification publique
   du JDK (lot G réduit à ces trois fichiers, planifié).
2. **Le niveau B reste en l'état** : ressemblance textuelle faible
   (0,08-0,26 de lignes identiques, Jaccard 0,01-0,46) et assertion
   d'auteur du propriétaire (« la quasi-totalité sont mes fichiers »,
   référence consultée « pas au mot près ») — pas de réécriture, pas de
   lot G complet.
3. **Les conséquences de statut restent inchangées tant que la réécriture
   niveau A n'est pas faite** : module vendored privé, aucune publication
   en bibliothèque indépendante, aucun nouveau copiage du projet de référence.
