package jo.lspkotlin.documentation

import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.parse.kt.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de KDoc (S2 — survol riche, KDocParser vendoré) :
 * extraction du DOC_COMMENT porté par la déclaration, rendu en texte
 * neutre (sections libres + tags). Tolérance D3 : rien ne lève.
 */
class DocumentationKotlinTest {
    private val parseur = ParseurKotlin()

    /** Première déclaration du fichier analysé. */
    private fun premiereDeclaration(texte: String) = KtFile(parseur.analyser(texte).racine).declarations().single()

    @Test
    fun `kdoc simple - texte libre rendu, asteriskes et blancs detaches`() {
        val declaration =
            premiereDeclaration(
                "/**\n" +
                    " * La vue de rendu.\n" +
                    " * Un paragraphe de plus.\n" +
                    " */\n" +
                    "class Rendu\n",
            )
        assertEquals(
            "La vue de rendu.\nUn paragraphe de plus.",
            DocumentationKotlin.kdocDe(declaration),
        )
    }

    @Test
    fun `kdoc avec tags - param et return rendus en lignes`() {
        val declaration =
            premiereDeclaration(
                "/**\n" +
                    " * Calcule la taille.\n" +
                    " *\n" +
                    " * @param x le nombre de colonnes\n" +
                    " * @param y la hauteur\n" +
                    " * @return le total\n" +
                    " */\n" +
                    "class Rendu(val x: Int, val y: Int) {\n" +
                    "    fun total(): Int = x + y\n" +
                    "}\n",
            )
        assertEquals(
            "Calcule la taille.\n" +
                "@param x — le nombre de colonnes\n" +
                "@param y — la hauteur\n" +
                "@return le total",
            DocumentationKotlin.kdocDe(declaration),
        )
    }

    @Test
    fun `kdoc d un membre - fonction documentée`() {
        val classe =
            premiereDeclaration(
                "class A {\n" +
                    "    /** Rend la taille. */\n" +
                    "    fun taille(): Int = 1\n" +
                    "}\n",
            ) as jo.lspkotlin.parse.kt.KtClass
        val membre = classe.membres().single()
        assertEquals("Rend la taille.", DocumentationKotlin.kdocDe(membre))
    }

    @Test
    fun `kdoc avant annotations - porte quand meme par la declaration`() {
        val declaration =
            premiereDeclaration(
                "/** La doc malgré l'annotation. */\n" +
                    "@Deprecated\n" +
                    "class Vieux\n",
            )
        assertEquals("La doc malgré l'annotation.", DocumentationKotlin.kdocDe(declaration))
    }

    @Test
    fun `sans kdoc - null`() {
        val declaration = premiereDeclaration("class Nue\n")
        assertNull(DocumentationKotlin.kdocDe(declaration))
    }

    @Test
    fun `block comment n est pas un kdoc - null`() {
        val declaration =
            premiereDeclaration(
                "/* un bloc, pas une doc */\n" +
                    "class A\n",
            )
        assertNull(DocumentationKotlin.kdocDe(declaration))
    }

    @Test
    fun `kdoc vide - etoiles seules, null`() {
        val declaration =
            premiereDeclaration(
                "/**\n" +
                    " *\n" +
                    " */\n" +
                    "class A\n",
            )
        assertNull(DocumentationKotlin.kdocDe(declaration))
    }

    @Test
    fun `kdoc une ligne - rendu sans entourage`() {
        val declaration = premiereDeclaration("/** Doc tenue courte. */\nclass A\n")
        assertEquals("Doc tenue courte.", DocumentationKotlin.kdocDe(declaration))
    }

    @Test
    fun `tag sans contenu - rendu degage sans exception`() {
        val declaration =
            premiereDeclaration(
                "/**\n * Rend.\n * @return\n */\nclass A\n",
            )
        val rendu = DocumentationKotlin.kdocDe(declaration)
        assertTrue("le tag nu est rendu", rendu!!.contains("@return"))
        assertTrue("le texte libre est rendu", rendu.contains("Rend."))
    }
}
