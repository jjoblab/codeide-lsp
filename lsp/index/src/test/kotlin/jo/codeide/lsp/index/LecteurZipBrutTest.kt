package jo.codeide.lsp.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du lecteur ZIP brut — lecture par `RandomAccessFile` +
 * `Inflater` (ADR 0111 §6 : AUCUN `java.util.zip.ZipFile`, motivation
 * CloseGuard documentée par l'ancienne version).
 */
class LecteurZipBrutTest {
    private val lecteur = LecteurZipBrut()

    @Test
    fun `lit un jar DEFLATED et restitue les entrées`() {
        val fichier = File.createTempFile("lsp-index", ".jar")
        fichier.deleteOnExit()
        val contenu = FixturesIndex.jarStandard()
        FixturesIndex.ecrireZip(fichier, contenu)

        lecteur.ouvrir(fichier).use { poignee ->
            assertEquals(contenu.size, poignee.noms.size)
            assertTrue(poignee.contient("alpha/Alpha.class"))
            val octets = poignee.lire("alpha/Alpha.class")
            assertEquals(contenu.getValue("alpha/Alpha.class").size, octets!!.size)
            // La magie d'un fichier de classe est préservée.
            assertEquals(0xCA, octets[0].toInt() and 0xFF)
            assertEquals(0xFE, octets[1].toInt() and 0xFF)
            assertNull(poignee.lire("absent/Inconnu.class"))
        }
    }

    @Test
    fun `lit un jar STORED sans décompression`() {
        val fichier = File.createTempFile("lsp-index", ".jar")
        fichier.deleteOnExit()
        val contenu = mapOf("a/A.class" to FixturesIndex.octetsClasse())
        FixturesIndex.ecrireZip(fichier, contenu, compresse = false)

        lecteur.ouvrir(fichier).use { poignee ->
            val octets = poignee.lire("a/A.class")
            assertArrayEquals(contenu.getValue("a/A.class"), octets)
        }
    }

    @Test
    fun `lit un zip depuis des octets en mémoire (jar interne d'AAR)`() {
        val contenu = mapOf("a/A.class" to FixturesIndex.octetsClasse())
        val octets = FixturesIndex.octetsZip(contenu)

        lecteur.depuisOctets(octets).use { poignee ->
            assertEquals(listOf("a/A.class"), poignee.noms)
            assertArrayEquals(contenu.getValue("a/A.class"), poignee.lire("a/A.class"))
        }
    }

    @Test
    fun `un fichier non-zip est refusé par exception d'ouverture`() {
        val fichier = File.createTempFile("lsp-index", ".bin")
        fichier.deleteOnExit()
        fichier.writeBytes("ceci n'est pas un zip".toByteArray())
        val erreur =
            try {
                lecteur.ouvrir(fichier).use { }
                null
            } catch (e: java.io.IOException) {
                // Attendu : EOCD introuvable — l'échec EST le contrat.
                e
            }
        assertTrue(erreur != null)
    }
}
