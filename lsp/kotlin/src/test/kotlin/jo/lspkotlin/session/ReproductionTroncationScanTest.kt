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
 * H1 — TRONCATION DU SCAN (mission fiabilisation, étape A.2 → CORRECTIF
 * étape B, rapport faux positifs 2026-10-26 §7.1) : la MARCHE du scan
 * est bornée à [ScanProjet.BORN_FICHIERS] ; au-delà de la borne, les
 * fichiers sont IGNORÉS du parse. L'étape B corrige les DEUX lacunes
 * mesurées en A.2 :
 *
 *  - la MARCHE COMPTE au-delà de la borne
 *    ([ScanProjet.statsDernierPassage] rend le total RÉEL des fichiers
 *    éligibles — la troncation est OBSERVABLE côté bilan ADR 0213) ;
 *  - le drapeau `pret` reste posé sur un passage tronqué (ADR 0212 :
 *    fraîcheur du snapshot) MAIS [ScanProjet.estUniversConnu] rend
 *    FAUX et la CASCADE de noms ([ResolutionReferences.estResolu])
 *    coupe la preuve d'absence des voies 4/5 (miroir de la garde
 *    `scanProjetComplet` de `selectionProjet`, vague 3) : une
 *    référence à un type déclaré derrière la borne est MUETTE — plus
 *    de faux `kt.unresolved`.
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
    fun `marche bornee - le fichier au-dela de la borne est compte et estUniversConnu rend faux`() {
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
        // Étape B, lacune n° 1 CORRIGÉE : la MARCHE compte AU-DELÀ de
        // la borne — `total` = 4 (le total RÉEL des fichiers
        // éligibles), la troncation est OBSERVABLE (faits 3 < total 4),
        // plus de petit projet sain déguisé.
        assertEquals("faits", 3, stats.faits)
        assertEquals("total RÉEL (la marche compte au-delà de la borne)", 4, stats.total)
        // Étape B, lacune n° 2 CORRIGÉE (H1) : `pret` reste posé
        // (fraîcheur ADR 0212) mais l'univers TRONQUÉ n'est pas CONNU
        // — la preuve d'absence projet est coupée.
        assertTrue(
            "estUniversConnu() rend FAUX sur un univers TRONQUÉ : la preuve d'absence projet est coupée",
            !scan.estUniversConnu(),
        )
    }

    @Test
    fun `correction - la reference au type du fichier non scanne est muette (absence non prouvable)`() {
        val session = sessionTronquee(semerProjet(), bornFichiers = 3)
        val source = "package com.ex.app\n\nval essai: Cible? = null\n"
        val uri = "file:///troncation-cible.kt"
        session.ouvrirDocument(uri, source)
        val recus =
            session.diagnostics(uri)
                .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
                .map { it.message }
        session.close()
        // ÉTAPE B (H1 CORRIGÉE) : « Cible » est déclaré dans D.kt,
        // DERRIÈRE la borne — l'univers est TRONQUÉ, les voies 4/5 ne
        // peuvent prouver NI résolution NI absence : la référence est
        // MUETTE (D3 — silence, jamais de faux positif).
        assertTrue(
            "Univers tronqué : « Cible » (derrière la borne) doit être MUET — absence non prouvable : $recus",
            recus.none { it.contains("« Cible »") },
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
        // que le silence du test de correction ci-dessus vient de la
        // GARDE d'univers, pas d'un mutisme du harnais.
        assertTrue(
            "la CIBLE scannée : « Cible » doit être muet : $recus",
            recus.none { it.contains("« Cible »") },
        )
    }
}
