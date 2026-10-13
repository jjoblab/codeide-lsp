package jo.lspxml.rules

import jo.lspxml.api.VueNomsXml
import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des règles XE4 — MANIFEST (package, composants nommés) et
 * BALISE INCONNUE (couture conservatrice X4 : catalogue + vue de noms
 * TOUS d'accord).
 */
class ReglesXe4Test {
    private val parseur = ParseurXml()

    private fun manifeste(source: String) = parseur.analyser(source)

    // ── Manifest ───────────────────────────────────────────────────

    @Test
    fun `manifest sans package — erreur`() {
        val diagnostics =
            ReglesManifeste.diagnostiquer(manifeste("<manifest><application/></manifest>"))
        assertEquals(listOf("xml.manifestSansPackage"), diagnostics.map { it.code })
    }

    @Test
    fun `manifest avec package — silence`() {
        val diagnostics =
            ReglesManifeste.diagnostiquer(
                manifeste("<manifest package=\"com.exemple\"><application/></manifest>"),
            )
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `composant sans android name — erreur, connu — silence`() {
        val source =
            "<manifest package=\"p\"><application>" +
                "<activity android:name=\".Ok\"/>" +
                "<activity/>" +
                "<service android:name=\".S\"/>" +
                "<service/>" +
                "<provider/>" +
                "</application></manifest>"
        val diagnostics = ReglesManifeste.diagnostiquer(manifeste(source))
        assertEquals(
            3,
            diagnostics.count { it.code == "xml.composantSansNom" },
        )
    }

    @Test
    fun `hors manifest — les règles ne s'appliquent pas`() {
        // La règle ne voit que les racines <manifest> (le genre est
        // déjà déduit de l'URI par l'appelant).
        val diagnostics =
            ReglesManifeste.diagnostiquer(manifeste("<activity/>"))
        assertTrue(diagnostics.isEmpty())
    }

    // ── Balise inconnue (couture X4) ───────────────────────────────

    /** Vue de noms factice : ne connaît que « MonBouton ». */
    private class VueNomsFactice(private val connues: Set<String>) : VueNomsXml {
        override fun nomsCommencantPar(prefixe: String, limite: Int): List<String> =
            connues.filter { it.endsWith(".$prefixe") || it == prefixe }
    }

    @Test
    fun `balise inconnue — catalogue et vue d'accord`() {
        val catalogue =
            CatalogueFixture.avecBalises(setOf("TextView", "Button", "LinearLayout"))
        val vue = VueNomsFactice(emptySet())
        val document = parseur.analyser("<LinearLayout><TextVie/></LinearLayout>")
        val diagnostics = ReglesTagInconnu.diagnostiquer(document, catalogue, vue)
        assertEquals(listOf("xml.tagInconnu"), diagnostics.map { it.code })
        // TextVie est bien le NOM visé.
        assertEquals("TextVie", document.racine?.enfants?.single()?.nom)
    }

    @Test
    fun `balise du catalogue — silence`() {
        val catalogue = CatalogueFixture.avecBalises(setOf("TextView", "LinearLayout"))
        val vue = VueNomsFactice(emptySet())
        val document = parseur.analyser("<LinearLayout><TextView/></LinearLayout>")
        assertTrue(ReglesTagInconnu.diagnostiquer(document, catalogue, vue).isEmpty())
    }

    @Test
    fun `balise du CLASSPATH (vue de noms) — silence`() {
        val catalogue = CatalogueFixture.avecBalises(setOf("TextView", "LinearLayout"))
        val vue = VueNomsFactice(setOf("com.exemple.MonBouton"))
        val document = parseur.analyser("<LinearLayout><MonBouton/></LinearLayout>")
        assertTrue(ReglesTagInconnu.diagnostiquer(document, catalogue, vue).isEmpty())
    }

    @Test
    fun `garde X4 — catalogue ou vue absents = silence total`() {
        val catalogue = CatalogueFixture.avecBalises(setOf("TextView"))
        val document = parseur.analyser("<LinearLayout><TextVie/></LinearLayout>")
        assertTrue(ReglesTagInconnu.diagnostiquer(document, null, null).isEmpty())
        val vue = VueNomsFactice(emptySet())
        assertTrue(ReglesTagInconnu.diagnostiquer(document, null, vue).isEmpty())
        assertTrue(ReglesTagInconnu.diagnostiquer(document, catalogue, null).isEmpty())
    }

    @Test
    fun `balises speciales et qualifiees — jamais signalees`() {
        val catalogue = CatalogueFixture.avecBalises(setOf("TextView"))
        val vue = VueNomsFactice(emptySet())
        val document =
            parseur.analyser("<merge><include layout=\"@layout/x\"/><androidx.Foo/></merge>")
        assertTrue(ReglesTagInconnu.diagnostiquer(document, catalogue, vue).isEmpty())
    }
}

/** Mini-catalogue à la volée (widgets + un styleable). */
internal object CatalogueFixture {
    fun avecBalises(balises: Set<String>): jo.lspxml.catalog.CataloguePlateforme? {
        val dossier =
            java.io.File(
                System.getProperty("java.io.tmpdir"),
                "fixture-catalogue-${System.nanoTime()}",
            ).apply { mkdirs() }
        dossier.resolve("data").apply { mkdirs() }
            .resolve("widgets.txt")
            .writeText(
                balises.joinToString("\n") { nom ->
                    "Wandroid.widget.$nom android.view.View java.lang.Object"
                },
            )
        dossier.resolve("data/res/values").apply { mkdirs() }
            .resolve("attrs.xml")
            .writeText("<resources><declare-styleable name=\"View\"/></resources>")
        return jo.lspxml.catalog.CataloguePlateforme.charger(dossier)
    }
}
