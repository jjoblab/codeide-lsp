package jo.lspxml.corpus

import jo.lspxml.parse.ParseurXml
import jo.lspxml.rules.GenreDocument
import jo.lspxml.rules.ReglesStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Suite corpus LayoutLib (XE1, LSP_XML.md §7 — « les 50 layouts de
 * validation deviennent fixtures partagées ») : TOUT fichier `.xml` du
 * dépôt frère LayoutLib est analysé par le parseur tolérant —
 * - AUCUNE exception (tolérance X1 — le parseur ne lève jamais) ;
 * - les fichiers SAINS ne produisent AUCUN recouvrement de parseur
 *   (balise non fermée, fermante incohérente…) : le corpus est la
 *   preuve du REJET ZÉRO sur du XML réel d'Android ;
 * - les règles de structure tournent sur l'arbre (garde anti-crash ;
 *   les fixtures de LayoutLib portent des layouts que aapt accepte —
 *   les diagnostics RÈGLES, eux, peuvent exister et sont seulement
 *   comptés, jamais échoués : ce corpus éprouve le PARSEUR).
 *
 * Auto-skip honnête (patron PariteCorpusDepotTest) : dépôt frère
 * absent (CI sans LayoutLib) → la suite se saute EXPLICITEMENT ;
 * `-Dcorpus.racine=…` force la racine.
 */
class CorpusLayoutLibTest {
    private val parseur = ParseurXml()

    /**
     * Exemption detekt ciblée (règle 16) : ReturnCount — remontée par
     * niveaux avec sortie ANTICIPÉE au frère trouvé, un return unique
     * noierait la convention dossier.
     */
    @Suppress("ReturnCount")
    private fun racineCorpus(): File? {
        System.getProperty("corpus.racine")?.let { return File(it) }
        // Remontée depuis le répertoire de travail du test (lsp/xml)
        // jusqu'au frère LayoutLib (convention sandbox/CI, §7).
        var courant: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val dossier = courant ?: return null
            val parent = dossier.parentFile ?: return null
            val frere = File(parent, "LayoutLib")
            if (frere.isDirectory) return frere
            courant = parent
        }
        return null
    }

    private fun fichiersCorpus(racine: File): List<File> {
        val exclus = setOf("build", ".git", ".gradle")
        val resultat = mutableListOf<File>()
        fun parcourir(dossier: File) {
            val enfants = dossier.listFiles() ?: return
            for (enfant in enfants.sortedBy { it.name }) {
                when {
                    enfant.isDirectory -> if (enfant.name !in exclus) parcourir(enfant)
                    enfant.isFile && enfant.extension.equals("xml", ignoreCase = true) -> resultat.add(enfant)
                }
            }
        }
        parcourir(racine)
        return resultat.sortedBy { it.relativeTo(racine).path }
    }

    @Test
    fun `tout le corpus LayoutLib — zero recouvrement de parseur`() {
        val racine = racineCorpus()
        assumeTrue("dépôt LayoutLib absent — suite sautée honnêtement", racine != null)
        val fichiers = fichiersCorpus(racine!!)
        assumeTrue("corpus vide — suite sautée honnêtement", fichiers.isNotEmpty())
        assertTrue("corpus significatif (50+ fichiers), trouvé : ${fichiers.size}", fichiers.size >= 50)

        var analyses = 0
        val recouvrements = mutableListOf<String>()
        for (fichier in fichiers) {
            val texte = fichier.readText()
            // Tolérance X1 : l'analyse ne lève JAMAIS — aucune try
            // nécessaire, une exception est un ÉCHEC du test.
            val document = parseur.analyser(texte)
            analyses += 1
            document.recuperations.forEach { recouvrement ->
                recouvrements.add("${fichier.name} : ${recouvrement.code} — ${recouvrement.message}")
            }
            // Les règles tournent (garde anti-crash sur tout le corpus).
            ReglesStructure.diagnostiquer(document, GenreDocument.depuisUri(fichier.path))
        }
        assertEquals(
            "le corpus LayoutLib est du XML VALIDE — recouvrements inattendus :\n" +
                recouvrements.joinToString("\n"),
            0,
            recouvrements.size,
        )
        assertTrue("corpus analysé intégralement : $analyses", analyses == fichiers.size)
    }

    @Test
    fun `budget de re-parse — corpus integral sous la seconde`() {
        val racine = racineCorpus()
        assumeTrue("dépôt LayoutLib absent — suite sautée honnêtement", racine != null)
        val fichiers = fichiersCorpus(racine!!)
        assumeTrue("corpus vide — suite sautée honnêtement", fichiers.isNotEmpty())
        val textes = fichiers.map { it.readText() }
        // Deux passages (froid + chaud) — le budget cadrage §6 est
        // < 10 ms par édition de 1 500 lignes : le corpus entier (des
        // dizaines de fichiers) doit rester de l'ordre de la seconde.
        val debut = System.nanoTime()
        repeat(2) {
            textes.forEach { parseur.analyser(it) }
        }
        val duree = (System.nanoTime() - debut) / 1_000_000
        assertTrue(
            "corpus (${fichiers.size} fichiers) re-parsé 2x en ${duree} ms — budget cadrage §6",
            duree < 5_000,
        )
    }
}
