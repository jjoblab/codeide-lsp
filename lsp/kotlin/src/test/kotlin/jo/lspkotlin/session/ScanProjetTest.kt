package jo.lspkotlin.session

import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests du scan des racines sources (KP6 — plan de stabilisation LSP
 * §3.2 S0) : contrat fonctionnel (scan paresseux des `.kt`, exclusion
 * du document courant, invalidation au changement de racines) et
 * accès CONCURRENTS — deux sessions simultanées doivent voir un état
 * cohérent : la paire (racines, scannees) est mutée sous le verrou du
 * scan, la visibilité mémoire est garantie par les champs volatils
 * (miroir de [jo.lspkotlin.index.IndexAppelablesKotlin], l'asymétrie
 * pointée par l'audit est rétablie).
 */
class ScanProjetTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    private val scan = ScanProjet(ParseurKotlin())

    /** Racine factice : deux fichiers `.kt` d'une déclaration chacun. */
    private fun racinesAvecDeuxFichiers(): List<File> {
        val racine = dossierTemporaire.newFolder("src")
        File(racine, "A.kt").writeText("val a = 1\n")
        File(racine, "B.kt").writeText("val b = 2\n")
        return listOf(racine)
    }

    @Test
    fun `sans racines - aucune declaration`() {
        assertTrue(scan.declarations().isEmpty())
    }

    @Test
    fun `scan paresseux - les kt des racines nourrissent le scan`() {
        scan.definirRacines(racinesAvecDeuxFichiers())
        val declarations = scan.declarations()
        assertEquals(2, declarations.size)
        // File.toURI() : schéma `file:` (le chemin FUSE suit, un ou
        // trois slashs selon la racine — le contrat est le schéma).
        assertTrue(declarations.all { it.uri.startsWith("file:") })
    }

    @Test
    fun `document courant exclu - son doublon du scan ne revient pas`() {
        scan.definirRacines(racinesAvecDeuxFichiers())
        val uriA = scan.declarations().first().uri
        val sansA = scan.declarations(uriA)
        assertEquals(1, sansA.size)
        assertTrue(sansA.none { it.uri == uriA })
    }

    @Test
    fun `changement de racines - invalidation puis re-scan`() {
        val premiere = dossierTemporaire.newFolder("src1")
        File(premiere, "A.kt").writeText("val a = 1\n")
        scan.definirRacines(listOf(premiere))
        assertEquals(1, scan.declarations().size)
        val seconde = dossierTemporaire.newFolder("src2")
        File(seconde, "B.kt").writeText("val b = 2\n")
        File(seconde, "C.kt").writeText("val c = 3\n")
        scan.definirRacines(listOf(seconde))
        assertEquals(2, scan.declarations().size)
    }

    @Test
    fun `racines identiques - no-op chaud, meme resultat`() {
        val racines = racinesAvecDeuxFichiers()
        scan.definirRacines(racines)
        assertEquals(2, scan.declarations().size)
        // Sync redondante : le contrat reste le même (pas d'état vide).
        scan.definirRacines(racines)
        assertEquals(2, scan.declarations().size)
    }

    @Test
    fun `acces concurrents - chaque session voit un etat coherent (KP6)`() {
        val racines = racinesAvecDeuxFichiers()
        scan.definirRacines(racines)
        val depart = CountDownLatch(1)
        val pool: ExecutorService = Executors.newFixedThreadPool(NB_FILS)
        val erreurs = mutableListOf<Throwable>()
        repeat(NB_FILS) { index ->
            pool.submit {
                depart.await()
                repeat(ITERATIONS) {
                    try {
                        // Le fil 0 re-définit les MÊMES racines (no-op
                        // chaud) pendant que les autres lisent — le
                        // verrou sérialise, le résultat reste total.
                        if (index == 0) scan.definirRacines(racines)
                        assertEquals(2, scan.declarations().size)
                    } catch (t: Throwable) {
                        synchronized(erreurs) { erreurs.add(t) }
                    }
                }
            }
        }
        depart.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(DELAI_SECONDES, TimeUnit.SECONDS))
        assertEquals("aucun fil ne doit lever", emptyList<Throwable>(), erreurs)
    }

    private companion object {
        /** Fils concurrents (transitions d'onglets simulées). */
        private const val NB_FILS: Int = 4

        /** Itérations par fil (martèlement court, déterministe). */
        private const val ITERATIONS: Int = 50

        /** Budget d'attente du pool en secondes. */
        private const val DELAI_SECONDES: Long = 30L
    }
}
