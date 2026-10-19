package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du RENOMMAGE BORNÉ (S3 — plan de stabilisation LSP §3.3) :
 * `plageRenommable` (prepareRename) et `renommage` (rename) de la
 * session XML — référence `@type/nom`, déclarante `values`, bornage
 * (documents ouverts + déclarants de l'index), honnêteté (framework,
 * ressources par fichier, sans cible).
 */
class RenommageXmlTest {
    /** Dossiers temporaires (JUnit4 — plateforme fixture XE2). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val session = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())

    /** values/strings.xml déclarant `titre`, sur disque (fermé par défaut). */
    private lateinit var stringsXml: File

    private fun racineSources(): File {
        val racine = dossierTemp.newFolder("src", "main")
        val values = racine.resolve("res/values").apply { mkdirs() }
        stringsXml =
            values.resolve("strings.xml").apply {
                writeText(
                    "<resources>\n    <string name=\"titre\">Bonjour</string>\n</resources>",
                )
            }
        return racine
    }

    private fun preparer(): String {
        val racine = racineSources()
        session.definirClasspath(
            ClasspathXml(dossierPlateforme = null, racinesSources = listOf(racine), vueNoms = null),
        )
        return "file://" + stringsXml.absolutePath
    }

    /** Le layout de référence (curseur sur `titre` de @string/titre). */
    private fun layoutUri(texte: String): Pair<String, Int> {
        val uri = "file://" + dossierTemp.root.resolve("src/main/res/layout/main.xml").absolutePath
        session.ouvrirDocument(uri, texte)
        return uri to texte.indexOf("@string/titre")
    }

    @Test
    fun `prepareRename sur une reference projet rend la plage du nom`() {
        preparer()
        val (uri, base) = layoutUri("<TextView android:text=\"@string/titre\"/>")
        // Curseur sur le SEGMENT nom (après le `@string/`).
        val plage = session.plageRenommable(uri, base + "@string/".length + 2)
        assertNotNull(plage)
        assertEquals("titre", plage!!.placeholder)
        assertEquals("titre", session.ouvrirTexte(uri).substring(plage.plage.debut, plage.plage.fin))
    }

    @Test
    fun `prepareRename sur la declarante values rend la plage du name`() {
        val uriDecl = preparer()
        val texte = stringsXml.readText()
        session.ouvrirDocument(uriDecl, texte)
        val base = texte.indexOf("name=\"titre\"") + "name=\"".length
        val plage = session.plageRenommable(uriDecl, base + 1)
        assertNotNull(plage)
        assertEquals("titre", plage!!.placeholder)
        assertEquals("titre", texte.substring(plage.plage.debut, plage.plage.fin))
    }

    @Test
    fun `prepareRename sur une reference framework refuse`() {
        preparer()
        val (uri, _) = layoutUri("<TextView android:text=\"@android:string/ok\"/>")
        val texte = session.ouvrirTexte(uri)
        val base = texte.indexOf("@android:string/ok")
        assertNull(session.plageRenommable(uri, base + 5))
    }

    @Test
    fun `prepareRename sur une ressource par fichier refuse`() {
        preparer()
        val (uri, _) = layoutUri("<include layout=\"@layout/autre\"/>")
        val texte = session.ouvrirTexte(uri)
        val base = texte.indexOf("@layout/autre")
        assertNull(session.plageRenommable(uri, base + "@layout/".length + 1))
    }

    @Test
    fun `renommage met a jour declarant disque et references des ouverts`() {
        val uriDecl = preparer()
        val l1 = layoutUri("<TextView android:text=\"@string/titre\"/>")
        val texte2 = "<TextView android:text=\"@string/titre\" android:id=\"@+id/a\"/>"
        val uri2 = "file://" + dossierTemp.root.resolve("src/main/res/layout/second.xml").absolutePath
        session.ouvrirDocument(uri2, texte2)

        val emplacements = session.renommage(l1.first, l1.second + "@string/".length + 1)
        assertEquals(3, emplacements.size)
        // DÉCLARATION d'abord — le fichier déclarant fermé (disque).
        val declaration = emplacements[0]
        assertEquals(uriDecl, declaration.uri)
        val texteDecl = stringsXml.readText()
        assertEquals("titre", texteDecl.substring(declaration.debut, declaration.fin))
        // RÉFÉRENCES ensuite — les deux documents ouverts.
        val urisRefs = emplacements.drop(1).map { it.uri }.toSet()
        assertEquals(setOf(l1.first, uri2), urisRefs)
        for (ref in emplacements.drop(1)) {
            assertEquals("titre", session.ouvrirTexte(ref.uri).substring(ref.debut, ref.fin))
        }
    }

    @Test
    fun `renommage prefere le tampon ouvert du declarant`() {
        val uriDecl = preparer()
        // Le déclarant est OUVERT avec une modification non sauvegardée.
        val texteVif =
            "<resources>\n    <string name=\"titre\">Vif</string>\n    <string name=\"autre\">X</string>\n</resources>"
        session.ouvrirDocument(uriDecl, texteVif)
        val (uri, base) = layoutUri("<TextView android:text=\"@string/titre\"/>")
        val emplacements = session.renommage(uri, base + "@string/".length + 1)
        // Déclaration sur le TAMPON vif (plage exacte du vif, pas du disque).
        assertEquals(uriDecl, emplacements[0].uri)
        assertEquals("titre", texteVif.substring(emplacements[0].debut, emplacements[0].fin))
    }

    @Test
    fun `renommage d'une declarante plus-id sans valeurs — references seules`() {
        preparer()
        val texte1 = "<Button android:id=\"@+id/valider\"/>"
        val uri1 = "file://" + dossierTemp.root.resolve("src/main/res/layout/form.xml").absolutePath
        session.ouvrirDocument(uri1, texte1)
        val texte2 = "<Button android:id=\"@id/valider\"/>"
        val uri2 = "file://" + dossierTemp.root.resolve("src/main/res/layout/confirm.xml").absolutePath
        session.ouvrirDocument(uri2, texte2)

        val emplacements = session.renommage(uri1, texte1.indexOf("@+id/valider") + "@+id/".length)
        assertEquals(2, emplacements.size)
        assertEquals(setOf(uri1, uri2), emplacements.map { it.uri }.toSet())
    }

    @Test
    fun `renommage sans cible — liste vide`() {
        preparer()
        val uri = "file://" + dossierTemp.root.resolve("src/main/res/layout/main.xml").absolutePath
        session.ouvrirDocument(uri, "<TextView android:text=\"pas une reference\"/>")
        assertTrue(session.renommage(uri, 3).isEmpty())
    }

    @Test
    fun `renommage sur un document inconnu ou ferme — liste vide`() {
        preparer()
        assertTrue(session.renommage("file:///inconnu.xml", 2).isEmpty())
        val (uri, _) = layoutUri("<TextView android:text=\"@string/titre\"/>")
        session.fermerDocument(uri)
        assertTrue(session.renommage(uri, 3).isEmpty())
    }

    /** Sondage de test : le texte vivant d'un document ouvert. */
    private fun SessionLangageXmlImpl.ouvrirTexte(uri: String): String =
        texteDocumentInterne(uri) ?: error("document non ouvert: $uri")
}
