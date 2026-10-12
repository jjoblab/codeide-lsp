package jo.lspkotlin.parse

import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du parseur sur des sources représentatives (l'autre moitié de la
 * suite de parité K1 : le corpus balaye le dépôt entier, ces fixtures
 * épinglent des formes précises, jeton par jeton).
 */
class ParseurKotlinTest {
    private val parseur = ParseurKotlin()

    @Test
    fun `fichier vide - arbre racine KT_FILE sans erreur`() {
        val arbre = parseur.analyser("")
        assertEquals("kotlin.FILE", arbre.racine.type.toString())
        assertEquals(0, arbre.nombreErreurs)
    }

    @Test
    fun `accesseurs non whitespace - texte du jeton exact`() {
        val arbre = parseur.analyser("val x = 1")
        val jetons = arbre.racine.jetonsDescendants()
        val identifiant =
            checkNotNull(jetons.firstOrNull { it.type == KtTokens.IDENTIFIER }) {
                "jeton IDENTIFIER attendu dans « val x = 1 »"
            }
        assertEquals("x", identifiant.texte.toString())
    }

    @Test
    fun `fichier valide complet - aucun marqueur d'erreur`() {
        val source =
            """
            package demo.parity

            import java.util.ArrayList as JArrayList
            import kotlin.collections.*

            /**
             * KDoc de la classe [Demo].
             */
            class Demo(
                val nom: String,
                private val age: Int = 0,
            ) : Comparable<Demo> {
                companion object {
                    const val MAX: Int = 42
                }

                var compteur: Int = 0
                    get() = field
                    set(value) { field = value }

                fun saluer(prefix: String = "Bonjour"): String = "${'$'}prefix ${'$'}nom"

                override fun compareTo(other: Demo): Int = age - other.age
            }

            object Singleton {
                fun instance(): Singleton = this
            }

            typealias ListeDemo = List<Demo>

            enum class Couleur { ROUGE, VERT, BLEU }

            fun principale() {
                val liste = JArrayList<Demo>()
                val demo = Demo("alpha")
                when (demo.nom) {
                    "alpha" -> println(demo.saluer())
                    else -> println("inconnu")
                }
            }
            """.trimIndent()
        val arbre = parseur.analyser(source)
        assertEquals("la source valide ne doit produire aucune erreur", 0, arbre.nombreErreurs)
        assertEquals("kotlin.FILE", arbre.racine.type.toString())
    }

    @Test
    fun `accolade manquante - arbre construit et erreur marquee`() {
        // Philosopherie D3 : la faute est TRANSPORTÉE, jamais fatale.
        val arbre = parseur.analyser("class Incomplete {\n    fun a(): Int = 1\n")
        assertTrue("au moins un marqueur d'erreur attendu", arbre.nombreErreurs > 0)
        assertTrue(arbre.racine.fin <= "class Incomplete {\n    fun a(): Int = 1\n".length)
    }

    @Test
    fun `texte tronque en plein identifiant - parse sans lever`() {
        val arbre = parseur.analyser("fun fonctionTronqu")
        assertEquals("kotlin.FILE", arbre.racine.type.toString())
    }

    @Test
    fun `chaine non fermee - parse sans lever`() {
        val arbre = parseur.analyser("val s = \"chaine sans fin")
        assertTrue(arbre.nombreErreurs > 0)
    }

    @Test
    fun `script kts - directives autorisees en racine`() {
        val arbre = parseur.analyser("println(\"hello\")\nval x = 2\n", script = true)
        assertEquals(0, arbre.nombreErreurs)
    }

    @Test
    fun `determinisme - deux analyses du meme texte donnent le meme digeste`() {
        val source = "class A { fun b(): Int = 2 }\n"
        assertEquals(parseur.analyser(source).digeste(), parseur.analyser(source).digeste())
    }

    @Test
    fun `positions - offsets de jetons dans le texte source`() {
        val source = "class Positionnee"
        val arbre = parseur.analyser(source)
        val jetonClasse = arbre.racine.jetonsDescendants().first { it.type == KtTokens.CLASS_KEYWORD }
        assertEquals(0, jetonClasse.debut)
        assertEquals(5, jetonClasse.fin)
        assertEquals("class", jetonClasse.texte.toString())
    }
}
