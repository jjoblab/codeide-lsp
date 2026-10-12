package jo.lspkotlin.parse

import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import org.jetbrains.kotlin.kmp.lexer.KotlinLexer
import org.jetbrains.kotlin.kmp.parser.KotlinParser

/**
 * Façade d'analyse syntaxique Kotlin — point d'entrée K1 du LSP Kotlin
 * (docs/LSP_KOTLIN.md §4.2, étape K1).
 *
 * Le parseur réel est la grammaire multiplateforme du compilateur Kotlin,
 * vendorée dans `src/main/vendor` (Apache 2.0, ADR 0115) : cette classe
 * n'est que la mise en œuvre de la chaîne documentée par l'amont —
 * lexification (`performLexing` + `KotlinLexer`), construction de
 * l'arbre (`SyntaxTreeBuilderFactory`), parse (`KotlinParser.parse`) puis
 * reconstruction en arbre immuable exploitable ([ArbreSyntaxiqueKotlin]).
 *
 * Tolérance aux fautes (D3) : AUCUNE entrée de texte ne lève — un texte
 * invalide ou en cours d'édition produit un arbre marqué d'erreurs
 * ([NoeudSyntaxique.marqueErreur]), la grammaire du compilateur étant
 * intrinsèquement tolérante. Un appel ne peut échouer que sur un état
 * interne corrompu (erreur de programmation, pas de donnée).
 *
 * Usage unique par document analysé ; immuable et réutilisable en toute
 * sécurité concurrentielle (aucun état partagé mutable).
 */
public class ParseurKotlin {
    /**
     * Analyse un texte source Kotlin complet.
     *
     * @param texte source (aucune hypothèse sur la validité).
     * @param script vrai pour un script `.kts` (libre en racine, pas de
     *        directive package/imports contraignante).
     * @return l'arbre syntaxique reconstruit, toujours — voir la KDoc de
     *         classe sur la tolérance.
     */
    public fun analyser(texte: CharSequence, script: Boolean = false): ArbreSyntaxiqueKotlin {
        val parseur = KotlinParser(isScript = script, isLazy = false)
        // Paramètres positionnels : les noms amont (text/lexer…) ne sont
        // pas stables entre versions de syntax-api — la position, si.
        val jetons = performLexing(texte, KotlinLexer(), null, null)
        val builder =
            SyntaxTreeBuilderFactory
                .builder(texte, jetons, parseur.whitespaces, parseur.comments)
                .withStartOffset(0)
                .withWhitespaceOrCommentBindingPolicy(parseur.whitespaceOrCommentBindingPolicy)
                .build()
        parseur.parse(builder)
        return ReconstructeurArbre.construire(builder)
    }
}
