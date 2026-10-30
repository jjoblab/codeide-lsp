package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeurJava
import jo.codeide.lsp.host.harnais.OutilsHarnais.ecrireClasspathJava
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REPRODUCTION du retour appareil du 30/10 au soir (CodeIDE 0.204.1,
 * logs `codeide-logs-2026-10-30-001905.zip`) : le client cel-lsp 4.14.1
 * se connecte (connect: SUCCESS, capabilities YES), puis CHAQUE requête
 * échoue côté serveur :
 *
 * - `documentHighlight: ERROR ... ResponseErrorException: Internal error.`
 *   (dès l'ouverture, offset=0) ;
 * - `inlayHint: ERROR ... Internal error.` (lines 0-36) ;
 * - `completion: ERROR ... Internal error.` (après chaque didChange) ;
 * - et AUCUNE ligne `publishDiagnostics: N diagnostics received`
 *   (la publication n'arrive jamais).
 *
 * Ce scénario rejoue LE FIL EXACT de la session défaillante sur la
 * chaîne de production complète (SessionLsp → serveur du moteur neuf,
 * stdio) : didOpen → documentHighlight(0) → inlayHint → didChange →
 * completion, puis (miroir de l'app qui sync) pousserSync + re-frappe.
 * Le stderr du sous-processus (journaux JUL de lsp4j — la stack trace
 * réelle du « Internal error ») est imprimé à l'échec.
 */
@Timeout(900)
public class ReproductionFrappeAppareilTest {
    @Test
    public fun `E1 - fil appareil - didOpen puis documentHighlight inlayHint completion sans Internal error`() {
        val connexion = demarrerServeurJava("repro-appareil")
        try {
            val fichier = File(connexion.racine, "src/com/ex/app/MainActivity.java")
            val uri = fichier.toURI().toString()
            val texteInitial = fichier.readText()

            // ── Le fil appareil, à la lettre ────────────────────────────
            // 1. didOpen (le connect de cel-lsp l'envoie avant tout).
            connexion.distant.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "java", 1, texteInitial)),
            )

            // 2. documentHighlight à l'offset 0 — PREMIÈRE requête de
            //    l'app après la connexion (le simple fait de toucher le
            //    document déclenche le surlignage).
            var resultat = "OK"
            val surbrillances =
                runCatching {
                    connexion.distant.textDocumentService
                        .documentHighlight(
                            DocumentHighlightParams(TextDocumentIdentifier(uri), Position(0, 0)),
                        ).get(60, TimeUnit.SECONDS)
                }.getOrElse { echec ->
                    resultat = "ÉCHEC documentHighlight : ${echec.javaClass.simpleName} : ${echec.message}"
                    null
                }
            println("[REPRO] documentHighlight → $resultat (${surbrillances?.size ?: -1} éléments)")

            // 3. inlayHint sur les lignes 0-36 (l'app le demande à
            //    l'ouverture puis après chaque frappe).
            resultat = "OK"
            val indices =
                runCatching {
                    connexion.distant.textDocumentService
                        .inlayHint(
                            InlayHintParams(
                                TextDocumentIdentifier(uri),
                                Range(Position(0, 0), Position(36, 0)),
                            ),
                        ).get(60, TimeUnit.SECONDS)
                }.getOrElse { echec ->
                    resultat = "ÉCHEC inlayHint : ${echec.javaClass.simpleName} : ${echec.message}"
                    null
                }
            println("[REPRO] inlayHint → $resultat (${indices?.size ?: -1} éléments)")

            // 4. didChange v1 : l'utilisateur tape (sync PLEINE, comme
            //    cel-lsp — 1 événement portant le texte intégral).
            val frappe = texteInitial.replace("greeter.saluer();", "greeter.s")
            connexion.distant.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent(frappe)),
                ),
            )

            // 5. completion au caret (l'app la demande à la frappe —
            //    le caret est juste après « greeter.s » : complétion de
            //    MEMBRE avec préfixe « s », « saluer » doit sortir).
            resultat = "OK"
            val completions =
                runCatching {
                    connexion.distant.textDocumentService
                        .completion(
                            CompletionParams(
                                TextDocumentIdentifier(uri),
                                Position(13, 17),
                            ),
                        ).get(60, TimeUnit.SECONDS)
                }.getOrElse { echec ->
                    resultat = "ÉCHEC completion : ${echec.javaClass.simpleName} : ${echec.message}"
                    null
                }
            println("[REPRO] completion → $resultat (${completions?.right?.items?.size ?: -1} items)")

            // 6. La sync Gradle arrive (miroir de l'app : la notification
            //    harnais/appliquerClasspath rejoue session.appliquerClasspath).
            val fichierSync = ecrireClasspathJava(connexion.racine, OutilsHarnais.androidJar)
            connexion.endpoint.notify(
                "harnais/appliquerClasspath",
                mapOf("chemin" to fichierSync.absolutePath),
            )
            Thread.sleep(500)
            val frappe2 = frappe.replace("greeter.s", "greeter.saluer(); int x = 1;")
            connexion.distant.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 3),
                    listOf(TextDocumentContentChangeEvent(frappe2)),
                ),
            )
            resultat = "OK"
            runCatching {
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(13, 17)))
                    .get(60, TimeUnit.SECONDS)
            }.getOrElse { echec ->
                resultat = "ÉCHEC completion post-sync : ${echec.javaClass.simpleName} : ${echec.message}"
            }
            println("[REPRO] completion post-sync → $resultat")

            // ── Les diagnostics reçus (l'app attendu : N > 0 à la
            //    frappe d'une erreur ; le fil défaillant : RIEN) ───────
            Thread.sleep(1000)
            val publications = connexion.client.publications.toList()
            println("[REPRO] publishDiagnostics reçues : ${publications.size}")
            publications.take(5).forEach {
                println("[REPRO]   → ${it.diagnostics.size} diagnostics (${it.uri.takeLast(40)})")
            }

            // Le fil défaillant produit des Internal error — on ÉCHOUE
            // explicitement pour faire sortir le stderr du serveur.
            check(surbrillances != null) { "documentHighlight a échoué (Internal error ?) — voir stderr" }
            check(indices != null) { "inlayHint a échoué (Internal error ?) — voir stderr" }
            check(completions != null) { "completion a échoué (Internal error ?) — voir stderr" }
        } finally {
            println("[REPRO] ── stderr du serveur ──")
            println(connexion.stderr.toString().take(8000))
            connexion.processus.destroyForcibly()
        }
    }
}
