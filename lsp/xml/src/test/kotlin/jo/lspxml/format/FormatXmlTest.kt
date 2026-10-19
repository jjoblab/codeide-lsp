package jo.lspxml.format

import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du FORMATEUR XML borné (S3 — plan de stabilisation LSP §3.3) :
 * ré-indentation canonique depuis le DOM, contenu des lignes JAMAIS
 * touché (commentaires, CDATA, texte), refus honnête sur document
 * cassé, aucune édition superflue sur un document déjà juste.
 */
class FormatXmlTest {
    /** Applique les éditions (ordonnées) à un texte — vérifie le rendu. */
    private fun appliquer(
        texte: String,
        editions: List<jo.lspxml.api.EditionXml>,
    ): String {
        val sb = StringBuilder()
        var curseur = 0
        for (edition in editions) {
            sb.append(texte, curseur, edition.debut)
            sb.append(edition.texte)
            curseur = edition.fin
        }
        sb.append(texte, curseur, texte.length)
        return sb.toString()
    }

    @Test
    fun `re-indente canoniquement un document decale`() {
        val desordonne =
            "<resources>\n<string name=\"a\">Bonjour</string>\n<color name=\"c\">#FFF</color>\n</resources>\n"
        val document = ParseurXml().analyser(desordonne)
        val editions = FormatXml.editer(document, desordonne, 4)
        // Les 2 lignes intérieures (les balises racine ouvrante/fermante
        // sont déjà au niveau 0 — aucune édition superflue).
        assertEquals(2, editions.size)
        val rendu = appliquer(desordonne, editions)
        val attendu =
            "<resources>\n    <string name=\"a\">Bonjour</string>\n    <color name=\"c\">#FFF</color>\n</resources>\n"
        assertEquals(attendu, rendu)
    }

    @Test
    fun `document deja juste — aucune edition`() {
        val juste = "<resources>\n    <string name=\"a\">Bonjour</string>\n</resources>\n"
        val document = ParseurXml().analyser(juste)
        assertTrue(FormatXml.editer(document, juste, 4).isEmpty())
    }

    @Test
    fun `le contenu des lignes voyage intact — commentaires et CDATA`() {
        val source =
            "<resources>\n<!-- catalogue -->\n<string name=\"a\"><![CDATA[<b>x</b>]]> texte</string>\n</resources>\n"
        val document = ParseurXml().analyser(source)
        val rendu = appliquer(source, FormatXml.editer(document, source, 4))
        // Même texte SANS le blanc de tête : seule l'indentation bouge.
        val normalise = { s: String -> s.lines().joinToString("\n") { it.trim() } }
        assertEquals(normalise(source), normalise(rendu))
        assertTrue(rendu.contains("    <!-- catalogue -->"))
        assertTrue(rendu.contains("<b>x</b>]]> texte"))
    }

    @Test
    fun `texte d'une entree values vit un niveau sous ses balises`() {
        val source = "<resources>\n<string name=\"a\">\nBonjour\n</string>\n</resources>\n"
        val document = ParseurXml().analyser(source)
        val rendu = appliquer(source, FormatXml.editer(document, source, 4))
        val lignes = rendu.lines()
        assertEquals("    <string name=\"a\">", lignes[1])
        assertEquals("        Bonjour", lignes[2]) // contenu : 2 niveaux
        assertEquals("    </string>", lignes[3])
    }

    @Test
    fun `balise multi-ligne — les attributs continuent a sa profondeur`() {
        val source =
            "<resources>\n<LinearLayout\nandroid:layout_width=\"1\"\nandroid:layout_height=\"2\">\n" +
                "<TextView/>\n</LinearLayout>\n</resources>\n"
        val document = ParseurXml().analyser(source)
        val rendu = appliquer(source, FormatXml.editer(document, source, 4))
        val lignes = rendu.lines()
        assertEquals("    <LinearLayout", lignes[1])
        assertEquals("        android:layout_width=\"1\"", lignes[2])
        assertEquals("        android:layout_height=\"2\">", lignes[3])
        assertEquals("        <TextView/>", lignes[4])
        assertEquals("    </LinearLayout>", lignes[5])
    }

    @Test
    fun `document casse — refus honnete, liste vide`() {
        // Balise non fermée : pas de re-formatage.
        val casse = "<resources>\n<string name=\"a\">Bonjour\n</resources>\n"
        val document = ParseurXml().analyser(casse)
        assertTrue(FormatXml.editer(document, casse, 4).isEmpty())
    }

    @Test
    fun `lignes vides ne bougent pas — auto-fermante reindentee`() {
        val source = "<resources>\n\n<ImageView/>\n</resources>\n"
        val document = ParseurXml().analyser(source)
        val editions = FormatXml.editer(document, source, 4)
        // La ligne vide ne produit PAS d'édition ; l'auto-fermante sous
        // la racine EST ré-indentée (1 niveau).
        assertEquals(1, editions.size)
        assertEquals("    <ImageView/>", appliquer(source, editions).lines()[2])
    }

    @Test
    fun `taille d'indentation parametrable`() {
        val source = "<r>\n<a/>\n</r>\n"
        val document = ParseurXml().analyser(source)
        val rendu = appliquer(source, FormatXml.editer(document, source, 2))
        assertEquals("<r>\n  <a/>\n</r>\n", rendu)
    }
}
