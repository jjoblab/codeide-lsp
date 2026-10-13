package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests purs des nouveaux types d'état d'index étendu (Phase B prompt 2,
 * ADR 0117 §4.4 — lot B1). Valide les invariants du modèle étendu et la
 * rétrocompatibilité (les valeurs par défaut ne cassent pas les
 * consommateurs existants qui ne lisent que `phase`/`faits`/`total`).
 *
 * Convention : noms de tests en français entre accents graves
 * (convention du dépôt, ADR 0007).
 */
class TypesEtatIndexEtendusTest {
    // ============================================================
    // EtatIndex — rétrocompatibilité
    // ============================================================

    @Test
    fun `etat initial conserve ses valeurs historiques`() {
        val initial = EtatIndex.INITIAL
        assertEquals(PhaseIndex.CONSTRUCTION, initial.phase)
        assertEquals(0, initial.total)
        assertEquals(0, initial.faits)
        assertNull(initial.entreeCourante)
        assertEquals(0, initial.reprises)
        assertTrue(initial.avertissements.isEmpty())
        assertTrue(initial.erreurs.isEmpty())
    }

    @Test
    fun `etat initial a les valeurs par defaut des nouveaux champs`() {
        val initial = EtatIndex.INITIAL
        // Les nouveaux champs ont des valeurs par défaut neutres — les
        // consommateurs existants qui ne les lisent pas ne sont pas affectés.
        assertEquals(PhaseDetailleeIndex.AUCUNE, initial.phaseDetaillee)
        assertTrue(initial.listeTravail.isEmpty())
        assertTrue(initial.breakdownIndexeurs.isEmpty())
        assertNull(initial.dureesPhases)
        assertNull(initial.bilanDernierPassage)
        assertFalse(initial.settled)
    }

    @Test
    fun `construction EtatIndex sans nouveaux champs — retrocompatibilite binaire`() {
        // Un code qui construit EtatIndex avec seulement les champs
        // historiques (sans nommer les nouveaux champs) doit compiler
        // et fonctionner — c'est le contrat des valeurs par défaut.
        val etat =
            EtatIndex(
                phase = PhaseIndex.PRET,
                total = 191,
                faits = 191,
                entreeCourante = null,
                reprises = 188,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        assertEquals(PhaseIndex.PRET, etat.phase)
        assertEquals(191, etat.total)
        assertEquals(PhaseDetailleeIndex.AUCUNE, etat.phaseDetaillee)
        assertFalse(etat.settled)
    }

    // ============================================================
    // EtatIndex — champs étendus
    // ============================================================

    @Test
    fun `etat avec phase detaillee bibliotheques et liste de travail`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.CONSTRUCTION,
                total = 191,
                faits = 118,
                entreeCourante = "material 1.14.0",
                reprises = 96,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndex.BIBLIOTHEQUES,
                listeTravail =
                    listOf(
                        EntreeTravail("androidx.core 1.19.0", EtatEntreeTravail.REPRIS),
                        EntreeTravail("material 1.14.0", EtatEntreeTravail.ACTIF),
                        EntreeTravail("okio 3.9.0", EtatEntreeTravail.EN_ATTENTE),
                    ),
            )
        assertEquals(PhaseDetailleeIndex.BIBLIOTHEQUES, etat.phaseDetaillee)
        assertEquals(3, etat.listeTravail.size)
        assertEquals(EtatEntreeTravail.REPRIS, etat.listeTravail[0].etat)
        assertEquals(EtatEntreeTravail.ACTIF, etat.listeTravail[1].etat)
        assertEquals(EtatEntreeTravail.EN_ATTENTE, etat.listeTravail[2].etat)
    }

    @Test
    fun `etat PRET avec bilan dernier passage et settled vrai`() {
        val bilan =
            BilanDernierPassage(
                dureeMs = 4_200,
                artefactsTotal = 191,
                artefactsRepris = 188,
                artefactsReconstruits = 3,
                fichiersParcourus = 214,
                fichiersReanalyses = 12,
                indexeurPlusCouteux = "noms-classes-java",
                horodatageMs = 1_726_000_000_000,
            )
        val etat =
            EtatIndex(
                phase = PhaseIndex.PRET,
                total = 191,
                faits = 191,
                entreeCourante = null,
                reprises = 188,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndex.AUCUNE,
                bilanDernierPassage = bilan,
                settled = true,
            )
        assertTrue(etat.settled)
        assertEquals(4_200L, etat.bilanDernierPassage?.dureeMs)
        assertEquals(191, etat.bilanDernierPassage?.artefactsTotal)
        assertEquals(188, etat.bilanDernierPassage?.artefactsRepris)
        assertEquals(3, etat.bilanDernierPassage?.artefactsReconstruits)
        assertEquals(214, etat.bilanDernierPassage?.fichiersParcourus)
        assertEquals(12, etat.bilanDernierPassage?.fichiersReanalyses)
        assertEquals("noms-classes-java", etat.bilanDernierPassage?.indexeurPlusCouteux)
    }

    @Test
    fun `etat PARTIEL avec avertissements et settled vrai`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.PARTIEL,
                total = 191,
                faits = 188,
                entreeCourante = null,
                reprises = 185,
                avertissements = listOf("legacy-utils.jar : jar illisible", "play-core.aar : AAR sans jar interne"),
                erreurs = emptyList(),
                settled = true,
            )
        assertEquals(PhaseIndex.PARTIEL, etat.phase)
        assertTrue(etat.settled)
        assertEquals(2, etat.avertissements.size)
        assertEquals("legacy-utils.jar : jar illisible", etat.avertissements[0])
    }

    @Test
    fun `etat ERREUR avec erreurs bloquantes et settled vrai`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.ERREUR,
                total = 191,
                faits = 0,
                entreeCourante = null,
                reprises = 0,
                avertissements = emptyList(),
                erreurs = listOf("Segment n°3 corrompu (checksum invalide)"),
                settled = true,
            )
        assertEquals(PhaseIndex.ERREUR, etat.phase)
        assertTrue(etat.settled)
        assertEquals(1, etat.erreurs.size)
    }

    // ============================================================
    // BreakdownIndexeur — tri du plus coûteux au plus rapide
    // ============================================================

    @Test
    fun `breakdown indexeurs — tri du plus couteux au plus rapide`() {
        val breakdown =
            listOf(
                BreakdownIndexeur("ressources-android", "Ressources Android", 600L, 214),
                BreakdownIndexeur("noms-classes-java", "Noms de classes Java", 1_800L, 191),
                BreakdownIndexeur("appelables-kotlin", "Appelables Kotlin", 1_100L, 87),
            ).sortedByDescending { it.millisecondesCumulees }
        assertEquals("noms-classes-java", breakdown[0].identifiant)
        assertEquals(1_800L, breakdown[0].millisecondesCumulees)
        assertEquals("appelables-kotlin", breakdown[1].identifiant)
        assertEquals(1_100L, breakdown[1].millisecondesCumulees)
        assertEquals("ressources-android", breakdown[2].identifiant)
        assertEquals(600L, breakdown[2].millisecondesCumulees)
    }

    // ============================================================
    // DureesPhases
    // ============================================================

    @Test
    fun `durees phases — bibliotheques plus longues que sources`() {
        val durees = DureesPhases(bibliothequesMs = 3_500L, sourcesMs = 700L)
        assertEquals(3_500L, durees.bibliothequesMs)
        assertEquals(700L, durees.sourcesMs)
        assertTrue(durees.bibliothequesMs > durees.sourcesMs)
    }

    @Test
    fun `durees phases — sources plus longues que bibliotheques (projet massif)`() {
        val durees = DureesPhases(bibliothequesMs = 800L, sourcesMs = 2_400L)
        assertTrue(durees.sourcesMs > durees.bibliothequesMs)
    }

    // ============================================================
    // PhaseDetailleeIndex
    // ============================================================

    @Test
    fun `phase detaillee INITIAL avant tout demarrage`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.CONSTRUCTION,
                total = 0,
                faits = 0,
                entreeCourante = null,
                reprises = 0,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndex.INITIAL,
            )
        assertEquals(PhaseDetailleeIndex.INITIAL, etat.phaseDetaillee)
    }

    @Test
    fun `phase detaillee SOURCES pendant la re_analyse des fichiers`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.CONSTRUCTION,
                total = 214,
                faits = 12,
                entreeCourante = "MainActivity.java",
                reprises = 0,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndex.SOURCES,
            )
        assertEquals(PhaseDetailleeIndex.SOURCES, etat.phaseDetaillee)
        assertEquals("MainActivity.java", etat.entreeCourante)
    }

    @Test
    fun `phase detaillee AUCUNE quand phase PRET`() {
        val etat =
            EtatIndex(
                phase = PhaseIndex.PRET,
                total = 191,
                faits = 191,
                entreeCourante = null,
                reprises = 188,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        assertEquals(PhaseDetailleeIndex.AUCUNE, etat.phaseDetaillee)
    }

    // ============================================================
    // EntreeTravail — états
    // ============================================================

    @Test
    fun `entree de travail — tous les etats possibles`() {
        val entrees =
            listOf(
                EntreeTravail("a", EtatEntreeTravail.EN_ATTENTE),
                EntreeTravail("b", EtatEntreeTravail.ACTIF),
                EntreeTravail("c", EtatEntreeTravail.TERMINE),
                EntreeTravail("d", EtatEntreeTravail.REPRIS),
                EntreeTravail("e", EtatEntreeTravail.ECARTE),
            )
        assertEquals(5, entrees.size)
        assertEquals(EtatEntreeTravail.EN_ATTENTE, entrees[0].etat)
        assertEquals(EtatEntreeTravail.ACTIF, entrees[1].etat)
        assertEquals(EtatEntreeTravail.TERMINE, entrees[2].etat)
        assertEquals(EtatEntreeTravail.REPRIS, entrees[3].etat)
        assertEquals(EtatEntreeTravail.ECARTE, entrees[4].etat)
    }
}
