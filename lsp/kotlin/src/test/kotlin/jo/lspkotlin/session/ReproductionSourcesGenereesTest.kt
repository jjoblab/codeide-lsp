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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION H2 (mission fiabilisation, étape A.2 — hypothèse
 * « sources générées invisibles » du prompt) : la MARCHE du scan
 * EXCLUT le dossier `build` (exclusions ADR 0212 : sorties, caches —
 * le scan est le SOURCE). Or les sources GÉNÉRÉES d'un projet Android
 * réel vivent précisément dans l'arbre `build/generated` : KSP/Hilt
 * (`Hilt_` préfixé), Room (`_Impl` suffixé), DataBinding
 * (`BindingImpl` suffixé)… Un type
 * qui y est déclaré — et que le code source RÉFÉRENCE (le parent
 * `Hilt_MainActivity` d'un `@AndroidEntryPoint`, l'implémentation
 * Room instanciée par le runtime) — est INVISIBLE du scan : les voies
 * 4/5 de la résolution échouent et la référence produit un FAUX
 * `kt.unresolved`.
 *
 * CORRECTIF ÉTAPE B (rapport §7.4) : SILENCE sur les conventions de
 * nommage générées (`Hilt_`, `_Impl`, `BindingImpl` —
 * [DiagnosticsSemantiques.estNomGenere]) — l'option « racines
 * build/generated en lecture » est ÉCARTÉE : le scan est le SOURCE
 * (ADR 0212) et les racines générées viennent du TOOLING
 * (`EnsemblesSources.generes`) après un build. Le CONTRÔLE prouve
 * que le même fichier dans une racine NON exclue résout sans erreur.
 *
 * Les classes synthétiques D5 (R, BuildConfig, ViewBinding,
 * DataBinding) ne couvrent PAS les conventions KSP/Hilt/Room — c'est
 * précisément l'écart mesuré ici.
 */
class ReproductionSourcesGenereesTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

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
                            "generees:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /**
     * Sème le projet : la source RÉELLE (MainActivity.kt référence le
     * parent généré) + le fichier GÉNÉRÉ Hilt dans
     * `build/generated/...` (invisible du scan).
     */
    private fun semerProjet(): File {
        val racine = dossierTemporaire.newFolder("projet-generees")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "MainActivity.kt").writeText("package com.ex.app\n")
        val genere =
            File(racine, "build/generated/ksp/debug/java/com/ex/app")
        assertTrue(genere.mkdirs())
        File(genere, "Hilt_MainActivity.kt").writeText("package com.ex.app\n\nopen class Hilt_MainActivity\n")
        return racine
    }

    private fun nonResolues(
        session: SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    @Test
    fun `source generee dans build - le parent Hilt est muet (convention generee)`() {
        val session = session(listOf(semerProjet()))
        val source =
            """
            package com.ex.app

            class MainActivity : Hilt_MainActivity()
            """.trimIndent()
        val uri = "file:///generees-hilt.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        // ÉTAPE B (H2 CORRIGÉE) : « Hilt_MainActivity » est déclaré
        // dans build/generated (KSP) — le scan EXCLUT build/ (ADR 0212,
        // le scan est le SOURCE) et les racines générées viennent du
        // TOOLING après un build. Le préfixe `Hilt_` est une CONVENTION
        // GÉNÉRÉE : l'absence n'est PAS prouvable sans build → MUET
        // (D3 — [DiagnosticsSemantiques.estNomGenere]).
        assertTrue(
            "le parent généré « Hilt_MainActivity » (convention Hilt_) doit être MUET : $recus",
            recus.none { it.contains("« Hilt_MainActivity »") },
        )
    }

    @Test
    fun `controle - la meme classe dans une racine non exclue est muette`() {
        val racine = dossierTemporaire.newFolder("projet-generees-controle")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "Hilt_MainActivity.kt").writeText("package com.ex.app\n\nopen class Hilt_MainActivity\n")
        val session = session(listOf(racine))
        val source =
            """
            package com.ex.app

            class MainActivity : Hilt_MainActivity()
            """.trimIndent()
        val uri = "file:///generees-controle.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "la même classe dans une racine scannée : « Hilt_MainActivity » doit être muet : $recus",
            recus.none { it.contains("« Hilt_MainActivity »") },
        )
    }
}
