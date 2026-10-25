package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION du template Kotlin Multiplatform (KMP) d'Android Studio
 * au niveau MOTEUR — le module `shared` a DEUX racines sources (le paquet
 * y vit DEUX fois : expect dans commonMain, actual dans androidMain) :
 *
 *   shared/src/commonMain/kotlin/com/example/kmp/Platform.kt
 *     expect fun platformName(): String ; expect class Platform
 *   shared/src/commonMain/kotlin/com/example/kmp/Greeting.kt
 *     class Greeting { fun greet(): String = platformName() }
 *   shared/src/androidMain/kotlin/com/example/kmp/Platform.android.kt
 *     actual fun platformName(): String = "Android"
 *     actual class Platform actual constructor() { actual val name }
 *
 * Exercé : les MODIFICATEURS expect/actual (parse), les appels NU à un
 * expect depuis commonMain, le type expect en signature, les usages
 * ANDROID (actual), le type de retour de l'actual en typage — miroir
 * Android Studio : aucun faux `kt.unresolved` dans AUCUN source set.
 */
class ReproductionKmpTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Sème le module shared KMP (deux racines sources). */
    private fun semerKmp(): List<File> {
        val racine = dossierTemporaire.newFolder("shared")
        val common = File(racine, "src/commonMain/kotlin/com/example/kmp")
        val android = File(racine, "src/androidMain/kotlin/com/example/kmp")
        common.mkdirs()
        android.mkdirs()
        File(common, "Platform.kt").writeText(
            """
            package com.example.kmp

            expect fun platformName(): String

            expect class Platform() {
              val name: String
            }
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(common, "Greeting.kt").writeText(
            """
            package com.example.kmp

            class Greeting {
              private val plateforme: Platform = Platform()
              fun greet(): String = platformName() + " " + plateforme.name
            }
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(android, "Platform.android.kt").writeText(
            """
            package com.example.kmp

            actual fun platformName(): String = "Android"

            actual class Platform actual constructor() {
              actual val name: String = "Android"
            }
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        File(android, "AndroidGreeting.kt").writeText(
            """
            package com.example.kmp

            class AndroidGreeting {
              fun greetAndroid(): String = platformName() + " " + Platform().name
            }
            """.trimIndent().replace("    ", "  ") + "\n",
        )
        return listOf(
            File(racine, "src/commonMain/kotlin"),
            File(racine, "src/androidMain/kotlin"),
        )
    }

    /** Session nourrie du stdlib réel + des DEUX racines du module shared. */
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        jo.lspkotlin.api.EntreeClasspathKotlin(
                            jo.lspkotlin.api.KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    .codeSource.location.toURI(),
                            ).path,
                            "repro:stdlib",
                        ),
                    ),
                vue = null,
                racinesSources = semerKmp(),
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
    fun `kmp - commonMain, expect et usages, zero faux positif`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Greeting.kt"
        val source =
            """
            package com.example.kmp

            class Greeting {
              private val plateforme: Platform = Platform()
              fun greet(): String = platformName() + " " + plateforme.name
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        // AVANT correctif : les usages de l'EXPECT (platformName, Platform)
        // selon le verdict du moteur — le test CONSTATE puis verrouille.
        System.out.println("kmp-commonMain => ${resultats.map { it.message }}")
        assertEquals("commonMain ne doit produire AUCUN faux positif : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp - expect declasse et fonction, fichier meme, zero faux positif`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Platform.kt"
        val source =
            """
            package com.example.kmp

            expect fun platformName(): String

            expect class Platform() {
              val name: String
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("kmp-expect => ${resultats.map { it.message }}")
        assertEquals("les déclarations expect ne doivent RIEN signaler : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp - androidMain, actual et usages, zero faux positif`() {
        val session = session()
        val uri = "file:///shared/src/androidMain/kotlin/com/example/kmp/AndroidGreeting.kt"
        val source =
            """
            package com.example.kmp

            class AndroidGreeting {
              fun greetAndroid(): String = platformName() + " " + Platform().name
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("kmp-androidMain => ${resultats.map { it.message }}")
        assertEquals("androidMain ne doit produire AUCUN faux positif : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `kmp - survol de l expect platformName depuis commonMain`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Greeting.kt"
        val source =
            """
            package com.example.kmp

            class Greeting {
              fun greet(): String = platformName()
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("platformName"))
        System.out.println("kmp-survol expect => ${survol?.valeur}")
        assertNotNull("le survol de l'expect doit répondre", survol)
        assertTrue(
            "la signature de l'expect se rend — obtenu : ${survol?.valeur}",
            survol!!.valeur.contains("platformName"),
        )
        session.close()
    }

    @Test
    fun `kmp - definition go-to depuis l usage common vers l expect`() {
        val session = session()
        val uri = "file:///shared/src/commonMain/kotlin/com/example/kmp/Greeting.kt"
        val source =
            """
            package com.example.kmp

            class Greeting {
              fun greet(): String = platformName()
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument(uri, source)
        val definitions = session.definitions(uri, source.indexOf("platformName"))
        System.out.println("kmp-definition expect => ${definitions.map { it.uri }}")
        assertTrue(
            "la définition de l'expect vit dans Platform.kt (commonMain) : ${definitions.map { it.uri }}",
            definitions.any { it.uri.endsWith("Platform.kt") },
        )
        session.close()
    }
}
