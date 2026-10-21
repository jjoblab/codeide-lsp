package jo.lspxml.session

import jo.lspxml.api.GenreSurlignageXml
import jo.lspxml.api.ServiceMoteurLangageXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du FORMATAGE DE PLAGE, de l'ÉDITION LIÉE des balises et des
 * CHAÎNES DE SÉLECTION XML (lot L3 du plan 1.3.0) — par le SPI
 * (chemin de production D6), moteur RÉEL.
 *
 * Le cœur éprouvé : le bornage (les éditions de plage restent DANS la
 * plage ; le contenu n'est jamais réécrit), la liaison honnête (le nom
 * d'ouvrante ↔ fermante du MÊME élément, jamais d'auto-fermante) et
 * la croissance STRICTE des chaînes de sélection jusqu'au fichier.
 */
class FormatagePlageSelectionXmlTest {
    /** Décalage du PREMIER caractère de [occurrence] dans [texte]. */
    private fun decalageDe(
        texte: String,
        occurrence: String,
        numero: Int = 0,
    ): Int {
        var index = -1
        repeat(numero + 1) {
            index = texte.indexOf(occurrence, index + 1)
        }
        return index
    }

    private fun sessionNeuve() = ServiceMoteurLangageXml.unique().creerSession()

    // ── Formatage de plage ───────────────────────────────────────────

    @Test
    fun `plage - seules les lignes qui intersectent la plage bougent`() {
        val texte =
            "<a>\n" +
                "<b/>\n" +
                "<c>\n" +
                "<d/>\n" +
                "</c>\n" +
                "</a>\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        // Plage = les lignes de <c>…</c> (la ligne de <d/> est DANS la
        // plage : elle vit entre les bornes).
        val debutPlage = decalageDe(texte, "<c>")
        val finPlage = decalageDe(texte, "</a>")
        val editions = session.formatter("fixture://x.xml", debutPlage, finPlage, 4)
        // La ligne de <b/> (hors plage) ne reçoit AUCUNE édition ; les
        // éditions restent DANS la plage.
        assertTrue(editions.isNotEmpty())
        assertTrue(editions.all { it.debut >= debutPlage && it.fin <= finPlage })
        // Les TROIS lignes de la plage se ré-indentent : <c> et </c> à
        // 1 niveau, <d/> (enfant) à 2 niveaux.
        assertEquals(
            listOf(decalageDe(texte, "<c>"), decalageDe(texte, "<d/>"), decalageDe(texte, "</c>")),
            editions.map { it.debut },
        )
        assertEquals(listOf("    ", "        ", "    "), editions.map { it.texte })
        session.close()
    }

    @Test
    fun `plage - document casse ou plage vide, liste VIDE`() {
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://casse.xml", "<a>\n<b>\n")
        assertTrue(session.formatter("fixture://casse.xml", 0, 10, 4).isEmpty())
        // Plage vide : rien à servir.
        session.ouvrirDocument("fixture://ok.xml", "<a>\n<b/>\n</a>\n")
        assertTrue(session.formatter("fixture://ok.xml", 5, 5, 4).isEmpty())
        // Document inconnu : vide, jamais d'exception.
        assertTrue(session.formatter("fixture://inconnu.xml", 0, 10, 4).isEmpty())
        session.close()
    }

    // ── Édition liée ─────────────────────────────────────────────────

    @Test
    fun `edition liee - ouvrante et fermante du meme element, fermante comprise`() {
        val texte = "<linear><bouton texte=\"Ok\"/></linear>"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        // Curseur sur le NOM de l'ouvrante de <linear>.
        val surOuvrante = session.plagesLiees("fixture://x.xml", decalageDe(texte, "linear"))
        assertEquals(decalageDe(texte, "linear"), surOuvrante?.ouvrante?.debut)
        assertEquals(decalageDe(texte, "</linear>") + 2, surOuvrante?.fermante?.debut)
        // Curseur sur le nom de la FERMANTE : mêmes plages liées.
        val surFermante = session.plagesLiees("fixture://x.xml", decalageDe(texte, "</linear>") + 3)
        assertEquals(surOuvrante, surFermante)
        session.close()
    }

    @Test
    fun `edition liee - hors nom, auto-fermante ou inconnu, null`() {
        val texte = "<linear><bouton texte=\"Ok\"/></linear>"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        // L'auto-fermante <bouton/> n'a PAS de fermante : aucune liaison.
        assertNull(session.plagesLiees("fixture://x.xml", decalageDe(texte, "bouton")))
        // Le nom d'ATTRIBUT n'est pas une balise : aucune liaison.
        assertNull(session.plagesLiees("fixture://x.xml", decalageDe(texte, "texte")))
        // Document inconnu : null, jamais d'exception.
        assertNull(session.plagesLiees("fixture://inconnu.xml", 0))
        session.close()
    }

    // ── Chaînes de sélection ─────────────────────────────────────────

    @Test
    fun `selection - chaine strictement croissante de l_attribut au fichier`() {
        val texte = "<linear><bouton texte=\"Ok\"/></linear>"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        val chaines = session.selections("fixture://x.xml", listOf(decalageDe(texte, "texte")))
        assertEquals(1, chaines.size)
        val plages = chaines.single().plages
        // Le maillon le plus profond : le NOM de l'attribut (la chaîne
        // entière est décroissante du profond au fichier).
        assertTrue("plages=$plages", plages.first().debut == decalageDe(texte, "texte"))
        // Le FICHIER ENTIER ferme la chaîne.
        assertEquals(0, plages.last().debut)
        assertEquals(texte.length, plages.last().fin)
        // STRICTEMENT croissant (chaque maillon contient le précédent).
        plages.zipWithNext { courant, suivant ->
            assertTrue(
                "maillons non croissants : $plages",
                suivant.debut <= courant.debut && suivant.fin >= courant.fin,
            )
        }
        // <linear> couvre TOUT le document : ses frontières fusionnent
        // avec le maillon fichier (une expansion sans effet n'est pas
        // un maillon). Les niveaux restants : bouton (8..28) puis
        // l'attribut texte (16..21).
        assertEquals(3, plages.size)
        assertEquals(
            listOf(
                decalageDe(texte, "texte") to decalageDe(texte, "texte") + "texte".length,
                decalageDe(texte, "<bouton") to decalageDe(texte, "/>") + 2,
                0 to texte.length,
            ),
            plages.map { it.debut to it.fin },
        )
        session.close()
    }

    @Test
    fun `selection - hors arbre fichier seul, ordre conserve, inconnu vide`() {
        val texte = "<a><b/></a>"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://x.xml", texte)
        val chaines = session.selections("fixture://x.xml", listOf(0, texte.length + 5))
        assertEquals(2, chaines.size)
        // Hors arbre : la chaîne du FICHIER SEUL.
        assertEquals(1, chaines[1].plages.size)
        assertEquals(0, chaines[1].plages.single().debut)
        assertEquals(texte.length, chaines[1].plages.single().fin)
        // Document inconnu : liste VIDE, jamais d'exception.
        assertTrue(session.selections("fixture://inconnu.xml", listOf(0)).isEmpty())
        session.close()
    }
}
