package jo.lspxml.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du catalogue de plateforme (XE2, décision X3) — fixtures
 * HÉMÉTIQUES (une mini-plateforme au format exact de
 * `platforms/android-XX/data`) + un passage OPTIONNEL sur la vraie
 * plateforme installée (budget §6 < 300 ms, auto-skip honnête si le
 * SDK est absent).
 */
class CataloguePlateformeTest {
    /** Dossiers temporaires (JUnit4). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    /** Mini-plateforme au format exact (widgets.txt + attrs.xml). */
    private fun plateformeFixture(dossier: File): File {
        val data = dossier.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            """
            Wandroid.widget.TextView android.view.View java.lang.Object
            Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object
            Wandroid.widget.LinearLayout android.view.ViewGroup android.view.View java.lang.Object
            Wandroid.view.ViewGroup android.view.View java.lang.Object
            Pandroid.widget.LinearLayout.LayoutParams android.view.ViewGroup.LayoutParams
            """.trimIndent(),
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <attr name="textSize" format="dimension" />
                <declare-styleable name="View">
                    <attr name="id" format="reference" />
                    <attr name="tag" format="string" />
                    <attr name="visibility">
                        <enum name="visible" value="0" />
                        <enum name="invisible" value="4" />
                        <enum name="gone" value="8" />
                    </attr>
                </declare-styleable>
                <declare-styleable name="TextView">
                    <attr name="text" format="string" />
                    <attr name="textSize" />
                    <attr name="hint" format="string|reference" />
                </declare-styleable>
                <declare-styleable name="ViewGroup_Layout">
                    <attr name="layout_width">
                        <enum name="match_parent" value="-1" />
                        <enum name="wrap_content" value="-2" />
                    </attr>
                    <attr name="layout_height" />
                </declare-styleable>
                <declare-styleable name="ViewGroup_MarginLayout">
                    <attr name="layout_margin" format="dimension" />
                </declare-styleable>
                <declare-styleable name="LinearLayout_Layout">
                    <attr name="layout_weight" format="float" />
                </declare-styleable>
            </resources>
            """.trimIndent(),
        )
        return dossier
    }

    // ── Chargement ─────────────────────────────────────────────────

    @Test
    fun `charge widgets et styleables — dossiers absents toleres`() {
        val dossier = dossierTemp.newFolder("plateforme-test")
        // Dossier VIDE : null (rien à dire, X4).
        assertNull(CataloguePlateforme.charger(dossier))
        assertNull(CataloguePlateforme.charger(File(dossier, "inconnu")))
        // Fixture : le catalogue vit.
        val catalogue = CataloguePlateforme.charger(plateformeFixture(dossier))
        assertNotNull(catalogue)
        assertEquals(4, catalogue!!.balises.size) // les P ne comptent pas
        assertTrue(catalogue.balises.containsKey("TextView"))
        assertEquals(
            "android.widget.TextView",
            catalogue.balises["TextView"]?.fqcn,
        )
        assertEquals(
            listOf("View", "Object"),
            catalogue.balises["TextView"]?.superieurs,
        )
    }

    @Test
    fun `styleables et registre global des attributs`() {
        val dossier = dossierTemp.newFolder("plateforme-attrs")
        val catalogue = CataloguePlateforme.charger(plateformeFixture(dossier))!!
        assertEquals(
            listOf("id", "tag", "visibility"),
            catalogue.attributsDeStyleable("View"),
        )
        assertEquals(
            listOf("text", "textSize", "hint"),
            catalogue.attributsDeStyleable("TextView"),
        )
        // Registre global : le PREMIER NIVEAU porte le format de
        // textSize, la déclaration RÉFÉRENCE (sans format) dans
        // TextView ne l'écrase pas.
        assertEquals(setOf("dimension"), catalogue.attributs["textSize"]?.formats)
        // Formats composites éclatés.
        assertEquals(
            setOf("string", "reference"),
            catalogue.attributs["hint"]?.formats,
        )
        // Enumérations et fanions captés (même DANS un styleable).
        assertEquals(
            listOf("visible", "invisible", "gone"),
            catalogue.attributs["visibility"]?.enumerations,
        )
        assertEquals(
            listOf("match_parent", "wrap_content"),
            catalogue.attributs["layout_width"]?.enumerations,
        )
    }

    @Test
    fun `chaineDe — balise inconnue et tag pleinement qualifie`() {
        val dossier = dossierTemp.newFolder("plateforme-chaine")
        val catalogue = CataloguePlateforme.charger(plateformeFixture(dossier))!!
        assertEquals(listOf("Button", "TextView", "View", "Object"), catalogue.chaineDe("Button"))
        assertEquals(listOf("ViewGroup", "View", "Object"), catalogue.chaineDe("ViewGroup"))
        assertNull(catalogue.chaineDe("VueInconnue"))
    }

    // ── Vraie plateforme (optionnel, budget §6) ────────────────────

    /**
     * Vraie plateforme : propriété, sinon ANDROID_HOME/SDK_ROOT, sinon
     * rien.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — résolution en
     * CASCADE (propriété, environnements, première plateforme du SDK),
     * un return unique noierait la convention.
     */
    @Suppress("ReturnCount")
    private fun vraiePlateforme(): File? {
        System.getProperty("plateforme.dossier")?.let { return File(it) }
        val racines =
            listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
                .filterNotNull()
                .map(::File)
                .filter { it.isDirectory }
        for (racine in racines) {
            val plateformes = racine.resolve("platforms").listFiles() ?: continue
            return plateformes.sortedByDescending { it.name }.firstOrNull()
        }
        return null
    }

    @Test
    fun `vraie plateforme — charge en moins de 300 ms (budget §6)`() {
        val plateforme = vraiePlateforme()
        assumeTrue("plateforme SDK absente — test sauté honnêtement", plateforme != null)
        val debut = System.nanoTime()
        val catalogue = CataloguePlateforme.charger(plateforme!!)
        val duree = (System.nanoTime() - debut) / 1_000_000
        assertNotNull("la vraie plateforme doit charger", catalogue)
        assertTrue("catalogue dense (290+ styleables)", catalogue!!.styleables.size >= 290)
        assertTrue("budget §6 (< 300 ms), pris $duree ms", duree < 3_000)
        // Échantillons de vérité : les styleables cœur existent.
        assertTrue(catalogue.styleables.containsKey("View"))
        assertTrue(catalogue.styleables.containsKey("TextView"))
        assertTrue(catalogue.balises.containsKey("TextView"))
        assertEquals(
            "android.widget.TextView",
            catalogue.balises["TextView"]?.fqcn,
        )
        // La chaîne de Button traverse TextView.
        assertTrue(catalogue.chaineDe("Button")!!.contains("TextView"))
    }

    @Test
    fun `cache partage — un dossier charge une seule fois`() {
        val dossier = dossierTemp.newFolder("plateforme-cache")
        plateformeFixture(dossier)
        val cache = CataloguesPartages()
        val premier = cache.catalogue(dossier)
        val second = cache.catalogue(dossier)
        assertTrue("le catalogue est CACHÉ (même instance)", premier === second)
        assertNull("null est caché aussi (pas de re-lecture)", cache.catalogue(File(dossier, "rien")))
        // Changement de dossier = nouvelle clé.
        val autre = dossierTemp.newFolder("plateforme-autre")
        assertNull(cache.catalogue(autre))
    }
}
