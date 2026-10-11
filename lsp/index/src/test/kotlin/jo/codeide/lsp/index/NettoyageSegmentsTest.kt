package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests du nettoyage (ADR 0111 §9) : orphelins par âge, budget disque
 * global (LRU des orphelins), segments RÉFÉRENCÉS jamais supprimés,
 * dépassement restant SIGNALÉ.
 */
class NettoyageSegmentsTest {
    private val base = FixturesIndex.dossierTemporaire("nettoyage")
    private var maintenant = 1_760_000_000_000L
    private var compteur = 0

    /** Répertoire de cas ISOLÉ (chaque test a son stockage propre). */
    private fun cas(): File = File(base, "cas-${compteur++}")

    /** Horloge injectée : déterministe, aucun sommeil réel. */
    private fun nettoyage(cas: File): NettoyageSegments =
        NettoyageSegments(EntrepotSegments(cas), horloge = { maintenant })

    private fun segment(
        cas: File,
        empreinte: String,
        dernierAccesMillis: Long,
    ): File {
        val donnees =
            AssembleurSegment.assembler(
                conteneur = Conteneur.Jar("/cache/$empreinte.jar"),
                cle = empreinte,
                tailleSource = 1L,
                mtimeSource = 1L,
                moisson = listOf(Triple("a/A\$empreinte.class", MembresClasse(1, emptyList(), emptyList()), null)),
            )
        val entrepot = EntrepotSegments(cas)
        entrepot.ecrire(donnees, empreinte)
        val fichier = entrepot.fichierSegment(empreinte)
        fichier.setLastModified(dernierAccesMillis)
        return fichier
    }

    @Test
    fun `orphelin ancien supprimé, orphelin récent conservé`() {
        val cas = cas()
        val ancien = segment(cas, "maven:ancien", maintenant - TimeUnit.DAYS.toMillis(40L))
        val recent = segment(cas, "maven:recent", maintenant)

        val resultat =
            nettoyage(
                cas,
            ).nettoyer(references = emptySet(), joursRetention = 30, budgetOctets = Long.MAX_VALUE)

        assertEquals(1, resultat.segmentsSupprimes)
        assertTrue(!ancien.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `segment référencé JAMAIS supprimé, même ancien`() {
        val cas = cas()
        val reference = segment(cas, "maven:reference", maintenant - TimeUnit.DAYS.toMillis(365L))

        val resultat =
            nettoyage(cas).nettoyer(
                references = setOf("maven:reference"),
                joursRetention = 1,
                budgetOctets = Long.MAX_VALUE,
            )

        assertEquals(0, resultat.segmentsSupprimes)
        assertTrue(reference.exists())
    }

    @Test
    fun `budget disque — orphelins LRU supprimés jusqu'au budget`() {
        // Deux orphelins : vieux et récent, budget étroit.
        val cas = cas()
        val vieux = segment(cas, "maven:vieux", maintenant - TimeUnit.DAYS.toMillis(10L))
        val jeune = segment(cas, "maven:jeune", maintenant - TimeUnit.DAYS.toMillis(1L))
        val budget = vieux.length()

        val resultat = nettoyage(cas).nettoyer(references = emptySet(), joursRetention = 30, budgetOctets = budget)

        // Le VIEUX orphelin est supprimé pour tenir le budget ; le jeune reste.
        assertTrue(!vieux.exists())
        assertTrue(jeune.exists())
        assertEquals(1, resultat.segmentsSupprimes)
        assertTrue(resultat.octetsRestants <= budget)
        assertTrue(!resultat.budgetDepasse)
    }

    @Test
    fun `références au-delà du budget — signalé, jamais amputé`() {
        val cas = cas()
        val gros = segment(cas, "maven:gros-reference", maintenant)

        val resultat =
            nettoyage(cas).nettoyer(
                references = setOf("maven:gros-reference"),
                joursRetention = 30,
                budgetOctets = 1L,
            )

        assertEquals(0, resultat.segmentsSupprimes)
        assertTrue(gros.exists())
        assertTrue(resultat.budgetDepasse)
        assertEquals(gros.length(), resultat.octetsRestants)
    }

    @Test
    fun `stockage vide — résultat neutre`() {
        val resultat = nettoyage(cas()).nettoyer(emptySet(), 30, Long.MAX_VALUE)
        assertEquals(0, resultat.segmentsSupprimes)
        assertEquals(0L, resultat.octetsLiberes)
        assertEquals(0L, resultat.octetsRestants)
    }
}
