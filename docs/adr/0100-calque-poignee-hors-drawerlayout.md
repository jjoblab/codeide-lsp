# ADR 0100 — Calque de poignée HORS du DrawerLayout (touches du tiroir restaurées)

- Statut : accepté (2026-10-10)
- Contexte : régression v0.80.2 (ADR 0099, décision 3) — retour
  utilisateur : « toutes les touches sur n'importe quelle partie du
  tiroir sont interceptées ». Après le déplacement de la poignée ⋮ dans
  `conteneur_poignee` (enfant PLEIN ÉCRAN du `TiroirPoussantLayout`),
  plus RIEN dans le tiroir ne répond au toucher : arborescence de
  l'explorateur, rail de fragments, barres de recherche — et la poignée
  elle-même.

## Cause racine (vérifiée dans androidx.drawerlayout 1.1.1)

`conteneur_poignee` est un enfant SANS GRAVITÉ du `DrawerLayout`, donc
un **enfant de contenu**. Trois règles de la bibliothèque se
combinaient pour intercepter chaque touche dès que le tiroir est
ouvert :

1. `onMeasure` mesure tout enfant de contenu **à la taille exacte du
   layout** — impossible de rétrécir le conteneur à la seule bande de
   la poignée : il couvre TOUT l'écran.
2. `computeScroll` pose `mScrimOpacity = max(onScreen) = 1` dès que le
   tiroir est ouvert (indépendant de la couleur du voile, transparente
   dans CodeIDE).
3. `onInterceptTouchEvent(ACTION_DOWN)` : `findTopChildUnder(x, y)`
   parcourt les enfants **à l'envers** (bornes `left/right`, sans
   translation) — le conteneur, dernier enfant plein écran, est
   l'enfant le plus haut pour TOUT point de l'écran ; comme
   `isContentView` (gravité `NO_GRAVITY`) dit vrai, la branche
   « tap sur le contenu » pose `interceptForTap = true` : la touche
   n'atteint JAMAIS les enfants (elle sert à refermer le tiroir au
   relâchement dans le comportement standard).

Les contournements simples sont impossibles : donner une gravité de
tiroir au conteneur en ferait une vue **draggable** par `ViewDragHelper`
(`isDrawerView` → `tryCaptureView` vrai) ; le sortir du flux du
`DrawerLayout` est la seule sortie.

## Décision

La racine de `activity_editor.xml` devient un **FrameLayout de
superposition** de deux calques :

1. `TiroirPoussantLayout` (id `racine_editeur` inchangé — le type du
   binding `racineEditeur` ne change pas, tout le code existant reste
   valable) : la zone centrale et le tiroir, RIEN d'autre ;
2. `conteneur_poignee`, DERNIER enfant : dessiné après (au-dessus) et
   touché avant le `DrawerLayout`.

Le calque n'étant plus un enfant du `DrawerLayout` :

- `findTopChildUnder` ne le voit jamais — l'interception « tap sur le
  contenu » retrouve sa cible légitime : la vraie zone centrale (le
  liseré de 5 % du mode POUSSÉ referme le tiroir, comme avant) ;
- le rognage `clipRect` de `DrawerLayout.drawChild` ne s'applique plus
  (le calque n'est pas dessiné par le `DrawerLayout`) — le cheval de
  la poignée sur le bord du tiroir n'a plus besoin de bypass ;
- l'ordre des calques suffit au-dessus du tiroir (l'élévation ne
  re-classe que des FRÈRES : le tiroir à 10 dp reste dans le sous-arbre
  du `DrawerLayout`, dessiné avant le calque).

Nettoyage des hacks v0.80.2 devenus morts :

- `TiroirPoussantLayout.setConteneurPoignee` + le bypass
  `drawChild` + le champ `conteneurPoignee` : SUPPRIMÉS ;
- `EditorActivity` : `setDrawerElevation(0f)` SUPPRIMÉ (l'élévation
  du tiroir retrouve la valeur standard, ombre de bord comprise) ;
- `brancherPoignee` : les appels `requestDisallowInterceptTouchEvent`
  SUPPRIMÉS — le calque n'est plus un descendant du `DrawerLayout`,
  l'écouteur de la poignée reçoit le geste entier sans rien négocier.

`recalerPoignee()` est inchangé : la translation (et non le layout)
porte le cheval image par image, et le test de touche des vues est
conscient des transformations (`isTransformedTouchPointInView`), donc
la poignée déplacée reste cliquable depuis le calque racine.

## Justification

- **Architecture standard plutôt que contournement** : superposer un
  calque de contrôle au-dessus d'un `DrawerLayout` est un patron
  Android banal ; les deux hacks v0.80.2 (`drawChild` bypassant le
  rognage, élévation forcée à 0) contrevenaient au contrat de la
  bibliothèque et dépendaient d'internals (`findTopChildUnder`,
  `isContentView`, `mScrimOpacity`) non garantis d'une version à
  l'autre.
- **Comportement du DrawerLayout intégralement préservé** :
  tap-to-close sur le liseré, glissement de fermeture depuis le
  tiroir, edge drag d'ouverture, verrouillage grand écran — tout
  repasse par les chemins prévus par la bibliothèque.
- **La poignée gagne en robustesse** : le geste de redimensionnement
  ne dépend plus d'un équilibre fragile (ordre des enfants +
  élévation 0 + désaveu d'interception) mais d'une position stable
  dans l'arbre.

## Conséquences

- `activity_editor.xml` : racine FrameLayout à deux calques
  (TiroirPoussantLayout puis conteneur_poignee) ; commentaires mis à
  jour (pourquoi le calque ne doit PAS vivre dans le `DrawerLayout`).
- `TiroirPoussantLayout` : retour à un rôle pur (translation du
  contenu + réévaluation après redimensionnement).
- `EditorActivity` : configuration réduite à `idContenu` ; l'ombre
  d'élévation du tiroir revient (valeur par défaut, aspect
  pré-v0.80.2).
- Tests `ActivityEditorLayoutTest` : structure v0.80.3 (calque
  sibling, DrawerLayout à DEUX enfants seulement) + DEUX régressions
  comportementales exécutant de vraies touches sur le layout mesuré
  et posé — tiroir ouvert (`openDrawer(animate = false)` avant le
  premier layout + `computeScroll()` pour `mScrimOpacity = 1`) : un
  appui au MILIEU du corps du tiroir doit atteindre le conteneur de
  fragments, et un appui posé SUR le bord (moitié zone centrale de la
  poignée) doit être consommé par la poignée.
- Risque résiduel : un calque plein écran de plus dans l'arbre —
  négligeable (transparent, non cliquable, `importantForAccessibility
  = no`) ; les insets continuent de descendre jusqu'à `racineEditeur`
  (le FrameLayout racine ne consomme rien).
