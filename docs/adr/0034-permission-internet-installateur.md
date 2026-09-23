# ADR 0034 — Permission INTERNET : unique usage réseau, l'installateur du bootstrap

- **Statut** : accepté (étape 21 = Terminal T3, v0.22.0)
- **Date** : 2026-09-23
- **Contexte** : ADR 0033 (installateur) ; règle 7 du prompt maître et
  ROADMAP Phase 2 (« pas de réseau sans décision explicite : la
  permission `INTERNET` reste absente tant qu'un cas d'usage ne la
  justifie pas publiquement, ADR dédiée le cas échéant »).

## Décision

L'application déclare désormais la permission **`INTERNET`** —
**un seul cas d'usage** : l'installateur du bootstrap natif

1. télécharge l'archive `bootstrap-aarch64.zip` depuis les releases de
   `jjoblab/codeide-packages` (≈ 40 Mo, empreinte SHA-256 vérifiée) ;
2. puis `apt update` / `apt install` interroge le dépôt APT
   `https://jjoblab.github.io/codeide-packages/apt/codeide-main`
   (paquets d'outils : `openjdk-17`, `git`).

Aucune autre Voie du code n'émet de requête : pas de télémétrie, pas
d'envoi automatique de rapports (conforme au périmètre hors-scope de la
Phase 1), pas de téléchargement de dépendances au build. Le client est
`HttpURLConnection` (ADR 0033) — aucune dépendance réseau tierce.

## Options écartées

- **Rester hors-ligne et embarquer le bootstrap dans l'APK** : ≈ 40 Mo
  d'APK supplémentaires pour **tous** les utilisateurs, y compris ceux
  qui n'utiliseront jamais le terminal ; impossible à mettre à jour sans
  republier l'application. Le prompt compagnon Terminal-1 impose
  explicitement le téléchargement depuis les releases.
- **Ajouter `INTERNET` en T2** (quand l'installateur a été écrit) :
  prématuré — le module n'était pas encore branché à `app`, la
  permission aurait été déclarée pour du code mort.

## Conséquences

- La promesse « aucune permission » de la Phase 1 devient « aucune
  permission d'entrée de gamme » : `INTERNET` est une permission
  `normal`, sans dialogue système, installée à la mise à jour.
- Les futurs usages réseau (plugins hors-ligne uniquement — ROADMAP :
  « découverte embarquée, pas de réseau ») devront s'inscrire dans ce
  même périmètre ou exiger une nouvelle ADR.
- Le débogage réseau reste possible sans permission spécifique
  (`adb reverse`, `localhost`).
