package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tests de l'écriture ATOMIQUE ([EcrivainSegment]) — v0.100.1, retour
 * d'appareil 62f8627d (Android 15 / API 35, NoClassDefFoundError
 * `java.lang.ProcessHandle` à la PREMIÈRE écriture de segment) :
 *
 * - le nom `.tmp` est UNIQUE PAR ÉCRITURE sans AUCUNE API JDK postérieure
 *   au sous-ensemble d'ART (l'ancien `ProcessHandle.current().pid()` +
 *   `Thread.threadId()` sont JDK 9/19 — absents d'ART ≤ 35 : le module
 *   est JVM pur, ses tests tournent sur JVM où ils EXISTENT, la
 *   régression n'était observable QUE sur appareil) ;
 * - deux écritures CONCURRENTES du MÊME contenu vers le MÊME fichier
 *   réussissent TOUTES DEUX (collision de `.tmp` constatée sur CI en
 *   v0.93.1, la course « deux sessions orphelines » de v0.100.0 la
 *   réamène à l'échelle d'un appareil) ;
 * - l'en-tête court se valide par lecture complète bornée (la reprise
 *   utilise `readFully` — `readNBytes` est JDK 9, absent d'ART < 33,
 *   minSdk 26).
 */
class EcrivainSegmentTest {
    private val stockage = FixturesIndex.dossierTemporaire("ecrivain")

    /** Segment de taille réduite (nettement < 4096 octets d'en-tête lu). */
    private fun donnees(cle: String = "maven:segment-test"): DonneesSegment =
        AssembleurSegment.assembler(
            conteneur = Conteneur.Jar("/cache/segment-test.jar"),
            cle = cle,
            tailleSource = 1L,
            mtimeSource = 1L,
            moisson =
                listOf(
                    Triple("alpha/Alpha.class", MembresClasse(1, emptyList(), emptyList()), null),
                    Triple("beta/Profond.class", MembresClasse(1, emptyList(), emptyList()), null),
                ),
        )

    @Test
    fun `le nom temporaire est unique a chaque ecriture`() {
        val cible = File(stockage, "segment.idx")
        val ecrivain = EcrivainSegment()

        val noms = (1..1_000).map { ecrivain.nomTemporaireUnique(cible).name }

        assertEquals("1 000 écritures rapprochées, 1 000 noms distincts", 1_000, noms.toSet().size)
        assertTrue("le nom doit rester suffixé .tmp", noms.all { it.endsWith(".tmp") })
        assertTrue(
            "le nom doit garder le préfixe du fichier cible",
            noms.all { it.startsWith("segment.idx-") },
        )
    }

    @Test
    fun `deux ecritures concurrentes du meme contenu reussissent toutes deux`() {
        val dossier = File(stockage, "concurrent")
        dossier.mkdirs()
        val cible = File(dossier, "segment.idx")
        val donnees = donnees()

        // Deux FILS écrivent le MÊME contenu vers le MÊME fichier : le
        // nom `.tmp` unique garantit que chacune réussit (l'ancienne
        // course v0.93.1 : même nom → renommage perdant → ERREUR).
        val taches: List<Callable<String>> =
            (1..2).map { numero ->
                Callable {
                    try {
                        EcrivainSegment().ecrire(donnees, cible)
                        "succes-$numero"
                    } catch (e: java.io.IOException) {
                        "echec-$numero: ${e.message}"
                    }
                }
            }
        val executeur = Executors.newFixedThreadPool(2)
        try {
            val resultats: List<Future<String>> = executeur.invokeAll(taches)
            resultats.forEach { resultat ->
                assertTrue(
                    "l'écriture concurrente devait réussir : ${resultat.get(10, TimeUnit.SECONDS)}",
                    resultat.get(10, TimeUnit.SECONDS).startsWith("succes"),
                )
            }
        } finally {
            executeur.shutdownNow()
        }

        // Le fichier final est COMPLET et relisible (le dernier écrivain
        // gagne, contenu identique — jamais un fichier à moitié écrit).
        val lecture = LecteurSegment().lire(cible, donnees.cle, 1L, 1L)
        assertTrue(
            "le segment devait se relire après écritures concurrentes : $lecture",
            lecture is LectureSegment.Ok,
        )
        assertEquals(2, (lecture as LectureSegment.Ok).segment.entrees.size)
    }

    @Test
    fun `l en tete court se valide par lecture complete bornée`() {
        val dossier = File(stockage, "en-tete-court")
        dossier.mkdirs()
        val donnees = donnees()
        val fichier = File(dossier, "segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        assertTrue("le segment écrit est nettement plus court que la limite d'en-tête", fichier.length() < 4096)
        assertTrue(
            "la reprise d'un segment COURT doit se valider (lecture complète bornée)",
            LecteurSegment().enTeteValide(fichier, donnees.cle, 1L, 1L),
        )
    }
}
