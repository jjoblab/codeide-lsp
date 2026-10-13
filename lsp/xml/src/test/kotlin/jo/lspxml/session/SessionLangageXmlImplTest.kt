package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.CodesDiagnosticsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la session de langue XML (XE1) — cycle de vie, tolérance du
 * contrat (documents inconnus, remplacements, fermetures idempotentes)
 * et diagnostics recalculés au fil de l'eau.
 */
class SessionLangageXmlImplTest {
    /** Dossiers temporaires (JUnit4 — plateforme fixture XE2). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val session = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())

    @Test
    fun `ouvrir et diagnostiquer un layout`() {
        session.ouvrirDocument(
            "file:///p/res/layout/main.xml",
            "<LinearLayout android:id=\"@+id/x\"><Button /></LinearLayout>",
        )
        val diagnostics = session.diagnostics("file:///p/res/layout/main.xml")
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID })
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.TAILLES_MANQUANTES })
    }

    @Test
    fun `diagnostics ordonnes par position`() {
        session.ouvrirDocument(
            "file:///p/res/layout/main.xml",
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\">" +
                "<b android:layout_width=\"1\" android:layout_height=\"2\" x=\"1\" x=\"2\"/></r>&",
        )
        val diagnostics = session.diagnostics("file:///p/res/layout/main.xml")
        assertTrue(diagnostics.map { it.debut }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `didChange remplace le texte — diagnostics vivants`() {
        val uri = "file:///p/res/layout/main.xml"
        val saine =
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\">" +
                "<b android:layout_width=\"1\" android:layout_height=\"2\" x=\"1\"/></r>"
        session.ouvrirDocument(uri, saine.replace("x=\"1\"/>", "x=\"1\" x=\"2\"/>"))
        assertEquals(1, session.diagnostics(uri).count { it.code == CodesDiagnosticsXml.ATTRIBUT_DUPLIQUE })
        session.modifierDocument(uri, saine)
        assertEquals(0, session.diagnostics(uri).size)
    }

    @Test
    fun `modifier un document inconnu equivaut a l'ouvrir`() {
        val uri = "file:///p/res/values/strings.xml"
        session.modifierDocument(uri, "<resources><string/></resources>")
        assertEquals(1, session.diagnostics(uri).size)
        assertEquals(1, session.nombreDocuments)
    }

    @Test
    fun `reouvrir remplace — pas de fuite de documents`() {
        val uri = "file:///p/res/values/strings.xml"
        session.ouvrirDocument(uri, "<resources/>")
        session.ouvrirDocument(uri, "<resources><string/></resources>")
        assertEquals(1, session.nombreDocuments)
        assertEquals(1, session.diagnostics(uri).size)
    }

    @Test
    fun `document inconnu ou ferme — liste vide, jamais d'exception`() {
        assertTrue(session.diagnostics("file:///p/inconnu.xml").isEmpty())
        session.ouvrirDocument("file:///p/a.xml", "<a/>")
        session.fermerDocument("file:///p/a.xml")
        assertTrue(session.diagnostics("file:///p/a.xml").isEmpty())
        assertEquals(0, session.nombreDocuments)
        // Fermer un document inconnu : sans effet.
        session.fermerDocument("file:///p/rien.xml")
        assertEquals(0, session.nombreDocuments)
    }

    @Test
    fun `texte invalide ne leve jamais`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<<<>>>&\"'")
        // Le contrat X1 : aucun rejet, un diagnostic ou rien — jamais
        // une exception vers l'hôte.
        session.diagnostics(uri)
        assertTrue(true)
    }

    @Test
    fun `definirClasspath — stocke puis vide a la fermeture`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<a/>")
        val classpath = ClasspathXml(null, emptyList(), null)
        session.definirClasspath(classpath)
        assertEquals(classpath, (session as SessionLangageXmlImpl).classpathCourant())
        session.close()
        assertNull(session.classpathCourant())
    }

    @Test
    fun `close — idempotente et definitive`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<a/>")
        session.close()
        session.close()
        assertEquals(0, session.nombreDocuments)
        // Après close : ouvrir/définir sont des NO-OP honnêtes.
        session.ouvrirDocument(uri, "<b/>")
        session.definirClasspath(ClasspathXml(null, emptyList(), null))
        assertEquals(0, session.nombreDocuments)
        assertNull(session.classpathCourant())
    }

    @Test
    fun `genre deduit par document — regles par uri`() {
        session.ouvrirDocument("file:///p/res/values/strings.xml", "<resources><string/></resources>")
        session.ouvrirDocument("file:///p/res/layout/main.xml", "<merge/>")
        assertTrue(session.diagnostics("file:///p/res/values/strings.xml").isNotEmpty())
        assertTrue(session.diagnostics("file:///p/res/layout/main.xml").isEmpty())
    }

    // ── Complétion (XE2) ───────────────────────────────────────────

    /** Mini-plateforme pour le classpath de la session. */
    private fun plateformeFixture(): File {
        val dossier = dossierTemp.newFolder("platforms/android-test")
        val data = dossier.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            "Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object\n" +
                "Wandroid.widget.LinearLayout android.view.ViewGroup android.view.View java.lang.Object\n",
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            "<resources>" +
                "<declare-styleable name=\"View\"><attr name=\"id\" format=\"reference\"/></declare-styleable>" +
                "<declare-styleable name=\"TextView\"><attr name=\"text\" format=\"string\"/></declare-styleable>" +
                "<declare-styleable name=\"ViewGroup_Layout\"><attr name=\"layout_width\"/></declare-styleable>" +
                "</resources>",
        )
        return dossier
    }

    @Test
    fun `completion balise — catalogue de la plateforme du classpath`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        val propositions = session.completion(uri, 15)
        assertTrue(propositions.any { it.libelle == "Button" })
        // La proposition porte le FQCN du catalogue.
        assertEquals(
            "android.widget.Button",
            propositions.single { it.libelle == "Button" }.detail,
        )
    }

    @Test
    fun `completion attribut — styleables de la chaine`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><Button ")
        val libelles = session.completion(uri, 27).map { it.libelle }
        assertTrue(libelles.contains("android:text"))
        assertTrue(libelles.contains("android:layout_width"))
        assertTrue(libelles.contains("android:id"))
    }

    @Test
    fun `completion — sans classpath, silencieuse (X4)`() {
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        // Pas de catalogue : les ATTRIBUTS tools restent, les BALISES
        // se taisent (jamais de candidat inventé).
        assertTrue(session.completion(uri, 15).isEmpty())
    }

    @Test
    fun `completion — genre non layout, silencieuse (XE2, layout seul)`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/res/values/strings.xml"
        session.ouvrirDocument(uri, "<resources><stri")
        assertTrue(session.completion(uri, 15).isEmpty())
    }

    @Test
    fun `completion — document inconnu et zone muette, silencieuses`() {
        assertTrue(session.completion("file:///p/inconnu.xml", 0).isEmpty())
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<TextView>du texte</TextView>")
        assertTrue(session.completion(uri, 12).isEmpty())
    }

    @Test
    fun `completion — apres close, silencieuse`() {
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        session.close()
        assertTrue(session.completion(uri, 15).isEmpty())
    }
}
