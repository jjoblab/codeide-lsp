package jo.codeide.lsp.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests du format binaire des segments — ALLER-RETOUR intégral (toutes
 * les sections), corruption (crc, magie, troncature), migration de
 * version (ré-indexation paresseuse = refus propre, jamais de crash),
 * recoupement de clé et statistiques source (W7).
 */
@Suppress("TooManyFunctions")
class FormatSegmentTest {
    @get:Rule
    val dossier = TemporaryFolder()

    /** Données complètes : entrées, paquets, noms simples, membres, AAR. */
    private fun donneesCompletes(): DonneesSegment {
        val entrees =
            listOf(
                "alpha/Alpha.class",
                "alpha/Alpha\$Interne.class",
                "beta/beta/Profond.class",
            )
        val membres =
            listOf(
                MembresClasse(0x0021, listOf(Membre(0x0009, "compteur", "I")), listOf(Membre(0x0001, "<init>", "()V"))),
                MembresClasse(0x0020, emptyList(), emptyList()),
                null,
            )
        return AssembleurSegment.assembler(
            conteneur = Conteneur.Jar("/tmp/exemple.jar"),
            cle = "maven:abcdef0123456789abcdef0123456789abcdef01",
            tailleSource = 1234L,
            mtimeSource = 5678L,
            moisson = entrees.mapIndexed { index, entree -> Triple(entree, membres[index], null) },
        )
    }

    @Test
    fun `aller-retour intégral restitue toutes les sections`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        val lecture = LecteurSegment().lire(fichier, donnees.cle, 1234L, 5678L)
        assertTrue(lecture is LectureSegment.Ok)
        val segment = (lecture as LectureSegment.Ok).segment
        assertEquals(donnees.cle, segment.cle)
        assertEquals("/tmp/exemple.jar", segment.conteneur.chemin)
        assertEquals(1234L, segment.tailleSource)
        assertEquals(5678L, segment.mtimeSource)
        assertEquals(donnees.entrees, segment.entrees)
        assertEquals(2, segment.paquets.size)
        assertEquals("alpha", segment.paquets[0].nom)
        assertEquals(0, segment.paquets[0].premiereClasse)
        assertEquals(2, segment.paquets[0].nombreClasses)
        assertEquals("beta.beta", segment.paquets[1].nom)
        assertEquals(1, segment.paquets[1].nombreClasses)
        // Noms simples : Interne (imbriquée), Alpha, Profond — triés.
        assertEquals(listOf("Alpha", "Interne", "Profond"), segment.nomsSimples.map { it.nom })
        // Alpha porte l'index 1 : le tri place Alpha$Interne (0x24 < 0x2E) d'abord.
        assertArrayEquals(intArrayOf(1), segment.nomsSimples.first { it.nom == "Alpha" }.indices)
        // Membres : l'index 1 (Alpha) les porte, l'index 2 (Profond) non.
        val membresAlpha = segment.membres(1)
        assertNotNull(membresAlpha)
        assertEquals(1, membresAlpha!!.champs.size)
        assertEquals("compteur", membresAlpha.champs[0].nom)
        assertEquals("I", membresAlpha.champs[0].descripteur)
        assertEquals(0x0009, membresAlpha.champs[0].drapeaux)
        assertEquals("<init>", membresAlpha.methodes[0].nom)
        assertNull(segment.membres(2))
        // La PREMIÈRE entrée (Alpha$Interne) porte ses propres membres.
        assertEquals(0x0020, segment.membres(0)!!.drapeauxClasse)
    }

    @Test
    fun `corruption du contenu est refusée par le crc`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)
        // Corromp un octet au MILIEU de la charge utile (après l'en-tête).
        val octets = fichier.readBytes()
        octets[octets.size / 2] = (octets[octets.size / 2].toInt() xor 0x41).toByte()
        fichier.writeBytes(octets.toList().map { it }.toByteArray())

        val lecture = LecteurSegment().lire(fichier, donnees.cle, null, null)
        assertTrue(lecture is LectureSegment.Refuse)
    }

    @Test
    fun `troncature est refusée proprement`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)
        val octets = fichier.readBytes()
        fichier.writeBytes(octets.copyOfRange(0, 20))

        val lecture = LecteurSegment().lire(fichier, donnees.cle, null, null)
        assertTrue(lecture is LectureSegment.Refuse)
    }

    @Test
    fun `version future est refusée (ré-indexation paresseuse, pas de crash)`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        val octets = EcrivainSegment().serialiser(donnees)
        // Version du format incrémentée (octets 4-5, u16 gros-boutien).
        octets[4] = 0
        octets[5] = 99
        fichier.writeBytes(octets)

        val lecture = LecteurSegment().lire(fichier, donnees.cle, null, null)
        assertTrue(lecture is LectureSegment.Refuse)
        assertTrue((lecture as LectureSegment.Refuse).raison.contains("version"))
    }

    @Test
    fun `clé divergente est refusée (fichier renommé)`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        val lecture = LecteurSegment().lire(fichier, "maven:autre", null, null)
        assertTrue(lecture is LectureSegment.Refuse)
    }

    @Test
    fun `statistiques divergentes sont refusées (W7)`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        assertTrue(
            LecteurSegment().lire(fichier, donnees.cle, 9999L, null) is LectureSegment.Refuse,
        )
        assertTrue(
            LecteurSegment().lire(fichier, donnees.cle, null, 9999L) is LectureSegment.Refuse,
        )
    }

    @Test
    fun `en-tête valide seul est détecté sans lire la charge (reprise rapide)`() {
        val donnees = donneesCompletes()
        val fichier = dossier.newFile("segment.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        assertTrue(LecteurSegment().enTeteValide(fichier, donnees.cle, 1234L, 5678L))
        assertTrue(!LecteurSegment().enTeteValide(fichier, donnees.cle, 9999L, null))
        assertTrue(!LecteurSegment().enTeteValide(fichier, "maven:autre", null, null))
        assertTrue(!LecteurSegment().enTeteValide(dossier.newFile(), "maven:x", null, null))
    }

    @Test
    fun `segment AAR restitue les origines des jars internes`() {
        val moisson =
            listOf(
                Triple("alpha/Alpha.class", null, 0),
                Triple("beta/Beta.class", null, 1),
            )
        val donnees =
            AssembleurSegment.assembler(
                conteneur = Conteneur.Arr("/tmp/exemple.aar", listOf("classes.jar", "libs/extra.jar")),
                cle = ClesSegments.cleArr("maven:sha1", listOf("classes.jar", "libs/extra.jar")),
                tailleSource = 10L,
                mtimeSource = 20L,
                moisson = moisson,
            )
        val fichier = dossier.newFile("segment-aar.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        val lecture = LecteurSegment().lire(fichier, donnees.cle, null, null)
        assertTrue(lecture is LectureSegment.Ok)
        val segment = (lecture as LectureSegment.Ok).segment
        val aar = segment.conteneur as Conteneur.Arr
        assertEquals(listOf("classes.jar", "libs/extra.jar"), aar.jarsInternes)
        assertEquals("classes.jar", segment.jarInterne(0))
        assertEquals("libs/extra.jar", segment.jarInterne(1))
        assertNull(segment.jarInterne(2))
    }

    @Test
    fun `segment vide est valide (jar sans classe)`() {
        val donnees =
            AssembleurSegment.assembler(
                conteneur = Conteneur.Jar("/tmp/vide.jar"),
                cle = "partiel:abc:1:1",
                tailleSource = 1L,
                mtimeSource = 1L,
                moisson = emptyList(),
            )
        val fichier = dossier.newFile("segment-vide.idx")
        EcrivainSegment().ecrire(donnees, fichier)

        val lecture = LecteurSegment().lire(fichier, donnees.cle, 1L, 1L)
        assertTrue(lecture is LectureSegment.Ok)
        val segment = (lecture as LectureSegment.Ok).segment
        assertTrue(segment.entrees.isEmpty())
        assertTrue(segment.paquets.isEmpty())
        assertNull(segment.membres(0))
        assertEquals(0L, segment.octetsMemoire)
    }
}
