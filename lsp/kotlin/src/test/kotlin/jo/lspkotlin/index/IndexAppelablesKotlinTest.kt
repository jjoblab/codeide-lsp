package jo.lspkotlin.index

import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de l'index des appelables (K3) — le CONTRAT DISQUE est le cœur
 * du test : un segment écrit puis relu SANS le jar d'origine (supprimé)
 * prouve la persistance ; un segment CORROMPU se re-décoderait (jar
 * absent → partiel honnête, jamais d'exception).
 */
class IndexAppelablesKotlinTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    private fun entreeStdlib(empreinte: String): EntreeClasspathKotlin =
        EntreeClasspathKotlin(KindEntreeKotlin.JAR, jarStdlib().path, empreinte)

    @Test
    fun `segment persiste - le jar supprime est encore servi par le cache`() {
        val stockage = dossierTemporaire.newFolder("index")
        // COPIE du stdlib sous l'empreinte de test (le jar original du
        // classpath ne doit pas être supprimé !).
        val copie = dossierTemporaire.newFile("stdlib.jar")
        copie.writeBytes(jarStdlib().readBytes())
        val entree = EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, "test:stdlib-1")

        val index = IndexAppelablesKotlin()
        index.definir(listOf(entree), stockage)
        val nommes = index.extensionsSur("kotlin.String")
        assertFalse("le stdlib doit offrir des extensions de String", nommes.isEmpty())

        // Le jar DISPARAÎT : la redéfinition doit être servie par le
        // SEGMENT (l'index est un cache, ADR 0111 §1).
        assertTrue("la copie de travail doit exister pour ce test", copie.delete())
        val indexRelu = IndexAppelablesKotlin()
        indexRelu.definir(listOf(entree), stockage)
        val nommesRelus = indexRelu.extensionsSur("kotlin.String")
        assertFalse("segment attendu : les extensions doivent survivre au jar", nommesRelus.isEmpty())
    }

    @Test
    fun `memoire seule - index fonctionnel sans dossier`() {
        val index = IndexAppelablesKotlin()
        index.definir(listOf(entreeStdlib("test:mem")), null)
        assertFalse(index.extensionsSur("kotlin.String").isEmpty())
        assertTrue(index.nomsTopParPrefixe("tri", 10).isNotEmpty())
        assertFalse(index.topParNom("trim").isEmpty())
    }

    @Test
    fun `entree inexistante - partiel honnete, jamais d'exception`() {
        val index = IndexAppelablesKotlin()
        index.definir(
            listOf(EntreeClasspathKotlin(KindEntreeKotlin.JAR, "/inexistant.jar", "test:absent")),
            dossierTemporaire.newFolder("index2"),
        )
        assertTrue(index.extensionsSur("kotlin.String").isEmpty())
        assertTrue(index.topParNom("trim").isEmpty())
    }

    @Test
    fun `recherche par prefixe - binaire bornee et limite respectee`() {
        val index = IndexAppelablesKotlin()
        index.definir(listOf(entreeStdlib("test:prefixe")), null)
        val cinq = index.nomsTopParPrefixe("tr", 5)
        assertTrue(cinq.size <= 5)
        assertTrue(cinq.all { it.startsWith("tr") })
        // Préfixe introuvable : liste vide (pas de scan complet).
        assertTrue(index.nomsTopParPrefixe("zzzzzzzz", 5).isEmpty())
    }

    // ── ADR 0211/0214 (demandes D1/D4 de CodeIDE) ──────────────────────

    @Test
    fun `garde d empreintes - definir deux fois est un no-op chaud`() {
        val index = IndexAppelablesKotlin()
        val saine = entreeStdlib("test:garde-1")
        val autre = entreeStdlib("test:garde-2")
        val entrees = listOf(saine, autre)
        assertEquals(
            IndexAppelablesKotlin.ResultatDefin.RECONSTRUIT,
            index.definir(entrees, null),
        )
        // Deuxième appel : le snapshot COUVRE — aucune relecture (l'issue
        // d'observation ne se déclenche JAMAIS).
        var issues = 0
        val resultat =
            index.definir(entrees, null) { _, issue ->
                if (issue != null) issues++
                true
            }
        assertEquals(IndexAppelablesKotlin.ResultatDefin.DEJA_COUVERT, resultat)
        assertEquals(0, issues)
        assertTrue(index.couvre(entrees))
        assertFalse("un ordre différent n'est pas couvert", index.couvre(entrees.reversed()))
        assertFalse("une entrée différente n'est pas couverte", index.couvre(listOf(saine)))
    }

    @Test
    fun `cache memoire - le dossier efface ne re-relit rien`() {
        val stockage = dossierTemporaire.newFolder("index-cache")
        val copie = dossierTemporaire.newFile("stdlib-cache.jar")
        copie.writeBytes(jarStdlib().readBytes())
        val entreeA = EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, "test:cache-a")
        val entreeB = EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, "test:cache-b")
        val index = IndexAppelablesKotlin()
        // Les DEUX empreintes vivent en cache mémoire (chacune a été lue
        // une fois, à des ordres différents — la garde ne bloque que la
        // liste EXACTE).
        index.definir(listOf(entreeA), stockage)
        index.definir(listOf(entreeB, entreeA), stockage)
        assertFalse(index.extensionsSur("kotlin.String").isEmpty())

        // Le disque DISPARAÎT tout entier (segments ET jar) : une
        // redéfinition à ordre DIFFÉRENT échappe à la garde mais est
        // servie par le CACHE MÉMOIRE — tout est REPRIS, aucune lecture.
        stockage.deleteRecursively()
        assertTrue("la copie de travail doit exister pour ce test", copie.delete())
        var reprises = 0
        val resultat =
            index.definir(listOf(entreeA, entreeB), stockage) { _, issue ->
                if (issue == IndexAppelablesKotlin.IssueSegment.REPRIS) reprises++
                true
            }
        assertEquals(IndexAppelablesKotlin.ResultatDefin.RECONSTRUIT, resultat)
        assertEquals("les deux entrées sont reprises du cache mémoire", 2, reprises)
        assertFalse("les extensions survivent au disque effacé", index.extensionsSur("kotlin.String").isEmpty())
        assertTrue(index.couvre(listOf(entreeA, entreeB)))
    }

    @Test
    fun `echec jamais en cache - la reprise retente toujours l illisible`() {
        val stockage = dossierTemporaire.newFolder("index-echec")
        val saine = entreeStdlib("test:echec-saine")
        val absente = EntreeClasspathKotlin(KindEntreeKotlin.JAR, "/inexistant-reprise.jar", "test:echec-absente")
        val index = IndexAppelablesKotlin()
        val issuesPremier = mutableListOf<IndexAppelablesKotlin.IssueSegment>()
        index.definir(listOf(saine, absente), stockage) { _, issue ->
            if (issue != null) issuesPremier.add(issue)
            true
        }
        assertEquals(
            listOf(IndexAppelablesKotlin.IssueSegment.TERMINE, IndexAppelablesKotlin.IssueSegment.ECARTE),
            issuesPremier,
        )
        // La REPRISE ciblée (ADR 0214) : la saine est REPRIS du cache,
        // l'absente est RETENTÉE (un échec n'est jamais mis en cache).
        val issuesReprise = mutableListOf<IndexAppelablesKotlin.IssueSegment>()
        index.definir(listOf(saine, absente), stockage) { _, issue ->
            if (issue != null) issuesReprise.add(issue)
            true
        }
        assertEquals(
            listOf(IndexAppelablesKotlin.IssueSegment.REPRIS, IndexAppelablesKotlin.IssueSegment.ECARTE),
            issuesReprise,
        )
        // Le snapshot couvre les entrées SAINES seules (l'absente n'y
        // entre pas) — la couverture exige la liste EXACTE.
        assertFalse(index.couvre(listOf(saine, absente)))
    }

    @Test
    fun `annulation - le snapshot partiel est la verite`() {
        val stockage = dossierTemporaire.newFolder("index-annul")
        val copie = dossierTemporaire.newFile("stdlib-annul.jar")
        copie.writeBytes(jarStdlib().readBytes())
        val premiere = EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, "test:annul-1")
        val seconde = EntreeClasspathKotlin(KindEntreeKotlin.JAR, copie.path, "test:annul-2")
        val index = IndexAppelablesKotlin()
        val resultat =
            index.definir(listOf(premiere, seconde), stockage) { _, _ -> false } // annule après la 1re
        assertEquals(IndexAppelablesKotlin.ResultatDefin.PARTIEL_ANNULE, resultat)
        // L'ACQUIS de la première entrée est publié — la seconde pend.
        assertFalse(index.extensionsSur("kotlin.String").isEmpty())
        assertFalse(index.couvre(listOf(premiere, seconde)))
        assertTrue("la première entrée seule est couverte", index.couvre(listOf(premiere)))
    }
}
