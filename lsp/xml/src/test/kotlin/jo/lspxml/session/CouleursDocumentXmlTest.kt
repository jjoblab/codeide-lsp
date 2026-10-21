package jo.lspxml.session

import jo.lspxml.api.CanauxCouleurXml
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.ServiceMoteurLangageXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests des COULEURS DE DOCUMENT XML (lot L3 du plan 1.3.0) — par le
 * SPI (chemin de production D6), moteur RÉEL, montage projet (le
 * déclarant `values` vit sur disque, l'index le prouve).
 *
 * Le cœur éprouvé : les QUATRE formes de littéraux (#RGB, #ARGB,
 * #RRGGBB, #AARRGGBB — alpha en tête), les références @color RÉSOLUES
 * par l'index (la valeur du déclarant), et l'honnêteté D3 : une
 * référence non résolue ne rend RIEN, jamais de couleur inventée.
 */
class CouleursDocumentXmlTest {
    /** Dossier temporaire du projet (racines des ressources). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    /** Décalage du PREMIER caractère de [occurrence] dans [texte]. */
    private fun decalageDe(
        texte: String,
        occurrence: String,
    ): Int = texte.indexOf(occurrence)

    private fun sessionNeuve() = ServiceMoteurLangageXml.unique().creerSession()

    @Test
    fun `litteraux - les quatre formes, canaux 0 a 1, alpha en tete`() {
        val texte =
            "<a fond=\"#FF0000\" " +
                "canal=\"#F0F0\" " +
                "simple=\"#ABC\" " +
                "opaque=\"#80FF00FF\" />"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        val couleurs = session.couleurs("fixture://x.xml")
        assertEquals(4, couleurs.size)
        val parFond = couleurs.associateBy { texte.substring(it.debut - 1, it.fin) }
        // #RRGGBB : rouge pur.
        val rouge = parFond.getValue("#FF0000")
        assertEquals(1.0, rouge.rouge, 0.001)
        assertEquals(0.0, rouge.vert, 0.001)
        assertEquals(0.0, rouge.bleu, 0.001)
        assertEquals(1.0, rouge.alpha, 0.001)
        // #ARGB (canal simple) : alpha F, rouge 0, vert 0, bleu 0.
        val canal = parFond.getValue("#F0F0")
        assertEquals(1.0, canal.alpha, 0.01)
        assertEquals(0.0, canal.rouge, 0.01)
        // #RGB : ABC → canaux simples.
        val simple = parFond.getValue("#ABC")
        assertEquals(10 / 15.0, simple.rouge, 0.01)
        assertEquals(11 / 15.0, simple.vert, 0.01)
        assertEquals(12 / 15.0, simple.bleu, 0.01)
        // #AARRGGBB : alpha 0x80 en TÊTE, rouge FF.
        val translucide = parFond.getValue("#80FF00FF")
        assertEquals(0x80 / 255.0, translucide.alpha, 0.001)
        assertEquals(1.0, translucide.rouge, 0.001)
        assertEquals(0.0, translucide.vert, 0.001)
        assertEquals(1.0, translucide.bleu, 0.001)
        session.close()
    }

    @Test
    fun `reference color resolue par l index - pastille du declarant`() {
        val couleursValues =
            "<resources>" +
                "<color name=\"principal\">#00FF00</color>" +
                "</resources>"
        val racine = dossierTemp.newFolder("src${System.nanoTime()}")
        File(racine, "res/values/colors.xml").apply {
            parentFile.mkdirs()
            writeText(couleursValues)
        }
        val layout = "<a fond=\"@color/principal\" />"
        val session = sessionNeuve()
        session.definirClasspath(
            ClasspathXml(dossierPlateforme = null, racinesSources = listOf(racine), vueNoms = null),
        )
        session.ouvrirDocument("fixture://x.xml", layout)
        val couleurs = session.couleurs("fixture://x.xml")
        // La référence RÉSOLUE : pastille verte sur le SEGMENT nom.
        assertEquals(1, couleurs.size)
        val couleur = couleurs.single()
        assertEquals(decalageDe(layout, "principal"), couleur.debut)
        assertEquals(0.0, couleur.rouge, 0.001)
        assertEquals(1.0, couleur.vert, 0.001)
        assertEquals(0.0, couleur.bleu, 0.001)
        session.close()
    }

    @Test
    fun `presentation - libelle canonique et edition, non resolue vide, inconnu vide`() {
        val texte = "<a fond=\"#FF8800\" autre=\"@color/absente\" />"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        val presentations =
            session.presentationCouleur(
                "fixture://x.xml",
                decalageDe(texte, "FF8800"),
                decalageDe(texte, "FF8800") + "FF8800".length,
                CanauxCouleurXml(
                    rouge = 1.0,
                    vert = 0x88 / 255.0,
                    bleu = 0.0,
                    alpha = 1.0,
                ),
            )
        // Le libellé canonique opaque + l'édition de la plage d'origine.
        assertEquals(1, presentations.size)
        assertEquals("#ff8800", presentations.single().libelle)
        assertEquals(decalageDe(texte, "FF8800"), presentations.single().debut)
        // Référence NON résolue : aucune couleur, aucune présentation.
        assertTrue(session.couleurs("fixture://x.xml").none { it.debut == decalageDe(texte, "absente") })
        // Document inconnu : vide, jamais d'exception.
        assertTrue(session.couleurs("fixture://inconnu.xml").isEmpty())
        session.close()
    }
}
