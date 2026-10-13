package jo.lspxml.context

import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du scanner de POSITION du curseur (XE2, §4.1 « context ») —
 * chaque ZONE a ses fixtures : balise, attribut (préfixe, après nom,
 * fin de frappe), valeur (guillemets), muet (texte, fermante,
 * commentaire) ; le scan respecte les QUOTES (un `>` dans une valeur
 * ne clôt pas la balise).
 */
class ContexteCurseurTest {
    private val parseur = ParseurXml()

    private fun contexte(
        texte: String,
        decalage: Int,
    ): Contexte = ContexteCurseur.analyser(texte, decalage, parseur.analyser(texte))

    // ── Nom de balise ──────────────────────────────────────────────

    @Test
    fun `chevron nu — nom de balise vide`() {
        val contexte = contexte("<LinearLayout><", 15)
        assertTrue(contexte is Contexte.NomDeBalise)
        assertEquals("", (contexte as Contexte.NomDeBalise).prefixeTape)
        // Le PARENT est le conteneur ouvert (LinearLayout).
        assertEquals("LinearLayout", contexte.parent?.nom)
    }

    @Test
    fun `nom partiel — préfixe tape`() {
        val contexte = contexte("<LinearLayout><TextVi", 21)
        assertTrue(contexte is Contexte.NomDeBalise)
        assertEquals("TextVi", (contexte as Contexte.NomDeBalise).prefixeTape)
        assertEquals("LinearLayout", contexte.parent?.nom)
    }

    @Test
    fun `nom complet sans fermeture — encore un nom de balise`() {
        val contexte = contexte("<Button", 7)
        assertTrue(contexte is Contexte.NomDeBalise)
        assertEquals("Button", (contexte as Contexte.NomDeBalise).prefixeTape)
        assertNull(contexte.parent)
    }

    @Test
    fun `caret juste après le nom — zone attribut`() {
        val contexte = contexte("<Button", 1)
        assertTrue(contexte is Contexte.NomDeBalise)
        assertEquals("", (contexte as Contexte.NomDeBalise).prefixeTape)
    }

    // ── Nom d'attribut ─────────────────────────────────────────────

    @Test
    fun `attribut partiel dans balise ouverte`() {
        val contexte = contexte("<Button andr", 12)
        assertTrue(contexte is Contexte.NomAttribut)
        assertEquals("andr", (contexte as Contexte.NomAttribut).prefixeTape)
        assertEquals("Button", contexte.element?.nom)
    }

    @Test
    fun `apres le nom et un blanc — zone attribut ouverte`() {
        val contexte = contexte("<Button ", 8)
        assertTrue(contexte is Contexte.NomAttribut)
        assertEquals("", (contexte as Contexte.NomAttribut).prefixeTape)
        assertEquals("Button", contexte.element?.nom)
    }

    @Test
    fun `attribut complet avant le egale`() {
        val contexte = contexte("<Button android:layout_width=", 28)
        // Le curseur est SUR le `=` : fin de nom → zone attribut.
        assertTrue(contexte is Contexte.NomAttribut)
    }

    @Test
    fun `attribut sans valeur en fin de frappe`() {
        val contexte = contexte("<Button android:layout_width", 28)
        assertTrue(contexte is Contexte.NomAttribut)
        assertEquals("android:layout_width", (contexte as Contexte.NomAttribut).prefixeTape)
    }

    // ── Valeur ─────────────────────────────────────────────────────

    @Test
    fun `valeur en frappe entre guillemets`() {
        val contexte = contexte("<Button android:layout_width='ma", 32)
        assertTrue(contexte is Contexte.Valeur)
        val valeur = contexte as Contexte.Valeur
        assertEquals("ma", valeur.prefixeTape)
        assertEquals("android:layout_width", valeur.attribut)
        assertEquals("Button", valeur.element?.nom)
    }

    @Test
    fun `guillemet ouvrant nu — valeur vide`() {
        val contexte = contexte("<Button android:layout_width='", 30)
        assertTrue(contexte is Contexte.Valeur)
        assertEquals("", (contexte as Contexte.Valeur).prefixeTape)
    }

    @Test
    fun `chevron dans une valeur ne clôt pas la balise`() {
        // La première valeur porte un `>` : elle ne clôt PAS la balise —
        // le curseur lit la seconde valeur EN FRAPPE (non fermée).
        val texte = "<Button android:text='a>b' android:id='x"
        val contexte = contexte(texte, texte.length)
        assertTrue(contexte is Contexte.Valeur)
        assertEquals("x", (contexte as Contexte.Valeur).prefixeTape)
        assertEquals("android:id", contexte.attribut)
    }

    // ── Muet ───────────────────────────────────────────────────────

    @Test
    fun `texte entre balises — muet`() {
        assertTrue(contexte("<TextView>du te|</TextView>", 14) is Contexte.Muet)
    }

    @Test
    fun `balise fermante — muet`() {
        assertTrue(contexte("<TextView></TextVi", 17) is Contexte.Muet)
    }

    @Test
    fun `apres la fermeture de la balise — muet`() {
        assertTrue(contexte("<TextView/>", 11) is Contexte.Muet)
    }

    @Test
    fun `commentaire et PI — muets`() {
        assertTrue(contexte("<!-- com|", 7) is Contexte.Muet)
        assertTrue(contexte("<?xml |", 6) is Contexte.Muet)
    }

    @Test
    fun `document vide — muet`() {
        assertTrue(contexte("", 0) is Contexte.Muet)
    }

    // ── Parent depuis le DOM ───────────────────────────────────────

    @Test
    fun `parent profond — le conteneur le plus interne gagne`() {
        val texte = "<A><B><C></C><"
        val contexte = contexte(texte, texte.length)
        // C est REFERMÉ : le parent est B.
        assertEquals("B", (contexte as Contexte.NomDeBalise).parent?.nom)
    }

    @Test
    fun `element en cours retrouve par le DOM`() {
        val texte = "<LinearLayout><Button andr"
        val contexte = contexte(texte, texte.length)
        assertEquals("Button", (contexte as Contexte.NomAttribut).element?.nom)
    }
}
