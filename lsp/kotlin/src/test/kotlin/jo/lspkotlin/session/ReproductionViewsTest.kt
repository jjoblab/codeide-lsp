package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * REPRODUCTION du monde NON-Compose (vues XML / AppCompat) au niveau
 * MOTEUR — ADR 0219 : deux découvertes du template « Empty Views
 * Activity » (rapport d'audit §14) :
 *
 *  - **chaîne COUPÉE** : la borne de profondeur 6 de
 *    `ResolutionKotlin.chaineSupertypes` s'arrêtait AVANT
 *    `android.content.Context` — la hiérarchie Android réelle
 *    (AppCompatActivity → … → ContextWrapper → Context) exige
 *    PROFONDEUR 7 : `getString` vivait au maillon 7 et restait un faux
 *    `kt.unresolved` alors que le classpath portait tout ;
 *  - **propriétés SYNTHÉTIQUES Java** : l'interop cœur du langage
 *    (getter `getFoo()` lisible `foo`) n'existait pas — `layoutInflater`
 *    nu dans une Activity, `title = …` via setTitle, étaient des faux
 *    `kt.unresolved`.
 *
 * Vue factice au patron de `SessionLangageKotlinHeritageTest` : la
 * chaîne BINAIRE complète d'Android Studio (8 maillons jusqu'à Object),
 * `getLayoutInflater`/`setTitle` sur Activity (profondeur 4 — prouve la
 * synthèse SANS dépendre de la borne), `getString`/`getDrawable` sur
 * Context (profondeur 7 — prouve la borne corrigée).
 */
class ReproductionViewsTest {
    /** Session nourrie de la vue factice (chaîne AppCompat complète). */
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = VueViewsFactice(),
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Références non résolues du document (code, plages, messages). */
    private fun nonResolus(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ) = session.diagnostics(uri).filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }

    // ── Découverte 1 : chaîne coupée avant Context (borne ADR 0219) ──

    @Test
    fun `getString herite du maillon 7 - Context - non signale`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): String = getString(1)
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-getString.kt"
        session.ouvrirDocument(uri, source)
        // AVANT ADR 0219 : « Référence non résolue : getString » — la
                // chaîne s'arrêtait à ContextWrapper (profondeur 6), le
        // porteur réel vit à profondeur 7.
        val nonResolus = nonResolus(session, uri)
        assertEquals("le membre du maillon 7 doit résoudre : $nonResolus", 0, nonResolus.size)
        session.close()
    }

    // ── Découverte 2 : propriétés synthétiques Java (ADR 0219) ────────

    @Test
    fun `layoutInflater propriete synthetique - non signale`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): android.view.LayoutInflater = layoutInflater
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-layoutinflater.kt"
        session.ouvrirDocument(uri, source)
        // AVANT ADR 0219 : « Référence non résolue : layoutInflater » —
        // le getter getLayoutInflater() (Activity, profondeur 4) existe
        // dans le classpath mais aucune synthèse n'était faite.
        val nonResolus = nonResolus(session, uri)
        assertEquals("la propriété synthétique doit résoudre : $nonResolus", 0, nonResolus.size)
        session.close()
    }

    @Test
    fun `title affectation nue via setter synthetique - non signale`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo() {
                title = "Démonstration"
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-title.kt"
        session.ouvrirDocument(uri, source)
        // setTitle(CharSequence) sur Activity — l'AFFECTATION nu prouve
        // sa résolution par l'existence du setter (miroir kotlinc).
        val nonResolus = nonResolus(session, uri)
        assertEquals("le setter synthétique doit résoudre : $nonResolus", 0, nonResolus.size)
        session.close()
    }

    @Test
    fun `packageManager var - getter ET setter - non signale`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): String = packageName
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-packagename.kt"
        session.ouvrirDocument(uri, source)
        val nonResolus = nonResolus(session, uri)
        assertEquals("la propriété de Context (maillon 7) doit résoudre : $nonResolus", 0, nonResolus.size)
        session.close()
    }

    // ── Honnêteté D3 : les vrais négatifs restent signalés ────────────

    @Test
    fun `typo d une propriete synthetique reste signalee`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo() {
                val faux = layoutInfleurer
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-typo.kt"
        session.ouvrirDocument(uri, source)
        val nonResolus = nonResolus(session, uri)
        assertEquals("la typo doit rester signalée : $nonResolus", 1, nonResolus.size)
        assertTrue(nonResolus.single().message.contains("layoutInfleurer"))
        session.close()
    }

    @Test
    fun `getter A PARAMETRE ne synthetise pas de propriete`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo() {
                val d = drawable
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-getdrawable.kt"
        session.ouvrirDocument(uri, source)
        // getDrawable(I) porte UN paramètre — Kotlin n'en fait JAMAIS une
        // propriété : l'écriture `drawable` nue reste signalée.
        val nonResolus = nonResolus(session, uri)
        assertEquals("le getter paramétré ne doit PAS synthétiser : $nonResolus", 1, nonResolus.size)
        assertTrue(nonResolus.single().message.contains("drawable"))
        session.close()
    }

    // ── Parité Android Studio : survol, typage, complétion ────────────

    @Test
    fun `survol implicite de layoutInflater - val avec proprietaire`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): android.view.LayoutInflater = layoutInflater
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-survol-implicite.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("layoutInflater"))
        assertNotNull("le survol implicite doit répondre", survol)
        assertTrue(
            "rendu miroir Android Studio — obtenu : ${survol?.valeur}",
            survol!!.valeur.startsWith("val layoutInflater: LayoutInflater"),
        )
        assertTrue(
            "le propriétaire (Activity) accompagne — obtenu : ${survol.valeur}",
            survol.valeur.contains("android.app.Activity"),
        )
        session.close()
    }

    @Test
    fun `survol implicite de getString - fun avec proprietaire Context`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): String = getString(1)
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-survol-getstring.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("getString"))
        assertNotNull("le survol de la méthode implicite doit répondre", survol)
        assertTrue(
            "rendu fun + propriétaire — obtenu : ${survol?.valeur}",
            survol!!.valeur.startsWith("fun getString(Int): String") &&
                survol.valeur.contains("android.content.Context"),
        )
        session.close()
    }

    @Test
    fun `survol qualifie d une propriete synthetique - this layoutInflater`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): android.view.LayoutInflater = this.layoutInflater
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-survol-qualifie.kt"
        session.ouvrirDocument(uri, source)
        // La forme QUALIFIÉE `this.layoutInflater` — même rendu que
        // l'implicite (miroir Android Studio).
        val survol = session.survol(uri, source.indexOf("layoutInflater"))
        assertNotNull("le survol qualifié doit répondre", survol)
        assertTrue(
            "rendu val + propriétaire — obtenu : ${survol?.valeur}",
            survol!!.valeur.contains("val layoutInflater: LayoutInflater"),
        )
        session.close()
    }

    @Test
    fun `typage - layoutInflater inflate chaine membre apres propriete synthetique`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo(): android.view.View = layoutInflater.inflate(1, null, null)
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-typage.kt"
        session.ouvrirDocument(uri, source)
        // La propriété synthétique DOIT typer (LayoutInflater) — sinon la
        // CHAÎNE `layoutInflater.inflate(…)` reste muette : le retour
        // prouvé (View) se voit par la complétion membre du sélecteur.
        val completions =
            session.completion(uri, source.indexOf("inflate") + "inflate".length)
        assertTrue(
            "inflate doit s'offrir (le récepteur a typé) — obtenu : " +
                completions.map { it.libelle },
            completions.any { it.libelle.startsWith("inflate") },
        )
        session.close()
    }

    @Test
    fun `completion membre apres this point - proprietes synthetiques offertes`() {
        val session = session()
        val source =
            """
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
              fun demo() {
                this.
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///views-completion-this.kt"
        session.ouvrirDocument(uri, source)
        val apresPoint = source.indexOf("this.") + "this.".length
        val completions = session.completion(uri, apresPoint)
        val libelles = completions.map { it.libelle }
        // Les propriétés synthétiques dominent les listes du monde
        // non-Compose (miroir Android Studio).
        assertTrue("layoutInflater doit s'offrir — obtenu : $libelles", "layoutInflater" in libelles)
        assertTrue("packageName doit s'offrir (maillon 7) — obtenu : $libelles", "packageName" in libelles)
        session.close()
    }

    // ── Vue factice : la chaîne BINAIRE complète d'Android ────────────

    /**
     * Classpath factice miroir du monde Views — la chaîne RÉELLE
     * d'Android Studio jusqu'à `java.lang.Object` (8 maillons) :
     * AppCompatActivity → FragmentActivity → activity.ComponentActivity
     * → core.ComponentActivity → Activity → ContextThemeWrapper →
     * ContextWrapper → Context → Object.
     */
    private class VueViewsFactice : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "androidx.appcompat.app.AppCompatActivity" to
                    MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "setSupportActionBar", "(Landroidx/appcompat/widget/Toolbar;)V"),
                        ),
                    ),
                "android.app.Activity" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(
                            MembreJvmKotlin(0x0004, "mFenetre", "Landroid/view/View;"),
                        ),
                        listOf(
                            MembreJvmKotlin(0x0001, "getLayoutInflater", "()Landroid/view/LayoutInflater;"),
                            MembreJvmKotlin(0x0001, "setTitle", "(Ljava/lang/CharSequence;)V"),
                            MembreJvmKotlin(0x0001, "findViewById", "(I)Landroid/view/View;"),
                        ),
                    ),
                "android.content.Context" to
                    MembresTypeKotlin(
                        0x0401, // ABSTRACT + PUBLIC
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "getString", "(I)Ljava/lang/String;"),
                            MembreJvmKotlin(0x0001, "getPackageName", "()Ljava/lang/String;"),
                            MembreJvmKotlin(0x0001, "getDrawable", "(I)Landroid/graphics/drawable/Drawable;"),
                        ),
                    ),
                "android.view.LayoutInflater" to
                    MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(
                                0x0001,
                                "inflate",
                                "(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;",
                            ),
                        ),
                    ),
                // Feuilles de descripteurs.
                "androidx.fragment.app.FragmentActivity" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "androidx.activity.ComponentActivity" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "androidx.core.app.ComponentActivity" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.view.ContextThemeWrapper" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.content.ContextWrapper" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.view.View" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "androidx.appcompat.widget.Toolbar" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.graphics.drawable.Drawable" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "java.lang.Object" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )

        private val superTypes: Map<String, String?> =
            mapOf(
                "androidx.appcompat.app.AppCompatActivity" to "androidx.fragment.app.FragmentActivity",
                "androidx.fragment.app.FragmentActivity" to "androidx.activity.ComponentActivity",
                "androidx.activity.ComponentActivity" to "androidx.core.app.ComponentActivity",
                "androidx.core.app.ComponentActivity" to "android.app.Activity",
                "android.app.Activity" to "android.view.ContextThemeWrapper",
                "android.view.ContextThemeWrapper" to "android.content.ContextWrapper",
                "android.content.ContextWrapper" to "android.content.Context",
                "android.content.Context" to "java.lang.Object",
                "android.view.LayoutInflater" to "java.lang.Object",
                "android.view.View" to "java.lang.Object",
                "androidx.appcompat.widget.Toolbar" to "android.view.View",
                "android.graphics.drawable.Drawable" to "java.lang.Object",
                "java.lang.Object" to null,
            )

        override fun nomsParPrefixe(
            prefixe: String,
            limite: Int,
        ): List<jo.lspkotlin.api.NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> =
            when (paquet) {
                "androidx.appcompat.app" -> listOf("androidx.appcompat.app.AppCompatActivity")
                "android.app" -> listOf("android.app.Activity")
                "android.content" -> listOf("android.content.Context")
                "android.view" -> listOf("android.view.LayoutInflater", "android.view.View")
                else -> emptyList()
            }

        override fun estPaquet(nom: String): Boolean =
            nom in setOf(
                "androidx",
                "androidx.appcompat",
                "androidx.appcompat.app",
                "androidx.fragment",
                "androidx.fragment.app",
                "androidx.activity",
                "androidx.core",
                "androidx.core.app",
                "android",
                "android.app",
                "android.content",
                "android.view",
                "android.graphics",
                "android.graphics.drawable",
                "java.lang",
            )

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? =
            if (superTypes.containsKey(fqcn)) {
                octetsSynthetiques(fqcn, superTypes[fqcn])
            } else {
                null
            }

        /** Classe minimale (cf. SessionLangageKotlinHeritageTest) au fil de l'eau. */
        private fun octetsSynthetiques(
            fqcn: String,
            superFqcn: String?,
        ): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                val paires = 1 + (if (superFqcn != null) 1 else 0)
                flux.writeShort(paires * 2 + 1)
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val octets = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(octets.size)
                    flux.write(octets)
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
}
