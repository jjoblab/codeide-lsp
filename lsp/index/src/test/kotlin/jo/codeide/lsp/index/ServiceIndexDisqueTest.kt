package jo.codeide.lsp.index

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du service d'index disque — états (construction/prêt/partiel/
 * erreur), progression par jar, REPRISE (seul le jar manquant est
 * analysé), +1 dépendance, annulation, auto-guérison d'un segment
 * corrompu, concurrence de vues partageant un segment.
 */
@Suppress("TooManyFunctions")
class ServiceIndexDisqueTest {
    private val stockage = FixturesIndex.dossierTemporaire("service")
    private val dispatcheur: CoroutineDispatcher = Dispatchers.IO

    /** Analyseur lent à volonté : progression et annulation observables. */
    private class AnalyseurReglable(
        var delaiMs: Long = 0,
        val appels: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
        val analyses: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
    ) : AnalyseurClasspath {
        override fun analyser(
            entree: EntreeAIndexer,
            repriseValide: (cle: String) -> Boolean,
        ): ResultatAnalyse {
            appels.add(entree.chemin)
            // Reprise honorée : un segment déjà valide n'est PAS ré-analysé
            // (compté à part — c'est la cible « +1 dépendance »).
            if (repriseValide(entree.empreinte)) {
                return ResultatAnalyse.Reprise(entree.empreinte)
            }
            analyses.add(entree.chemin)
            if (delaiMs > 0) {
                Thread.sleep(delaiMs)
            }
            // FQCN SANS point hors paquet (bijectif entrée ↔ FQCN — les
            // empreintes portent ':' et '.', sanitized en '_').
            val nomSain = entree.empreinte.replace(':', '_').replace('.', '_')
            val moisson =
                listOf(Triple("exo/$nomSain.class", MembresClasse(1, emptyList(), emptyList()), null))
            return ResultatAnalyse.Ok(
                AssembleurSegment.assembler(
                    conteneur = Conteneur.Jar(entree.chemin),
                    cle = entree.empreinte,
                    tailleSource = entree.taille,
                    mtimeSource = entree.mtime,
                    moisson = moisson,
                ),
            )
        }
    }

    private fun service(
        analyseur: AnalyseurClasspath,
        parallelisme: Int = 2,
    ): Pair<ServiceIndexDisque, CoroutineScope> {
        val scope = CoroutineScope(dispatcheur + Job())
        val service =
            ServiceIndexDisque(
                entrepot = EntrepotSegments(File(stockage, "index-${System.nanoTime()}")),
                analyseur = analyseur,
                scope = scope,
                dispatcheurIo = dispatcheur,
                parallelisme = parallelisme,
                budgetCacheOctets = 8L * 1024 * 1024,
            )
        return Pair(service, scope)
    }

    private fun classpath(vararg empreintes: String): ClasspathInstantane =
        ClasspathInstantane(
            entrees =
                empreintes.map { empreinte ->
                    EntreeClasspathInstantane(
                        kind = KindEntree.JAR,
                        chemin = "/cache/$empreinte.jar",
                        portee = "compile",
                        sources = null,
                        aar = null,
                        empreinte = empreinte,
                        existe = true,
                        taille = 1L,
                        mtime = 1L,
                    )
                },
            empreinte = "projet-test",
        )

    @Test
    fun `construction complète mène à PRÊT et localise`() {
        val analyseur = AnalyseurReglable()
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:a", "maven:b"))

        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }

        assertEquals(PhaseIndex.PRET, vue.etat.value.phase)
        assertEquals(2, vue.etat.value.faits)
        assertEquals(2, vue.etat.value.total)
        assertNotNull(vue.localiser("exo.maven_a"))
        scope.cancel()
    }

    @Test
    fun `reprise à chaud ne ré-analyse AUCUN jar (cible +1 dépendance)`() {
        val analyseur = AnalyseurReglable()
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:a", "maven:b"))
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }
        assertEquals(2, analyseur.analyses.size)

        // Réouverture du MÊME classpath : tout est repris, RIEN n'est
        // ré-analysé (seuls les en-têtes sont validés — reprise à chaud).
        val vue2 = service.vuePour(classpath("maven:a", "maven:b"))
        runBlocking { withTimeout(10_000) { service.construire(vue2).join() } }
        assertEquals(PhaseIndex.PRET, vue2.etat.value.phase)
        assertEquals(2, vue2.etat.value.reprises)
        assertEquals(0, vue2.etat.value.erreurs.size)
        assertEquals(2, analyseur.analyses.size)

        // +1 dépendance : SEULE la nouvelle est analysée.
        val vue3 = service.vuePour(classpath("maven:a", "maven:b", "maven:nouveau"))
        runBlocking { withTimeout(10_000) { service.construire(vue3).join() } }
        assertEquals(3, analyseur.analyses.size)
        assertEquals("/cache/maven:nouveau.jar", analyseur.analyses.last())
        assertEquals(2, vue3.etat.value.reprises)
        assertEquals(PhaseIndex.PRET, vue3.etat.value.phase)
        scope.cancel()
    }

    @Test
    fun `jar illisible mène à PARTIEL honnête (avertissement, pas d'échec)`() {
        val analyseur =
            object : AnalyseurClasspath {
                override fun analyser(
                    entree: EntreeAIndexer,
                    repriseValide: (cle: String) -> Boolean,
                ): ResultatAnalyse =
                    if (entree.empreinte == "maven:casse") {
                        ResultatAnalyse.Illisible("jar brouillé")
                    } else {
                        ResultatAnalyse.Reprise(entree.empreinte)
                    }
            }
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:bon", "maven:casse"))
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }

        assertEquals(PhaseIndex.PARTIEL, vue.etat.value.phase)
        assertEquals(1, vue.etat.value.avertissements.size)
        assertTrue(
            vue.etat.value.avertissements[0]
                .contains("jar brouillé"),
        )
        scope.cancel()
    }

    @Test
    fun `écriture impossible mène à ERREUR (stockage en lecture seule simulé)`() {
        // Entrepot vers un fichier (mkdirs échoue) : écriture impossible.
        val fichier = File(stockage, "pas-un-dossier")
        fichier.writeBytes("bloque".toByteArray())
        val scope = CoroutineScope(dispatcheur + Job())
        val service =
            ServiceIndexDisque(
                entrepot = EntrepotSegments(File(fichier, "impossible")),
                analyseur = AnalyseurReglable(),
                scope = scope,
                dispatcheurIo = dispatcheur,
                parallelisme = 1,
                budgetCacheOctets = 1L * 1024 * 1024,
            )
        val vue = service.vuePour(classpath("maven:x"))
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }

        assertEquals(PhaseIndex.ERREUR, vue.etat.value.phase)
        assertTrue(
            vue.etat.value.erreurs
                .isNotEmpty(),
        )
        scope.cancel()
    }

    @Test
    fun `annulation laisse les positions acquises interrogables (pas de faux PRÊT)`() {
        // Séquentiel : l'entrée en cours dort dans Thread.sleep
        // (insensible à l'annulation), les SUIVANTES ne démarrent pas
        // (ensureActive avant chaque entrée). ADR 0214 (demande D5 de
        // CodeIDE) : l'état devient PARTIEL « annulé par l'utilisateur »
        // (settled=false — l'acquis reste interrogable, JAMAIS de faux
        // PRÊT ; l'ancien invariant « reste CONSTRUCTION » est dépassé
        // par la maquette de l'écran d'indexation).
        val analyseur = AnalyseurReglable(delaiMs = 400)
        val (service, scope) = service(analyseur, parallelisme = 1)
        val vue =
            service.vuePour(
                classpath("maven:lent1", "maven:lent2", "maven:lent3", "maven:lent4"),
            )
        val job = service.construire(vue)
        runBlocking {
            withTimeout(5_000) {
                delay(450) // ~1 entrée terminée, 1 en cours
                job.cancel()
                job.join()
            }
        }

        assertEquals(PhaseIndex.PARTIEL, vue.etat.value.phase)
        assertFalse(vue.etat.value.settled)
        assertTrue(
            vue.etat.value.avertissements
                .any { it == "annulé par l'utilisateur" },
        )
        assertTrue(vue.etat.value.faits in 1..2)
        scope.cancel()
    }

    @Test
    fun `segment corrompu est auto-guéri à la lecture (reconstruction seule)`() {
        val analyseur = AnalyseurReglable()
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:guerir"))
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }
        assertEquals(PhaseIndex.PRET, vue.etat.value.phase)
        assertNotNull(vue.localiser("exo.maven_guerir"))

        // Corrompt le segment sur le disque PUIS invalide le cache.
        val fichier = service.entrepot.fichierSegment("maven:guerir")
        val octets = fichier.readBytes()
        octets[octets.size - 1] = (octets[octets.size - 1].toInt() xor 0x55).toByte()
        fichier.writeBytes(octets.toList().toByteArray())
        val vue2 = service.vuePour(classpath("maven:guerir"))
        runBlocking { withTimeout(10_000) { service.construire(vue2).join() } }

        // La lecture du segment corrompu déclenche l'auto-guérison (la
        // reconstruction ré-écrit le fichier) — attendre le Job lancé.
        runBlocking {
            withTimeout(10_000) {
                var tentatives = 0
                while (vue2.localiser("exo.maven_guerir") == null && tentatives < 50) {
                    delay(100)
                    tentatives++
                }
            }
        }
        assertNotNull(vue2.localiser("exo.maven_guerir"))
        scope.cancel()
    }

    @Test
    fun `deux vues partagent les segments (mutualisation inter-modules)`() {
        val analyseur = AnalyseurReglable()
        val (service, scope) = service(analyseur)
        val vueA = service.vuePour(classpath("maven:partage"))
        val vueB = service.vuePour(classpath("maven:partage"))

        runBlocking {
            withTimeout(10_000) {
                launch { service.construire(vueA).join() }
                service.construire(vueB).join()
            }
        }

        assertEquals(PhaseIndex.PRET, vueA.etat.value.phase)
        assertEquals(PhaseIndex.PRET, vueB.etat.value.phase)
        // Le contenu identique n'est analysé qu'une fois par appelant au
        // pire (le même fichier est écrit deux fois, atomiquement) — la
        // LOCALISATION est identique depuis les deux vues.
        assertEquals(
            vueA.localiser("exo.maven_partage"),
            vueB.localiser("exo.maven_partage"),
        )
        scope.cancel()
    }

    @Test
    fun `entrées MODULE ne sont pas indexées`() {
        val analyseur = AnalyseurReglable()
        val (service, scope) = service(analyseur)
        val classpath =
            ClasspathInstantane(
                entrees =
                    listOf(
                        EntreeClasspathInstantane(
                            kind = KindEntree.MODULE,
                            chemin = ":core:ui",
                            portee = null,
                            sources = null,
                            aar = null,
                            empreinte = "module::core:ui",
                            existe = true,
                            taille = 0L,
                            mtime = 0L,
                        ),
                        EntreeClasspathInstantane(
                            kind = KindEntree.JAR,
                            chemin = "/cache/seul.jar",
                            portee = null,
                            sources = null,
                            aar = null,
                            empreinte = "maven:seul",
                            existe = true,
                            taille = 1L,
                            mtime = 1L,
                        ),
                    ),
                empreinte = "projet-mixte",
            )
        val vue = service.vuePour(classpath)
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }

        assertEquals(1, vue.etat.value.total)
        assertNull(vue.localiser("exo.inconnu"))
        scope.cancel()
    }

    // ── ADR 0214 (demandes D4/D5 de CodeIDE) ───────────────────────────

    @Test
    fun `annulation publie PARTIEL annule par l utilisateur (ADR 0214)`() {
        val analyseur = AnalyseurReglable(delaiMs = 400)
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:long-a", "maven:long-b", "maven:long-c"))
        val job = service.construire(vue)
        // Laisse la première entrée démarrer puis annule (drapeau
        // coopératif : la mort survient à l'entrée SUIVANTE).
        runBlocking { delay(150) }
        job.cancel()
        runBlocking { withTimeout(10_000) { job.join() } }
        val etat = vue.etat.value
        assertEquals(PhaseIndex.PARTIEL, etat.phase)
        assertFalse("un passage annulé n'est pas settled", etat.settled)
        assertTrue(
            "l'avertissement d'annulation est publié",
            etat.avertissements.any { it == "annulé par l'utilisateur" },
        )
        scope.cancel()
    }

    /**
     * Analyseur à PANNE PONCTUELLE : l'entrée [enPanne] échoue tant que
     * [repare] n'est pas basculé (écart honnête — jamais de crash).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — reprise/panne/
     * analyse sont les ISSUES du contrat de reprise ciblée (D4).
     */
    @Suppress("ReturnCount")
    private class AnalyseurReparable(
        private val enPanne: String,
        var repare: Boolean = false,
        val analyses: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
    ) : AnalyseurClasspath {
        override fun analyser(
            entree: EntreeAIndexer,
            repriseValide: (cle: String) -> Boolean,
        ): ResultatAnalyse {
            if (repriseValide(entree.empreinte)) return ResultatAnalyse.Reprise(entree.empreinte)
            if (entree.chemin == enPanne && !repare) {
                return ResultatAnalyse.Illisible("panne ponctuelle (test)")
            }
            analyses.add(entree.chemin)
            val nomSain = entree.empreinte.replace(':', '_').replace('.', '_')
            val moisson =
                listOf(Triple("exo/$nomSain.class", MembresClasse(1, emptyList(), emptyList()), null))
            return ResultatAnalyse.Ok(
                AssembleurSegment.assembler(
                    conteneur = Conteneur.Jar(entree.chemin),
                    cle = entree.empreinte,
                    tailleSource = entree.taille,
                    mtimeSource = entree.mtime,
                    moisson = moisson,
                ),
            )
        }
    }

    @Test
    fun `reesayerEchecs - ne retente que les ECARTEES, les saines gardees par semis`() {
        val analyseur = AnalyseurReparable(enPanne = "/cache/maven:malade.jar")
        val (service, scope) = service(analyseur)
        val vue = service.vuePour(classpath("maven:saine", "maven:malade"))
        assertNull("rien à retenter avant tout passage", service.reesayerEchecs(vue))

        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }
        assertEquals(PhaseIndex.PARTIEL, vue.etat.value.phase)
        assertEquals(1, vue.etat.value.avertissements.size)
        assertEquals(
            EtatEntreeTravail.ECARTE,
            vue.etat.value.listeTravail
                .first { it.nom == "/cache/maven:malade.jar" }
                .etat,
        )

        // RÉPARATION + reprise ciblée : SEULE la malade est re-analysée
        // (la saine garde son segment — reprise honorée, jamais
        // re-analysée) ; les messages de la retentée partent, la phase
        // finale est recalculée sur l'ensemble.
        analyseur.repare = true
        val reprise = service.reesayerEchecs(vue)
        assertNotNull("un écart existe", reprise)
        runBlocking { withTimeout(10_000) { reprise!!.join() } }
        assertEquals(PhaseIndex.PRET, vue.etat.value.phase)
        assertTrue(
            "l'avertissement de la retentée part",
            vue.etat.value.avertissements
                .isEmpty(),
        )
        assertEquals(2, vue.etat.value.faits)
        assertEquals(2, vue.etat.value.total)
        assertEquals(
            EtatEntreeTravail.TERMINE,
            vue.etat.value.listeTravail
                .first { it.nom == "/cache/maven:malade.jar" }
                .etat,
        )
        // La cible « reprise ciblée » : chaque entrée n'a été ANALYSÉE
        // qu'une fois — la saine au premier passage (la reprise l'a
        // REPRISE sans la toucher), la malade à la reprise (le premier
        // passage l'a écartée sans analyse).
        assertEquals(1, analyseur.analyses.count { it == "/cache/maven:saine.jar" })
        assertEquals(1, analyseur.analyses.count { it == "/cache/maven:malade.jar" })
        assertNull("plus rien à retenter", service.reesayerEchecs(vue))
        scope.cancel()
    }
}
