package jo.lspxml.completion

import jo.lspxml.context.Contexte
import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la complétion du genre MENU (XE5) — hiérarchie menu →
 * item/group → sous-menu, attributs par balise (préfixe `app:`
 * AppCompat compris), fanions `showAsAction`, énumérations.
 */
class CompletionMenusXmlTest {
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
    fun `balises — menu seul en racine`() {
        val source = "<m"
        val libelles = CompletionMenusXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertEquals(listOf("menu"), libelles)
    }

    @Test
    fun `balises — item et group sous menu`() {
        val source = "<menu><"
        val libelles = CompletionMenusXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("item", "group")))
        assertTrue(!libelles.contains("menu"))
    }

    @Test
    fun `balises — item seul sous group, sous-menu dans un item`() {
        val source = "<menu><group><"
        val libelles = CompletionMenusXml.balises(contexteBalise(source, source.length)).map { it.libelle }
        assertEquals(listOf("item"), libelles)
        val sourceSous = "<menu><item android:id=\"@+id/a\"><"
        val libellesSous =
            CompletionMenusXml.balises(contexteBalise(sourceSous, sourceSous.length)).map { it.libelle }
        assertEquals(listOf("menu"), libellesSous)
    }

    @Test
    fun `attributs — item porte la trinité id title icon puis showAsAction`() {
        val source = "<menu><item "
        val propositions = CompletionMenusXml.attributs(contexteAttribut(source, source.length))
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.take(3).containsAll(listOf("android:id", "android:title", "android:icon")))
        assertTrue(libelles.contains("android:showAsAction"))
        assertTrue(libelles.contains("app:showAsAction"))
        assertTrue(libelles.contains("android:orderInCategory"))
        assertTrue(libelles.contains("android:actionLayout"))
    }

    @Test
    fun `attributs — group porte checkableBehavior`() {
        val source = "<menu><group "
        val libelles = CompletionMenusXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(libelles.contains("android:checkableBehavior"))
        assertTrue(!libelles.contains("android:title"))
    }

    @Test
    fun `attributs — racine menu, xmlns seuls`() {
        val source = "<menu xmln"
        val libelles = CompletionMenusXml.attributs(contexteAttribut(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("xmlns:android", "xmlns:app", "xmlns:tools")))
        val sourceRacine = "<menu "
        assertTrue(
            CompletionMenusXml.attributs(contexteAttribut(sourceRacine, sourceRacine.length)).isEmpty(),
        )
    }

    @Test
    fun `attributs — balise inconnue, silence`() {
        val source = "<menu><inconnu "
        assertTrue(CompletionMenusXml.attributs(contexteAttribut(source, source.length)).isEmpty())
    }

    @Test
    fun `valeurs — showAsAction en fanions combinables`() {
        val source = "<menu><item android:showAsAction=\""
        val propositions = CompletionMenusXml.valeurs(contexteValeur(source, source.length))
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.containsAll(listOf("never", "ifRoom", "always", "withText", "collapseActionView")))
        assertEquals(jo.lspxml.api.GenreCompletionXml.VALEUR_FANION, propositions.first().genre)
    }

    @Test
    fun `valeurs — showAsAction repond au prefixe app aussi`() {
        val source = "<menu><item app:showAsAction=\"if"
        val libelles = CompletionMenusXml.valeurs(contexteValeur(source, source.length)).map { it.libelle }
        assertEquals(listOf("ifRoom"), libelles)
    }

    @Test
    fun `valeurs — menuCategory et checkableBehavior en enums`() {
        val source = "<menu><item android:menuCategory=\""
        val libelles = CompletionMenusXml.valeurs(contexteValeur(source, source.length)).map { it.libelle }
        assertTrue(libelles.containsAll(listOf("container", "system", "secondary", "alternative")))
        val sourceGroupe = "<menu><group android:checkableBehavior=\""
        val libellesGroupe =
            CompletionMenusXml.valeurs(contexteValeur(sourceGroupe, sourceGroupe.length)).map { it.libelle }
        assertTrue(libellesGroupe.containsAll(listOf("none", "all", "single")))
    }

    @Test
    fun `valeurs — attribut inconnu, silence`() {
        val source = "<menu><item android:title=\""
        assertTrue(CompletionMenusXml.valeurs(contexteValeur(source, source.length)).isEmpty())
    }
}
