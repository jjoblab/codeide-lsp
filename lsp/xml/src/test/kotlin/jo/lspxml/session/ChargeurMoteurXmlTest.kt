package jo.lspxml.session

import jo.lspxml.api.ServiceMoteurLangageXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de pack du moteur XML (XE1, miroir ChargeurMoteurKotlinTest) —
 * le descripteur `jo/lspxml/pack/version.properties` fait PARTIE du
 * contrat : un moteur qui y recourt (repli « inconnue ») est un bug de
 * packaging, le test échoue explicitement.
 */
class ChargeurMoteurXmlTest {
    @Test
    fun `ServiceLoader resolve le moteur unique`() {
        val moteur = ServiceMoteurLangageXml.unique()
        assertEquals("jo.codeide.lsp.xml", moteur.identifiant)
    }

    @Test
    fun `le descripteur de pack est complet — pas de repli inconnue`() {
        val moteur = ServiceMoteurLangageXml.unique()
        assertEquals("jo.codeide.lsp.xml", moteur.identifiant)
        assertTrue(
            "version de pack lisible : « ${moteur.version} »",
            moteur.version != "inconnue",
        )
        assertTrue(
            "version minimale d'app lisible : « ${moteur.versionMinimaleApp} »",
            moteur.versionMinimaleApp != "inconnue",
        )
    }

    @Test
    fun `analyserSource — le meme parseur que la session`() {
        val moteur = ServiceMoteurLangageXml.unique()
        val document = moteur.analyserSource("<a><b/></a>")
        assertEquals("a", document.racine?.nom)
        assertEquals("b", document.racine?.enfants?.single()?.nom)
    }

    @Test
    fun `creerSession — sessions distinctes, etats confines`() {
        val moteur = ServiceMoteurLangageXml.unique()
        val premiere = moteur.creerSession()
        val seconde = moteur.creerSession()
        premiere.ouvrirDocument("file:///a.xml", "<a/>")
        assertEquals(1, premiere.nombreDocuments)
        assertEquals(0, seconde.nombreDocuments)
        premiere.close()
        // La seconde vit sa vie (confitement par session).
        seconde.ouvrirDocument("file:///b.xml", "<b/>")
        assertEquals(1, seconde.nombreDocuments)
        seconde.close()
    }
}
