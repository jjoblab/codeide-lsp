package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tests ADR 0216 — la voie 2-ter « membres HÉRITÉS » de
 * `ResolutionReferences.estResolu` (découverte E3 du rapport d'audit
 * §14) : un appel NU à un membre d'un SUPERTYPE dans une classe du
 * projet ne doit PLUS être un faux `kt.unresolved`.
 *
 * Cas du propriétaire : `setContentView(…)` dans un
 * `class MainActivity : AppCompatActivity()` et
 * `instantiate(…)` dans un `FragmentFactory` — le moteur lit la
 * clause ÉCRITE de la classe englobante (`AppCompatActivity()`), la
 * résout en FQCN par les imports, puis sonde la fermeture transitive
 * CLASSPATH (`ResolutionKotlin.methodesNommees` traverse toute la
 * chaîne binaire) et PROJET (membres DÉCLARÉS puis clause du FRÈRE
 * relue par le port du GO TO Super).
 *
 * Vues factices au patron de la suite K5 (octets .class minimaux au
 * fil de l'eau) : `android.app.Activity` (setContentView /
 * moveTaskToBack / champ protégé mFenetre) et
 * `androidx.fragment.app.FragmentFactory` (instantiate) — miroir
 * HONNÊTE du bug rapporté (supertype BINAIRE, pas source projet).
 */
class SessionLangageKotlinHeritageTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Vue factice — Activity/FragmentFactory binaires + leurs membres. */
    private val vue = VueHeritageFactice()

    /** Session nourrie du classpath factice + des racines données. */
    private fun session(racines: List<File>): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = racines,
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

    // ── Héritage DIRECT classpath ──────────────────────────────────────

    @Test
    fun `methode heritee directe - setContentView non signalee`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun demo() {
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///mon-activite.kt"
        session.ouvrirDocument(uri, source)
        val nonResolus = nonResolus(session, uri)
        // AVANT ADR 0216 : « Référence non résolue : setContentView » —
        // la voie 2 (membres DÉCLARÉS) ne voit pas l'héritage.
        assertEquals("le membre hérité doit résoudre : $nonResolus", 0, nonResolus.size)
        session.close()
    }

    @Test
    fun `methode heritee transitives - findViewById et moveTaskToBack non signales`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun demo(): Boolean {
                findViewById(1)
                return moveTaskToBack(true)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///transitive.kt"
        session.ouvrirDocument(uri, source)
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `champ herite - mFenetre non signale`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun lit(): Int = mFenetre
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///champ.kt"
        session.ouvrirDocument(uri, source)
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `instantiate herite - FragmentFactory non signale`() {
        val session = session(emptyList())
        val source =
            """
            import androidx.fragment.app.FragmentFactory

            class MaFabrique : FragmentFactory() {
              fun creer() {
                instantiate(null, "com.ex.Zzz")
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///fabrique.kt"
        session.ouvrirDocument(uri, source)
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    // ── Chaîne PROJET → classpath (le port du GO TO Super) ────────────

    /**
     * Sème un paquet `com.ex.app` avec [BaseActivite] (projet, étend
     * Activity classpath) — le FRÈRE FERMÉ que le port relit.
     */
    private fun semerPaquet(vararg classes: Pair<String, String>): File {
        val racine = dossierTemporaire.newFolder("src")
        val dossier = File(racine, "com/ex/app").apply { mkdirs() }
        for ((nomFichier, contenu) in classes) {
            File(dossier, nomFichier).writeText(contenu)
        }
        return racine
    }

    private val baseActiviteSource =
        """
        package com.ex.app

        import android.app.Activity

        open class BaseActivite : Activity() {
          fun aCote() = 1
        }
        """.trimIndent() + "\n"

    @Test
    fun `frere projet entre l appel et le classpath - setContentView non signalee`() {
        val racine = semerPaquet("BaseActivite.kt" to baseActiviteSource)
        val session = session(listOf(racine))
        val source =
            """
            package com.ex.app

            class Enfant : BaseActivite() {
              fun go() {
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = File(racine, "com/ex/app/Enfant.kt").toURI().toString()
        session.ouvrirDocument(uri, source)
        // La fermeture traverse Enfant → BaseActivite (FRÈRE projet, lu par
        // le PORT — arbre vif du frère OUVERT non requis : il est FERMÉ,
        // lecture disque) → Activity (classpath) → setContentView.
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `membre declare du frere projet herite - aCote non signale`() {
        val racine = semerPaquet("BaseActivite.kt" to baseActiviteSource)
        val session = session(listOf(racine))
        val source =
            """
            package com.ex.app

            class Enfant : BaseActivite() {
              fun go(): Int = aCote()
            }
            """.trimIndent().replace("    ", "  ")
        val uri = File(racine, "com/ex/app/Enfant.kt").toURI().toString()
        session.ouvrirDocument(uri, source)
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `frere ouvert vivant - setContentView non signalee (arbre vif)`() {
        val racine = semerPaquet("BaseActivite.kt" to baseActiviteSource)
        val session = session(listOf(racine))
        val uriBase = File(racine, "com/ex/app/BaseActivite.kt").toURI().toString()
        session.ouvrirDocument(uriBase, baseActiviteSource)
        val source =
            """
            package com.ex.app

            class Enfant : BaseActivite() {
              fun go() {
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = File(racine, "com/ex/app/Enfant.kt").toURI().toString()
        session.ouvrirDocument(uri, source)
        // Le port lit l'arbre VIF du frère OUVERT — jamais le disque périmé.
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    // ── Régressions : le diagnostic reste UN ALTER ego honnête ────────

    @Test
    fun `E4 - type qualifie existant - segments muets (android os Bundle)`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              override fun onCreate(savedInstanceState: android.os.Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///type-qualifie.kt"
        session.ouvrirDocument(uri, source)
        // AVANT E4 : TROIS faux « Référence non résolue » — android, os,
        // Bundle — alors que le type COMPLET vit dans la vue du classpath.
        assertEquals(0, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `E4bis - type qualifie inexistant - un seul segment faillible`() {
        val session = session(emptyList())
        val source =
            """
            fun demo(param: zz.qqq.Inconnu) {
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///type-inconnu.kt"
        session.ouvrirDocument(uri, source)
        val nonResolus = nonResolus(session, uri)
        // kotlinc signale UN segment faillible, jamais les PAQUETS zz/qqq.
        assertEquals("seul le DERNIER segment (nom de classe) : $nonResolus", 1, nonResolus.size)
        assertTrue(nonResolus.single().message.contains("Inconnu"))
        session.close()
    }

    @Test
    fun `regression - nom reellement inconnu reste signale`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun demo() {
                zzInconnuNu()
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///regression.kt"
        session.ouvrirDocument(uri, source)
        val nonResolus = nonResolus(session, uri)
        assertEquals("un vrai inconnu DOIT rester signalé : $nonResolus", 1, nonResolus.size)
        assertTrue(nonResolus.single().message.contains("zzInconnuNu"))
        session.close()
    }

    @Test
    fun `regression - methode absente du supertype reste signalee`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun demo() {
                methodeQuiNexistePas()
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///regression-absente.kt"
        session.ouvrirDocument(uri, source)
        assertEquals(1, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `regression - sans supertype, pas de voie heritee magique`() {
        val session = session(emptyList())
        val source =
            """
            class TouteSeule {
              fun demo() {
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///sans-supertype.kt"
        session.ouvrirDocument(uri, source)
        // Pas de clause de supertype : la voie 2-ter ne peut RIEN prouver
        // — le diagnostic reste (sous-rapport impossible ici, Activity
        // n'est PAS un supertype de TouteSeule).
        assertEquals(1, nonResolus(session, uri).size)
        session.close()
    }

    @Test
    fun `quick-fix import - membre herite ne propose aucun import`() {
        val session = session(emptyList())
        val source =
            """
            import android.app.Activity

            class MonActivite : Activity() {
              fun demo() {
                setContentView(null)
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///quickfix.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("setContentView")
        // MÊME prédicat que le diagnostic (ADR 0216) : résolu par
        // héritage ⇒ AUCUN candidat d'import — sinon l'ampoule
        // proposerait un import absurde pour un appel déjà résolu.
        assertEquals(0, session.importsCandidats(uri, decalage).size)
        session.close()
    }

    // ── Vue factice ────────────────────────────────────────────────────

    /**
     * Classpath factice : `android.app.Activity` (super Object,
     * méthodes setContentView/findViewById/moveTaskToBack + champ
     * protégé mFenetre), `androidx.fragment.app.FragmentFactory`
     * (super Object, instantiate), `androidx.fragment.app.Fragment`
     * (type de retour), `android.view.View` (paramètre).
     */
    private class VueHeritageFactice : VueClasspathKotlin {
        private val paquetsConnus: Set<String> =
            setOf(
                "android",
                "android.app",
                "android.os",
                "android.view",
                "androidx",
                "androidx.fragment",
                "androidx.fragment.app",
            )

        private val membres: Map<String, jo.lspkotlin.api.MembresTypeKotlin> =
            mapOf(
                "android.app.Activity" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0021,
                        listOf(
                            jo.lspkotlin.api.MembreJvmKotlin(0x0004, "mFenetre", "Landroid/view/View;"),
                        ),
                        listOf(
                            jo.lspkotlin.api.MembreJvmKotlin(
                                0x0001,
                                "setContentView",
                                "(Landroid/view/View;)V",
                            ),
                            jo.lspkotlin.api.MembreJvmKotlin(0x0001, "findViewById", "(I)Landroid/view/View;"),
                            jo.lspkotlin.api.MembreJvmKotlin(0x0001, "moveTaskToBack", "(Z)Z"),
                        ),
                    ),
                "androidx.fragment.app.FragmentFactory" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(
                            jo.lspkotlin.api.MembreJvmKotlin(
                                0x0001,
                                "instantiate",
                                "(Ljava/lang/ClassLoader;Ljava/lang/String;)Landroidx/fragment/app/Fragment;",
                            ),
                        ),
                    ),
                "androidx.fragment.app.Fragment" to
                    jo.lspkotlin.api.MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.os.Bundle" to
                    jo.lspkotlin.api.MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "android.view.View" to
                    jo.lspkotlin.api.MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "java.lang.Object" to
                    jo.lspkotlin.api.MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )

        private val superTypes: Map<String, String?> =
            mapOf(
                "android.app.Activity" to "java.lang.Object",
                "androidx.fragment.app.FragmentFactory" to "java.lang.Object",
                "androidx.fragment.app.Fragment" to "java.lang.Object",
                "android.os.Bundle" to "java.lang.Object",
                "android.view.View" to "java.lang.Object",
                "java.lang.Object" to null,
            )

        override fun nomsParPrefixe(
            prefixe: String,
            limite: Int,
        ): List<jo.lspkotlin.api.NomTypeKotlin> =
            when {
                prefixe == "Activ" || prefixe == "Activity" ->
                    listOf(jo.lspkotlin.api.NomTypeKotlin("Activity", listOf("android.app.Activity")))

                prefixe.startsWith("FragmentFa") ->
                    listOf(
                        jo.lspkotlin.api.NomTypeKotlin(
                            "FragmentFactory",
                            listOf("androidx.fragment.app.FragmentFactory"),
                        ),
                    )

                else -> emptyList()
            }

        override fun typesDansPaquet(paquet: String): List<String> =
            when (paquet) {
                "android.app" -> listOf("android.app.Activity")
                "androidx.fragment.app" ->
                    listOf(
                        "androidx.fragment.app.FragmentFactory",
                        "androidx.fragment.app.Fragment",
                    )
                "android.view" -> listOf("android.view.View")
                else -> emptyList()
            }

        override fun estPaquet(nom: String): Boolean = nom in paquetsConnus || nom == "java.lang"

        override fun sousPaquetsDirects(nom: String): List<String> =
            when (nom) {
                "android" -> listOf("android.app", "android.view")
                "androidx" -> listOf("androidx.fragment")
                "androidx.fragment" -> listOf("androidx.fragment.app")
                else -> emptyList()
            }

        override fun membres(fqcn: String): jo.lspkotlin.api.MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? =
            if (superTypes.containsKey(fqcn)) {
                octetsSynthetiques(fqcn, superTypes[fqcn])
            } else {
                null
            }

        /** Classe minimale (cf. VueClasspathFacticeK5) au fil de l'eau. */
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
