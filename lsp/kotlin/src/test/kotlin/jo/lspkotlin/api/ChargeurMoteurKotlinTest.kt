package jo.lspkotlin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du SPI : résolution ServiceLoader du moteur (le contrat de pack
 * LSP_KOTLIN §6 doit être vérifié MÉCANIQUEMENT, pas seulement revu) et
 * intégrité du descripteur de pack.
 */
class ChargeurMoteurKotlinTest {
    @Test
    fun `serviceLoader resolve le moteur unique`() {
        val moteur = ServiceMoteurLangageKotlin.unique()
        assertEquals(MoteurKotlinVendore::class.java, moteur::class.java)
    }

    @Test
    fun `descripteur de pack complet - valeurs reelles pas des replis`() {
        // Les valeurs « inconnue » du moteur sont des repli de secours :
        // si le descripteur version.properties est absent du classpath,
        // c'est le CONTRAT de pack qui est cassé (D6) — le test doit le
        // dire, pas l'absorber.
        val moteur = ServiceMoteurLangageKotlin.unique()
        assertEquals("jo.codeide.lsp.kotlin", moteur.identifiant)
        assertNotEquals("inconnue", moteur.version)
        assertNotEquals("inconnue", moteur.versionGrammaire)
        assertTrue(
            "version minimale illisible : ${moteur.versionMinimaleApp}",
            motifVersion(moteur.versionMinimaleApp),
        )
    }

    @Test
    fun `le moteur analyse via le spi - arbre non vide`() {
        val arbre = ServiceMoteurLangageKotlin.unique().analyserSource("val essai = 1\n")
        assertEquals("kotlin.FILE", arbre.racine.type.toString())
        assertEquals(0, arbre.nombreErreurs)
    }

    private fun motifVersion(texte: String): Boolean = Regex("""\d+\.\d+\.\d+""").matches(texte)
}
