package jo.codeide.lsp.host

import jo.codeide.lsp.kotlin.api.ClasspathKotlin
import jo.codeide.lsp.kotlin.api.GenreContexteKotlin
import jo.codeide.lsp.kotlin.api.PreviewKotlin
import jo.codeide.lsp.kotlin.api.ReponseCompletionKotlin
import jo.codeide.lsp.kotlin.api.ServiceMoteurLangageKotlin
import jo.codeide.lsp.kotlin.api.SessionLangageKotlin
import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Tests de la FAÇADE de session LSP Kotlin ([SessionLspKotlin], K3 —
 * bascule ADR 0136) : cycle de vie des sessions du PACK NEUF (créées à
 * `initialize` par la fabrique, refermées par `exit`, rattrapées par
 * [SessionLspKotlin.close]), CONVERSION du classpath tooling en entrées
 * neutres du pack (jars/AAR d'existence vérifiée, racines sources,
 * plateforme android.jar), nourrissage moteur ADR 0211 (guichet unique
 * par sync, tolérance au pack non nourricier) et tolérance aux sessions
 * défaillantes — le moteur est TOUJOURS factice ici (la traduction LSP4J
 * vit dans la suite du pack côté code-lsp : protocole + harnais
 * BoucleLocale + 7 cas dorés ; la chaîne complète côté hôte vit dans les
 * suites E2E du harnais).
 */
class SessionLspKotlinTest {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    // ── Cycle de vie ───────────────────────────────────────────────────

    @Test
    fun `shutdown-exit - la session moteur est refermee et decomptee`() {
        // Moteur FACTICE (cycle de vie isolé) : la façade ne retient QUE
        // les sessions des connexions vivantes — la session naît à
        // `initialize` (fabrique du serveur du pack) et meurt à `exit`.
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("vie"), moteur, dossierIndex = null)
        val serveur = facade.serveurInitialise()
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
        assertEquals(1, moteur.sessionsFermees)
        // La façade ne referme PAS deux fois une session déjà arrêtée.
        facade.close()
        assertEquals(1, moteur.sessionsFermees)
    }

    @Test
    fun `close rattrape les connexions jamais arretees`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("rattrapage"), moteur, dossierIndex = null)
        facade.serveurInitialise()
        facade.serveurInitialise()
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
                                    plageNom =
                                        jo.codeide.lsp.kotlin.parse.kt
                                            .PlageTexte(0, 0),
                                    parametres = emptyList(),
                                    corps = emptyList(),
                                    coupureBudget = false,
                                    ligneAnnotation = 0,
                                ),
                            ),
                    )
            }
        val facade = SessionLspKotlin(dossierTemp.resolve("previews"), moteur, dossierIndex = null)
        // Aucune session vivante : VIDE, jamais d'exception.
        assertTrue(facade.previews("file:///ecran.kt").isEmpty())
        facade.serveurInitialise() // session vivante née avec les previews factices
        assertEquals(
            listOf("ApercuEcran"),
            facade.previews("file:///ecran.kt").map { it.nom },
        )
        // URI détenue par AUCUNE session : VIDE.
        assertTrue(facade.previews("file:///autre.kt").isEmpty())
        facade.close()
    }

    // ── Nourrissage moteur (K3 — ADR 0211) ────────────────────────────

    @Test
    fun `appliquerModele - confie le classpath au moteur, les sessions futures naissent nourries`() {
        val moteur = MoteurFactice() // nourricier : definirClasspath rend vrai
        val facade = SessionLspKotlin(dossierTemp.resolve("push"), moteur, dossierIndex = null)
        facade.serveurInitialise() // session vivante, non refermée
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        facade.appliquerModele(ProjetLsp(dossierTemp.resolve("push").path, listOf(moduleSimple(racine)), "projet"))
        // Le MOTEUR a reçu LE classpath (guichet ADR 0211, une fois par
        // sync) — racines en FORME NEUTRE du pack (chemins absolus).
        assertEquals(1, moteur.classpathsRecus.size)
        assertEquals(
            listOf(racine.absolutePath),
            moteur.classpathsRecus.single().racinesSources,
        )
        // Session VIVANTE : PAS de poussée par session (le pack est
        // nourricier — la définition y est un BRANCHEMENT, la requête
        // consulte l'état publié par le moteur ; la session n'a reçu que
        // le classpath de SA naissance).
        assertEquals(
            1,
            moteur.sessions
                .single()
                .classpathsRecus.size,
        )
        // Session FUTURE née nourrie du classpath COURANT.
        facade.serveurInitialise()
        assertEquals(2, moteur.sessions.size)
        assertEquals(
            listOf(racine.absolutePath),
            moteur.sessions[1]
                .classpathsRecus
                .single()
                .racinesSources,
        )
        facade.close()
    }

    @Test
    fun `appliquerClasspath - convertit le resultat tooling et le confie au moteur`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("appliquer"), moteur, dossierIndex = null)
        val jar = dossierTemp.resolve("vrai.jar")
        jar.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        val resultat = resultatSimple(jar, racine)
        facade.appliquerClasspath(resultat)
        val classpath = moteur.classpathsRecus.single()
        assertEquals(listOf(racine.absolutePath), classpath.racinesSources)
        assertEquals(1, classpath.entrees.size)
        assertEquals(jo.codeide.lsp.kotlin.api.KindEntreeKotlin.JAR, classpath.entrees.single().kind)
        assertEquals(jar.path, classpath.entrees.single().chemin)
        facade.close()
    }

    @Test
    fun `appliquerModele - AAR garde, entree absente filtree, plateforme ajoutee, ignore ecarte`() {
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("filtres"), moteur, dossierIndex = null)
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
        facade.appliquerModele(projet)
        val classpath = moteur.classpathsRecus.single()
        // Le jar ABSENT est filtré (existence vérifiée), l'AAR est gardé,
        // la plateforme rejoint l'union, le module ignoré n'y contribue pas.
        val chemins = classpath.entrees.map { it.chemin }
        assertEquals(listOf(jarExistant.path, aarExistant.path, plateforme.path), chemins)
        assertEquals(
            listOf(
                jo.codeide.lsp.kotlin.api.KindEntreeKotlin.JAR,
                jo.codeide.lsp.kotlin.api.KindEntreeKotlin.AAR,
                jo.codeide.lsp.kotlin.api.KindEntreeKotlin.JAR,
            ),
            classpath.entrees.map { it.kind },
        )
        assertEquals(listOf(racineApp.absolutePath), classpath.racinesSources)
        facade.close()
    }

    @Test
    fun `pack non nourricier - la poussee synchrone aux sessions reste LE nourrissage`() {
        // Tolérance de version du contrat D6 : un pack exotique dont le
        // guichet rend `false` retombe sur la poussée SYNCHRONE par
        // session (le monde d'avant).
        val moteur = MoteurFactice().apply { nourricier = false }
        val facade = SessionLspKotlin(dossierTemp.resolve("exotique"), moteur, dossierIndex = null)
        facade.serveurInitialise()
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        facade.appliquerModele(ProjetLsp(dossierTemp.path, listOf(moduleSimple(racine)), "projet"))
        assertEquals(1, moteur.classpathsRecus.size)
        // La session VIVANTE a reçu la poussée synchrone (naissance + sync).
        assertEquals(
            2,
            moteur.sessions
                .single()
                .classpathsRecus.size,
        )
        assertEquals(
            listOf(racine.absolutePath),
            moteur.sessions
                .single()
                .classpathsRecus
                .last()
                .racinesSources,
        )
        facade.close()
    }

    @Test
    fun `poussee et fermeture defaillantes - la facade absorbe (journal honnete)`() {
        val moteur = MoteurFautif()
        val facade = SessionLspKotlin(dossierTemp.resolve("fautif"), moteur, dossierIndex = null)
        facade.serveurInitialise()
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        // La poussée lève : la façade journalise et POURSUIT (jamais
        // d'exception vers l'appelant, le régime dégradé est contractuel).
        facade.appliquerModele(ProjetLsp(dossierTemp.path, listOf(moduleSimple(racine)), "projet"))
        assertEquals(1, moteur.sessionsCrees)
        // La fermeture de session lève aussi : absorbée de la même façon.
        facade.close()
        assertEquals(1, moteur.sessionsCrees, "la session reste décomptée une seule fois")
    }

    @Test
    fun `fenetre d'indexation - CONSTRUCTION a la pose, PRET au retour du guichet`() {
        // Bascule ADR 0136 : le pack neuf n'expose pas la progression
        // fine — l'état public est une FENÊTRE (CONSTRUCTION → PRET au
        // verdict du guichet moteur).
        val moteur = MoteurFactice()
        val facade = SessionLspKotlin(dossierTemp.resolve("fenetre"), moteur, dossierIndex = null)
        assertEquals(PhaseIndexation.INITIAL, facade.etatIndexation.value.phase)
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        facade.appliquerModele(ProjetLsp(dossierTemp.path, listOf(moduleSimple(racine)), "projet"))
        assertEquals(PhaseIndexation.PRET, facade.etatIndexation.value.phase)
        facade.close()
    }

    // ── Aides de montage ───────────────────────────────────────────────

    /** Serveur du pack INITIALISÉ : la session naît (fabrique) et devient vivante. */
    private fun SessionLspKotlin.serveurInitialise(): LanguageServer {
        val serveur = serveur()
        serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        serveur.initialized(InitializedParams())
        return serveur
    }

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

    /** Moteur factice (SPI du pack) : sessions factices + guichet compté. */
    private class MoteurFactice : ServiceMoteurLangageKotlin {
        public val sessionsFermees: Int
            get() = sessions.count { it.fermee }

        public val sessions: CopyOnWriteArrayList<SessionFactice> = CopyOnWriteArrayList()

        /** À poser AVANT serveur() : les sessions futures naîtront avec. */
        public var previewsDesSessions: Map<String, List<PreviewKotlin>> = emptyMap()

        /** Verdict du guichet definirClasspath (nourricier par défaut). */
        public var nourricier: Boolean = true

        /** Classpaths confiés au MOTEUR (guichet ADR 0211). */
        public val classpathsRecus: CopyOnWriteArrayList<ClasspathKotlin> = CopyOnWriteArrayList()

        override val identifiant: String = "pack-factice"

        override val version: String = "0.0.0-test"

        override val versionGrammaire: String = "test"

        override val versionMinimaleApp: String = "0.0.0"

        override fun creerSession(): SessionLangageKotlin =
            SessionFactice().also {
                it.previewsRendues = previewsDesSessions
                sessions.add(it)
            }

        override fun definirClasspath(classpath: ClasspathKotlin): Boolean {
            classpathsRecus.add(classpath)
            return nourricier
        }
    }

    /**
     * Session factice (SPI du pack) : journalise classpaths/previews,
     * ne lève jamais — les méthodes d'analyse rendent les réponses VIDES
     * honnêtes du contrat (listes vides, optionnelles nulles) : la façade
     * et le serveur du pack n'en consomment que le cycle de vie, la
     * fabrique et le guichet ici (l'inférence de type des corps
     * d'expression évite d'énumérer les types de retour inutilisés).
     */
    private open class SessionFactice : SessionLangageKotlin {
        public var fermee: Boolean = false

        /** Classpaths reçus via definirClasspath (naissance + syncs). */
        public val classpathsRecus: CopyOnWriteArrayList<ClasspathKotlin> = CopyOnWriteArrayList()

        /** Previews rendues par previews(uri) (test de passe-plat C1). */
        public var previewsRendues: Map<String, List<PreviewKotlin>> = emptyMap()

        override fun ouvrir(
            uri: String,
            texte: CharSequence,
        ) {
            // Non journalisé : seuls les classpaths comptent ici.
        }

        override fun modifier(
            uri: String,
            texte: CharSequence,
        ) {
            // Non journalisé : seuls les classpaths comptent ici.
        }

        override fun fermer(uri: String) {
            // Non journalisé : seuls les classpaths comptent ici.
        }

        override fun rafraichirSources() {
            // No-op factice (l'amorce du scan du serveur l'appelle).
        }

        override fun definirClasspath(classpath: ClasspathKotlin) {
            classpathsRecus.add(classpath)
        }

        override fun previews(uri: String): List<PreviewKotlin> = previewsRendues[uri] ?: emptyList()

        override fun diagnostics(uri: String): List<jo.codeide.lsp.kotlin.api.DiagnosticKotlin> = emptyList()

        override fun plagesPliables(uri: String): List<jo.codeide.lsp.kotlin.api.PlagePliableKotlin> = emptyList()

        override fun formatage(
            uri: String,
            tailleIndentation: Int,
        ): List<jo.codeide.lsp.kotlin.api.EditionTexteKotlin> = emptyList()

        override fun formatagePlage(
            uri: String,
            debutPlage: Int,
            finPlage: Int,
            tailleIndentation: Int,
        ): List<jo.codeide.lsp.kotlin.api.EditionTexteKotlin> = emptyList()

        override fun survol(
            uri: String,
            deplacement: Int,
        ) = null

        override fun completion(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.PropositionKotlin> = emptyList()

        override fun completionArrangee(
            uri: String,
            deplacement: Int,
        ): ReponseCompletionKotlin = ReponseCompletionKotlin(GenreContexteKotlin.NOM, emptyList(), 0)

        override fun importsCandidats(
            uri: String,
            deplacement: Int,
        ) = emptyList<String>()

        override fun correctifsTexte(uri: String): List<jo.codeide.lsp.kotlin.api.CorrectifTexteKotlin> = emptyList()

        override fun membresManquants(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.CorrectifTexteKotlin> = emptyList()

        override fun definition(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun symboles(uri: String): List<jo.codeide.lsp.kotlin.api.SymboleDocumentKotlin> = emptyList()

        override fun occurrences(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun references(
            uri: String,
            deplacement: Int,
            inclureDeclaration: Boolean,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun surlignagesDocument(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.SurlignageDocumentKotlin> = emptyList()

        override fun plageRenommable(
            uri: String,
            deplacement: Int,
        ) = null

        override fun renommage(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun typeDefinition(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun implementations(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.EmplacementKotlin> = emptyList()

        override fun superDefinition(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.CibleSuperKotlin> = emptyList()

        override fun actions(
            uri: String,
            deplacement: Int,
        ): List<jo.codeide.lsp.kotlin.api.PlanActionKotlin> = emptyList()

        override fun indices(
            uri: String,
            debutPlage: Int,
            finPlage: Int,
        ): List<jo.codeide.lsp.kotlin.api.IndiceKotlin> = emptyList()

        override fun symbolesEspace(requete: String): List<jo.codeide.lsp.kotlin.api.SymboleEspaceKotlin> = emptyList()

        override fun selections(
            uri: String,
            deplacements: List<Int>,
        ): List<jo.codeide.lsp.kotlin.api.ChaineSelectionKotlin> = emptyList()

        override fun signature(
            uri: String,
            deplacement: Int,
        ) = null

        override fun close() {
            fermee = true
        }
    }

    /**
     * Moteur FAUTIF : chaque session lève à la poussée de classpath ET à
     * la fermeture — la façade doit absorber les deux (journal honnête,
     * jamais d'exception vers l'appelant).
     */
    private class MoteurFautif : ServiceMoteurLangageKotlin {
        public val sessionsCrees: Int
            get() = sessions.size

        public val sessions: CopyOnWriteArrayList<SessionFautive> = CopyOnWriteArrayList()

        override val identifiant: String = "pack-fautif"

        override val version: String = "0.0.0-test"

        override val versionGrammaire: String = "test"

        override val versionMinimaleApp: String = "0.0.0"

        override fun creerSession(): SessionLangageKotlin = SessionFautive().also { sessions.add(it) }

        override fun definirClasspath(classpath: ClasspathKotlin): Boolean =
            throw java.io.IOException("guichet impossible (test)")
    }

    /** Session qui lève sur definirClasspath et sur close. */
    private class SessionFautive : SessionFactice() {
        override fun definirClasspath(classpath: ClasspathKotlin): Unit =
            throw java.io.IOException("poussée impossible (test)")

        override fun close(): Unit = throw java.io.IOException("fermeture impossible (test)")
    }
}
