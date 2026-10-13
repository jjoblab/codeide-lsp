package jo.codeide.lsp.host

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.MoteurLangageXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.api.VueNomsXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests de la FAÇADE de session LSP XML ([SessionLspXml], XE1) : cycle de
 * vie des sessions moteur, CONVERSION du modèle en classpath neutre XML
 * (plateforme = PARENT du android.jar, racines sources de l'union, vue
 * de noms) et tolérance aux sessions défaillantes — le moteur est
 * TOUJOURS factice ici (la traduction LSP4J vit dans
 * XmlLanguageServerTest, le moteur réel dans les suites :lsp:xml).
 */
class SessionLspXmlTest {
    /** Dossier temporaire des racines de projet (Jupiter). */
    @field:TempDir
    public lateinit var dossierTemp: File

    // ── Cycle de vie ───────────────────────────────────────────────────

    @Test
    fun `shutdown - la session moteur est refermee et decomptee`() {
        val moteur = MoteurFactice()
        val facade = SessionLspXml(dossierTemp.resolve("vie"), moteur)
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
        val facade = SessionLspXml(dossierTemp.resolve("rattrapage"), moteur)
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
        val facade = SessionLspXml(dossierTemp.resolve("apres-close"), MoteurFactice())
        facade.close()
        assertThrows(IllegalStateException::class.java) { facade.serveur() }
    }

    @Test
    fun `definition - extension xml routee et transport in-process`() {
        val facade = SessionLspXml(dossierTemp.resolve("definitions"), MoteurFactice())
        try {
            val definition = facade.definition()
            assertEquals("xml", definition.ext)
            val transport = definition.createConnectionProvider("file:///res/layout/main.xml")
            assertNotNull(transport, "transport in-process pour l'extension xml")
            transport.stop()
        } finally {
            facade.close()
        }
    }

    // ── Poussée de classpath (XE1 : stockage, XE2/XE3 consommation) ────

    @Test
    fun `appliquerModele - pousse le classpath aux sessions vivantes ET futures`() {
        val moteur = MoteurFactice()
        val facade = SessionLspXml(dossierTemp.resolve("push"), moteur)
        facade.serveur() // session vivante, non refermée
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        val vue = VueNomsFactice()
        facade.appliquerModele(ProjetLsp(dossierTemp.path, listOf(moduleSimple(racine)), "projet"), vue)
        // Session VIVANTE nourrie.
        val classpath =
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
        assertEquals(listOf(racine), classpath.racinesSources)
        assertEquals(vue, classpath.vueNoms)
        assertNull(classpath.dossierPlateforme)
        // Session FUTURE née nourrie.
        facade.serveur()
        assertEquals(2, moteur.sessions.size)
        assertEquals(1, moteur.sessions[1].classpathsRecus.size)
        facade.close()
    }

    @Test
    fun `appliquerModele - plateforme = PARENT du androidJar du module actif`() {
        val moteur = MoteurFactice()
        val facade = SessionLspXml(dossierTemp.resolve("plateforme"), moteur)
        facade.serveur()
        val plateforme = dossierTemp.resolve("platforms/android-37.2")
        val androidJar =
            plateforme.resolve("android.jar").apply {
                plateforme.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        val racine = dossierTemp.resolve("src").apply { mkdirs() }
        facade.appliquerModele(
            ProjetLsp(
                dossierTemp.path,
                listOf(module(emptyList(), androidJar.path, ignore = false, racine = racine)),
                "projet",
            ),
            null,
        )
        assertEquals(
            plateforme,
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
                .dossierPlateforme,
        )
        facade.close()
    }

    @Test
    fun `appliquerModele - module ignore ecarte, plateforme absente filtree`() {
        val moteur = MoteurFactice()
        val facade = SessionLspXml(dossierTemp.resolve("filtres"), moteur)
        facade.serveur()
        val androidJarAbsent = dossierTemp.resolve("ou/android.jar")
        val racineApp = dossierTemp.resolve("app").apply { mkdirs() }
        val racineIgnoree = dossierTemp.resolve("brouillard").apply { mkdirs() }
        facade.appliquerModele(
            ProjetLsp(
                dossierTemp.path,
                listOf(
                    module(emptyList(), androidJarAbsent.path, ignore = false, racine = racineApp),
                    module(emptyList(), null, ignore = true, racine = racineIgnoree),
                ),
                "projet",
            ),
            null,
        )
        val classpath =
            moteur.sessions
                .single()
                .classpathsRecus
                .single()
        assertNull(classpath.dossierPlateforme, "android.jar inexistant : sans plateforme")
        assertEquals(listOf(racineApp), classpath.racinesSources, "le module ignoré ne contribue pas")
        facade.close()
    }

    @Test
    fun `poussee et fermeture defaillantes - la facade absorbe (journal honnete)`() {
        val moteur = MoteurFautif()
        val facade = SessionLspXml(dossierTemp.resolve("fautif"), moteur)
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

    /** Module JVM paramétrable (plateforme, ignorance, racine). */
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

    /** Moteur FACTICE (cycle de vie isolé) : sessions comptées. */
    private class MoteurFactice : MoteurLangageXml {
        public val sessions: CopyOnWriteArrayList<SessionFactice> = CopyOnWriteArrayList()

        public val sessionsFermees: Int
            get() = sessions.count { it.fermee }

        override val identifiant: String = "moteur-factice"

        override val version: String = "0.0.0-test"

        override val versionMinimaleApp: String = "0.0.0"

        override fun analyserSource(texte: CharSequence): jo.lspxml.parse.DocumentXml =
            jo.lspxml.parse.DocumentXml(null, emptyList(), emptyList(), emptyList(), texte.length)

        override fun creerSession(): SessionLangageXml = SessionFactice().also { sessions.add(it) }
    }

    /** Session factice : journalise, ne lève jamais. */
    private class SessionFactice : SessionLangageXml {
        public var fermee: Boolean = false

        /** Classpaths reçus via definirClasspath (tests de poussée XE1). */
        public val classpathsRecus: CopyOnWriteArrayList<ClasspathXml> = CopyOnWriteArrayList()

        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
        ) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun fermerDocument(uri: String) {
            // Non journalisé : seules les fermetures comptent ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticXml> =
            listOf(
                DiagnosticXml(
                    code = "xml.test",
                    severite = SeveriteXml.ERREUR,
                    message = "factice",
                    debut = 0,
                    fin = 1,
                ),
            )

        override fun definirClasspath(classpath: ClasspathXml) {
            classpathsRecus.add(classpath)
        }

        override fun close() {
            fermee = true
        }
    }

    /**
     * Moteur FAUTIF : chaque session lève à la poussée de classpath ET à
     * la fermeture — la façade doit absorber les deux (journal honnête,
     * jamais d'exception vers l'appelant).
     */
    private class MoteurFautif : MoteurLangageXml {
        public val sessions: CopyOnWriteArrayList<SessionFautive> = CopyOnWriteArrayList()

        public val sessionsCrees: Int
            get() = sessions.size

        override val identifiant: String = "moteur-fautif"

        override val version: String = "0.0.0-test"

        override val versionMinimaleApp: String = "0.0.0"

        override fun analyserSource(texte: CharSequence): jo.lspxml.parse.DocumentXml =
            jo.lspxml.parse.DocumentXml(null, emptyList(), emptyList(), emptyList(), texte.length)

        override fun creerSession(): SessionLangageXml = SessionFautive().also { sessions.add(it) }
    }

    /** Session qui lève sur definirClasspath et sur close. */
    private class SessionFautive : SessionLangageXml {
        override val nombreDocuments: Int = 0

        override fun ouvrirDocument(
            uri: String,
            texte: CharSequence,
        ) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun modifierDocument(
            uri: String,
            texte: CharSequence,
        ) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun fermerDocument(uri: String) {
            // Non sollicité : seule la poussée compte ici.
        }

        override fun diagnostics(uri: String): List<DiagnosticXml> = emptyList()

        override fun definirClasspath(classpath: ClasspathXml): Unit =
            throw java.io.IOException("poussée impossible (test)")

        override fun close(): Unit = throw java.io.IOException("fermeture impossible (test)")
    }

    /** Vue de noms factice (identité pour l'assertion de poussée). */
    private class VueNomsFactice : VueNomsXml {
        override fun nomsCommencantPar(
            prefixe: String,
            limite: Int,
        ): List<String> = emptyList()
    }
}
