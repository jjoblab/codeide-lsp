package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tests ADR 0211/0212 (demandes D1/D2 de CodeIDE) — BRANCHEMENT et
 * SOUS-LECTURE de la session :
 *
 * - quand le MOTEUR possède le nourrissage (`nourrissageParMoteur`),
 *   `definirClasspath` NE RE-LECT RIEN : l'index reste tel quel et les
 *   requêtes qui prouvent par l'ABSENCE (diagnostics `kt.unresolved`)
 *   se TAISENT (index non couvert ⇒ AUCUN faux négatif) ;
 * - hors moteur nourricier, le contrat SYNCHRONE historique tient
 *   (l'index est nourri ICI, `kt.unresolved` reprend ses droits) ;
 * - le verrou LECTURE-ÉCRITURE : des requêtes parallèles sur des
 *   documents distincts aboutissent sans exception ni interblocage
 *   (ADR 0212 — l'audit de pureté a synchronisé [ClassesSynthetiques]).
 */
class SessionLangageKotlinA211Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /** Classpath de test : le stdlib RÉEL (jamais modifié), empreinte de test. */
    private fun classpathStdlib(): ClasspathKotlin =
        ClasspathKotlin(
            entrees =
                listOf(
                    EntreeClasspathKotlin(
                        KindEntreeKotlin.JAR,
                        jarStdlib().path,
                        "test:a211-stdlib",
                    ),
                ),
            vue = null,
            racinesSources = emptyList(),
            dossierIndex = null,
        )

    /** Session sur index + scan NEUFS (miroir de la construction moteur). */
    private fun sessionNeuve(): Triple<SessionLangageKotlinParse, IndexAppelablesKotlin, ScanProjet> {
        val index = IndexAppelablesKotlin()
        val scan = ScanProjet(ParseurKotlin())
        return Triple(SessionLangageKotlinParse(ParseurKotlin(), index, scan), index, scan)
    }

    /** `return PI` sans import : un `kt.unresolved` SI l'index prouve l'absence. */
    private fun nombreUnresolved(
        session: SessionLangageKotlinParse,
    ): Int {
        val uri = "file:///a211.kt"
        val source = "fun f(): Double {\n  return PI\n}\n"
        session.ouvrirDocument(uri, source)
        return session.diagnostics(uri).count { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
    }

    @Test
    fun `contrat synchrone - hors moteur nourricier, definirClasspath nourrit ici`() {
        val (session, index, _) = sessionNeuve()
        assertFalse("aucun moteur n'a pris la main", index.nourrissageParMoteur)
        session.definirClasspath(classpathStdlib())
        assertTrue("l'index est couvert après le nourrissage synchrone", index.couvre(classpathStdlib().entrees))
        // L'index prouve l'absence : `PI` (kotlin.math, PAS un paquet par
        // défaut) est signalé — le contrat K4 historique tient.
        assertEquals(1, nombreUnresolved(session))
        session.close()
    }

    @Test
    fun `branchement - moteur nourricier, definirClasspath ne re-lect rien`() {
        val (session, index, _) = sessionNeuve()
        // Le moteur SONNE sa prise en main (passe en vol ou aboutie —
        // ici : en vol, le snapshot n'existe pas encore).
        index.sonnerNourrissageMoteur()
        assertTrue(index.nourrissageParMoteur)
        session.definirClasspath(classpathStdlib())
        // BRANCHEMENT : l'index n'a PAS été nourri par la session.
        assertFalse("la session ne re-définit pas sous moteur nourricier", index.couvre(classpathStdlib().entrees))
        assertTrue("l'index reste vide (passe en vol)", index.topParNom("maxOf").isEmpty())
        // SOUS-LECTURE honnête : index non couvert ⇒ le diagnostic
        // `kt.unresolved` se TAIT (impossible de prouver une absence,
        // miroir « index froid = silence » du LSP XML) — jamais de faux
        // négatif pendant la fenêtre.
        assertEquals(0, nombreUnresolved(session))
        session.close()
    }

    @Test
    fun `sous-lecture guerit - le snapshot publie rend l index a la requete suivante`() {
        val (session, index, _) = sessionNeuve()
        index.sonnerNourrissageMoteur()
        session.definirClasspath(classpathStdlib())
        assertEquals(0, nombreUnresolved(session))
        // La passe moteur ABOUTIT (publie le snapshot couvrant) : SANS
        // nouvelle poussée, la requête SUIVANTE voit l'index — c'est le
        // recalcul par requête de `indexSiPret` (jamais d'état périmé).
        index.definir(classpathStdlib().entrees, null)
        assertTrue(index.couvre(classpathStdlib().entrees))
        assertEquals(1, nombreUnresolved(session))
        session.close()
    }

    @Test
    fun `verrou lecture-ecriture - requetes paralleles sans interblocage`() {
        val (session, _, _) = sessionNeuve()
        session.definirClasspath(classpathStdlib())
        val documents = (1..4).map { indice ->
            val uri = "file:///parallele-$indice.kt"
            session.ouvrirDocument(uri, "val v$indice = $indice\nval w = v$indice + v$indice\n")
            uri
        }
        val executeur: ExecutorService = Executors.newFixedThreadPool(6)
        try {
            val taches: List<Future<Int>> =
                (1..24).map { tour ->
                    executeur.submit(
                        Callable {
                            val uri = documents[tour % documents.size]
                            when (tour % 3) {
                                0 -> session.diagnostics(uri).count { it.severite == SeveriteKotlin.ERREUR }

                                1 -> session.completion(uri, 4).size

                                else -> session.survol(uri, 4)?.let { 1 } ?: 0
                            }
                        },
                    )
                }
            // Chaque future aboutit (aucun interblocage) — la complétion
            // d'un document ne bloque pas le diagnostic d'un autre.
            taches.forEach { future -> future.get(60, TimeUnit.SECONDS) }
            assertTrue(session.nombreDocuments == documents.size)
        } finally {
            executeur.shutdownNow()
        }
        session.close()
    }
}
