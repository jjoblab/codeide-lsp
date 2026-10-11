package jo.codeide.lsp.host

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests de l'ORCHESTRATION de l'hôte (mission « LSP Java » étape 4) :
 * ClasspathResult → modèle → moteurs PAR module + index FUSION — au
 * travers de la surface publique [SessionLsp].
 *
 * Fixtures : projet à DEUX modules (:lib porteur du jar, :app déclarant
 * le frère en `MODULE`) — la résolution de `alpha.Alpha` par le moteur
 * de :app passe par les SOURCES du frère (motif lot C) ou par
 * l'autorité de l'index (union) — les deux voies prouvent le
 * branchement réel.
 *
 * DestructuringDeclarationWithTooManyEntries justifié (règle 8) : le
 * quadruplet (session, fixture, jar, application) est le pack complet
 * du montage — l'accès nommé alourdirait chaque test pour un gain nul.
 */
@Suppress("DestructuringDeclarationWithTooManyEntries")
@Timeout(120)
class OrchestrateurLspTest {
    @TempDir
    lateinit var tmp: Path

    private fun sessionSurFixture(): Quadruplet {
        val racine = Files.createDirectories(tmp.resolve("projet"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index").toFile(),
            )
        val resultat = fixture.resultatClasspath(jar)
        val application = session.appliquerClasspath(resultat)
        return Quadruplet(session, fixture, jar, application)
    }

    /** session, fixture, jar, application — le pack du test. */
    private data class Quadruplet(
        val session: SessionLsp,
        val fixture: ProjetFixture,
        val jar: Path,
        val application: ResultatApplication,
    )

    @Test
    fun `modele et modules appliques`() {
        val (session, fixture, jar, application) = sessionSurFixture()
        try {
            assertEquals(2, application.modulesActifs)
            assertEquals(0, application.modulesIgnores)
            assertEquals(1, application.entreesIndex, "une seule entrée indexable (le jar)")
            assertTrue(application.unionChangee, "premier sync : l'union change")
            assertEquals(30, application.budgetCacheMo, "plancher ADR 0111 sous 62 entrées")
            val projet = session.modele.value
            assertTrue(projet != null, "le modèle est publié")
            assertEquals(setOf(":lib", ":app"), projet!!.modules.map { it.nom }.toSet())
            val moduleLib = projet.modules.first { it.nom == ":lib" }
            assertEquals(
                jar.toString(),
                moduleLib.classpath.entrees
                    .first { it.kind == jo.codeide.lsp.model.KindEntree.JAR }
                    .chemin,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `moteurs par module et resolution par les sources du frere`() {
        val (session, fixture, _, _) = sessionSurFixture()
        try {
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()
            // DEUX moteurs distincts (lot E) — :app et :lib.
            val moteurApp = environnement.jdt(":app")
            val moteurLib = environnement.jdt(":lib")
            assertNotSame(moteurApp, moteurLib)

            // Le diagnostic de :app résout alpha.Alpha : par les SOURCES
            // du frère (:lib/src est racine de :app) ou par l'autorité
            // de l'index (union porte le jar) — zéro erreur attendu.
            val main = fixture.dossierApp.resolve("Main.java")
            Files.writeString(main, SOURCE_MAIN)
            val diagnostics = moteurApp.diagnose(main.toString(), SOURCE_MAIN)
            val erreurs = diagnostics.filter { it.severity() == jo.lspjava.api.Severity.ERROR }
            assertEquals(emptyList<String>(), erreurs.map { it.message() })
        } finally {
            session.close()
        }
    }

    @Test
    fun `index fusion pret et completion par l'index`() =
        runBlocking {
            val (session, fixture, _, _) = sessionSurFixture()
            try {
                // La vue fusion atteint PRÊT (P7/P8) — le flux public suit.
                withTimeout(60_000) {
                    session.etatIndexation.first { it.phase == PhaseIndexation.PRET }
                }
                assertEquals(1, session.etatIndexation.value.total)

                // La complétion NOM du moteur :app propose Alpha — la voie
                // préfixe de l'index (le jar n'est PAS dans le classpath
                // propre de :app : c'est l'UNION qui nourrit la requête).
                val compl = fixture.dossierApp.resolve("Compl.java")
                Files.writeString(compl, SOURCE_COMPLETION)
                val (ligne, colonne) = positionApres(SOURCE_COMPLETION, "Alp")
                val serveur = session.serveur()
                serveur
                    .initialize(
                        org.eclipse.lsp4j.InitializeParams().apply {
                            workspaceFolders =
                                listOf(
                                    org.eclipse.lsp4j.WorkspaceFolder(
                                        fixture.dossierApp.parent.parent.parent
                                            .toUri()
                                            .toString(),
                                        "projet",
                                    ),
                                )
                        },
                    ).get(10, java.util.concurrent.TimeUnit.SECONDS)
                serveur.initialized(org.eclipse.lsp4j.InitializedParams())
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
                val reponse =
                    serveur.textDocumentService
                        .completion(
                            org.eclipse.lsp4j.CompletionParams(
                                org.eclipse.lsp4j.TextDocumentIdentifier(compl.toUri().toString()),
                                org.eclipse.lsp4j.Position(ligne, colonne),
                            ),
                        ).get(30, java.util.concurrent.TimeUnit.SECONDS)
                val items = reponse.left ?: reponse.right.items
                assertTrue(
                    items.any { it.label.contains("Alpha") },
                    "Alpha attendu dans la complétion (via l'index) : " + items.map { it.label },
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `sync redondant sans cout`() {
        val (session, fixture, jar, _) = sessionSurFixture()
        try {
            // Rejoue le MÊME résultat (contenu identique) : l'union ne
            // change pas, l'adaptateur est conservé (setIndexNoms
            // idempotent), les moteurs restent chauds (lot E).
            val seconde = session.appliquerClasspath(fixture.resultatClasspath(jar))
            org.junit.jupiter.api.Assertions
                .assertFalse(seconde.unionChangee)
            assertEquals(2, seconde.modulesActifs)
            assertEquals(30, seconde.budgetCacheMo, "le budget ne bouge pas")
        } finally {
            session.close()
        }
    }

    @Test
    fun `modules ignores comptes et sans contexte`() {
        val racine = Files.createDirectories(tmp.resolve("projet-igne"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-igne"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-igne").toFile(),
            )
        try {
            val application =
                session.appliquerClasspath(
                    fixture.resultatClasspath(jar, avecIgnore = true),
                )
            assertEquals(2, application.modulesActifs)
            assertEquals(1, application.modulesIgnores)
            // :brouillard n'a jamais de contexte — son moteur est vide
            // (jamais invalidé par un sync des autres).
            assertTrue(
                session.modele.value!!
                    .modules
                    .any { it.nom == ":brouillard" && it.ignore },
                "le module ignoré reste dans le modèle (honnêteté W7)",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `budget derive du corpus par jar`() {
        val racine = Files.createDirectories(tmp.resolve("projet-budget"))
        val modules = ArrayList<jo.codeide.tooling.protocol.ClasspathModule>()
        val dossierVide = Files.createDirectories(racine.resolve("src"))
        // 64 entrées vides → 64 × 512 Kio = 32 Mio > plancher 30 Mio.
        repeat(NB_ENTREES_BUDGET) { indice ->
            val jar = tmp.resolve("vide-$indice.jar").toFile()
            // Contenu DISTINCT par entrée : l'union déduplique PAR
            // EMPREINTE de contenu — un même octet donnerait UNE seule
            // entrée (comportement voulu, cf. `sync redondant`).
            jar.writeBytes(byteArrayOf(indice.toByte(), 1, 2))
            modules.add(
                jo.codeide.tooling.protocol.ClasspathModule(
                    name = ":m$indice",
                    sourceDirs = listOf(dossierVide.toString()),
                    entries =
                        listOf(
                            jo.codeide.tooling.protocol.ClasspathEntry(
                                path = jar.toString(),
                                kind = jo.codeide.tooling.protocol.ClasspathKind.JAR,
                                scope = "compile",
                            ),
                        ),
                ),
            )
        }
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-budget").toFile(),
            )
        try {
            val application =
                session.appliquerClasspath(
                    jo.codeide.tooling.protocol.ClasspathResult(
                        id = "budget",
                        protocolVersion = 1,
                        projectDir = racine.toString(),
                        modules = modules,
                    ),
                )
            assertEquals(32, application.budgetCacheMo, "budget PAR JAR au-delà du plancher")
        } finally {
            session.close()
        }
    }

    private companion object {
        /** 64 × 512 Kio = 32 Mio — dépasse le plancher de 30 Mio. */
        private const val NB_ENTREES_BUDGET: Int = 64
    }
}
