# :lsp:ecj-art — patcher build-time d'ecj pour ART

> **Exemption « Kotlin 100 % » (règle 8 d'AGENTS.md, ADR 0108)** : module
> JVM vendored en Java, exempté par l'ADR 0108 ; detekt et spotless ne
> s'appliquent pas à ses sources.

Patcher ASM exécuté **à la compilation** par la tâche
`:lsp:java:patchEclipseForArt` : réécrit les jars Eclipse du graphe de
`org.eclipse.jdt:org.eclipse.jdt.core` pour le runtime Android
(`Runtime$Version`/`StackWalker` → shims, `InputStream.readAllBytes`/
`readNBytes` → `InputStreamCompat`, purge OSGi/signatures/MR-jar).

**Jamais dans l'APK** — artefact de build uniquement. Dépend d'ASM
(versions dans `gradle/libs.versions.toml`).

Clé : `jo.lspjava.jdtpatch.EcjArtPatcher` (CLI :
`input.jar output.jar [ancien=nouveau]…`).
