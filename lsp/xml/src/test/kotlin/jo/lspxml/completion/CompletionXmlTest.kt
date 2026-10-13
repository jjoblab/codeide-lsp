package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.context.Contexte
import jo.lspxml.context.ContexteCurseur
import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests des CONTRIBUTEURS de complétion (XE2, §4.3) — balises du
 * catalogue (fréquentes d'abord, préfixe), attributs de la chaîne de
 * classes + mise en page du PARENT + tools:, déclarations xmlns en
 * frappe. Le catalogue est la FIXTURE hermétique (patron
 * CataloguePlateformeTest).
 */
class CompletionXmlTest {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    private val parseur = ParseurXml()

    /** Catalogue fixture (même montage que CataloguePlateformeTest). */
    private fun catalogue(): CataloguePlateforme? {
        val dossier = dossierTemp.newFolder("plateforme")
        val data = dossier.resolve("data").apply { mkdirs() }
        data.resolve("widgets.txt").writeText(
            """
            Wandroid.widget.TextView android.view.View java.lang.Object
            Wandroid.widget.Button android.widget.TextView android.view.View java.lang.Object
            Wandroid.widget.LinearLayout android.view.ViewGroup android.view.View java.lang.Object
            Wandroid.view.ViewGroup android.view.View java.lang.Object
            """.trimIndent(),
        )
        val valeurs = data.resolve("res/values").apply { mkdirs() }
        valeurs.resolve("attrs.xml").writeText(
            """
            <resources>
                <declare-styleable name="View">
                    <attr name="id" format="reference" />
                    <attr name="tag" format="string" />
                </declare-styleable>
                <declare-styleable name="TextView">
                    <attr name="text" format="string" />
                </declare-styleable>
                <declare-styleable name="ViewGroup_Layout">
                    <attr name="layout_width">
                        <enum name="match_parent" value="-1" />
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
        return CataloguePlateforme.charger(dossier)
    }

    /** Contexte d'attribut pour un texte/élément. */
    private fun contexteAttribut(
        source: String,
        decalage: Int,
    ): Contexte.NomAttribut =
        ContexteCurseur.analyser(source, decalage, parseur.analyser(source)) as Contexte.NomAttribut

    private fun contexteBalise(
        source: String,
        decalage: Int,
    ): Contexte.NomDeBalise =
        ContexteCurseur.analyser(source, decalage, parseur.analyser(source)) as Contexte.NomDeBalise

    // ── Balises ────────────────────────────────────────────────────

    @Test
    fun `balises — vues du catalogue + spéciales, classement fréquentes`() {
        val catalogue = catalogue()
        val propositions = CompletionXml.balises(contexteBalise("<LinearLayout><", 15), catalogue)
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("TextView"))
        assertTrue(libelles.contains("Button"))
        assertTrue(libelles.contains("LinearLayout"))
        assertTrue(libelles.contains("merge"))
        assertTrue(libelles.contains("include"))
        // Une balise du catalogue porte son FQCN en détail.
        val textView = propositions.single { it.libelle == "TextView" }
        assertEquals("android.widget.TextView", textView.detail)
        assertEquals(GenreCompletionXml.BALISE, textView.genre)
        // Tri par la clé (croissant).
        assertTrue(propositions.map { it.texteTri }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `balises — préfixe filtre`() {
        val propositions =
            CompletionXml.balises(contexteBalise("<Text", 5), catalogue())
        assertTrue(propositions.isNotEmpty())
        assertTrue(propositions.all { it.libelle.startsWith("Text") })
    }

    @Test
    fun `balises — sans catalogue, silence honnête`() {
        assertTrue(CompletionXml.balises(contexteBalise("<", 1), null).isEmpty())
    }

    // ── Attributs ──────────────────────────────────────────────────

    @Test
    fun `attributs — chaîne de classes + layout du parent + tools`() {
        // Button DANS LinearLayout : attrs de Button/TextView/View +
        // layout_* du PARENT (LinearLayout_Layout : layout_weight).
        val texte = "<LinearLayout><Button "
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("android:text"))
        assertTrue(libelles.contains("android:id"))
        assertTrue(libelles.contains("android:tag"))
        assertTrue(libelles.contains("android:layout_width"))
        assertTrue(libelles.contains("android:layout_height"))
        assertTrue(libelles.contains("android:layout_weight"))
        assertTrue(libelles.contains("android:layout_margin"))
        assertTrue(libelles.any { it.startsWith("tools:") })
    }

    @Test
    fun `attributs — racine sans parent, layout universels seuls`() {
        // TextView en RACINE : pas de parent → ViewGroup_Layout +
        // MarginLayout, SANS layout_weight ( LinearLayout_Layout).
        val texte = "<TextView "
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("android:layout_width"))
        assertTrue(libelles.contains("android:layout_margin"))
        assertTrue(!libelles.contains("android:layout_weight"))
        assertTrue(libelles.contains("android:text"))
    }

    @Test
    fun `attributs — préfixe avec deux-points filtre l'espace de noms`() {
        val texte = "<Button android:"
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        assertTrue(propositions.isNotEmpty())
        assertTrue(propositions.all { it.libelle.startsWith("android:") })
        assertTrue(!propositions.any { it.libelle.startsWith("tools:") })
    }

    @Test
    fun `attributs — préfixe local sans deux-points`() {
        // `te` → android:text (nom local), pas android:tag.
        val texte = "<Button te"
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("android:text"))
        assertTrue(!libelles.contains("android:tag"))
    }

    @Test
    fun `attributs — détail porte le format`() {
        val source = "<Button "
        val propositions = CompletionXml.attributs(contexteAttribut(source, source.length), catalogue())
        val attrTexte = propositions.single { it.libelle == "android:text" }
        assertEquals("format: string", attrTexte.detail)
        val layoutWidth = propositions.single { it.libelle == "android:layout_width" }
        assertEquals("enum: match_parent", layoutWidth.detail)
    }

    @Test
    fun `attributs — classement id puis layout puis courant`() {
        val texte = "<LinearLayout><Button "
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        assertTrue(propositions.map { it.texteTri }.zipWithNext().all { (a, b) -> a <= b })
        assertEquals("android:id", propositions.first().libelle)
    }

    @Test
    fun `xmlns en frappe — déclarations proposees`() {
        val texte = "<LinearLayout xmln"
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("xmlns:android"))
        assertTrue(libelles.contains("xmlns:tools"))
        assertTrue(libelles.contains("xmlns:app"))
        assertEquals(
            "http://schemas.android.com/apk/res/android",
            propositions.single { it.libelle == "xmlns:android" }.detail,
        )
    }

    @Test
    fun `attributs — élément inconnu du catalogue, View en repli`() {
        // Vue PERSONNALISÉE inconnue : les attrs de View restent
        // proposables (toute vue Android EST une View — aapt le sait).
        val texte = "<MonBouton "
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), catalogue())
        val libelles = propositions.map { it.libelle }
        assertTrue(libelles.contains("android:id"))
        assertTrue(!libelles.contains("android:text"))
    }

    @Test
    fun `attributs — sans catalogue, tools seuls`() {
        val texte = "<Button "
        val propositions = CompletionXml.attributs(contexteAttribut(texte, texte.length), null)
        assertTrue(propositions.isNotEmpty())
        assertTrue(propositions.all { it.libelle.startsWith("tools:") })
    }
}
