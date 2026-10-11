package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du cache LRU borné (budget tas explicite — ADR 0111 §6) :
 * éviction des moins récents, admission d'un segment plus gros que le
 * budget seul, refus mémorisé puis invalidé (auto-guérison).
 */
class CacheSegmentsTest {
    private val stockage = FixturesIndex.dossierTemporaire("cache")

    private fun segmentEcrit(
        entrepot: EntrepotSegments,
        empreinte: String,
        tailleMembre: Int = 4,
    ): SegmentDecode {
        val champs = (1..tailleMembre).map { Membre(0x0001, "champ$it", "I") }
        val donnees =
            AssembleurSegment.assembler(
                conteneur = Conteneur.Jar("/cache/$empreinte.jar"),
                cle = empreinte,
                tailleSource = 1L,
                mtimeSource = 1L,
                moisson = listOf(Triple("a/A\$empreinte.class", MembresClasse(1, champs, emptyList()), null)),
            )
        entrepot.ecrire(donnees, empreinte)
        val lecture = entrepot.lire(empreinte, 1L, 1L)
        assertTrue(lecture is LectureSegment.Ok)
        return (lecture as LectureSegment.Ok).segment
    }

    @Test
    fun `un segment plus gros que le budget reste admis seul`() {
        val entrepot = EntrepotSegments(File(stockage, "gros"))
        val cache = CacheSegments(budgetOctets = 1L, entrepot = entrepot)
        val segment = segmentEcrit(entrepot, "maven:gros", tailleMembre = 2)

        val obtenu = cache.obtenir("maven:gros", 1L, 1L) { _, _ -> }
        assertNotNull(obtenu)
        assertEquals(segment.cle, obtenu!!.cle)
        assertTrue(cache.octetsCourants > 0L)
    }

    @Test
    fun `l'éviction respecte le budget (les moins récents d'abord)`() {
        val entrepot = EntrepotSegments(File(stockage, "eviction"))
        val segment1 = segmentEcrit(entrepot, "maven:un")
        val segment2 = segmentEcrit(entrepot, "maven:deux")
        // Budget juste suffisant pour UN segment.
        val cache = CacheSegments(budgetOctets = segment1.octetsMemoire, entrepot = entrepot)

        cache.obtenir("maven:un", 1L, 1L) { _, _ -> }
        cache.obtenir("maven:deux", 1L, 1L) { _, _ -> }
        // L'ajout du deuxième a évincé le premier (budget dépassé).
        assertTrue(cache.octetsCourants <= segment2.octetsMemoire)
        // Un second accès recharge le premier (traversant) et évince le 2e.
        assertNotNull(cache.obtenir("maven:un", 1L, 1L) { _, _ -> })
        assertTrue(cache.octetsCourants <= segment1.octetsMemoire)
        assertEquals(1, cache.octetsCourants / maxOf(segment1.octetsMemoire, 1L))
    }

    @Test
    fun `un segment corrompu est refusé puis rechargé après invalidation`() {
        val entrepot = EntrepotSegments(File(stockage, "corrompu"))
        segmentEcrit(entrepot, "maven:corrompu")
        val fichier = entrepot.fichierSegment("maven:corrompu")
        val octets = fichier.readBytes()
        octets[octets.size / 2] = (octets[octets.size / 2].toInt() xor 0x33).toByte()
        fichier.writeBytes(octets.toList().toByteArray())

        val corruptions = mutableListOf<String>()
        val cache = CacheSegments(budgetOctets = 1024L * 1024L, entrepot = entrepot)
        assertNull(cache.obtenir("maven:corrompu", 1L, 1L) { empreinte, _ -> corruptions.add(empreinte) })
        // Le refus est mémorisé : pas de nouvelle lecture, pas de nouveau signal.
        assertNull(cache.obtenir("maven:corrompu", 1L, 1L) { empreinte, _ -> corruptions.add(empreinte) })
        assertEquals(1, corruptions.size)

        // Reconstruction (auto-guérison) : le refus est invalidé, le
        // segment ré-écrit redevient lisible.
        segmentEcrit(entrepot, "maven:corrompu")
        cache.invaliderRefus("maven:corrompu")
        assertNotNull(cache.obtenir("maven:corrompu", 1L, 1L) { _, _ -> })
    }

    @Test
    fun `vider libère tout le budget`() {
        val entrepot = EntrepotSegments(File(stockage, "vider"))
        segmentEcrit(entrepot, "maven:vidage")
        val cache = CacheSegments(budgetOctets = 1024L * 1024L, entrepot = entrepot)
        cache.obtenir("maven:vidage", 1L, 1L) { _, _ -> }
        assertTrue(cache.octetsCourants > 0L)
        cache.vider()
        assertEquals(0L, cache.octetsCourants)
    }
}
