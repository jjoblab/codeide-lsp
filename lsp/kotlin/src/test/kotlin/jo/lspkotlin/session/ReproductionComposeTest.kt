package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCTION des faux positifs `kt.unresolved` signalés par le
 * propriétaire sur un projet Android Compose (25/10/2026) — chaque test
 * porte UN motif Compose canonique et constate le verdict du moteur
 * aujourd'hui. Classpath : kotlin-stdlib RÉEL (voie 6 d'ADR 0203) —
 * les motifs ne dépendent d'aucune AAR Compose, ils isolent les FORMES.
 */
class ReproductionComposeTest {
    /** Session nourrie du jar kotlin-stdlib RÉEL (miroir ADR 0203). */
    private fun sessionAvecStdlib(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    .codeSource.location.toURI(),
                            ).path,
                            "repro:stdlib",
                        ),
                    ),
                vue = null,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    private fun nonResolus(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    @Test
    fun `repro - lambda finale a parametre nomme (Scaffold innerPadding)`() {
        val session = sessionAvecStdlib()
        // Motif : trailing lambda avec paramètre EXPLICITE — la forme
        // Scaffold { innerPadding -> ... } de tout projet Compose.
        val source =
            """
            fun demo() {
              val r = listOf(1, 2).map { nombre -> nombre + 1 }
            }
            """.trimIndent()
        val uri = "file:///repro-lambda.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-lambda => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - lambda imbriquee parametres nommes`() {
        val session = sessionAvecStdlib()
        val source =
            """
            fun demo(): List<Int> {
              return listOf(1, 2).flatMap { a ->
                listOf(a, a).map { b -> a + b }
              }
            }
            """.trimIndent()
        val uri = "file:///repro-lambda-imbriquee.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-lambda-imbriquee => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - appel complet qualifie premier segment (paquet)`() {
        val session = sessionAvecStdlib()
        // Motif : appel PLEINEMENT QUALIFIÉ — android.widget.TextView(...)
        // en Kotlin réel : le PREMIER segment (paquet) est nu.
        val source = "val x = kotlin.collections.emptyList<String>()\n"
        val uri = "file:///repro-qualifie.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-qualifie => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - annotation qualifiee`() {
        val session = sessionAvecStdlib()
        // Motif : annotation COMPLÈTEMENT qualifiée @kotlin.jvm.JvmName(...)
        val source =
            """
            @kotlin.jvm.JvmName("autre")
            fun demo() {
            }
            """.trimIndent()
        val uri = "file:///repro-annotation-qualifiee.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-annotation-qualifiee => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - delegation by lazy`() {
        val session = sessionAvecStdlib()
        val source =
            """
            val copain: String by lazy { "x" }

            fun demo(): String {
              return copain
            }
            """.trimIndent()
        val uri = "file:///repro-by-lazy.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-by-lazy => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - import alias puis usage`() {
        val session = sessionAvecStdlib()
        val source =
            """
            import kotlin.math.PI as PHI

            fun demo(): Double {
              return PHI
            }
            """.trimIndent()
        val uri = "file:///repro-alias.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-alias => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - import joker puis usage`() {
        val session = sessionAvecStdlib()
        // Motif : import joker — quasiment tous les fichiers Compose
        // (androidx.compose.foundation.layout.*) — la vue est null ici,
        // le joker doit rester sous-rapporté (pas de preuve).
        val source =
            """
            import kotlin.math.*

            fun demo(): Double {
              return abs(-1.0)
            }
            """.trimIndent()
        val uri = "file:///repro-joker.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-joker => $resultats")
        assertTrue("attendu AUCUN faux positif — obtenu : $resultats", resultats.isEmpty())
        session.close()
    }

    @Test
    fun `repro - appel de fonction importee prefixee par paquet`() {
        val session = sessionAvecStdlib()
        // Motif : l'appelant Compose qualifie parfois le PAQUET entier.
        val source = "fun demo(): List<Int> = kotlin.collections.listOf(1)\n"
        val uri = "file:///repro-paquet-appel.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        println("repro-paquet-appel => $resultats")
        assertEquals(emptyList<String>(), resultats)
        session.close()
    }

    // ── Sondage des AUTRES capacités (miroir LSP Kotlin officiel) ──────

    @Test
    fun `sondage - survol d'un appelable top-level du classpath (println)`() {
        val session = sessionAvecStdlib()
        val source = "fun demo() {\n  println(\"x\")\n}\n"
        val uri = "file:///sondage-survol-println.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("println")
        val survol = session.survol(uri, decalage)
        println("sondage-survol-println => " + (survol?.valeur ?: "null"))
        org.junit.Assert.assertNotNull("le survol d'un appelable indexé doit rendre une signature", survol)
        session.close()
    }

    @Test
    fun `sondage - symboles de document d'un fichier Compose-like`() {
        val session = sessionAvecStdlib()
        val source =
            """
            package demo

            import kotlin.math.PI

            class MainActivity {
              fun onCreate() {
                val x = PI
              }
            }

            fun Greeting(nom: String): String = nom
            """.trimIndent()
        val uri = "file:///sondage-symboles.kt"
        session.ouvrirDocument(uri, source)
        val symboles = session.symbolesDocument(uri)
        // Aplatie : l'arbre LSP est HIÉRARCHIQUE (miroir DocumentSymbol
        // d'Android Studio) — onCreate vit dans les ENFANTS de
        // MainActivity, pas au premier niveau.
        fun aplati(syms: List<jo.lspkotlin.api.SymboleDocumentKotlin>): List<String> =
            syms.flatMap { listOf(it.nom) + aplati(it.enfants) }
        val noms = aplati(symboles)
        println("sondage-symboles (aplati) => $noms")
        org.junit.Assert.assertTrue(
            "MainActivity et Greeting doivent être des symboles",
            noms.containsAll(listOf("MainActivity", "Greeting")),
        )
        org.junit.Assert.assertTrue(
            "la méthode onCreate doit être un symbole ENFANT de MainActivity",
            symboles.single { it.nom == "MainActivity" }.enfants.any { it.nom == "onCreate" },
        )
        session.close()
    }

    @Test
    fun `sondage - references d'une fonction projet`() {
        val session = sessionAvecStdlib()
        val source =
            """
            fun Greeting(nom: String): String = nom

            fun usage(): String = Greeting("x")
            """.trimIndent()
        val uri = "file:///sondage-references.kt"
        session.ouvrirDocument(uri, source)
        // Curseur sur la DÉCLARATION, déclaration INCLUSE : la sémantique
        // LSP canonique (miroir Android Studio) — déclaration + usage.
        val avecDeclaration = session.references(uri, source.indexOf("Greeting"), true)
        // Déclaration EXCLUE : les usages seuls (1 ici — l'appel).
        val sansDeclaration = session.references(uri, source.indexOf("Greeting"), false)
        println("sondage-references => incl=${avecDeclaration.size} excl=${sansDeclaration.size}")
        org.junit.Assert.assertEquals(
            "la déclaration et l'usage de Greeting doivent être trouvés (inclureDeclaration=true)",
            2,
            avecDeclaration.size,
        )
        org.junit.Assert.assertEquals(
            "un seul usage hors déclaration (l'appel Greeting(\"x\"))",
            1,
            sansDeclaration.size,
        )
        session.close()
    }

    @Test
    fun `sondage - completion de nom dans un corps`() {
        val session = sessionAvecStdlib()
        val source =
            """
            fun Greeting(nom: String): String = nom

            fun usage(): String {
              val salut = Gr
            }
            """.trimIndent()
        val uri = "file:///sondage-completion.kt"
        session.ouvrirDocument(uri, source)
        val propositions = session.completion(uri, source.indexOf("Gr") + 2)
        println("sondage-completion => ${propositions.map { it.libelle }.take(10)}")
        org.junit.Assert.assertTrue(
            "Greeting doit être proposé dans la complétion de nom",
            propositions.any { it.libelle.startsWith("Greeting") },
        )
        session.close()
    }
}
