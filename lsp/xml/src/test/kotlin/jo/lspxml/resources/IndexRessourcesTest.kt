package jo.lspxml.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de l'INDEX des ressources du projet (XE3, §4.1 « resources ») —
 * entrées nommées de `values`, ressources de DOSSIER (layouts…), scan
 * INCREMENTAL par mtime, purge des fichiers disparus.
 */
class IndexRessourcesTest {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    /** Racine source avec res/ complet (values + layout + drawable). */
    private fun racineProjet(): File {
        val racine = dossierTemp.newFolder("src")
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText(
            "<resources>" +
                "<string name=\"app_nom\">CodeIDE</string>" +
                "<string name=\"autre\">Autre</string>" +
                "<dimen name=\"marge\">8dp</dimen>" +
                "</resources>",
        )
        // Le COLOR vit dans styles.xml : il doit SURVIVRE à la
        // suppression de strings.xml (fixture du test de purge).
        values.resolve("styles.xml").writeText(
            "<resources>" +
                "<color name=\"principal\">#FF0000</color>" +
                "<style name=\"Theme.App\" parent=\"Theme.Material\"/>" +
                "</resources>",
        )
        val layout = racine.resolve("res/layout").apply { mkdirs() }
        layout.resolve("activity_main.xml").writeText("<merge/>")
        racine.resolve("res/layout-land").apply { mkdirs() }
            .resolve("secondaire.xml").writeText("<merge/>")
        val drawable = racine.resolve("res/drawable").apply { mkdirs() }
        drawable.resolve("ic_logo.xml").writeText("<vector/>")
        drawable.resolve("photo.png").writeBytes(byteArrayOf(1))
        return racine
    }

    @Test
    fun `scan — entrees nommees et ressources de dossier`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        assertTrue(index.estChaud)
        // Entrées nommées (values).
        assertEquals(
            listOf("app_nom", "autre"),
            index.nomsParType("string"),
        )
        assertEquals(listOf("principal"), index.nomsParType("color"))
        assertEquals(listOf("marge"), index.nomsParType("dimen"))
        assertEquals(listOf("Theme.App"), index.nomsParType("style"))
        // Ressources de dossier (layout, layout-land, drawable).
        assertEquals(setOf("activity_main", "secondaire"), index.nomsParType("layout").toSet())
        assertEquals(setOf("ic_logo", "photo"), index.nomsParType("drawable").toSet())
        // Le déclarant d'une entrée nommée pointe le FICHIER + la plage du nom.
        val declaration = index.resoudre("string", "app_nom")
        assertNotNull(declaration)
        assertEquals("strings.xml", declaration!!.fichier.name)
        assertTrue(declaration.plage.debut > 0)
        // Le déclarant d'une ressource de dossier : le fichier, plage 0.
        val layout = index.resoudre("layout", "activity_main")
        assertNotNull(layout)
        assertEquals("activity_main.xml", layout!!.fichier.name)
        assertEquals(0, layout.plage.debut)
    }

    @Test
    fun `racines sans res ignorees — aucune entree`() {
        val index = IndexRessources()
        val jvm = dossierTemp.newFolder("jvm")
        jvm.resolve("Main.java").writeText("class Main {}")
        index.definir(listOf(jvm))
        // Les racines SONT définies (pas de dumb mode) mais sans res/ :
        // aucune entrée — le verdict « non résolu » resterait honnête.
        assertTrue(index.estChaud)
        assertTrue(index.types().isEmpty())
        // SANS racine : index froid (dumb mode, X4).
        assertFalse(IndexRessources().estChaud)
    }

    @Test
    fun `resolution — inconnu null, deuxieme declarant resolu`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        assertNull(index.resoudre("string", "inconnu"))
        assertNull(index.resoudre("genreAbsent", "x"))
        assertNotNull(index.resoudre("drawable", "photo"))
    }

    @Test
    fun `scan increment — mtime inchangé ne re-parse pas, fichier change re-scanne`() {
        val racine = racineProjet()
        val index = IndexRessources()
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "autre"), index.nomsParType("string"))
        // Ré-définition des MÊMES racines : le fichier inchangé garde
        // son entrée (mtime identique — pas de re-parse).
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "autre"), index.nomsParType("string"))
        // Ajout d'une entrée : le fichier CHANGE (mtime), re-scan.
        racine.resolve("res/values/strings.xml").writeText(
            "<resources>" +
                "<string name=\"app_nom\">CodeIDE</string>" +
                "<string name=\"nouvelle\">X</string>" +
                "</resources>",
        )
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "nouvelle"), index.nomsParType("string"))
    }

    @Test
    fun `purge — un fichier disparu retire ses entrées`() {
        val racine = racineProjet()
        val index = IndexRessources()
        index.definir(listOf(racine))
        assertNotNull(index.resoudre("color", "principal"))
        racine.resolve("res/values/strings.xml").delete()
        index.definir(listOf(racine))
        assertNull("les entrées du fichier disparu partent", index.resoudre("string", "app_nom"))
        // Les AUTRES fichiers survivent.
        assertNotNull(index.resoudre("color", "principal"))
    }

    @Test
    fun `vider — tout part`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        index.vider()
        assertFalse(index.estChaud)
        assertTrue(index.types().isEmpty())
    }
}
