package jo.codeide.lsp.host

import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentColorParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.LinkedEditingRangeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests de BOUT EN BOUT des capacités L3 XML (plan 1.3.0) — VRAI
 * serveur LSP4J branché à un client espion (même montage que
 * KotlinLanguageServerL2Test) : initialize → didOpen → requête par le
 * service de documents (chemin de production du traducteur LSP) →
 * vérification → didClose. Ces preuves alimentent la colonne « E2E »
 * de la matrice des capacités (critère 4 de l'ADR 0201).
 *
 * Le projet vit sur DISQUE (racine avec `res/` DEDANS, contrat de
 * l'index des ressources) : les déclarants `values` y sont lus.
 */
@Suppress("DEPRECATION")
class XmlLanguageServerL3Test {
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

    /** Client espion minimal (même contrat que l'espion des autres suites). */
    private class EspionClient : org.eclipse.lsp4j.services.LanguageClient {
        public val recus: MutableList<org.eclipse.lsp4j.PublishDiagnosticsParams> =
            java.util.concurrent.CopyOnWriteArrayList()

        override fun telemetryEvent(objet: Any?) {
            // Ignoré : la télémétrie ne concerne pas l'espion.
        }

        override fun publishDiagnostics(diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams) {
            recus.add(diagnostics)
        }

        override fun showMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }

        override fun showMessageRequest(
            params: org.eclipse.lsp4j.ShowMessageRequestParams,
        ): java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem> =
            java.util.concurrent.CompletableFuture
                .completedFuture(null)

        override fun logMessage(params: org.eclipse.lsp4j.MessageParams) {
            // Ignoré : l'espion ne collecte que publishDiagnostics.
        }
    }

    /** Ouvre une façade + serveur réel, branché à un client espion. */
    private fun espaceAvecEspion(): Espace {
        val facade = SessionLspXml(dossierTemp.resolve("projet-${System.nanoTime()}"))
        val espion = EspionClient()
        val serveur = facade.serveur() as XmlLanguageServer
        serveur.connect(espion)
        return Espace(facade, serveur, espion)
    }

    /**
     * Projet minimal portant une MINI-PLATEFORME (patron
     * XmlLanguageServerTest) ; la racine source est INJECTÉE (l'index
     * des ressources y lit ses déclarants `values`).
     */
    private fun projetMiniPlateforme(racineSources: File): jo.codeide.lsp.model.ProjetLsp {
        val plateforme = dossierTemp.resolve("platforms/android-${System.nanoTime()}")
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
                            principal = listOf(racineSources.path),
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

    /** Position (ligne, colonne) du PREMIER caractère de [marqueur]. */
    private fun positionDe(
        texte: String,
        marqueur: String,
    ): Position {
        val decalage = texte.indexOf(marqueur)
        var ligne = 0
        var colonne = 0
        for (indice in 0 until decalage) {
            if (texte[indice] == '\n') {
                ligne += 1
                colonne = 0
            } else {
                colonne += 1
            }
        }
        return Position(ligne, colonne)
    }

    @Test
    fun `initialize - les six capacites L3 declarees`() {
        val espace = espaceAvecEspion()
        val capacites =
            espace.serveur
                .initialize(InitializeParams())
                .join()
                .capabilities
        assertTrue(capacites.referencesProvider.left, "références déclarées (L3)")
        assertTrue(capacites.documentHighlightProvider.left, "surlignage déclaré (L3)")
        assertTrue(capacites.documentRangeFormattingProvider.left, "formatage de plage déclaré (L3)")
        assertTrue(capacites.linkedEditingRangeProvider.left, "édition liée déclarée (L3)")
        assertTrue(capacites.selectionRangeProvider.left, "plage de sélection déclarée (L3)")
        assertTrue(capacites.colorProvider.left, "couleurs de document déclarées (L3)")
        espace.fermer()
    }

    @Test
    fun `references et documentHighlight - par le transport reel, declarant du disque`() {
        // Projet : le déclarant values vit sur disque (l'index le prouve).
        val racine = dossierTemp.resolve("src-${System.nanoTime()}")
        File(racine, "res/values/strings.xml").apply {
            parentFile?.mkdirs()
            writeText("<resources><string name=\"app_nom\">CodeIDE</string></resources>")
        }
        val espace = espaceAvecEspion()
        // Ordre du test « hover et definition » (qui marche) : le
        // modèle AVANT initialize, la façade pousse le classpath.
        espace.facade.appliquerModele(projetMiniPlateforme(racine), null)
        espace.serveur.initialize(InitializeParams()).join()
        val uri = "file:///res/layout/main.xml"
        val texte = "<linear><bouton texte=\"@string/app_nom\"/></linear>"
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )
        // Références : la déclaration (disque) + l'usage du document ouvert.
        val params = ReferenceParams()
        params.textDocument = TextDocumentIdentifier(uri)
        params.position =
            positionDe(texte, "app_nom").let { position -> Position(position.line, position.character + 2) }
        params.context = ReferenceContext(true)
        val references =
            espace.serveur.textDocumentService
                .references(params)
                .join()
        val surlignageSonde = DocumentHighlightParams()
        surlignageSonde.textDocument = TextDocumentIdentifier(uri)
        surlignageSonde.position = params.position

        assertEquals(2, references.size)
        assertTrue(references.any { it.uri.endsWith("strings.xml") })
        assertEquals(1, references.count { it.uri == uri })
        // Surlignage du document courant : l'usage (lecture).
        val surlignage = DocumentHighlightParams()
        surlignage.textDocument = TextDocumentIdentifier(uri)
        surlignage.position = positionDe(texte, "app_nom")
        val surlignages =
            espace.serveur.textDocumentService
                .documentHighlight(surlignage)
                .join()
        assertEquals(1, surlignages.size)
        assertEquals(positionDe(texte, "app_nom"), surlignages.single().range.start)
        espace.serveur.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
        )
        espace.fermer()
    }

    @Test
    fun `rangeFormatting - editions dans la plage uniquement`() {
        val uri = "fixture://x.xml"
        val texte = "<a>\n<b/>\n<c/>\n</a>\n"
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )
        val options = FormattingOptions()
        options.tabSize = 4
        val params =
            DocumentRangeFormattingParams(
                TextDocumentIdentifier(uri),
                options,
                // La plage couvre les lignes 1 et 2 (<b/> et <c/>).
                Range(Position(1, 0), Position(2, 3)),
            )
        val editions =
            espace.serveur.textDocumentService
                .rangeFormatting(params)
                .join()
        // Les DEUX lignes de la plage s'indentent ; </a> (ligne 3, hors
        // plage) JAMAIS.
        assertEquals(listOf(Position(1, 0), Position(2, 0)), editions.map { it.range.start })
        assertTrue(editions.all { it.newText == "    " })
        espace.fermer()
    }

    @Test
    fun `linkedEditingRange - ouvrante et fermante, hors balise null`() {
        val uri = "fixture://x.xml"
        val texte = "<linear><bouton/></linear>"
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )
        val params = LinkedEditingRangeParams(TextDocumentIdentifier(uri), positionDe(texte, "linear"))
        val plages =
            espace.serveur.textDocumentService
                .linkedEditingRange(params)
                .join()
        assertNotNull(plages)
        assertEquals(2, plages.ranges.size)
        assertEquals(positionDe(texte, "linear"), plages.ranges[0].start)
        val attendueFermante = positionDe(texte, "</linear>")
        attendueFermante.character = attendueFermante.character + 2
        assertEquals(attendueFermante, plages.ranges[1].start)
        // L'auto-fermante : aucune liaison.
        val sansLiaison =
            LinkedEditingRangeParams(TextDocumentIdentifier(uri), positionDe(texte, "bouton"))
        assertEquals(
            null,
            espace.serveur.textDocumentService
                .linkedEditingRange(sansLiaison)
                .join(),
        )
        espace.fermer()
    }

    @Test
    fun `selectionRange - chaine profonde jusqu au fichier`() {
        val uri = "fixture://x.xml"
        val texte = "<linear><bouton texte=\"Ok\"/></linear>"
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )
        val params = SelectionRangeParams()
        params.textDocument = TextDocumentIdentifier(uri)
        params.positions = listOf(positionDe(texte, "texte"))
        val chaines =
            espace.serveur.textDocumentService
                .selectionRange(params)
                .join()
        assertEquals(1, chaines.size)
        var maillon = chaines.single()
        while (maillon.parent != null) maillon = maillon.parent
        assertEquals(Position(0, 0), maillon.range.start)
        assertEquals(Position(0, texte.length), maillon.range.end)
        assertEquals(positionDe(texte, "texte"), chaines.single().range.start)
        espace.fermer()
    }

    @Test
    fun `documentColor - litteral et reference resolue par le transport`() {
        val racine = dossierTemp.resolve("src-${System.nanoTime()}")
        File(racine, "res/values/colors.xml").apply {
            parentFile?.mkdirs()
            writeText("<resources><color name=\"principal\">#00FF00</color></resources>")
        }
        val espace = espaceAvecEspion()
        espace.serveur.initialize(InitializeParams()).join()
        espace.facade.appliquerModele(projetMiniPlateforme(racine), null)
        val uri = "fixture://x.xml"
        val texte = "<a fond=\"#FF0000\" autre=\"@color/principal\" />"
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )
        val params = DocumentColorParams()
        params.textDocument = TextDocumentIdentifier(uri)
        val couleurs =
            espace.serveur.textDocumentService
                .documentColor(params)
                .join()
        // Le littéral + la référence RÉSOLUE (le déclarant du disque).
        assertEquals(2, couleurs.size)
        assertTrue(couleurs.any { it.color.red == 1.0 && it.color.green == 0.0 })
        assertTrue(couleurs.any { it.color.green == 1.0 && it.color.red == 0.0 })
        espace.fermer()
    }
}
