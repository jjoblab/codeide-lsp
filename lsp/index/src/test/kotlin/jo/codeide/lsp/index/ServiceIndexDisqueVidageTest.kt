package jo.codeide.lsp.index

import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vidage et occupation du stockage (lot LSP-1, écran L3 « Index et
 * cache ») : la taille mesure les SEGMENTS (temporaires exclus), le
 * vidage supprime segments ET temporaires — via le service comme via
 * les miroirs statiques (session sans vue alignée).
 */
class ServiceIndexDisqueVidageTest {
    private val stockage = FixturesIndex.dossierTemporaire("vidage")

    /** Analyseur PASSIF : aucun travail attendu (aucune construction lancée). */
    private class AnalyseurPassif : AnalyseurClasspath {
        override fun analyser(
            entree: EntreeAIndexer,
            repriseValide: (cle: String) -> Boolean,
        ): ResultatAnalyse = error("aucune analyse attendue : ${entree.chemin}")
    }

    /** Segment minimal écrit par l'API réelle (nom opaque, crc valide). */
    private fun ecrireSegment(
        entrepot: EntrepotSegments,
        empreinte: String,
    ) {
        entrepot.ecrire(
            AssembleurSegment.assembler(
                conteneur = Conteneur.Jar("/fictif-$empreinte.jar"),
                cle = empreinte,
                tailleSource = 1L,
                mtimeSource = 1L,
                moisson =
                    listOf(
                        Triple("exo/A_$empreinte.class", MembresClasse(1, emptyList(), emptyList()), null),
                    ),
            ),
            empreinte,
        )
    }

    @Test
    fun `miroirs statiques - taille mesure les segments, vidage purge tout`() {
        val dossier = File(stockage, "index-1")
        assertEquals("stockage absent = 0 octet", 0L, ServiceIndexDisque.tailleStockageDe(dossier))

        val entrepot = EntrepotSegments(dossier)
        ecrireSegment(entrepot, "e1")
        // Un `.tmp` d'écriture interrompue : EXCLU du décompte, purgé au vidage.
        val segments = File(dossier, "segments").apply { mkdirs() }
        File(segments, "interrompu.tmp").writeBytes(ByteArray(64))

        val tailleSegment = entrepot.tailleTotale()
        assertTrue("le segment compte", tailleSegment > 0)
        assertEquals("temporaires exclus du décompte", tailleSegment, ServiceIndexDisque.tailleStockageDe(dossier))

        val vidage = ServiceIndexDisque.viderStockageDe(dossier)
        assertEquals("un segment supprimé", 1, vidage.first)
        assertEquals("octets libérés = taille des segments", tailleSegment, vidage.second)
        assertEquals("stockage vide après vidage", 0L, ServiceIndexDisque.tailleStockageDe(dossier))
        assertEquals("segments ET temporaires purgés", 0, segments.listFiles()?.size ?: 0)
    }

    @Test
    fun `instance - taille et vidage du service vivant`() {
        val dossier = File(stockage, "index-2")
        val entrepot = EntrepotSegments(dossier)
        ecrireSegment(entrepot, "e1")
        val tailleSegment = entrepot.tailleTotale()

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())
        val service =
            ServiceIndexDisque(
                entrepot = EntrepotSegments(dossier),
                analyseur = AnalyseurPassif(),
                scope = scope,
                dispatcheurIo = kotlinx.coroutines.Dispatchers.IO,
                parallelisme = 1,
                budgetCacheOctets = 1024 * 1024,
            )

        assertEquals("l'instance mesure l'entrepôt", tailleSegment, service.tailleStockage())
        val vidage = service.viderStockage()
        assertEquals("un segment supprimé par l'instance", 1, vidage.first)
        assertEquals("stockage vide après vidage", 0L, service.tailleStockage())

        scope.cancel()
    }
}
