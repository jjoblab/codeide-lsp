package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.EtatEntreeTravailXml
import jo.lspxml.api.EvenementIndexationXml
import jo.lspxml.api.PhaseDetailleeIndexationXml
import jo.lspxml.api.PhaseIndexationXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.resources.IndexRessources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests du PILOTE de passages d'indexation XML (ADR 0211/0213/0214 —
 * demandes D1-annexe/D3/D4/D5 de CodeIDE) : SÉQUENCE (catalogue XE2
 * préchauffé + AAR P3 puis racines `res/`), ANNULATION coopérative
 * (PARTIEL « annulé par l'utilisateur », l'acquis reste, la purge ne
 * s'exécute PAS), REPRISE ciblée (un échec n'est jamais mis en cache
 * mtime) et PRET paritaire avec le contrat Java/Kotlin.
 */
class PasseurIndexationXmlTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Racine source avec res/ minimal (une string déclarée). */
    private fun racineAvecRes(nom: String = "src"): File {
        val racine = dossierTemporaire.newFolder(nom)
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText(
            "<resources><string name=\"app_nom\">CodeIDE</string></resources>",
        )
        return racine
    }

    /** AAR factice : res/values/values.xml porte UNE string déclarée. */
    private fun aarFactice(nom: String): File {
        val aar = dossierTemporaire.newFile("$nom.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("res/values/values.xml"))
            zip.write("<resources><string name=\"lib_$nom\">x</string></resources>".toByteArray())
            zip.closeEntry()
        }
        return aar
    }

    /** Fichier PAS un zip (écart honnête — doute X4). */
    private fun aarCorrompu(nom: String): File {
        val aar = dossierTemporaire.newFile("$nom.aar")
        aar.writeBytes(byteArrayOf(1, 2, 3))
        return aar
    }

    private fun classpath(
        racines: List<File>,
        aars: List<File> = emptyList(),
        plateforme: File? = null,
    ): ClasspathXml =
        ClasspathXml(
            dossierPlateforme = plateforme,
            racinesSources = racines,
            vueNoms = null,
            racinesAar = aars,
        )

    private fun passeurNeuf(): PasseurIndexationXml =
        PasseurIndexationXml(IndexRessources(), CataloguesPartages())

    /** Collecteur d'événements + attente bornée du FINAL. */
    private class Collecteur {
        val evenements = CopyOnWriteArrayList<EvenementIndexationXml>()

        fun attacher(passeur: PasseurIndexationXml): java.io.Closeable =
            passeur.enregistrer { evenements.add(it) }

        fun reinitialiser() = evenements.clear()

        fun attendreFinal(
            secondes: Long = 30,
        ): EvenementIndexationXml {
            val limite = System.currentTimeMillis() + secondes * 1000
            while (System.currentTimeMillis() < limite) {
                evenements
                    .lastOrNull {
                        it.phase == PhaseIndexationXml.PRET || it.phase == PhaseIndexationXml.PARTIEL
                    }?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("aucun événement final (${evenements.size} événements)")
        }
    }

    @Test
    fun `sequence - aar puis racines puis PRET, breakdown paritaire`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            passeur.definirClasspath(classpath(listOf(racineAvecRes()), listOf(aarFactice("material"))))
            val final = collecteur.attendreFinal()
            assertEquals(PhaseIndexationXml.PRET, final.phase)
            assertTrue(final.settled)
            // Liste de travail : l'AAR + la racine res.
            assertEquals(2, final.total)
            assertEquals(2, final.faits)
            // ADR 0213 : breakdown à TROIS indexeurs (catalogue, AAR,
            // ressources) et bilan paritaire.
            assertEquals(3, final.breakdownIndexeurs.size)
            assertNotNull(final.dureesPhases)
            assertNotNull(final.bilanDernierPassage)
            // La SÉQUENCE : bibliothèques puis sources.
            val phases = collecteur.evenements.map { it.phaseDetaillee }
            assertTrue(phases.contains(PhaseDetailleeIndexationXml.BIBLIOTHEQUES))
            assertTrue(phases.contains(PhaseDetailleeIndexationXml.SOURCES))
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `aar corrompu - ECARTE puis gueri par la reprise ciblee`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            val racine = racineAvecRes("src-panne")
            val sain = aarFactice("sain")
            val panne = aarCorrompu("panne")
            passeur.definirClasspath(classpath(listOf(racine), listOf(sain, panne)))
            val premier = collecteur.attendreFinal()
            assertEquals(PhaseIndexationXml.PARTIEL, premier.phase)
            assertEquals(
                EtatEntreeTravailXml.ECARTE,
                premier.listeTravail.first { it.nom == panne.path }.etat,
            )

            // RÉPARATION du disque + reprise : l'écart seul est re-payé
            // (le sain est REPRIS du cache mtime — l'échec n'y vit pas).
            panne.writeBytes(aarFactice("repare").readBytes())
            panne.setLastModified(panne.lastModified() + 10_000)
            collecteur.reinitialiser()
            assertTrue(passeur.reesayerEchecs())
            val reprise = collecteur.attendreFinal()
            assertEquals(PhaseIndexationXml.PRET, reprise.phase)
            assertEquals(
                EtatEntreeTravailXml.TERMINE,
                reprise.listeTravail.first { it.nom == panne.path }.etat,
            )
            assertTrue(reprise.avertissements.none { it.contains(panne.path) })
            assertFalse(passeur.reesayerEchecs())
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `annulation - PARTIEL annule, l acquis survit a la purge`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        var annuleDemande = false
        val poignee =
            passeur.enregistrer { evenement ->
                collecteur.evenements.add(evenement)
                val enBibliotheques =
                    evenement.phase == PhaseIndexationXml.CONSTRUCTION &&
                        evenement.phaseDetaillee == PhaseDetailleeIndexationXml.BIBLIOTHEQUES
                if (!annuleDemande && enBibliotheques && evenement.faits >= 1) {
                    annuleDemande = true
                    passeur.annulerPassage()
                }
            }
        try {
            passeur.definirClasspath(
                classpath(
                    listOf(racineAvecRes("src-annul")),
                    listOf(aarFactice("annul-1"), aarFactice("annul-2")),
                ),
            )
            val final = collecteur.attendreFinal()
            assertEquals(PhaseIndexationXml.PARTIEL, final.phase)
            assertFalse(final.settled)
            assertTrue(
                final.avertissements.any { it.contains("annulé par l'utilisateur") },
            )
            // La REPRISE complète : l'acquis est REPRIS (mtime), les
            // pendances aboutissent.
            collecteur.reinitialiser()
            assertTrue(passeur.reesayerEchecs())
            val reprise = collecteur.attendreFinal()
            assertEquals(PhaseIndexationXml.PRET, reprise.phase)
            assertTrue(reprise.settled)
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `hors vol - annulerPassage et reesayerEchecs refusent honnetement`() {
        val passeur = passeurNeuf()
        assertFalse(passeur.annulerPassage())
        assertFalse(passeur.reesayerEchecs())
    }
}
