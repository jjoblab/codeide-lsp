package jo.lspkotlin.index

import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
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
}
