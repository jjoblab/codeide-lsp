package jo.lspxml.session

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.rules.GenreDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests SESSION du genre DRAWABLE/MENU (XE5) — complétion par URI,
 * extension des règles xmlns/ids aux nouveaux genres, quick-fix
 * xmlns sur une racine drawable.
 */
class SessionXe5Test {
    private val uriDrawable = "file:///projets/app/src/main/res/drawable/fond.xml"
    private val uriMenu = "file:///projets/app/src/main/res/menu/principal.xml"

    private fun session(): SessionLangageXmlImpl = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())

    @Test
    fun `genre — uris drawables et menus, qualifiers compris`() {
        assertEquals(GenreDocument.DRAWABLE, GenreDocument.depuisUri("file:///a/res/drawable/f.xml"))
        assertEquals(GenreDocument.DRAWABLE, GenreDocument.depuisUri("file:///a/res/drawable-v24/f.xml"))
        assertEquals(GenreDocument.MENU, GenreDocument.depuisUri("file:///a/res/menu/main.xml"))
        assertEquals(GenreDocument.AUTRE, GenreDocument.depuisUri("file:///a/res/xml/inconnu.xml"))
        assertEquals(GenreDocument.LAYOUT, GenreDocument.depuisUri("file:///a/res/layout-land/main.xml"))
    }

    @Test
    fun `completion drawable — racine au prefixe, enfants selon le parent`() {
        val session = session()
        session.ouvrirDocument(uriDrawable, "<shape><")
        // Racine : le préfixe « shape » ne laisse que shape.
        session.modifierDocument(uriDrawable, "<shape")
        assertEquals(listOf("shape"), session.completion(uriDrawable, 6).map { it.libelle })
        // Enfant de shape : les six balises de forme.
        session.modifierDocument(uriDrawable, "<shape><")
        val libelles = session.completion(uriDrawable, 8).map { it.libelle }
        assertTrue(
            libelles.containsAll(listOf("corners", "gradient", "solid", "stroke", "padding", "size")),
        )
    }

    @Test
    fun `completion drawable — attributs par balise et enumerations`() {
        val session = session()
        val source =
            "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"><stroke "
        session.ouvrirDocument(uriDrawable, source)
        val libelles = session.completion(uriDrawable, source.length).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("android:width", "android:color", "android:dashWidth")))
        // Énumération de la forme.
        val sourceForme = "<shape android:shape=\""
        session.modifierDocument(uriDrawable, sourceForme)
        val valeurs = session.completion(uriDrawable, sourceForme.length).map { it.libelle }
        assertTrue(valeurs.containsAll(listOf("rectangle", "oval", "line", "ring")))
    }

    @Test
    fun `completion menu — attributs d item et fanions showAsAction`() {
        val session = session()
        val source = "<menu><item android:showAsAction=\""
        session.ouvrirDocument(uriMenu, source)
        val attributions = "<menu><item "
        val attributs = session.completion(uriMenu, attributions.length).map { it.libelle }
        assertTrue(attributs.contains("android:showAsAction"))
        assertTrue(attributs.contains("app:showAsAction"))
        val fanions = session.completion(uriMenu, source.length).map { it.libelle }
        assertTrue(fanions.containsAll(listOf("never", "ifRoom", "always", "withText")))
    }

    @Test
    fun `completion — uri hors genres previewables, silence`() {
        val session = session()
        val uri = "file:///a/res/xml/inconnu.xml"
        session.ouvrirDocument(uri, "<inconnu attri")
        assertTrue(session.completion(uri, 14).isEmpty())
    }

    @Test
    fun `diagnostics drawable — xmlns manquant signale, quick-fix disponible`() {
        val session = session()
        session.ouvrirDocument(
            uriDrawable,
            "<shape android:shape=\"rectangle\"><solid android:color=\"#fff\"/></shape>",
        )
        val diagnostics = session.diagnostics(uriDrawable)
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID })
        // Le quick-fix d'insertion xmlns vit au DÉBUT du nom de racine.
        val diagnostic = diagnostics.first { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID }
        assertTrue(session.correctifs(uriDrawable, diagnostic.debut).isNotEmpty())
    }

    @Test
    fun `diagnostics drawable — xmlns declare, silence des regles de genre`() {
        val session = session()
        session.ouvrirDocument(
            uriDrawable,
            "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:shape=\"rectangle\"><solid android:color=\"#fff\"/></shape>",
        )
        assertTrue(
            session.diagnostics(uriDrawable)
                .none { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID },
        )
    }

    @Test
    fun `diagnostics menu — id duplique signale`() {
        val session = session()
        session.ouvrirDocument(
            uriMenu,
            "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<item android:id=\"@+id/action\"/>" +
                "<item android:id=\"@+id/action\"/>" +
                "</menu>",
        )
        assertTrue(
            session.diagnostics(uriMenu).any { it.code == CodesDiagnosticsXml.ID_DUPLIQUE },
        )
    }
}
