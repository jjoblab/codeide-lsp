package jo.codeide.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Empreintes par contenu (W6) : sha1 du chemin de cache Maven, haché de
 * transform, repli partiel (taille+mtime+contenu), dossier, module.
 */
class EmpreintesTest {
    private val stats = FauxStats()

    @Test
    fun `un jar du cache maven porte le sha1 de son chemin`() {
        val sha1 = "a".repeat(40)
        val chemin = "/gradle/caches/modules-2/files-2.1/com.exemple/artefact/1.2.3/$sha1/artefact-1.2.3.jar"

        val empreinte = Empreintes.empreinteFichier(chemin, stats.lecteur)

        assertEquals("maven:$sha1", empreinte)
    }

    @Test
    fun `deux versions differentes du meme artefact ont des empreintes differentes`() {
        val v1 =
            Empreintes.empreinteFichier(
                "/gradle/caches/modules-2/files-2.1/g/a/1.0.0/${"a".repeat(40)}/a-1.0.0.jar",
                stats.lecteur,
            )
        val v2 =
            Empreintes.empreinteFichier(
                "/gradle/caches/modules-2/files-2.1/g/a/2.0.0/${"b".repeat(40)}/a-2.0.0.jar",
                stats.lecteur,
            )
        assertTrue(v1 != v2)
    }

    @Test
    fun `un jar du cache de transforms porte le hache de transform et le nom du fichier`() {
        val chemin =
            "/gradle/caches/9.7.1/transforms/91d8547094a6bf7615ba0dae817fc867/" +
                "transformed/material-1.14.0/jars/classes.jar"

        val empreinte = Empreintes.empreinteFichier(chemin, stats.lecteur)

        assertEquals("transform:91d8547094a6bf7615ba0dae817fc867:classes.jar", empreinte)
    }

    @Test
    fun `repli - jar local par taille, mtime et empreinte partielle`() {
        stats.ajouter("/p/libs/outil.jar", taille = 2048L, mtime = 12_345L)

        val empreinte = Empreintes.empreinteFichier("/p/libs/outil.jar", stats.lecteur)

        assertTrue("préfixe partiel : $empreinte", empreinte.startsWith("partiel:contenu:2048:"))
        assertTrue(empreinte.endsWith(":12345"))
    }

    @Test
    fun `repli - un fichier disparu reste empreintable (illisible, taille 0)`() {
        val empreinte = Empreintes.empreinteFichier("/p/libs/fantome.jar", stats.lecteur)

        assertEquals("partiel:illisible:0:0", empreinte)
    }

    @Test
    fun `un dossier porte son mtime`() {
        stats.ajouter("/p/app/build/classes", taille = 0L, mtime = 99L)

        val empreinte = Empreintes.empreinteDossier("/p/app/build/classes", stats.lecteur)

        assertEquals("dossier:99", empreinte)
    }

    @Test
    fun `une dependance de module a une identite nominale`() {
        assertEquals("module::core:ui", Empreintes.empreinteModule(":core:ui"))
    }

    @Test
    fun `sha256 est stable et hexadecimale`() {
        assertEquals(
            Empreintes.sha256("essai"),
            Empreintes.sha256("essai"),
        )
        assertEquals(64, Empreintes.sha256("essai").length)
        assertTrue(Empreintes.sha256("essai").all { it in "0123456789abcdef" })
    }
}

/** Identité de bibliothèque extraite des chemins de cache (doublons). */
class IdentiteBibliothequeTest {
    @Test
    fun `chemin maven - groupe et artefact`() {
        val chemin = "/g/caches/modules-2/files-2.1/com.google.guava/guava/33.4.0/${"a".repeat(40)}/guava-33.4.0.jar"

        assertEquals("com.google.guava:guava", ConvertisseurModeleLsp.identiteBibliotheque(chemin))
    }

    @Test
    fun `chemin transform - artefact sans version`() {
        val chemin = "/g/caches/9.7.1/transforms/hache/transformed/play-services-maps-19.2.0/jars/classes.jar"

        assertEquals(
            "play-services-maps",
            ConvertisseurModeleLsp.identiteBibliotheque(chemin),
        )
    }

    @Test
    fun `chemin quelconque - non determinable`() {
        assertEquals(null, ConvertisseurModeleLsp.identiteBibliotheque("/p/libs/outil.jar"))
    }
}
