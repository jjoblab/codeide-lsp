package jo.codeide.lsp.host

import jo.codeide.lsp.model.ProjetLsp
import jo.lspkotlin.api.BilanDernierPassageKotlin
import jo.lspkotlin.api.BreakdownIndexeurKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DureesPhasesKotlin
import jo.lspkotlin.api.EntreeTravailKotlin
import jo.lspkotlin.api.EtatEntreeTravailKotlin
import jo.lspkotlin.api.EvenementIndexationKotlin
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.ObservateurIndexationKotlin
import jo.lspkotlin.api.PhaseDetailleeIndexationKotlin
import jo.lspkotlin.api.PhaseIndexationKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests ADR 0213/0214 (demandes D3/D4/D5 de CodeIDE) — la façade
 * Kotlin expose `etatIndexation` du MÊME type public que Java, traduit
 * 1:1 les événements NEUTRES du pack, pose ses FENÊTRES de repli pour
 * les packs anciens (`definirClasspath` = false) et laisse le moteur
 * reprendre la main pour les packs neufs ; `annulerPassage`/
 * `reesayerEchecs` sont des passe-plats gardés ; `close` DÉSINSCRIT
 * l'observateur (une façade morte ne reçoit plus rien).
 */
class SessionLspKotlinIndexationTest {
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Moteur factice OBSERVABLE : le test pousse les événements. */
    private class MoteurObservable(
        var definisPrisEnCharge: Boolean = true,
        var annulation: Boolean = true,
        var reprise: Boolean = true,
    ) : MoteurLangageKotlin {
        var observateur: ObservateurIndexationKotlin? = null
        val classpathsMoteur = CopyOnWriteArrayList<ClasspathKotlin>()

        override val identifiant: String = "test.kotlin.observable"
        override val version: String = "test"
        override val versionMinimaleApp: String = "0"
        override val versionGrammaire: String = "test"

        override fun analyserSource(
            texte: CharSequence,
            script: Boolean,
        ): jo.lspkotlin.parse.ArbreSyntaxiqueKotlin = error("hors périmètre du test")

        override fun creerSession(): SessionLangageKotlin = error("hors périmètre du test")

        override fun definirClasspath(classpath: ClasspathKotlin): Boolean {
            classpathsMoteur.add(classpath)
            return definisPrisEnCharge
        }

        override fun enregistrerObservateurIndexation(observateur: ObservateurIndexationKotlin): Closeable {
            this.observateur = observateur
            return Closeable { this.observateur = null }
        }

        override fun annulerPassage(): Boolean = annulation

        override fun reesayerEchecs(): Boolean = reprise
    }

    private fun evenementRiche(): EvenementIndexationKotlin =
        EvenementIndexationKotlin(
            phase = PhaseIndexationKotlin.PARTIEL,
            faits = 3,
            total = 4,
            entreeCourante = "/gradle/stdlib.jar",
            avertissements = listOf("/cache/panne.jar : entrée illisible (écartée)"),
            erreurs = emptyList(),
            phaseDetaillee = PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES,
            listeTravail =
                listOf(
                    EntreeTravailKotlin("/gradle/stdlib.jar", EtatEntreeTravailKotlin.REPRIS),
                    EntreeTravailKotlin("/cache/panne.jar", EtatEntreeTravailKotlin.ECARTE),
                ),
            breakdownIndexeurs =
                listOf(BreakdownIndexeurKotlin("kidx-segments", "Segments Kotlin (kidx)", 250, 4)),
            dureesPhases = DureesPhasesKotlin(bibliothequesMs = 250, sourcesMs = 40),
            bilanDernierPassage =
                BilanDernierPassageKotlin(
                    dureeMs = 290,
                    artefactsTotal = 4,
                    artefactsRepris = 2,
                    artefactsReconstruits = 1,
                    fichiersParcourus = 12,
                    fichiersReanalyses = 3,
                    indexeurPlusCouteux = "kidx-segments",
                    horodatageMs = 1_000,
                ),
            settled = true,
            reprises = 2,
        )

    @Test
    fun `etatIndexation - traduction 1 pour 1 de l evenement moteur`() {
        val moteur = MoteurObservable()
        val facade = SessionLspKotlin(dossierTemp.resolve("traduction"), moteur, dossierIndex = null)
        assertEquals(PhaseIndexation.INITIAL, facade.etatIndexation.value.phase)
        moteur.observateur?.surEvenement(evenementRiche())
        val etat = facade.etatIndexation.value
        assertEquals(PhaseIndexation.PARTIEL, etat.phase)
        assertEquals(3, etat.faits)
        assertEquals(4, etat.total)
        assertEquals("/gradle/stdlib.jar", etat.entreeCourante)
        assertEquals(PhaseDetailleeIndexation.BIBLIOTHEQUES, etat.phaseDetaillee)
        assertEquals(1, etat.avertissements.size)
        assertEquals(2, etat.listeTravail.size)
        assertEquals(EtatEntreeTravailIndexation.REPRIS, etat.listeTravail[0].etat)
        assertEquals(EtatEntreeTravailIndexation.ECARTE, etat.listeTravail[1].etat)
        assertEquals("kidx-segments", etat.breakdownIndexeurs.single().identifiant)
        assertEquals(250L, etat.dureesPhases!!.bibliothequesMs)
        assertEquals(290L, etat.bilanDernierPassage!!.dureeMs)
        assertTrue(etat.settled)
        assertEquals(2, etat.reprises)
        facade.close()
    }

    @Test
    fun `fenetres - pack ancien, CONSTRUCTION puis PRET autour de la poussee`() {
        val moteur = MoteurObservable(definisPrisEnCharge = false)
        val facade = SessionLspKotlin(dossierTemp.resolve("ancien"), moteur, dossierIndex = null)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        // Pack ancien : la poussée synchrone a eu lieu (le classpath est
        // parti au MOTEUR aussi — l'API existe, il répond « non pris en
        // charge »), la fenêtre referme PRET (le monde d'avant, jamais
        // de PARTIEL inventé).
        assertEquals(PhaseIndexation.PRET, facade.etatIndexation.value.phase)
        assertEquals(0, facade.etatIndexation.value.total)
        assertEquals(1, moteur.classpathsMoteur.size)
        facade.close()
    }

    @Test
    fun `fenetres - pack neuf, la fenetre CONSTRUCTION attend les evenements`() {
        val moteur = MoteurObservable(definisPrisEnCharge = true)
        val facade = SessionLspKotlin(dossierTemp.resolve("neuf"), moteur, dossierIndex = null)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        // Pack neuf : la fenêtre CONSTRUCTION reste ouverte — le PRET ne
        // viendra QUE du moteur (l'événement reprend la main).
        assertEquals(PhaseIndexation.CONSTRUCTION, facade.etatIndexation.value.phase)
        moteur.observateur?.surEvenement(evenementRiche())
        assertEquals(PhaseIndexation.PARTIEL, facade.etatIndexation.value.phase)
        facade.close()
    }

    @Test
    fun `controles - passe-plats gardes vers le moteur (D4 et D5)`() {
        val moteur = MoteurObservable(annulation = false, reprise = false)
        val facade = SessionLspKotlin(dossierTemp.resolve("controles"), moteur, dossierIndex = null)
        assertEquals(false, facade.annulerPassage())
        assertEquals(false, facade.reesayerEchecs())
        moteur.annulation = true
        moteur.reprise = true
        assertEquals(true, facade.annulerPassage())
        assertEquals(true, facade.reesayerEchecs())
        facade.close()
    }

    @Test
    fun `close desinscrit - une facade morte ne recoit plus rien`() {
        val moteur = MoteurObservable()
        val facade = SessionLspKotlin(dossierTemp.resolve("close"), moteur, dossierIndex = null)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        facade.close()
        val avant = facade.etatIndexation.value
        // Le moteur émet ENCORE (le pack ne sait pas) : la façade morte
        // est désinscrite — le flux ne bouge plus.
        moteur.observateur?.surEvenement(evenementRiche())
        assertEquals(avant, facade.etatIndexation.value)
        assertTrue(moteur.observateur == null, "l'observateur est désinscrit")
    }
}
