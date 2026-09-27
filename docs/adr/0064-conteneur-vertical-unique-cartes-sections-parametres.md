# ADR 0064 — Conteneur vertical unique dans les cartes des sections des Paramètres

- **Statut** : accepté (v0.35.4, retour utilisateur du 2026-09-27 —
  « l'utilisation du scrollview comme parent des materialcardview donne
  un mauvais rendu, les vues sont empilées »)
- **Contexte** : les sept sections dédiées des Paramètres (Apparence,
  À propos, Avancé, Éditeur, Notifications, Projets, Terminal — écran à
  deux niveaux de l'ADR 0059, enrichi des palettes statiques de l'ADR
  0060) affichaient des vues superposées au coin haut-gauche de leur
  carte : rangées, séparateurs, interrupteurs et sélecteurs les uns SUR
  les autres. La cause n'est pas le `ScrollView` (lui n'a qu'un enfant,
  la carte — structure saine depuis l'ADR 0059) : `MaterialCardView`
  étend `FrameLayout`, et dans ces sept layouts le `LinearLayout`
  vertical de contenu fermait trop tôt ou n'existait pas — toutes les
  rangées suivantes devenaient des enfants DIRECTS de la carte, posés
  au même coin par défaut du FrameLayout. L'écran maître
  (`fragment_settings.xml`) est resté correct : chacune de ses quatre
  cartes porte un unique `LinearLayout` vertical. Aucun test ne l'a vu
  : les tests de layout existants (`SettingsFragmentTest`, style
  `TiroirTerminalLayoutTest`) assertent la PRÉSENCE des vues par
  `findViewById`, jamais leurs positions — et un gonflage sans passe
  de mesure/pose ne superpose rien, il ne fait qu'instancier.

## Décisions

1. **Un conteneur vertical unique par carte** : dans chaque layout de
   section, tout le contenu de la `MaterialCardView` (sections,
   séparateurs, rangées) vit dans un unique `LinearLayout` vertical
   sans id — exactement la structure du maître. Sans id, le conteneur
   n'existe ni pour les fragments ni pour ViewBinding : aucun code
   Kotlin n'a changé, les rangées gardent leurs id et leurs écouteurs.
2. **Régression par pose réelle, pas par présence**
   (`SectionsParametresLayoutTest`, Robolectric sous le thème réel) :
   chaque layout est gonflé PUIS mesuré et posé à taille d'écran
   (1080×2340), et le test vérifie (a) la structure — chaque carte
   porte un unique enfant `LinearLayout` vertical (les quatre cartes du
   maître comprises, huit layouts au total) ; (b) le symptôme exact —
   aucune rangée visible ne chevauche celle du dessus (le bas de l'une
   au-dessus du haut de la suivante, tolérance 1 px). Les vues `gone`
   sont exclues du contrôle : elles ne se posent pas, elles ne peuvent
   ni chevaucher ni être chevauchées. Le test échouait sur les sept
   layouts avant correctif (« la carte doit porter un unique enfant,
   pas 7 » ; « bottom 141 > top 24 — contenu de carte empilé ») et
   passe après — c'est le symptôme de l'utilisateur qui est verrouillé,
   pas un détail d'implémentation.
3. **Correctif mécanique vérifié** : la transformation des sept
   fichiers (insertion du conteneur, décalage d'indentation du
   contenu) a été appliquée par script avec vérifications intégrées —
   XML bien formé, une carte par fichier porte exactement un enfant
   LinearLayout vertical, et la liste des `android:id` du document est
   strictement inchangée avant/après (aucun binding rompu).

## Conséquences

- Les sept sections affichent leurs rangées empilées verticalement,
  séparateurs compris, comme dessiné à l'origine — le contenu
  redevient lisible et touchable rangée par rangée.
- Ajouter une rangée directement sous une carte (contournement du
  conteneur) fait échouer la CI sur deux garde-fous : la structure
  (enfant unique vertical) ET le chevauchement réel après passe de
  mesure/pose.
- La garantie s'étend au maître : ses quatre cartes de domaine sont
  couvertes par le même test — la famille entière des écrans
  Paramètres partage maintenant un invariant commun.
- Aucun coût d'exécution : le correctif est purement structurel
  (XML), aucun code de fragment ni de ViewModel n'a bougé.
