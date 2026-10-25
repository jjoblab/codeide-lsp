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

    // ── Références : appelables top-level du MÊME fichier (ADR 0204) ───

    @Test
    fun `references fonction top-level - usages du MEME fichier déclarant (ADR 0204)`() {
        val texte =
            """
            fun triple(x: Int): Int = x * 3

            fun essai(): Int = triple(2) + triple(3)
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Sur la DÉCLARATION : la déclaration + les DEUX usages du même
        // fichier — le garde d'homonymie ne confond plus la cible avec
        // un homonyme (elle-même).
        val references =
            session.references("fixture://Main.kt", decalageDe(texte, "triple"), inclureDeclaration = true)
        assertEquals(3, references.size)
        assertTrue(references.all { it.uri == "fixture://Main.kt" })
        // En ordre du document : déclaration, puis les deux usages.
        assertTrue(references.zipWithNext().all { (a, b) -> a.debut < b.debut })
        // Curseur sur un USAGE : mêmes occurrences.
        val depuisUsage =
            session.references("fixture://Main.kt", decalageDe(texte, "triple", numero = 1))
        assertEquals(2, depuisUsage.size)
        // documentHighlight : le surlignage couvre déclaration + usages.
        val surlignages =
            session.surlignagesDocument("fixture://Main.kt", decalageDe(texte, "triple"))
        assertEquals(3, surlignages.size)
        assertEquals(GenreSurlignageKotlin.ECRITURE, surlignages.first().genre)
        assertTrue(surlignages.drop(1).all { it.genre == GenreSurlignageKotlin.LECTURE })
    }

    @Test
    fun `renommage same-file - déclaration ET usages du fichier déclarant édités (ADR 0204)`() {
        val texte =
            """
            fun triple(x: Int): Int = x * 3

            fun essai(): Int = triple(2) + triple(3)
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // AVANT le correctif : 1 occurrence (déclaration seule) — le
        // renommage produisait du code CASSÉ (usages épargnés).
        val occurrences = session.renommage("fixture://Main.kt", decalageDe(texte, "triple"))
        assertEquals("le renommage doit couvrir déclaration + usages", 3, occurrences.size)
        assertTrue(occurrences.all { it.uri == "fixture://Main.kt" })
    }

    @Test
    fun `references propriété de constructeur - usage non qualifié prouvé (ADR 0206)`() {
        val texte =
            """
            class Boite(private val contenu: Int) {
                fun lire(): Int = contenu
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        // Le paramètre-propriété est DEUX choses (paramètre + PROPRIÉTÉ
        // membre) : l'usage non qualifié dans lire() prouve la cible.
        val references =
            session.references("fixture://Main.kt", decalageDe(texte, "contenu"), inclureDeclaration = true)
        assertEquals(2, references.size)
        assertTrue(references.all { it.uri == "fixture://Main.kt" })
        assertTrue(references.zipWithNext().all { (a, b) -> a.debut < b.debut })
        // Même preuve depuis l'USAGE.
        val depuisUsage =
            session.references("fixture://Main.kt", decalageDe(texte, "contenu", numero = 1))
        assertEquals(1, depuisUsage.size)
        // Et le surlignage document (miroir S8 : propriété de corps).
        val surlignages =
            session.surlignagesDocument("fixture://Main.kt", decalageDe(texte, "contenu"))
        assertEquals(2, surlignages.size)
        assertEquals(GenreSurlignageKotlin.ECRITURE, surlignages.first().genre)
    }

    @Test
    fun `renommage propriété de constructeur - déclaration ET usage édités (ADR 0206)`() {
        val texte =
            """
            class Boite(private val contenu: Int) {
                fun lire(): Int = contenu
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val occurrences = session.renommage("fixture://Main.kt", decalageDe(texte, "contenu"))
        assertEquals("renommage complet du paramètre-propriété", 2, occurrences.size)
        assertTrue(occurrences.all { it.uri == "fixture://Main.kt" })
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

    // ── ADR 0218 : repli Android Studio, curseur sur le MOT-CLÉ ────────

    /**
     * Ouvre le document sous son URI DISQUE RÉELLE — la convention
     * `fixture://` des autres tests crée une COPIE fantôme au balayage
     * (le document ouvert et le fichier du scan portent des URI
     * DISTINCTES, les occurrences du même fichier seraient comptées deux
     * fois) ; l'URI réelle garantit l'exclusion du balayage, miroir de la
     * production (didOpen et scan partagent le même file:// URI).
     */
    private fun uriReelle(chemin: String): String = File(dossierTemporaire.root, "src/$chemin").toURI().toString()

    @Test
    fun `references - curseur sur le mot-cle fun, la declaration englobante est visee (ADR 0218)`() {
        val texte =
            """
            fun saluer(nom: String): String = nom

            fun appel(): String = saluer("monde")
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        val uri = uriReelle("Main.kt")
        session.ouvrirDocument(uri, texte)
        // Curseur sur le MOT-CLÉ `fun` de la DÉCLARATION (aucun
        // identifiant sous le curseur) — Android Studio y répond
        // Find Usages : la déclaration englobante est la cible.
        val references = session.references(uri, decalageDe(texte, "fun saluer"), true)
        assertEquals("déclaration + usage de saluer trouvés depuis le mot-clé fun", 2, references.size)
        session.close()
    }

    @Test
    fun `references - curseur sur le mot-cle class, la declaration englobante est visee (ADR 0218)`() {
        val texte =
            """
            class Pompe {
                fun amorcer(): Int = 1
            }

            fun usage(): Pompe = Pompe()
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        val uri = uriReelle("Main.kt")
        session.ouvrirDocument(uri, texte)
        val references = session.references(uri, decalageDe(texte, "class Pompe"), true)
        assertEquals(
            "déclaration + type écrit + construction de Pompe trouvés depuis le mot-clé class",
            3,
            references.size,
        )
        session.close()
    }

    @Test
    fun `references - curseur dans le CORPS d'une fonction, AUCUNE cible (le corps n'est pas l'en-tête)`() {
        val texte =
            """
            fun saluer(): String {
                return "bonjour"
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        val uri = uriReelle("Main.kt")
        session.ouvrirDocument(uri, texte)
        // Curseur sur `return` — DANS le corps : le repli ne vise PAS la
        // fonction englobante (le corps appartient aux expressions).
        assertTrue(session.references(uri, decalageDe(texte, "return")).isEmpty())
        session.close()
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
