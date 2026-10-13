package jo.codeide.lsp.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Test de FUITES à la fermeture (mission « LSP Java » étape 4, prompt 3
 * — ADR 0109 point 4 : « arrêt propre exhaustif, aucune ressource
 * orpheline ») : après [SessionLsp.close], le serveur LSP4J (qui tient
 * l'environnement → moteurs → index par champ FINAL — constat du banc
 * §15.8) doit être COLLECTABLE, les poignées de fichiers retombées,
 * l'usage ultérieur REFUSÉ explicitement.
 *
 * ExplicitGarbageCollectionCall justifié (règle 16) : ce test EST la
 * preuve de collectabilité (prompt 3) — provoquer le GC y est
 * l'instrument de mesure, pas un réflexe de production.
 */
@Suppress("ExplicitGarbageCollectionCall")
@Timeout(120)
class SessionLspTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `fermeture sans ressource orpheline`() {
        // La session vit et meurt DANS le helper : les locales (session,
        // serveur) sont INATTEIGNABLES au retour — c'est le contrat de
        // l'hôte qui lâche TOUT (le serveur tient l'index par champ
        // FINAL, §15.8 : garder la session vivante garderait la chaîne
        // session → orchestrateur → environnement → auditeur → service).
        val fuite = vivreUneConnexionComplete()

        // Le serveur (→ environnement → moteurs → adaptateur → cache
        // LRU) doit être COLLECTABLE : le GC le réclame en quelques
        // passages après que TOUTES les références fortes sont mortes.
        var collecte = false
        var tentative = 0
        // ExplicitGarbageCollectionCall justifié (règle 16) : ce test
        // EST la preuve de collectabilité — provoquer le GC est son
        // instrument de mesure, pas un réflexe de production.
        while (tentative < ESSAIS_GC && !collecte) {
            System.gc()
            collecte = fuite.faible.get() == null
            tentative++
            if (!collecte) Thread.sleep(PAUSE_GC_MS)
        }
        assertTrue(
            collecte,
            "le serveur doit être collectable après close " +
                "(ressource orpheline détectée — cf. indexNoms FINAL, §15.8)",
        )

        // Poignées de fichiers : pas de croissance (jrt/handles fermés
        // par dispose() ; tolérance de 3 pour les FD du test lui-même).
        val poigneesApres = compterPoignees()
        if (poigneesApres >= 0 && fuite.poigneesAvant >= 0) {
            assertTrue(
                poigneesApres - fuite.poigneesAvant <= TOLERANCE_POIGNEES,
                "poignées ${fuite.poigneesAvant} → $poigneesApres après close",
            )
        }
    }

    /** Une vie complète de session (weakref du serveur + FD avant close). */
    private data class Fuite(
        val faible: WeakReference<Any>,
        val poigneesAvant: Int,
    )

    /** Vie et mort d'une session : retourne la traçabilité de la fuite. */
    private fun vivreUneConnexionComplete(): Fuite {
        val racine = Files.createDirectories(tmp.resolve("projet"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index").toFile(),
            )
        session.appliquerClasspath(fixture.resultatClasspath(jar))
        attendrePret(session)

        // Le cycle LSP COMPLET d'une connexion (comme cel-lsp le fera en
        // h2) : initialize → didOpen → completion → shutdown — les
        // exécuteurs du service de documents sont arrêtés par shutdown.
        val serveur = session.serveur()
        serveur
            .initialize(
                org.eclipse.lsp4j.InitializeParams().apply {
                    workspaceFolders =
                        listOf(
                            org.eclipse.lsp4j.WorkspaceFolder(racine.toUri().toString(), "projet"),
                        )
                },
            ).get(10, TimeUnit.SECONDS)
        serveur.initialized(org.eclipse.lsp4j.InitializedParams())
        val main = fixture.dossierApp.resolve("Main.java")
        Files.writeString(main, SOURCE_MAIN)
        serveur.textDocumentService.didOpen(
            org.eclipse.lsp4j.DidOpenTextDocumentParams(
                org.eclipse.lsp4j.TextDocumentItem(
                    main.toUri().toString(),
                    "java",
                    1,
                    SOURCE_MAIN,
                ),
            ),
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)

        val poigneesAvant = compterPoignees()
        val faible = WeakReference<Any>(serveur)
        session.close()

        // Usage ultérieur : refus EXPLICITE (pas de comportement fantôme).
        assertThrows(IllegalStateException::class.java) { session.serveur() }
        assertThrows(IllegalStateException::class.java) { session.reindexer() }
        return Fuite(faible, poigneesAvant)
    }

    @Test
    fun `close est idempotent et le modele se vide`() {
        val racine = Files.createDirectories(tmp.resolve("projet-2"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-2"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-2").toFile(),
            )
        session.appliquerClasspath(fixture.resultatClasspath(jar))
        assertNotNull(session.modele.value)
        session.close()
        session.close() // idempotent
        assertTrue(session.modele.value == null, "le modèle est vidé à la fermeture")
    }

    /**
     * Régression v0.121.1 (retour CI du run 38412598199) : `close` doit
     * attendre l'arrêt EFFECTIF des coroutines — la construction d'index
     * encore en vol à la fermeture (cas de ce test : PAS d'attente du
     * PRÊT) ne doit PLUS écrire de segment APRÈS le retour de [close].
     * Avant le correctif, l'IOException « Failed to delete temp
     * directory » de JUnit provenait exactement de là : des fichiers
     * apparaissaient dans le stockage PENDANT le nettoyage `@TempDir`.
     */
    @Test
    fun `close attend l arret effectif - aucun segment ecrit apres fermeture`() {
        val racine = Files.createDirectories(tmp.resolve("projet-arret"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-arret"))
        val stockage = tmp.resolve("index-arret").toFile()
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = stockage,
            )
        session.appliquerClasspath(fixture.resultatClasspath(jar))
        // Fermeture SANS attendre le PRÊT : la construction est en vol —
        // c'est la course observée sur la CI (machine lente).
        session.close()

        // PREUVE du contrat : plus AUCUN fichier n'apparaît dans le
        // stockage après le retour de close (le « après » est borné :
        // toute écriture fantôme se produit dans les instants qui
        // suivent la fermeture).
        val dossierSegments = File(stockage, "segments")
        val instantane = dossierSegments.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        Thread.sleep(500)
        val apres = dossierSegments.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(
            instantane,
            apres,
            "des segments apparaissent dans le stockage après close — " +
                "la fermeture n'attend pas l'arrêt effectif (régression v0.121.0)",
        )
        assertTrue(session.modele.value == null, "le modèle est vidé à la fermeture")
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

    /** Compte les poignées ouvertes (`/proc/self/fd`, Linux ; -1 sinon). */
    private fun compterPoignees(): Int {
        val dossier = Path.of("/proc/self/fd")
        if (!Files.isDirectory(dossier)) return -1
        return dossier.toFile().list()?.size ?: -1
    }

    private companion object {
        /** Passes de GC avant de conclure à une fuite. */
        private const val ESSAIS_GC: Int = 20

        /** Pause entre passes (ms). */
        private const val PAUSE_GC_MS: Long = 50

        /** Attente du PRÊT (ms). */
        private const val PAUSE_ATTENTE_MS: Long = 20

        /** Tolérance sur les poignées (FD propres au test). */
        private const val TOLERANCE_POIGNEES: Int = 3
    }
}
