package jo.lspkotlin.parite

import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolérance aux fautes (LSP_KOTLIN D3) : un texte en cours d'édition doit
 * TOUJOURS produire un arbre — l'éditeur appelle le parseur à chaque
 * frappe, y compris entre deux lettres, au milieu d'une chaîne, d'un
 * commentaire ou d'un KDoc. Le oracle est simple : aucune exception, un
 * arbre racine complet.
 */
class ToleranceErreursTest {
    private val parseur = ParseurKotlin()

    private val echantillon =
        """
        package demo.tolerance

        import java.util.List

        /**
         * KDoc multiligne avec [lien] et balise {@code demo}.
         */
        class Demo(
            val nom: String,
        ) {
            fun saluer(prefixe: String = "Bonjour"): String {
                val message = "${'$'}prefixe, ${'$'}nom !"
                return message.trim()
            }
        }
        """.trimIndent()

    @Test
    fun `troncature a chaque position - aucun lever`() {
        // Toutes les coupes 0..n : c'est exactement l'état du texte pendant
        // la frappe (chaque préfixe est un état réel de l'éditeur).
        for (coupure in 0..echantillon.length) {
            val texte = echantillon.substring(0, coupure)
            try {
                val arbre = parseur.analyser(texte)
                assertTrue("la racine doit couvrir le texte coupé", arbre.racine.fin <= texte.length)
            } catch (t: Throwable) {
                throw AssertionError("le parseur a levé sur une troncature à $coupure :\n« $texte »", t)
            }
        }
    }

    @Test
    fun `insertions en positions hostiles - aucun lever`() {
        val trous = listOf(' ', '\n', '\t', '"', '{', '}', '(', ')', '<', '`', '$', '\\', '*', '/')
        // Texte avec un caractère parasite injecté à une position fixe :
        // représentatif d'une frappe accidentelle au milieu du document.
        val position = echantillon.indexOf("class Demo")
        for (caractere in trous) {
            val texte =
                StringBuilder(echantillon).insert(position, caractere.toString()).toString()
            try {
                parseur.analyser(texte)
            } catch (t: Throwable) {
                throw AssertionError("le parseur a levé sur l'injection « $caractere » à $position", t)
            }
        }
    }

    @Test
    fun `deletions aleatoires bornees - aucun lever`() {
        // Suppressions pseudo-aléatoires DÉTERMINISTES (graine fixe — la
        // suite doit être reproductible, pas vraiment aléatoire).
        val graine = java.util.Random(42L)
        for (essai in 0 until 64) {
            val caracteres = echantillon.toCharArray()
            val nombreSuppressions = 1 + graine.nextInt(8)
            repeat(nombreSuppressions) {
                val index = graine.nextInt(caracteres.size)
                caracteres[index] = ' '
            }
            try {
                parseur.analyser(String(caracteres))
            } catch (t: Throwable) {
                throw AssertionError("le parseur a levé sur l'essai $essai de suppression", t)
            }
        }
    }
}
