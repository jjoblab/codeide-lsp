package jo.codeide.lsp.model

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistance versionnée VALIDÉE (W7) : aller-retour, écart de taille ou
 * d'horodatage détecté (modèle utilisable mais marqué non vérifié),
 * fichier corrompu/absent/version inconnue refusés proprement.
 */
class PersistanceModeleLspTest {
    @get:Rule
    val dossier = TemporaryFolder()

    private val stats = FauxStats()
    private val persistance = PersistanceModeleLsp()

    private fun petitProjet(jar: String): ProjetLsp {
        stats.ajouter(jar)
        val module =
            ConvertisseurModeleLsp.depuisClasspathResult(
                FixturesClasspath.resultat(
                    modules =
                        listOf(
                            FixturesClasspath.module(nom = ":app", entrees = listOf(FixturesClasspath.jar(jar))),
                        ),
                ),
                stats.lecteur,
            )
        return module
    }

    @Test
    fun `aller-retour - le modele relu est identique et coherent`() {
        val jar = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        val projet = petitProjet(jar)
        val fichier = dossier.newFile("lsp-modele.json")

        persistance.ecrire(projet, fichier)
        val relu = persistance.lire(fichier, stats.lecteur)

        assertEquals(projet, relu?.projet)
        assertTrue(relu?.verification is EtatVerification.Coherant)
        assertEquals(1, (relu?.verification as EtatVerification.Coherant).nbEntrees)
    }

    @Test
    fun `un jar retouche marque le modele non verifie - utilisable mais signale`() {
        val jar = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        val projet = petitProjet(jar)
        val fichier = dossier.newFile("lsp-modele.json")
        persistance.ecrire(projet, fichier)

        stats.retoucher(jar, mtime = 2_000L)
        val relu = persistance.lire(fichier, stats.lecteur)

        assertEquals("le modèle reste utilisable immédiatement (W7)", projet, relu?.projet)
        val verification = relu?.verification
        assertTrue("marqué non vérifié : $verification", verification is EtatVerification.NonVerifie)
        assertTrue(
            (verification as EtatVerification.NonVerifie).ecarts.any { it.contains("retouché") },
        )
    }

    @Test
    fun `un jar disparu ou retaille est signale`() {
        val jar = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        val projet = petitProjet(jar)
        val fichier = dossier.newFile("lsp-modele.json")
        persistance.ecrire(projet, fichier)

        stats.retoucher(jar, taille = 999L)
        val retaille = persistance.lire(fichier, stats.lecteur)
        assertTrue(retaille?.verification is EtatVerification.NonVerifie)

        stats.supprimer(jar)
        val disparu = persistance.lire(fichier, stats.lecteur)
        assertTrue((disparu?.verification as EtatVerification.NonVerifie).ecarts.any { it.contains("a disparu") })
    }

    @Test
    fun `fichier absent, corrompu ou version inconnue - refus propre, jamais de crash`() {
        assertNull("absent", persistance.lire(File(dossier.root, "inconnu.json"), stats.lecteur))

        val corrompu = dossier.newFile("corrompu.json")
        corrompu.writeText("{")
        assertNull("corrompu", persistance.lire(corrompu, stats.lecteur))

        val versionInconnue = dossier.newFile("futur.json")
        versionInconnue.writeText("""{"format":99,"projet":null}""")
        assertNull("version de format future", persistance.lire(versionInconnue, stats.lecteur))
    }
}

/** Publication par instantanés successifs (§7.1). */
class DepotModeleLspTest {
    @Test
    fun `le depot publie des instantanes successifs et se vide`() =
        runTest {
            val depot = DepotModeleLsp()
            assertNull(depot.instantane.first())

            val jar = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
            val stats = FauxStats().ajouter(jar)
            val projet =
                ConvertisseurModeleLsp.depuisClasspathResult(
                    FixturesClasspath.resultat(
                        modules =
                            listOf(
                                FixturesClasspath.module(nom = ":app", entrees = listOf(FixturesClasspath.jar(jar))),
                            ),
                    ),
                    stats.lecteur,
                )
            depot.remplacer(projet)
            assertEquals(projet, depot.instantane.first())

            val projetDeux = projet.copy(cheminRacine = "/p2")
            depot.remplacer(projetDeux)
            assertEquals(projetDeux, depot.instantane.first())

            depot.vider()
            assertNull(depot.instantane.first())
        }
}
