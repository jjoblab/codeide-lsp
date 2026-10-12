package jo.lspkotlin.parse.kt

import jo.lspkotlin.parse.ParseurKotlin
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la façade `Kt*` : les accesseurs portent le vocabulaire du
 * compilateur et RESTENT conservateurs (D3) — introuvable = null/vide.
 */
class FacadeKtTest {
    private val parseur = ParseurKotlin()

    private fun fichier(source: String): KtFile {
        val arbre = parseur.analyser(source)
        return KtFile(arbre.racine)
    }

    @Test
    fun `directive package - nom qualifie`() {
        val f = fichier("package a.b.c\n\nval x = 1\n")
        assertEquals("a.b.c", f.directivePackage()?.nomQualifie())
    }

    @Test
    fun `fichier sans package - nom qualifie null (directive vide presente)`() {
        // Parité PSI : une PACKAGE_DIRECTIVE vide existe toujours dans
        // l'arbre — l'absence réelle se lit sur le nom.
        assertNull(fichier("val x = 1\n").directivePackage()?.nomQualifie())
    }

    @Test
    fun `imports simple alias et wildcard`() {
        val f =
            fichier(
                """
                import java.util.ArrayList
                import java.util.ArrayList as JArrayList
                import kotlin.collections.*
                """.trimIndent(),
            )
        val imports = f.directivesImport()
        assertEquals(3, imports.size)
        assertEquals("java.util.ArrayList", imports[0].nomQualifie())
        assertEquals("JArrayList", imports[1].alias())
        assertEquals("kotlin.collections.*", imports[2].nomQualifie())
    }

    @Test
    fun `alias sans identifiant derriere as - null conservateur`() {
        // `as` sans alias : édition en cours — D3 : sous-rapporter.
        val f = fichier("import java.util.ArrayList as ")
        assertNull(f.directivesImport().single().alias())
    }

    @Test
    fun `declarations de premier niveau - noms et genres`() {
        val f =
            fichier(
                """
                class Alpha(val a: Int) {
                    fun membre(): Int = a
                    var champ: Int = 0
                }

                object Beta

                fun gamma(): Int = 1

                val delta: Int = 2

                typealias Epsilon = List<Int>

                enum class Zeta { UN, DEUX }
                """.trimIndent(),
            )
        val declarations = f.declarations()
        assertEquals(6, declarations.size)
        assertEquals("Alpha", (declarations[0] as KtClass).nom())
        assertEquals("Beta", (declarations[1] as KtObjectDeclaration).nom())
        assertEquals("gamma", (declarations[2] as KtFunction).nom())
        assertEquals("delta", (declarations[3] as KtProperty).nom())
        assertEquals("Epsilon", (declarations[4] as KtTypeAlias).nom())
        assertEquals("Zeta", (declarations[5] as KtClass).nom())
    }

    @Test
    fun `classe - membres, interface, enum`() {
        val f =
            fichier(
                """
                interface Contrat {
                    fun appliquer()
                }

                enum class Couleur {
                    ROUGE,
                    VERT,
                }

                class AvecCorps {
                    val a: Int = 1
                    private fun b() {}
                    object Imbrique
                }
                """.trimIndent(),
            )
        val declarations = f.declarations()
        val contrat = declarations[0] as KtClass
        assertTrue(contrat.estInterface())
        assertEquals("appliquer", contrat.membres().single().nom())

        val couleur = declarations[1] as KtClass
        assertEquals(listOf("ROUGE", "VERT"), couleur.membres().mapNotNull { it.nom() })

        val avecCorps = declarations[2] as KtClass
        assertTrue(!avecCorps.estInterface())
        assertEquals(
            listOf("a", "b", "Imbrique"),
            avecCorps.membres().mapNotNull { it.nom() },
        )
    }

    @Test
    fun `fonction - parametres nommes`() {
        val f =
            fichier(
                """
                fun addition(premiere: Int, deuxieme: String = "x", lambda: (Int) -> Int = { it }): Int = premiere
                """.trimIndent(),
            )
        val fonction = f.declarations().single() as KtFunction
        assertEquals("addition", fonction.nom())
        assertEquals(listOf("premiere", "deuxieme", "lambda"), fonction.parametres().mapNotNull { it.nom() })
    }

    @Test
    fun `propriete avec objet anonyme - nom de propriete conservateur`() {
        val f =
            fichier(
                """
                val x = object : Runnable {
                    override fun run() {}
                }
                """.trimIndent(),
            )
        val propriete = f.declarations().single() as KtProperty
        assertEquals("x", propriete.nom())
    }

    @Test
    fun `declaration tronquee sans identifiant - nom null sans lever`() {
        // `class ` (mot-clé seul) : pas d'IDENTIFIER → null (D3).
        val f = fichier("class ")
        assertNull(f.declarations().single().nom())
    }

    @Test
    fun `navigation generique - enfants par type`() {
        val f = fichier("class A { fun b() {} }\n")
        val classe = f.declarations().single() as KtClass
        val corps =
            checkNotNull(classe.premierEnfantParType(KtNodeTypes.CLASS_BODY)) {
                "CLASS_BODY attendu dans « class A { … } »"
            }
        val fonctions = corps.enfantsParType(KtNodeTypes.FUN)
        assertEquals(1, fonctions.size)
    }
}
