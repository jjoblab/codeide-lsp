package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de l'analyseur de classpath SYSTÈME (vrais fichiers) : jar,
 * AAR normalisé à l'indexation (classes.jar + libs), dossier par
 * signature d'arborescence, reprise par prédicat.
 */
class AnalyseurClasspathSystemeTest {
    private val analyseur = AnalyseurClasspathSysteme(LecteurZipBrut())
    private val base = FixturesIndex.dossierTemporaire("analyseur")

    private fun entreeJar(
        chemin: String,
        empreinte: String,
        fichier: File,
    ): EntreeAIndexer =
        EntreeAIndexer(
            chemin = chemin,
            type = TypeEntreeIndex.JAR,
            empreinte = empreinte,
            portee = "compile",
            taille = fichier.length(),
            mtime = fichier.lastModified(),
        )

    @Test
    fun `analyse un jar réel — entrées, membres, META-INF écarté`() {
        val jar = FixturesIndex.ecrireZip(File(base, "stdlib.jar"), FixturesIndex.jarStandard())
        val resultat = analyseur.analyser(entreeJar(jar.path, "maven:jar-1", jar)) { false }

        assertTrue(resultat is ResultatAnalyse.Ok)
        val donnees = (resultat as ResultatAnalyse.Ok).donnees
        // module-info.class et META-INF sont écartés.
        assertEquals(
            listOf(
                "alpha/Alpha\$1.class",
                "alpha/Alpha\$Interne.class",
                "alpha/Alpha.class",
                "beta/beta/Profond.class",
            ),
            donnees.entrees,
        )
        assertEquals("maven:jar-1", donnees.cle)
        assertEquals(Conteneur.Jar(jar.path), donnees.conteneur)
        assertTrue(donnees.membres[2] != null)
        assertTrue(donnees.origines == null)
    }

    @Test
    fun `reprise d'un jar évite l'analyse (prédicat consulté)`() {
        val jar = FixturesIndex.ecrireZip(File(base, "reprise.jar"), FixturesIndex.jarStandard())
        var consultations = 0

        val resultat =
            analyseur.analyser(entreeJar(jar.path, "maven:jar-2", jar)) { cle ->
                consultations++
                cle == "maven:jar-2"
            }

        assertEquals(1, consultations)
        assertTrue(resultat is ResultatAnalyse.Reprise)
        assertEquals("maven:jar-2", (resultat as ResultatAnalyse.Reprise).cle)
    }

    @Test
    fun `analyse un AAR — normalisation classes et libs, origines`() {
        val classes = FixturesIndex.octetsZip(mapOf("aar/Utile.class" to FixturesIndex.octetsClasse()))
        val lib = FixturesIndex.octetsZip(mapOf("lib/Extra.class" to FixturesIndex.octetsClasse()))
        val aar =
            FixturesIndex.ecrireZip(
                File(base, "biblio.aar"),
                mapOf(
                    "classes.jar" to classes,
                    "libs/extra.jar" to lib,
                    "AndroidManifest.xml" to "<manifest/>".toByteArray(),
                ),
            )
        val entree =
            EntreeAIndexer(
                chemin = aar.path,
                type = TypeEntreeIndex.ARR,
                empreinte = "maven:aar-1",
                portee = null,
                taille = aar.length(),
                mtime = aar.lastModified(),
            )

        val resultat = analyseur.analyser(entree) { false }
        assertTrue(resultat is ResultatAnalyse.Ok)
        val donnees = (resultat as ResultatAnalyse.Ok).donnees
        assertEquals(listOf("aar/Utile.class", "lib/Extra.class"), donnees.entrees)
        val conteneur = donnees.conteneur as Conteneur.Arr
        assertEquals(listOf("classes.jar", "libs/extra.jar"), conteneur.jarsInternes)
        assertEquals(intArrayOf(0, 1).toList(), donnees.origines!!.toList())
        // La clé AAR est dérivée, pas l'empreinte brute.
        assertEquals(ClesSegments.cleArr("maven:aar-1", conteneur.jarsInternes), donnees.cle)
    }

    @Test
    fun `AAR sans jar interne est illisible (signalé, pas de crash)`() {
        val aar =
            FixturesIndex.ecrireZip(
                File(base, "vide.aar"),
                mapOf("AndroidManifest.xml" to "<manifest/>".toByteArray()),
            )
        val entree =
            EntreeAIndexer(
                chemin = aar.path,
                type = TypeEntreeIndex.ARR,
                empreinte = "maven:aar-vide",
                portee = null,
                taille = aar.length(),
                mtime = aar.lastModified(),
            )
        val resultat = analyseur.analyser(entree) { false }
        assertTrue(resultat is ResultatAnalyse.Illisible)
        assertTrue((resultat as ResultatAnalyse.Illisible).raison.contains("sans jar interne"))
    }

    @Test
    fun `analyse un dossier de classes — signature egale clé, reprise par clé`() {
        val dossier = File(base, "sorties")
        File(dossier, "jo/exemple").mkdirs()
        File(dossier, "jo/exemple/Machine.class").writeBytes(FixturesIndex.octetsClasse())
        File(dossier, "jo/exemple/Autre.class").writeBytes(FixturesIndex.octetsClasse())

        val entree =
            EntreeAIndexer(
                chemin = dossier.path,
                type = TypeEntreeIndex.DOSSIER,
                empreinte = "dossier:${dossier.lastModified()}",
                portee = null,
                taille = 0L,
                mtime = dossier.lastModified(),
            )
        val resultat = analyseur.analyser(entree) { false }
        assertTrue(resultat is ResultatAnalyse.Ok)
        val donnees = (resultat as ResultatAnalyse.Ok).donnees
        assertEquals(listOf("jo/exemple/Autre.class", "jo/exemple/Machine.class"), donnees.entrees)
        assertEquals(Conteneur.Dossier(dossier.path), donnees.conteneur)

        // Reprise : même signature → prédicat consulté avec la même clé.
        var cleConsultee: String? = null
        val reprise =
            analyseur.analyser(entree) { cle ->
                cleConsultee = cle
                true
            }
        assertTrue(reprise is ResultatAnalyse.Reprise)
        assertEquals(donnees.cle, cleConsultee)
    }

    @Test
    fun `dossier absent ou vide est illisible (honnête)`() {
        val absent =
            EntreeAIndexer("chemin/absent", TypeEntreeIndex.DOSSIER, "dossier:1", null, 0L, 0L)
        assertTrue(analyseur.analyser(absent) { false } is ResultatAnalyse.Illisible)

        val vide = File(base, "vide")
        vide.mkdirs()
        val entreeVide =
            EntreeAIndexer(vide.path, TypeEntreeIndex.DOSSIER, "dossier:2", null, 0L, vide.lastModified())
        val resultat = analyseur.analyser(entreeVide) { false }
        assertTrue(resultat is ResultatAnalyse.Illisible)
    }

    @Test
    fun `jar brouillé est illisible (EOCD introuvable)`() {
        val fichier = File(base, "brouille.jar")
        fichier.writeBytes("pas un zip du tout".toByteArray())
        val resultat = analyseur.analyser(entreeJar(fichier.path, "maven:brouille", fichier)) { false }
        assertTrue(resultat is ResultatAnalyse.Illisible)
    }
}
