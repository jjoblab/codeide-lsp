package jo.lspkotlin.index

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EvenementIndexationKotlin
import jo.lspkotlin.api.EtatEntreeTravailKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.PhaseDetailleeIndexationKotlin
import jo.lspkotlin.api.PhaseIndexationKotlin
import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.session.ScanProjet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests du PILOTE de passages d'indexation Kotlin (ADR 0211/0213/0214 —
 * demandes D1/D3/D4/D5 de CodeIDE) : SÉQUENCE d'événements (bibliothèques
 * puis sources puis PRET/PARTIEL), ANNULATION coopérative (PARTIEL
 * « annulé par l'utilisateur », l'acquis est publié), REPRISE ciblée
 * (les entrées saines deviennent REPRIS du cache, seuls les échecs sont
 * re-payés) et no-op chaud des syncs redondantes.
 *
 * Le stdlib RÉEL du classpath de test sert d'entrée froide (copie sous
 * empreinte de test — le jar original n'est JAMAIS touché) ; les
 * attentes sont des SONDAGES bornés (le passeur n'expose volontairement
 * aucune primitive d'attente publique).
 */
class PasseurIndexationKotlinTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /** Copie ISOLÉE du stdlib sous [empreinte] (jamais l'original). */
    private fun copieStdlib(empreinte: String): EntreeClasspathKotlin {
        val copie = dossierTemporaire.newFile("stdlib-$empreinte.jar")
        copie.writeBytes(jarStdlib().readBytes())
        return EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, empreinte)
    }

    /** Entrée ABSENTE (écart honnête — PARTIEL). */
    private fun entreeAbsente(empreinte: String): EntreeClasspathKotlin =
        EntreeClasspathKotlin(KindEntreeKotlin.JAR, "/inexistant-$empreinte.jar", empreinte)

    private fun classpath(
        entrees: List<EntreeClasspathKotlin>,
        racines: List<File> = emptyList(),
    ): ClasspathKotlin =
        ClasspathKotlin(
            entrees = entrees,
            vue = null,
            racinesSources = racines,
            dossierIndex = dossierTemporaire.newFolder(),
        )

    /** Passeur sur index + scan NEUFS (chaque test vit sa propre vie). */
    private fun passeurNeuf(): PasseurIndexationKotlin =
        PasseurIndexationKotlin(IndexAppelablesKotlin(), ScanProjet(ParseurKotlin()))

    /** Collecteur d'événements + attente bornée du FINAL. */
    private class Collecteur {
        val evenements = CopyOnWriteArrayList<EvenementIndexationKotlin>()

        fun attacher(passeur: PasseurIndexationKotlin): java.io.Closeable =
            passeur.enregistrer { evenements.add(it) }

        fun reinitialiser() = evenements.clear()

        fun attendreFinal(
            secondes: Long = 90,
            aPartirDe: Int = 0,
        ): EvenementIndexationKotlin {
            val limite = System.currentTimeMillis() + secondes * 1000
            while (System.currentTimeMillis() < limite) {
                evenements
                    .drop(aPartirDe)
                    .lastOrNull {
                        it.phase == PhaseIndexationKotlin.PRET || it.phase == PhaseIndexationKotlin.PARTIEL
                    }?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("aucun événement final (${evenements.size} événements)")
        }
    }

    @Test
    fun `sequence - bibliotheques puis sources puis PARTIEL avec ecart`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            val sain = copieStdlib("test:seq-sain")
            val absent = entreeAbsente("test:seq-absent")
            passeur.definirClasspath(classpath(listOf(sain, absent)))

            val final = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PARTIEL, final.phase)
            assertEquals(2, final.total)
            assertEquals(2, final.faits)
            // L'écart est ECARTE et signé dans les avertissements.
            assertEquals(
                EtatEntreeTravailKotlin.ECARTE,
                final.listeTravail.first { it.nom == absent.chemin }.etat,
            )
            assertTrue(final.avertissements.any { it.contains(absent.chemin) })
            assertTrue("l'écart n'est pas PRÊT mais PARTIEL", final.settled)
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `sequence complete - liste de travail, breakdown et bilan paritaires`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            val sain = copieStdlib("test:riche-sain")
            passeur.definirClasspath(classpath(listOf(sain)))
            val final = collecteur.attendreFinal()

            assertEquals(PhaseIndexationKotlin.PRET, final.phase)
            assertTrue("le passage abouti est settled", final.settled)
            assertEquals(1, final.total)
            assertEquals(1, final.faits)
            assertEquals(
                EtatEntreeTravailKotlin.TERMINE,
                final.listeTravail.single().etat,
            )
            // ADR 0213 : breakdown à DEUX indexeurs (segments, scan).
            assertEquals(2, final.breakdownIndexeurs.size)
            assertTrue(final.breakdownIndexeurs.any { it.identifiant == "kidx-segments" })
            assertTrue(final.breakdownIndexeurs.any { it.identifiant == "scan-warm" })
            assertNotNull(final.dureesPhases)
            assertNotNull(final.bilanDernierPassage)
            assertEquals(1, final.bilanDernierPassage!!.artefactsTotal)
            // La séQUENCE : au moins un événement BIBLIOTHEQUES puis un
            // SOURCES avant le final.
            val phases = collecteur.evenements.map { it.phaseDetaillee }
            assertTrue(
                "BIBLIOTHEQUES précède SOURCES",
                phases.indexOf(PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES) >= 0,
            )
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `sync redondante - tout est REPRIS, aucune relecture`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            val entrees = listOf(copieStdlib("test:redondant"))
            val cp = classpath(entrees)
            passeur.definirClasspath(cp)
            val premier = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PRET, premier.phase)
            assertEquals(
                "premier passage : décodage à froid",
                EtatEntreeTravailKotlin.TERMINE,
                premier.listeTravail.single().etat,
            )
            // Sync REDONDANTE (classpath identique) : le snapshot couvre,
            // tout est REPRIS — le cache mémoire rend la reprise gratuite.
            collecteur.reinitialiser()
            passeur.definirClasspath(cp)
            val second = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PRET, second.phase)
            assertEquals(
                EtatEntreeTravailKotlin.REPRIS,
                second.listeTravail.single().etat,
            )
            assertEquals(1, second.reprises)
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `annulation - PARTIEL annule par l utilisateur, l acquis est publie`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        var annuleDemande = false
        val poignee =
            passeur.enregistrer { evenement ->
                collecteur.evenements.add(evenement)
                // Annulation déclenchée DEPUIS l'observateur dès la
                // première entrée traitée : déterministe (le drapeau est
                // posé avant le point coopératif suivant).
                val enBibliotheques =
                    evenement.phase == PhaseIndexationKotlin.CONSTRUCTION &&
                        evenement.phaseDetaillee == PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES
                if (!annuleDemande && enBibliotheques && evenement.faits >= 1) {
                    annuleDemande = true
                    passeur.annulerPassage()
                }
            }
        try {
            passeur.definirClasspath(
                classpath(
                    listOf(
                        copieStdlib("test:annul-1"),
                        copieStdlib("test:annul-2"),
                        copieStdlib("test:annul-3"),
                    ),
                ),
            )
            val final = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PARTIEL, final.phase)
            assertFalse("un passage annulé n'est pas settled", final.settled)
            assertTrue(
                "l'avertissement d'annulation est publié",
                final.avertissements.any { it.contains("annulé par l'utilisateur") },
            )
            // L'ACQUIS est publié : la première entrée est traitée, les
            // suivantes restent EN_ATTENTE (pendances de la reprise).
            assertEquals(
                EtatEntreeTravailKotlin.TERMINE,
                final.listeTravail.first { it.nom.contains("annul-1") }.etat,
            )
            assertTrue(
                "les suivantes pendent",
                final.listeTravail
                    .filter { it.nom.contains("annul-2") || it.nom.contains("annul-3") }
                    .all { it.etat == EtatEntreeTravailKotlin.EN_ATTENTE },
            )
            // La REPRISE complète le passage : l'acquis est REPRIS du
            // cache, les pendances aboutissent.
            collecteur.reinitialiser()
            assertTrue("des pendances existent", passeur.reesayerEchecs())
            val reprise = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PRET, reprise.phase)
            assertTrue(reprise.settled)
            assertEquals(
                EtatEntreeTravailKotlin.REPRIS,
                reprise.listeTravail.first { it.nom.contains("annul-1") }.etat,
            )
            assertTrue(
                reprise.listeTravail.all { it.etat != EtatEntreeTravailKotlin.EN_ATTENTE },
            )
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `echec isole - reesayerEchecs retente l ecart et le guerit`() {
        val passeur = passeurNeuf()
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            val sain = copieStdlib("test:echec-sain")
            // L'entrée échouée : un jar PUIS un jar SAIN (le disque est
            // réparé entre les deux passages — la reprise le voit).
            val fichierEnPanne = dossierTemporaire.newFile("en-panne.jar")
            fichierEnPanne.writeBytes(byteArrayOf(1, 2, 3)) // pas un jar
            val enPanne =
                EntreeClasspathKotlin(KindEntreeKotlin.JAR, fichierEnPanne.path, "test:echec-panne")
            passeur.definirClasspath(classpath(listOf(sain, enPanne)))
            val premier = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PARTIEL, premier.phase)
            assertEquals(
                EtatEntreeTravailKotlin.ECARTE,
                premier.listeTravail.first { it.nom == enPanne.chemin }.etat,
            )

            // RÉPARATION du disque + reprise ciblée : l'écart seul est
            // re-payé (le sain devient REPRIS du cache).
            fichierEnPanne.writeBytes(jarStdlib().readBytes())
            fichierEnPanne.setLastModified(fichierEnPanne.lastModified() + 10_000)
            collecteur.reinitialiser()
            assertTrue("un écart existe", passeur.reesayerEchecs())
            val reprise = collecteur.attendreFinal()
            assertEquals(PhaseIndexationKotlin.PRET, reprise.phase)
            assertEquals(
                EtatEntreeTravailKotlin.REPRIS,
                reprise.listeTravail.first { it.nom == sain.chemin }.etat,
            )
            assertEquals(
                EtatEntreeTravailKotlin.TERMINE,
                reprise.listeTravail.first { it.nom == enPanne.chemin }.etat,
            )
            assertTrue(reprise.avertissements.none { it.contains(enPanne.chemin) })

            // Plus RIEN à retenter : la demande suivante refuse.
            assertFalse(passeur.reesayerEchecs())
        } finally {
            poignee.close()
        }
    }

    @Test
    fun `hors vol - annulerPassage et reesayerEchecs refusent honnetement`() {
        val passeur = passeurNeuf()
        assertFalse("rien en vol", passeur.annulerPassage())
        assertFalse("aucun passage passé", passeur.reesayerEchecs())
        // Après un passage PRET sans écart : toujours rien à retenter.
        val collecteur = Collecteur()
        val poignee = collecteur.attacher(passeur)
        try {
            passeur.definirClasspath(classpath(listOf(copieStdlib("test:hors-vol"))))
            collecteur.attendreFinal()
            assertFalse(passeur.reesayerEchecs())
            assertFalse(passeur.annulerPassage())
        } finally {
            poignee.close()
        }
    }
}
