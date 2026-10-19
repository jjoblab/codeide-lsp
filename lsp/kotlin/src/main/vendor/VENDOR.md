# Vendoring — la grammaire Kotlin multiplateforme du compilateur

Ces fichiers sont copiés **octet pour octet** depuis le compilateur Kotlin
(JetBrains). Les noms de paquets amont (`org.jetbrains.kotlin.kmp.*`) sont
conservés tels quels : un re-sync est un **remplacement**, jamais une fusion —
la moindre divergence locale ferait perdre à ce module sa propriété centrale
(décision D2 de `docs/LSP_KOTLIN.md`, ADR 0115) : être exactement la
grammaire du compilateur, vérifiée par la suite de parité
(`src/test/kotlin/jo/lspkotlin/parite/`).

    amont :   https://github.com/JetBrains/kotlin
    chemin :  compiler/multiplatform-parsing/common/src
    tag :     v2.3.0
    commit :  f95cb2f7693a15966fd8fd9b1d78d440545e7b66
    date :    2026-10-13 (extraction)
    licence : Apache 2.0 (LICENSE.txt + COPYRIGHT.txt dans ce répertoire)

## Pourquoi un vendoring plutôt qu'une dépendance

`compiler/multiplatform-parsing` est un module **interne** du build Kotlin :
il n'est pas publié sur Maven, il n'existe donc aucune coordonnée à déclarer.
Ses propres dépendances, elles, sont publiées sur le dépôt
`intellij-dependencies` de JetBrains : `org.jetbrains:syntax-api` (l'arbre de
construction syntaxique) et `org.jetbrains:annotations` — toutes deux
consommées ici comme artefacts binaires versionnés dans le catalogue.

Le tag **v2.3.0** est délibéré : c'est la première version publiée qui
contient ce module, et le build Kotlin v2.3.0 est lui-même compilé par le
compilateur **bootstrap 2.2.x** — exactement la chaîne d'outil de CodeIDE
(Kotlin Gradle plugin 2.2.10). Les sources vendorées compilent donc avec
notre compilateur sans montée de version. Une montée future de la grammaire
(v2.4+) devra être étudiée avec la version bootstrap correspondante.

## Zéro modification locale

Contrainte plus stricte que celle du projet de référence (étudié en
référence) : **aucun** fichier vendoré n'est modifié localement. Ni la
grammaire (parser/lexer), ni l'arbre. Les accès indexés et le re-parse
incrémental dont le projet de référence avait besoin vivent chez nous dans la façade
`jo.lspkotlin.parse` (paquet `kt/`), construite sur l'API **publique** de
`syntax-api` — un re-sync amont ne peut donc jamais casser le build local :
il ne reste qu'à relancer la suite de parité.

## Contenu

- `gen/` — lexers JFlex **générés et commités par l'amont**
  (`KotlinFlexLexer.kt`, `KDocFlexLexer.kt`). Les entrées `.flex` ne sont pas
  copiées : nous ne régénérons jamais les lexers, l'amont le fait.
- `org/jetbrains/kotlin/kmp/lexer/` — `KotlinLexer`, `KtTokens`,
  `KDocLexer`, `KDocTokens`, `KDocKnownTag`.
- `org/jetbrains/kotlin/kmp/parser/` — `KotlinParser` (le parseur),
  `KtNodeTypes` (vocabulaire d'éléments), `KDocParser`, et les utils
  (`KotlinParsing`, `KotlinExpressionParsing`, …).
- `org/jetbrains/kotlin/kmp/utils/` — utilitaires (`Stack`, `StringUtil`,
  `SyntaxElementTypesWithIds`).
- `LICENSE.txt`, `COPYRIGHT.txt` — licence Apache 2.0 amont.

## Re-sync (procédure complète)

Le script `scripts/vendor-grammaire-kotlin.sh` automatise la procédure :

1. Clone sparse du tag voulu (`--filter=blob:none --sparse --depth 1
   --branch <tag>`), checkout de `compiler/multiplatform-parsing` ;
2. Copie de `common/src/{gen,org}` **par remplacement** des répertoires
   existants ;
3. Suppression des `.flex` / `.skeleton` ;
4. Copie de `license/LICENSE.txt` et `license/COPYRIGHT.txt` ;
5. Mise à jour du tag + commit amont dans **ce fichier** ;
6. Bump éventuel de `jetbrains-syntax-api` vers la version que le tag
   amont épingle dans son propre `gradle/libs.versions.toml` ;
7. `./gradlew :lsp:kotlin:check` — la suite de parité dit si la nouvelle
   grammaire produit encore les mêmes arbres sur le corpus du dépôt (la
   baseline `src/test/resources/parite/digeste-corpus.txt` est mise à jour
   délibérément via `-Dparite.majBase=true`, le diff de baseline fait alors
   partie de la revue).

Après un re-sync, la suite de parité doit passer sans régénération SI le
tag amont n'a pas changé la grammaire (cas des re-syncs de version mineure
sans toucher au parsing) ; sinon, la mise à jour de baseline doit être
justifiée en revue (quels fichiers du corpus changent de forme, pourquoi).
