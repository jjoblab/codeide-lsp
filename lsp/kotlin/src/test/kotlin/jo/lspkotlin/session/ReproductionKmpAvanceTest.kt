package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION KMP AVANCÉE — les formes qui dépassent la déclaration
 * simple : `expect val`, `expect object` (le patron HttpClient/Database
 * de toute lib KMP), `actual typealias`, membres d'expect object,
 * génériques sur un type expect, usage depuis une EXTENSION.
 */
class ReproductionKmpAvanceTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    // Exemption detekt ciblée (règle 16) : LongMethod — le semis du
    // module KMP complet (quatre fichiers, deux racines) est le
    // FIXTURE du scénario, miroir des semis des suites E2E.
    @Suppress("LongMethod")
    private fun semer(): List<File> {
        val racine = dossierTemporaire.newFolder("shared")
        val common = File(racine, "src/commonMain/kotlin/com/example/kmp")
        val android = File(racine, "src/androidMain/kotlin/com/example/kmp")
        common.mkdirs()
        android.mkdirs()
        File(common, "Attentes.kt").writeText(
            """
            package com.example.kmp

            expect val seuilDebug: Boolean

            expect object Fabrique {
              fun creer(): String
            }

            expect class Boite<T>(val contenu: T) {
              fun ouvrir(): T
            }
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(common, "Usages.kt").writeText(
            """
            package com.example.kmp

            fun demo(): String {
              val b = Boite(platformName())
              return if (seuilDebug) Fabrique.creer() + b.ouvrir() else b.ouvrir()
            }

            expect fun platformName(): String
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(android, "Reels.android.kt").writeText(
            """
            package com.example.kmp

            actual val seuilDebug: Boolean = true

            actual object Fabrique {
              actual fun creer(): String = "fabrique-android"
            }

            actual class Boite<T> actual constructor(actual val contenu: T) {
              actual fun ouvrir(): T = contenu
            }

            actual fun platformName(): String = "Android"
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(android, "Alias.android.kt").writeText(
            """
            package com.example.kmp

            actual typealias ChainePlateforme = String

            fun androidSeuil(): Boolean = seuilDebug
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        return listOf(
            File(racine, "src/commonMain/kotlin"),
            File(racine, "src/androidMain/kotlin"),
        )
    }

    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    .codeSource.location.toURI(),
                            ).path,
                            "repro:stdlib",
                        ),
                    ),
                vue = null,
                racinesSources = semer(),
                dossierIndex = null,
            ),
        )
        return session
    }

    private fun nonResolus(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ) = session.diagnostics(uri).filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }

    @Test
    fun `kmp avance - usages commonMain de expect val, object, generique`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Usages.kt"
        val source =
            """
            package com.example.kmp

            object Local {
              fun fait(): Int = 1
            }

            fun demo(): String {
              val b = Boite(platformName())
              return if (seuilDebug) Fabrique.creer() + b.ouvrir() else Local.fait().toString()
            }

            expect fun platformName(): String
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("kmp-avance-common => ${resultats.map { it.message }}")
        assertEquals("les usages expect avancés ne doivent RIEN signaler : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp avance - androidMain, actual val, object, typealias`() {
        val session = session()
        val uri = "file:///shared/src/androidMain/kotlin/com/example/kmp/Alias.android.kt"
        val source =
            """
            package com.example.kmp

            actual typealias ChainePlateforme = String

            fun androidSeuil(): Boolean = seuilDebug
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("kmp-avance-android => ${resultats.map { it.message }}")
        assertEquals("les actual avancés ne doivent RIEN signaler : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp avance - typage du retour de Boite ouvrir`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Usages.kt"
        val source =
            """
            package com.example.kmp

            fun demo(): String {
              val b = Boite(platformName())
              return b.ouvrir().plus("!") + seuilDebug.toString()
            }

            expect fun platformName(): String
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("kmp-avance-typage => ${resultats.map { it.message }}")
        assertEquals("la chaîne typée du générique expect résout : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp avance - survol du membre d un expect object`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Usages.kt"
        val source =
            """
            package com.example.kmp

            fun demo(): String = Fabrique.creer()

            expect fun platformName(): String
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("creer"))
        System.out.println("kmp-avance-survol membre => ${survol?.valeur}")
        assertNotNull("le survol du membre de l'expect object répond", survol)
        assertTrue(
            "la signature du membre se rend — obtenu : ${survol?.valeur}",
            survol!!.valeur.contains("creer"),
        )
        session.close()
    }

    @Test
    fun `kmp avance - completion membre apres Fabrique point`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Usages.kt"
        val source =
            """
            package com.example.kmp

            fun demo(): String = Fabrique.

            expect fun platformName(): String
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val apresPoint = source.indexOf("Fabrique.") + "Fabrique.".length
        val completions = session.completion(uri, apresPoint)
        System.out.println("kmp-avance-completion => ${completions.map { it.libelle }.take(8)}")
        assertTrue(
            "creer doit s'offrir après Fabrique. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle.startsWith("creer") },
        )
        session.close()
    }
}
