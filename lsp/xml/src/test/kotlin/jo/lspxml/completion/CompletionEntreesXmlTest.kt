package jo.lspxml.completion

import jo.lspxml.parse.ParseurXml
import jo.lspxml.resources.IndexRessources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la complétion du genre VALUES (XE4) — entrées nommées,
 * attributs d'entrée, styles du `parent=` (projet + framework).
 */
class CompletionEntreesXmlTest {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val parseur = ParseurXml()

    /** Catalogue fixture avec thèmes framework. */
    private fun catalogue(): jo.lspxml.catalog.CataloguePlateforme? {
        val dossier = dossierTemp.newFolder("plateforme")
        dossier.resolve("data").apply { mkdirs() }
            .resolve("widgets.txt")
            .writeText("Wandroid.widget.Button android.view.View java.lang.Object\n")
        dossier.resolve("data/res/values").apply { mkdirs() }
            .resolve("attrs.xml")
            .writeText("<resources><declare-styleable name=\"View\"/></resources>")
        dossier.resolve("data/res/values")
            .resolve("public-final.xml")
            .writeText(
                "<resources>" +
                    "<public type=\"style\" name=\"Theme.Material\" id=\"0x1\"/>" +
                    "<public type=\"style\" name=\"Theme.Material.Light\" id=\"0x2\"/>" +
                    "</resources>",
            )
        return jo.lspxml.catalog.CataloguePlateforme.charger(dossier)
    }

    /** Index avec deux styles de projet. */
    private fun index(): IndexRessources {
        val racine = dossierTemp.newFolder("src")
        racine.resolve("res/values").apply { mkdirs() }
            .resolve("styles.xml")
            .writeText(
                "<resources>" +
                    "<style name=\"Theme.App\"/>" +
                    "<style name=\"Theme.App.Sombre\"/>" +
                    "</resources>",
            )
        val index = IndexRessources()
        index.definir(listOf(racine))
        return index
    }

    private fun contexteBalise(source: String, decalage: Int) =
        jo.lspxml.context.ContexteCurseur.analyser(
            source,
            decalage,
            parseur.analyser(source),
        ) as jo.lspxml.context.Contexte.NomDeBalise

    private fun contexteAttribut(source: String, decalage: Int) =
        jo.lspxml.context.ContexteCurseur.analyser(
            source,
            decalage,
            parseur.analyser(source),
        ) as jo.lspxml.context.Contexte.NomAttribut

    private fun contexteValeur(source: String, decalage: Int) =
        jo.lspxml.context.ContexteCurseur.analyser(
            source,
            decalage,
            parseur.analyser(source),
        ) as jo.lspxml.context.Contexte.Valeur

    @Test
    fun `balises — entrees nommees proposees`() {
        val source = "<resources><st"
        val libelles = CompletionEntreesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("string"))
        assertTrue(libelles.contains("style"))
        assertTrue(libelles.contains("string-array"))
        assertTrue(!libelles.contains("item"))
        assertTrue(!libelles.contains("dimen"))
    }

    @Test
    fun `balises — item sous un style`() {
        val source = "<resources><style name=\"s\"><"
        val libelles = CompletionEntreesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("item"))
    }

    @Test
    fun `attributs — name en tete, parent et translatable`() {
        val source = "<resources><string "
        val propositions = CompletionEntreesXml.attributs(contexteAttribut(source, source.length))
        assertEquals("name", propositions.first().libelle)
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("parent"))
        assertTrue(libelles.contains("translatable"))
    }

    @Test
    fun `valeurs — parent propose styles du projet puis framework`() {
        val index = index()
        val catalogue = catalogue()
        // parent=" : styles du PROJET.
        val source = "<resources><style name=\"x\" parent=\""
        val propositions =
            CompletionEntreesXml.valeurs(contexteValeur(source, source.length), catalogue, index)
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("Theme.App"))
        assertTrue(libelles.contains("Theme.App.Sombre"))
        // Préfixe @android:style/ : thèmes du FRAMEWORK.
        val sourceFw = "<resources><style name=\"x\" parent=\"@android:style/Theme.M"
        val libellesFw =
            CompletionEntreesXml.valeurs(contexteValeur(sourceFw, sourceFw.length), catalogue, index)
                .map { it.libelle }
        // Les deux thèmes matchent le préfixe « Theme.M ».
        assertTrue(libellesFw.contains("@android:style/Theme.Material"))
        assertTrue(libellesFw.contains("@android:style/Theme.Material.Light"))
        assertTrue(libellesFw.none { !it.startsWith("@android:style/Theme.M") })
    }

    @Test
    fun `valeurs — hors parent, silence`() {
        val source = "<resources><string name=\"x\" translatable=\"du te"
        assertTrue(
            CompletionEntreesXml.valeurs(
                contexteValeur(source, source.length),
                catalogue(),
                index(),
            ).isEmpty(),
        )
    }
}
