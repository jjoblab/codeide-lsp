package jo.codeide.lsp.host

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Test d'ALIGNEMENT lsp4j (mission « LSP Java » étape 4, étude §2.6) :
 * `:lsp:java` compile contre lsp4j **0.21.2**, `cel-lsp` contre
 * **0.22.0** — la résolution de `:lsp:host` prend la PLUS HAUTE
 * (0.22.0). Ce test prouve que le moteur VENDORED (binaire compilé
 * 0.21.2) fonctionne SOUS 0.22.0 : liaisons initialize/initialized/
 * didOpen/completion/shutdown/exit.
 *
 * L'écart documenté à l'étude (« l'étape 4 devra aligner (ou isoler) »)
 * est tranché : ALIGNER — la voie RemoteEndpoint/gson (JSON-RPC complet
 * via cel-lsp) vit côté branchement h2 ; ici c'est la liaison binaire
 * qui fait foi.
 */
@Timeout(120)
class AlignementLsp4jTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `la resolution prend lsp4j 0-22-0 et le moteur fonctionne dessous`() {
        // La classe LanguageServer vient du jar RESOLU (0.22.0 par
        // cel-lsp — la plus haute gagne).
        val localisation =
            org.eclipse.lsp4j.services.LanguageServer::class.java
                .protectionDomain
                ?.codeSource
                ?.location
        assertNotNull(localisation) { "lsp4j chargé d'un jar connu" }
        assertTrue(
            localisation.toString().contains(VERSION_LSP4J),
            "lsp4j résolu attendu $VERSION_LSP4J, trouvé $localisation",
        )

        // Le moteur vendored sous CE runtime : le cycle nominal complet.
        val racine = Files.createDirectories(tmp.resolve("projet"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index").toFile(),
            )
        try {
            session.appliquerClasspath(fixture.resultatClasspath(jar))
            val serveur = session.serveur()
            val resultats =
                serveur
                    .initialize(
                        org.eclipse.lsp4j.InitializeParams().apply {
                            workspaceFolders =
                                listOf(
                                    org.eclipse.lsp4j.WorkspaceFolder(racine.toUri().toString(), "projet"),
                                )
                        },
                    ).get(10, TimeUnit.SECONDS)
            // ServerInfo rempli par le moteur (compile 0.21.2, runtime
            // 0.22.0 — la liaison binaire tient).
            assertTrue(resultats.serverInfo.name.isNotBlank())
            serveur.initialized(org.eclipse.lsp4j.InitializedParams())

            val items = compler(serveur, fixture)
            assertTrue(items.isNotEmpty(), "la complétion propose (liaison 0.21.2 sous 0.22.0)")
            serveur.shutdown().get(10, TimeUnit.SECONDS)
            serveur.exit()
        } finally {
            session.close()
        }
    }

    /** didOpen + complétion sur la source fixture — les items résolus. */
    private fun compler(
        serveur: org.eclipse.lsp4j.services.LanguageServer,
        fixture: ProjetFixture,
    ): List<org.eclipse.lsp4j.CompletionItem> {
        val compl = fixture.dossierApp.resolve("Compl.java")
        Files.writeString(compl, SOURCE_COMPLETION)
        serveur.textDocumentService.didOpen(
            org.eclipse.lsp4j.DidOpenTextDocumentParams(
                org.eclipse.lsp4j.TextDocumentItem(
                    compl.toUri().toString(),
                    "java",
                    1,
                    SOURCE_COMPLETION,
                ),
            ),
        )
        val (ligne, colonne) = positionApres(SOURCE_COMPLETION, "Alp")
        val reponse =
            serveur.textDocumentService
                .completion(
                    org.eclipse.lsp4j.CompletionParams(
                        org.eclipse.lsp4j.TextDocumentIdentifier(compl.toUri().toString()),
                        org.eclipse.lsp4j.Position(ligne, colonne),
                    ),
                ).get(30, TimeUnit.SECONDS)
        return reponse.left ?: reponse.right.items
    }

    private companion object {
        /** Version attendue de la résolution (cel-lsp l'impose). */
        private const val VERSION_LSP4J: String = "0.22.0"
    }
}
