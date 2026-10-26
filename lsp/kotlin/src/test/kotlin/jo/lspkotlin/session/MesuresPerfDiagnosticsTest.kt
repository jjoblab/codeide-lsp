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
 * MESURES DE PERFORMANCE (mission fiabilisation, étape A.3) — temps
 * de REPARSE COMPLET et de DIAGNOSTICS sur un GROS fichier (≥ 1 500
 * lignes), l'étape D du prompt s'appuyant sur ces chiffres pour
 * décider du cache par déclaration.
 *
 * Le document est GÉNÉRÉ DÉTERMINISTEMENT (~1 680 lignes : 150
 * fonctions réelles — locales, appels stdlib, littéraux, chaînes) ;
 * la session porte le VRAI stdlib (index nourri, pas de racine de
 * scan : l'univers des documents OUVERTS est complet par contrat).
 *
 * Protocole : un passage À FROID (premier parse + premiers
 * diagnostics) puis CINQ passages À CHAUD (modifierDocument = reparse
 * intégral — TextDocumentSyncKind.Full — puis diagnostics complet,
 * recalcul intégral sans cache). Chiffres IMPRIMÉS pour le rapport
 * (état initial, HEAD v1.15.0, 2026-10-26) ; bornes de SANITÉ
 * volontairement larges (machine CI 2 cœurs / 4 Go : le test chasse
 * la régression pathologique, pas la latence).
 */
class MesuresPerfDiagnosticsTest {
    /** Vue factice VIDE : la mesure porte le moteur, pas le classpath hôte. */
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
                            "perf:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Document géant DÉTERMINISTE : 150 fonctions de ~11 lignes (~1 680 lignes). */
    private fun sourceGeante(): String =
        buildString {
            appendLine("package com.ex.perf")
            appendLine()
            for (indice in 1..NOMBRE_FONCTIONS) {
                appendLine("fun calcul$indice(base: Int): Int {")
                appendLine("    val montant = base * $indice + ${indice % 7}")
                appendLine("    val libelle = \"calcul-$indice\"")
                appendLine("    val liste = listOf(montant, base, ${indice % 13})")
                appendLine("    val trie = liste.map { it + 1 }.filter { it > ${indice % 5} }.sorted()")
                appendLine("    val total = trie.sum()")
                appendLine("    println(libelle)")
                appendLine("    val texte = libelle + \":\" + total")
                appendLine("    val profondeur = if (total > ${indice}) total - base else base - total")
                appendLine("    return profondeur + texte.length")
                appendLine("}")
                appendLine()
            }
        }

    @Test
    fun `gros fichier - reparse complet et diagnostics mesures (froid puis 5 passes chaudes)`() {
        val source = sourceGeante()
        val lignes = source.count { it == '\n' } + 1
        assertTrue("document géant (>= 1500 lignes) : $lignes", lignes >= 1500)
        val session = session()
        val uri = "file:///perf-gros.kt"
        // À FROID : premier parse intégral + premiers diagnostics.
        var debut = System.nanoTime()
        session.ouvrirDocument(uri, source)
        val reparseFroidMs = (System.nanoTime() - debut) / 1_000_000
        debut = System.nanoTime()
        val diagnostiques = session.diagnostics(uri)
        val diagnosticsFroidsMs = (System.nanoTime() - debut) / 1_000_000
        assertTrue(
            "document sain attendu (aucun non résolu) : ${diagnostiques.map { it.code }}",
            diagnostiques.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        // À CHAUD : 5 passes modifierDocument (reparse intégral, full
        // sync) + diagnostics (recalcul intégral, sans cache).
        val reprisesChaudes = mutableListOf<Long>()
        val diagnosticsChauds = mutableListOf<Long>()
        for (passe in 1..NOMBRE_PASSES_CHAUDES) {
            val texte = source.replaceFirst("calcul1(", "calcul1Passe$passe(")
            debut = System.nanoTime()
            session.modifierDocument(uri, texte)
            reprisesChaudes += (System.nanoTime() - debut) / 1_000_000
            debut = System.nanoTime()
            session.diagnostics(uri)
            diagnosticsChauds += (System.nanoTime() - debut) / 1_000_000
        }
        session.close()
        val reparseChaudMin = reprisesChaudes.min()
        val diagnosticsChaudMin = diagnosticsChauds.min()
        println(
            "=== MESURES PERF: lignes=$lignes fonctions=$NOMBRE_FONCTIONS " +
                "reparseFroid=${reparseFroidMs}ms repriseChaudeMin=${reparseChaudMin}ms " +
                "reprisesChaudes=${reprisesChaudes}ms " +
                "diagnosticsFroid=${diagnosticsFroidsMs}ms diagnosticsChaudMin=${diagnosticsChaudMin}ms " +
                "diagnosticsChauds=${diagnosticsChauds}ms",
        )
        // Bornes de SANITÉ (larges — machine CI 2 cœurs) : le test
        // chasse la régression pathologique, le rapport porte les
        // chiffres réels.
        assertTrue("reparse à froid borné (< 20 s) : ${reparseFroidMs}ms", reparseFroidMs < 20_000)
        assertTrue("reprise à chaud bornée (< 10 s) : ${reparseChaudMin}ms", reparseChaudMin < 10_000)
        assertTrue("diagnostics à chaud bornés (< 10 s) : ${diagnosticsChaudMin}ms", diagnosticsChaudMin < 10_000)
    }

    private companion object {
        private const val NOMBRE_FONCTIONS: Int = 150

        private const val NOMBRE_PASSES_CHAUDES: Int = 5
    }
}
