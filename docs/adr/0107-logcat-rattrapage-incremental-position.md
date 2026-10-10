# ADR 0107 — Logcat : rattrapage incrémental par position (LotJournal)

- **Date** : 2026-10-10
- **État** : accepté
- **Phase** : mission « Exécuter » R3 (onglet Logcat, spec EXECUTER.md § 4)
- **Décideurs** : session IA (implémentation R3)

## Contexte

L'onglet Logcat (R3) affiche le tampon d'une session du registre du pont
de journaux (`RegistrePontJournaux`, ADR 0103). Pour suivre le flux
vivant, l'afficheur doit :

1. partir de l'instantané tamponné (`instantane(id)`) au moment de la
   sélection (ou de la reprise de pause) ;
2. recevoir chaque lot NOUVEAU après ce point de départ.

Le port expose un flux de lots (`MutableSharedFlow`, `replay = 0`).
Or **l'abonnement à un SharedFlow sans rejeu et la lecture d'un
instantané ne sont pas atomiques** : entre « je lis l'instantané » et
« mon abonnement est enregistré », un lot peut être émis — il n'est
ni dans l'instantané lu (émis après), ni livré à l'abonné (pas encore
enregistré). La ligne disparaît silencieusement de l'affichage :
exactement le défaut que la politique d'honnêteté du projet interdit
(« jamais perdues en silence », ADR 0103 §5).

Inverser l'ordre (s'abonner puis lire) déplace la fenêtre : le lot
alors émis est livré ET présent dans l'instantané lu ensuite —
doublon affiché. Aucun ordre des deux opérations ne ferme la fenêtre.

## Décision

1. **Chaque lot porte sa position** : le type d'émission devient
   `LotJournal(apres, lignes)` où `apres` est le nombre TOTAL de lignes
   reçues par la session après ce lot (compteur du registre, jamais
   remis à zéro, mis à jour SOUS LE VERROU du registre, dans le même
   bloc critique que l'émission).

2. **Le port gagne un delta incrémental synchrone** :
   `lignesDepuis(idSession, position)` retourne, sous le même verrou,
   les lignes reçues STRICTEMENT après `position` (bornées par ce que
   le tampon retient) et la nouvelle position. Lecture cohérente avec
   l'état des sessions émis au même instant.

3. **L'afficheur ne souscrit PAS au flux de lots** : l'état des
   sessions — un `StateFlow`, SANS PERTE par conception (conflation),
   réémis à CHAQUE lot par le registre — devient le signal de
   rattrapage. À chaque réémission, le ViewModel lit
   `lignesDepuis(id, positionRendue)` et n'ajoute que le delta.

4. Le flux de lots (`lignes()`) reste au port : c'est l'API de poussée
   pour un consommateur qui vit AVEC les lots (instrumentation,
   export) — mais l'afficheur ne l'utilise plus.

## Conséquences

- **Aucune perte, aucun doublon, quelle que soit la concurrence** :
  le delta est lu sous le verrou du registre ; la conflation du
  `StateFlow` ne peut qu'AGRANDIR le delta à rattraper (jamais le
  réduire à zéro) — un afficheur lent rattrape en un appel ce que
  N lots ont apporté.
- **Filtrage incrémental** : le filtre s'applique AU PASSAGE du delta
  (O(delta), aucune allocation en rafale par lot, critère EXECUTER.md
  § 6.5) ; le re-balayage complet n'arrive que sur action utilisateur
  (changement de filtre) ou re-sélection.
- **La pause est triviale et honnête** : figer = ne pas rattraper (le
  registre continue de collecter, borné) ; reprendre = UN appel de
  rattrapage ramène tout ce qui est retenu.
- **Le tampon d'affichage du ViewModel est borné en synchronisation**
  avec le tampon filtré (la ligne évincée quitte les deux si elle y
  était — identité d'objet).
- Coût : une réémission de l'état des sessions PAR lot (le registre le
  faisait déjà — `nombreLignes` et `lignesPerdues` vivent là) et une
  copie de liste par réémission côté ViewModel (même ordre de grandeur
  que le `submitList` de l'adaptateur, inévitable avec DiffUtil).
- Les tests du registre et du ViewModel couvrent la sémantique
  positionnelle (position intermédiaire, à jour, trop vieille, session
  inconnue) et le rattrapage de pause.

## Alternatives rejetées

- **`replay` sur le SharedFlow** : rejouerait les derniers lots à CHAQUE
  abonnement — doublons garantis pour le nouvel abonné (l'instantané
  les contient déjà).
- **Prefill dans `onSubscription`** : l'action s'exécute AVANT
  l'enregistrement auprès du flux amont — la fenêtre de course demeure
  (c'est la cause racine), elle est juste plus étroite.
- **Re-lecture complète de l'instantané à chaque lot** : O(capacité)
  par lot — allocation en rafale, contredit le critère § 6.5.
