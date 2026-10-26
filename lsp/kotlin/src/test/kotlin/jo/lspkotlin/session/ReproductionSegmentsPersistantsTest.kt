package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION H4 (mission fiabilisation, étape A.2 — hypothèse
 * « segment d'index partiel / périmé » du prompt) : l'index des
 * appelables persiste ses segments sous `dossierIndex`
 * (`kotlin-appelables/<sha256>.kidx`), relus PAR EMPREINTE. Les scénarios
 * suspectés — segment CORROMPU, segment de VERSION périmée (le format
 * porte magie + version), jar DISPARU — sont mesurés ici en session
 * complète.
 *
 * Comportement attendu (et mesuré) : le segment n'est JAMAIS une
 * autorité — corrompu ou périmé, il est REJETÉ puis re-décodé depuis
 * le jar (auto-guérison ADR 0111 §4) ; un jar ABSENT rend l'entrée
 * ÉCARTÉE, le snapshot ne couvre PLUS les empreintes et la garde ADR
 * 0211 (`indexSiPret` ⇒ `null`) TAIT tout `kt.unresolved` — jamais de
 * faux positif sur un index qui ne prouve rien.
 *
 * Vrai négatif de contrôle : `PI` (kotlin.math, PAS un paquet par
 * défaut) DOIT être signalé quand l'index est portant — preuve que les
 * tests ne valident pas un simple « tout muet ».
 */
class ReproductionSegmentsPersistantsTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    private fun classpathStdlib(dossierIndex: File?): ClasspathKotlin =
        ClasspathKotlin(
            entrees =
                listOf(
                    EntreeClasspathKotlin(
                        KindEntreeKotlin.JAR,
                        jarStdlib().path,
                        EMPREINTE,
                    ),
                ),
            vue = null,
            racinesSources = emptyList(),
            dossierIndex = dossierIndex,
        )

    /** Session directe (miroir A211) — le dossierIndex vit dans le CLASSPATH, pas dans la session. */
    private fun session(): SessionLangageKotlinParse =
        SessionLangageKotlinParse(
            ParseurKotlin(),
            IndexAppelablesKotlin(),
            ScanProjet(ParseurKotlin()),
        )

    /** Le segment persisté de l'empreinte de test, s'il existe. */
    private fun segmentDe(dossierIndex: File): File? =
        dossierIndex.walkTopDown()
            .filter { it.isFile && it.name == IndexAppelablesKotlin().nomSegment(EMPREINTE) }
            .firstOrNull()

    /** Diagnostics « non résolue » de la sonde : println (en portée) + PI (vrai négatif). */
    private fun nonResolues(
        session: SessionLangageKotlinParse,
        uri: String,
        source: String,
    ): List<String> {
        session.ouvrirDocument(uri, source)
        return session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }
    }

    @Test
    fun `segment corrompu - auto-guerison, l index reste portant`() {
        val dossierIndex = dossierTemporaire.newFolder("index-corrompu")
        // 1. Premier passage : le segment est écrit sur disque.
        val premiere = session()
        premiere.definirClasspath(classpathStdlib(dossierIndex))
        val recus1 = nonResolues(premiere, "file:///h4-premiere.kt", SOURCE_SONDE)
        assertTrue("témoin initial : PI signalé (index portant) : $recus1", recus1.any { it.contains("« PI »") })
        assertTrue("témoin initial : println muet : $recus1", recus1.none { it.contains("« println »") })
        premiere.close()
        val segment = segmentDe(dossierIndex)
        assertTrue("le segment doit exister après le premier passage", segment != null && segment.isFile)
        // 2. CORRUPTION : des octets quelconques remplacent le segment.
        segment!!.writeBytes("CORROMPU-PAS-UN-SEGMENT".toByteArray())
        // 3. Session NEUVE, même empreinte : le segment est rejeté puis
        //    re-décodé depuis le jar — l'index reste PORTANT.
        val seconde = session()
        seconde.definirClasspath(classpathStdlib(dossierIndex))
        val recus2 = nonResolues(seconde, "file:///h4-seconde.kt", SOURCE_SONDE)
        seconde.close()
        assertTrue(
            "auto-guérison : PI reste signalé (l'index a été re-décodé, pas mué) : $recus2",
            recus2.any { it.contains("« PI »") },
        )
        assertTrue(
            "auto-guérison : println reste muet (aucun faux positif) : $recus2",
            recus2.none { it.contains("« println »") },
        )
    }

    @Test
    fun `segment de version perimee - rejete puis re-decode, pas de faux positif`() {
        val dossierIndex = dossierTemporaire.newFolder("index-perime")
        val premiere = session()
        premiere.definirClasspath(classpathStdlib(dossierIndex))
        nonResolues(premiere, "file:///h4-temoin.kt", SOURCE_SONDE)
        premiere.close()
        val segment = segmentDe(dossierIndex)
        assertTrue(segment != null && segment.isFile)
        // Version = 2e entier du format (magie, VERSION, empreinte…) :
        // on la FORCE à 0 — un segment d'une AUTRE ère du format, CRC
        // par ailleurs cohérent, exactement la montée de version
        // (l.380 de IndexAppelablesKotlin : version ≠ VERSION ⇒ rejet).
        val octets = segment!!.readBytes()
        octets[4] = 0
        octets[5] = 0
        octets[6] = 0
        octets[7] = 0
        segment.writeBytes(octets)
        val seconde = session()
        seconde.definirClasspath(classpathStdlib(dossierIndex))
        val recus = nonResolues(seconde, "file:///h4-perime.kt", SOURCE_SONDE)
        seconde.close()
        assertTrue(
            "segment périmé rejeté et re-décodé : PI reste signalé : $recus",
            recus.any { it.contains("« PI »") },
        )
        assertTrue(
            "segment périmé : println reste muet (aucun faux positif) : $recus",
            recus.none { it.contains("« println »") },
        )
    }

    @Test
    fun `jar absent - l entree est ecartee et la garde tait tout kt unresolved`() {
        val classpathCasse =
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            "/chemin/inexistant.jar",
                            "h4:absent",
                        ),
                    ),
                vue = null,
                racinesSources = emptyList(),
                dossierIndex = null,
            )
        val session = session()
        session.definirClasspath(classpathCasse)
        // L'entrée ÉCARTÉE ne couvre PAS les empreintes : la garde ADR
        // 0211 rend l'index MUET — aucun `kt.unresolved`, même pour un
        // vrai négatif (PI). C'est le contrat « index froid = silence ».
        val recus = nonResolues(session, "file:///h4-absent.kt", SOURCE_SONDE)
        session.close()
        assertTrue(
            "jar absent : la garde tient TOUT (même le vrai négatif PI est muet) : $recus",
            recus.none { it.contains("« PI »") || it.contains("« println »") },
        )
    }

    private companion object {
        private const val EMPREINTE: String = "h4:stdlib"

        /** println (kotlin.io, en portée) doit rester MUET ; PI (kotlin.math, hors portée)
         * est le VRAI NÉGATIF de contrôle. */
        private val SOURCE_SONDE: String =
            """
            fun demo() {
                println(1)
                val pi = PI
            }
            """.trimIndent()
    }
}
