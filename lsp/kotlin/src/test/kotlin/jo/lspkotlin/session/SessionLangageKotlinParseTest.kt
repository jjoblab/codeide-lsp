package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.TagDiagnosticKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du contrat de session K2 (LSP_KOTLIN.md §5, K2 « session LSP
 * minimale ») — par le SPI ([ServiceMoteurLangageKotlin.unique]), PAS par
 * le type concret : c'est le chemin de production de l'hôte (D6 : le pack
 * futur se branche là, et uniquement là).
 *
 * Comportements éprouvés (fixtures calibrées sur le comportement RÉEL de
 * la grammaire vendorée, sondé avant écriture) :
 * - tolérance : aucun texte ne lève, les erreurs voyagent en diagnostics ;
 * - conservatisme D3 : les formes non modélisées (joker, directives en
 *   édition) ne sont JAMAIS signalées ;
 * - fin de vie : après close, plus rien n'est retenu (test de fuite).
 */
class SessionLangageKotlinParseTest {
    private fun sessionNeuve() = ServiceMoteurLangageKotlin.unique().creerSession()

    @Test
    fun `didOpen invalide - une erreur de syntaxe, plage et message du parseur`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "val x = (")
        val diagnostics = session.diagnostics("file:///a.kt")
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.single()
        assertEquals(CodesDiagnosticsKotlin.SYNTAXE, diagnostic.code)
        assertEquals(SeveriteKotlin.ERREUR, diagnostic.severite)
        assertEquals("Expecting an expression", diagnostic.message)
        assertEquals(9, diagnostic.debut)
        assertEquals(9, diagnostic.fin)
        session.close()
    }

    @Test
    fun `didChange corrige - les diagnostics disparaissent`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "fun {")
        assertEquals(2, session.diagnostics("file:///a.kt").size)
        session.modifierDocument("file:///a.kt", "fun essai() { }\n")
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `didClose - le document est oublie`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "val x = (")
        session.fermerDocument("file:///a.kt")
        assertEquals(0, session.nombreDocuments)
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `document inconnu - liste vide, jamais d'exception`() {
        val session = sessionNeuve()
        assertTrue(session.diagnostics("file:///inconnu.kt").isEmpty())
        session.fermerDocument("file:///inconnu.kt")
        session.close()
    }

    @Test
    fun `import inutilise - signale`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.math.PI\nval x = 1\n")
        val diagnostics = session.diagnostics("file:///a.kt")
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsKotlin.IMPORT_INUTILISE, diagnostics.single().code)
        assertEquals(SeveriteKotlin.AVERTISSEMENT, diagnostics.single().severite)
        session.close()
    }

    @Test
    fun `nom present en commentaire seulement - l'import reste inutilise`() {
        // Un nom dans un COMMENTAIRE n'est pas un identifiant (le lexer
        // sépare déjà) : l'import est réellement inutilisé — le signal est
        // correct, pas un faux positif.
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.math.PI\n// PI en prose\nval x = 1\n")
        assertEquals(1, session.diagnostics("file:///a.kt").size)
        session.close()
    }

    @Test
    fun `import utilise - aucun diagnostic (sous-rapport)`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.math.PI\nval x = PI\n")
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `joker - jamais signale`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.io.*\nval x = 1\n")
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `imports conflictuels - signale sur le plus tardif avec les deux noms`() {
        val session = sessionNeuve()
        session.ouvrirDocument(
            "file:///a.kt",
            "import java.lang.Math.abs\nimport kotlin.math.abs\n",
        )
        val conflits =
            session.diagnostics("file:///a.kt")
                .filter { it.code == CodesDiagnosticsKotlin.IMPORT_CONFLIT }
        assertEquals(1, conflits.size)
        val conflit = conflits.single()
        assertEquals(SeveriteKotlin.ERREUR, conflit.severite)
        assertTrue(conflit.message.contains("java.lang.Math.abs"))
        assertTrue(conflit.message.contains("kotlin.math.abs"))
        // Signalé sur la directive TARDIVE (offset = fin de la première ligne).
        assertEquals("import java.lang.Math.abs".length + 1, conflit.debut)
        session.close()
    }

    @Test
    fun `meme import repete - pas un conflit (sous-rapport)`() {
        val session = sessionNeuve()
        session.ouvrirDocument(
            "file:///a.kt",
            "import kotlin.math.PI\nimport kotlin.math.PI\nval x = PI\n",
        )
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `alias inutilise - signale`() {
        val session = sessionNeuve()
        session.ouvrirDocument(
            "file:///a.kt",
            "import kotlin.math.PI as TAU\nval x = 1\n",
        )
        val diagnostics = session.diagnostics("file:///a.kt")
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsKotlin.IMPORT_INUTILISE, diagnostics.single().code)
        assertTrue(diagnostics.single().message.contains("TAU"))
        session.close()
    }

    @Test
    fun `alias utilise - aucun diagnostic`() {
        val session = sessionNeuve()
        session.ouvrirDocument(
            "file:///a.kt",
            "import kotlin.math.PI as TAU\nval x = TAU\n",
        )
        assertTrue(session.diagnostics("file:///a.kt").isEmpty())
        session.close()
    }

    @Test
    fun `script kts - parse et diagnostics en mode script`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///build.kts", "println(\"x\")\n", script = true)
        assertTrue(session.diagnostics("file:///build.kts").isEmpty())
        session.modifierDocument("file:///build.kts", "val = ", script = true)
        assertTrue(session.diagnostics("file:///build.kts").any { it.code == CodesDiagnosticsKotlin.SYNTAXE })
        session.close()
    }

    @Test
    fun `didChange sans didOpen - tolerant, vaut une ouverture`() {
        val session = sessionNeuve()
        session.modifierDocument("file:///a.kt", "val x = (")
        assertEquals(1, session.diagnostics("file:///a.kt").size)
        assertEquals(1, session.nombreDocuments)
        session.close()
    }

    @Test
    fun `fermeture - tout abandonne, mutations posterieures no-ops`() {
        // LSP : des notifications peuvent encore arriver entre shutdown et
        // exit — la session tolère SANS retenir quoi que ce soit (fuite 0).
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "val x = (")
        session.close()
        session.close()
        assertEquals(0, session.nombreDocuments)
        session.ouvrirDocument("file:///b.kt", "val x = (")
        assertEquals(0, session.nombreDocuments)
        assertTrue(session.diagnostics("file:///b.kt").isEmpty())
    }

    @Test
    fun `sessions distinctes - etats independants`() {
        // Une session par CONNEXION : deux sessions ne partagent RIEN
        // (documents, fermeture) — le moteur est juste l'usine.
        val premiere = sessionNeuve()
        val seconde = sessionNeuve()
        premiere.ouvrirDocument("file:///a.kt", "val x = (")
        assertEquals(0, seconde.nombreDocuments)
        assertTrue(seconde.diagnostics("file:///a.kt").isEmpty())
        seconde.close()
        assertEquals(1, premiere.nombreDocuments)
        assertEquals(1, premiere.diagnostics("file:///a.kt").size)
        premiere.close()
    }

    // ── KP15 — métadonnées riches (plan de stabilisation §3.2 S0/S1) ────

    @Test
    fun `import inutilise - porte le tag INUTILE (KP15)`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.math.PI\nval x = 1\n")
        val diagnostic = session.diagnostics("file:///a.kt").single()
        assertEquals(listOf(TagDiagnosticKotlin.INUTILE), diagnostic.tags)
        session.close()
    }

    @Test
    fun `erreur de syntaxe - aucun tag (KP15, sous-rapport)`() {
        // Le tag INUTILE est réservé aux « inutiles » : une erreur de
        // syntaxe ne le porte JAMAIS (le client griserait à tort).
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "val x = (")
        val diagnostic = session.diagnostics("file:///a.kt").single()
        assertEquals(CodesDiagnosticsKotlin.SYNTAXE, diagnostic.code)
        assertTrue(diagnostic.tags.isEmpty())
        assertTrue(diagnostic.informationsLiees.isEmpty())
        session.close()
    }

    @Test
    fun `imports conflictuels - information liee sur la PREMIERE directive (KP15)`() {
        val session = sessionNeuve()
        session.ouvrirDocument(
            "file:///a.kt",
            "import java.lang.Math.abs\nimport kotlin.math.abs\n",
        )
        val conflit = session.diagnostics("file:///a.kt")
            .single { it.code == CodesDiagnosticsKotlin.IMPORT_CONFLIT }
        val liee = conflit.informationsLiees.single()
        // La plage liée est celle de la directive PREMIÈRE (ligne 1).
        assertEquals(0, liee.debut)
        assertEquals("import java.lang.Math.abs".length, liee.fin)
        assertTrue(liee.message.contains("java.lang.Math.abs"))
        session.close()
    }

    @Test
    fun `import inutilise - aucune information liee (KP15)`() {
        val session = sessionNeuve()
        session.ouvrirDocument("file:///a.kt", "import kotlin.math.PI\nval x = 1\n")
        val diagnostic = session.diagnostics("file:///a.kt").single()
        assertTrue(diagnostic.informationsLiees.isEmpty())
        session.close()
    }
}
