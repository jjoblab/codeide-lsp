package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION H1 (mission fiabilisation, étape A.2 — hypothèse
 * « troncation à [BORN_FICHIERS] = 2000 » du prompt, constaté dans le
 * code puis vérifié ici) : la MARCHE du scan est bornée ; au-delà de
 * la borne, les fichiers sont silencieusement IGNORÉS — ils ne sont
 * même pas comptés dans les statistiques du passage (`total` borné,
 * indistinguable d'un petit projet sain) — et le drapeau `pret` est
 * posé quand même (ADR 0212 : « un résultat VIDE ou PARTIEL est un
 * état abouti »). Conséquence : [ScanProjet.estUniversConnu] répond
 * VRAI sur un univers TRONQUÉ, et la preuve d'absence sur les types
 * du PROJET n'est PAS fondée.
 *
 * Le FAUX POSITIF est reproduit en session complète : une référence à
 * un type déclaré DERRIÈRE la borne produit un `kt.unresolved` — les
 * voies 4/5 de la résolution (déclarations du PROJET) ne sont gardées
 * par la complétude du scan QUE côté [DiagnosticsSemantiques]
 * `selectionProjet` (vague 3), PAS dans la cascade `estResolu`.
 *
 * ⚠️ Les tests « faux positif » documentent le comportement ACTUEL :
 * le correctif de l'étape B (refléter la troncation dans
 * `estUniversConnu`) INVERSERA leurs assertions — ils sont les tests
 * de reproduction du futur correctif.
 *
 * La borne est injectée par la couture de test (3 fichiers) : le
 * parcours est DÉTERMINISTE (tri par nom), la cible « D.kt » sort
 * toujours derrière A/B/C.
 */
class ReproductionTroncationScanTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /**
     * Sème com/ex/app : trois fichiers de remplissage PUIS la cible —
     * l'ordre alphabétique du parcours garantit que « D.kt » (la
     * CIBLE) est TOUJOURS derrière la borne.
     */
    private fun semerProjet(): File {
        val racine = dossierTemporaire.newFolder("src-troncation")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "A.kt").writeText("package com.ex.app\n\nclass PresentationA\n")
        File(paquet, "B.kt").writeText("package com.ex.app\n\nclass PresentationB\n")
        File(paquet, "C.kt").writeText("package com.ex.app\n\nclass PresentationC\n")
        File(paquet, "D.kt").writeText("package com.ex.app\n\nclass Cible\n")
        return racine
    }

    /** Session complète (index + scan NEUFS) sur un projet TRONQUÉ à 3 fichiers. */
    private fun sessionTronquee(
        racine: File,
        bornFichiers: Int,
    ): SessionLangageKotlinParse {
        val scan = ScanProjet(ParseurKotlin(), bornFichiers)
        val session = SessionLangageKotlinParse(ParseurKotlin(), IndexAppelablesKotlin(), scan)
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            jarStdlib().path,
                            "troncation:stdlib",
                        ),
                    ),
                vue = null,
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        return session
    }

    @Test
    fun `marche bornee - le fichier au-dela de la borne est invisible et estUniversConnu reste vrai`() {
        val scan = ScanProjet(ParseurKotlin(), bornFichiers = 3)
        scan.definirRacines(listOf(semerProjet()))
        scan.attendrePassageCourant()
        val noms = scan.declarations().map { it.nom }.toSet()
        assertTrue(
            "A/B/C scannés (devant la borne) : $noms",
            setOf("PresentationA", "PresentationB", "PresentationC").all { it in noms },
        )
        assertTrue(
            "la CIBLE (D.kt, derrière la borne) est absente du scan : $noms",
            "Cible" !in noms,
        )
        val stats = scan.statsDernierPassage()
        // LACUNE n° 1 : la MARCHE elle-même est bornée — le 4e fichier
        // n'est même pas compté, `total` = 3 : la troncation est
        // INDÉTECTABLE dans les statistiques.
        assertEquals("faits", 3, stats.faits)
        assertEquals("total BORNÉ à la marche — la troncation est invisible", 3, stats.total)
        // LACUNE n° 2 (H1 CONFIRMÉE) : `pret` posé sur un passage
        // TRONQUÉ — l'univers est déclaré CONNU.
        assertTrue(
            "estUniversConnu() répond VRAI sur un univers TRONQUÉ : la preuve d'absence projet n'est pas fondée",
            scan.estUniversConnu(),
        )
    }

    @Test
    fun `faux positif - la reference au type du fichier non scanne est signalee`() {
        val session = sessionTronquee(semerProjet(), bornFichiers = 3)
        val source = "package com.ex.app\n\nval essai: Cible? = null\n"
        val uri = "file:///troncation-cible.kt"
        session.ouvrirDocument(uri, source)
        val recus =
            session.diagnostics(uri)
                .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
                .map { it.message }
        session.close()
        // ⚠️ COMPORTEMENT ACTUEL DOCUMENTÉ (H1 confirmée) : « Cible »
        // est signalé non résolu alors que le type est DÉCLARÉ dans le
        // projet (D.kt, derrière la borne). Le correctif étape B
        // (estUniversConnu reflète la troncation) INVERSERA cette
        // assertion.
        assertTrue(
            "FAUX POSITIF attendu : « Cible » déclaré dans D.kt (derrière la borne) est signalé : $recus",
            recus.any { it.contains("« Cible »") },
        )
    }

    @Test
    fun `controle - sans troncation, la meme reference est muette`() {
        val session = sessionTronquee(semerProjet(), bornFichiers = 4)
        val source = "package com.ex.app\n\nval essai: Cible? = null\n"
        val uri = "file:///troncation-controle.kt"
        session.ouvrirDocument(uri, source)
        val recus =
            session.diagnostics(uri)
                .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
                .map { it.message }
        session.close()
        // CONTRÔLE : la CIBLE scannée (borne 4) — aucune erreur. Prouve
        // que le faux positif ci-dessus vient de la TRONCATION, pas du
        // harnais.
        assertTrue(
            "la CIBLE scannée : « Cible » doit être muet : $recus",
            recus.none { it.contains("« Cible »") },
        )
    }
}
