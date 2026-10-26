package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCTION (mission fiabilisation, étape A.3 — découverte du test
 * différentiel [jo.lspkotlin.classpath.LecteurClassBrutDifferentielTest])
 * : les FONCTIONS BUILTIN `arrayOf` et `intArrayOf` du stdlib ne sont
 * PAS émises par [DecodeurMetadonnees] (le nom manque de l'index des
 * appelables — seul écart constaté sur la liste des fonctions usuelles)
 * → la voie 6 de la résolution (appelables de premier niveau EN PORTÉE)
 * échoue et l'écriture `arrayOf(…)` / `intArrayOf(…)` produit un FAUX
 * `kt.unresolved` en session réelle avec le VRAI jar stdlib.
 *
 * ⚠️ COMPORTEMENT ACTUEL DOCUMENTÉ : le correctif de l'étape B (voire
 * une entrée « builtins réputées » du moteur, miroir
 * [ResolutionKotlin.IMPORTS_DEFAUT] côté noms) INVERSERa l'assertion.
 *
 * CONTRÔLES : `listOf`, `emptyArray` et `println` (lus par le
 * décodeur) restent muets — le faux positif vient bien de l'index,
 * pas du harnais.
 */
class ReproductionAppelablesBuiltinTest {
    /** Vue factice VIDE : le stdlib est le seul fournisseur d'appelables. */
    private val vueVide: VueClasspathKotlin =
        object : VueClasspathKotlin {
            override fun membres(fqcn: String): MembresTypeKotlin? = null

            override fun octetsClasse(fqcn: String): ByteArray? = null

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> = emptyList()

            override fun typesDansPaquet(paquet: String): List<String> = emptyList()

            override fun estPaquet(nom: String): Boolean = false

            override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
        }

    private fun session(): SessionLangageKotlin {
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
                            "builtin:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    private fun nonResolues(
        session: SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    @Test
    fun `builtins arrayOf et intArrayOf - faux positifs documentes`() {
        val session = session()
        val source =
            """
            fun demo() {
                val x = arrayOf(1, 2)
                val y = intArrayOf(1, 2)
            }
            """.trimIndent()
        val uri = "file:///builtin-tableaux.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        // ⚠️ COMPORTEMENT ACTUEL DOCUMENTÉ : les builtins de tableaux ne
        // vivent pas dans l'index des appelables → faux `kt.unresolved`.
        assertTrue(
            "FAUX POSITIFS attendus : « arrayOf » et « intArrayOf » (absents de l'index) sont signalés : $recus",
            recus.any { it.contains("« arrayOf »") } && recus.any { it.contains("« intArrayOf »") },
        )
    }

    @Test
    fun `controles - listOf, emptyArray et println restent muets`() {
        val session = session()
        val source =
            """
            fun demo() {
                val a = listOf(1, 2)
                val b = emptyArray<String>()
                println(a)
            }
            """.trimIndent()
        val uri = "file:///builtin-controles.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "listOf, emptyArray, println (émis par le décodeur) muets : $recus",
            recus.none {
                it.contains("« listOf »") || it.contains("« emptyArray »") || it.contains("« println »")
            },
        )
    }
}
