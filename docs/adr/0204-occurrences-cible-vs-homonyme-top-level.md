# ADR 0204 — Occurrences : distinguer la cible du garde « homonyme top-level local »

- **Statut** : appliqué (24/10/2026 — audit E2E du serveur LSP Kotlin, découverte D2, scénarios S1–S3 ; mise en œuvre v1.6.0 : correctif moteur + tests, sondes S1–S3 du harnais verrouillées sur le comportement corrigé au fil du transport réel).
- **Contexte** : le substrat d'occurrences (`OccurrencesKotlin`) alimente `references`,
  `documentHighlight` et `rename`. Pour prouver un usage d'appelable top-level, la voie
  `occurrenceTopLevel` rejette toute référence si le fichier d'occurrence déclare son propre
  top-level homonyme (précédence/ambiguïté de résolution Kotlin). Le garde
  `estHomonymeTopLevel(déclaration, nom, uri)` teste `déclaration.uri == uri` — or quand
  l'usage vit dans le **fichier déclarant**, la cible **elle-même** (et sa copie du scan)
  satisfait ce prédicat : **tout usage same-file d'un appelable top-level est rejeté**,
  seule la déclaration (branche « jeton == nom déclaré ») survit. L'audit l'a caractérisé
  sur trois formes (points-virgules une ligne, multi-lignes, avec emoji) : 1 surlignage au
  lieu de 3. Le commentaire du code constatait déjà le risque (« le scan porte toutes les
  déclarations, **y compris celle de la cible elle-même** ») sans le traiter. Les tests du
  dépôt ne couvraient les top-level qu'en cross-file, le trou est passé entre les mailles.
  **Impact le plus grave** : `rename` édite la déclaration mais pas les usages du fichier
  déclarant — le code produit ne compile plus, alors que les usages des AUTRES fichiers
  sont correctement renommés (asymétrie éprouvée par F5). C'est un défaut d'exactitude,
  pas un sous-rapport : la philosophie D3 ne le couvre pas.
- **Options considérées** :
  1. **Exclure la cible du garde par identité (uri + plage du nom)** — un homonyme ne compte
     que s'il n'est pas la cible elle-même : `!(déclaration.uri == cible.uri &&
     déclaration.debutNom == cible.debutNom)`. La copie scan du même fichier porte la même
     uri et la même plage → les deux exemplaires sont exclus d'un coup.
  2. **Exclure par uri seule** — rejeter toute déclaration du fichier déclarant : revient
     au statu quo pour le cas same-file (la cible vit dans ce fichier) ; ne résout rien.
  3. **Changer la sémantique du garde en « homonyme CONCURRENT » calculé en amont** —
     filtrer `preuve.contexte.declarations` une fois par fichier (coût moindre par jeton)
     mais complexité supérieure pour le même effet que l'option 1.
- **Décision** : retenir l'**option 1** — le garde `estHomonymeTopLevel` reçoit la cible et
  ne retient un homonyme que s'il est **distinct de la cible** (comparaison uri + debutNom ;
  finNom en garde additionnelle). La preuve « même paquet / import exact / joker » située
  APRÈS le garde s'applique alors au fichier déclarant (paquet courant == paquet cible) :
  les usages same-file deviennent prouvés, symétriques du cas cross-file. Les occurrences
  de la DÉCLARATION restent servies par la première branche de `occurrenceJeton` (jeton ==
  plage du nom déclaré), inchangée.
- **Conséquences** :
  - `references`/`documentHighlight` rendent déclaration **+ usages du fichier déclarant**
    pour tout appelable top-level (fonctions comme propriétés) ; `rename` devient exact
    sur le cas le plus courant (le script/fichier unique).
  - Le cas ambigu originel reste couvert : deux top-level homonymes dans le même fichier
    restent rejetés pour la cible de l'un comme de l'autre (la désambiguïsation de
    `cibleAuCurseur` exclut déjà les cibles ambiguës).
  - Tests : S1/S2/S3 du harnais passent de la caractérisation « 1 » à « 3 » ; ajouter au
    moteur (`SessionLangageKotlinOccurrencesTest`) les fixtures manquantes : « fonction
    top-level, usage dans le MÊME fichier » et « renommage same-file — déclaration ET usage
    édités » ; conserver les fixtures cross-file existantes comme garde de non-regression.
  - Le garde étant le goulot des occurrences top-level, le coût est nul (comparaison
    d'entiers en plus par candidat).
