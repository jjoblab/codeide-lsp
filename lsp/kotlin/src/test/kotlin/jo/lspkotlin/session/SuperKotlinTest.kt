package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du GO TO « Super » Kotlin (ADR 0210 — `textDocument/
 * superDefinition`, miroir des scénarios `NavigationFacadeTest` du LSP
 * Java) — par le SPI (chemin de production D6), moteur RÉEL, projet
 * multi-fichiers sur disque (racines sources du scan, même montage que
 * [TypesKotlinTest]).
 *
 * Le cœur éprouvé : les DEUX chemins du picker Super — (1) MEMBRE au
 * caret (déclaration outrepassante, ou usage RÉSOLU par la cible
 * occurrences — un homonyme AMBIGU n'est jamais présumé, borne O3) →
 * le même-nommé dans chaque supertype TRANSITIF projet
 * (`« name  ·  Super »`) ;
 * (2) sinon le type en contexte (référence de type, à défaut la classe
 * englobante) → ses supertypes DIRECTS (`« Simple  ·  pkg »`). Chaque
 * supertype est prouvé PAR FQCN (les imports de SON fichier) — jamais
 * par homonymie ; un supertype sans source projet est ABSENT.
 */
class SuperKotlinTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /**
     * Session avec racines sources (scan multi-fichiers actif) — les
     * documents sont ouverts sous leur URI disque RÉELLE (comme la
     * production : le scan exclut les documents ouverts PAR URI).
     */
    private fun sessionAvecRacines(
        vararg fichiers: Pair<String, String>,
    ): Pair<SessionLangageKotlin, Map<String, String>> {
        val racine = dossierTemporaire.newFolder("src")
        val uris = mutableMapOf<String, String>()
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine, chemin)
            fichier.parentFile.mkdirs()
            fichier.writeText(contenu)
            uris[chemin] = fichier.toURI().toString()
        }
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = null,
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        return session to uris
    }

    /** Décalage du PREMIER caractère de [occurrence] dans [texte]. */
    private fun decalageDe(
        texte: String,
        occurrence: String,
        numero: Int = 0,
    ): Int {
        var index = -1
        repeat(numero + 1) {
            index = texte.indexOf(occurrence, index + 1)
        }
        return index
    }

    // ── Fixtures (miroir NavigationFacadeTest du LSP Java) ──────────

    private val baseKt =
        """
        package demo

        open class Base {
            val compteur: Int = 0

            open fun run() {
            }
        }
        """.trimIndent()

    private val implAKt =
        """
        package demo

        class ImplA : Base() {
            val tag: Int = 1

            override fun run() {
                compteur + 1
            }
        }
        """.trimIndent()

    private val implBKt =
        """
        package demo

        class ImplB : ImplA() {
            override fun run() {
            }
        }
        """.trimIndent()

    private val greeterKt =
        """
        package demo

        interface Greeter {
            fun greet()
        }
        """.trimIndent()

    private val enGreeterKt =
        """
        package demo

        class EnGreeter : Greeter {
            override fun greet() {
            }
        }
        """.trimIndent()

    private val mainKt =
        """
        package demo

        class Main {
            fun use(a: ImplA) {
                a.run()
            }
        }
        """.trimIndent()

    // ── Chemin MEMBRE ────────────────────────────────────────────────

    @Test
    fun `super sur déclaration outrepassante - le même-nommé du supertype direct`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
            )
        val uriImpl = uris.getValue("demo/ImplA.kt")
        session.ouvrirDocument(uriImpl, implAKt)
        // Caret sur « run » — la déclaration OUTREPASSANTE dans ImplA.
        val cibles = session.superDefinition(uriImpl, decalageDe(implAKt, "run()"))
        assertEquals("run() de Base : $cibles", 1, cibles.size)
        val cible = cibles.single()
        assertEquals(uris.getValue("demo/Base.kt"), cible.uri)
        assertEquals("libellé picker (miroir ecj)", "run  ·  Base", cible.libelle)
        assertEquals("plage du NOM run dans Base.kt", baseKt.indexOf("fun run()") + "fun ".length, cible.debutNom)
        assertEquals("fin du nom", baseKt.indexOf("run()") + "run".length, cible.finNom)
        session.close()
    }

    @Test
    fun `super sur déclaration outrepassante - clôture TRANSITIVE (deux niveaux)`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
                "demo/ImplB.kt" to implBKt,
            )
        val uriImplB = uris.getValue("demo/ImplB.kt")
        session.ouvrirDocument(uriImplB, implBKt)
        // ImplB.run outrepasse ImplA.run qui outrepasse Base.run — la
        // clôture transitive rend les DEUX (miroir allSupertypes ecj).
        val cibles = session.superDefinition(uriImplB, decalageDe(implBKt, "run()"))
        assertEquals("ImplA puis Base : $cibles", 2, cibles.size)
        assertEquals("tri par libellé", "run  ·  Base", cibles[0].libelle)
        assertEquals("run  ·  ImplA", cibles[1].libelle)
        assertEquals(uris.getValue("demo/Base.kt"), cibles[0].uri)
        assertEquals(uris.getValue("demo/ImplA.kt"), cibles[1].uri)
        session.close()
    }

    @Test
    fun `super sur déclaration d'une IMPLÉMENTATION d'interface`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Greeter.kt" to greeterKt,
                "demo/EnGreeter.kt" to enGreeterKt,
            )
        val uriEn = uris.getValue("demo/EnGreeter.kt")
        session.ouvrirDocument(uriEn, enGreeterKt)
        val cibles = session.superDefinition(uriEn, decalageDe(enGreeterKt, "greet()"))
        assertEquals("greet() de l'interface : $cibles", 1, cibles.size)
        val cible = cibles.single()
        assertEquals(uris.getValue("demo/Greeter.kt"), cible.uri)
        assertEquals("greet  ·  Greeter", cible.libelle)
        session.close()
    }

    @Test
    fun `super sur usage AMBIGU d'un override - vide honnête (borne O3)`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
                "demo/Main.kt" to mainKt,
            )
        val uriMain = uris.getValue("demo/Main.kt")
        session.ouvrirDocument(uriMain, mainKt)
        // Caret sur l'INVOCATION a.run() : DEUX homonymes projet
        // (Base.run + ImplA.run) — la cible occurrences refuse
        // l'ambiguïté (miroir du renommage) : le moteur ne présage
        // JAMAIS le membre d'un récepteur de type projet (borne O3 de
        // l'audit — membres hérités non résolus pour récepteurs
        // projet). L'option Super n'apparaît pas : vide, jamais un
        // faux jalon. Le chemin usuel (curseur sur la DÉCLARATION
        // outrepassante) est couvert par les tests ci-dessus.
        val cibles = session.superDefinition(uriMain, decalageDe(mainKt, "run()"))
        assertTrue("usage ambigu : vide (borne O3), pas un faux jalon : $cibles", cibles.isEmpty())
        session.close()
    }

    @Test
    fun `super sur usage UNIQUE d'un membre - les supertypes du déclarant`() {
        val usageKt =
            """
            package demo

            class Usage {
                val hote: ImplA = ImplA()

                fun appel(): Int = hote.tag
            }
            """.trimIndent()
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
                "demo/Usage.kt" to usageKt,
            )
        val uriUsage = uris.getValue("demo/Usage.kt")
        session.ouvrirDocument(uriUsage, usageKt)
        // `tag` n'existe que dans ImplA (cible UNIQUE → prouvée) : le
        // chemin membre éprouve les supertypes du déclarant ImplA,
        // AUCUN homonyme `tag` n'y vit → retombée TYPE : la classe
        // englobante Usage n'a pas de supertype écrit → vide.
        val cibles = session.superDefinition(uriUsage, decalageDe(usageKt, "tag"))
        assertTrue("usage unique sans super vivant : $cibles", cibles.isEmpty())
        session.close()
    }

    @Test
    fun `super sur propriété NON outrepassante - retombée classe englobante`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
            )
        val uriImpl = uris.getValue("demo/ImplA.kt")
        session.ouvrirDocument(uriImpl, implAKt)
        // Caret sur le champ « tag » (aucun homonyme dans Base) — le
        // chemin membre ne trouve rien, la retombée TYPE montre les
        // supertypes DIRECTS de la classe ENGLOBANTE ImplA.
        val cibles = session.superDefinition(uriImpl, decalageDe(implAKt, "tag"))
        assertEquals("le supertype direct : $cibles", 1, cibles.size)
        val cible = cibles.single()
        assertEquals(uris.getValue("demo/Base.kt"), cible.uri)
        assertEquals("libellé picker « Simple  ·  pkg »", "Base  ·  demo", cible.libelle)
        assertEquals("plage du NOM Base", baseKt.indexOf("class Base") + "class ".length, cible.debutNom)
        session.close()
    }

    // ── Chemin TYPE ─────────────────────────────────────────────────

    @Test
    fun `super sur référence de type - les supertypes du type visé`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
                "demo/Main.kt" to mainKt,
            )
        val uriMain = uris.getValue("demo/Main.kt")
        session.ouvrirDocument(uriMain, mainKt)
        // Caret sur « ImplA » (référence de type dans la signature) —
        // le type en contexte EST ImplA (miroir contextType ecj), ses
        // supertypes DIRECTS : Base.
        val cibles = session.superDefinition(uriMain, decalageDe(mainKt, "ImplA"))
        assertEquals("supertype direct de ImplA : $cibles", 1, cibles.size)
        assertEquals("Base  ·  demo", cibles.single().libelle)
        assertEquals(uris.getValue("demo/Base.kt"), cibles.single().uri)
        session.close()
    }

    @Test
    fun `super classe racine sans clause - aucune cible (garde D3)`() {
        val (session, uris) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
                "demo/ImplA.kt" to implAKt,
                "demo/Main.kt" to mainKt,
            )
        val uriBase = uris.getValue("demo/Base.kt")
        session.ouvrirDocument(uriBase, baseKt)
        // Caret sur le champ « compteur » DANS Base : le chemin membre
        // ne trouve pas d'homonyme (Any est IMPLICITE — jamais écrit),
        // la retombée type ne montre que les supertypes ÉCRITS de
        // Base : aucun → vide honnête.
        val cibles = session.superDefinition(uriBase, decalageDe(baseKt, "compteur"))
        assertTrue("aucun supertype écrit : $cibles", cibles.isEmpty())
        session.close()
    }

    @Test
    fun `supertype classpath SANS source projet - aucune cible (garde D3)`() {
        val androidViewKt =
            """
            package demo

            import android.view.View

            class MaVue : View {
                val tag: Int = 1

                override fun toString(): String = "x"
            }
            """.trimIndent()
        // Vue NULLE (classpath absent) : le supertype écrit ne résout
        // vers AUCUNE déclaration projet — vide, jamais un faux jalon.
        val (session, uris) =
            sessionAvecRacines(
                "demo/MaVue.kt" to androidViewKt,
            )
        val uriVue = uris.getValue("demo/MaVue.kt")
        session.ouvrirDocument(uriVue, androidViewKt)
        val cibles = session.superDefinition(uriVue, decalageDe(androidViewKt, "tag"))
        assertTrue("supertype classpath sans source : $cibles", cibles.isEmpty())
        session.close()
    }

    @Test
    fun `document inconnu ou fermé - liste vide, jamais d'exception`() {
        val (session, _) =
            sessionAvecRacines(
                "demo/Base.kt" to baseKt,
            )
        assertTrue("document inconnu : vide", session.superDefinition("file:///introuvable.kt", 3).isEmpty())
        session.close()
        assertTrue(
            "session fermée : vide",
            session.superDefinition("file:///quelconque.kt", 3).isEmpty(),
        )
    }
}
