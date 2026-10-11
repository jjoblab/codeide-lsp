package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'assemblage (logique pure) : tri/déduplication, paquets
 * CONTIGUS (« ni plus ni moins »), noms simples (imbriquées par `$`,
 * anonymes écartées de la complétion mais localisables).
 */
class AssembleurSegmentTest {
    @Test
    fun `paquets contigus avec plages exactes`() {
        val entrees =
            listOf(
                "alpha/Alpha.class",
                "alpha/Alpha\$Interne.class",
                "beta/beta/Profond.class",
                "beta/Prefixe.class",
                "Racine.class",
            )
        val paquets = AssembleurSegment.assemblerPaquets(entrees)

        assertEquals(listOf("alpha", "beta.beta", "beta", ""), paquets.map { it.nom })
        assertEquals(0, paquets[0].premiereClasse)
        assertEquals(2, paquets[0].nombreClasses)
        assertEquals(2, paquets[1].premiereClasse)
        assertEquals(1, paquets[1].nombreClasses)
        assertEquals(4, paquets[3].premiereClasse)
        assertEquals(1, paquets[3].nombreClasses)
    }

    @Test
    fun `noms simples — imbriquée par dollar, anonymes écartées`() {
        val noms =
            AssembleurSegment.assemblerNomsSimples(
                listOf(
                    "alpha/Alpha.class",
                    "alpha/Alpha\$Interne.class",
                    "alpha/Alpha\$1.class",
                    "beta/Profond.class",
                ),
            )
        // Alpha\$1 (anonyme) : pas de nom simple, mais LOCALISABLE.
        assertEquals(listOf("Alpha", "Interne", "Profond"), noms.map { it.nom })
        assertEquals(listOf(0), noms.first { it.nom == "Alpha" }.indices.toList())
        assertEquals(listOf(1), noms.first { it.nom == "Interne" }.indices.toList())
    }

    @Test
    fun `le premier porteur d'un doublon gagne (classes précède libs)`() {
        val moisson =
            listOf(
                Triple("a/Double.class", MembresClasse(1, emptyList(), emptyList()), 0),
                Triple("b/Unique.class", null, 1),
                Triple("a/Double.class", null, 1),
            )
        val donnees =
            AssembleurSegment.assembler(
                conteneur = Conteneur.Arr("/tmp/a.aar", listOf("classes.jar", "libs/x.jar")),
                cle = "aar:x",
                tailleSource = 0L,
                mtimeSource = 0L,
                moisson = moisson,
            )

        assertEquals(listOf("a/Double.class", "b/Unique.class"), donnees.entrees)
        // Le membre du PREMIER porteur (classes.jar) est conservé.
        assertTrue(donnees.membres[0] != null && donnees.membres[1] == null)
        assertEquals(intArrayOf(0, 1).toList(), donnees.origines!!.toList())
    }

    @Test
    fun `nom simple et paquet — cas limites`() {
        assertEquals("Entry", AssembleurSegment.nomSimpleDe("java/util/Map\$Entry.class"))
        assertEquals("Map", AssembleurSegment.nomSimpleDe("Map.class"))
        assertNull(AssembleurSegment.nomSimpleDe("a/Map$1.class"))
        assertNull(AssembleurSegment.nomSimpleDe("a/Map$.class"))
        assertNull(AssembleurSegment.nomSimpleDe("a/123.class"))
        assertEquals("java.util", AssembleurSegment.paquetDe("java/util/Map.class"))
        assertEquals("", AssembleurSegment.paquetDe("Racine.class"))
    }
}
