package jo.lspxml.resources

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
 * Tests de l'INDEX des ressources du projet (XE3, §4.1 « resources ») —
 * entrées nommées de `values`, ressources de DOSSIER (layouts…), scan
 * INCREMENTAL par mtime, purge des fichiers disparus.
 */
class IndexRessourcesTest {
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    /** Racine source avec res/ complet (values + layout + drawable). */
    private fun racineProjet(): File {
        val racine = dossierTemp.newFolder("src")
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText(
            "<resources>" +
                "<string name=\"app_nom\">CodeIDE</string>" +
                "<string name=\"autre\">Autre</string>" +
                "<dimen name=\"marge\">8dp</dimen>" +
                "</resources>",
        )
        // Le COLOR vit dans styles.xml : il doit SURVIVRE à la
        // suppression de strings.xml (fixture du test de purge).
        values.resolve("styles.xml").writeText(
            "<resources>" +
                "<color name=\"principal\">#FF0000</color>" +
                "<style name=\"Theme.App\" parent=\"Theme.Material\"/>" +
                "</resources>",
        )
        val layout = racine.resolve("res/layout").apply { mkdirs() }
        layout.resolve("activity_main.xml").writeText("<merge/>")
        racine.resolve("res/layout-land").apply { mkdirs() }
            .resolve("secondaire.xml").writeText("<merge/>")
        val drawable = racine.resolve("res/drawable").apply { mkdirs() }
        drawable.resolve("ic_logo.xml").writeText("<vector/>")
        drawable.resolve("photo.png").writeBytes(byteArrayOf(1))
        return racine
    }

    @Test
    fun `scan — entrees nommees et ressources de dossier`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        assertTrue(index.estChaud)
        // Entrées nommées (values).
        assertEquals(
            listOf("app_nom", "autre"),
            index.nomsParType("string"),
        )
        assertEquals(listOf("principal"), index.nomsParType("color"))
        assertEquals(listOf("marge"), index.nomsParType("dimen"))
        assertEquals(listOf("Theme.App"), index.nomsParType("style"))
        // Ressources de dossier (layout, layout-land, drawable).
        assertEquals(setOf("activity_main", "secondaire"), index.nomsParType("layout").toSet())
        assertEquals(setOf("ic_logo", "photo"), index.nomsParType("drawable").toSet())
        // Le déclarant d'une entrée nommée pointe le FICHIER + la plage du nom.
        val declaration = index.resoudre("string", "app_nom")
        assertNotNull(declaration)
        assertEquals("strings.xml", declaration!!.fichier.name)
        assertTrue(declaration.plage.debut > 0)
        // Le déclarant d'une ressource de dossier : le fichier, plage 0.
        val layout = index.resoudre("layout", "activity_main")
        assertNotNull(layout)
        assertEquals("activity_main.xml", layout!!.fichier.name)
        assertEquals(0, layout.plage.debut)
    }

    @Test
    fun `racines sans res ignorees — aucune entree`() {
        val index = IndexRessources()
        val jvm = dossierTemp.newFolder("jvm")
        jvm.resolve("Main.java").writeText("class Main {}")
        index.definir(listOf(jvm))
        // Les racines SONT définies (pas de dumb mode) mais sans res/ :
        // aucune entrée — le verdict « non résolu » resterait honnête.
        assertTrue(index.estChaud)
        assertTrue(index.types().isEmpty())
        // SANS racine : index froid (dumb mode, X4).
        assertFalse(IndexRessources().estChaud)
    }

    @Test
    fun `resolution — inconnu null, deuxieme declarant resolu`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        assertNull(index.resoudre("string", "inconnu"))
        assertNull(index.resoudre("genreAbsent", "x"))
        assertNotNull(index.resoudre("drawable", "photo"))
    }

    @Test
    fun `scan increment — mtime inchangé ne re-parse pas, fichier change re-scanne`() {
        val racine = racineProjet()
        val index = IndexRessources()
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "autre"), index.nomsParType("string"))
        // Ré-définition des MÊMES racines : le fichier inchangé garde
        // son entrée (mtime identique — pas de re-parse).
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "autre"), index.nomsParType("string"))
        // Ajout d'une entrée : le fichier CHANGE (mtime), re-scan.
        racine.resolve("res/values/strings.xml").writeText(
            "<resources>" +
                "<string name=\"app_nom\">CodeIDE</string>" +
                "<string name=\"nouvelle\">X</string>" +
                "</resources>",
        )
        index.definir(listOf(racine))
        assertEquals(listOf("app_nom", "nouvelle"), index.nomsParType("string"))
    }

    @Test
    fun `purge — un fichier disparu retire ses entrées`() {
        val racine = racineProjet()
        val index = IndexRessources()
        index.definir(listOf(racine))
        assertNotNull(index.resoudre("color", "principal"))
        racine.resolve("res/values/strings.xml").delete()
        index.definir(listOf(racine))
        assertNull("les entrées du fichier disparu partent", index.resoudre("string", "app_nom"))
        // Les AUTRES fichiers survivent.
        assertNotNull(index.resoudre("color", "principal"))
    }

    @Test
    fun `vider — tout part`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        index.vider()
        assertFalse(index.estChaud)
        assertTrue(index.types().isEmpty())
    }

    // ── P3 — volet BIBLIOTHÈQUES (AAR) ─────────────────────────────

    /** Archive AAR synthétique (ZIP) portant values + layout + drawable. */
    private fun ecrireAar(
        dossier: File,
        nom: String,
        valeurs: String,
    ): File {
        val aar = dossier.resolve(nom)
        java.util.zip.ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("res/values/values.xml"))
            zip.write(valeurs.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("res/layout/eleve.xml"))
            zip.write("<merge/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("res/drawable-hdpi/puce.png"))
            zip.write(byteArrayOf(1, 2))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
        }
        return aar
    }

    /** Values d'AAR Material fictif : named entries + styleable + item. */
    private fun valeursMaterial(): String =
        "<resources>" +
            "<string name=\"material_hello\">Bonjour</string>" +
            "<color name=\"material_red\">#FF0000</color>" +
            "<style name=\"Theme.Material3.Day\" parent=\"android:Theme.Material\"/>" +
            "<attr name=\"material_btn\" format=\"reference\"/>" +
            "<declare-styleable name=\"MaterialButton\">" +
            "<attr name=\"material_shape\"/>" +
            "<attr name=\"material_btn\"/>" +
            "</declare-styleable>" +
            "<item type=\"id\" name=\"material_id\"/>" +
            "</resources>"

    @Test
    fun `scan aar — entrees nommees, attrs de styleable, item type, dossiers`() {
        val index = IndexRessources()
        val aar = ecrireAar(dossierTemp.newFolder("libs"), "material-1.12.0.aar", valeursMaterial())
        index.definirAar(listOf(aar))
        assertTrue(index.estChaudLib)
        assertFalse(index.douteux)
        // Entrées nommées (values).
        assertEquals(listOf("material_hello"), index.nomsLibParType("string"))
        assertEquals(listOf("material_red"), index.nomsLibParType("color"))
        assertEquals(listOf("Theme.Material3.Day"), index.nomsLibParType("style"))
        // Fidélité aapt : l'attr porté par un declare-styleable existe.
        assertEquals(setOf("material_btn", "material_shape"), index.nomsLibParType("attr").toSet())
        // item type="id" : la ressource vit sous le type nommé.
        assertEquals(listOf("material_id"), index.nomsLibParType("id"))
        // Ressources de dossier (layout, drawable — qualifier compris).
        assertEquals(listOf("eleve"), index.nomsLibParType("layout"))
        assertEquals(listOf("puce"), index.nomsLibParType("drawable"))
        // Le déclarant pointe le CONTENEUR (chemin de l'AAR).
        assertEquals(aar.path, index.resoudreLib("string", "material_hello"))
        assertEquals(aar.path, index.resoudreLib("layout", "eleve"))
        // Types du volet lib.
        assertEquals(
            setOf("string", "color", "style", "attr", "id", "layout", "drawable"),
            index.typesLib(),
        )
        // Le volet PROJET reste vierge (cartes séparées).
        assertNull(index.resoudre("string", "material_hello"))
        assertTrue(index.types().isEmpty())
    }

    @Test
    fun `scan aar — increment mtime, purge des disparus`() {
        val index = IndexRessources()
        val dossier = dossierTemp.newFolder("libsincrement")
        val aar = ecrireAar(dossier, "material.aar", valeursMaterial())
        index.definirAar(listOf(aar))
        assertEquals(listOf("material_hello"), index.nomsLibParType("string"))
        // Ré-définition IDENTIQUE : no-op chaud (l'AAR inchangé n'est
        // jamais re-décodé — les entrées restent).
        index.definirAar(listOf(aar))
        assertEquals(listOf("material_hello"), index.nomsLibParType("string"))
        // L'AAR CHANGE (re-écriture + mtime avancé) : re-scan.
        val nouvelle =
            ecrireAar(dossier, "material.aar", "<resources><string name=\"autre\">X</string></resources>")
        nouvelle.setLastModified(aar.lastModified() + 5_000)
        index.definirAar(listOf(nouvelle))
        assertEquals(listOf("autre"), index.nomsLibParType("string"))
        // Les entrées de l'ANCIENNES version ont disparu (index honnête).
        assertNull(index.resoudreLib("style", "Theme.Material3.Day"))
        // L'AAR DISPARU de la liste purge TOUT son volet.
        index.definirAar(emptyList())
        assertTrue(index.typesLib().isEmpty())
        assertTrue("le volet reste alimenté (même vide)", index.estChaudLib)
        assertFalse(index.douteux)
    }

    @Test
    fun `scan aar — archive corrompue = doute persistant, les saines survivent`() {
        val index = IndexRessources()
        val dossier = dossierTemp.newFolder("libsdoute")
        val sain = ecrireAar(dossier, "material.aar", valeursMaterial())
        val corrompu = dossier.resolve("casse.aar")
        corrompu.writeBytes(byteArrayOf(0, 1, 2, 3, 4)) // PAS un ZIP
        index.definirAar(listOf(sain, corrompu))
        assertTrue("un AAR illisible marque le doute (X4)", index.douteux)
        // Les entrées de l'AAR SAINE survivent.
        assertEquals(listOf("material_hello"), index.nomsLibParType("string"))
        // Le doute PERSISTE tant que l'AAR ne change pas (les passes
        // suivantes l'épargnent par mtime — le doute ne s'efface pas).
        index.definirAar(listOf(sain, corrompu))
        assertTrue(index.douteux)
    }

    @Test
    fun `vider — le volet bibliothèques part aussi`() {
        val index = IndexRessources()
        index.definir(listOf(racineProjet()))
        val aar = ecrireAar(dossierTemp.newFolder("libsvider"), "m.aar", valeursMaterial())
        index.definirAar(listOf(aar))
        assertTrue(index.estChaudLib)
        index.vider()
        assertFalse(index.estChaudLib)
        assertFalse(index.douteux)
        assertTrue(index.typesLib().isEmpty())
        assertNull(index.resoudreLib("string", "material_hello"))
    }
}
