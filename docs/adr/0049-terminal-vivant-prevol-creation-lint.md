# ADR 0049 — Terminal vivant (repeint, zoom, onglets), pré-vol de création et lint exhaustif

- **Statut** : accepté (v0.31.5, correction après retour utilisateur)
- **Contexte** : cinquième retour de terrain (app v0.31.3/v0.31.4 en cours
  d'envoi, moto g06 / Android 15). Trois familles :
  1. **« Quand j'écris ou tape une cmd, le terminal n'est pas à jour
     immédiatement »** ; **« quand je fais un pinch zoom rien ne se
     passe »** ; **« je ne peux pas naviguer entre les sessions quand
     j'appuie sur les onglets »** — trois défauts distincts du même
     écran Terminal.
  2. **« Tu n'as pas corrigé le problème de la création de projet : au
     dernier étape, un dossier porte déjà ce nom à l'emplacement choisi
     — toutes mes tentatives sont vaines. »** L'échec est typé
     `Storage(AlreadyExists)` depuis v0.31.1 (plus de masque), donc la
     collision est réelle au moment de l'écriture — mais la
     vérification qui précède l'appui sur « Créer » date de l'étape
     Informations (délai 400 ms) et peut être périmée : résidu d'une
     tentative échouée, dossier déposé entre-temps, registre déjà
     référencé… et l'écran d'échec n'offre **aucune sortie** : «
     Réessayer » relance exactement la même requête condamnée.
  3. **CI rouge sur v0.31.4** : `:feature:install:lintDebug` — 8 erreurs
     dont `NestedScrolling` (un `ScrollView` de journal dans le
     `ScrollView` de l'écran, introduit par la refonte v0.31.4) et des
     `PluralsCandidate` ; la vérification locale de v0.31.4 n'avait
     lancé que `:app:lintDebug`, pas celle des modules bibliothèques.

- **Diagnostic** (le premier et le troisième vérifiés sur le **bytecode**
  de `terminal-view` v0.118.3, désassemblé par `javap` — le source
  JitPack n'étant pas publié) :

1. **Repeint** : dans l'architecture Termux, c'est le
   `TerminalSessionClient` **de l'activité** qui déclenche le
   repaint (`onTextChanged → mTerminalView.onScreenUpdated()`). Ici,
   `ClientTermux` (core:terminal-runtime) ne remonte qu'au registre —
   pour l'aperçu **throttlé 250 ms** des métadonnées. Personne
   n'appelle `onScreenUpdated()` : le texte tapé n'apparaît qu'au
   prochain layout unrelated (clavier, focus) — « pas à jour
   immédiatement ».
2. **Zoom** : `TerminalView$1.onScale` accumule un facteur
   (`mScaleFactor *= scale`), le passe au client (`mScaleFactor =
   client.onScale(mScaleFactor)`) et **ne l'applique jamais
   lui-même** — le contrat veut que le CLIENT change la taille puis
   retourne `1.0f` pour consommer. Notre `ClientVueTerminal.onScale`
   retournait le facteur intact : le compteur s'accumulait, rien ne
   s'appliquait. Pincement inerte par construction.
3. **Onglets** : `synchroniserOnglets()` **reconstruit tout le
   TabLayout** (`removeAllTabs` + `addTab`) à CHAQUE émission d'état —
   or les aperçus de session changent toutes les 250 ms pendant qu'une
   commande débite : les vues d'onglets sont détruites **sous le doigt**
   de l'utilisateur, un tap n'atterrit jamais. Aggravant : le « + »
   crée une session que le registre n'active **pas** (seule la première
   session devenait active) — l'écran retombe visuellement sur
   l'ancienne : « je ne peux pas naviguer ».
4. **Création** : la seule source de `AlreadyExists` en production est
   `SafFileSystem.creer` (homonyme réel ou nom retourné ≠ demandé) ;
   l'insertion en base (`addProject`, index unique sur `documentUri`)
   produisait aussi `AlreadyExists` mais `CreateProjectUseCase` le
   **remplaçait** par un `Io` générique (« insertion du projet en
   base ») — même famille de masque que celui de v0.31.1. Restait le
   contrôle post-création : un fournisseur qui rabote espaces/points
   finaux (couches compatibles Windows) était lu comme un « renommage
   hostile » → nettoyage + collision de pure invention.
5. **Lint** : le `NestedScrollView` est la réponse canonique au
   `NestedScrolling` (le conteneur interne participe à
   l'imbrication) ; les trois `PluralsCandidate` d'`install` et trois
   `UseKtx` d'`onboarding` (`Uri.parse` → `toUri()`) étaient des
   erreurs préexistantes que la CI verrait dès qu'`install` serait
   réparé — les corriger maintenant évite un second aller-retour CI.

- **Décisions** :

1. **Signal de repeint immédiat** (`TerminalRuntime.observeSorties()` :
   `Flow<Unit>`, tampon 1, dernier gagnant) : émis par
   `surTexteModifie`/`surTerminee` **sans throttle** (l'aperçu garde
   sa fenêtre de 250 ms — il ne sert que la carte). L'activité
   collecte et appelle `vueTerminal.onScreenUpdated()` — c'est le
   transcript complet qui se redessine, pas un delta : un signal
   perdu pendant une collecte occupée est indolore. Même garantie au
   rebranchement : repaint explicite après `attachSession`.
2. **Zoom appliqué par le client** : `ClientVueTerminal.onScale`
   délègue à l'activité (`zoomer(facteur)`) qui borne
   (10–30 dp), applique `setTextSize` et consomme (retour `1.0f`,
   pincements < ±10 % ignorés comme Termux). Un drapeau `zoomManuel`
   empêche les émissions d'état (250 ms) d'écraser la taille pincée
   par celle du réglage.
3. **Onglets par diff** : mise à jour en place (libellé, pastille,
   écouteurs — l'identifiant positionnel peut glisser après une
   fermeture), insertions avant le « + » (créé une fois), retraits par
   la fin, sélection déplacée **seulement si elle diffère**. Et la
   création de session **active toujours** la nouvelle (l'onglet « + »
   et le bouton de la toolbar partent de ce postulat, comme Termux) ;
   les sessions créées depuis l'accueil/l'éditeur y gagnent aussi (le
   point d'entrée ouvre l'écran juste après).
4. **Pré-vol à la création** : `WizardViewModel.creer()` re-vérifie la
   cible (`VerifyCreationTargetUseCase`) juste avant d'écrire. Une
   collision détectée là publie l'échec typé **sans lancer la création
   condamnée** (aucune écriture, aucun rollback à faire) ; les autres
   motifs relèvent l'erreur interne telle quelle. En cas de collision
   SAF pendant l'écriture proprement dite, le domaine remonte
   toujours sa raison réelle.
5. **Échec honnête et sortie du piège** : l'écran d'échec **affiche**
   les détails techniques portés par l'erreur (URI de l'homonyme,
   pré-vol, insertion refusée — monospace, borné, toujours copiables)
   ; un bouton **« Changer de nom ou d'emplacement »** ramène
   directement à l'étape Informations (retour arrière pur — les gardes
   de validité restent intactes) : le message le réclamait depuis
   v0.31.0, l'écran le propose enfin. `CreateProjectUseCase` relaye
   l'erreur **réelle** de `addProject` (déjà référencé = AlreadyExists
   actionnable, base verrouillée = Io) au lieu du `Io` générique. Le
   contrôle post-création de `SafFileSystem` tolère une
   **normalisation fournisseur** (espaces/points finaux rabotés,
   comparaison hors casse) — le document créé au nom normalisé est le
   nôtre, pas une collision.
6. **Lint exhaustif, localement** : le `ScrollView` du journal devient
   `NestedScrollView` (hauteur fixe conservée, auto-défilement
   inchangé) ; les quantités deviennent de vrais `<plurals>` (extraction)
   ou des indices « n/total » (paquet — un indice n'est pas une
   quantité, le pluriel serait sémantiquement faux) ; `Uri.parse` →
   `toUri()` côté onboarding. **La vérification standard étendue** :
   outre `:app:lintDebug`, lancer `lintDebug` sur **tous** les modules
   touchés (une CI rouge coûte un aller-retour de plus que quarante
   secondes de lint local).

- **Alternatives rejetées** :
  - *Un `TerminalView` par session* (au lieu du rebranchement) — la
    mémoire en dépend (position v0.31.2), et le problème n'était pas
    le rebranchement mais le repaint et les taps mangés.
  - *Throttlé le repaint aussi* (réutiliser la fenêtre de 250 ms) —
    exactement le symptôme rapporté : la frappe doit s'afficher au
    frame suivant, pas un quart de seconde plus tard ; l'aperçu seul
    est une donnée de carte.
  - *Persister le zoom pincé dans les réglages* — le réglage dédié
    (petite/moyenne/grande) existe déjà ; le pincement est un
    ajustement d'écran, réinitialisé à la réouverture, borné pour ne
    pas fabriquer de tailles injouables.
  - *Créer une ligne à `lint-baseline.xml`* — masquer des erreurs
    fraîches plutôt que les corriger ; les 8 de la CI se réparent
    toutes proprement (imbriquation, pluriels, KTX).
  - *Supprimer le défilement du journal* (aplatir dans le conteneur
    externe) — la carte de hauteur fixe suit la sortie (auto-scroll)
    sans faire bondir tout l'écran ; c'est le comportement v0.31.4
    validé par l'utilisateur.

- **Conséquences** :
  - La frappe, la sortie des commandes et la fin d'un shell apparaissent
    au frame suivant ; le pincement zoome entre 10 et 30 dp ; les onglets
    répondent même pendant qu'une commande débite ; une session créée
    s'affiche.
  - Une collision à la création est détectée AVANT toute écriture
    (échec honnête avec détails visibles) ou relevée avec sa raison
    réelle pendant l'écriture ; l'utilisateur a toujours un bouton
    pour changer de nom/emplacement.
  - La CI repasse : toutes les erreurs lint de toutes les modules sont
    corrigées à la source (une seule nouvelle chaîne localisée et un
    pluriel).
  - Risque résiduel : si la collision observée sur l'appareil venait
    d'un comportement fournisseur encore inconnu, l'échec affiche
    désormais ses détails (bornés, copiables) — le prochain rapport
    d'appareil est diagnosticable sans devinette.
