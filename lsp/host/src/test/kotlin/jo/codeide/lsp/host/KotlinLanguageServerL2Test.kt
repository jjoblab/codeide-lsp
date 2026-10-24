package jo.codeide.lsp.host

import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests de BOUT EN BOUT des capacités L2 Kotlin (plan 1.3.0) — VRAI
 * serveur LSP4J branché à un client espion (même montage que le reste
 * de la classe) : initialize → didOpen → REQUÊTE par le service de
 * documents (le chemin de production du traducteur LSP) → vérification
 * → didClose. Ces tests alimentent la colonne « preuve E2E » de la
 * matrice des capacités (docs/LSP_MATRICE_CAPACITES.md) — critère 4
 * « testée de bout en bout » de la définition « en service ».
 *
 * Le projet vit sur DISQUE (racine de la façade) : le balayage
 * multi-fichiers (implémentations, symboles d'espace) y lit ses
 * fichiers fermés.
 */
@Suppress("DEPRECATION")
class KotlinLanguageServerL2Test {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    /** Modèle minimal : UN module actif portant [racine] comme source. */
    private fun modeleUneRacine(racine: File): jo.codeide.lsp.model.ProjetLsp =
        jo.codeide.lsp.model.ProjetLsp(
            cheminRacine = racine.parentFile?.absolutePath ?: racine.absolutePath,
            modules =
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
                        androidJar = null,
                        ignore = false,
                        raisonIgnore = null,
                        avertissements = emptyList(),
                        empreinte = "module-app",
                    ),
                ),
            empreinte = "projet",
        )

    /** Façade + serveur RÉEL branché à l'espion (moteur réel). */
    private class Espace(
        public val facade: SessionLspKotlin,
        public val serveur: KotlinLanguageServer,
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
        val facade = SessionLspKotlin(dossierTemp.resolve("projet-${System.nanoTime()}"))
        val espion = EspionClient()
        val serveur = facade.serveur() as KotlinLanguageServer
        serveur.connect(espion)
        return Espace(facade, serveur, espion)
    }

    /**
     * Façade + serveur dont le classpath CONNAÎT [sources] (modèle
     * minimal une-racine, patron du test de surveillance L1) — le
     * balayage multi-fichiers y lit ses fichiers fermés.
     */
    private fun espaceAvecRacines(sources: File): Espace {
        val espace = espaceAvecEspion()
        espace.facade.appliquerModele(modeleUneRacine(sources), null)
        return espace
    }

    /** Ouvre [uri] côté serveur (chemin didOpen de production). */
    private fun ouvrir(
        espace: Espace,
        uri: String,
        texte: String,
    ) {
        espace.serveur.initialize(InitializeParams()).join()
        espace.serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, texte)),
        )
    }

    @Test
    fun `initialize - les sept capacites L2 declarees, jetons semantiques absents`() {
        val espace = espaceAvecEspion()
        val capacites =
            espace.serveur
                .initialize(InitializeParams())
                .join()
                .capabilities
        assertTrue(capacites.documentFormattingProvider.left, "formatage déclaré (L2)")
        assertTrue(capacites.documentRangeFormattingProvider.left, "formatage de plage déclaré (L2)")
        assertTrue(capacites.typeDefinitionProvider.left, "définition de type déclarée (L2)")
        assertTrue(capacites.implementationProvider.left, "implémentations déclarées (L2)")
        assertTrue(capacites.inlayHintProvider != null, "indices en ligne déclarés (L2)")
        assertTrue(capacites.workspaceSymbolProvider.left, "symboles d'espace déclarés (L2)")
        assertTrue(capacites.selectionRangeProvider.left, "plage de sélection déclarée (L2)")
        // Jetons sémantiques : JAMAIS annoncés (distinction non fiable — D3).
        assertEquals(null, capacites.semanticTokensProvider, "jetons sémantiques non déclarés (D3)")
        espace.fermer()
    }

    @Test
    fun `formatting - editions de re-indentation traduites en TextEdits LSP`() {
        val uri = "file:///a.kt"
        val texte = "fun a() {\nval x = 1\n}\n"
        val espace = espaceAvecEspion()
        ouvrir(espace, uri, texte)
        val options = FormattingOptions()
        options.tabSize = 4
        val editions =
            espace.serveur.textDocumentService
                .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), options))
                .join()
        // La ligne du corps gagne 4 espaces (offset 10 → position (1,0)).
        assertEquals(1, editions.size)
        assertEquals(Position(1, 0), editions.single().range.start)
        assertEquals(Position(1, 0), editions.single().range.end)
        assertEquals("    ", editions.single().newText)
        espace.fermer()
    }

    @Test
    fun `rangeFormatting - les editions restent dans la plage demandee`() {
        val uri = "file:///a.kt"
        val texte = "fun un() {\nval a = 1\n}\n\nfun deux() {\nval b = 2\n}\n"
        val espace = espaceAvecEspion()
        ouvrir(espace, uri, texte)
        val options = FormattingOptions()
        options.tabSize = 4
        val params =
            DocumentRangeFormattingParams(
                TextDocumentIdentifier(uri),
                options,
                Range(Position(4, 0), Position(7, 0)),
            )
        val editions =
            espace.serveur.textDocumentService
                .rangeFormatting(params)
                .join()
        // La ligne mal indentée de la PREMIÈRE fonction (hors plage) ne
        // bouge pas ; celle de la plage s'indente (offset → (5,0)).
        assertEquals(1, editions.size)
        assertEquals(Position(5, 0), editions.single().range.start)
        assertEquals("    ", editions.single().newText)
        espace.fermer()
    }

    @Test
    fun `typeDefinition et implementation - navigation de type par le transport reel`() {
        val sources = dossierTemp.resolve("sources-nav").apply { mkdirs() }
        val base = sources.resolve("Base.kt")
        base.writeText("package p\n\nopen class Base\n")
        val heritier = sources.resolve("Heritiers.kt")
        heritier.writeText(
            "package p\n\nimport p.Base\n\nclass Heritee : Base()\n",
        )
        val uri = heritier.toURI().toString()
        val texte = heritier.readText()
        val espace = espaceAvecRacines(sources)
        ouvrir(espace, uri, texte)
        // Curseur sur le supertype ÉCRIT de Heritee (ligne 3).
        val positionType = Position(3, 17)
        val definitions =
            espace.serveur.textDocumentService
                .typeDefinition(TypeDefinitionParams(TextDocumentIdentifier(uri), positionType))
                .join()
        // La déclaration de la classe Base (fichier fermé du scan).
        assertEquals(1, definitions.left.size)
        assertEquals(base.toURI().toString(), definitions.left.single().uri)
        assertEquals(
            Position(2, 11),
            definitions.left
                .single()
                .range.start,
        )
        // Implémentations : le nom de la classe héritière, document courant.
        val implementations =
            espace.serveur.textDocumentService
                .implementation(
                    org.eclipse.lsp4j.ImplementationParams(
                        TextDocumentIdentifier(uri),
                        positionType,
                    ),
                ).join()
        assertEquals(1, implementations.left.size)
        assertEquals(uri, implementations.left.single().uri)
        assertEquals(
            Position(4, 6),
            implementations.left
                .single()
                .range.start,
        )
        espace.fermer()
    }

    @Test
    fun `inlayHint - noms de parametres et types certains rendus par le transport`() {
        val uri = "file:///a.kt"
        val texte =
            "fun somme(a: Int, b: Int): Int = a + b\n" +
                "fun essai(): Int = somme(1, 2)\n" +
                "val entier = 42\n"
        val espace = espaceAvecEspion()
        ouvrir(espace, uri, texte)
        val params = InlayHintParams()
        params.textDocument = TextDocumentIdentifier(uri)
        params.range = Range(Position(0, 0), Position(3, 0))
        val indices =
            espace.serveur.textDocumentService
                .inlayHint(params)
                .join()
        // Deux indices de paramètres (les arguments 1 et 2) + un indice
        // de type (le littéral 42) — séparateurs ajoutés par l'hôte.
        assertEquals(3, indices.size)
        val parametres = indices.filter { it.label.left.endsWith(" =") }
        assertEquals(listOf("a =", "b ="), parametres.map { hint -> hint.label.left })
        assertEquals(
            listOf(Position(1, 25), Position(1, 28)),
            parametres.map { hint -> hint.position },
        )
        val type = indices.single { hint -> hint.label.left.startsWith(":") }
        assertEquals(": Int", type.label.left)
        assertEquals(Position(2, 4), type.position)
        espace.fermer()
    }

    @Test
    fun `selectionRange - chaine des nœuds englobants par le transport`() {
        val uri = "file:///a.kt"
        val texte = "fun essai() {\n    val x = 1\n}\n"
        val espace = espaceAvecEspion()
        ouvrir(espace, uri, texte)
        val params = SelectionRangeParams()
        params.textDocument = TextDocumentIdentifier(uri)
        params.positions = listOf(Position(1, 12))
        val chaines =
            espace.serveur.textDocumentService
                .selectionRange(params)
                .join()
        assertEquals(1, chaines.size)
        // Dernier maillon : le FICHIER ENTIER (0,0)→(3,0).
        var maillon = chaines.single()
        while (maillon.parent != null) maillon = maillon.parent
        assertEquals(Position(0, 0), maillon.range.start)
        assertEquals(Position(3, 0), maillon.range.end)
        // Le maillon le plus profond couvre le littéral (le `1` en (1,12)).
        assertEquals(Position(1, 12), chaines.single().range.start)
        espace.fermer()
    }

    @Test
    fun `superDefinition - go to super par le transport, miroir du LSP Java (ADR 0210)`() {
        val sources = dossierTemp.resolve("sources-super").apply { mkdirs() }
        val baseTexte = "package p\n\nopen class Base {\n    open fun run() {\n    }\n}\n"
        val base = sources.resolve("Base.kt")
        base.writeText(baseTexte)
        val heritierTexte =
            "package p\n\nimport p.Base\n\nclass Heritee : Base() {\n    override fun run() {\n    }\n}\n"
        val heritier = sources.resolve("Heritiers.kt")
        heritier.writeText(heritierTexte)
        val uri = heritier.toURI().toString()
        val espace = espaceAvecRacines(sources)
        ouvrir(espace, uri, heritierTexte)
        // La capability experimental annonce le provider (pattern
        // jdt.ls — le client y cherche la clé « superDefinitionProvider »).
        val capacites =
            espace.serveur
                .initialize(InitializeParams())
                .join()
                .capabilities
        val experimental = capacites.experimental as? Map<*, *>
        assertTrue(
            experimental?.get("superDefinitionProvider") == true,
            "superDefinitionProvider annoncé dans experimental",
        )
        // Caret sur « run » de la déclaration OUTREPASSANTE (ligne 5,
        // « override fun run() » — colonne du nom).
        val position = Position(5, 17)
        val cibles =
            espace.serveur.textDocumentService
                .superDefinition(TextDocumentPositionParams(TextDocumentIdentifier(uri), position))
                .join()
        // UNE cible : run() de Base — chemin ABSOLU, plage du nom en
        // OFFSETS (aucun round-trip Position↔offset), libellé picker.
        assertEquals(1, cibles.size)
        val cible = cibles.single()
        assertEquals(base.absolutePath, cible.path, "chemin absolu du fichier déclarant")
        assertEquals(baseTexte.indexOf("run()"), cible.start, "offset du NOM run dans Base.kt")
        assertEquals(baseTexte.indexOf("run()") + "run".length, cible.end, "fin du nom")
        assertEquals("run  ·  Base", cible.label, "libellé picker (miroir ecj)")
        espace.fermer()
    }

    @Test
    fun `workspace symbol - palette du projet par le transport, fichiers fermes inclus`() {
        val sources = dossierTemp.resolve("sources-espace").apply { mkdirs() }
        val ferme = sources.resolve("Ferme.kt")
        ferme.writeText("package p\n\nclass ServiceFerme\n")
        val uri = "file:///Ouvre.kt"
        val texte = "class ClientOuvert\n"
        val espace = espaceAvecRacines(sources)
        ouvrir(espace, uri, texte)
        val symboles =
            espace.serveur.workspaceService
                .symbol(WorkspaceSymbolParams("ferme"))
                .join()
                .left
        // Le fichier FERMÉ du scan répond à la requête (contient).
        assertEquals(1, symboles.size)
        assertEquals("ServiceFerme", symboles.single().name)
        assertEquals(ferme.toURI().toString(), symboles.single().location.uri)
        // didClose : le document est oublié — aucune fuite, requête vide honnête.
        espace.serveur.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
        )
        val apresFermeture =
            espace.serveur.workspaceService
                .symbol(WorkspaceSymbolParams("ouvert"))
                .join()
                .left
        assertTrue(apresFermeture.isEmpty(), "document fermé absent de la palette")
        espace.fermer()
    }
}
