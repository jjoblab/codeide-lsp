package jo.codeide.lsp.host

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests du serveur LSP XML (XE1) — VRAI flux LSP4J comme
 * KotlinLanguageServerTest côté Kotlin : serveur + client espion
 * branchés par connect(), didOpen/didChange appelés en direct, les
 * publishDiagnostics collectés. Le moteur est RÉEL (l'implémentation
 * maison du classpath de test) — seuls les tests de CYCLE DE VIE de la
 * façade utilisent un moteur factice injecté.
 */
class XmlLanguageServerTest {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Façade + serveur RÉEL branché à l'espion (moteur réel). */
    private class Espace(
        public val facade: SessionLspXml,
        public val serveur: XmlLanguageServer,
        public val espion: EspionClient,
    ) {
        public fun fermer() = facade.close()
    }

    /** Ouvre une façade + serveur réel, branché à un client espion. */
    private fun espaceAvecEspion(): Espace {
        val facade = SessionLspXml(dossierTemp.resolve("projet-${System.nanoTime()}"))
        val espion = EspionClient()
        val serveur = facade.serveur() as XmlLanguageServer
        serveur.connect(espion)
        return Espace(facade, serveur, espion)
    }

    @Test
    fun `initialize - capacites honnetes et serverInfo`() {
        val espace = espaceAvecEspion()
        val resultat = espace.serveur.initialize(InitializeParams()).join()
        val capacites = resultat.capabilities
        // XE2 : complétion déclarée (balises/attributs) ; XE3 : survol et
        // définition — RIEN d'autre.
        assertEquals(org.eclipse.lsp4j.TextDocumentSyncKind.Full, capacites.textDocumentSync.left)
        assertTrue(capacites.completionProvider != null, "complétion déclarée à XE2")
        assertEquals(listOf("<", "=", ":"), capacites.completionProvider?.triggerCharacters)
        // lsp4j : les fournisseurs simples voyagent en Either<Boolean, …>.
        assertEquals(true, capacites.hoverProvider.left, "survol déclaré à XE3")
        assertEquals(true, capacites.definitionProvider.left, "définition déclarée à XE3")
        assertTrue(capacites.codeActionProvider == null, "quick-fixes non déclarés avant XE4")
        assertEquals("codeide-xml", resultat.serverInfo.name)
        assertTrue(resultat.serverInfo.version != "inconnue")
        espace.fermer()
    }

    @Test
    fun `didOpen layout - recouvrements et regles traduits en LSP`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        // Layout non fermé + attribut android: sans xmlns : DEUX
        // diagnostics (récupération + règle) publiés en positions LSP.
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///res/layout/main.xml", "xml", 3, "<LinearLayout android:id=\"@+id/x\">"),
            ),
        )
        val publication = espace.espion.recus.single()
        assertEquals("file:///res/layout/main.xml", publication.uri)
        assertEquals(3, publication.version)
        val codes = publication.diagnostics.map { it.code.left }
        assertTrue(codes.contains("xml.tagNonFermee"))
        assertTrue(codes.contains("xml.manqueXmlnsAndroid"))
        val nonFermee = publication.diagnostics.single { it.code.left == "xml.tagNonFermee" }
        assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Error, nonFermee.severity)
        assertEquals("codeide-xml", nonFermee.source)
        // Le nom « LinearLayout » occupe les offsets 1..13 (fin
        // exclusive) → ligne 0.
        assertEquals(1, nonFermee.range.start.character)
        assertEquals(13, nonFermee.range.end.character)
        espace.fermer()
    }

    @Test
    fun `didChange corrige - la publication suivante est vide`() {
        val espace = espaceAvecEspion()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///res/layout/main.xml", "xml", 1, "<LinearLayout>"),
            ),
        )
        espace.serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file:///res/layout/main.xml", 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        "<merge xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\"/>",
                    ),
                ),
            ),
        )
        val derniere = espace.espion.recus.last()
        assertEquals(2, derniere.version)
        assertTrue(derniere.diagnostics.isEmpty(), "un layout sain ne publie rien")
        espace.fermer()
    }

    @Test
    fun `didClose - vidange LSP du document`() {
        val espace = espaceAvecEspion()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///res/values/s.xml",
                    "xml",
                    1,
                    "<resources><string/></resources>",
                ),
            ),
        )
        espace.serveur.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier("file:///res/values/s.xml")),
        )
        val derniere = espace.espion.recus.last()
        assertEquals("file:///res/values/s.xml", derniere.uri)
        assertTrue(derniere.diagnostics.isEmpty(), "les squiggles partent avec le document")
        espace.fermer()
    }

    @Test
    fun `completion — traduite en CompletionItems LSP (kind, detail, sortText)`() {
        val espace = espaceAvecEspion()
        espace.facade.appliquerModele(projetMiniPlateforme(), null)
        val texte = "<But"
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///res/layout/main.xml", "xml", 1, texte)),
        )
        val reponse =
            espace.serveur.textDocumentService
                .completion(
                    org.eclipse.lsp4j.CompletionParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier("file:///res/layout/main.xml"),
                        org.eclipse.lsp4j.Position(0, texte.length),
                    ),
                ).join()
        val items = reponse.right.items
        assertTrue(items.isNotEmpty(), "la complétion balise répond")
        val bouton = items.single { it.label == "Button" }
        assertEquals(org.eclipse.lsp4j.CompletionItemKind.Class, bouton.kind)
        assertEquals("android.widget.Button", bouton.detail)
        // Clé de tri : rang 01 (fréquentes) + longueur 0006 + libellé.
        assertEquals("010006Button", bouton.sortText)
        espace.fermer()
    }

    /**
     * Projet minimal portant une MINI-PLATEFORME (patron
     * SessionLspXmlTest — le moteur y lira son catalogue) ; la racine
     * source est INJECTABLE (hover/définition : une racine avec res/).
     */
    private fun projetMiniPlateforme(racineSources: java.io.File? = null): jo.codeide.lsp.model.ProjetLsp {
        val racine = racineSources ?: dossierTemp.resolve("src").apply { mkdirs() }
        val plateforme = dossierTemp.resolve("platforms/android-test")
        val data = plateforme.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            "Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object\n",
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            "<resources>" +
                "<declare-styleable name=\"TextView\"><attr name=\"text\" format=\"string\"/></declare-styleable>" +
                "</resources>",
        )
        val androidJar = plateforme.resolve("android.jar").apply { writeBytes(byteArrayOf(1)) }
        return jo.codeide.lsp.model.ProjetLsp(
            dossierTemp.path,
            listOf(
                jo.codeide.lsp.model.ModuleLsp(
                    nom = ":app",
                    type = jo.codeide.lsp.model.TypeModuleLsp.JVM,
                    variante = null,
                    sources =
                        jo.codeide.lsp.model.EnsemblesSources(
                            principal = listOf(racine.path),
                            test = emptyList(),
                            generes = emptyList(),
                        ),
                    classpath =
                        jo.codeide.lsp.model
                            .ClasspathInstantane(emptyList(), "instantane"),
                    dependancesModules = emptyList(),
                    androidJar = androidJar.path,
                    ignore = false,
                    raisonIgnore = null,
                    avertissements = emptyList(),
                    empreinte = "module-app",
                ),
            ),
            "projet",
        )
    }

    @Test
    fun `hover et definition — reference projet traduite en LSP`() {
        val espace = espaceAvecEspion()
        // Racine source avec une string DÉCLARÉE (l'index y puisera).
        val racine = dossierTemp.resolve("src-res").apply { mkdirs() }
        racine
            .resolve("res/values")
            .apply { mkdirs() }
            .resolve("strings.xml")
            .writeText("<resources><string name=\"hello\">Salut</string></resources>")
        espace.facade.appliquerModele(projetMiniPlateforme(racine), null)
        val texte = "<Button android:text=\"@string/hello\""
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///res/layout/main.xml", "xml", 1, texte)),
        )
        val colonne = texte.indexOf("hello") + 2
        val survol =
            espace.serveur.textDocumentService
                .hover(
                    org.eclipse.lsp4j.HoverParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier("file:///res/layout/main.xml"),
                        org.eclipse.lsp4j.Position(0, colonne),
                    ),
                ).join()
        val contenuAttendu =
            listOf(
                org.eclipse.lsp4j.jsonrpc.messages.Either
                    .forLeft<String, Any>("string « hello » — projet, strings.xml"),
            )
        assertEquals(contenuAttendu, survol?.contents?.left)
        val definition =
            espace.serveur.textDocumentService
                .definition(
                    org.eclipse.lsp4j.DefinitionParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier("file:///res/layout/main.xml"),
                        org.eclipse.lsp4j.Position(0, colonne),
                    ),
                ).join()
        val locations = definition.left
        assertEquals(1, locations.size)
        assertTrue(locations.single().uri.endsWith("strings.xml"))
        espace.fermer()
    }

    @Test
    fun `shutdown puis exit - session refermee une seule fois`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.shutdown().join()
        espace.serveur.exit()
        // didChange TARDIF (entre shutdown et exit) : toléré, sans effet.
        espace.serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file:///a.xml", 2),
                listOf(TextDocumentContentChangeEvent("<a/>")),
            ),
        )
        espace.fermer()
        assertTrue(true, "aucune exception au fil du cycle brutal")
    }

    /** Client espion : collecte les publications de diagnostics. */
    private class EspionClient : LanguageClient {
        public val recus: CopyOnWriteArrayList<PublishDiagnosticsParams> = CopyOnWriteArrayList()

        override fun telemetryEvent(obj: Any?) {
            // Non sollicité.
        }

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            recus.add(params)
        }

        override fun showMessage(params: MessageParams) {
            // Non sollicité.
        }

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(MessageActionItem())

        override fun logMessage(params: MessageParams) {
            // Non sollicité.
        }
    }
}
