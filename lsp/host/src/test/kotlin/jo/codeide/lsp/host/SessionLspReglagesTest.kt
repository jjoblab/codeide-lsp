package jo.codeide.lsp.host

import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test des RÉGLAGES de session (lot LSP-1, ADR 0128 §4) : configuration
 * par défaut = comportement historique, application DYNAMIQUE du plafond
 * de budget et de la surveillance, occupation disque, vidage refusé
 * pendant une indexation puis effectif, capacités par langage.
 *
 * Le refus « indexation en vol » est rendu DÉTERMINISTE par un dispatcher
 * GELÉ : la coroutine de construction est ACTIVE (en file) mais jamais
 * exécutée — aucune course avec la vélocité de la machine.
 */
@Timeout(120)
class SessionLspReglagesTest {
    @TempDir
    lateinit var tmp: Path

    // ── Configuration ──────────────────────────────────────────────────

    @Test
    fun `defauts - configuration historique et surveillance vivante`() {
        val session = sessionNeuve("defauts")
        assertEquals(
            ConfigurationLsp.parDefaut(),
            session.configuration(),
            "les défauts sont le comportement historique (v1.0.x–v1.1.x)",
        )
        assertTrue(session.surveillanceVivante, "surveillant 5 s par défaut")
        session.close()
    }

    @Test
    fun `surveillance desactivee a la construction`() {
        val session =
            SessionLsp(
                racineProjet = racineDe("surveillance-off"),
                stockageIndex = tmp.resolve("index-surveillance-off").toFile(),
                config = ConfigurationLsp(surveillanceFichiersMs = ConfigurationLsp.SURVEILLANCE_DESACTIVEE_MS),
            )
        assertFalse(session.surveillanceVivante, "période 0 = surveillant jamais lancé")
        session.close()
    }

    @Test
    fun `appliquer coupe puis relance la surveillance`() {
        val session = sessionNeuve("surveillance-dynamique")
        assertTrue(session.surveillanceVivante)

        session.appliquer(ConfigurationLsp(surveillanceFichiersMs = 0))
        assertFalse(session.surveillanceVivante, "appliquer(0) annule la boucle")
        assertEquals(0, session.configuration().surveillanceFichiersMs)

        session.appliquer(ConfigurationLsp(surveillanceFichiersMs = 2_000))
        assertTrue(session.surveillanceVivante, "appliquer(2000) relance la boucle")
        assertEquals(2_000, session.configuration().surveillanceFichiersMs)
        session.close()
    }

    @Test
    fun `bornes - periode negative et budget nul refuses`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ConfigurationLsp(surveillanceFichiersMs = -1)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ConfigurationLsp(budgetCacheOctets = 0)
        }
    }

    // ── Budget du cache ─────────────────────────────────────────────────

    @Test
    fun `budget plafonne - l application suivante porte le budget demande`() {
        val (_, fixture, jar) = fixtureDe("budget")
        val session = sessionNeuve("budget")

        val premiere = session.appliquerClasspath(fixture.resultatClasspath(jar))
        attendrePret(session)
        assertEquals(
            MIO,
            premiere.budgetCacheMo,
            "Auto : plancher ADR 0111 (30 Mo) pour un petit corpus",
        )

        // Plafond explicite 1 Mo : l'alignement est REJOUÉ, le service
        // recréé au NOUVEAU budget — la sync suivante (redondante) le
        // CONFIRME dans son résultat public.
        session.appliquer(ConfigurationLsp(budgetCacheOctets = 1L * OCTETS_PAR_MIO))
        val bornee = session.appliquerClasspath(fixture.resultatClasspath(jar))
        assertEquals(1L, bornee.budgetCacheMo, "le budget demandé s'applique (décroissant)")

        // Retour à Auto : re-dérivation (plancher 30 Mo — croissant).
        session.appliquer(ConfigurationLsp.parDefaut())
        val retour = session.appliquerClasspath(fixture.resultatClasspath(jar))
        assertEquals(MIO, retour.budgetCacheMo, "retour Auto : re-dérivation du corpus")
        session.close()
    }

    // ── Occupation et vidage ─────────────────────────────────────────────

    @Test
    fun `taille avant sync - stockage herite compte`() {
        val (racine, fixture, jar) = fixtureDe("heritage")
        val stockage = tmp.resolve("index-heritage").toFile()

        val premiere =
            SessionLsp(racineProjet = racine.toFile(), stockageIndex = stockage)
        premiere.appliquerClasspath(fixture.resultatClasspath(jar))
        attendrePret(premiere)
        premiere.close()

        // Seconde session sur le MÊME stockage, SANS sync : l'occupation
        // se mesure AVANT tout alignement (entrepôt hérité).
        val seconde =
            SessionLsp(racineProjet = racine.toFile(), stockageIndex = stockage)
        assertTrue(seconde.tailleCacheOctets() > 0, "le stockage hérité compte avant le premier sync")
        seconde.close()
    }

    @Test
    fun `viderCache vide le disque apres pret`() {
        val (_, fixture, jar) = fixtureDe("vidage")
        val session = sessionNeuve("vidage")
        session.appliquerClasspath(fixture.resultatClasspath(jar))
        attendrePret(session)

        assertTrue(session.tailleCacheOctets() > 0, "des segments existent après construction")
        val vidage = session.viderCache()
        assertTrue(
            vidage is ResultatVidageCache.Videe,
            "vidage accepté une fois la construction terminée : $vidage",
        )
        vidage as ResultatVidageCache.Videe
        assertTrue(vidage.segmentsSupprimes >= 1, "au moins un segment supprimé")
        assertTrue(vidage.octetsLiberes > 0, "des octets libérés")
        assertEquals(0L, session.tailleCacheOctets(), "stockage vide après vidage")
        session.close()
    }

    @Test
    fun `viderCache refuse pendant une indexation - dispatcher gele`() {
        val (racine, fixture, jar) = fixtureDe("refus")
        val executeur =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        val verrou = java.util.concurrent.CountDownLatch(1)
        // Le SEUL fil du dispatcher est OCCUPÉ à attendre le verrou : la
        // coroutine de construction lancée ensuite reste EN FILE — active,
        // jamais exécutée. Aucune course avec la vitesse de la machine.
        executeur.submit { verrou.await() }
        val gele = executeur.asCoroutineDispatcher()
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-refus").toFile(),
                dispatcheurIo = gele,
                config = ConfigurationLsp(surveillanceFichiersMs = 0),
            )

        session.appliquerClasspath(fixture.resultatClasspath(jar))
        val refus = session.viderCache()
        assertTrue(
            refus is ResultatVidageCache.Refusee &&
                refus.raison == RaisonRefusVidage.INDEXATION_EN_COURS,
            "vidage refusé pendant une indexation en vol : $refus",
        )

        verrou.countDown()
        session.close()
        executeur.shutdownNow()
    }

    // ── Capacités par langage ───────────────────────────────────────────

    @Test
    fun `capacites par langage - tables des interrupteurs`() {
        val session = sessionNeuve("capacites")
        assertEquals(
            setOf(
                CapaciteLsp.COMPLETION,
                CapaciteLsp.SURVOL,
                CapaciteLsp.DIAGNOSTICS,
                CapaciteLsp.SIGNATURES,
                CapaciteLsp.DEFINITION,
                CapaciteLsp.REFERENCES,
                CapaciteLsp.RENOMMAGE,
                CapaciteLsp.ACTIONS_RAPIDES,
                CapaciteLsp.FORMATAGE,
                CapaciteLsp.INDICES_EN_LIGNE,
            ),
            session.capacites(LangageLsp.JAVA),
        )
        assertEquals(
            setOf(
                CapaciteLsp.COMPLETION,
                CapaciteLsp.SURVOL,
                CapaciteLsp.DIAGNOSTICS,
                CapaciteLsp.SIGNATURES,
                CapaciteLsp.DEFINITION,
                CapaciteLsp.REFERENCES,
                CapaciteLsp.RENOMMAGE,
                CapaciteLsp.ACTIONS_RAPIDES,
                // L2 (plan 1.3.0) : formateur borné + indices en ligne.
                CapaciteLsp.FORMATAGE,
                CapaciteLsp.INDICES_EN_LIGNE,
            ),
            session.capacites(LangageLsp.KOTLIN),
        )
        assertEquals(
            setOf(
                CapaciteLsp.COMPLETION,
                CapaciteLsp.SURVOL,
                CapaciteLsp.DIAGNOSTICS,
                CapaciteLsp.DEFINITION,
                // L3 (plan 1.3.0) : références des ressources projet.
                CapaciteLsp.REFERENCES,
                CapaciteLsp.RENOMMAGE,
                CapaciteLsp.ACTIONS_RAPIDES,
                CapaciteLsp.FORMATAGE,
            ),
            session.capacites(LangageLsp.XML),
        )
        session.close()
    }

    // ── Aides ───────────────────────────────────────────────────────────

    private fun racineDe(nom: String): File = Files.createDirectories(tmp.resolve("projet-$nom")).toFile()

    private fun sessionNeuve(nom: String): SessionLsp =
        SessionLsp(
            racineProjet = racineDe(nom),
            stockageIndex = tmp.resolve("index-$nom").toFile(),
        )

    private fun fixtureDe(nom: String): Triple<Path, ProjetFixture, Path> {
        val racine = Files.createDirectories(tmp.resolve("projet-$nom"))
        val fixture = ProjetFixture(racine)
        return Triple(racine, fixture, fixture.construireJarFixture(tmp.resolve("fixture-$nom")))
    }

    /** Attend PRÊT sur le flux public (index fusion construit). */
    private fun attendrePret(session: SessionLsp) {
        val delai = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < delai) {
            if (session.etatIndexation.value.phase == PhaseIndexation.PRET) return
            Thread.sleep(PAUSE_ATTENTE_MS)
        }
        throw AssertionError("index non prêt à temps : ${session.etatIndexation.value}")
    }

    private companion object {
        /** Plancher ADR 0111 en Mio (30 Mo). */
        private const val MIO: Long = 30L

        /** Octets par Mio (le plafond public est en octets). */
        private const val OCTETS_PAR_MIO: Long = 1024L * 1024L

        /** Attente du PRÊT (ms). */
        private const val PAUSE_ATTENTE_MS: Long = 20
    }
}
