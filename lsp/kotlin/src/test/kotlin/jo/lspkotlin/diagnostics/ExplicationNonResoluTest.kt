package jo.lspkotlin.diagnostics

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * JOURNAL « EXPLAIN » DES `kt.unresolved` ÉMIS (mission fiabilisation,
 * étape A.1 — instrumentation) : chaque émission est consignée en
 * niveau FINE — forme syntaxique de la référence, issue de la
 * sélection par types complets (ADR 0223, la « voie de preuve » qui a
 * conclu à l'absence), récepteur TYPÉ et état de l'univers des preuves
 * (scan projet, index portant, régime script).
 *
 * Ce test EST la « commande qui rend le journal » : il branche un
 * [Handler] JUL sur [ExplicationsNonResolus], joue un document porteur
 * d'un vrai négatif NU et d'un vrai négatif QUALIFIÉ, et vérifie le
 * rendu greppable (`clé=valeur`) ligne par ligne. Les références
 * RÉSOLUES ne journalisent RIEN (le journal ne parle que des
 * émissions), et le niveau INFO de production ne rend rien du tout.
 *
 * Le classpath porte le VRAI jar stdlib (l'index doit CONNAÎTRE des
 * entrées pour prouver l'absence — ADR 0223) et les racines sources
 * nourrissent le scan projet, miroir du patron PreuveAbsenceProjetTest.
 */
class ExplicationNonResoluTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    // ── Capture du journal FINE de l'instrumentation ──────────────────

    private val journal: Logger = Logger.getLogger(ExplicationsNonResolus::class.java.name)
    private val lignesJournal: MutableList<String> = mutableListOf()
    private var ancienNiveau: Level? = null
    private val ecouteur: Handler =
        object : Handler() {
            override fun publish(enregistrement: LogRecord) {
                lignesJournal += enregistrement.message
            }

            override fun flush() {
                // Rien à vider — la capture vit en mémoire du test.
            }

            override fun close() {
                // Rien à fermer — le test retire l'écouteur lui-même.
            }
        }

    @Before
    public fun ecouter() {
        ancienNiveau = journal.level
        journal.level = Level.FINE
        journal.addHandler(ecouteur)
        lignesJournal.clear()
    }

    @After
    public fun taire() {
        journal.removeHandler(ecouteur)
        journal.level = ancienNiveau
        lignesJournal.clear()
    }

    // ── Session nourrie du stdlib réel + racines du scan ──────────────

    /** Vue factice VIDE : aucun type classpath n'entre en jeu. */
    private val vueVide: VueClasspathKotlin =
        object : VueClasspathKotlin {
            override fun membres(fqcn: String): MembresTypeKotlin? = null

            override fun octetsClasse(fqcn: String): ByteArray? = null

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> = emptyList()

            override fun typesDansPaquet(paquet: String): List<String> = emptyList()

            override fun estPaquet(nom: String): Boolean = false

            override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
        }

    private fun session(racines: List<File>): SessionLangageKotlin {
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
                            "explain:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Sème une classe projet SANS héritage écrit (preuve d'absence fondée). */
    private fun semerSources(): File {
        val racine = dossierTemporaire.newFolder("src-explain")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "Boite.kt").writeText(
            """
            package com.ex.app

            class Boite(var contenu: String) {
                fun ouvrir(): Int = contenu.length
            }
            """.trimIndent(),
        )
        return racine
    }

    private fun nonResolues(
        session: SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    private fun ligneAttendue(
        source: String,
        marqueur: String,
    ): Int = 1 + source.substring(0, source.indexOf(marqueur)).count { it == '\n' }

    // ── Le journal rend chaque émission ────────────────────────────────

    @Test
    fun `nom nu - le journal rend la forme, l issue INCONNU et l etat des preuves`() {
        val session = session(listOf(semerSources()))
        val source =
            """
            package com.ex.app

            fun demo() {
                val precedent = grincher(1)
            }
            """.trimIndent()
        val uri = "file:///explain-nom-nu.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue("grincher (vrai négatif NU) doit être signalé : $recus", recus.any { it.contains("grincher") })
        val ligne = lignesJournal.filter { it.contains("« grincher »") }
        assertTrue(
            "le journal doit contenir UNE ligne pour « grincher » : $lignesJournal",
            ligne.size == 1,
        )
        assertTrue("forme du nom nu : $ligne", ligne.single().contains("forme=nom-nu"))
        assertTrue(
            "issue de la sélection (voie normale — pas de récepteur) : $ligne",
            ligne.single().contains("selection=INCONNU"),
        )
        assertTrue(
            "récepteur absent d'un nom nu : $ligne",
            ligne.single().contains("recepteur=aucun"),
        )
        assertTrue(
            "l'univers des preuves doit être rendu (scan complet, index portant, pas un script) : $ligne",
            ligne.single().contains("scan=complet") &&
                ligne.single().contains("index=portant") &&
                ligne.single().contains("script=non"),
        )
        assertTrue(
            "position ligne attendue : $ligne",
            ligne.single().contains("${ligneAttendue(source, "grincher")}:"),
        )
    }

    @Test
    fun `selecteur qualifie - le journal rend l ABSENCE_PROUVEE et le recepteur type`() {
        val session = session(listOf(semerSources()))
        val source =
            """
            package com.ex.app

            fun demo(b: Boite) {
                val x = b.contenu
                val u = b.champInconnu
            }
            """.trimIndent()
        val uri = "file:///explain-selecteur.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue("contenu (déclaré) muet : $recus", recus.none { it.contains("« contenu »") })
        assertTrue(
            "champInconnu (membre inexistant d'une classe sans héritage, scan complet) doit être signalé : $recus",
            recus.any { it.contains("champInconnu") },
        )
        val ligne = lignesJournal.filter { it.contains("« champInconnu »") }
        assertTrue(
            "le journal doit contenir UNE ligne pour « champInconnu » : $lignesJournal",
            ligne.size == 1,
        )
        assertTrue(
            "voie de preuve = absence prouvée par types complets : $ligne",
            ligne.single().contains("selection=ABSENCE_PROUVEE"),
        )
        assertTrue(
            "forme du sélecteur à absence prouvée : $ligne",
            ligne.single().contains("forme=selecteur-qualifie-absence-prouvee"),
        )
        assertTrue(
            "le récepteur TYPÉ doit être rendu : $ligne",
            ligne.single().contains("recepteur=com.ex.app.Boite"),
        )
        assertTrue(
            "l'univers des preuves doit être rendu : $ligne",
            ligne.single().contains("scan=complet") && ligne.single().contains("index=portant"),
        )
    }

    @Test
    fun `references resolues - le journal ne dit rien`() {
        val session = session(listOf(semerSources()))
        val source =
            """
            package com.ex.app

            fun demo(b: Boite) {
                val x = b.contenu
                val y = b.ouvrir()
                val t = b.toString()
            }
            """.trimIndent()
        val uri = "file:///explain-silence.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue("document sain : aucun non résolu, $recus", recus.isEmpty())
        assertTrue(
            "aucune référence résolue ne journalise (le journal ne parle que des émissions) : $lignesJournal",
            lignesJournal.isEmpty(),
        )
    }

    @Test
    fun `journal eteint par defaut - aucun rendu sans ecouteur FINE`() {
        // La production ne branche AUCUN handler et le niveau par défaut
        // (INFO) filtre FINE : l'enregistrement paresseux ne construit
        // RIEN. Prouvé en baissant le niveau sous FINE pendant l'émission.
        journal.level = Level.INFO
        val session = session(listOf(semerSources()))
        val source =
            """
            package com.ex.app

            fun demo() {
                val precedent = grincher(1)
            }
            """.trimIndent()
        val uri = "file:///explain-eteint.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(recus.any { it.contains("grincher") })
        assertTrue(
            "niveau INFO (production) : le journal ne rend RIEN : $lignesJournal",
            lignesJournal.isEmpty(),
        )
    }
}
