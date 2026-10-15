package jo.lspkotlin.session

import jo.lspkotlin.api.AideSignatureKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du contrat de session — AIDE À LA SIGNATURE (S1, plan de
 * stabilisation LSP §3.2) : par le SPI (chemin de production D6),
 * moteur RÉEL, candidates du PROJET (fonctions, constructeurs, membres
 * vifs), candidates du CLASSPATH (index Kotlin — jar stdlib RÉEL ;
 * vue factice — méthode JVM du récepteur, même montage que le survol
 * qualifié K3).
 */
class SessionLangageKotlinSignatureTest {
    /** Vue factice : le minimum pour éprouver le récepteur qualifié. */
    private val vue =
        object : VueClasspathKotlin {
            private val membres: Map<String, MembresTypeKotlin> =
                mapOf(
                    "android.widget.TextView" to
                        MembresTypeKotlin(
                            0x0021,
                            emptyList(),
                            listOf(MembreJvmKotlin(0x0001, "setText", "(Ljava/lang/CharSequence;)V")),
                        ),
                )

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
                if (prefixe == "TextV") {
                    listOf(NomTypeKotlin("TextView", listOf("android.widget.TextView")))
                } else {
                    emptyList()
                }

            override fun typesDansPaquet(paquet: String): List<String> =
                if (paquet == "android.widget") listOf("android.widget.TextView") else emptyList()

            override fun estPaquet(nom: String): Boolean = nom == "android" || nom == "android.widget"

            override fun sousPaquetsDirects(nom: String): List<String> =
                if (nom == "android") listOf("android.widget") else emptyList()

            override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

            override fun octetsClasse(fqcn: String): ByteArray? = null
        }

    private fun session(
        entrees: List<EntreeClasspathKotlin> = emptyList(),
        racines: List<File> = emptyList(),
    ): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = entrees,
                vue = vue,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Le jar kotlin-stdlib du classpath de test (index RÉEL). */
    private fun entreeStdlib(): EntreeClasspathKotlin =
        EntreeClasspathKotlin(
            KindEntreeKotlin.JAR,
            File(
                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                    .codeSource.location.toURI(),
            ).path,
            "test:stdlib",
        )

    // ── Candidates du projet ───────────────────────────────────────────

    @Test
    fun `signature - fonction projet, curseur après parenthèse`() {
        val session = session()
        val source =
            """
            fun creer(nom: String, taille: Int): Int = 1
            fun demo() { creer() }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///fonction.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("creer()") + "creer(".length)
        assertNotNull(aide)
        assertEquals(1, aide!!.signatures.size)
        assertEquals("creer(nom: String, taille: Int): Int", aide!!.signatures.single().etiquette)
        assertEquals(listOf("nom: String", "taille: Int"), aide!!.signatures.single().parametres)
        assertEquals(0, aide!!.signatureActive)
        assertEquals(0, aide!!.parametreActif)
        session.close()
    }

    @Test
    fun `signature - paramètre actif après la virgule`() {
        val session = session()
        val source =
            """
            fun creer(nom: String, taille: Int): Int = 1
            fun demo() { creer("a", ) }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///virgule.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("\"a\", ") + 4)
        assertNotNull(aide)
        assertEquals(1, aide!!.parametreActif)
        session.close()
    }

    @Test
    fun `signature - surcharges, signature active = arité compatible`() {
        val session = session()
        val source =
            """
            fun f(x: Int): Int = 1
            fun f(x: Int, y: Int): Int = 1
            fun demo() { f(1, 2) }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///surcharge.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("f(1, 2)") + 5)
        assertNotNull(aide)
        assertEquals(2, aide!!.signatures.size)
        assertEquals("f(x: Int, y: Int): Int", aide!!.signatures[aide!!.signatureActive].etiquette)
        assertEquals(1, aide!!.parametreActif)
        session.close()
    }

    @Test
    fun `signature - paramètres par défaut élargissent l'arité légale`() {
        val session = session()
        val source =
            """
            fun saluer(nom: String, ponctuation: String = "!"): String = nom
            fun demo() { saluer("a") }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///defaut.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("saluer(\"a\")") + "saluer(".length)
        assertNotNull(aide)
        assertEquals(0, aide!!.signatureActive)
        session.close()
    }

    @Test
    fun `signature - constructeur primaire de classe projet`() {
        val session = session()
        val source =
            """
            class Point(x: Int, y: Int)
            fun demo() { Point(1, ) }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///constructeur.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("Point(1, )") + "Point(1, ".length)
        assertNotNull(aide)
        assertEquals("Point(x: Int, y: Int)", aide!!.signatures.single().etiquette)
        assertEquals(1, aide!!.parametreActif)
        session.close()
    }

    @Test
    fun `signature - membre vif de la classe englobante`() {
        val session = session()
        val source =
            """
            class Boite {
                fun ranger(contenu: String): Boolean = true
                fun demo() { ranger() }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///membre.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("ranger()") + "ranger(".length)
        assertNotNull(aide)
        assertEquals("ranger(contenu: String): Boolean", aide!!.signatures.single().etiquette)
        session.close()
    }

    // ── Lambda final ───────────────────────────────────────────────────

    @Test
    fun `signature - lambda final compte pour l'argument suivant`() {
        val session = session()
        val source =
            """
            fun repeter(times: Int, action: () -> Unit): Unit = Unit
            fun demo() { repeter(2) { } }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///lambda.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("{ }") + 1)
        assertNotNull(aide)
        assertEquals(1, aide!!.parametreActif)
        assertEquals(0, aide!!.signatureActive)
        session.close()
    }

    // ── Candidates du classpath ────────────────────────────────────────

    @Test
    fun `signature - appelable de premier niveau du stdlib (index réel)`() {
        val session = session(entrees = listOf(entreeStdlib()))
        val source = "fun demo() { listOf(1, ) }\n"
        val uri = "file:///stdlib.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("listOf(1, ") + "listOf(1, ".length)
        assertNotNull(aide)
        assertTrue(aide!!.signatures.isNotEmpty())
        assertTrue(aide!!.signatures.all { it.etiquette.startsWith("listOf(") })
        assertTrue(aide!!.parametreActif >= 1)
        session.close()
    }

    @Test
    fun `signature - méthode JVM du récepteur qualifié (vue factice)`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo() {
                val v: TextView = creer()
                v.setText()
            }
            fun creer(): TextView = creer()
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///qualifie.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("v.setText()") + "v.setText(".length)
        assertNotNull(aide)
        assertEquals("setText(CharSequence): Unit", aide!!.signatures.single().etiquette)
        assertEquals(listOf("CharSequence"), aide!!.signatures.single().parametres)
        session.close()
    }

    // ── Imbrication et silence ─────────────────────────────────────────

    @Test
    fun `signature - appel niché, le plus profond gagne`() {
        val session = session()
        val source =
            """
            fun exterieur(a: Int): Int = a
            fun interieur(b: String): String = b
            fun demo() { exterieur(interieur()) }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///niche.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("interieur()") + "interieur(".length)
        assertNotNull(aide)
        assertEquals("interieur(b: String): String", aide!!.signatures.single().etiquette)
        session.close()
    }

    @Test
    fun `signature - curseur après l'appel fermé, silence`() {
        val session = session()
        val source =
            """
            fun creer(nom: String): Int = 1
            fun demo() { creer("a") }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///ferme.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("creer(\"a\")") + "creer(\"a\")".length)
        assertNull(aide)
        session.close()
    }

    @Test
    fun `signature - hors appel, document inconnu, session fermée`() {
        val session = session()
        val source = "val x = 1\n"
        val uri = "file:///hors.kt"
        session.ouvrirDocument(uri, source)
        assertNull(session.signature(uri, source.indexOf("x") + 1))
        assertNull(session.signature("file:///jamais-vu.kt", 0))
        session.close()
        assertNull(session.signature(uri, 0))
    }
}
