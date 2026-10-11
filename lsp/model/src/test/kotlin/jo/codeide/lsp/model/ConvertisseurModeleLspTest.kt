package jo.codeide.lsp.model

import jo.codeide.tooling.protocol.ClasspathModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversion ClasspathResult → ProjetLsp (prompt 2 §8 étape 1) : cas
 * multi-modules, api/impl (isolation par module), conflits de versions,
 * dépendance cyclique rejetée, module ignoré, AAR, sourcesets de test —
 * et le CRITÈRE : même classpath ⇒ même empreinte ; une dépendance
 * changée ⇒ l'empreinte du SEUL module concerné change.
 */
class ConvertisseurModeleLspTest {
    private val stats = FauxStats()
    private val convertisseur = ConvertisseurModeleLsp

    // ------------------------------------------------------------------
    // Multi-modules, isolation api/impl, sourcesets de test.
    // ------------------------------------------------------------------

    @Test
    fun `un projet multi-modules livre chaque module avec ses sources par role`() {
        stats.ajouter(FixturesClasspath.cheminMaven("org.slf4j", "slf4j-api", "2.0.18"))
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            sources = listOf("/p/app/src/main/java", "/p/app/src/main/kotlin"),
                            sourcesTest = listOf("/p/app/src/test/java"),
                            entrees = listOf(FixturesClasspath.moduleFrere(":core:domain")),
                            androidJar = "/sdk/android.jar",
                            variante = "debug",
                        ),
                        FixturesClasspath.module(
                            nom = ":core:domain",
                            sources = listOf("/p/core/domain/src/main/kotlin"),
                            entrees =
                                listOf(
                                    FixturesClasspath.jar(
                                        FixturesClasspath.cheminMaven("org.slf4j", "slf4j-api", "2.0.18"),
                                    ),
                                ),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        assertEquals(2, projet.modules.size)
        assertEquals(":app", projet.modules[0].nom)
        assertEquals(TypeModuleLsp.ANDROID, projet.modules[0].type)
        assertEquals(listOf("/p/app/src/main/java", "/p/app/src/main/kotlin"), projet.modules[0].sources.principal)
        assertEquals(listOf("/p/app/src/test/java"), projet.modules[0].sources.test)
        assertEquals("/sdk/android.jar", projet.modules[0].androidJar)
        assertEquals("debug", projet.modules[0].variante)
        assertEquals(TypeModuleLsp.JVM, projet.modules[1].type)
        assertNull(projet.modules[1].androidJar)
    }

    @Test
    fun `isolation par module - le classpath d'un module ne porte que ses propres entrees`() {
        // api/implementation (§8) : la dépendance implementation de
        // :lib n'est PAS dans le classpath de :app — Gradle l'a déjà
        // résolu ainsi ; le modèle PRÉSERVE l'isolation (W5).
        val implementationDeLib = FixturesClasspath.cheminMaven("com.squareup.retrofit2", "retrofit", "3.0.0")
        val apiDeLib = FixturesClasspath.cheminMaven("org.jetbrains", "annotations", "26.0.1")
        stats.ajouter(implementationDeLib).ajouter(apiDeLib)
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            entrees =
                                listOf(
                                    FixturesClasspath.moduleFrere(":lib"),
                                    FixturesClasspath.jar(apiDeLib),
                                ),
                        ),
                        FixturesClasspath.module(
                            nom = ":lib",
                            entrees =
                                listOf(
                                    FixturesClasspath.jar(apiDeLib),
                                    FixturesClasspath.jar(implementationDeLib),
                                ),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val app = projet.modules.first { it.nom == ":app" }
        val lib = projet.modules.first { it.nom == ":lib" }
        assertTrue("app voit l'api de :lib (annotations)", app.classpath.entrees.any { it.chemin == apiDeLib })
        assertFalse(
            "app ne voit PAS l'implementation de :lib (retrofit)",
            app.classpath.entrees.any {
                it.chemin ==
                    implementationDeLib
            },
        )
        assertTrue(
            "lib voit sa propre dépendance implementation",
            lib.classpath.entrees.any {
                it.chemin ==
                    implementationDeLib
            },
        )
        assertEquals(listOf(DependanceModule(cible = ":lib", portee = "compile")), app.dependancesModules)
    }

    @Test
    fun `les entrees de test portent la portee test et vivent dans le meme module`() {
        val jarTest = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        stats.ajouter(jarTest)
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            entrees =
                                listOf(
                                    FixturesClasspath.jar(
                                        FixturesClasspath.cheminMaven("org.jetbrains", "annotations", "26.0.1"),
                                        portee = "compile",
                                    ),
                                    FixturesClasspath.jar(jarTest, portee = "test"),
                                ),
                        ),
                    ),
            )
        stats.ajouter(FixturesClasspath.cheminMaven("org.jetbrains", "annotations", "26.0.1"))

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val entreeTest =
            projet.modules
                .single()
                .classpath.entrees
                .first { it.chemin == jarTest }
        assertEquals("test", entreeTest.portee)
    }

    // ------------------------------------------------------------------
    // Conflits de versions — « plus récent gagne ».
    // ------------------------------------------------------------------

    @Test
    fun `conflit de versions - la plus recente gagne et l'ecart est signale`() {
        val ancienne = FixturesClasspath.cheminMaven("com.google.guava", "guava", "33.1.0")
        val recente = FixturesClasspath.cheminMaven("com.google.guava", "guava", "33.4.0")
        val premiere = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        stats.ajouter(ancienne).ajouter(recente).ajouter(premiere)
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            entrees =
                                listOf(
                                    FixturesClasspath.jar(ancienne),
                                    FixturesClasspath.jar(recente),
                                    FixturesClasspath.jar(premiere),
                                ),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val app = projet.modules.single()
        assertTrue("la version la plus récente est conservée", app.classpath.entrees.any { it.chemin == recente })
        assertFalse("la version ancienne est écartée", app.classpath.entrees.any { it.chemin == ancienne })
        assertTrue(
            "l'écart est signalé en avertissement, jamais silencieux",
            app.avertissements.any { it.contains("guava") && it.contains("plus récent gagne") },
        )
        // La bibliothèque sans conflit est intacte.
        assertTrue(app.classpath.entrees.any { it.chemin == premiere })
    }

    @Test
    fun `versions numeriques - 33-10-0 est plus recente que 33-9-0`() {
        assertEquals(
            1,
            ConvertisseurModeleLsp.comparerVersions(listOf(33, 10, 0), listOf(33, 9, 0)),
        )
        assertEquals(
            0,
            ConvertisseurModeleLsp.comparerVersions(listOf(1, 0, 0), listOf(1, 0)),
        )
        assertEquals(
            -1,
            ConvertisseurModeleLsp.comparerVersions(listOf(2, 0), listOf(10, 0)),
        )
    }

    // ------------------------------------------------------------------
    // Dépendance cyclique rejetée.
    // ------------------------------------------------------------------

    @Test
    fun `dependance cyclique rejetee avec avertissement - le graphe reste acyclique`() {
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":a",
                            entrees = listOf(FixturesClasspath.moduleFrere(":b")),
                        ),
                        FixturesClasspath.module(
                            nom = ":b",
                            entrees = listOf(FixturesClasspath.moduleFrere(":a")),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val a = projet.modules.first { it.nom == ":a" }
        val b = projet.modules.first { it.nom == ":b" }
        val totalAretes = a.dependancesModules.size + b.dependancesModules.size
        assertEquals("une seule arête survit au rejet de cycle", 1, totalAretes)
        assertTrue(
            "le rejet est signalé sur le module source",
            a.avertissements.any { it.contains("cyclique") } || b.avertissements.any { it.contains("cyclique") },
        )
    }

    @Test
    fun `une auto-dependance est rejetee`() {
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":a",
                            entrees = listOf(FixturesClasspath.moduleFrere(":a")),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        assertTrue(
            projet.modules
                .single()
                .dependancesModules
                .isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Module ignoré, AAR.
    // ------------------------------------------------------------------

    @Test
    fun `un module ignore garde sa raison - jamais silencieux`() {
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":conteneur",
                            ignore = true,
                            raisonIgnore = "module conteneur sans sources ni classpath",
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val module = projet.modules.single()
        assertTrue(module.ignore)
        assertEquals("module conteneur sans sources ni classpath", module.raisonIgnore)
    }

    @Test
    fun `un AAR normalise livre son jar extrait avec l'aar d'origine en provenance`() {
        val jarExtrait = FixturesClasspath.cheminTransform("material", "1.14.0")
        stats.ajouter(jarExtrait)
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            entrees = listOf(FixturesClasspath.aarNormalise(jarExtrait, "/cache/material-1.14.0.aar")),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val entree =
            projet.modules
                .single()
                .classpath.entrees
                .single()
        assertEquals(KindEntree.JAR, entree.kind)
        assertEquals("/cache/material-1.14.0.aar", entree.aar)
        assertTrue(entree.existe)
    }

    @Test
    fun `un AAR brut est signale - l'index ne saura pas le lire`() {
        stats.ajouter("/libs/brute.aar")
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(
                            nom = ":app",
                            entrees = listOf(FixturesClasspath.aarBrut("/libs/brute.aar")),
                        ),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val app = projet.modules.single()
        assertEquals(
            KindEntree.AAR,
            app.classpath.entrees
                .single()
                .kind,
        )
        assertTrue(app.avertissements.any { it.contains("AAR non normalisé") })
    }

    // ------------------------------------------------------------------
    // CRITÈRE du prompt — empreintes.
    // ------------------------------------------------------------------

    @Test
    fun `le meme classpath produit toujours la meme empreinte`() {
        val jarA = FixturesClasspath.cheminMaven("org.jetbrains", "annotations", "26.0.1")
        val jarB = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        stats.ajouter(jarA).ajouter(jarB)
        val modules =
            listOf(
                FixturesClasspath.module(
                    nom = ":app",
                    entrees = listOf(FixturesClasspath.jar(jarA), FixturesClasspath.jar(jarB)),
                ),
            )
        val premiere = convertisseur.depuisClasspathResult(FixturesClasspath.resultat(modules = modules), stats.lecteur)

        // Même résultat, ordre des modules et des entrées PERMUTÉ : l'ordre
        // canonique (tri par kind puis chemin) rend l'empreinte stable.
        val modulesPermuttes =
            listOf(
                FixturesClasspath.module(
                    nom = ":app",
                    entrees = listOf(FixturesClasspath.jar(jarB), FixturesClasspath.jar(jarA)),
                ),
            )
        val seconde =
            convertisseur.depuisClasspathResult(
                FixturesClasspath.resultat(modules = modulesPermuttes),
                stats.lecteur,
            )

        assertEquals(
            "ordre de résolution différent ⇒ même empreinte de classpath",
            premiere.modules
                .single()
                .classpath.empreinte,
            seconde.modules
                .single()
                .classpath.empreinte,
        )
        assertEquals(premiere.modules.single().empreinte, seconde.modules.single().empreinte)
        assertEquals(premiere.empreinte, seconde.empreinte)
    }

    @Test
    fun `changer une dependance change l'empreinte du SEUL module concerne`() {
        val jarCommun = FixturesClasspath.cheminMaven("org.jetbrains", "annotations", "26.0.1")
        val jarDeB = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        val jarAutre = FixturesClasspath.cheminMaven("commons-io", "commons-io", "2.20.0")
        stats.ajouter(jarCommun).ajouter(jarDeB).ajouter(jarAutre)
        val avant =
            convertisseur.depuisClasspathResult(
                FixturesClasspath.resultat(
                    modules =
                        listOf(
                            FixturesClasspath.module(
                                nom = ":a",
                                entrees = listOf(FixturesClasspath.jar(jarCommun), FixturesClasspath.moduleFrere(":b")),
                            ),
                            FixturesClasspath.module(
                                nom = ":b",
                                entrees = listOf(FixturesClasspath.jar(jarDeB)),
                            ),
                        ),
                ),
                stats.lecteur,
            )

        // La dépendance de :b change (autre jar) : :a dépend de :b par
        // NOM — son empreinte ne doit PAS bouger.
        stats.ajouter(jarAutre)
        val apres =
            convertisseur.depuisClasspathResult(
                FixturesClasspath.resultat(
                    modules =
                        listOf(
                            FixturesClasspath.module(
                                nom = ":a",
                                entrees = listOf(FixturesClasspath.jar(jarCommun), FixturesClasspath.moduleFrere(":b")),
                            ),
                            FixturesClasspath.module(
                                nom = ":b",
                                entrees = listOf(FixturesClasspath.jar(jarAutre)),
                            ),
                        ),
                ),
                stats.lecteur,
            )

        val moduleAAvant = avant.modules.first { it.nom == ":a" }
        val moduleAApres = apres.modules.first { it.nom == ":a" }
        val moduleBAvant = avant.modules.first { it.nom == ":b" }
        val moduleBApres = apres.modules.first { it.nom == ":b" }
        assertEquals("l'empreinte de :a est inchangée (dép. nominale)", moduleAAvant.empreinte, moduleAApres.empreinte)
        assertNotEquals("l'empreinte de :b a changé", moduleBAvant.empreinte, moduleBApres.empreinte)
        assertNotEquals("l'empreinte du projet change avec :b", avant.empreinte, apres.empreinte)
    }

    @Test
    fun `un jar disparu du cache est signale existe=false - jamais masque`() {
        val jarFantome = FixturesClasspath.cheminMaven("junit", "junit", "4.13.2")
        // PAS ajouté aux stats : le fichier n'existe pas.
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(nom = ":app", entrees = listOf(FixturesClasspath.jar(jarFantome))),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        val entree =
            projet.modules
                .single()
                .classpath.entrees
                .single()
        assertFalse(entree.existe)
        assertEquals(0L, entree.taille)
    }

    // ------------------------------------------------------------------
    // Modules triés, avertissements du serveur.
    // ------------------------------------------------------------------

    @Test
    fun `les modules sont tries par nom et portent les avertissements du serveur`() {
        val resultat =
            FixturesClasspath.resultat(
                modules =
                    listOf(
                        FixturesClasspath.module(nom = ":z", avertissements = listOf("dépendance non résolue : x")),
                        FixturesClasspath.module(nom = ":a"),
                    ),
            )

        val projet = convertisseur.depuisClasspathResult(resultat, stats.lecteur)

        assertEquals(listOf(":a", ":z"), projet.modules.map { it.nom })
        assertEquals(listOf("dépendance non résolue : x"), projet.modules[1].avertissements)
        assertNotNull(projet.empreinte)
    }
}
