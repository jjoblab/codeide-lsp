package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EXTENSIONS À RÉCEPTEUR PARAMÈTRE DE TYPE (mission fiabilisation,
 * étape A.3 — mesure née du différentiel
 * [jo.lspkotlin.classpath.LecteurClassBrutDifferentielTest]) :
 * `let`/`also`/`apply`/`run`/`takeIf`/`takeUnless` sont déclarées
 * `fun <T> T.…(…)` — leur récepteur n'est PAS une classe, et
 * [DecodeurMetadonnees] les indexe donc comme appelables TOP-LEVEL du
 * paquet `kotlin` (récepteur null, sous-rapport assumé K4).
 *
 * Le diagnostic n'en fait pourtant PAS de faux positifs : la voie 6
 * (appelables EN PORTÉE, ADR 0203) résout le nom nu grâce à
 * l'import PAR DÉFAUT de `kotlin.*` — comportement mesuré en session
 * réelle (VRAI stdlib + vue servant les membres du récepteur : la
 * sélection par types ADR 0223 ne prouve JAMAIS l'absence d'une
 * extension homonyme à récepteur T, elle résout avant). Ce test
 * VERROUILLE le mécanisme : une régression de la voie 6 ou des
 * imports par défaut y serait immédiatement visible (six faux
 * positifs sur les idiomes les plus courants de Kotlin).
 *
 * CONTRÔLE : un membre réellement absent du récepteur lisible reste
 * signalé (ABSENCE_PROUVEE, ADR 0223) — le test ne valide pas un
 * simple « tout muet ».
 */
class ReproductionExtensionsRecepteurNulTest {
    /** Vue factice servant les membres DÉCLARÉS d'une classe lisible. */
    private val vueBoite: VueClasspathKotlin =
        object : VueClasspathKotlin {
            override fun membres(fqcn: String): MembresTypeKotlin? =
                when (fqcn) {
                    "exo.lib.Boite" ->
                        MembresTypeKotlin(
                            drapeauxClasse = 0x0021,
                            champs = listOf(MembreJvmKotlin(0x0002, "contenu", "Ljava/lang/String;")),
                            methodes = listOf(MembreJvmKotlin(0x0001, "ouvrir", "()I")),
                        )
                    else -> null
                }

            override fun octetsClasse(fqcn: String): ByteArray? = null

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
                if (prefixe == "Boite") {
                    listOf(NomTypeKotlin("Boite", listOf("exo.lib.Boite")))
                } else {
                    emptyList()
                }

            override fun typesDansPaquet(paquet: String): List<String> =
                if (paquet == "exo.lib") listOf("exo.lib.Boite") else emptyList()

            override fun estPaquet(nom: String): Boolean = nom == "exo.lib"

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
                            "recepteur-nul:stdlib",
                        ),
                    ),
                vue = vueBoite,
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
    fun `let also apply run takeIf takeUnless - muets malgre un recepteur classpath lisible`() {
        val session = session()
        val source =
            """
            import exo.lib.Boite

            fun demo(b: Boite) {
                val a = b.contenu
                val l = b.let { it }
                val al = b.also { }
                val ap = b.apply { }
                val r = b.run { 1 }
                val t = b.takeIf { true }
            }
            """.trimIndent()
        val uri = "file:///recepteur-nul.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "contenu (membre déclaré) et let/also/apply/run/takeIf (récepteur T, voie 6 + kotlin.*) muets : $recus",
            recus.none {
                it.contains("« contenu »") || it.contains("« let »") || it.contains("« also »") ||
                    it.contains("« apply »") || it.contains("« run »") || it.contains("« takeIf »")
            },
        )
    }

    @Test
    fun `controle - un membre reellement absent du recepteur lisible reste signale`() {
        val session = session()
        val source =
            """
            import exo.lib.Boite

            fun demo(b: Boite): Int = b.absenteDuBytecode
            """.trimIndent()
        val uri = "file:///recepteur-nul-controle.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "vrai négatif : « absenteDuBytecode » (absent d'une classe LISIBLE) doit rester signalé : $recus",
            recus.any { it.contains("« absenteDuBytecode »") },
        )
    }
}
