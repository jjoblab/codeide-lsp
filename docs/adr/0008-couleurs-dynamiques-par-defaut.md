# ADR 0008 — Couleurs dynamiques actives par défaut (Android 12+), réglage à venir

- **Statut** : accepté (étape 1) — réévalué à l'étape 5
- **Contexte** : l'étape 1 doit livrer un thème Material 3 complet avec
  « couleurs dynamiques optionnelles (Android 12+) ». Or le réglage
  utilisateur correspondant (`AppSettings.useDynamicColor`) n'arrive qu'à
  l'étape 4 (modèle) / 5 (assistant d'apparence). Il faut décider du
  comportement par défaut entre les deux, et de l'endroit où vit le thème.
- **Décision** :
  - le **thème `Theme.CodeIDE` et l'écran de démarrage vivent dans
    `core:ui`** (palettes clair/sombre, typographie, formes, espacements,
    styles de composants — tout en tokens, aucun attribut en dur dans les
    écrans) ; `app` ne fait que référencer le thème ;
  - les couleurs dynamiques sont **appliquées par défaut quand l'appareil
    les supporte** (Android 12+), via
    `Activity.applyDynamicColorsIfAvailable()` appelé par `MainActivity` —
    l'identité indigo reste le socle pour les appareils plus anciens et
    pour les éléments non thémés ;
  - dès que `useDynamicColor` existe (étape 4) et que l'écran d'apparence
    permet de le débrancher (étape 5), le paramètre persisté remplace ce
    défaut : `MainActivity` lira les paramètres et passera le booléen à
    l'extension, qui deviendra `applyDynamicColors(active: Boolean)`.
- **Conséquences** :
  - les premiers retours visuels (étapes 1 à 4) goûtent Material You sur
    Android 12+ — conforme à l'esprit « optionnelles » : un réglage pourra
    les couper, le socle CodeIDE reste utilisable sans elles ;
  - un seul point de bascule (`MainActivity`) à modifier à l'étape 5,
    aucun écran à retoucher ;
  - le glyphe de démarrage provisoire (chevrons blancs) sera remplacé par
    la charte définitive à l'étape 13.
