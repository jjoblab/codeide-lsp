package jo.codeide.lsp.host

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `le niveau java du projet descend jusqu au moteur du module - S1-a`() {
        val racine = Files.createDirectories(tmp.resolve("projet-niveaux"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-niveaux"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-niveaux").toFile(),
            )
        try {
            session.appliquerClasspath(
                fixture.resultatClasspath(
                    jar,
                    compatibiliteSourceParNom = mapOf(":app" to "8", ":lib" to "21"),
                ),
            )
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()

            // Le niveau POSÉ sur chaque contexte (brut, tel que le
            // modèle l'a normalisé).
            assertEquals("8", environnement.complianceModule(":app"))
            assertEquals("21", environnement.complianceModule(":lib"))

            // Chaque moteur est construit avec SON niveau.
            assertEquals("8", environnement.jdt(":app").complianceDemandee())
            assertEquals("21", environnement.jdt(":lib").complianceDemandee())

            // Deuxième sync IDENTIQUE : pas de retrait du niveau (no-op
            // système conservé — les moteurs ne sont pas reconstruits).
            val moteurAvant = environnement.jdt(":app")
            session.appliquerClasspath(
                fixture.resultatClasspath(
                    jar,
                    compatibiliteSourceParNom = mapOf(":app" to "8", ":lib" to "21"),
                ),
            )
            assertEquals(moteurAvant, environnement.jdt(":app"), "sync redondant : moteur conservé")
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

    @Test
    fun `android jar de la toolchain branche au moteur par defaut`() {
        // Lot h4 écart 1 (§16.5) : AVANT toute sync, le contexte PAR DÉFAUT
        // porte la plateforme du toolchain — le SDK est installé par le
        // bootstrap AVANT le modèle Gradle, comme android.jar arrive dès
        // la config SDK dans Android Studio.
        val racine = Files.createDirectories(tmp.resolve("projet-defaut"))
        val plateforme = tmp.resolve("android.jar")
        Files.write(plateforme, byteArrayOf(1, 2, 3))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-defaut").toFile(),
                androidJar = plateforme.toFile(),
            )
        try {
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()
            assertTrue(
                environnement.classpathEntries().any { it.name == "android.jar" },
                "le contexte par défaut porte la plateforme du toolchain",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `le moteur par defaut resout la plateforme avant toute sync`() {
        // La promesse de l'écart 1 : PREMIÈRE ouverture, aucune sync —
        // android.* (et java.* du boot classpath) RÉSOLVENT déjà en
        // régime plein, pas seulement le repli syntaxique v0.101.0.
        val racine = Files.createDirectories(tmp.resolve("projet-plateforme"))
        val fixture = ProjetFixture(racine)
        val plateforme = fixture.construireJarPlateforme(tmp.resolve("plateforme-defaut"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-plateforme").toFile(),
                androidJar = plateforme.toFile(),
            )
        try {
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()
            val sources = Files.createDirectories(racine.resolve("src/app"))
            val androidMain = sources.resolve("AndroidMain.java")
            Files.writeString(androidMain, SOURCE_ANDROID_MAIN)
            environnement.addSourceDir(sources.parent.toFile())
            val erreurs =
                environnement
                    .jdt()
                    .diagnose(androidMain.toString(), SOURCE_ANDROID_MAIN)
                    .filter { it.severity() == jo.lspjava.api.Severity.ERROR }
            assertEquals(emptyList<String>(), erreurs.map { it.message() })
        } finally {
            session.close()
        }
    }

    @Test
    fun `android jar du module rejoint le moteur du module - regression v0 101`() {
        // Écart 1, côté MODÈLE Gradle : le champ androidJar (ADR 0112)
        // existait dans le protocole v8 mais n'était JAMAIS consommé —
        // sans lui, android.util.Log restait « cannot be resolved » même
        // après sync. Ce test échoue sur le code d'avant v0.102.0.
        val racine = Files.createDirectories(tmp.resolve("projet-android"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-android"))
        val plateforme = fixture.construireJarPlateforme(tmp.resolve("plateforme-android"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-android").toFile(),
            )
        try {
            session.appliquerClasspath(fixture.resultatClasspath(jar, androidJar = plateforme))
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()
            val android = fixture.dossierApp.resolve("AndroidMain.java")
            Files.writeString(android, SOURCE_ANDROID_MAIN)
            val erreurs =
                environnement
                    .jdt(":app")
                    .diagnose(android.toString(), SOURCE_ANDROID_MAIN)
                    .filter { it.severity() == jo.lspjava.api.Severity.ERROR }
            assertEquals(
                emptyList<String>(),
                erreurs.map { it.message() },
                "android.util.Log devait résoudre par le android.jar du module",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `le modele persiste nourrit une nouvelle session sans sync`() {
        // Écart 2 (§16.5) : la session 1 applique le classpath (sync) et
        // PERSISTE le modèle ; la session 2 (réouverture) s'en nourrit
        // DEPUIS LE DISQUE — modèles, index et moteurs retrouvés avant même
        // la revalidation silencieuse, comme Android Studio rouvre son
        // index persisté.
        val racine = Files.createDirectories(tmp.resolve("projet-persiste"))
        val fixture = ProjetFixture(racine)
        val jar = fixture.construireJarFixture(tmp.resolve("fixture-persiste"))
        SessionLsp(
            racineProjet = racine.toFile(),
            stockageIndex = tmp.resolve("index-persiste").toFile(),
        ).use { premiere ->
            premiere.appliquerClasspath(fixture.resultatClasspath(jar))
        }
        val fichierModele = racine.resolve(".codeide/local/lsp-modele.json")
        assertTrue(Files.isRegularFile(fichierModele), "le modèle est persisté à côté de sync-state.json")

        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-persiste").toFile(),
            )
        try {
            assertTrue(session.nourrirDepuisModelePersiste(), "le modèle persisté relû et appliqué")
            assertEquals(
                setOf(":lib", ":app"),
                session.modele.value
                    ?.modules
                    ?.map { it.nom }
                    ?.toSet(),
            )
            // Les moteurs sont nourris SANS sync : Main.java résout
            // alpha.Alpha par le classpath relu du disque.
            val environnement =
                (session.serveur() as jo.lspjava.lsp.JavaLanguageServer).environment()
            val main = fixture.dossierApp.resolve("Main.java")
            Files.writeString(main, SOURCE_MAIN)
            val erreurs =
                environnement
                    .jdt(":app")
                    .diagnose(main.toString(), SOURCE_MAIN)
                    .filter { it.severity() == jo.lspjava.api.Severity.ERROR }
            assertEquals(
                emptyList<String>(),
                erreurs.map { it.message() },
                "le moteur :app devait résoudre par le modèle persisté",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `sans modele persiste le nourrissage rend faux sans erreur`() {
        // Lecture tolérante : racine vierge (première ouverture, projet
        // importé) → faux, aucune exception — la sync nourrira à sa suite.
        val racine = Files.createDirectories(tmp.resolve("projet-vierge"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-vierge").toFile(),
            )
        try {
            assertFalse(session.nourrirDepuisModelePersiste())
            assertNull(session.modele.value)
        } finally {
            session.close()
        }
    }

    private companion object {
        /** 64 × 512 Kio = 32 Mio — dépasse le plancher de 30 Mio. */
        private const val NB_ENTREES_BUDGET: Int = 64
    }
}
