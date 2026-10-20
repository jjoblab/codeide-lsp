package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la NAVIGATION DE TYPES Kotlin (L2 — plan 1.3.0,
 * `textDocument/typeDefinition` et `textDocument/implementation`) — par
 * le SPI (chemin de production D6), moteur RÉEL, projet multi-fichiers
 * sur disque (racines sources du scan, même montage que les
 * occurrences S2).
 *
 * Le cœur éprouvé : la PREUVE PAR FQCN. Une cible est servie seulement
 * si son nom résout par les IMPORTS du fichier du curseur (ou par
 * unicité projet) ; un héritier est listé seulement si le supertype
 * ÉCRIT résout par les imports de SON PROPRE fichier — jamais par
 * homonymie de nom simple. L'incertain rend VIDE (D3).
 */
class TypesKotlinTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /**
     * Session avec racines sources (scan multi-fichiers actif) — les
     * documents sont ouverts sous leur URI disque RÉELLE (comme la
     * production : le scan exclut les documents ouverts PAR URI).
     */
    private fun sessionAvecRacines(
        vararg fichiers: Pair<String, String>,
    ): Pair<SessionLangageKotlin, Map<String, String>> {
        val racine = dossierTemporaire.newFolder("src")
        val uris = mutableMapOf<String, String>()
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine, chemin)
            fichier.parentFile.mkdirs()
            fichier.writeText(contenu)
            uris[chemin] = fichier.toURI().toString()
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
        return session to uris
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

    // ── typeDefinition : la chaîne de résolution ─────────────────────

    @Test
    fun `typeDefinition référence de type - la déclaration du type visé`() {
        val texte =
            """
            open class Base

            class Derivee : Base()

            fun f(param: Base) {
                val copie = param
            }
            """.trimIndent()
        val session = sessionAvecRacines("Types.kt" to texte).first
        session.ouvrirDocument("fixture://Types.kt", texte)
        // Curseur sur la RÉFÉRENCE de type de « param: Base » (3e
        // occurrence du nom) → le NOM déclaré de la classe.
        val editions = session.definitionsType("fixture://Types.kt", decalageDe(texte, "Base", 2))
        assertEquals(1, editions.size)
        val attendu = decalageDe(texte, "Base")
        assertEquals("fixture://Types.kt", editions.single().uri)
        assertEquals(attendu, editions.single().debut)
        assertEquals(attendu + "Base".length, editions.single().fin)
        session.close()
    }

    @Test
    fun `typeDefinition symbole typé - propriété et paramètre de type écrit`() {
        val texte =
            """
            open class Base

            val prop: Base = Base()

            fun f(param: Base) {
            }
            """.trimIndent()
        val session = sessionAvecRacines("Types.kt" to texte).first
        session.ouvrirDocument("fixture://Types.kt", texte)
        val attendu = decalageDe(texte, "Base")
        // Curseur sur le NOM de la propriété → le type ÉCRIT.
        assertEquals(
            listOf(attendu),
            session.definitionsType("fixture://Types.kt", decalageDe(texte, "prop"))
                .map { it.debut },
        )
        // Curseur sur le NOM du paramètre → le type ÉCRIT.
        assertEquals(
            listOf(attendu),
            session.definitionsType("fixture://Types.kt", decalageDe(texte, "param"))
                .map { it.debut },
        )
        session.close()
    }

    @Test
    fun `typeDefinition constructeur - la classe construite`() {
        val texte =
            """
            open class Base

            val prop: Base = Base()
            """.trimIndent()
        val session = sessionAvecRacines("Types.kt" to texte).first
        session.ouvrirDocument("fixture://Types.kt", texte)
        // Curseur sur l'APPEL constructeur (2e occurrence) → la
        // déclaration de la classe construite.
        val editions = session.definitionsType("fixture://Types.kt", decalageDe(texte, "Base", 1))
        assertEquals(listOf(decalageDe(texte, "Base")), editions.map { it.debut })
        session.close()
    }

    @Test
    fun `typeDefinition incertain - vide, jamais deviné`() {
        val texte =
            """
            open class Base

            val s: String = "x"
            val n = 42
            """.trimIndent()
        val session = sessionAvecRacines("Types.kt" to texte).first
        session.ouvrirDocument("fixture://Types.kt", texte)
        // Type du classpath SANS source projet : pas servi.
        assertTrue(session.definitionsType("fixture://Types.kt", decalageDe(texte, "s")).isEmpty())
        // Littéral sans type écrit : rien à prouver.
        assertTrue(session.definitionsType("fixture://Types.kt", decalageDe(texte, "42")).isEmpty())
        // Document inconnu : vide, jamais d'exception.
        assertTrue(session.definitionsType("fixture://inconnu.kt", 0).isEmpty())
        session.close()
    }

    // ── implementations : la preuve par FQCN ─────────────────────────

    @Test
    fun `implementations - héritiers prouvés, document courant en tête, fermés du scan ensuite`() {
        val base =
            """
            package o

            open class Base
            """.trimIndent()
        val heritiers =
            """
            package o

            import o.Base

            class HeriteeA : Base()
            class HeriteeB : Base()
            class Etrangere
            """.trimIndent()
        val ferme =
            """
            package o

            import o.Base

            class HeritierFerme : Base()
            """.trimIndent()
        val session = sessionAvecRacines(
            "o/Base.kt" to base,
            "o/Heritiers.kt" to heritiers,
            "o/HeritierFerme.kt" to ferme,
        )
        val courant = session.second.getValue("o/Heritiers.kt")
        session.first.ouvrirDocument(courant, heritiers)
        // Curseur sur le supertype ÉCRIT de HeriteeA.
        val impls = session.first.implementations(courant, decalageDe(heritiers, "Base()"))
        // Les DEUX héritiers du document courant (ordre du fichier)
        // puis le héritier FERMÉ du scan ; l'étrangère sans supertype
        // n'est jamais listée.
        assertEquals(3, impls.size)
        assertEquals(
            listOf(
                decalageDe(heritiers, "HeriteeA"),
                decalageDe(heritiers, "HeriteeB"),
            ),
            impls.take(2).map { it.debut },
        )
        assertTrue(impls.take(2).all { it.uri == courant })
        assertTrue(impls.drop(2).all { it.uri.endsWith("HeritierFerme.kt") })
        session.first.close()
    }

    @Test
    fun `implementations homonymie - le supertype résout par les imports du fichier, pas par nom simple`() {
        val baseO =
            """
            package o

            open class Base
            """.trimIndent()
        val baseZ =
            """
            package z

            open class Base
            """.trimIndent()
        val heritierO =
            """
            package o

            import o.Base

            class HeritierO : Base()
            """.trimIndent()
        val heritierZ =
            """
            package z

            class HeritierZ : Base()
            """.trimIndent()
        val session =
            sessionAvecRacines(
                "o/Base.kt" to baseO,
                "z/Base.kt" to baseZ,
                "o/HeritierO.kt" to heritierO,
                "z/HeritierZ.kt" to heritierZ,
            )
        val courant = session.second.getValue("o/HeritierO.kt")
        session.first.ouvrirDocument(courant, heritierO)
        // Curseur sur le supertype de HeritierO : la cible est o.Base
        // (preuve par l'import du fichier du curseur) ; HeritierZ, dont
        // le « Base » résout vers z.Base (son paquet courant), n'est PAS
        // un héritier de la cible — jamais d'homonymie acceptée.
        val impls = session.first.implementations(courant, decalageDe(heritierO, "Base()"))
        assertEquals(1, impls.size)
        assertEquals(decalageDe(heritierO, "HeritierO"), impls.single().debut)
        assertEquals(courant, impls.single().uri)
        session.first.close()
    }

    @Test
    fun `implementations incertain - vide sans type prouvé`() {
        val texte =
            """
            val n = 42
            """.trimIndent()
        val session = sessionAvecRacines("Seul.kt" to texte).first
        session.ouvrirDocument("fixture://Seul.kt", texte)
        assertTrue(session.implementations("fixture://Seul.kt", decalageDe(texte, "42")).isEmpty())
        assertTrue(session.implementations("fixture://inconnu.kt", 0).isEmpty())
        session.close()
    }
}
