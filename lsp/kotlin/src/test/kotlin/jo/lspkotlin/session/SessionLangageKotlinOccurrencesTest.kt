package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.GenreSurlignageKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du contrat de session — OCCURRENCES (S2, plan de
 * stabilisation LSP §3.2 : `references`, `documentHighlight`, rename
 * borné miroir Java) — par le SPI (chemin de production D6), moteur
 * RÉEL, projet multi-fichiers sur disque (racines sources du scan).
 *
 * Le cœur éprouvé : le conservatisme. Chaque référence NON prouvée
 * (homonyme d'un autre paquet sans import, locale ombrageante,
 * sélecteur qualifié sans récepteur démontrable) reste HORS liste —
 * jamais de faux usage, jamais de faux renommage.
 */
class SessionLangageKotlinOccurrencesTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Session avec racines sources (scan multi-fichiers actif). */
    private fun sessionAvecRacines(vararg fichiers: Pair<String, String>): SessionLangageKotlin {
        val racine = dossierTemporaire.newFolder("src")
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine, chemin)
            fichier.parentFile.mkdirs()
            fichier.writeText(contenu)
        }
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = null,
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        return session
    }

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

    // ── Références : locale file-local ────────────────────────────────

    @Test
    fun `references locale - usages du fichier déclarant seul, ombrage exclu`() {
        val texte =
            """
            fun principale() {
                val compteur = 0
                val somme = compteur + compteur
            }

            fun autre() {
                val compteur = 9
                val copie = compteur
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Sur la DÉCLARATION de compteur (1re occurrence) — usages dans
        // principale() SEULEMENT (la locale de autre() est un homonyme).
        val decalage = decalageDe(texte, "compteur")
        val references =
            session.references("fixture://Main.kt", decalage, inclureDeclaration = true)
        assertEquals(3, references.size)
        assertTrue(references.all { it.uri == "fixture://Main.kt" })
        // Sans la déclaration : les deux usages seulement.
        val usages = session.references("fixture://Main.kt", decalageDe(texte, "compteur"))
        assertEquals(2, usages.size)
    }

    @Test
    fun `references paramètre - confiné au fichier, usages du corps`() {
        val texte =
            """
            fun saluer(nom: String): String = "Bonjour " + nom
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val usages = session.references("fixture://Main.kt", decalageDe(texte, "nom"))
        assertEquals(1, usages.size)
    }

    // ── Références : fonction top-level multi-fichiers ────────────────

    @Test
    fun `references fonction top-level - même paquet, autre fichier fermé du scan`() {
        val bibli =
            """
            package outils

            fun triple(x: Int): Int = x * 3
            """.trimIndent()
        val appelant =
            """
            package outils

            fun essai(): Int = triple(2) + triple(3)
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Appelant.kt" to appelant)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        // Sur l'USAGE de triple dans l'appelant — retourne les usages
        // de Biblio.kt (fichier fermé du scan).
        val decalageAutreFichier = decalageDe(bibli, "triple")
        val references = session.references("fixture://Biblio.kt", decalageAutreFichier)
        assertEquals(2, references.size)
        assertTrue(references.all { it.uri.endsWith("Appelant.kt") })
    }

    @Test
    fun `references fonction top-level - import explicite d'un autre paquet`() {
        val bibli =
            """
            package outils

            fun triple(x: Int): Int = x * 3
            """.trimIndent()
        val appelant =
            """
            package principal

            import outils.triple

            fun essai(): Int = triple(2)
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Appelant.kt" to appelant)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        val references = session.references("fixture://Biblio.kt", decalageDe(bibli, "triple"))
        // L'USAGE + le segment d'IMPORT (miroir IntelliJ : les usages
        // listent aussi l'import à renommer).
        assertEquals(2, references.size)
        assertTrue(references.all { it.uri.endsWith("Appelant.kt") })
    }

    @Test
    fun `references homonyme sans preuve - vide (conservatisme D3)`() {
        val bibli =
            """
            package outils

            fun triple(x: Int): Int = x * 3
            """.trimIndent()
        // Même paquet principal, AUTRE fonction homonyme — l'usage
        // principal() désigne SA fonction, jamais celle d'outils.
        val autre =
            """
            package principal

            fun triple(x: Int): Int = x * 30

            fun essai(): Int = triple(2)
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Autre.kt" to autre)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        val references = session.references("fixture://Biblio.kt", decalageDe(bibli, "triple"))
        assertTrue(references.isEmpty())
    }

    // ── Références : type via import + appel de constructeur ──────────

    @Test
    fun `references classe - import, usage, et import renommé aussi`() {
        val bibli =
            """
            package outils

            class Outil(val nom: String)
            """.trimIndent()
        val appelant =
            """
            package principal

            import outils.Outil

            fun fabrique(): Outil = Outil("clef")
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Appelant.kt" to appelant)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        val references = session.references("fixture://Biblio.kt", decalageDe(bibli, "Outil"))
        // L'IMPORT, le TYPE DE RETOUR et le CONSTRUCTEUR sont prouvés
        // (FQCN résolu dans le fichier appelant).
        assertEquals(3, references.size)
        assertTrue(references.all { it.uri.endsWith("Appelant.kt") })
    }

    @Test
    fun `references membre qualifié - récepteur typé prouve la cible`() {
        val bibli =
            """
            package outils

            class Boite {
                fun ouvrir(): Int = 1
            }
            """.trimIndent()
        val appelant =
            """
            package principal

            import outils.Boite

            fun essai(b: Boite): Int = b.ouvrir() + b.ouvrir()
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Appelant.kt" to appelant)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        // Sur la DÉCLARATION de ouvrir (membre) — les sélecteurs
        // qualifiés b.ouvrir() sont prouvés par le typage du récepteur.
        val references = session.references("fixture://Biblio.kt", decalageDe(bibli, "ouvrir"))
        assertEquals(2, references.size)
        assertTrue(references.all { it.uri.endsWith("Appelant.kt") })
    }

    // ── documentHighlight ─────────────────────────────────────────────

    @Test
    fun `documentHighlight - déclaration en écriture, références en lecture, ombrage exclu`() {
        val texte =
            """
            fun principale() {
                val total = 1
                val doubling = total + total
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val surlignages = session.surlignagesDocument("fixture://Main.kt", decalageDe(texte, "total"))
        assertEquals(3, surlignages.size)
        assertEquals(GenreSurlignageKotlin.ECRITURE, surlignages.first().genre)
        assertTrue(surlignages.drop(1).all { it.genre == GenreSurlignageKotlin.LECTURE })
        // En ordre du document.
        assertTrue(surlignages.zipWithNext().all { (a, b) -> a.debut < b.debut })
    }

    @Test
    fun `documentHighlight - curseur sur référence, pas sur la déclaration`() {
        val texte =
            """
            fun principale() {
                val total = 1
                val copie = total
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Curseur sur l'USAGE (2e occurrence de total).
        val surlignages = session.surlignagesDocument("fixture://Main.kt", decalageDe(texte, "total", numero = 1))
        assertEquals(2, surlignages.size)
    }

    // ── prepareRename / renommage ─────────────────────────────────────

    @Test
    fun `plageRenommable - placeholder exact sur le nom déclaré`() {
        val texte =
            """
            class Pompe {
                fun amorcer(): Int = 1
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val plage = session.plageRenommable("fixture://Main.kt", decalageDe(texte, "amorcer"))
        assertNotNull(plage)
        assertEquals("amorcer", plage!!.placeholder)
        assertEquals(decalageDe(texte, "amorcer"), plage.debut)
    }

    @Test
    fun `plageRenommable - hors identifiant, null (jamais deviné)`() {
        val texte =
            """
            val x = "litteral"
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Curseur DANS le littéral chaîne.
        assertNull(session.plageRenommable("fixture://Main.kt", decalageDe(texte, "litteral") + 2))
    }

    @Test
    fun `renommage - déclaration, usages et import multi-fichiers`() {
        val bibli =
            """
            package outils

            fun triple(x: Int): Int = x * 3
            """.trimIndent()
        val appelant =
            """
            package principal

            import outils.triple

            fun essai(): Int = triple(2)
            """.trimIndent()
        val session = sessionAvecRacines("Biblio.kt" to bibli, "Appelant.kt" to appelant)
        session.ouvrirDocument("fixture://Biblio.kt", bibli)
        val occurrences = session.renommage("fixture://Biblio.kt", decalageDe(bibli, "triple"))
        // Déclaration (Biblio) + usage + import (Appelant).
        assertEquals(3, occurrences.size)
        assertEquals(1, occurrences.count { it.uri.endsWith("Biblio.kt") })
        assertEquals(2, occurrences.count { it.uri.endsWith("Appelant.kt") })
    }

    @Test
    fun `renommage - sans cible prouvée, liste vide (l'hôte en fait une erreur typée)`() {
        val texte =
            """
            val x = 1
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Curseur sur le littéral : rien à renommer.
        assertTrue(session.renommage("fixture://Main.kt", decalageDe(texte, "1")).isEmpty())
    }

    // ── Tolérance ─────────────────────────────────────────────────────

    @Test
    fun `document inconnu et session fermée - listes vides, sans exception`() {
        val session = sessionAvecRacines("Main.kt" to "val x = 1")
        assertTrue(session.references("fixture://Inconnu.kt", 0).isEmpty())
        assertTrue(session.surlignagesDocument("fixture://Inconnu.kt", 0).isEmpty())
        assertNull(session.plageRenommable("fixture://Inconnu.kt", 0))
        assertTrue(session.renommage("fixture://Inconnu.kt", 0).isEmpty())
        session.ouvrirDocument("fixture://Main.kt", "val x = 1")
        session.close()
        assertTrue(session.references("fixture://Main.kt", 0).isEmpty())
        assertTrue(session.surlignagesDocument("fixture://Main.kt", 0).isEmpty())
        assertNull(session.plageRenommable("fixture://Main.kt", 0))
        assertTrue(session.renommage("fixture://Main.kt", 0).isEmpty())
    }
}
