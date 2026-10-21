package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.GenreSurlignageXml
import jo.lspxml.api.ServiceMoteurLangageXml
import jo.lspxml.api.SessionLangageXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests des RÉFÉRENCES et du SURLIGNAGE d'occurrences XML (lot L3 du
 * plan 1.3.0) — par le SPI (chemin de production D6), moteur RÉEL,
 * montage projet : fichier `values` DÉCLARANT sur disque (l'index des
 * ressources le prouve) + documents OUVERTS (le bornage du balayage).
 *
 * Le cœur éprouvé : la MÊME preuve que le renommage borné — ce que
 * l'index ne prouve pas n'apparaît pas ; la référence framework
 * (`@android:…`) ne rend RIEN ; le surlignage distingue la DÉCLARANTE
 * `@+id` (écriture) des références (lectures) dans le document courant.
 */
class ReferencesXmlTest {
    /** Dossier temporaire du projet (racines des ressources). */
    @get:Rule
    public val dossierTemp: TemporaryFolder = TemporaryFolder()

    /** Décalage du PREMIER caractère de [occurrence] dans [texte]. */
    private fun decalageDe(
        texte: String,
        occurrence: String,
        numero: Int = 0,
    ): Int {
        var index = -1
        repeat(numero + 1) {
            index = texte.indexOf(occurrence, index + 1)
        }
        return index
    }

    /**
     * Session dont l'index connaît [fichiers] (racines sources du
     * classpath — le scan des ressources y lit les fichiers fermés).
     */
    private fun sessionAvecRacines(vararg fichiers: Pair<String, String>): SessionLangageXml {
        // Contrat d'index : le `res/` vit DANS la racine livrée
        // (racine/res/values/… — miroir du montage IndexRessourcesTest).
        val racine = dossierTemp.newFolder("src${System.nanoTime()}")
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine.resolve("res"), chemin)
            fichier.parentFile.mkdirs()
            fichier.writeText(contenu)
        }
        val session = ServiceMoteurLangageXml.unique().creerSession()
        session.definirClasspath(
            ClasspathXml(dossierPlateforme = null, racinesSources = listOf(racine), vueNoms = null),
        )
        return session
    }

    @Test
    fun `references d'un string - declaration de l'index puis usages des documents ouverts`() {
        val strings =
            """
            <resources>
                <string name="app_nom">CodeIDE</string>
            </resources>
            """.trimIndent()
        val layout =
            """
            <LinearLayout>
                <TextView text="@string/app_nom" />
                <Button text="@string/app_nom" />
                <TextView text="@string/autre" />
            </LinearLayout>
            """.trimIndent()
        val session = sessionAvecRacines("values/strings.xml" to strings, "layout/main.xml" to layout)
        session.ouvrirDocument("fixture://main.xml", layout)
        val references =
            session.references("fixture://main.xml", decalageDe(layout, "app_nom"), inclureDeclaration = true)
        // La DÉCLARATION (fichier values, disque) en tête, puis les
        // DEUX usages du document ouvert ; l'autre string n'apparaît pas.
        assertEquals(3, references.size)
        val declaration = references.first()
        assertTrue(declaration.uri.endsWith("strings.xml"))
        assertEquals(decalageDe(strings, "app_nom"), declaration.debut)
        assertTrue(references.drop(1).all { it.uri == "fixture://main.xml" })
        // Les usages portent la plage du SEGMENT NOM (après « @string/ »).
        assertEquals(
            listOf(
                decalageDe(layout, "@string/app_nom") + "@string/".length,
                decalageDe(layout, "@string/app_nom", 1) + "@string/".length,
            ),
            references.drop(1).map { it.debut },
        )
        // Sans la déclaration : les deux usages seulement.
        assertEquals(
            2,
            session.references("fixture://main.xml", decalageDe(layout, "app_nom"), inclureDeclaration = false).size,
        )
        session.close()
    }

    @Test
    fun `references d'un id declare @+id - declarante en ecriture, usages en lecture`() {
        val layout =
            """
            <LinearLayout>
                <Button id="@+id/valider" />
                <Button id="@id/valider" />
            </LinearLayout>
            """.trimIndent()
        val session = sessionAvecRacines("layout/main.xml" to layout)
        session.ouvrirDocument("fixture://main.xml", layout)
        // Curseur sur la DÉCLARANTE `@+id/valider`.
        val surlignages = session.surlignagesDocument("fixture://main.xml", decalageDe(layout, "valider"))
        assertEquals(2, surlignages.size)
        // La déclarante en ÉCRITURE, la référence en LECTURE.
        assertEquals(GenreSurlignageXml.ECRITURE, surlignages.first().genre)
        assertEquals(GenreSurlignageXml.LECTURE, surlignages.last().genre)
        assertEquals(decalageDe(layout, "valider"), surlignages.first().debut)
        assertEquals(decalageDe(layout, "valider", 1), surlignages.last().debut)
        session.close()
    }

    @Test
    fun `references framework et inconnues - vide, jamais devine`() {
        val layout =
            """
            <LinearLayout id="@android:id/texte">
                <Button text="@string/absente" />
            </LinearLayout>
            """.trimIndent()
        val session = sessionAvecRacines("layout/main.xml" to layout)
        session.ouvrirDocument("fixture://main.xml", layout)
        // Référence framework : ressource SYSTÈME, jamais servie.
        val uriMain = "fixture://main.xml"
        assertTrue(
            session.references(uriMain, decalageDe(layout, "id/texte"), inclureDeclaration = true).isEmpty(),
        )
        assertTrue(
            session.surlignagesDocument("fixture://main.xml", decalageDe(layout, "id/texte")).isEmpty(),
        )
        // Curseur hors ressource : vide.
        assertTrue(
            session.references(uriMain, decalageDe(layout, "LinearLayout"), inclureDeclaration = true).isEmpty(),
        )
        // Document inconnu : vide, jamais d'exception.
        assertTrue(session.references("fixture://inconnu.xml", 0, inclureDeclaration = true).isEmpty())
        session.close()
    }
}
