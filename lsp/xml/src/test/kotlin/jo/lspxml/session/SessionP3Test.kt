package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.ParseurXml
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests SESSION du volet BIBLIOTHÈQUES (P3, racine de XP1) — les `res/`
 * des AAR de dépendances sont indexés depuis les conteneurs : une
 * référence `@style/Theme.Material3…` n'est PLUS un faux positif (la
 * gravité remonte à ERREUR légitime — ADR 0120), le survol porte le
 * détail « bibliothèque », la complétion propose styles/attrs des
 * dépendances, les définitions restent PROJET (D3 : pas de saut dans
 * une archive), un AAR corrompu redescend en AVERTISSEMENT (doute X4).
 */
class SessionP3Test {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val uriLayout = "file:///projets/app/src/main/res/layout/main.xml"
    private val uriValues = "file:///projets/app/src/main/res/values/styles.xml"

    /** Layout minimal avec xmlns:android (les règles de structure taisent). */
    private fun layout(valeur: String): String =
        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "android:text=\"$valeur\"/>"

    /** Session nourrie d'une racine projet (strings.xml) ET d'un AAR Material fictif. */
    private fun sessionNourrie(corrompreAar: Boolean = false): SessionLangageXmlImpl {
        val racine = dossierTemp.newFolder("src")
        racine.resolve("res/values").apply { mkdirs() }
            .resolve("strings.xml")
            .writeText("<resources><string name=\"app_nom\">CodeIDE</string></resources>")
        val aar = dossierTemp.newFolder("libs").resolve("material-1.12.0.aar")
        if (corrompreAar) {
            aar.writeBytes(byteArrayOf(9, 8, 7, 6)) // PAS un ZIP
        } else {
            val valeurs =
                "<resources>" +
                    "<style name=\"Theme.Material3.DayNight\" parent=\"android:Theme.Material\"/>" +
                    "<attr name=\"material_btn\" format=\"reference\"/>" +
                    "<declare-styleable name=\"MaterialButton\"><attr name=\"material_shape\"/></declare-styleable>" +
                    "</resources>"
            ZipOutputStream(aar.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("res/values/values.xml"))
                zip.write(valeurs.toByteArray())
                zip.closeEntry()
            }
        }
        val session = SessionLangageXmlImpl(ParseurXml())
        session.definirClasspath(
            ClasspathXml(
                dossierPlateforme = null,
                racinesSources = listOf(racine),
                vueNoms = null,
                racinesAar = listOf(aar),
            ),
        )
        return session
    }

    // ── Résolution (fin du faux positif XP1) ───────────────────────

    @Test
    fun `reference lib — material resolu, inconnu projet en ERREUR legitime`() {
        val session = sessionNourrie()
        session.ouvrirDocument(uriLayout, layout("@style/Theme.Material3.DayNight"))
        assertTrue(
            "une référence de DÉPENDANCE indexée n'est plus signalée",
            session.diagnostics(uriLayout).none { it.code == CodesDiagnosticsXml.REFERENCE_NON_RESOLUE },
        )
        // Le PROJET et la BIBLIOTHÈQUE se valent : app_nom (projet) OK aussi.
        session.modifierDocument(uriLayout, layout("@string/app_nom"))
        assertTrue(session.diagnostics(uriLayout).none { it.code == CodesDiagnosticsXml.REFERENCE_NON_RESOLUE })
        // L'inconnu des TROIS sources est un VRAI positif — ERREUR.
        session.modifierDocument(uriLayout, layout("@string/inconnu"))
        val diagnostic = session.diagnostics(uriLayout).single { it.code == CodesDiagnosticsXml.REFERENCE_NON_RESOLUE }
        assertEquals(SeveriteXml.ERREUR, diagnostic.severite)
        assertTrue(diagnostic.message.contains("bibliothèques"))
    }

    @Test
    fun `doute — aar corrompu, la gravite redescend en AVERTISSEMENT`() {
        val session = sessionNourrie(corrompreAar = true)
        session.ouvrirDocument(uriLayout, layout("@string/inconnu"))
        val diagnostic = session.diagnostics(uriLayout).single { it.code == CodesDiagnosticsXml.REFERENCE_NON_RESOLUE }
        assertEquals(SeveriteXml.AVERTISSEMENT, diagnostic.severite)
    }

    // ── Survol (détail bibliothèque) ───────────────────────────────

    @Test
    fun `survol — projet, bibliothèque, inconnu`() {
        val session = sessionNourrie()
        session.ouvrirDocument(uriLayout, layout("@string/app_nom"))
        val sourceProjet = layout("@string/app_nom")
        assertEquals(
            "string « app_nom » — projet, strings.xml",
            session.survol(uriLayout, sourceProjet.indexOf("app_nom") + 2)?.valeur,
        )
        val sourceLib = layout("@style/Theme.Material3.DayNight")
        session.modifierDocument(uriLayout, sourceLib)
        assertEquals(
            "style « Theme.Material3.DayNight » — bibliothèque, material-1.12.0.aar",
            session.survol(uriLayout, sourceLib.indexOf("DayNight") + 2)?.valeur,
        )
        val sourceInconnu = layout("@string/inconnu")
        session.modifierDocument(uriLayout, sourceInconnu)
        assertEquals(
            null,
            session.survol(uriLayout, sourceInconnu.indexOf("inconnu") + 2)?.valeur,
        )
    }

    // ── Complétion (styles/attrs des dépendances) ──────────────────

    @Test
    fun `completion — types, noms et attrs de bibliothèque`() {
        val session = sessionNourrie()
        // @sty : le TYPE style vient de la BIBLIOTHÈQUE (aucun style projet).
        val sourceTypes = layout("@sty")
        session.ouvrirDocument(uriLayout, sourceTypes)
        val types = session.completion(uriLayout, sourceTypes.indexOf("@sty") + 4)
        assertTrue(types.any { it.libelle == "@style/" && it.detail?.contains("bibliothèques") == true })
        // @style/Th : le NOM de la dépendance est proposé.
        val sourceNoms = layout("@style/Th")
        session.modifierDocument(uriLayout, sourceNoms)
        val noms = session.completion(uriLayout, sourceNoms.indexOf("@style/Th") + 9)
        assertTrue(noms.any { it.libelle == "@style/Theme.Material3.DayNight" && it.detail == "bibliothèque" })
        // ?attr/mate : les attrs de la dépendance (styleable compris).
        val sourceAttr = layout("?attr/mate")
        session.modifierDocument(uriLayout, sourceAttr)
        val attrs =
            session
                .completion(uriLayout, sourceAttr.indexOf("?attr/mate") + 10)
                .map { it.libelle }
        assertTrue(attrs.contains("?attr/material_btn"))
        assertTrue(attrs.contains("?attr/material_shape"))
    }

    @Test
    fun `completion parent — styles de bibliothèque proposés`() {
        val session = sessionNourrie()
        val source = "<resources><style name=\"App\" parent=\"Theme.Mate\"/></resources>"
        session.ouvrirDocument(uriValues, source)
        val propositions =
            session
                .completion(uriValues, source.indexOf("Theme.Mate") + "Theme.Mate".length)
                .map { it.libelle }
        assertTrue(
            "le thème Material de la dépendance apparaît dans parent=",
            propositions.contains("Theme.Material3.DayNight"),
        )
    }

    // ── Définitions (projet seul — D3 : pas de saut dans une archive) ─

    @Test
    fun `definitions — projet seul, bibliothèque sans saut`() {
        val session = sessionNourrie()
        val sourceProjet = layout("@string/app_nom")
        session.ouvrirDocument(uriLayout, sourceProjet)
        assertEquals(1, session.definitions(uriLayout, sourceProjet.indexOf("app_nom") + 2).size)
        val sourceLib = layout("@style/Theme.Material3.DayNight")
        session.modifierDocument(uriLayout, sourceLib)
        assertTrue(
            "aucune définition factice vers un conteneur AAR",
            session.definitions(uriLayout, sourceLib.indexOf("DayNight") + 2).isEmpty(),
        )
    }
}
