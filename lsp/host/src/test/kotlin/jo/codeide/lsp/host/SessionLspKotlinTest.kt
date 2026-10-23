package jo.codeide.lsp.host

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.PreviewKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SeveriteKotlin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests de la FAÇADE de session LSP Kotlin ([SessionLspKotlin], K3) :
 * cycle de vie des sessions moteur, CONVERSION du classpath tooling en
 * entrées neutres (jars/AAR d'existence vérifiée, racines sources,
 * plateforme android.jar) et tolérance aux sessions défaillantes — le
 * moteur est TOUJOURS factice ici (la traduction LSP4J vit dans
 * KotlinLanguageServerTest, le moteur réel dans les suites :lsp:kotlin).
 */
class SessionLspKotlinTest {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    // ── Cycle de vie ───────────────────────────────────────────────────

    @Test
    fun `shutdown - la session moteur est refermee et decomptee`() {
        // Moteur FACTICE (cycle de vie isolé) : la façade ne doit retenir
        // QUE les sessions des connexions vivantes.
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("vie"), moteur, dossierIndex = null)
        val serveur = facade.serveur()
        serveur.shutdown().join()
        assertEquals(1, moteur.sessionsFermees)
        // La façade ne referme PAS deux fois une session déjà arrêtée.
        facade.close()
        assertEquals(1, moteur.sessionsFermees)
    }

    @Test
    fun `close rattrape les connexions jamais arretees`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("rattrapage"), moteur, dossierIndex = null)
        facade.serveur()
        facade.serveur()
        facade.close()
        assertEquals(2, moteur.sessionsFermees)
        // Idempotente : un second close ne referme rien de plus.
        facade.close()
        assertEquals(2, moteur.sessionsFermees)
    }

    @Test
    fun `serveur apres close - erreur explicite`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("apres-close"), MoteurFactice(), dossierIndex = null)
        facade.close()
        org.junit.jupiter.api.Assertions
            .assertThrows(IllegalStateException::class.java) { facade.serveur() }
    }

    @Test
    fun `definitions - deux extensions routees et transport in-process`() {
        val facade = SessionLspKotlin(dossierTemp.resolve("definitions"), MoteurFactice(), dossierIndex = null)
        try {
            val definitions = facade.definitions()
            assertEquals(2, definitions.size)
            val extensions = definitions.map { it.ext }
            assertTrue(extensions.contains("kt"))
            assertTrue(extensions.contains("kts"))
            // Chaque définition déroge un transport in-process (cel-lsp
            // approvisionne par le supplier de la définition).
            definitions.forEach { definition ->
                val transport = definition.createConnectionProvider("file:///x.${definition.ext}")
                assertNotNull(transport, "transport in-process par extension")
                transport.stop()
            }
        } finally {
            facade.close()
        }
    }

    // ── Previews Compose (C1 — passe-plat vers les sessions vivantes) ─

    @Test
    fun `previews - la session detenant le document repond, vide sinon`() {
        val moteur =
            MoteurFactice().apply {
                previewsDesSessions =
                    mapOf(
                        "file:///ecran.kt" to
                            listOf(
                                PreviewKotlin(
                                    nom = "ApercuEcran",
                                    paquet = "com.ex.app",
                                    debutNom = 0,
                                    finNom = 0,
                                    parametres = emptyList(),
                                    corps = emptyList(),
                                ),
                            ),
                    )
            }
        val facade = SessionLspKotlin(dossierTemp.resolve("previews"), moteur, dossierIndex = null)
        // Aucune session vivante : VIDE, jamais d'exception.
        assertTrue(facade.previews("file:///ecran.kt").isEmpty())
        facade.serveur() // session vivante née avec les previews factices
        assertEquals(
            listOf("ApercuEcran"),
            facade.previews("file:///ecran.kt").map { it.nom },
        )
        // URI détenue par AUCUNE session : VIDE.
        assertTrue(facade.previews("file:///autre.kt").isEmpty())
        facade.close()
    }

    // ── Poussée de classpath (K3) ──────────────────────────────────────

    @Test
    fun `appliquerModele - pousse le classpath aux sessions vivantes ET futures`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("push"), moteur, dossierIndex = null)
        facade.serveur() // session vivante, non refermée
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        facade.appliquerModele(
            ProjetLsp(dossierTemp.resolve("push").path, listOf(moduleSimple(racine)), "projet"),
            null,
        )
        // Session VIVANTE nourrie.
        assertEquals(
            1,
            moteur.sessions
                .single()
                .classpathsRecus.size,
        )
        assertEquals(
            listOf(racine),
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
                .racinesSources,
        )
        // Session FUTURE née nourrie.
        facade.serveur()
        assertEquals(2, moteur.sessions.size)
        assertEquals(1, moteur.sessions[1].classpathsRecus.size)
        facade.close()
    }

    @Test
    fun `appliquerClasspath - convertit le resultat tooling et pousse aux sessions`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("appliquer"), moteur, dossierIndex = null)
        facade.serveur()
        val jar = dossierTemp.resolve("vrai.jar")
        jar.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        val resultat = resultatSimple(jar, racine)
        facade.appliquerClasspath(resultat, null)
        val classpath =
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
        assertEquals(listOf(racine), classpath.racinesSources)
        assertEquals(1, classpath.entrees.size)
        assertEquals(KindEntreeKotlin.JAR, classpath.entrees.single().kind)
        assertEquals(jar.path, classpath.entrees.single().chemin)
        facade.close()
    }

    @Test
    fun `appliquerModele - AAR garde, entree absente filtree, plateforme ajoutee, ignore ecarte`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("filtres"), moteur, dossierIndex = null)
        facade.serveur()
        val jarExistant = dossierTemp.resolve("vrai.jar").apply { writeBytes(byteArrayOf(1)) }
        val aarExistant = dossierTemp.resolve("vrai.aar").apply { writeBytes(byteArrayOf(2)) }
        val jarAbsent = dossierTemp.resolve("absent.jar")
        val plateforme = dossierTemp.resolve("android.jar").apply { writeBytes(byteArrayOf(3)) }
        val racineApp = dossierTemp.resolve("app").apply { mkdirs() }
        val racineIgnoree = dossierTemp.resolve("brouillard").apply { mkdirs() }
        val entrees =
            listOf(
                entreeNeutre(jo.codeide.lsp.model.KindEntree.JAR, jarExistant.path, "e-jar"),
                entreeNeutre(jo.codeide.lsp.model.KindEntree.AAR, aarExistant.path, "e-aar"),
                entreeNeutre(jo.codeide.lsp.model.KindEntree.JAR, jarAbsent.path, "e-absent"),
            )
        val projet =
            ProjetLsp(
                dossierTemp.path,
                listOf(
                    module(entrees, plateforme.path, ignore = false, racine = racineApp),
                    module(emptyList(), null, ignore = true, racine = racineIgnoree),
                ),
                "projet",
            )
        facade.appliquerModele(projet, null)
        val classpath =
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
        // Le jar ABSENT est filtré (existence vérifiée), l'AAR est gardé,
        // la plateforme rejoint l'union, le module ignoré n'y contribue pas.
        val chemins = classpath.entrees.map { it.chemin }
        assertEquals(listOf(jarExistant.path, aarExistant.path, plateforme.path), chemins)
        assertEquals(
            listOf(KindEntreeKotlin.JAR, KindEntreeKotlin.AAR, KindEntreeKotlin.JAR),
            classpath.entrees.map { it.kind },
        )
        assertEquals(listOf(racineApp), classpath.racinesSources)
        facade.close()
    }

    @Test
    fun `poussee et fermeture defaillantes - la facade absorbe (journal honnete)`() {
        val moteur = MoteurFautif()
        val facade = SessionLspKotlin(dossierTemp.resolve("fautif"), moteur, dossierIndex = null)
        facade.serveur()
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        // La poussée lève : la façade journalise et POURSUIT (jamais
        // d'exception vers l'appelant, le régime dégradé est contractuel).
        facade.appliquerModele(ProjetLsp(dossierTemp.path, listOf(moduleSimple(racine)), "projet"), null)
        assertEquals(1, moteur.sessionsCrees)
        // La fermeture de session lève aussi : absorbée de la même façon.
        facade.close()
        assertEquals(1, moteur.sessionsCrees, "la session reste décomptée une seule fois")
    }

    // ── Aides de montage ───────────────────────────────────────────────

    /** Module JVM simple sans classpath (une racine source suffit). */
    private fun moduleSimple(racine: File): ModuleLsp = module(emptyList(), null, ignore = false, racine = racine)

    /** Module JVM paramétrable (entrées, plateforme, ignorance, racine). */
    private fun module(
        entrees: List<jo.codeide.lsp.model.EntreeClasspathInstantane>,
        androidJar: String?,
        ignore: Boolean,
        racine: File,
    ): ModuleLsp =
        ModuleLsp(
            nom = ":${racine.name}",
            type = TypeModuleLsp.JVM,
            variante = null,
            sources = EnsemblesSources(principal = listOf(racine.path), test = emptyList(), generes = emptyList()),
            classpath = ClasspathInstantane(entrees, "instantane"),
            dependancesModules = emptyList(),
            androidJar = androidJar,
            ignore = ignore,
            raisonIgnore = if (ignore) "variante non résolue" else null,
            avertissements = emptyList(),
            empreinte = "module-${racine.name}",
        )

    /** Entrée du modèle (empreinte et existence contrôlées par l'appelant). */
    private fun entreeNeutre(
        kind: jo.codeide.lsp.model.KindEntree,
        chemin: String,
        empreinte: String,
    ): jo.codeide.lsp.model.EntreeClasspathInstantane =
        jo.codeide.lsp.model.EntreeClasspathInstantane(
            kind = kind,
            chemin = chemin,
            portee = "compile",
            sources = null,
            aar = null,
            empreinte = empreinte,
            existe = true,
            taille = 1,
            mtime = 0,
        )

    /** Résultat tooling minimal : un module, un jar, une racine. */
    private fun resultatSimple(
        jar: File,
        racine: File,
    ): ClasspathResult =
        ClasspathResult(
            id = "k3",
            protocolVersion = 1,
            projectDir = dossierTemp.path,
            modules =
                listOf(
                    ClasspathModule(
                        name = ":lib",
                        sourceDirs = listOf(racine.toString()),
                        entries = listOf(ClasspathEntry(jar.toString(), ClasspathKind.JAR, "compile")),
                    ),
                ),
        )

    /** Moteur factice : sessions factices comptant les fermetures. */
    private class MoteurFactice : MoteurLangageKotlin {
        public val sessionsFermees: Int
            get() = sessions.count { it.fermee }

        public val sessions: CopyOnWriteArrayList<SessionFactice> = CopyOnWriteArrayList()

        /** À poser AVANT serveur() : les sessions futures naîtront avec. */
        public var previewsDesSessions: Map<String, List<PreviewKotlin>> = emptyMap()

        override val identifiant: String = "moteur-factice"

        override val version: String = "0.0.0-test"

        override val versionMinimaleApp: String = "0.0.0"

        override val versionGrammaire: String = "test"

        override fun analyserSource(
            texte: CharSequence,
            script: Boolean,
        ): jo.lspkotlin.parse.ArbreSyntaxiqueKotlin = ServiceMoteurLangageKotlin.unique().analyserSource(texte, script)

        override fun creerSession(): SessionLangageKotlin =
            SessionFactice().also {
                it.previewsRendues = previewsDesSessions
                sessions.add(it)
            }
    }

    /** Session factice : journalise, ne lève jamais. */
    private class SessionFactice : SessionLangageKotlin {
        public var fermee: Boolean = false

        /** Classpaths reçus via definirClasspath (tests de poussée K3). */
        public val classpathsRecus: CopyOnWriteArrayList<ClasspathKotlin> = CopyOnWriteArrayList()

        /** Previews rendues par previews(uri) (test de passe-plat C1). */
        public var previewsRendues: Map<String, List<PreviewKotlin>> = emptyMap()

        override fun previews(uri: String): List<PreviewKotlin> = previewsRendues[uri] ?: emptyList()

        override fun definirClasspath(classpath: ClasspathKotlin) {
            classpathsRecus.add(classpath)
        }

        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun fermerDocument(uri: String) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticKotlin> =
            listOf(
                DiagnosticKotlin(
                    code = "kt.test",
                    severite = SeveriteKotlin.ERREUR,
                    message = "factice",
                    debut = 0,
                    fin = 1,
                ),
            )

        override fun close() {
            fermee = true
        }
    }

    /**
     * Moteur FAUTIF : chaque session lève à la poussée de classpath ET à
     * la fermeture — la façade doit absorber les deux (journal honnête,
     * jamais d'exception vers l'appelant).
     */
    private class MoteurFautif : MoteurLangageKotlin {
        public val sessionsCrees: Int
            get() = sessions.size

        public val sessions: CopyOnWriteArrayList<SessionFautive> = CopyOnWriteArrayList()

        override val identifiant: String = "moteur-fautif"

        override val version: String = "0.0.0-test"

        override val versionMinimaleApp: String = "0.0.0"

        override val versionGrammaire: String = "test"

        override fun analyserSource(
            texte: CharSequence,
            script: Boolean,
        ): jo.lspkotlin.parse.ArbreSyntaxiqueKotlin = ServiceMoteurLangageKotlin.unique().analyserSource(texte, script)

        override fun creerSession(): SessionLangageKotlin = SessionFautive().also { sessions.add(it) }
    }

    /** Session qui lève sur definirClasspath et sur close. */
    private class SessionFautive : SessionLangageKotlin {
        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
            script: Boolean,
        ) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun fermerDocument(uri: String) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticKotlin> = emptyList()

        override fun definirClasspath(classpath: ClasspathKotlin): Unit =
            throw java.io.IOException("poussée impossible (test)")

        override fun close(): Unit = throw java.io.IOException("fermeture impossible (test)")
    }
}
