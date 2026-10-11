package jo.codeide.lsp.index

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de la VUE d'index de bout en bout (vrais jars sur disque) — les
 * six extensions de requête d'ADR 0111 §5 sur la fusion LSM ordonnée.
 */
@Suppress("TooManyFunctions")
class VueIndexTest {
    private val base = FixturesIndex.dossierTemporaire("vue")
    private val dispatcheur: CoroutineDispatcher = Dispatchers.IO

    /** Jar 1 : alpha/beta + imbriquée ; Jar 2 : gamma + doublon alpha. */
    private val jar1: File
    private val jar2: File
    private val service: ServiceIndexDisque
    private val scope: CoroutineScope

    init {
        jar1 =
            FixturesIndex.ecrireZip(
                File(base, "un.jar"),
                mapOf(
                    "alpha/Alpha.class" to FixturesIndex.octetsClasse(champ = Triple(0x0019, "PI", "D")),
                    "alpha/Alpha\$Interne.class" to FixturesIndex.octetsClasse(),
                    "beta/Outil.class" to
                        FixturesIndex.octetsClasse(
                            champ = Triple(0x0002, "cache", "I"),
                            methode = Triple(0x0009, "calculer", "(I)I"),
                        ),
                ),
            )
        jar2 =
            FixturesIndex.ecrireZip(
                File(base, "deux.jar"),
                mapOf(
                    "alpha/Alpha.class" to FixturesIndex.octetsClasse(),
                    "gamma/Sous.class" to FixturesIndex.octetsClasse(champ = Triple(0x0002, "cache", "I")),
                    "gamma/sous/Enfant.class" to FixturesIndex.octetsClasse(),
                ),
            )
        scope = CoroutineScope(dispatcheur + Job())
        service =
            ServiceIndexDisque(
                repertoire = File(base, "stockage"),
                scope = scope,
                dispatcheurIo = dispatcheur,
                parallelisme = 2,
                budgetCacheOctets = 8L * 1024 * 1024,
            )
    }

    /** Classpath : jar1 (compile) PUIS jar2 (test) — l'ordre fait la préséance. */
    private fun vueConstruite(): VueIndex {
        val vue =
            service.vuePour(
                FixturesIndex.classpath(
                    listOf(
                        FixturesIndex.entreeJar(
                            jar1.path,
                            "maven:jar-un",
                            jar1.length(),
                            jar1.lastModified(),
                            portee = "compile",
                        ),
                        FixturesIndex.entreeJar(
                            jar2.path,
                            "maven:jar-deux",
                            jar2.length(),
                            jar2.lastModified(),
                            portee = "test",
                        ),
                    ),
                ),
            )
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }
        assertEquals(PhaseIndex.PRET, vue.etat.value.phase)
        return vue
    }

    @Test
    fun `classLocator — localisation, préséance d'ordre, imbriquée, négatif`() {
        val vue = vueConstruite()

        val alpha = vue.localiser("alpha.Alpha")
        assertNotNull(alpha)
        assertEquals(jar1.path, alpha!!.conteneur.chemin)
        assertEquals("alpha/Alpha.class", alpha.entree)
        assertEquals("compile", alpha.portee)

        // Le jar1 précède : c'est LUI qui porte le doublon alpha.Alpha.
        assertEquals(jar1.path, vue.localiser("alpha.Alpha")!!.conteneur.chemin)
        // Le jar2 (portée test) porte gamma.
        assertEquals("test", vue.localiser("gamma.Sous")!!.portee)
        // Imbriquée par dollar.
        assertNotNull(vue.localiser("alpha.Alpha\$Interne"))
        // Négatif (prêt = autoritatif).
        assertNull(vue.localiser("absent.Inconnu"))
    }

    @Test
    fun `classNames — préfixe, fusion des segments, dédoublonnage par FQCN`() {
        val vue = vueConstruite()

        val resultats = vue.nomsParPrefixe("Al")
        assertEquals(listOf("Alpha"), resultats.map { it.nomSimple })
        // Un seul FQCN (le doublon du jar2 est dédoublonné par FQCN).
        assertEquals(listOf("alpha.Alpha"), resultats[0].fqcns)

        val parA = vue.nomsParPrefixe("A")
        assertEquals(listOf("Alpha"), parA.map { it.nomSimple })

        val vides = vue.nomsParPrefixe("zzz")
        assertTrue(vides.isEmpty())
    }

    @Test
    fun `packages et packageTypes — ni plus ni moins`() {
        val vue = vueConstruite()

        assertTrue(vue.estPaquet("alpha"))
        assertTrue(vue.estPaquet("gamma"))
        assertTrue(vue.estPaquet("gamma.sous"))
        assertTrue(!vue.estPaquet("gamma.sous.enfant"))
        assertTrue(!vue.estPaquet("inconnu"))

        // Types DIRECTEMENT dans alpha : Alpha et Alpha\$Interne, PAS plus.
        assertEquals(listOf("alpha.Alpha\$Interne", "alpha.Alpha"), vue.typesDansPaquet("alpha"))
        assertEquals(listOf("gamma.sous.Enfant"), vue.typesDansPaquet("gamma.sous"))
        assertTrue(vue.typesDansPaquet("beta").contains("beta.Outil"))
    }

    @Test
    fun `sousPaquetsDirects — un seul niveau, ni plus ni moins`() {
        val vue = vueConstruite()

        assertEquals(listOf("gamma.sous"), vue.sousPaquetsDirects("gamma"))
        assertTrue(vue.sousPaquetsDirects("alpha").isEmpty())
        assertTrue(vue.sousPaquetsDirects("gamma.sous").isEmpty())
        // Racine : les paquets de premier niveau.
        val racine = vue.sousPaquetsDirects("")
        assertTrue(racine.containsAll(listOf("alpha", "beta", "gamma")))
    }

    @Test
    fun `members et membersByOwner — statiques du propriétaire`() {
        val vue = vueConstruite()

        val alpha = vue.membres("alpha.Alpha")
        assertNotNull(alpha)
        assertEquals("PI", alpha!!.champs.single().nom)

        // Imports statiques : le champ statique (public static final =
        // 0x0019 & 0x0008 ≠ 0) et la méthode statique de beta.Outil.
        val statiques = vue.membresStatiques("alpha.Alpha")
        assertEquals(listOf("PI"), statiques.map { it.nom })
        assertEquals(listOf("calculer"), vue.membresStatiques("beta.Outil").map { it.nom })
        assertTrue(vue.membresStatiques("gamma.Sous").isEmpty())
        assertNull(vue.membres("absent.Inconnu"))
    }

    @Test
    fun `requêtes avant construction restent silencieusement vides (repli sondage)`() {
        val vue =
            service.vuePour(
                FixturesIndex.classpath(
                    listOf(
                        FixturesIndex.entreeJar(jar1.path, "maven:jar-un-avance", jar1.length(), jar1.lastModified()),
                    ),
                ),
            )
        assertNull(vue.localiser("alpha.Alpha"))
        assertTrue(vue.nomsParPrefixe("Al").isEmpty())
        assertEquals(EtatIndex.INITIAL.phase, vue.etat.value.phase)
        scope.cancel()
    }

    @Test
    fun `jar absent du disque mène à PARTIEL et reste interrogeable`() {
        val vue =
            service.vuePour(
                FixturesIndex.classpath(
                    listOf(
                        FixturesIndex.entreeJar(jar1.path, "maven:jar-ok", jar1.length(), jar1.lastModified()),
                        FixturesIndex.entreeJar("/chemin/absent.jar", "maven:absent", 1L, 1L),
                    ),
                ),
            )
        runBlocking { withTimeout(10_000) { service.construire(vue).join() } }

        assertEquals(PhaseIndex.PARTIEL, vue.etat.value.phase)
        assertTrue(
            vue.etat.value.avertissements
                .isNotEmpty(),
        )
        // Le jar valide reste interrogable.
        assertNotNull(vue.localiser("alpha.Alpha"))
        // L'entrée AAR du modèle (KindEntree.AAR) serait normalisée : pas
        // testée ici (AnalyseurClasspathSystemeTest la couvre).
    }

    @Test
    fun `empreintes référencées alimentent le nettoyage`() {
        val vue = vueConstruite()
        val references = vue.empreintesReferences()
        assertEquals(setOf("maven:jar-un", "maven:jar-deux"), references)
        scope.cancel()
    }

    @org.junit.After
    fun fermer() {
        scope.cancel()
    }
}
