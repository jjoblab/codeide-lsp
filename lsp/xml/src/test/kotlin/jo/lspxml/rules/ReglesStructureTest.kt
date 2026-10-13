package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.ParseurXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des RÈGLES DE STRUCTURE (XE1, §4.4) — une fixture PAR CODE de
 * diagnostic, plus les gardes conservatoires X4 (ce que la règle ne
 * sait pas, elle ne le dit pas).
 *
 * Convention des fixtures LAYOUT : la racine porte `layout_width`/
 * `layout_height` (ou est une balise SPÉCIALE) — la règle des tailles
 * est éprouvée SEULE sur les éléments qui la déclenchent.
 */
class ReglesStructureTest {
    private val parseur = ParseurXml()

    private fun diagnostiquer(
        source: String,
        genre: GenreDocument,
    ) = ReglesStructure.diagnostiquer(parseur.analyser(source), genre)

    private fun codes(source: String, genre: GenreDocument) = diagnostiquer(source, genre).map { it.code }

    private val racineSaine = "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\">"

    // ── GenreDocument ──────────────────────────────────────────────

    @Test
    fun `genre deduit de l'URI`() {
        assertEquals(GenreDocument.LAYOUT, GenreDocument.depuisUri("file:///p/res/layout/main.xml"))
        assertEquals(GenreDocument.LAYOUT, GenreDocument.depuisUri("file:///p/res/layout-land/main.xml"))
        assertEquals(GenreDocument.VALEURS, GenreDocument.depuisUri("file:///p/res/values/strings.xml"))
        assertEquals(GenreDocument.VALEURS, GenreDocument.depuisUri("file:///p/res/values-night/themes.xml"))
        assertEquals(GenreDocument.MANIFEST, GenreDocument.depuisUri("file:///p/app/src/main/AndroidManifest.xml"))
        // XE5 : drawables et menus ont leurs genres propres.
        assertEquals(GenreDocument.DRAWABLE, GenreDocument.depuisUri("file:///p/res/drawable/ic.xml"))
        assertEquals(GenreDocument.DRAWABLE, GenreDocument.depuisUri("file:///p/res/drawable-v24/ic.xml"))
        assertEquals(GenreDocument.MENU, GenreDocument.depuisUri("file:///p/res/menu/main.xml"))
        assertEquals(GenreDocument.AUTRE, GenreDocument.depuisUri("file:///p/res/xml/inconnu.xml"))
        assertEquals(GenreDocument.AUTRE, GenreDocument.depuisUri("file:///p/note.txt"))
    }

    // ── Règles génériques ──────────────────────────────────────────

    @Test
    fun `attribut duplique — marqueur sur le nom du second porteur`() {
        val source = "<r a=\"1\" a=\"2\" />"
        val diagnostics = diagnostiquer(source, GenreDocument.AUTRE)
        val diagnostic = diagnostics.single { it.code == CodesDiagnosticsXml.ATTRIBUT_DUPLIQUE }
        assertEquals(SeveriteXml.ERREUR, diagnostic.severite)
        assertEquals("a", source.substring(diagnostic.debut, diagnostic.fin))
    }

    @Test
    fun `attributs distincts — silence`() {
        assertEquals(0, codes("<r a=\"1\" b=\"2\" />", GenreDocument.AUTRE).size)
    }

    @Test
    fun `esperluette brute dans une valeur d'attribut`() {
        val source = "<r t=\"Tom & Jerry\" />"
        val diagnostics = diagnostiquer(source, GenreDocument.AUTRE)
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.single()
        assertEquals(CodesDiagnosticsXml.ENTITE_INVALIDE, diagnostic.code)
        // Position de l'esperluette DANS LA SOURCE (plage de la valeur).
        assertEquals(source.indexOf('&'), diagnostic.debut)
    }

    @Test
    fun `entite legale dans une valeur — silence`() {
        assertEquals(0, codes("<r t=\"&amp;ok&#65;\" />", GenreDocument.AUTRE).size)
    }

    @Test
    fun `esperluette brute dans le texte`() {
        val diagnostics = diagnostiquer("<r>Tom & Jerry</r>", GenreDocument.AUTRE)
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsXml.ENTITE_INVALIDE, diagnostics.single().code)
    }

    // ── Layout : xmlns ─────────────────────────────────────────────

    @Test
    fun `xmlns android manquant quand le prefixe est utilise`() {
        val source = "<LinearLayout android:layout_width=\"1\" android:layout_height=\"2\"/>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID, diagnostics.single().code)
        assertEquals("LinearLayout", source.substring(diagnostics.single().debut, diagnostics.single().fin))
    }

    @Test
    fun `xmlns android declare — silence meme tardif`() {
        // La déclaration vit sur un ENFANT : XML l'autorise, la règle
        // reste silencieuse (conservatoire — aapt tranchera).
        assertEquals(
            0,
            codes(
                "$racineSaine<c xmlns:android=\"u\" android:id=\"x\" " +
                    "android:layout_width=\"1\" android:layout_height=\"2\"/></r>",
                GenreDocument.LAYOUT,
            ).size,
        )
    }

    @Test
    fun `layout sans prefixe android — silence`() {
        assertEquals(0, codes("<merge tools:visibility=\"gone\"/>", GenreDocument.LAYOUT).size)
    }

    // ── Layout : tailles ───────────────────────────────────────────

    @Test
    fun `tailles manquantes — AVERTISSEMENT (hint, pas error)`() {
        val source = "$racineSaine<Button android:layout_width=\"1\"/></r>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        val diagnostic = diagnostics.single { it.code == CodesDiagnosticsXml.TAILLES_MANQUANTES }
        assertEquals(SeveriteXml.AVERTISSEMENT, diagnostic.severite)
        assertEquals("Button", source.substring(diagnostic.debut, diagnostic.fin))
    }

    @Test
    fun `tailles presentes — silence`() {
        assertEquals(
            0,
            codes(
                "$racineSaine<Button android:layout_width=\"1\" android:layout_height=\"2\"/></r>",
                GenreDocument.LAYOUT,
            ).size,
        )
    }

    @Test
    fun `balises speciales sans tailles exigees`() {
        val source = "<merge xmlns:android=\"u\"><include layout=\"@layout/x\"/></merge>"
        assertEquals(0, codes(source, GenreDocument.LAYOUT).size)
    }

    // ── Layout : ids ───────────────────────────────────────────────

    @Test
    fun `id plus duplique dans le fichier`() {
        val source =
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@+id/x\">" +
                "<a android:id=\"@+id/x\"/></r>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        val diagnostic = diagnostics.single { it.code == CodesDiagnosticsXml.ID_DUPLIQUE }
        assertEquals("@+id/x", source.substring(diagnostic.debut, diagnostic.fin))
    }

    @Test
    fun `ids distincts ou references simples — silence`() {
        val source =
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@+id/x\">" +
                "<a android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@+id/y\"/>" +
                "<b android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@id/x\"/></r>"
        assertEquals(0, codes(source, GenreDocument.LAYOUT).size)
    }

    // ── Layout : include / merge ───────────────────────────────────

    @Test
    fun `include sans layout`() {
        val source = "$racineSaine<include/></r>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsXml.INCLUDE_SANS_LAYOUT, diagnostics.single().code)
    }

    @Test
    fun `include avec layout — silence`() {
        assertEquals(0, codes("$racineSaine<include layout=\"@layout/x\"/></r>", GenreDocument.LAYOUT).size)
    }

    @Test
    fun `merge imbrique — erreur`() {
        val source = "$racineSaine<merge/></r>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        assertEquals(CodesDiagnosticsXml.MERGE_NON_RACINE, diagnostics.single().code)
        assertEquals("merge", source.substring(diagnostics.single().debut, diagnostics.single().fin))
    }

    @Test
    fun `merge en racine — silence`() {
        assertEquals(0, codes("<merge/>", GenreDocument.LAYOUT).size)
    }

    @Test
    fun `merge seconde racine — erreur`() {
        assertEquals(
            1,
            codes("<a/><merge/>", GenreDocument.LAYOUT).count { it == CodesDiagnosticsXml.MERGE_NON_RACINE },
        )
    }

    // ── Values ─────────────────────────────────────────────────────

    @Test
    fun `entree sans nom`() {
        val diagnostics = diagnostiquer("<resources><string/></resources>", GenreDocument.VALEURS)
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsXml.ENTREE_SANS_NOM, diagnostics.single().code)
    }

    @Test
    fun `entree nommee — silence`() {
        assertEquals(0, codes("<resources><string name=\"a\">x</string></resources>", GenreDocument.VALEURS).size)
    }

    @Test
    fun `item sous style sans nom de premier niveau — non exige`() {
        // `item` n'est jamais de premier niveau : PAS d'exigence name
        // (conservatoire — plurals utilise quantity).
        assertEquals(
            0,
            codes("<resources><style name=\"s\"><item>a</item></style></resources>", GenreDocument.VALEURS).size,
        )
    }

    @Test
    fun `nom duplique dans le fichier`() {
        val source = "<resources><string name=\"a\">1</string><string name=\"a\">2</string></resources>"
        val diagnostics = diagnostiquer(source, GenreDocument.VALEURS)
        assertEquals(1, diagnostics.size)
        assertEquals(CodesDiagnosticsXml.NOM_DUPLIQUE, diagnostics.single().code)
        assertEquals("string", source.substring(diagnostics.single().debut, diagnostics.single().fin))
    }

    @Test
    fun `meme nom types distincts — silence (ressources differentes)`() {
        val source = "<resources><string name=\"a\">1</string><color name=\"a\">2</color></resources>"
        assertEquals(0, codes(source, GenreDocument.VALEURS).size)
    }

    // ── Genres croisés (gardes X4) ─────────────────────────────────

    @Test
    fun `les regles de layout ne s'appliquent pas aux values`() {
        // Un values file n'a pas de layout_width à exiger.
        val source = "<resources><string name=\"a\">x</string></resources>"
        assertEquals(0, codes(source, GenreDocument.VALEURS).size)
    }

    @Test
    fun `les regles de values ne s'appliquent pas aux layouts`() {
        // Un layout sans `name` : rien à dire (les VUES n'ont pas de name).
        val source = "$racineSaine<Button android:layout_width=\"1\" android:layout_height=\"2\"/></r>"
        assertEquals(0, codes(source, GenreDocument.LAYOUT).size)
    }

    @Test
    fun `genre AUTRE — regles generiques seules`() {
        // drawable : pas de tailles, pas d'entrées nommées ; les
        // entités restent (XML générique).
        assertEquals(1, codes("<vector>&</vector>", GenreDocument.AUTRE).size)
    }

    @Test
    fun `diagnostics du document — toutes les regles de fichier concourent`() {
        val source =
            "<r xmlns:android=\"u\" android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@+id/x\">" +
                "<a android:layout_width=\"1\" android:layout_height=\"2\" android:id=\"@+id/x\"/>&</r>"
        val diagnostics = diagnostiquer(source, GenreDocument.LAYOUT)
        // L'ORDRE du document est posé par la SESSION (tri par
        // position) ; les règles rendent l'ordre du PARCOURS — ici :
        // l'id dupliqué de <a> ET l'esperluette finale, tous deux.
        assertEquals(
            setOf(CodesDiagnosticsXml.ID_DUPLIQUE, CodesDiagnosticsXml.ENTITE_INVALIDE),
            diagnostics.map { it.code }.toSet(),
        )
    }
}
