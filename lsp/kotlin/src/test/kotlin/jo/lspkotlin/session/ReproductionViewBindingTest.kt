package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * REPRODUCTION de la forme ViewBinding au niveau MOTEUR (ADR 0219) —
 * le récepteur est une PROPRIÉTÉ typée (private lateinit var
 * binding : ActivityMainBinding) : la complétion membre doit offrir
 * root/textView/inflate/bind du groupe synthétique K5, TOUJOURS dans
 * le sous-paquet `databinding` (convention AGP réelle — le template
 * Android Studio « Empty Views Activity » importe
 * com.ex.app.databinding.ActivityMainBinding).
 */
class ReproductionViewBindingTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    private fun vue(): VueClasspathKotlin = object : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "androidx.appcompat.app.AppCompatActivity" to
                    MembresTypeKotlin(0x0021, emptyList(), listOf(MembreJvmKotlin(0x0001, "setContentView", "(I)V"))),
                "android.app.Activity" to
                    MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "getLayoutInflater", "()Landroid/view/LayoutInflater;"),
                        ),
                    ),
                "android.view.LayoutInflater" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "java.lang.Object" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )
        private val supers: Map<String, String?> =
            mapOf(
                "androidx.appcompat.app.AppCompatActivity" to "android.app.Activity",
                "android.app.Activity" to "java.lang.Object",
                "android.view.LayoutInflater" to "java.lang.Object",
                "java.lang.Object" to null,
            )

        override fun nomsParPrefixe(prefixe: String, limite: Int) = emptyList<jo.lspkotlin.api.NomTypeKotlin>()
        override fun typesDansPaquet(paquet: String) = emptyList<String>()
        override fun estPaquet(nom: String) = nom == "androidx.appcompat.app" || nom == "java.lang"
        override fun sousPaquetsDirects(nom: String) = emptyList<String>()
        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]
        override fun octetsClasse(fqcn: String): ByteArray? =
            supers[fqcn]?.let { octets(fqcn, it) } ?: if (fqcn == "java.lang.Object") octets(fqcn, null) else null

        private fun octets(fqcn: String, superFqcn: String?): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                val paires = 1 + (if (superFqcn != null) 1 else 0)
                flux.writeShort(paires * 2 + 1)
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val o = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(o.size)
                    flux.write(o)
                }
                fun classe(idx: Int) {
                    flux.writeByte(7)
                    flux.writeShort(idx)
                }
                var index = 1
                val thisUtf8 = index
                utf8(fqcn.replace('.', '/'))
                index += 2
                classe(thisUtf8)
                var superClasse = 0
                if (superFqcn != null) {
                    val superUtf8 = index
                    utf8(superFqcn.replace('.', '/'))
                    index += 2
                    classe(superUtf8)
                    superClasse = superUtf8 + 1
                }
                flux.writeShort(0x0021)
                flux.writeShort(thisUtf8 + 1)
                flux.writeShort(superClasse)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
            }
            return sortie.toByteArray()
        }
    }

    /** Sème src/main/java + res/layout + manifest du paquet com.ex.app. */
    private fun semer(): File {
        val dossier = dossierTemporaire.newFolder()
        val srcMain = File(dossier, "src/main")
        File(srcMain, "java").mkdirs()
        File(srcMain, "res/layout").mkdirs()
        File(srcMain, "AndroidManifest.xml").writeText(
            """<?xml version="1.0"?><manifest package="com.ex.app" />""",
        )
        File(srcMain, "res/layout/activity_main.xml").writeText(
            """<?xml version="1.0"?>
            <androidx.constraintlayout.widget.ConstraintLayout
                xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/textView" />
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
        )
        return File(srcMain, "java")
    }

    @Test
    fun `viewbinding - propriete typée, completion membre du groupe K5`() {
        val racine = semer()
        // Sondage direct du constructeur de groupes K5.
        val synthetiques = jo.lspkotlin.synthetics.ClassesSynthetiques()
        synthetiques.definirRacines(listOf(racine))
        val groupes = synthetiques.groupes()
        System.out.println("SONDAGE groupes K5 (${groupes.size}): ${groupes.map { it.fqcn }}")

        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue(),
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        val source =
            """
            package com.ex.app

            import androidx.appcompat.app.AppCompatActivity
            import com.ex.app.databinding.ActivityMainBinding

            class MainActivity : AppCompatActivity() {
              private lateinit var binding: ActivityMainBinding

              override fun onCreate(etat: android.os.Bundle?) {
                super.onCreate(etat)
                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///com/ex/app/MainActivity.kt"
        session.ouvrirDocument(uri, source)

        val diags = session.diagnostics(uri)
        System.out.println("SONDAGE diagnostics (${diags.size}): ${diags.map { it.message }}")

        val apresBinding = source.indexOf("binding.root") + "binding.".length
        val completions = session.completion(uri, apresBinding)
        System.out.println("SONDAGE completion binding. => ${completions.map { it.libelle }.take(12)}")
        assertTrue(
            "root doit s'offrir — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle == "root" },
        )
        session.close()
    }
}
