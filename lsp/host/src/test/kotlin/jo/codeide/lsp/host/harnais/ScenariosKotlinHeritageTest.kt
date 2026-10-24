package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.client
import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeur
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SCÉNARIOS KOTLIN ANDROID « MEMBRES HÉRITÉS »
 * (découverte E3, ADR 0216).
 *
 * Le CAS RAPPORTÉ PAR LE PROPRIÉTAIRE au fil du VRAI transport stdio
 * (classpath de production : kotlin-stdlib + android.jar de la
 * plateforme 37.2) : un projet Android Kotlin dont l'activité appelle
 * NU un membre du SUPERTYPE binaire —
 *
 * > « Référence non résolue : « setContentView » »
 *
 * CAUSE RACINE : la résolution des références NUES ne couvrait que les
 * membres DÉCLARÉS des classes englobantes — JAMAIS les HÉRITÉS de la
 * fermeture transitive des supertypes (classpath ou projet).
 *
 * PREUVES :
 *  KH1 — `class MonActivite : Activity()` appelant NU
 *       `setContentView(null)` : AUCUN `kt.unresolved` (la chaîne
 *       binaire Activity → … porte le membre, platorm android.jar) ;
 *  KH2 — régression : un nom RÉELLEMENT inconnu reste signalé ;
 *  KH3 — chaîne PROJET → classpath : un FRÈRE OUVERT
 *       (`BaseActivite : Activity()`) entre l'appel et la plateforme.
 */
@Timeout(900)
@Suppress("TooManyFunctions")
class ScenariosKotlinHeritageTest {
    /** Ouvre un fichier du projet (texte disque) et retourne l'URI. */
    private fun ouvrir(
        connexion: OutilsHarnais.Connexion,
        cheminRelatif: String,
        texte: String,
        langage: String = "kotlin",
    ): String {
        val uri = File(connexion.racine, cheminRelatif).toURI().toString()
        connexion.distant.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, langage, 1, texte)),
        )
        return uri
    }

    /** Les kt.unresolved de la dernière publication de [uri]. */
    private fun nonResolus(
        connexion: OutilsHarnais.Connexion,
        uri: String,
    ): List<org.eclipse.lsp4j.Diagnostic> =
        client(connexion).attendre(uri).diagnostics.filter { it.code?.left == "kt.unresolved" }

    @Test
    fun `KH1 - setContentView herite d Activity - aucun kt_unresolved`() {
        val connexion = demarrerServeur("kh1")
        try {
            val source =
                """
                package jo.demo

                import android.app.Activity

                class MonActivite : Activity() {
                  override fun onCreate(savedInstanceState: android.os.Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(null)
                  }
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "src/jo/demo/MonActivite.kt", source)
            val nonResolus = nonResolus(connexion, uri)
            assertEquals(
                0,
                nonResolus.size,
                "un appel NU à un membre HÉRITÉ de la plateforme ne doit plus être " +
                    "un faux kt.unresolved : ${nonResolus.map { it.message }}",
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `KH2 - regression - nom reellement inconnu reste signale`() {
        val connexion = demarrerServeur("kh2")
        try {
            val source =
                """
                package jo.demo

                import android.app.Activity

                class MonActivite : Activity() {
                  fun demo() {
                    zzInconnuNu()
                  }
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "src/jo/demo/Inconnu.kt", source)
            val nonResolus = nonResolus(connexion, uri)
            assertEquals(1, nonResolus.size, "le vrai inconnu DOIT rester signalé")
            assertEquals(DiagnosticSeverity.Error, nonResolus.single().severity)
            assertTrue(nonResolus.single().message.contains("zzInconnuNu"))
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `KH3 - frere projet ouvert entre l appel et le classpath`() {
        val connexion = demarrerServeur("kh3")
        try {
            // Le FRÈRE OUVERT (arbre vif) porte la clause `: Activity()` —
            // la fermeture traverse Enfant → BaseActivite (projet) →
            // Activity (android.jar).
            val base =
                """
                package jo.demo

                import android.app.Activity

                open class BaseActivite : Activity()
                """.trimIndent() + "\n"
            ouvrir(connexion, "src/jo/demo/BaseActivite.kt", base)
            val enfant =
                """
                package jo.demo

                class Enfant : BaseActivite() {
                  fun go() {
                    setContentView(null)
                  }
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "src/jo/demo/Enfant.kt", enfant)
            val nonResolus = nonResolus(connexion, uri)
            assertEquals(
                0,
                nonResolus.size,
                "la fermeture PROJET → classpath doit résoudre l'appel hérité : " +
                    nonResolus.map { it.message },
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `KH4 - diagnostic du document ferme n est pas rafraichi par la frere`() {
        val connexion = demarrerServeur("kh4")
        try {
            val source =
                """
                package jo.demo

                class TouteSeule {
                  fun demo() {
                    methodeQuiNexistePas()
                  }
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "src/jo/demo/TouteSeule.kt", source)
            val nonResolus = nonResolus(connexion, uri)
            assertEquals(1, nonResolus.size, "sans supertype, la voie héritée ne prouve RIEN")
            assertTrue(nonResolus.single().message.contains("methodeQuiNexistePas"))
        } finally {
            connexion.arreter()
        }
    }
}
