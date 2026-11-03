package jo.codeide.lsp.host

import jo.codeide.lsp.kotlin.api.ClasspathKotlin
import jo.codeide.lsp.kotlin.api.ServiceMoteurLangageKotlin
import jo.codeide.lsp.model.ProjetLsp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests de la FENÊTRE d'indexation de la façade Kotlin (bascule ADR
 * 0136) — le pack neuf n'expose PAS la progression fine de sa passe
 * (liste de travail, breakdown, durées) par son SPI : `etatIndexation`
 * est une fenêtre HONNÊTE, INITIAL avant tout sync, CONSTRUCTION au
 * départ de pose, PRET au verdict du guichet moteur
 * ([ServiceMoteurLangageKotlin.definirClasspath]) ; la sous-lecture
 * honnête du pack (aucune réponse servie sur un index non couvert)
 * garantit que la fenêtre ne ment jamais, elle sous-rapporte.
 *
 * Les passe-plats ADR 0214 D4/D5 (annulation coopérative, reprise des
 * échecs) ne sont PAS exposés par le pack neuf : les méthodes restent
 * au contrat public (l'agrégat CodeIDE les appelle pour les trois
 * langues) et rendent `false` — no-ops honnêtes consignées, à rouvrir
 * quand le pack expose les guichets (vague capacités).
 */
class SessionLspKotlinIndexationTest {
    @field:TempDir
    public lateinit var dossierTemp: File

    /**
     * Moteur factice OBSERVABLE : le guichet compté, le verdict
     * paramétrable, et l'état du flux CAPTURÉ pendant l'appel (le
     * seul point d'observation de la fenêtre CONSTRUCTION).
     */
    private class MoteurObservable(
        var prisEnCharge: Boolean = true,
    ) : ServiceMoteurLangageKotlin {
        val classpathsMoteur = CopyOnWriteArrayList<ClasspathKotlin>()

        override val identifiant: String = "test.kotlin.observable"

        override val version: String = "test"

        override val versionMinimaleApp: String = "0"

        override val versionGrammaire: String = "test"

        override fun creerSession(): jo.codeide.lsp.kotlin.api.SessionLangageKotlin =
            error("hors périmètre du test — aucun serveur n'est créé")

        override fun definirClasspath(classpath: ClasspathKotlin): Boolean {
            classpathsMoteur.add(classpath)
            return prisEnCharge
        }
    }

    @Test
    fun `etatIndexation - INITIAL avant tout sync`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("initial"), MoteurObservable(), dossierIndex = null)
        assertEquals(PhaseIndexation.INITIAL, facade.etatIndexation.value.phase)
        facade.close()
    }

    @Test
    fun `fenetre - CONSTRUCTION pendant le guichet, PRET au retour (pack nourricier)`() {
        // Le seul point d'observation de la fenêtre CONSTRUCTION : PENDANT
        // le guichet du moteur (la façade publie via son StateFlow — le
        // test l'échantillonne au moment exact de l'appel).
        val capture = CopyOnWriteArrayList<PhaseIndexation>()
        val facadeRef =
            java.util.concurrent.atomic
                .AtomicReference<SessionLspKotlin>()
        val moteur =
            object : ServiceMoteurLangageKotlin by MoteurObservable() {
                override fun definirClasspath(classpath: ClasspathKotlin): Boolean {
                    capture.add(
                        facadeRef
                            .get()
                            .etatIndexation.value.phase,
                    )
                    return true
                }
            }
        val facade = SessionLspKotlin(dossierTemp.resolve("nourricier"), moteur, dossierIndex = null)
        facadeRef.set(facade)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"))
        // Pendant le guichet : CONSTRUCTION ; au retour : PRET.
        assertEquals(PhaseIndexation.CONSTRUCTION, capture.single())
        assertEquals(PhaseIndexation.PRET, facade.etatIndexation.value.phase)
        facade.close()
    }

    @Test
    fun `fenetre - pack exotique, PRET honnete et guichet compte`() {
        val moteur = MoteurObservable(prisEnCharge = false)
        val facade = SessionLspKotlin(dossierTemp.resolve("exotique"), moteur, dossierIndex = null)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"))
        // Pack exotique : la fenêtre referme PRET (le monde d'avant,
        // jamais de PARTIEL inventé) et le guichet a été sollicité UNE
        // fois (ADR 0211).
        assertEquals(PhaseIndexation.PRET, facade.etatIndexation.value.phase)
        assertEquals(0, facade.etatIndexation.value.total)
        assertEquals(1, moteur.classpathsMoteur.size)
        facade.close()
    }

    @Test
    fun `controles - no-ops honnetes (le pack neuf n expose pas D4 et D5)`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("controles"), MoteurObservable(), dossierIndex = null)
        assertFalse(facade.annulerPassage(), "aucun passage annulable via le pack neuf")
        assertFalse(facade.reesayerEchecs(), "aucune reprise exposée par le pack neuf")
        facade.close()
    }

    @Test
    fun `apres close - appliquerModele est un no-op silencieux`() {
        val moteur = MoteurObservable()
        val facade = SessionLspKotlin(dossierTemp.resolve("close"), moteur, dossierIndex = null)
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"))
        facade.close()
        val avant = facade.etatIndexation.value
        // Façade morte : la pose postérieure est un no-op silencieux —
        // ni guichet sollicité, ni état déplacé (garde fermée).
        facade.appliquerModele(ProjetLsp(dossierTemp.path, emptyList(), "projet"))
        assertEquals(avant, facade.etatIndexation.value)
        assertEquals(1, moteur.classpathsMoteur.size, "le guichet n'a été sollicité qu'une fois")
        assertTrue(moteur.classpathsMoteur.size == 1)
    }
}
