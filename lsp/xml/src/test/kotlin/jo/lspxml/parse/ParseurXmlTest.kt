package jo.lspxml.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du parseur TOLÉRANT (XE1, décision X1) — chaque comportement de
 * reprise du cadrage §4.2 a sa fixture : le parseur ne lève JAMAIS, le
 * buffer à moitié tapé produit un ARBRE (pas un rejet), les erreurs
 * voyagent comme recouvrements [DocumentXml.recuperations].
 */
class ParseurXmlTest {
    private val parseur = ParseurXml()

    // ── Document sain ──────────────────────────────────────────────

    @Test
    fun `document sain — arbre complet sans recouvrement`() {
        val document =
            parseur.analyser(
                """<?xml version="1.0"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent">
                    <TextView android:text="@string/hello" />
                    <Button>Ok</Button>
                </LinearLayout>""",
            )
        assertEquals("LinearLayout", document.racine?.nom)
        assertEquals(2, document.racine?.enfants?.size)
        assertEquals(0, document.recuperations.size)
        val textView = document.racine?.enfants?.get(0)
        assertTrue(textView?.autoFermante == true)
        assertEquals("@string/hello", textView?.valeurDe("android:text"))
        val bouton = document.racine?.enfants?.get(1)
        assertEquals("Button", bouton?.nom)
        assertNotNull(bouton?.plageFermante)
        assertTrue(bouton?.estFermee == true)
    }

    @Test
    fun `entites decodees dans les valeurs`() {
        val document = parseur.analyser("""<a v="Tom &amp; Jerry &lt;3 &#65;" />""")
        assertEquals("Tom & Jerry <3 A", document.racine?.valeurDe("v"))
    }

    @Test
    fun `attributs sans valeur et quotes simples`() {
        val document = parseur.analyser("""<root outils='x' nuage />""")
        assertEquals("x", document.racine?.attribut("outils")?.valeur)
        assertNull(document.racine?.attribut("nuage")?.valeur)
        assertEquals(2, document.racine?.attributs?.size)
    }

    @Test
    fun `prefixe et locale des attributs`() {
        val document = parseur.analyser("""<r xmlns:tools="u" android:id="@+id/x" />""")
        val xmlns = document.racine?.attribut("xmlns:tools")
        assertEquals("xmlns", xmlns?.prefixe)
        assertEquals("tools", xmlns?.locale)
        assertTrue(xmlns?.estXmlns == true)
        assertEquals("tools", xmlns?.prefixeDeclare)
        val id = document.racine?.attribut("android:id")
        assertEquals("android", id?.prefixe)
        assertEquals("id", id?.locale)
    }

    @Test
    fun `parents et ancetres relies dans l'arbre`() {
        val document = parseur.analyser("<a><b><c/></b></a>")
        val a = document.racine!!
        val b = a.enfants.single()
        val c = b.enfants.single()
        assertEquals(b, c.parent)
        assertEquals(listOf(a), b.ancetres())
        assertEquals(listOf(b, a), c.ancetres())
        // Ordre du document : b précède c.
        assertEquals(listOf<XmlElement>(b, c), a.descendants())
    }

    @Test
    fun `CDATA commentaires PI et DOCTYPE consommes sans bruit`() {
        val document =
            parseur.analyser(
                "<r><!-- un commentaire --><![CDATA[<raw>& ]]><?pi data?>" +
                    "<!DOCTYPE r><e/></r>",
            )
        assertEquals("r", document.racine?.nom)
        assertEquals(1, document.racine?.enfants?.size)
        assertEquals(0, document.recuperations.size)
    }

    @Test
    fun `texte vide et sans balise`() {
        assertEquals(0, parseur.analyser("").recuperations.size)
        assertNull(parseur.analyser("du texte seul").racine)
        assertNull(parseur.analyser("").racine)
        assertEquals(0, parseur.analyser("").racines.size)
    }

    // ── Balise non fermée ──────────────────────────────────────────

    @Test
    fun `balise non fermee — diagnostic sur son nom`() {
        val document = parseur.analyser("<a><b></b>")
        assertEquals(1, document.recuperations.size)
        val recuperation = document.recuperations.single()
        assertEquals("xml.tagNonFermee", recuperation.code)
        // Le marqueur porte sur le NOM de la balise ouverte.
        assertEquals(document.racine?.plageNom, recuperation.plage)
    }

    @Test
    fun `balise en edition — aucun marqueur a chaud`() {
        // Le `>` n'est pas encore tapé : l'élément existe (complétion),
        // AUCUN diagnostic ne descend SOUS LE CURSEUR (guillemet en
        // cours, balise b en édition) ; l'ANCÊTRE ouvert, lui, porte
        // son marqueur — il se dissipe à la refermeture (contrat de la
        // spec, seul l'élément en édition est exempté).
        val document = parseur.analyser("<a><b attr=\"va")
        val codes = document.recuperations.map { it.code }
        assertTrue(codes.contains("xml.tagNonFermee"))
        assertTrue(!codes.contains("xml.guillemetNonFerme"))
        val a = document.racine!!
        assertEquals("a", a.nom)
        val b = a.enfants.single()
        assertTrue(b.enEdition)
        assertEquals("va", b.valeurDe("attr"))
        assertNull(b.plageFermante)
    }

    // ── Fermantes ──────────────────────────────────────────────────

    @Test
    fun `fermante orpheline — ignoree avec diagnostic`() {
        val document = parseur.analyser("<a></b></a>")
        val codes = document.recuperations.map { it.code }
        assertTrue(codes.contains("xml.tagOrpheline"))
        assertEquals("a", document.racine?.nom)
        // L'arbre vit : la fermante parasite n'a rien cassé.
        assertTrue(document.racine?.estFermee == true)
    }

    @Test
    fun `fermante incoherente — intermediaires refermes implicitement`() {
        val document = parseur.analyser("<a><b><c></a>")
        val codes = document.recuperations.map { it.code }
        assertTrue(codes.contains("xml.tagFermanteIncoherente"))
        val a = document.racine!!
        assertEquals("a", a.nom)
        // b et c refermés IMPLICITEMENT par </a> — l'arbre est complet.
        assertEquals("b", a.enfants.single().nom)
        assertEquals("c", a.enfants.single().enfants.single().nom)
    }

    @Test
    fun `fermante orpheline sur pile vide`() {
        val document = parseur.analyser("</seul>")
        assertEquals(1, document.recuperations.size)
        assertEquals("xml.tagOrpheline", document.recuperations.single().code)
        assertNull(document.racine)
    }

    // ── Guillemets et valeurs ──────────────────────────────────────

    @Test
    fun `guillemet non referme — valeur jusqu'au chevron suivant`() {
        // La valeur s'arrête au prochain `<` (le `>` de la balise est
        // englouti — la balise est cassée de toute façon), la balise
        // devient EN ÉDITION (pas de second marqueur en cascade).
        val document = parseur.analyser("<a x=\"1><b/></a>")
        val codes = document.recuperations.map { it.code }
        assertTrue(codes.contains("xml.guillemetNonFerme"))
        assertEquals("1>", document.racine?.valeurDe("x"))
        // L'élément suivant vit quand même dans l'arbre.
        assertTrue(document.racine?.enfants?.single()?.nom == "b")
    }

    @Test
    fun `guillemet en frappe a la fin du texte — aucun marqueur`() {
        // Fin de texte = guillemet qui VA se refermer : silence (X1,
        // aucun marqueur à chaud), l'élément existe pour la complétion.
        val document = parseur.analyser("<a x=\"va")
        assertEquals(0, document.recuperations.size)
        assertEquals("va", document.racine?.valeurDe("x"))
        assertTrue(document.racine?.enEdition == true)
    }

    @Test
    fun `valeur non quotée — tolérée silencieusement`() {
        val document = parseur.analyser("<a x=1 />")
        assertEquals("1", document.racine?.valeurDe("x"))
        assertEquals(0, document.recuperations.size)
    }

    // ── Esperluettes ───────────────────────────────────────────────

    @Test
    fun `esperluettes brutes notees par section`() {
        val document = parseur.analyser("<r>Tom & Jerry &amp; Anna</r>")
        val sections = document.sectionsTexte.filter { it.esperluettes.isNotEmpty() }
        assertEquals(1, sections.size)
        // Une seule esperluette brute : celle de « Tom & Jerry ».
        assertEquals(1, sections.single().esperluettes.size)
    }

    @Test
    fun `entites numeriques et nommees valides dans le texte`() {
        val document = parseur.analyser("<r>&#65;&#x42;&amp;ok</r>")
        val sections = document.sectionsTexte.filter { it.esperluettes.isNotEmpty() }
        assertEquals(0, sections.size)
    }

    @Test
    fun `esperluette en fin de frappe — notee mais non bloquante`() {
        val document = parseur.analyser("<r>a &")
        // Pas d'exception, l'esperluette seule est notée.
        assertEquals(1, document.sectionsTexte.count { it.esperluettes.isNotEmpty() })
    }

    // ── Racines multiples et reprise ───────────────────────────────

    @Test
    fun `racines soeurs — racine premiere, toutes visibles`() {
        val document = parseur.analyser("<a/><b/>")
        assertEquals(2, document.racines.size)
        assertEquals("a", document.racine?.nom)
        assertEquals("b", document.racines[1].nom)
    }

    @Test
    fun `plages coherentes avec la source`() {
        val source = "<LinearLayout><Button /></LinearLayout>"
        val document = parseur.analyser(source)
        val racine = document.racine!!
        assertEquals("<LinearLayout>", source.substring(racine.plageOuvrante.debut, racine.plageOuvrante.fin))
        assertEquals("</LinearLayout>", source.substring(racine.plageFermante!!.debut, racine.plageFermante!!.fin))
        val bouton = racine.enfants.single()
        assertEquals("<Button />", source.substring(bouton.plageOuvrante.debut, bouton.plageOuvrante.fin))
        assertEquals("Button", source.substring(bouton.plageNom.debut, bouton.plageNom.fin))
    }

    @Test
    fun `tag pleinement qualifie conserve tel quel`() {
        val document =
            parseur.analyser("<androidx.constraintlayout.widget.ConstraintLayout />")
        assertEquals(
            "androidx.constraintlayout.widget.ConstraintLayout",
            document.racine?.nom,
        )
    }

    @Test
    fun `chevron isole dans le texte — conserve sans diagnostic`() {
        val document = parseur.analyser("<r>1 < 2</r>")
        // Conservatoire X4 : le `<` isolé est hors périmètre XE1.
        assertEquals(0, document.recuperations.size)
        assertEquals("r", document.racine?.nom)
    }

    @Test
    fun `document profond — pile entierement refermee a la fin`() {
        val document = parseur.analyser("<a><b><c><d></b>")
        val codes = document.recuperations.map { it.code }
        // </b> referme implicitement c et d ; a reste ouvert → tagNonFermee.
        assertTrue(codes.contains("xml.tagFermanteIncoherente"))
        assertTrue(codes.contains("xml.tagNonFermee"))
        val a = document.racine!!
        assertEquals("d", a.enfants.single().enfants.single().enfants.single().nom)
    }
}
