package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCTION des fichiers GRADLE KOTLIN DSL (.gradle.kts) — le
 * build.gradle.kts et settings.gradle.kts du template « Empty Views
 * Activity » d'Android Studio (version-catalog `libs`, blocs
 * plugins/android/dependencies, receivers implicites injectés par le
 * DSL Gradle).
 *
 * Miroir Android Studio : le DSL Gradle est INJECTÉ par le modèle
 * Gradle — sans lui (pas de classpath Gradle), IntelliJ ne signale
 * PAS les non-résolus des scripts en erreur dure : jamais de tsunami
 * de faux `kt.unresolved` sur un build.gradle.kts.
 */
class ReproductionGradleKtsTest {
    /** Session nourrie du stdlib réel (les scripts Gradle n'ont pas de
     * classpath Gradle dans le moteur — c'est le PRÉCISÉMENT du cas). */
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
                                    ?.codeSource?.location?.toURI(),
                            ).path,
                            "repro:stdlib",
                        ),
                    ),
                vue = null,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    private fun nonResolus(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ) = session.diagnostics(uri).filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }

    /** LE build.gradle.kts du template (viewBinding actif, version catalog). */
    private val buildGradleKts =
        """
        plugins {
          alias(libs.plugins.android.application)
        }

        android {
          namespace = "com.example.myapplication"
          compileSdk = 36

          defaultConfig {
            applicationId = "com.example.myapplication"
            minSdk = 24
            targetSdk = 36
            versionCode = 1
            versionName = "1.0"
          }

          buildTypes {
            release {
              isMinifyEnabled = false
              proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
              )
            }
          }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
          kotlinOptions {
            jvmTarget = "11"
          }
          buildFeatures {
            viewBinding = true
          }
        }

        dependencies {
          implementation(libs.androidx.core.ktx)
          implementation(libs.androidx.appcompat)
          implementation(libs.material)
          implementation(libs.constraintlayout)
          testImplementation(libs.junit)
          androidTestImplementation(libs.androidx.junit)
          androidTestImplementation(libs.androidx.espresso.core)
        }
        """.trimIndent() + "\n"

    /** LE settings.gradle.kts du template (pluginManagement, repositories). */
    private val settingsGradleKts =
        """
        pluginManagement {
          repositories {
            google {
              content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
              }
            }
            mavenCentral()
            gradlePluginPortal()
          }
        }
        dependencyResolutionManagement {
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {
            google()
            mavenCentral()
          }
        }

        rootProject.name = "My Application"
        include(":app")
        """.trimIndent() + "\n"

    @Test
    fun `gradle kts - build gradle kts du template, aucun faux positif`() {
        val session = session()
        val uri = "file:///projet/build.gradle.kts"
        session.ouvrirDocument(uri, buildGradleKts, script = true)
        val resultats = nonResolus(session, uri)
        System.out.println("=== BUILD.GRADLE.KTS (${resultats.size}) ===")
        resultats.forEach { System.out.println("  ${it.message}") }
        assertEquals(
            "le script Gradle ne doit produire AUCUN faux positif (miroir Android Studio) : " +
                resultats.map { it.message },
            0,
            resultats.size,
        )
        session.close()
    }

    @Test
    fun `gradle kts - settings gradle kts du template, aucun faux positif`() {
        val session = session()
        val uri = "file:///projet/settings.gradle.kts"
        session.ouvrirDocument(uri, settingsGradleKts, script = true)
        val resultats = nonResolus(session, uri)
        System.out.println("=== SETTINGS.GRADLE.KTS (${resultats.size}) ===")
        resultats.forEach { System.out.println("  ${it.message}") }
        assertEquals(
            "le script Gradle ne doit produire AUCUN faux positif : " +
                resultats.map { it.message },
            0,
            resultats.size,
        )
        session.close()
    }

    @Test
    fun `gradle kts - le Kotlin du script resout quand meme (stdlib, locales)`() {
        val session = session()
        val source =
            """
            import java.io.File

            val message = listOf("a", "b").map { it.uppercase() }.joinToString("-")
            println(message)
            val fichier = File("essai.txt")
            val nom: String = fichier.name
            """.trimIndent() + "\n"
        val uri = "file:///projet/outil.gradle.kts"
        session.ouvrirDocument(uri, source, script = true)
        val resultats = nonResolus(session, uri)
        System.out.println("=== KOTLIN DANS SCRIPT (${resultats.size}) ===")
        resultats.forEach { System.out.println("  ${it.message}") }
        assertEquals(
            "le Kotlin STANDARD d'un script résout (stdlib, import, locales) : " +
                resultats.map { it.message },
            0,
            resultats.size,
        )
        session.close()
    }

    @Test
    fun `gradle kts - garde D3 - le segment qualifie inconnu reste signale`() {
        val session = session()
        val source = "val x = java.io.Fichier(\"essai\")\n"
        val uri = "file:///projet/garde.gradle.kts"
        session.ouvrirDocument(uri, source, script = true)
        val resultats = nonResolus(session, uri)
        System.out.println("=== GARDE D3 SCRIPT (${resultats.size}) ===")
        resultats.forEach { System.out.println("  ${it.message}") }
        assertEquals(
            "le segment QUALIFIÉ inconnu reste signalé (D3) : ${resultats.map { it.message }}",
            1,
            resultats.size,
        )
        assertTrue(resultats.single().message.contains("Fichier"))
        session.close()
    }

    @Test
    fun `gradle kts - survol du Kotlin standard du script`() {
        val session = session()
        val source =
            """
            import java.io.File

            val message = listOf("a", "b").map { it.uppercase() }.joinToString("-")
            val fichier = File("essai.txt")
            """.trimIndent() + "\n"
        val uri = "file:///projet/outil.gradle.kts"
        session.ouvrirDocument(uri, source, script = true)
        // Le survol du KOTLIN STANDARD répond dans un script (les
        // receivers Gradle restent muets — eux ne sont pas prouvables).
        val survol = session.survol(uri, source.indexOf("listOf"))
        System.out.println("SONDAGE survol listOf (script) => ${survol?.valeur}")
        assertNotNull("le survol de l'appelable standard répond dans un script", survol)
        val survolLocale = session.survol(uri, source.indexOf("message"))
        System.out.println("SONDAGE survol message (script) => ${survolLocale?.valeur}")
        assertNotNull("le survol de la locale du script répond", survolLocale)
        session.close()
    }
}
