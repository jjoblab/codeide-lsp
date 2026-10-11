package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests des clés de segments — noms de fichiers opaques et clés dérivées. */
class ClesSegmentsTest {
    @Test
    fun `le nom de fichier est le sha256 opaque de l'empreinte`() {
        val nom = ClesSegments.nomFichier("maven:abcdef")
        assertEquals(64 + ClesSegments.SUFFIXE_SEGMENT.length, nom.length)
        assertTrue(nom.endsWith(ClesSegments.SUFFIXE_SEGMENT))
        assertTrue(ClesSegments.estNomSegment(nom))
        assertTrue(!ClesSegments.estNomSegment("nimporte.quoi"))
        assertTrue(!ClesSegments.estNomSegment(nom.drop(1)))
    }

    @Test
    fun `la même empreinte donne le même nom, une autre non`() {
        assertEquals(
            ClesSegments.nomFichier("maven:abc"),
            ClesSegments.nomFichier("maven:abc"),
        )
        assertNotEquals(
            ClesSegments.nomFichier("maven:abc"),
            ClesSegments.nomFichier("maven:abd"),
        )
    }

    @Test
    fun `clé AAR dépend des jars internes retenus`() {
        val base = ClesSegments.cleArr("maven:sha1", listOf("classes.jar"))
        val meme = ClesSegments.cleArr("maven:sha1", listOf("classes.jar"))
        val autre = ClesSegments.cleArr("maven:sha1", listOf("classes.jar", "libs/x.jar"))
        val autreEmpreinte = ClesSegments.cleArr("maven:sha2", listOf("classes.jar"))
        assertEquals(base, meme)
        assertNotEquals(base, autre)
        assertNotEquals(base, autreEmpreinte)
        assertTrue(ClesSegments.estEmpreinteFichier(base))
    }

    @Test
    fun `clé dossier change avec la signature d'arborescence`() {
        val fichiers = listOf(Triple("a/A.class", 10L, 100L))
        val cle = ClesSegments.cleDossier(fichiers)
        assertEquals(cle, ClesSegments.cleDossier(fichiers))
        assertNotEquals(cle, ClesSegments.cleDossier(listOf(Triple("a/A.class", 11L, 100L))))
        assertNotEquals(cle, ClesSegments.cleDossier(listOf(Triple("a/B.class", 10L, 100L))))
        // Une entrée de plus change la clé.
        assertNotEquals(
            cle,
            ClesSegments.cleDossier(fichiers + Triple("a/B.class", 1L, 1L)),
        )
    }

    @Test
    fun `empreintes fichier indexables reconnues`() {
        assertTrue(ClesSegments.estEmpreinteFichier("maven:x"))
        assertTrue(ClesSegments.estEmpreinteFichier("transform:x"))
        assertTrue(ClesSegments.estEmpreinteFichier("partiel:x"))
        assertTrue(ClesSegments.estEmpreinteFichier("aar:x"))
        assertTrue(!ClesSegments.estEmpreinteFichier("dossier:123"))
        assertTrue(!ClesSegments.estEmpreinteFichier("module:cible"))
    }
}
