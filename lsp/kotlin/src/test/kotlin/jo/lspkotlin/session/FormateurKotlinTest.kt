package jo.lspkotlin.session

import jo.lspkotlin.api.EditionSourceKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du FORMATEUR BORNÉ Kotlin (L2 — plan 1.3.0, `textDocument/
 * formatting` et `rangeFormatting`) — par le SPI (chemin de production
 * D6), moteur RÉEL. Le contrat éprouvé est celui de l'honnêteté D3 :
 * ré-indentation canonique SEULE (le contenu des lignes n'est jamais
 * réécrit), intouchables préservés (jeton multi-ligne : chaîne brute,
 * commentaire de bloc, KDoc), refus sur arbre en erreur, idempotence,
 * et le formatage de plage ne touche QUE la plage demandée.
 */
class FormateurKotlinTest {
    private fun sessionNeuve() = ServiceMoteurLangageKotlin.unique().creerSession()

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

    @Test
    fun `document sain - ré-indentation canonique, lignes déjà justes intactes`() {
        val texte =
            "fun a() {\n" +
                "val x = 1\n" +
                "    }\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", 4)
        // Deux éditions : +4 espaces pour le corps, retrait du blanc
        // excédentaire de l'accolade fermante (le fermant vit à la
        // profondeur de son bloc). Le contenu des lignes ne bouge pas.
        assertEquals(2, editions.size)
        val editionCorps = editions.single { it.texte == "    " }
        assertEquals(decalageDe(texte, "val x = 1"), editionCorps.debut)
        assertEquals(decalageDe(texte, "val x = 1"), editionCorps.fin)
        val editionFermante = editions.single { it.texte.isEmpty() }
        assertEquals(decalageDe(texte, "    }"), editionFermante.debut)
        assertEquals(decalageDe(texte, "    }") + 4, editionFermante.fin)
        session.close()
    }

    @Test
    fun `idempotence - reformater un document formaté ne change rien`() {
        val texte =
            "fun a() {\n" +
                "    val x = 1\n" +
                "}\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        assertTrue(session.formatage("fixture://a.kt", 4).isEmpty())
        session.close()
    }

    @Test
    fun `chaîne brute multi-lignes - le contenu entre les délimiteurs est inchangé`() {
        val texte =
            "fun a() {\n" +
                "val s = \"\"\"\n" +
                "      contenu  mal   indenté\n" +
                "  \"\"\"\n" +
                "}\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", 4)
        // La chaîne brute ENTRE SES DÉLIMITEURS (inclus) est du
        // CONTENU : AUCUNE édition ne la chevauche — le blanc de tête
        // des lignes internes (y compris celui devant le délimiteur
        // fermant) est du contenu de la chaîne, jamais réécrit.
        val debutChaine = decalageDe(texte, "\"\"\"")
        val finChaine = decalageDe(texte, "\"\"\"", 1) + "\"\"\"".length
        assertTrue(
            "une édition traverse la chaîne brute : $editions",
            editions.none { it.debut < finChaine && it.fin > debutChaine },
        )
        // La ligne PORTEUSE (hors chaîne) est ré-indentée.
        assertTrue(editions.any { it.debut == decalageDe(texte, "val s") && it.texte == "    " })
        session.close()
    }

    @Test
    fun `KDoc et commentaire de bloc - le contenu est inchangé, la ligne porteuse s'indente`() {
        val texte =
            "/**\n" +
                "   * doc mal indentée\n" +
                "*/\n" +
                "fun a() {\n" +
                "/* bloc\n" +
                "     mal indenté */\n" +
                "val x = 1\n" +
                "}\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", 4)
        // Les lignes INTERNES des jetons multi-lignes (KDoc, bloc)
        // gardent leur blanc de tête ; la ligne PORTEUSE d'un bloc
        // (le commentaire s'ouvre dessus) est du CODE — elle
        // s'indente avec son bloc. Le CONTENU des commentaires n'est
        // JAMAIS chevauché par une édition.
        val kdoc = decalageDe(texte, "/**")
        val finKdoc = decalageDe(texte, "*/") + 2
        val bloc = decalageDe(texte, "/* bloc")
        val finBloc = decalageDe(texte, "*/", 1) + 2
        assertTrue(
            "une édition traverse le KDoc : $editions",
            editions.none { it.debut < finKdoc && it.fin > kdoc },
        )
        assertTrue(
            "une édition traverse le commentaire de bloc : $editions",
            editions.none { it.debut < finBloc && it.fin > bloc },
        )
        assertEquals(
            listOf(decalageDe(texte, "/* bloc"), decalageDe(texte, "val x = 1")),
            editions.map { it.debut },
        )
        assertTrue(editions.all { it.texte == "    " })
        session.close()
    }


    @Test
    fun `document en erreur de syntaxe - liste d'éditions VIDE`() {
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", "fun a() {\nval x = (\n}\n")
        assertTrue(session.diagnostics("fixture://a.kt").isNotEmpty())
        assertTrue(session.formatage("fixture://a.kt", 4).isEmpty())
        session.close()
    }

    @Test
    fun `plage - seules les lignes qui intersectent la plage bougent`() {
        val texte =
            "fun un() {\n" +
                "val a = 1\n" +
                "}\n" +
                "\n" +
                "fun deux() {\n" +
                "val b = 2\n" +
                "}\n"
        val debutPlage = decalageDe(texte, "fun deux")
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", debutPlage, texte.length, 4)
        // La ligne mal indentée de la première fonction (hors plage) ne
        // reçoit AUCUNE édition ; celles de la plage restent DANS la
        // plage (une édition hors plage décalerait le buffer client).
        assertTrue(editions.isNotEmpty())
        assertTrue(editions.all { it.debut >= debutPlage && it.fin <= texte.length })
        assertEquals(
            listOf(decalageDe(texte, "val b = 2")),
            editions.map { it.debut },
        )
        session.close()
    }

    @Test
    fun `continuation de liste - éléments un niveau sous l'ouvrante, fermeture à la base`() {
        val texte =
            "fun somme(\n" +
                "a: Int,\n" +
                "b: Int,\n" +
                "): Int {\n" +
                "return a + b\n" +
                "}\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", 4)
        // Les éléments de la liste vivent UN niveau sous l'ouvrante ;
        // la ligne qui FERME la liste revient à la base (0) — déjà
        // juste, aucune édition ; le corps de la fonction est à 1.
        assertEquals(
            listOf(decalageDe(texte, "a: Int"), decalageDe(texte, "b: Int"), decalageDe(texte, "return a + b")),
            editions.map(EditionSourceKotlin::debut),
        )
        assertTrue(editions.all { it.texte == "    " })
        session.close()
    }

    @Test
    fun `taille d'indentation - deux espaces par niveau sur demande du client`() {
        val texte = "fun a() {\nval x = 1\n}\n"
        val session = sessionNeuve()
        session.ouvrirDocument("fixture://a.kt", texte)
        val editions = session.formatage("fixture://a.kt", 2)
        assertEquals(listOf("  "), editions.map { it.texte })
        session.close()
    }

    @Test
    fun `document inconnu ou fermé - liste VIDE, jamais d'exception`() {
        val session = sessionNeuve()
        assertTrue(session.formatage("fixture://inconnu.kt", 4).isEmpty())
        session.ouvrirDocument("fixture://a.kt", "fun a() {\nval x = 1\n}\n")
        session.fermerDocument("fixture://a.kt")
        assertTrue(session.formatage("fixture://a.kt", 4).isEmpty())
        session.close()
    }
}
