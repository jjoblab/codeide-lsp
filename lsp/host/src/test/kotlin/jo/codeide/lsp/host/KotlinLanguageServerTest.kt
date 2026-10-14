package jo.codeide.lsp.host

import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
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
@Suppress("LargeClass")
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

    // ── Cycle, tolérances et traductions K3 (chemins de repli) ───────

    @Test
    fun `initialized et services d'espace - acceptes sans effet`() {
        val session = SessionScriptee()
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.initialized(InitializedParams())
        // Service d'espace exposé, notifications acceptées sans effet.
        val espace = serveur.workspaceService
        espace.didChangeConfiguration(DidChangeConfigurationParams())
        espace.didChangeWatchedFiles(DidChangeWatchedFilesParams())
        org.junit.jupiter.api.Assertions
            .assertNotNull(espace)
        session.close()
    }

    @Test
    fun `exit sans shutdown - la session est refermee quand meme`() {
        val session = SessionScriptee()
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.exit()
        assertTrue(session.fermee, "exit referme la session (client brutal)")
    }

    @Test
    fun `didChange par plage - applique au texte courant (client non conforme)`() {
        val session = SessionScriptee()
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "abcdef")),
        )
        serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file:///a.kt", 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(0, 2), Position(0, 4)),
                        "XY",
                    ),
                ),
            ),
        )
        assertEquals("abXYef", session.dernierTexte)
        session.close()
    }

    @Test
    fun `requetes en echec - resultats vides honnetes, jamais d'exception`() {
        val session =
            SessionScriptee(
                leverSurCompletion = true,
                leverSurSurvol = true,
                leverSurDefinitions = true,
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = 1")),
        )
        val completion =
            serveur.textDocumentService
                .completion(CompletionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 4)))
                .join()
        assertTrue(completion.right.items.isEmpty())
        val survol =
            serveur.textDocumentService
                .hover(HoverParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 4)))
                .join()
        assertEquals(null, survol)
        val definitions =
            serveur.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 4)))
                .join()
        assertTrue(definitions.left.isEmpty())
        session.close()
    }

    @Test
    fun `definition document inconnu - liste vide typée`() {
        val session = SessionScriptee()
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        val definitions =
            serveur.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier("file:///jamais-ouvert.kt"), Position(0, 0)))
                .join()
        assertTrue(definitions.left.isEmpty())
        session.close()
    }

    @Test
    fun `documentSymbol - classe avec membres rendue en arbre hiérarchique`() {
        val espace = espaceAvecEspion()
        val texte =
            "class Foo(val a: Int) {\n" +
                "    fun bar(x: Int): String = \"\"\n" +
                "    val baz: Int = 0\n" +
                "}\n"
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, texte)),
        )
        val symboles =
            espace.serveur.textDocumentService
                .documentSymbol(
                    org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier("file:///a.kt")),
                ).join()
        assertEquals(1, symboles.size, "un seul symbole top-level")
        val classe = symboles.single().right
        assertEquals("Foo", classe.name)
        assertEquals(org.eclipse.lsp4j.SymbolKind.Class, classe.kind, "CLASSE → Class")
        // 2 enfants : bar (fonction) + baz (propriété)
        assertEquals(2, classe.children.size, "deux membres déclarés")
        val noms = classe.children.map { it.name }
        assertTrue(noms.contains("bar"), "fonction bar présente")
        assertTrue(noms.contains("baz"), "propriété baz présente")
        val bar = classe.children.first { it.name == "bar" }
        assertEquals(org.eclipse.lsp4j.SymbolKind.Method, bar.kind, "FONCTION → Method")
        assertTrue(bar.detail?.contains("(x: Int): String") == true, "détail = signature")
        val baz = classe.children.first { it.name == "baz" }
        assertEquals(org.eclipse.lsp4j.SymbolKind.Property, baz.kind, "PROPRIETE → Property")
        assertTrue(baz.detail?.contains(": Int") == true, "détail = type")
        espace.fermer()
    }

    @Test
    fun `documentSymbol - objets et interfaces distingues`() {
        val espace = espaceAvecEspion()
        val texte =
            "interface Iface { fun a() }\n" +
                "object Obj { val x = 1 }\n" +
                "typealias Str = String\n"
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, texte)),
        )
        val symboles =
            espace.serveur.textDocumentService
                .documentSymbol(
                    org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier("file:///a.kt")),
                ).join()
        assertEquals(3, symboles.size, "trois symboles top-level")
        val noms = symboles.map { it.right.name }
        assertTrue(noms.contains("Iface"), "interface présente")
        assertTrue(noms.contains("Obj"), "objet présent")
        assertTrue(noms.contains("Str"), "typealias présent")
        val iface = symboles.first { it.right.name == "Iface" }.right
        assertEquals(org.eclipse.lsp4j.SymbolKind.Interface, iface.kind, "INTERFACE → Interface")
        val obj = symboles.first { it.right.name == "Obj" }.right
        assertEquals(org.eclipse.lsp4j.SymbolKind.Object, obj.kind, "OBJET → Object")
        val alias = symboles.first { it.right.name == "Str" }.right
        assertEquals(org.eclipse.lsp4j.SymbolKind.TypeParameter, alias.kind, "ALIAS → TypeParameter")
        // L'interface a 1 enfant (a), l'objet a 1 enfant (x).
        assertEquals(1, iface.children.size, "interface a un membre")
        assertEquals(1, obj.children.size, "objet a un membre")
        espace.fermer()
    }

    @Test
    fun `documentSymbol - document inconnu rend liste vide, sans exception`() {
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        val symboles =
            espace.serveur.textDocumentService
                .documentSymbol(
                    org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier("file:///inconnu.kt")),
                ).join()
        assertTrue(symboles.isEmpty(), "document inconnu → outline vide")
        espace.fermer()
    }

    @Test
    fun `complétion auto-import - edition additionnelle apres le dernier import`() {
        val session =
            SessionScriptee(
                propositions =
                    listOf(
                        PropositionCompletionKotlin(
                            libelle = "Beta",
                            genre = GenreCompletionKotlin.CLASSE,
                            detail = "beta.Beta",
                            texteTri = "05000003Beta",
                            fqnImport = "beta.Beta",
                        ),
                    ),
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        val source = "package app\n\nimport alpha.Alpha\n\nfun main() { Be }\n"
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, source)),
        )
        val reponse =
            serveur.textDocumentService
                .completion(CompletionParams(TextDocumentIdentifier("file:///a.kt"), Position(4, 12)))
                .join()
        val item = reponse.right.items.single()
        val editions = checkNotNull(item.additionalTextEdits) { "l'auto-import doit porter son édition" }
        val edition = editions.single()
        assertEquals("import beta.Beta\n", edition.newText)
        assertEquals(3, edition.range.start.line)
        assertEquals(0, edition.range.start.character)
        assertEquals(3, edition.range.end.line)
        assertEquals(0, edition.range.end.character)
        session.close()
    }

    @Test
    fun `complétion auto-import - sans package ni import, en tete de fichier`() {
        val session =
            SessionScriptee(
                propositions =
                    listOf(
                        PropositionCompletionKotlin(
                            libelle = "TextView",
                            genre = GenreCompletionKotlin.CLASSE,
                            detail = null,
                            texteTri = "05000008TextView",
                            fqnImport = "android.widget.TextView",
                        ),
                    ),
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val v = TextVi")),
        )
        val reponse =
            serveur.textDocumentService
                .completion(CompletionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 14)))
                .join()
        val edition =
            reponse.right.items
                .single()
                .additionalTextEdits
                .orEmpty()
                .single()
        assertEquals("import android.widget.TextView\n", edition.newText)
        assertEquals(0, edition.range.start.line)
        assertEquals(0, edition.range.start.character)
        session.close()
    }

    @Test
    fun `complétion genres - interface, enum, variable, mot-cle et paquet traduits`() {
        val genres =
            listOf(
                Pair(GenreCompletionKotlin.INTERFACE, CompletionItemKind.Interface),
                Pair(GenreCompletionKotlin.ENUM, CompletionItemKind.Enum),
                Pair(GenreCompletionKotlin.VARIABLE, CompletionItemKind.Variable),
                Pair(GenreCompletionKotlin.MOT_CLE, CompletionItemKind.Keyword),
                Pair(GenreCompletionKotlin.PAQUET, CompletionItemKind.Module),
                Pair(GenreCompletionKotlin.ALIAS, CompletionItemKind.Class),
            )
        val session =
            SessionScriptee(
                propositions =
                    genres.mapIndexed { indice, (genre, _) ->
                        PropositionCompletionKotlin(
                            libelle = "c$indice",
                            genre = genre,
                            detail = null,
                            texteTri = "05000002c$indice",
                            fqnImport = null,
                        )
                    },
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = c")),
        )
        val reponse =
            serveur.textDocumentService
                .completion(CompletionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 9)))
                .join()
        val parLibelle = reponse.right.items.associateBy { it.label }
        genres.forEach { (genre, kind) ->
            assertEquals(kind, parLibelle["c${genres.indexOfFirst { it.first == genre }}"]?.kind)
        }
        session.close()
    }

    @Test
    fun `définition - cible d'un AUTRE fichier lue sur disque`() {
        val cible = dossierTemp.resolve("cible-${System.nanoTime()}.kt")
        cible.writeText("class Cible {\n  val nom: String = \"a\"\n}\n")
        val session =
            SessionScriptee(
                emplacements = listOf(EmplacementKotlin(cible.toURI().toString(), 6, 11)),
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val c = Cible()")),
        )
        val reponse =
            serveur.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 10)))
                .join()
        val location = reponse.left.single()
        assertEquals(cible.toURI().toString(), location.uri)
        assertEquals(0, location.range.start.line)
        assertEquals(6, location.range.start.character)
        assertEquals(0, location.range.end.line)
        assertEquals(11, location.range.end.character)
        session.close()
    }

    @Test
    fun `définition - cible illisible, definition omise (jamais fausse)`() {
        val session =
            SessionScriptee(
                emplacements = listOf(EmplacementKotlin("file:///introuvable/x.kt", 0, 1)),
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = Cible()")),
        )
        val reponse =
            serveur.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier("file:///a.kt"), Position(0, 10)))
                .join()
        assertTrue(reponse.left.isEmpty(), "cible illisible : la définition est omise")
        session.close()
    }

    @Test
    fun `publication diagnostics - client fautif tolere (didOpen et vidange)`() {
        val session = SessionScriptee()
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(ClientCassant())
        // didOpen publie (le client lève) puis didClose vidange (idem) :
        // la tolérance du serveur absorbe les DEUX pannes.
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///a.kt", "kotlin", 1, "val x = (")),
        )
        serveur.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier("file:///a.kt")),
        )
        assertTrue(session.fermee.not())
        session.close()
    }

    // ── K4 : quick-fixes d'import (codeAction) ────────────────────────

    @Test
    fun `codeAction - un correctif par FQN candidate, insertion apres le dernier import`() {
        val session =
            SessionScriptee(
                importsCandidats = listOf("android.widget.TextView", "demo.TextView"),
            )
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        val source = "package app\n\nimport alpha.Alpha\n\nfun main() { TextView() }\n"
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///fix.kt", "kotlin", 1, source)),
        )
        // Le client renvoie le diagnostic kt.unresolved dans le contexte.
        val nonResolu =
            org.eclipse.lsp4j.Diagnostic(
                Range(Position(4, 12), Position(4, 21)),
                "Référence non résolue : « TextView »",
                org.eclipse.lsp4j.DiagnosticSeverity.Error,
                "codeide-kotlin",
                "kt.unresolved",
            )
        val params =
            CodeActionParams(
                TextDocumentIdentifier("file:///fix.kt"),
                nonResolu.range,
                CodeActionContext(listOf(nonResolu)),
            )
        val actions = serveur.textDocumentService.codeAction(params).join()
        assertEquals(2, actions.size)
        val premiere = actions.first().right
        assertEquals("Importer « android.widget.TextView »", premiere.title)
        assertEquals(org.eclipse.lsp4j.CodeActionKind.QuickFix, premiere.kind)
        assertEquals(1, premiere.diagnostics.size)
        val editions = checkNotNull(premiere.edit?.changes?.get("file:///fix.kt"))
        val edition = editions.single()
        assertEquals("import android.widget.TextView\n", edition.newText)
        // Après le DERNIER import (ligne 2) → tête de la ligne 3.
        assertEquals(3, edition.range.start.line)
        assertEquals(0, edition.range.start.character)
        // La seconde candidate suit (ordre du classpath conservé).
        assertEquals("Importer « demo.TextView »", actions[1].right.title)
        session.close()
    }

    @Test
    fun `codeAction - sans diagnostic non resolu, aucune action`() {
        val session = SessionScriptee(importsCandidats = listOf("android.widget.TextView"))
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem("file:///rien.kt", "kotlin", 1, "val x = 1\n")),
        )
        val params =
            CodeActionParams(
                TextDocumentIdentifier("file:///rien.kt"),
                Range(Position(0, 0), Position(0, 1)),
                CodeActionContext(emptyList()),
            )
        val actions = serveur.textDocumentService.codeAction(params).join()
        assertTrue(actions.isEmpty())
        session.close()
    }

    @Test
    fun `codeAction - moteur en echec, liste vide honnete`() {
        val session = SessionScriptee(leverSurImports = true)
        val serveur = KotlinLanguageServer(session, "version-test")
        serveur.connect(EspionClient())
        val nonResolu =
            org.eclipse.lsp4j.Diagnostic(
                Range(Position(0, 0), Position(0, 1)),
                "Référence non résolue : « x »",
                org.eclipse.lsp4j.DiagnosticSeverity.Error,
                "codeide-kotlin",
                "kt.unresolved",
            )
        val params =
            CodeActionParams(
                TextDocumentIdentifier("file:///a.kt"),
                nonResolu.range,
                CodeActionContext(listOf(nonResolu)),
            )
        val actions = serveur.textDocumentService.codeAction(params).join()
        assertTrue(actions.isEmpty())
        session.close()
    }

    @Test
    fun `capacites K4 - codeAction declare (honnete)`() {
        val espace = espaceAvecEspion()
        val resultat = espace.serveur.initialize(InitializeParams()).join()
        assertEquals(true, resultat.capabilities.codeActionProvider.left)
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
                ?.right
                ?.value,
        )
        assertEquals(
            org.eclipse.lsp4j.MarkupKind.PLAINTEXT,
            survol
                ?.contents
                ?.right
                ?.kind,
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
            listOf(".", ":"),
            resultat.capabilities.completionProvider.triggerCharacters,
        )
        assertEquals(true, resultat.capabilities.hoverProvider.left)
        assertEquals(true, resultat.capabilities.definitionProvider.left)
        espace.fermer()
    }

    /**
     * Session scriptée : les réponses des requêtes K3/K4 sont INJECTÉES
     * — les tests de TRADUCTION du serveur (kinds, éditions, tolérances)
     * n'ont pas besoin du moteur réel.
     *
     * LongParameterList justifié (règle 8) : chaque paramètre est UNE
     * réponse injectable du contrat de session testée.
     */
    @Suppress("LongParameterList")
    private class SessionScriptee(
        private val propositions: List<PropositionCompletionKotlin> = emptyList(),
        private val importsCandidats: List<String> = emptyList(),
        private val leverSurImports: Boolean = false,
        private val survols: ContenuSurvoleKotlin? = null,
        private val emplacements: List<EmplacementKotlin> = emptyList(),
        private val leverSurCompletion: Boolean = false,
        private val leverSurSurvol: Boolean = false,
        private val leverSurDefinitions: Boolean = false,
    ) : SessionLangageKotlin {
        public var fermee: Boolean = false
            private set

        /** Dernier texte poussé par modifierDocument (assertion par plage). */
        public var dernierTexte: String? = null
            private set

        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            dernierTexte = texte.toString()
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            dernierTexte = texte.toString()
        }

        override fun fermerDocument(uri: String) {
            // Non journalisé : seuls les textes comptent ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticKotlin> =
            listOf(
                DiagnosticKotlin(
                    code = "kt.test",
                    severite = SeveriteKotlin.ERREUR,
                    message = "scripté",
                    debut = 0,
                    fin = 1,
                ),
            )

        override fun completion(
            uri: String,
            decalage: Int,
        ): List<PropositionCompletionKotlin> {
            check(!leverSurCompletion) { "complétion scriptée en échec" }
            return propositions
        }

        override fun importsCandidats(
            uri: String,
            decalage: Int,
        ): List<String> {
            check(!leverSurImports) { "imports scriptés en échec" }
            return importsCandidats
        }

        override fun survol(
            uri: String,
            decalage: Int,
        ): ContenuSurvoleKotlin? {
            check(!leverSurSurvol) { "survol scripté en échec" }
            return survols
        }

        override fun definitions(
            uri: String,
            decalage: Int,
        ): List<EmplacementKotlin> {
            check(!leverSurDefinitions) { "définitions scriptées en échec" }
            return emplacements
        }

        override fun close() {
            fermee = true
        }
    }

    /** Client LSP dont publishDiagnostics LÈVE (panne de transport). */
    private class ClientCassant : LanguageClient {
        override fun telemetryEvent(objet: Any) {
            // Ignoré : seule publishDiagnostics compte ici.
        }

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit =
            throw java.io.IOException("transport cassé (test)")

        override fun showMessage(params: MessageParams) {
            // Ignoré : la panne ne concerne que publishDiagnostics.
        }

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(params: MessageParams) {
            // Ignoré : la panne ne concerne que publishDiagnostics.
        }
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
}
