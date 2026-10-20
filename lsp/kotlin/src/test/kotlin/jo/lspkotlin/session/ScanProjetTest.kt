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
    fun `amorce warm - declarations joint le scan lancé à la définition`() {
        // 0.134.32 : definirRacines AMORCE le scan sur le fil dédié ;
        // une lecture immédiate S'Y JOINT (jamais de double scan) et
        // voit le résultat complet — le coût part à la sync, pas à la
        // première frappe.
        scan.definirRacines(racinesAvecDeuxFichiers())
        assertEquals(2, scan.declarations().size)
        // Re-définition CHANGÉE puis lecture immédiate : le même chemin.
        val autre = dossierTemporaire.newFolder("autre")
        File(autre, "C.kt").writeText("val c = 3\n")
        scan.definirRacines(listOf(autre))
        assertEquals(1, scan.declarations().size)
    }

    @Test
    fun `incremental mtime - le fichier changé re-parse, les autres survivent`() {
        // 0.134.32 : un fichier inchangé n'est JAMAIS re-parsé (cache
        // mtime) ; un fichier CHANGÉ rend ses NOUVELLES déclarations.
        val racines = racinesAvecDeuxFichiers()
        scan.definirRacines(racines)
        assertEquals(2, scan.declarations().size)
        // B.kt change de contenu ET de mtime (forcé — la granularité du
        // système de fichiers masquerait un mtime trop proche).
        val b = File(racines.first(), "B.kt")
        b.writeText("val bDeux = 22\nval bTrois = 23\n")
        // La granularité du FS masque un mtime trop proche : le décalage
        // est FORCÉ (et l'échec serait un refus du FS, pas un silence).
        assertTrue("mtime forcé accepté par le FS", b.setLastModified(System.currentTimeMillis() + 60_000))
        // Une liste de racines RÉELLEMENT différente (le même contenu
        // est un no-op chaud par contrat) déclenche la passe incrémentale.
        val racineAjoutee = dossierTemporaire.newFolder("racine-ajoutee")
        scan.definirRacines(listOf(racines.first(), racineAjoutee))
        val noms = scan.declarations().map { it.nom }
        assertTrue("les nouvelles déclarations de B apparaissent", "bDeux" in noms)
        assertTrue("les déclarations de A survivent (cache)", "a" in noms)
        assertTrue("l'ancienne déclaration de B part", "b" !in noms)
    }

    @Test
    fun `rafraichissement externe - un fichier cree devient visible (L1)`() {
        // Lot L1 (plan 1.3.0, correctif W1) : l'événement de fichier
        // externe (surveillant de l'hôte) appelle rafraichir() — les
        // MÊMES racines, un fichier CRÉÉ hors éditeur : ses déclarations
        // deviennent visibles à la prochaine lecture, sans sync.
        val racines = racinesAvecDeuxFichiers()
        scan.definirRacines(racines)
        assertEquals(2, scan.declarations().size)
        File(racines.first(), "C.kt").writeText("val c = 3\n")
        scan.rafraichir()
        val noms = scan.declarations().map { it.nom }
        assertTrue("c visible après rafraîchissement externe", "c" in noms)
        assertTrue(
            "les déclarations antérieures survivent (cache mtime)",
            "a" in noms && "b" in noms,
        )
    }

    @Test
    fun `rafraichissement externe - un fichier modifie remplace ses declarations (L1)`() {
        val racines = racinesAvecDeuxFichiers()
        scan.definirRacines(racines)
        assertEquals(2, scan.declarations().size)
        val b = File(racines.first(), "B.kt")
        b.writeText("val bDeux = 22\n")
        // La granularité du FS masque un mtime trop proche : le décalage
        // est FORCÉ (même garde que le test incrémental).
        assertTrue("mtime forcé accepté par le FS", b.setLastModified(System.currentTimeMillis() + 60_000))
        scan.rafraichir()
        val noms = scan.declarations().map { it.nom }
        assertTrue("bDeux visible après rafraîchissement externe", "bDeux" in noms)
        assertTrue("l'ancienne déclaration de B part", "b" !in noms)
    }

    @Test
    fun `sans racines - rafraichir est un no-op (L1)`() {
        scan.rafraichir()
        assertTrue(scan.declarations().isEmpty())
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
