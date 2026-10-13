package jo.lspxml.resolution

import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.parse.Plage
import jo.lspxml.resources.IndexRessources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la RÉSOLUTION de références (XE3, décision X5) — extraction
 * (`@type/nom`, `@+id`, `@android:`, `?attr/`), résolution (projet +
 * framework + dumb mode), survol et définitions.
 */
class ReferencesXmlTest {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    // ── Extraction ─────────────────────────────────────────────────

    @Test
    fun `reference projet — type, nom et plage`() {
        val reference = ReferencesXml.referenceSous("@string/hello", 8, Plage(20, 33))!!
        assertEquals("string", reference.type)
        assertEquals("hello", reference.nom)
        assertFalse(reference.framework)
        assertFalse(reference.declarante)
        assertFalse(reference.attributStyle)
        // Plage du NOM : 20 + « @string/ ».length = 28.
        assertEquals(Plage(28, 33), reference.plageNom)
    }

    @Test
    fun `reference declarante — at plus id`() {
        val reference = ReferencesXml.referenceSous("@+id/bouton", 10, Plage(0, 11))!!
        assertTrue(reference.declarante)
        assertEquals("id", reference.type)
        assertEquals("bouton", reference.nom)
    }

    @Test
    fun `reference framework — android deux-points`() {
        val reference = ReferencesXml.referenceSous("@android:string/ok", 8, Plage(0, 19))!!
        assertTrue(reference.framework)
        assertEquals("string", reference.type)
        assertEquals("ok", reference.nom)
    }

    @Test
    fun `reference privee — etoile android`() {
        val reference = ReferencesXml.referenceSous("@*android:drawable/fond", 8, Plage(0, 23))!!
        assertTrue(reference.framework)
        assertEquals("drawable", reference.type)
    }

    @Test
    fun `attribut de style — interrogation attr`() {
        val reference = ReferencesXml.referenceSous("?attr/colorPrimary", 10, Plage(0, 18))!!
        assertTrue(reference.attributStyle)
        assertEquals("attr", reference.type)
        assertEquals("colorPrimary", reference.nom)
        // La forme courte héritée `?colorPrimary` marche aussi.
        val courte = ReferencesXml.referenceSous("?colorPrimary", 10, Plage(0, 13))!!
        assertEquals("attr", courte.type)
        assertEquals("colorPrimary", courte.nom)
    }

    @Test
    fun `pas de reference — null`() {
        assertNull(ReferencesXml.referenceSous("match_parent", 5, Plage(0, 12)))
        assertNull(ReferencesXml.referenceSous("@", 1, Plage(0, 1)))
        assertNull(ReferencesXml.referenceSous("@type/", 6, Plage(0, 6)))
    }

    // ── Résolution (couture index + catalogue) ─────────────────────

    /** Index chaud avec une string « hello ». */
    private fun indexAvecHello(): IndexRessources {
        val racine = dossierTemp.newFolder("src")
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText("<resources><string name=\"hello\">Salut</string></resources>")
        val index = IndexRessources()
        index.definir(listOf(racine))
        return index
    }

    /** Catalogue factice avec une ressource framework « ok ». */
    private fun catalogueAvecFramework(): CataloguePlateforme? =
        CataloguePlateforme.charger(plateformeFramework())

    private fun plateformeFramework(): File {
        val dossier = dossierTemp.newFolder("platforms/android-test")
        val data = dossier.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            "Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object\n",
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            "<resources><declare-styleable name=\"View\"><attr name=\"id\"/></declare-styleable></resources>",
        )
        valeurs.resolve("public-final.xml").writeText(
            "<resources><public type=\"string\" name=\"ok\" id=\"0x1\"/></resources>",
        )
        return dossier
    }

    @Test
    fun `resolution — projet puis framework, declarante jamais signalée`() {
        val index = indexAvecHello()
        val catalogue = catalogueAvecFramework()
        val projet = ReferencesXml.referenceSous("@string/hello", 10, Plage(0, 13))!!
        val inconnu = ReferencesXml.referenceSous("@string/inconnu", 10, Plage(0, 15))!!
        val framework = ReferencesXml.referenceSous("@android:string/ok", 10, Plage(0, 18))!!
        val frameworkFaux = ReferencesXml.referenceSous("@android:string/absent", 10, Plage(0, 22))!!
        val declarante = ReferencesXml.referenceSous("@+id/x", 5, Plage(0, 6))!!
        assertTrue(ReferencesXml.estResolue(projet, index, catalogue))
        // Projet + framework consultés : inconnu des deux = NON résolu.
        assertFalse(ReferencesXml.estResolue(inconnu, index, catalogue))
        assertTrue(ReferencesXml.estResolue(framework, index, catalogue))
        assertFalse(ReferencesXml.estResolue(frameworkFaux, index, catalogue))
        assertTrue(ReferencesXml.estResolue(declarante, index, catalogue))
    }

    @Test
    fun `dumb mode — index froid = silence, framework sans catalogue = silence`() {
        val froid = IndexRessources()
        val inconnu = ReferencesXml.referenceSous("@string/inconnu", 10, Plage(0, 15))!!
        // Index FROID : tout est résolu (dumb mode, X4).
        assertTrue(ReferencesXml.estResolue(inconnu, froid, catalogueAvecFramework()))
        // Catalogue SANS ressources framework : une référence framework
        // n'est JAMAIS signalée.
        val index = indexAvecHello()
        val sansPublic = ReferencesXml.estResolue(
            ReferencesXml.referenceSous("@android:string/absent", 10, Plage(0, 22))!!,
            index,
            null,
        )
        assertTrue(sansPublic)
    }

    @Test
    fun `survol — projet avec déclarant, framework, declarante`() {
        val index = indexAvecHello()
        val catalogue = catalogueAvecFramework()
        val projet = ReferencesXml.referenceSous("@string/hello", 10, Plage(0, 13))!!
        assertEquals(
            "string « hello » — projet, strings.xml",
            ReferencesXml.survol(projet, index)?.valeur,
        )
        val framework = ReferencesXml.referenceSous("@android:string/ok", 10, Plage(0, 18))!!
        assertEquals(
            "string « ok » — framework android",
            ReferencesXml.survol(framework, index)?.valeur,
        )
        val declarante = ReferencesXml.referenceSous("@+id/x", 5, Plage(0, 6))!!
        assertEquals(
            "id « x » — déclaré ici (@+)",
            ReferencesXml.survol(declarante, index)?.valeur,
        )
        val inconnu = ReferencesXml.referenceSous("@string/inconnu", 10, Plage(0, 15))!!
        assertNull(ReferencesXml.survol(inconnu, index))
    }

    @Test
    fun `definitions — projet uniquement, plage du déclarant`() {
        val index = indexAvecHello()
        val projet = ReferencesXml.referenceSous("@string/hello", 10, Plage(0, 13))!!
        val emplacements = ReferencesXml.definitions(projet, index)
        assertEquals(1, emplacements.size)
        val emplacement = emplacements.single()
        assertTrue(emplacement.uri.endsWith("strings.xml"))
        assertTrue(emplacement.uri.startsWith("file://"))
        assertTrue(emplacement.debut > 0)
        // Framework et déclarante : aucune définition locale.
        val framework = ReferencesXml.referenceSous("@android:string/ok", 10, Plage(0, 18))!!
        assertTrue(ReferencesXml.definitions(framework, index).isEmpty())
        val declarante = ReferencesXml.referenceSous("@+id/x", 5, Plage(0, 6))!!
        assertTrue(ReferencesXml.definitions(declarante, index).isEmpty())
    }
}
