package jo.lspxml.completion

import jo.lspxml.context.Contexte
import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la complétion du genre DRAWABLE (XE5) — racines, enfants
 * selon le parent (X4 : parent inconnu = silence), attributs par
 * balise (parent-conscients pour `item`), xmlns en frappe,
 * énumérations.
 */
class CompletionDrawablesXmlTest {
    private val parseur = ParseurXml()

    private fun contexteBalise(source: String, decalage: Int): Contexte.NomDeBalise =
        jo.lspxml.context.ContexteCurseur.analyser(source, decalage, parseur.analyser(source))
            as Contexte.NomDeBalise

    private fun contexteAttribut(source: String, decalage: Int): Contexte.NomAttribut =
        jo.lspxml.context.ContexteCurseur.analyser(source, decalage, parseur.analyser(source))
            as Contexte.NomAttribut

    private fun contexteValeur(source: String, decalage: Int): Contexte.Valeur =
        jo.lspxml.context.ContexteCurseur.analyser(source, decalage, parseur.analyser(source))
            as Contexte.Valeur

    @Test
    fun `balises — racines proposees sans parent`() {
        val source = "<sha"
        val libelles = CompletionDrawablesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("shape"))
        // Le préfixe filtre : ni selector ni vector ne matchent « sha ».
        assertTrue(!libelles.contains("selector"))
        assertTrue(!libelles.contains("vector"))
    }

    @Test
    fun `balises — toutes les racines a prefixe vide`() {
        val source = "<"
        val libelles = CompletionDrawablesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("shape"))
        assertTrue(libelles.contains("selector"))
        assertTrue(libelles.contains("layer-list"))
        assertTrue(libelles.contains("ripple"))
        assertTrue(libelles.contains("vector"))
        assertTrue(libelles.contains("bitmap"))
        assertTrue(libelles.contains("animation-list"))
    }

    @Test
    fun `balises — enfants de shape`() {
        val source = "<shape><"
        val libelles = CompletionDrawablesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("corners", "gradient", "solid", "stroke", "padding", "size")))
        // Une racine n'est pas un enfant d'elle-même.
        assertTrue(!libelles.contains("shape"))
        assertTrue(!libelles.contains("item"))
    }

    @Test
    fun `balises — enfants de vector et de group`() {
        val source = "<vector><group><"
        val libelles = CompletionDrawablesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("group", "path", "clip-path")))
        val sourceDirect = "<vector><"
        val libellesDirect =
            CompletionDrawablesXml.balises(contexteBalise(sourceDirect, sourceDirect.length)).map { it.libelle }
        assertTrue(libellesDirect.contains("path"))
        assertTrue(!libellesDirect.contains("corners"))
    }

    @Test
    fun `balises — item sous selector`() {
        val source = "<selector><"
        val libelles = CompletionDrawablesXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertEquals(listOf("item"), libelles)
    }

    @Test
    fun `balises — parent inconnu, silence (X4)`() {
        val source = "<inconnu><ca"
        assertTrue(CompletionDrawablesXml.balises(contexteBalise(source, source.length)).isEmpty())
    }

    @Test
    fun `attributs — par balise (stroke)`() {
        val source = "<stroke "
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(
            libelles.containsAll(
                listOf("android:width", "android:color", "android:dashWidth", "android:dashGap"),
            ),
        )
        assertTrue(!libelles.contains("android:radius"))
    }

    @Test
    fun `attributs — nom local matche le prefixe (wi)`() {
        val source = "<stroke wi"
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertEquals(listOf("android:width"), libelles)
    }

    @Test
    fun `attributs — item de selector porte les etats`() {
        val source = "<selector><item "
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("android:state_pressed"))
        assertTrue(libelles.contains("android:state_focused"))
        assertTrue(libelles.contains("android:drawable"))
        assertTrue(!libelles.contains("android:duration"))
        assertTrue(!libelles.contains("android:left"))
    }

    @Test
    fun `attributs — item de layer-list porte les calques`() {
        val source = "<layer-list><item "
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(
            libelles.containsAll(listOf("android:left", "android:top", "android:right", "android:bottom")),
        )
        assertTrue(!libelles.contains("android:state_pressed"))
    }

    @Test
    fun `attributs — item d animation-list porte la duree`() {
        val source = "<animation-list><item "
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("android:duration"))
        assertTrue(!libelles.contains("android:state_pressed"))
    }

    @Test
    fun `attributs — balise inconnue, silence`() {
        val source = "<inconnu "
        assertTrue(CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).isEmpty())
    }

    @Test
    fun `attributs — xmlns en frappe sur la racine`() {
        val source = "<shape xmln"
        val libelles = CompletionDrawablesXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("xmlns:android"))
        assertTrue(libelles.contains("xmlns:tools"))
    }

    @Test
    fun `valeurs — enum de shape`() {
        val source = "<shape android:shape=\""
        val libelles = CompletionDrawablesXml.valeurs(contexteValeur(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("rectangle", "oval", "line", "ring")))
    }

    @Test
    fun `valeurs — enum filtree par prefixe (r)`() {
        val source = "<shape android:shape=\"r"
        val libelles = CompletionDrawablesXml.valeurs(contexteValeur(source, source.length)).map { it.libelle }
        assertEquals(listOf("rectangle", "ring"), libelles)
    }

    @Test
    fun `valeurs — enum de type de gradient`() {
        val source = "<gradient android:type=\""
        val libelles = CompletionDrawablesXml.valeurs(contexteValeur(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("linear", "radial", "sweep")))
    }

    @Test
    fun `valeurs — attribut inconnu, silence`() {
        val source = "<solid android:color=\""
        assertTrue(CompletionDrawablesXml.valeurs(contexteValeur(source, source.length)).isEmpty())
    }
}
