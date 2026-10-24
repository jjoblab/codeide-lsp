package jo.codeide.lsp.host

import jo.codeide.lsp.model.ProjetLsp
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.EvenementIndexationXml
import jo.lspxml.api.MoteurLangageXml
import jo.lspxml.api.ObservateurIndexationXml
import jo.lspxml.api.PhaseIndexationXml
import jo.lspxml.api.SessionLangageXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests ADR 0213/0214 — la façade XML expose `etatIndexation` du MÊME
 * type public que Java/Kotlin, traduit les événements NEUTRES du pack,
 * pose ses FENÊTRES de repli pour les packs anciens et laisse le
 * moteur reprendre la main pour les packs neufs (miroir exact de
 * [SessionLspKotlinIndexationTest]).
 */
class SessionLspXmlIndexationTest {
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Moteur factice OBSERVABLE : le test pousse les événements. */
    private class MoteurObservable(
        var definisPrisEnCharge: Boolean = true,
        var annulation: Boolean = true,
        var reprise: Boolean = true,
    ) : MoteurLangageXml {
        var observateur: ObservateurIndexationXml? = null
        val classpathsMoteur = CopyOnWriteArrayList<ClasspathXml>()

        override val identifiant: String = "test.xml.observable"
        override val version: String = "test"
        override val versionMinimaleApp: String = "0"

        override fun analyserSource(texte: CharSequence): jo.lspxml.parse.DocumentXml = error("hors périmètre du test")

        override fun creerSession(): SessionLangageXml = error("hors périmètre du test")

        override fun definirClasspath(classpath: ClasspathXml): Boolean {
            classpathsMoteur.add(classpath)
            return definisPrisEnCharge
        }

        override fun enregistrerObservateurIndexation(observateur: ObservateurIndexationXml): Closeable {
            this.observateur = observateur
            return Closeable { this.observateur = null }
        }

        override fun annulerPassage(): Boolean = annulation

        override fun reesayerEchecs(): Boolean = reprise
    }

    private fun evenementPret(): EvenementIndexationXml =
        EvenementIndexationXml.INITIAL.copy(
            phase = PhaseIndexationXml.PRET,
            faits = 2,
            total = 2,
            settled = true,
        )

    @Test
    fun `etatIndexation - traduction et fenetres pack ancien et neuf`() {
        // Pack NEUF : la fenêtre CONSTRUCTION attend les événements.
        val neuf = MoteurObservable(definisPrisEnCharge = true)
        val facadeNeuf = SessionLspXml(dossierTemp.resolve("neuf"), neuf)
        facadeNeuf.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        assertEquals(PhaseIndexation.CONSTRUCTION, facadeNeuf.etatIndexation.value.phase)
        neuf.observateur?.surEvenement(evenementPret())
        assertEquals(PhaseIndexation.PRET, facadeNeuf.etatIndexation.value.phase)
        assertEquals(2, facadeNeuf.etatIndexation.value.faits)
        assertTrue(facadeNeuf.etatIndexation.value.settled)
        facadeNeuf.close()

        // Pack ANCIEN : la fenêtre referme PRET après la poussée.
        val ancien = MoteurObservable(definisPrisEnCharge = false)
        val facadeAncien = SessionLspXml(dossierTemp.resolve("ancien"), ancien)
        facadeAncien.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        assertEquals(PhaseIndexation.PRET, facadeAncien.etatIndexation.value.phase)
        assertEquals(1, ancien.classpathsMoteur.size)
        facadeAncien.close()
    }

    @Test
    fun `controles - passe-plats gardes vers le moteur (D4 et D5)`() {
        val moteur = MoteurObservable(annulation = false, reprise = false)
        val facade = SessionLspXml(dossierTemp.resolve("controles"), moteur)
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
        val facade = SessionLspXml(dossierTemp.resolve("close"), moteur)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"), null)
        facade.close()
        val avant = facade.etatIndexation.value
        moteur.observateur?.surEvenement(evenementPret())
        assertEquals(avant, facade.etatIndexation.value)
        assertTrue(moteur.observateur == null, "l'observateur est désinscrit")
    }
}
