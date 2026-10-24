package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.parse.ParseurXml
import jo.lspxml.resources.IndexRessources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests ADR 0211 (annexe D1-d de CodeIDE) — BRANCHEMENT de la session
 * XML : quand le MOTEUR possède le nourrissage
 * (`IndexRessources.nourrissageParMoteur`), `definirClasspath` NE
 * RE-BALAYE RIEN (l'index reste froid jusqu'à la publication de la
 * passe) ; hors moteur nourricier, le contrat SYNCHRONE historique
 * tient (le balayage a lieu ici et maintenant).
 */
class SessionLangageXmlA211Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Racine source avec res/ minimal. */
    private fun racineAvecRes(): File {
        val racine = dossierTemporaire.newFolder("src")
        val values = racine.resolve("res/values").apply { mkdirs() }
        values.resolve("strings.xml").writeText(
            "<resources><string name=\"app_nom\">CodeIDE</string></resources>",
        )
        return racine
    }

    private fun classpathXml(racines: List<File>): ClasspathXml =
        ClasspathXml(
            dossierPlateforme = null,
            racinesSources = racines,
            vueNoms = null,
            racinesAar = emptyList(),
        )

    /** Session sur index + catalogues NEUFS (miroir de la construction moteur). */
    private fun sessionNeuve(): Triple<SessionLangageXmlImpl, IndexRessources, CataloguesPartages> {
        val index = IndexRessources()
        val catalogues = CataloguesPartages()
        return Triple(SessionLangageXmlImpl(ParseurXml(), catalogues, index), index, catalogues)
    }

    @Test
    fun `contrat synchrone - hors moteur nourricier, definirClasspath balaye ici`() {
        val (session, index, _) = sessionNeuve()
        assertFalse(index.nourrissageParMoteur)
        session.definirClasspath(classpathXml(listOf(racineAvecRes())))
        assertTrue("l'index est chaud après le balayage synchrone", index.estChaud)
        assertEquals(listOf("app_nom"), index.nomsParType("string"))
        session.close()
    }

    @Test
    fun `branchement - moteur nourricier, definirClasspath ne re-balaye rien`() {
        val (session, index, _) = sessionNeuve()
        // Le moteur SONNE sa prise en main (passe en vol — l'index n'a
        // pas encore été nourri).
        index.sonnerNourrissageMoteur()
        assertTrue(index.nourrissageParMoteur)
        session.definirClasspath(classpathXml(listOf(racineAvecRes())))
        // BRANCHEMENT : l'index est resté FROID (la passe moteur
        // nourrira) — le supplier de connexion ne paie aucun balayage.
        assertFalse("la session n'a pas balayé sous moteur nourricier", index.estChaud)
        assertTrue(index.nomsParType("string").isEmpty())
        session.close()
    }

    @Test
    fun `sous-lecture guerit - la passe moteur nourrit l index partage`() {
        val (session, index, _) = sessionNeuve()
        index.sonnerNourrissageMoteur()
        val racine = racineAvecRes()
        session.definirClasspath(classpathXml(listOf(racine)))
        assertFalse(index.estChaud)
        // La passe moteur ABOUTIT (le moteur nourrit l'index PARTAGÉ) :
        // la session — qui tient le MÊME index par référence — voit les
        // ressources à la requête suivante.
        index.definir(listOf(racine))
        assertTrue(index.estChaud)
        assertEquals(listOf("app_nom"), index.nomsParType("string"))
        session.close()
    }
}
