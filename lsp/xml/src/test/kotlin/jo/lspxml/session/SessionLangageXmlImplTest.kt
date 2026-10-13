package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.CodesDiagnosticsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la session de langue XML (XE1) — cycle de vie, tolérance du
 * contrat (documents inconnus, remplacements, fermetures idempotentes)
 * et diagnostics recalculés au fil de l'eau.
 */
class SessionLangageXmlImplTest {
    /** Dossiers temporaires (JUnit4 — plateforme fixture XE2). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val session = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())

    @Test
    fun `ouvrir et diagnostiquer un layout`() {
        session.ouvrirDocument(
            "file:///p/res/layout/main.xml",
            "<LinearLayout android:id=\"@+id/x\"><Button /></LinearLayout>",
        )
        val diagnostics = session.diagnostics("file:///p/res/layout/main.xml")
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID })
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.TAILLES_MANQUANTES })
    }

    @Test
    fun `diagnostics ordonnes par position`() {
        session.ouvrirDocument(
            "file:///p/res/layout/main.xml",
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\">" +
                "<b android:layout_width=\"1\" android:layout_height=\"2\" x=\"1\" x=\"2\"/></r>&",
        )
        val diagnostics = session.diagnostics("file:///p/res/layout/main.xml")
        assertTrue(diagnostics.map { it.debut }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `didChange remplace le texte — diagnostics vivants`() {
        val uri = "file:///p/res/layout/main.xml"
        val saine =
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\">" +
                "<b android:layout_width=\"1\" android:layout_height=\"2\" x=\"1\"/></r>"
        session.ouvrirDocument(uri, saine.replace("x=\"1\"/>", "x=\"1\" x=\"2\"/>"))
        assertEquals(1, session.diagnostics(uri).count { it.code == CodesDiagnosticsXml.ATTRIBUT_DUPLIQUE })
        session.modifierDocument(uri, saine)
        assertEquals(0, session.diagnostics(uri).size)
    }

    @Test
    fun `modifier un document inconnu equivaut a l'ouvrir`() {
        val uri = "file:///p/res/values/strings.xml"
        session.modifierDocument(uri, "<resources><string/></resources>")
        assertEquals(1, session.diagnostics(uri).size)
        assertEquals(1, session.nombreDocuments)
    }

    @Test
    fun `reouvrir remplace — pas de fuite de documents`() {
        val uri = "file:///p/res/values/strings.xml"
        session.ouvrirDocument(uri, "<resources/>")
        session.ouvrirDocument(uri, "<resources><string/></resources>")
        assertEquals(1, session.nombreDocuments)
        assertEquals(1, session.diagnostics(uri).size)
    }

    @Test
    fun `document inconnu ou ferme — liste vide, jamais d'exception`() {
        assertTrue(session.diagnostics("file:///p/inconnu.xml").isEmpty())
        session.ouvrirDocument("file:///p/a.xml", "<a/>")
        session.fermerDocument("file:///p/a.xml")
        assertTrue(session.diagnostics("file:///p/a.xml").isEmpty())
        assertEquals(0, session.nombreDocuments)
        // Fermer un document inconnu : sans effet.
        session.fermerDocument("file:///p/rien.xml")
        assertEquals(0, session.nombreDocuments)
    }

    @Test
    fun `texte invalide ne leve jamais`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<<<>>>&\"'")
        // Le contrat X1 : aucun rejet, un diagnostic ou rien — jamais
        // une exception vers l'hôte.
        session.diagnostics(uri)
        assertTrue(true)
    }

    @Test
    fun `definirClasspath — stocke puis vide a la fermeture`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<a/>")
        val classpath = ClasspathXml(null, emptyList(), null)
        session.definirClasspath(classpath)
        assertEquals(classpath, (session as SessionLangageXmlImpl).classpathCourant())
        session.close()
        assertNull(session.classpathCourant())
    }

    @Test
    fun `close — idempotente et definitive`() {
        val uri = "file:///p/res/layout/x.xml"
        session.ouvrirDocument(uri, "<a/>")
        session.close()
        session.close()
        assertEquals(0, session.nombreDocuments)
        // Après close : ouvrir/définir sont des NO-OP honnêtes.
        session.ouvrirDocument(uri, "<b/>")
        session.definirClasspath(ClasspathXml(null, emptyList(), null))
        assertEquals(0, session.nombreDocuments)
        assertNull(session.classpathCourant())
    }

    @Test
    fun `genre deduit par document — regles par uri`() {
        session.ouvrirDocument("file:///p/res/values/strings.xml", "<resources><string/></resources>")
        session.ouvrirDocument("file:///p/res/layout/main.xml", "<merge/>")
        assertTrue(session.diagnostics("file:///p/res/values/strings.xml").isNotEmpty())
        assertTrue(session.diagnostics("file:///p/res/layout/main.xml").isEmpty())
    }

    // ── Complétion de VALEURS, survol, définitions (XE3) ─────────

    /** Racine source avec res/ + plateforme + classpath complets. */
    private fun projetComplet(): Pair<File, File> {
        val racine = dossierTemp.newFolder("src")
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText(
            "<resources><string name=\"hello\">Salut</string></resources>",
        )
        racine.resolve("res/layout").apply { mkdirs() }
            .resolve("principal.xml").writeText("<merge/>")
        return racine to plateformeFixture()
    }

    @Test
    fun `completion valeurs — references du projet et enums du catalogue`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        // Valeur en frappe : `@` seul → les TYPES du projet.
        session.ouvrirDocument(uri, "<Button android:text=\"@str")
        val types = session.completion(uri, 23).map { it.libelle }
        assertTrue(types.contains("@string/"))
        assertTrue(types.contains("@drawable/") || types.contains("@layout/"))
        // `@string/he` → les NOMS du type filtrés.
        session.ouvrirDocument(uri, "<Button android:text=\"@string/he")
        val noms = session.completion(uri, 32).map { it.libelle }
        assertTrue(noms.contains("@string/hello"))
        assertTrue(noms.none { it != "@string/hello" })
    }

    @Test
    fun `survol — reference projet avec declarant`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        val source = "<Button android:text=\"@string/hello\""
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("hello") + 2)
        assertEquals("string « hello » — projet, strings.xml", survol?.valeur)
    }

    @Test
    fun `survol — nom de balise (classe) et attribut (format)`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<Button android:id=\"x\"")
        assertEquals(
            "class android.widget.Button",
            session.survol(uri, 4)?.valeur,
        )
        // attribut id → format reference (fixture plateforme).
        assertEquals(
            "format: reference",
            session.survol(uri, 13)?.valeur,
        )
    }

    @Test
    fun `definitions — saut au values declarant`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        val source = "<Button android:text=\"@string/hello\""
        session.ouvrirDocument(uri, source)
        val emplacements = session.definitions(uri, source.indexOf("hello") + 2)
        assertEquals(1, emplacements.size)
        assertTrue(emplacements.single().uri.endsWith("strings.xml"))
        assertTrue(emplacements.single().debut > 0)
    }

    @Test
    fun `survol et definitions muets — hors reference`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        val source = "<Button android:text=\"du texte"
        session.ouvrirDocument(uri, source)
        assertNull(session.survol(uri, source.length))
        assertTrue(session.definitions(uri, source.indexOf("texte")).isEmpty())
    }

    @Test
    fun `regle referenceNonResolue — projet et framework consultes, dumb mode garde`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/layout/main.xml"
        val source =
            "<Button android:text=\"@string/hello\" android:id=\"@+id/x\" " +
                "android:hint=\"@string/absent\""
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        val reference = diagnostics.single { it.code == "xml.referenceNonResolue" }
        // Le marqueur porte sur le NOM de la référence manquante.
        assertEquals("absent", source.substring(reference.debut, reference.fin))
        // Dumb mode : SANS classpath, la même référence est muette.
        val sessionFroide = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())
        sessionFroide.ouvrirDocument(uri, source)
        assertTrue(
            sessionFroide.diagnostics(uri).none { it.code == "xml.referenceNonResolue" },
        )
        sessionFroide.close()
    }

    // ── Complétion (XE2) ───────────────────────────────────────────

    /** Mini-plateforme pour le classpath de la session. */
    private fun plateformeFixture(): File {
        val dossier = dossierTemp.newFolder("platforms/android-test")
        val data = dossier.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            "Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object\n" +
                "Wandroid.widget.LinearLayout android.view.ViewGroup android.view.View java.lang.Object\n",
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            "<resources>" +
                "<declare-styleable name=\"View\"><attr name=\"id\" format=\"reference\"/></declare-styleable>" +
                "<declare-styleable name=\"TextView\"><attr name=\"text\" format=\"string\"/></declare-styleable>" +
                "<declare-styleable name=\"ViewGroup_Layout\"><attr name=\"layout_width\"/></declare-styleable>" +
                "</resources>",
        )
        return dossier
    }

    @Test
    fun `completion balise — catalogue de la plateforme du classpath`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        val propositions = session.completion(uri, 15)
        assertTrue(propositions.any { it.libelle == "Button" })
        // La proposition porte le FQCN du catalogue.
        assertEquals(
            "android.widget.Button",
            propositions.single { it.libelle == "Button" }.detail,
        )
    }

    @Test
    fun `completion attribut — styleables de la chaine`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><Button ")
        val libelles = session.completion(uri, 27).map { it.libelle }
        assertTrue(libelles.contains("android:text"))
        assertTrue(libelles.contains("android:layout_width"))
        assertTrue(libelles.contains("android:id"))
    }

    @Test
    fun `completion — sans classpath, silencieuse (X4)`() {
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        // Pas de catalogue : les ATTRIBUTS tools restent, les BALISES
        // se taisent (jamais de candidat inventé).
        assertTrue(session.completion(uri, 15).isEmpty())
    }

    @Test
    fun `completion — genre non layout, silencieuse (XE2, layout seul)`() {
        session.definirClasspath(ClasspathXml(plateformeFixture(), emptyList(), null))
        val uri = "file:///p/inconnu/fichier.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        assertTrue(session.completion(uri, 15).isEmpty())
    }

    // ── XE4 : manifest, balise inconnue, values, quick-fixes ─────

    /** Vue de noms factice (garde balise inconnue X4). */
    private class VueNomsFactice(private val connues: Set<String>) : jo.lspxml.api.VueNomsXml {
        override fun nomsCommencantPar(prefixe: String, limite: Int): List<String> =
            connues.filter { it.endsWith(".$prefixe") || it == prefixe }
    }

    @Test
    fun `manifest — package et composants nommes diagnostiques`() {
        val uri = "file:///p/app/src/main/AndroidManifest.xml"
        session.ouvrirDocument(
            uri,
            "<manifest><application><activity/></application></manifest>",
        )
        val codes = session.diagnostics(uri).map { it.code }
        assertTrue(codes.contains("xml.manifestSansPackage"))
        assertTrue(codes.contains("xml.composantSansNom"))
    }

    @Test
    fun `manifest sain — silence`() {
        val uri = "file:///p/app/src/main/AndroidManifest.xml"
        session.ouvrirDocument(
            uri,
            "<manifest package=\"p\"><application><activity android:name=\".A\"/></application></manifest>",
        )
        assertTrue(session.diagnostics(uri).isEmpty())
    }

    @Test
    fun `balise inconnue — catalogue et vue de noms d'accord`() {
        val plateforme = plateformeFixture()
        session.definirClasspath(
            ClasspathXml(plateforme, emptyList(), VueNomsFactice(emptySet())),
        )
        val uri = "file:///p/res/layout/main.xml"
        val source = "<LinearLayout><Buton/></LinearLayout>"
        session.ouvrirDocument(uri, source)
        val codes = session.diagnostics(uri).map { it.code }
        assertTrue(codes.contains("xml.tagInconnu"))
        // Sans vue de noms : silence (garde X4).
        val sessionMuette = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())
        sessionMuette.definirClasspath(ClasspathXml(plateforme, emptyList(), null))
        sessionMuette.ouvrirDocument(uri, source)
        assertTrue(sessionMuette.diagnostics(uri).none { it.code == "xml.tagInconnu" })
        sessionMuette.close()
    }

    @Test
    fun `balise inconnue — connue du classpath, silence`() {
        session.definirClasspath(
            ClasspathXml(
                plateformeFixture(),
                emptyList(),
                VueNomsFactice(setOf("com.ex.MonBouton")),
            ),
        )
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><MonBouton/></LinearLayout>")
        assertTrue(session.diagnostics(uri).none { it.code == "xml.tagInconnu" })
    }

    @Test
    fun `completion values — entrees et styles du parent`() {
        val (racine, plateforme) = projetComplet()
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        val uri = "file:///p/res/values/styles.xml"
        session.ouvrirDocument(uri, "<resources><st")
        val balises = session.completion(uri, 15).map { it.libelle }
        assertTrue(balises.contains("style"))
        assertTrue(balises.contains("string"))
        // Attributs d'entrée.
        session.ouvrirDocument(uri, "<resources><string ")
        val attributs = session.completion(uri, 20).map { it.libelle }
        assertEquals("name", attributs.first())
        // parent= : styles du projet (fixture projetComplet : hello…
        // pas de style — la fixture en ajoute un ici).
        racine.resolve("res/values/styles.xml").writeText(
            "<resources><style name=\"Theme.X\"/></resources>",
        )
        session.definirClasspath(ClasspathXml(plateforme, listOf(racine), null))
        session.ouvrirDocument(uri, "<resources><style name=\"y\" parent=\"")
        val parents = session.completion(uri, 37).map { it.libelle }
        assertTrue(parents.contains("Theme.X"))
    }

    @Test
    fun `correctifs — insertion xmlns android`() {
        val uri = "file:///p/res/layout/main.xml"
        val source = "<LinearLayout android:id=\"@+id/x\">"
        session.ouvrirDocument(uri, source)
        val diagnostic = session.diagnostics(uri).first { it.code == "xml.manqueXmlnsAndroid" }
        val correctifs = session.correctifs(uri, diagnostic.debut)
        val correctif = correctifs.single()
        assertEquals("Insérer xmlns:android sur « LinearLayout »", correctif.titre)
        val edition = correctif.editions.single()
        // Insertion JUSTE APRÈS le nom de la racine (fin du nom exclusif).
        val finNom = "<LinearLayout".length
        assertEquals(finNom, edition.debut)
        assertEquals(finNom, edition.fin)
        assertEquals(
            " xmlns:android=\"http://schemas.android.com/apk/res/android\"",
            edition.texte,
        )
    }

    @Test
    fun `correctifs — did you mean renomme ouvrante et fermante`() {
        session.definirClasspath(
            ClasspathXml(plateformeFixture(), emptyList(), VueNomsFactice(emptySet())),
        )
        val uri = "file:///p/res/layout/main.xml"
        val source = "<LinearLayout><Buton></Buton></LinearLayout>"
        session.ouvrirDocument(uri, source)
        val diagnostic = session.diagnostics(uri).first { it.code == "xml.tagInconnu" }
        val correctifs = session.correctifs(uri, diagnostic.debut)
        // Le candidat le plus proche de « Buton » est « Button ».
        val renommage = correctifs.first { it.titre.contains("Button") }
        assertEquals(2, renommage.editions.size)
        // Ouvrante : remplace « Buton » par Button — positions CALCULÉES
        // depuis la source (aucun décalage codé en dur).
        val debutOuvrante = source.indexOf("Buton")
        val ouvrante = renommage.editions[0]
        assertEquals(debutOuvrante, ouvrante.debut)
        assertEquals(debutOuvrante + "Buton".length, ouvrante.fin)
        assertEquals("Button", ouvrante.texte)
        // Fermante : remplace « Buton » dans </Buton>.
        val debutFermante = source.indexOf("</Buton>") + "</".length
        val fermante = renommage.editions[1]
        assertEquals(debutFermante, fermante.debut)
        assertEquals(debutFermante + "Buton".length, fermante.fin)
        assertEquals("Button", fermante.texte)
    }

    @Test
    fun `correctifs — diagnostic inconnu, liste vide`() {
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout>")
        assertTrue(session.correctifs(uri, 0).isEmpty())
    }

    @Test
    fun `completion — document inconnu et zone muette, silencieuses`() {
        assertTrue(session.completion("file:///p/inconnu.xml", 0).isEmpty())
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<TextView>du texte</TextView>")
        assertTrue(session.completion(uri, 12).isEmpty())
    }

    @Test
    fun `completion — apres close, silencieuse`() {
        val uri = "file:///p/res/layout/main.xml"
        session.ouvrirDocument(uri, "<LinearLayout><But")
        session.close()
        assertTrue(session.completion(uri, 15).isEmpty())
    }
}
