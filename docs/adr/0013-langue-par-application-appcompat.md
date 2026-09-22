# ADR 0013 — Langue par application via AppCompatDelegate.setApplicationLocales

- Date : 2026-09-22 (étape 5, v0.6.0)
- Statut : accepté

## Contexte

L'étape 5 introduit le choix de la langue dans l'assistant de premier
lancement (page « Apparence », FR/EN via
`AppCompatDelegate.setApplicationLocales`). Trois approches existent :

1. `LocaleList.setDefault` + `configuration.setLocale` au niveau de
   l'activité (pratique historique) ;
2. l'API plateforme par application (`LocaleManager.setApplicationLocales`,
   Android 13+) ;
3. `AppCompatDelegate.setApplicationLocales` d'AndroidX AppCompat.

L'application cible Android 8.0 (API 26) minimum et doit changer de
langue **à chaud**, avec aperçu immédiat, et conserver le choix après
redémarrage.

## Décision

Utiliser `AppCompatDelegate.setApplicationLocales(LocaleListCompat)`
piloté par la valeur persistée `AppSettings.languageTag` (tag BCP 47,
`""` pour suivre le système).

Motifs :

- **Une seule API pour tous les niveaux d'API** : AppCompat délègue au
  `LocaleManager` plateforme sur Android 13+ et gère le stockage de
  secours lui-même sur Android 8-12 — aucun `Build.VERSION` côté
  application.
- **Changement à chaud fiable** : AppCompat recrée automatiquement les
  activités ; c'est le mécanisme officiellement maintenu pour les
  langues par application (la pratique `configuration.setLocale` casse
  avec le multifenêtre et les sauvegardes d'état).
- **Cohérence avec l'architecture** : la source de vérité reste
  `SettingsDataStore` (relecture au démarrage de `MainActivity`) ; la
  persistance AppCompat interne n'est qu'un cache système, jamais lue
  comme référence.

## Conséquences

- `MainActivity` collecte les paramètres et applique
  `setApplicationLocales` à chaque divergence ; l'« aperçu immédiat »
  de l'assistant n'est que la conséquence naturelle de la persistance.
- Le manifeste déclare `android:localeConfig` (`locales_config.xml`,
  fr + en) pour l'intégration aux réglages système Android 13+.
- Le choix est **réappliqué au démarrage** (avant le premier rendu,
  sous l'écran de démarrage) : le paramètre persisté gagne toujours
  sur le cache AppCompat.
- Limite assumée : `setApplicationLocales` ne recrée pas le processus ;
  les fragments recréés relisent leurs chaînes — les pages de
  l'assistant sont conçues pour cela (rendu depuis l'état, jamais de
  chaîne capturée).
