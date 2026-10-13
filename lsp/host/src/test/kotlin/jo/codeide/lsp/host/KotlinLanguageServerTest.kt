package jo.codeide.lsp.host

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests du serveur LSP Kotlin (K2) — VRAI flux LSP4J comme
 * DatabindingImportDowngradeTest côté Java : serveur + client espion
 * branchés par connect(), didOpen/didChange appelés en direct, les
 * publishDiagnostics collectés. Le moteur est RÉEL (l'implémentation
 * vendorée du classpath de test) — seuls les tests de CYCLE DE VIE de la
 * façade utilisent un moteur factice injecté.
 */
class KotlinLanguageServerTest {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Façade + serveur RÉEL branché à l'espion (moteur réel). */
    private class Espace(
        public val facade: SessionLspKotlin,
        public val serveur: KotlinLanguageServer,
        public val espion: EspionClient,
    ) {
        public fun fermer() = facade.close()
    }

    /** Ouvre une façade + serveur réel, branché à un client espion. */
    private fun espaceAvecEspion(): Espace {
        val facade = SessionLspKotlin(dossierTemp.resolve("projet-${System.nanoTime()}"))
        val espion = EspionClient()
        val serveur = facade.serveur() as KotlinLanguageServer
        serveur.connect(espion)
        return Espace(facade, serveur, espion)
    }

    @Test
    fun `didOpen invalide - publication traduite en positions LSP`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 3, "val x = (\n")),
        )
        assertEquals(1, espace.espion.recus.size)
        val publication = espace.espion.recus.single()
        assertEquals("file:///a.kt", publication.uri)
        assertEquals(3, publication.version)
        assertEquals(1, publication.diagnostics.size)
        val diagnostic = publication.diagnostics.single()
        assertEquals("kt.syntax", diagnostic.code.left)
        assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals("codeide-kotlin", diagnostic.source)
        // Offsets moteur 9..9 (vide, fin de texte) → élargi d'un caractère :
        // le caractère suivant est le saut de ligne → fin de ligne 0 = (1,0).
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(9, diagnostic.range.start.character)
        assertEquals(1, diagnostic.range.end.line)
        assertEquals(0, diagnostic.range.end.character)
        espace.fermer()
    }

    @Test
    fun `didChange corrige - la publication suivante est vide`() {
        val espace = espaceAvecEspion()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = (")),
        )
        espace.serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file:///a.kt", 2),
                listOf(TextDocumentContentChangeEvent("val x = (1)\n")),
            ),
        )
        val derniere = espace.espion.recus.last()
        assertEquals(2, derniere.version)
        assertTrue(derniere.diagnostics.isEmpty())
        espace.fermer()
    }

    @Test
    fun `import inutilise - gravite Warning et code kt`() {
        val espace = espaceAvecEspion()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///a.kt",
                    "kotlin",
                    1,
                    "import kotlin.math.PI\nval x = 1\n",
                ),
            ),
        )
        val diagnostic =
            espace.espion.recus
                .single()
                .diagnostics
                .single()
        assertEquals("kt.unusedImport", diagnostic.code.left)
        assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Warning, diagnostic.severity)
        // Plage = la directive d'import (offsets 0..21 → 0:0..0:21).
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(0, diagnostic.range.end.line)
        assertEquals(21, diagnostic.range.end.character)
        espace.fermer()
    }

    @Test
    fun `didClose - vidange LSP du document`() {
        val espace = espaceAvecEspion()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = (")),
        )
        espace.serveur.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier("file:///a.kt")),
        )
        assertEquals(
            "file:///a.kt",
            espace.espion.recus
                .last()
                .uri,
        )
        assertTrue(
            espace.espion.recus
                .last()
                .diagnostics
                .isEmpty(),
        )
        espace.fermer()
    }

    @Test
    fun `shutdown - la session moteur est refermee et decomptee`() {
        // Moteur FACTICE (cycle de vie isolé) : la façade ne doit retenir
        // QUE les sessions des connexions vivantes.
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("vie"), moteur)
        val serveur = facade.serveur()
        serveur.shutdown().join()
        assertEquals(1, moteur.sessionsFermees)
        // La façade ne referme PAS deux fois une session déjà arrêtée.
        facade.close()
        assertEquals(1, moteur.sessionsFermees)
    }

    @Test
    fun `close rattrape les connexions jamais arretees`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("rattrapage"), moteur)
        facade.serveur()
        facade.serveur()
        facade.close()
        assertEquals(2, moteur.sessionsFermees)
        // Idempotente : un second close ne referme rien de plus.
        facade.close()
        assertEquals(2, moteur.sessionsFermees)
    }

    @Test
    fun `serveur apres close - erreur explicite`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("apres-close"), MoteurFactice())
        facade.close()
        assertThrows(IllegalStateException::class.java) { facade.serveur() }
    }

    @Test
    fun `definitions - deux extensions routees, transport in-process`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("definitions"))
        val definitions = facade.definitions()
        assertEquals(2, definitions.size)
        val extensions = definitions.map { it.ext }
        assertTrue(extensions.contains("kt"))
        assertTrue(extensions.contains("kts"))
        facade.close()
    }

    @Test
    fun `initialisation - capacites honnetes (sync complete seule)`() {
        val espace = espaceAvecEspion()
        val resultat = espace.serveur.initialize(InitializeParams()).join()
        assertEquals(
            org.eclipse.lsp4j.TextDocumentSyncKind.Full,
            resultat.capabilities.textDocumentSync.left,
        )
        assertEquals("codeide-kotlin", resultat.serverInfo.name)
        espace.fermer()
    }

    // ── K3 : complétion, survol, définition, poussée de classpath ────

    @Test
    fun `complétion membre - traduction LSP (kinds, libellés, tri)`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        val source =
            "class Vue {\n" +
                "  val titre: String = \"a\"\n" +
                "  fun demo() {\n" +
                "    val v = Vue()\n" +
                "    v.\n" +
                "  }\n" +
                "}\n"
        val uri = "file:///vue.kt"
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )
        val reponse =
            espace.serveur.textDocumentService
                .completion(CompletionParams(TextDocumentIdentifier(uri), Position(4, 6)))
                .join()
        val items = reponse.right.items
        assertTrue(items.isNotEmpty(), "aucune proposition")
        assertTrue(items.any { it.label == "titre" && it.kind == CompletionItemKind.Field })
        assertTrue(items.any { it.label == "demo()" && it.kind == CompletionItemKind.Method })
        // Chaque item porte sa clé de tri totale (le client trie par
        // sortText seul — motif du LSP Java : pertinence décroissante
        // codée sur 4, longueur sur 3, puis libellé).
        assertTrue(items.all { proposition -> proposition.sortText.orEmpty().length >= 8 })
        espace.fermer()
    }

    @Test
    fun `survol - contenu texte simple (même forme que le LSP Java)`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        val source = "fun demo() {\n  val titre: String = \"a\"\n}\n"
        val uri = "file:///survol.kt"
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )
        // « titre » ligne 1, colonne 6.
        val survol =
            espace.serveur.textDocumentService
                .hover(HoverParams(TextDocumentIdentifier(uri), Position(1, 7)))
                .join()
        assertEquals(
            "val titre: String",
            survol
                ?.contents
                ?.left
                ?.single()
                ?.left,
        )
        espace.fermer()
    }

    @Test
    fun `définition - Location LSP vers la plage du nom déclaré`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        val source = "class Vue {\n  fun demo() {\n    val v = Vue()\n  }\n}\n"
        val uri = "file:///def.kt"
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, source)),
        )
        // « Vue() » ligne 2, colonne 12 → définition du nom de classe.
        val reponse =
            espace.serveur.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier(uri), Position(2, 12)))
                .join()
        val locations = reponse.left
        assertEquals(1, locations.size)
        val location = locations.single()
        assertEquals(uri, location.uri)
        assertEquals(0, location.range.start.line)
        assertEquals(6, location.range.start.character)
        assertEquals(0, location.range.end.line)
        assertEquals(9, location.range.end.character)
        espace.fermer()
    }

    @Test
    fun `capacités K3 - complétion, survol et définition déclarés (honnêtes)`() {
        val espace = espaceAvecEspion()
        val resultat = espace.serveur.initialize(InitializeParams()).join()
        assertEquals(
            ".",
            resultat.capabilities.completionProvider.triggerCharacters
                .single(),
        )
        assertEquals(true, resultat.capabilities.hoverProvider.left)
        assertEquals(true, resultat.capabilities.definitionProvider.left)
        espace.fermer()
    }

    @Test
    fun `appliquerModele - pousse le classpath aux sessions vivantes ET futures`() {
        val moteur = MoteurFactice()
        val facade =
            SessionLspKotlin(
                dossierTemp.resolve("push"),
                moteur,
                dossierIndex = null,
            )
        facade.serveur() // session vivante, non refermée
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        val module =
            ModuleLsp(
                nom = ":app",
                type = TypeModuleLsp.JVM,
                variante = null,
                sources = EnsemblesSources(principal = listOf(racine.path), test = emptyList(), generes = emptyList()),
                classpath = ClasspathInstantane(emptyList(), "empreinte-vide"),
                dependancesModules = emptyList(),
                androidJar = null,
                ignore = false,
                raisonIgnore = null,
                avertissements = emptyList(),
                empreinte = "module",
            )
        facade.appliquerModele(ProjetLsp(dossierTemp.resolve("push").path, listOf(module), "projet"), null)
        // Session VIVANTE nourrie.
        assertEquals(
            1,
            moteur.sessions
                .single()
                .classpathsRecus.size,
        )
        assertEquals(
            listOf(racine),
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
                .racinesSources,
        )
        // Session FUTURE née nourrie.
        facade.serveur()
        assertEquals(2, moteur.sessions.size)
        assertEquals(1, moteur.sessions[1].classpathsRecus.size)
        facade.close()
    }

    /** Client LSP espion : collecte les publishDiagnostics, ignore le reste. */
    private class EspionClient : LanguageClient {
        public val recus: MutableList<PublishDiagnosticsParams> = CopyOnWriteArrayList()

        override fun telemetryEvent(objet: Any) {
            // Ignoré : la télémétrie ne concerne pas l'espion.
        }

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            recus.add(diagnostics)
        }

        override fun showMessage(params: MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(params: MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }
    }

    /** Moteur factice : sessions factices comptant les fermetures. */
    private class MoteurFactice : MoteurLangageKotlin {
        public val sessionsFermees: Int
            get() = sessions.count { it.fermee }

        public val sessions: CopyOnWriteArrayList<SessionFactice> = CopyOnWriteArrayList()

        override val identifiant: String = "moteur-factice"

        override val version: String = "0.0.0-test"

        override val versionMinimaleApp: String = "0.0.0"

        override val versionGrammaire: String = "test"

        override fun analyserSource(
            texte: CharSequence,
            script: Boolean,
        ): jo.lspkotlin.parse.ArbreSyntaxiqueKotlin = ServiceMoteurLangageKotlin.unique().analyserSource(texte, script)

        override fun creerSession(): SessionLangageKotlin = SessionFactice().also { sessions.add(it) }
    }

    /** Session factice : journalise, ne lève jamais. */
    private class SessionFactice : SessionLangageKotlin {
        public var fermee: Boolean = false

        /** Classpaths reçus via definirClasspath (test de poussée K3). */
        public val classpathsRecus: CopyOnWriteArrayList<ClasspathKotlin> = CopyOnWriteArrayList()

        override fun definirClasspath(classpath: ClasspathKotlin) {
            classpathsRecus.add(classpath)
        }

        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun fermerDocument(uri: String) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticKotlin> =
            listOf(
                DiagnosticKotlin(
                    code = "kt.test",
                    severite = SeveriteKotlin.ERREUR,
                    message = "factice",
                    debut = 0,
                    fin = 1,
                ),
            )

        override fun close() {
            fermee = true
        }
    }
}
