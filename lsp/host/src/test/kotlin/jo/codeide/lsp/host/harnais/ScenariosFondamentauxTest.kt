package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.annonce
import jo.codeide.lsp.host.harnais.OutilsHarnais.assertPlage
import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeur
import jo.codeide.lsp.host.harnais.OutilsHarnais.modifier
import jo.codeide.lsp.host.harnais.OutilsHarnais.ouvrir
import jo.codeide.lsp.host.harnais.OutilsHarnais.positionDe
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SCÉNARIOS FONDAMENTAUX.
 *
 * Groupe A : cycle de vie LSP et négociation EXACTE des capacités
 * déclarées (la table de 18 entrées de CapacitesLspPariteTest, passée
 * AU FIL DU TRANSPORT réel cette fois — la désérialisation JSON est
 * partie prenante : un Either mal sérialisé, un champ renommé, un
 * déclencheur perdu en route, tout cela se voit ici).
 *
 * Groupe B : synchronisation de document (Full), publication des
 * diagnostics (syntaxiques K2 + sémantiques K4), versions, vidange,
 * tolérances documentées du contrat.
 */
@Timeout(900)
@Suppress("LargeClass", "TooManyFunctions")
class ScenariosFondamentauxTest {
    // ─────────────────────────────────────────────────────────────────
    // GROUPE A — Cycle de vie et négociation
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `A1 - initialize annonce exactement les 18 capacites de la table`() {
        val connexion = demarrerServeur("a1")
        try {
            val params = InitializeParams()
            params.workspaceFolders =
                listOf(org.eclipse.lsp4j.WorkspaceFolder(connexion.racine.toURI().toString(), "harnais"))
            val resultat = connexion.distant.initialize(params).get(120, TimeUnit.SECONDS)
            val capacites = resultat.capabilities

            // 1. synchronisation COMPLÈTE
            assertTrue(
                capacites.textDocumentSync?.isLeft == true &&
                    capacites.textDocumentSync?.left == org.eclipse.lsp4j.TextDocumentSyncKind.Full,
                "synchronisation : attendue Full (complète), reçue ${capacites.textDocumentSync}",
            )

            // 2. complétion — déclencheurs « . » et « : », sans résolution
            val completion = capacites.completionProvider
            assertNotNull(completion, "completionProvider absent")
            assertEquals(listOf(".", ":"), completion.triggerCharacters, "déclencheurs de complétion")
            assertEquals(java.lang.Boolean.FALSE, completion.resolveProvider, "resolveProvider attendu faux")

            // 3. survol, définition, actions de code, symboles de document
            assertTrue(annonce(capacites.hoverProvider), "hoverProvider non annoncé")
            assertTrue(annonce(capacites.definitionProvider), "definitionProvider non annoncé")
            assertTrue(annonce(capacites.codeActionProvider), "codeActionProvider non annoncé")
            assertTrue(annonce(capacites.documentSymbolProvider), "documentSymbolProvider non annoncé")

            // 4. pliage, aide à la signature (déclencheurs « ( » et « , »)
            assertTrue(annonce(capacites.foldingRangeProvider), "foldingRangeProvider non annoncé")
            val signature = capacites.signatureHelpProvider
            assertNotNull(signature, "signatureHelpProvider absent")
            assertEquals(listOf("(", ","), signature.triggerCharacters, "déclencheurs de signature")

            // 5. références, surlignage, renommage PRÉPARÉ
            assertTrue(annonce(capacites.referencesProvider), "referencesProvider non annoncé")
            assertTrue(annonce(capacites.documentHighlightProvider), "documentHighlightProvider non annoncé")
            val renommage = capacites.renameProvider
            assertTrue(renommage?.isRight == true, "renameProvider attendu en Either droit (options)")
            assertEquals(java.lang.Boolean.TRUE, renommage?.right?.prepareProvider, "prepareRename non préparé")

            // 6. formatage (document + plage), définition de type, implémentations
            assertTrue(annonce(capacites.documentFormattingProvider), "documentFormattingProvider non annoncé")
            assertTrue(annonce(capacites.documentRangeFormattingProvider), "rangeFormatting non annoncé")
            assertTrue(annonce(capacites.typeDefinitionProvider), "typeDefinitionProvider non annoncé")
            assertTrue(annonce(capacites.implementationProvider), "implementationProvider non annoncé")

            // 7. indices en ligne, symboles d'espace, plage de sélection
            assertTrue(annonce(capacites.inlayHintProvider), "inlayHintProvider non annoncé")
            assertTrue(annonce(capacites.workspaceSymbolProvider), "workspaceSymbolProvider non annoncé")
            assertTrue(annonce(capacites.selectionRangeProvider), "selectionRangeProvider non annoncé")

            // 8. ABSENCES VERROUILLÉES (honnêteté D3 — jamais annoncées)
            assertNull(capacites.semanticTokensProvider, "jetons sémantiques ne doivent PAS être annoncés")
            assertNull(capacites.documentOnTypeFormattingProvider, "formatage à la frappe non annoncé attendu")
            assertNull(capacites.linkedEditingRangeProvider, "édition liée non annoncée attendue")
            assertNull(capacites.codeLensProvider, "lentilles non annoncées attendues")
            assertNull(capacites.callHierarchyProvider, "hiérarchie d'appels non annoncée attendue")
            assertNull(capacites.typeHierarchyProvider, "hiérarchie de types non annoncée attendue")
            assertNull(capacites.colorProvider, "couleurs de document non annoncées attendues")

            // 9. serverInfo — identité et version
            assertEquals("codeide-kotlin", resultat.serverInfo.name, "serverInfo.name")
            assertNotNull(resultat.serverInfo.version, "serverInfo.version attendu")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("A1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `A2 - notifications avant initialize tolerees - la connexion survit`() {
        val connexion = demarrerServeur("a2", initialiser = false)
        try {
            // Client brutal : didOpen AVANT initialize (contrat : tolérant).
            val uri = File(connexion.racine, "src/jo/demo/Main.kt").toURI().toString()
            connexion.distant.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, "val x = 1\n")),
            )
            val params = InitializeParams()
            params.workspaceFolders =
                listOf(org.eclipse.lsp4j.WorkspaceFolder(connexion.racine.toURI().toString(), "harnais"))
            val resultat = connexion.distant.initialize(params).get(60, TimeUnit.SECONDS)
            assertNotNull(resultat.capabilities, "initialize doit répondre malgré la notification précoce")
            // La publication du didOpen précoce doit finir par arriver.
            connexion.client.attendre(uri)
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("A2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `A3 - shutdown ferme la session puis exit termine le processus proprement`() {
        val connexion = demarrerServeur("a3")
        try {
            assertNull(connexion.distant.shutdown().get(30, TimeUnit.SECONDS), "shutdown doit répondre vide")
            // Tolérance post-shutdown : une requête encore acceptée sans tuer le fil.
            val uri = ouvrir(connexion, "avant-arret.kt", "val x = 1\n")
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get()
            assertNull(survol, "survol post-shutdown : null attendu (session fermée)")
            // exit → le processus doit disparaître (exit SANS shutdown est aussi légal).
            connexion.distant.exit()
            // Le transport stdio du harnais s'arrête sur la fermeture de stdin
            // (en production le transport est in-process — pas de processus).
            connexion.processus.outputStream.close()
            assertTrue(
                connexion.processus.waitFor(15, TimeUnit.SECONDS),
                "le processus doit s'arrêter à exit\n${connexion.stderr.trop}",
            )
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("A3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE B — Synchronisation et diagnostics
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `B1 - didOpen invalide - diagnostic kt syntax traduit en positions LSP et plage elargie`() {
        val connexion = demarrerServeur("b1")
        try {
            val uri = ouvrir(connexion, "b1.kt", "val x = (\n")
            val publication = connexion.client.attendre(uri)
            assertEquals(1, publication.version, "version portée dans la publication")
            assertEquals(1, publication.diagnostics.size, "un diagnostic attendu")
            val diagnostic = publication.diagnostics.first()
            assertEquals("kt.syntax", diagnostic.code?.left, "code du diagnostic")
            assertEquals(DiagnosticSeverity.Error, diagnostic.severity, "gravité")
            assertEquals("codeide-kotlin", diagnostic.source, "source")
            // Offsets moteur 9..9 (fin de texte, plage vide) → élargie d'un caractère.
            assertPlage((0 to 9) to (1 to 0), diagnostic.range, "plage élargie du marqueur")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B2 - didChange corrige - la publication suivante est vide et porte la version`() {
        val connexion = demarrerServeur("b2")
        try {
            val uri = ouvrir(connexion, "b2.kt", "val x = (")
            modifier(connexion, uri, "val x = (1)\n", 2)
            val derniere = connexion.client.attendre(uri) { (it.version ?: -1) >= 2 }
            assertEquals(2, derniere.version, "version de la publication corrigée")
            assertTrue(derniere.diagnostics.isEmpty(), "plus aucun diagnostic après correction")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B3 - didChange de version obsolete ignore - la derniere version gagne`() {
        val connexion = demarrerServeur("b3")
        try {
            val uri = ouvrir(connexion, "b3.kt", "val x = (")
            modifier(connexion, uri, "val x = (1)\n", 3)
            // Frappe arrivée en désordre : version 2 APRÈS version 3 → ignorée.
            connexion.distant.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent("val x = (2)\n")),
                ),
            )
            Thread.sleep(600)
            val apres = connexion.client.publicationsDe(uri)
            assertTrue(
                apres.none { (it.version ?: -1) == 2 },
                "une publication de la version obsolète ne doit JAMAIS exister",
            )
            // Le texte courant reste la version 3 : le symbole visible est x, non périmé.
            val symboles =
                connexion.distant.textDocumentService
                    .documentSymbol(
                        org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier(uri)),
                    ).get()
            assertTrue(symboles.isNotEmpty(), "le document v3 doit encore exposer ses symboles")
            assertEquals("x", symboles.first().right.name, "le nom du document v3 (pas v2)")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B4 - didChange par plage (client non conforme) applique sans exception`() {
        val connexion = demarrerServeur("b4")
        try {
            val uri = ouvrir(connexion, "b4.kt", "val compteur = 1\n")
            // Changement PAR PLAGE alors que la capacité annoncée est Full.
            val texte = "val compteur = 1\n"
            connexion.distant.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(
                        TextDocumentContentChangeEvent(
                            Range(Position(0, 4), Position(0, 13)),
                            "total",
                        ),
                    ),
                ),
            )
            connexion.client.attendre(uri) { (it.version ?: -1) >= 2 }
            // La preuve que la plage a été appliquée : le symbole renommé par l'édition.
            val symboles =
                connexion.distant.textDocumentService
                    .documentSymbol(
                        org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier(uri)),
                    ).get()
            assertTrue(symboles.isNotEmpty(), "symboles attendus après changement par plage")
            assertEquals("total", symboles.first().right.name, "le changement par plage doit être appliqué")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B5 - didSave republie les diagnostics du document`() {
        val connexion = demarrerServeur("b5")
        try {
            val uri = ouvrir(connexion, "b5.kt", "val x = (")
            connexion.client.attendre(uri)
            val countAvant = connexion.client.publicationsDe(uri).size
            connexion.distant.textDocumentService.didSave(
                DidSaveTextDocumentParams(TextDocumentIdentifier(uri)),
            )
            connexion.client.attendre(uri) { connexion.client.publicationsDe(uri).size > countAvant }
            assertTrue(
                connexion.client.publicationsDe(uri).size > countAvant,
                "didSave doit republier les diagnostics",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B6 - didClose vide les squiggles (vidange LSP sans version)`() {
        val connexion = demarrerServeur("b6")
        try {
            val uri = ouvrir(connexion, "b6.kt", "val x = (")
            connexion.client.unique(uri, "kt.syntax")
            val countAvant = connexion.client.publicationsDe(uri).size
            connexion.distant.textDocumentService.didClose(
                DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
            )
            val vidange = connexion.client.attendre(uri) { connexion.client.publicationsDe(uri).size > countAvant }
            assertTrue(vidange.diagnostics.isEmpty(), "la vidange ne doit porter AUCUN diagnostic")
            assertNull(vidange.version, "la vidange ne porte pas de version")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B6 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B7 - import inutilise - Warning et tag Unnecessary`() {
        val connexion = demarrerServeur("b7")
        try {
            val uri = ouvrir(connexion, "b7.kt", "import kotlin.math.PI\nval x = 1\n")
            val diagnostic = connexion.client.unique(uri, "kt.unusedImport")
            assertEquals(DiagnosticSeverity.Warning, diagnostic.severity, "gravité de l'import inutilisé")
            assertEquals(listOf(org.eclipse.lsp4j.DiagnosticTag.Unnecessary), diagnostic.tags, "tag Unnecessary")
            assertPlage((0 to 0) to (0 to 21), diagnostic.range, "plage = la directive d'import")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B7 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B8 - imports conflictuels signales sur le plus tardif`() {
        val connexion = demarrerServeur("b8")
        try {
            val uri =
                ouvrir(
                    connexion,
                    "b8.kt",
                    "import java.util.ArrayList\nimport kotlin.collections.ArrayList\nval x = ArrayList<String>()\n",
                )
            val diagnostic = connexion.client.unique(uri, "kt.conflictingImport")
            // La gravité ERREUR est par conception (miroir de kotlinc —
            // CONFLICTING_IMPORT est une erreur du compilateur amont).
            assertEquals(DiagnosticSeverity.Error, diagnostic.severity, "gravité du conflit d'import (parité kotlinc)")
            assertEquals(1, diagnostic.range.start.line, "le conflit est signalé sur le PLUS TARDIF des imports")
            assertEquals(1, diagnostic.range.end.line, "plage sur la ligne du second import")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B8 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B9 - diagnostics semantiques K4 - un scenario par code documente`() {
        val connexion = demarrerServeur("b9")
        try {
            val cas =
                mapOf(
                    "kt.unresolved" to "fun f() { inconnuXyz() }",
                    "kt.typeMismatch" to "fun f() { val x: Int = \"txt\" }",
                    "kt.argumentCount" to "fun g(a: Int) { }\nfun f() { g() }",
                    "kt.unsafeNullable" to "fun f(s: String?) { s.length }",
                    "kt.missingReturn" to "fun f(): Int { }",
                    "kt.unreachable" to "fun f(): Int { return 1\nprintln() \nreturn 2 }",
                    "kt.valReassign" to "fun f() { val x = 1\nx = 2 }",
                )
            for ((codeAttendu, texte) in cas) {
                val uri = ouvrir(connexion, "b9-${codeAttendu.substringAfter('.')}.kt", texte)
                val diagnostic = connexion.client.unique(uri, codeAttendu)
                // kt.unreachable est AVERTISSEMENT par conception (code
                // mort — miroir des IDE) ; les autres codes sont ERREUR.
                val graviteAttendue =
                    if (codeAttendu == "kt.unreachable") DiagnosticSeverity.Warning else DiagnosticSeverity.Error
                assertEquals(
                    graviteAttendue,
                    diagnostic.severity,
                    "$codeAttendu — gravité du catalogue",
                )
            }
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B9 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `B10 - documents limites - vide, package seul, kts, CRLF`() {
        val connexion = demarrerServeur("b10")
        try {
            // Document VIDE : aucune exception, publication sans diagnostic.
            val vide = ouvrir(connexion, "b10-vide.kt", "")
            val publicationVide = connexion.client.attendre(vide)
            assertTrue(publicationVide.diagnostics.isEmpty(), "document vide : aucun diagnostic")

            // Package + import seul : l'import inutilisé est signalé, rien d'autre.
            val paquetSeul = ouvrir(connexion, "b10-paquet.kt", "package jo.demo\nimport kotlin.math.PI\n")
            val publicationPaquet = connexion.client.attendre(paquetSeul)
            assertTrue(
                publicationPaquet.diagnostics.all { it.code?.left == "kt.unusedImport" },
                "package seul : seuls les imports inutilisés sont signalés",
            )

            // Script .kts : mode script (libre en racine) — un val nu est légal.
            val script = ouvrir(connexion, "file:///b10-script.kts", "val x = 1\n")
            connexion.client.attendre(script)
            assertTrue(
                connexion.client.publicationsDe(script).none {
                    it.diagnostics.isNotEmpty()
                },
                "script .kts valide : aucun diagnostic",
            )

            // CRLF : le convertisseur doit compter les lignes correctement.
            val crlf = ouvrir(connexion, "b10-crlf.kt", "val x = 1\r\nval y = (\r\n")
            val diagnostic = connexion.client.unique(crlf, "kt.syntax")
            assertEquals(1, diagnostic.range.start.line, "CRLF : le marqueur doit être sur la ligne 1")

            // Unicode/UTF-16 : une LOCALE dans une fonction, usages APRÈS un
            // emoji hors BMP sur la MÊME ligne — chaque colonne est en
            // unités UTF-16 (l'emoji en vaut DEUX). Les locales suivent la
            // preuve par portée ; les top-level same-file, eux, sont
            // prouvés depuis l'ADR 0204 (sondes S1–S3).
            val emoji = "fun f() { val cle = \"😀\"; val x = cle; val y = cle }\n"
            val uriEmoji = ouvrir(connexion, "b10-emoji.kt", emoji)
            connexion.client.attendre(uriEmoji)
            val surlignages =
                connexion.distant.textDocumentService
                    .documentHighlight(
                        org.eclipse.lsp4j.DocumentHighlightParams(
                            TextDocumentIdentifier(uriEmoji),
                            Position(0, 14),
                        ),
                    ).get()
            assertEquals(3, surlignages.size, "trois occurrences de la locale cle (déclaration + 2 usages)")
            val plages = surlignages.map { it.range }.sortedBy { it.start.character }
            assertPlage((0 to 14) to (0 to 17), plages[0], "déclaration de cle (AVANT l'emoji)")
            assertPlage((0 to 34) to (0 to 37), plages[1], "premier usage APRÈS l'emoji (UTF-16 : emoji = 2 unités)")
            assertPlage((0 to 47) to (0 to 50), plages[2], "second usage APRÈS l'emoji (UTF-16)")
            assertEquals(
                org.eclipse.lsp4j.DocumentHighlightKind.Write,
                surlignages.first { it.range == plages[0] }.kind,
                "la déclaration est en ÉCRITURE",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("B10 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }
}
