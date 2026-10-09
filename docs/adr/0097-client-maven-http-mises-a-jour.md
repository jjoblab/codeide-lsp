# ADR 0097 — Client Maven HTTP pour les mises à jour de versions

- Statut : accepté (2026-10-09)
- Contexte : mission Projet P6 (prompt `prompt-agent-dependances.md`).

## Décision

Un **client HTTP Maven** côté app (core:domain) interroge les
`maven-metadata.xml` des dépôts déclarés dans le projet pour exposer
les **versions disponibles** d'une dépendance. Le client vit dans
`core:domain` (module pur JVM testable) et utilise `HttpURLConnection`
— pas de nouvelle dépendance externe (OkHttp/ Retrofit alourdiraient
l'APK de ~1 Mo pour un besoin ponctuel).

### 1. Format de requête

`MavenVersionesDisponibles(group, name)` résout l'URL
`<dépôt>/<group avec / au lieu de .>/<name>/maven-metadata.xml` et
parse les balises `<version>...</version>`. Le premier dépôt qui
répond gagne ; les dépôts muets ou 404 sont ignorés sans erreur.

### 2. Dépôts déclarés

La liste des dépôts est fournie par l'appelant (l'UI P6 la construira
depuis les `repositories { ... }` des scripts de build — parsing
syntaxique minimal). Par défaut : `https://repo1.maven.org/maven2/`
(Maven Central) et `https://dl.google.com/dl/android/maven2/` (Google
Maven).

### 3. Cache et hors ligne

- Cache en mémoire (LRU 64 entrées) — TTL 1 heure : un projet consulté
  plusieurs fois ne re-télécharge pas le `maven-metadata.xml`.
- Hors ligne (`--offline` ou réseau absent) : le cache est servi tel
  quel même expiré, avec un avertissement (l'UI P6 affiche un drapeau
  « données potentiellement périmées »).

### 4. Sécurité

- HTTPS uniquement — HTTP est refusé (`IllegalArgumentException`).
- Timeout 5 s connexion / 10 s lecture (l'UI ne doit pas geler).
- Pas de cookies, pas d'auth — lecture publique Maven uniquement.
- Taille max 256 Ko (un `maven-metadata.xml` typique fait < 10 Ko ;
  toute réponse plus grosse est rejetée comme hostile).

### 5. Port du domaine

```kotlin
public interface MavenVersionesDisponibles {
    public suspend fun versions(
        group: String,
        name: String,
        depots: List<String> = DEPOTS_PAR_DEFAUT,
    ): AppResult<List<String>>
}
```

L'implémentation `ClientMavenHttp` vit dans `core:data` (dépend de
`core:domain` et de `HttpURLConnection`). Testée en JVM pur via un
`HttpServer` local (comme `GestionnaireTelechargementTest`).

## Conséquences

- Nouveau port `MavenVersionesDisponibles` dans `core:domain`.
- Nouveau module ou fichier `ClientMavenHttp` dans `core:data`.
- L'UI P6 (onglet « Mises à jour ») consomme le port via Hilt.
- Aucune nouvelle dépendance externe (zéro gain de poids APK).

## Références

- ADR 0095 (section Projet du tiroir, §4)
- ADR 0085 (GestionnaireTelechargement — patron HttpURLConnection)
- `maven-metadata.xml` XSD : https://maven.apache.org/ref/3.9.0/maven-repository-metadata/
