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
 * REPRODUCTION H3 (mission fiabilisation, étape A.2 — hypothèse
 * « Java mixte : classes imbriquées » du prompt, MESURÉE) : la
 * frontière réelle du modèle Java de [SourcesJava], telle que la
 * mesure la révèle (le sondeur a confronté chaque niveau au moteur) :
 *
 *  - NIVEAU 2 (`Aquarium.Bac`) : RÉSOUT — l'imbriquée est extraite
 *    comme DÉCLARATION MEMBRE du conteneur (`PROPRIETE:Bac`,
 *    conteneur `com.ex.app.Aquarium`, voie 4 de la résolution) —
 *    hypothèse INFIRMÉE à ce niveau (aucun faux positif) ; le genre
 *    approximatif (PROPRIETE plutôt que type imbriqué) ne dessert pas
 *    le diagnostic, il borne la complétion/navigation (écart consigné
 *    dans le rapport) ;
 *  - NIVEAU 3 (`Aquarium.Bac.Couloir` en position de TYPE) : CORRIGÉ
 *    en étape B (rapport §7.5) — [SourcesJava] extrait les types
 *    IMBRIQUÉS en RÉCURSIF BORNÉ (chaque imbriquée = DÉCLARATION à
 *    genre exact, conteneur = FQCN du parent, membres compris) :
 *    « Couloir » est MUET ;
 *  - NIVEAU 3 en position d'EXPRESSION (callee) : MUET — la garde
 *    sélecteur (récepteur `Aquarium.Bac` non typable → INCONNU, ADR
 *    0223) couvre ce cas ;
 *  - MEMBRE de l'imbriquée (`b.capacite`) : MUET — résolu par le
 *    membre extrait du corps de l'imbriquée (et sous-rapport D3
 *    au-delà de la borne de récursion, jamais prouvable absent).
 *
 * Contrôles de non-régression : membres de niveau 1 (vague 2) et
 * entrées d'enum Java (vague 3).
 */
class ReproductionJavaImbriqueTest {
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
                            "imbrique:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Sème : conteneur premier niveau, imbriquée NIVEAU 2 (avec membre + NIVEAU 3), POJO, enum. */
    private fun semerSourcesJava(): File {
        val racine = dossierTemporaire.newFolder("src-java-imbrique")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "Aquarium.java").writeText(
            """
            package com.ex.app;

            public class Aquarium {

                public static class Bac {
                    public int capacite = 10;

                    public static class Couloir {
                        public int longueur = 3;
                    }
                }
            }
            """.trimIndent(),
        )
        File(paquet, "Poisson.java").writeText(
            """
            package com.ex.app;

            public class Poisson {

                public int taille() {
                    return 12;
                }
            }
            """.trimIndent(),
        )
        File(paquet, "CouleurJava.java").writeText(
            """
            package com.ex.app;

            public enum CouleurJava {
                ROUGE,
                BLEU
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

    @Test
    fun `imbriquee de niveau 2 - type, expression et import resoluent (hypothese infirmee)`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            import com.ex.app.Aquarium.Bac

            fun demo(bac: Aquarium.Bac): Int {
                val y: Bac? = null
                val z = Aquarium.Bac()
                return bac.capacite
            }
            """.trimIndent()
        val uri = "file:///imbrique-niveau2.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "niveau 2 : « Aquarium », « Bac » (type, expression, import) et « capacite » muets : $recus",
            recus.none {
                it.contains("« Aquarium »") || it.contains("« Bac »") || it.contains("« capacite »")
            },
        )
    }

    @Test
    fun `imbriquee de niveau 3 en type - extraction recursive, dernier segment muet`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(c: Aquarium.Bac.Couloir): Int = c.longueur
            """.trimIndent()
        val uri = "file:///imbrique-niveau3-type.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        // ÉTAPE B (H3 CORRIGÉE) : « Couloir » est déclarée dans Bac
        // (imbriquée de niveau 3) — l'extraction RÉCURSIVE BORNÉE de
        // [SourcesJava] émet chaque imbriquée comme DÉCLARATION (genre
        // exact, conteneur = FQCN du parent) : la voie 4 résout le
        // dernier segment du type qualifié → MUET, plus de faux
        // `kt.unresolved`.
        assertTrue(
            "l'imbriquée de niveau 3 « Couloir » (extraction récursive) doit être MUETTE : $recus",
            recus.none { it.contains("« Couloir »") },
        )
        assertTrue(
            "les segments AMONT (« Aquarium », « Bac ») restent muets (un seul segment faillible, E4) : $recus",
            recus.none { it.contains("« Aquarium »") || it.contains("« Bac »") },
        )
    }

    @Test
    fun `imbriquee de niveau 3 en expression - la garde selecteur couvre (muet)`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            val w = Aquarium.Bac.Couloir()
            """.trimIndent()
        val uri = "file:///imbrique-niveau3-expression.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "callee de niveau 3 : récepteur non typable → INCONNU → sélecteur muet (ADR 0223) : $recus",
            recus.none { it.contains("« Couloir »") },
        )
    }

    @Test
    fun `membre de niveau 1 - non-regression de la vague 2`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(p: Poisson): Int = p.taille()
            """.trimIndent()
        val uri = "file:///imbrique-niveau1.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "taille() (méthode de niveau 1, vague 2) et « Poisson » (type premier niveau) muets : $recus",
            recus.none { it.contains("taille") || it.contains("« Poisson »") },
        )
    }

    @Test
    fun `entree d enum java - silence d3, non-regression vague 3`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(): Int = CouleurJava.ROUGE.ordinal
            """.trimIndent()
        val uri = "file:///imbrique-enum.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "« CouleurJava » et « ROUGE » (enum : jamais sans héritage écrit → silence D3) muets : $recus",
            recus.none { it.contains("« CouleurJava »") || it.contains("« ROUGE »") },
        )
    }
}
