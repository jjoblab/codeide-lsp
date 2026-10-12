package jo.lspkotlin.parite

import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Suite de parité « dépôt » (LSP_KOTLIN.md D2) : TOUT fichier `.kt` du
 * dépôt CodeIDE est parsé par la grammaire vendorée du compilateur —
 * chaque fichier doit produire un arbre SANS marqueur d'erreur (le dépôt
 * compile en CI, la grammaire du compilateur doit l'accepter telle
 * quelle), et la FORME globale des arbres est épingée par un digeste de
 * base (toute dérive de grammaire — re-sync amont ou régression locale —
 * se voit immédiatement).
 *
 * Équivalent projet de référence : leur sweep « 2381/2381 sur leur propre dépôt ».
 * La baseline (`src/test/resources/parite/digeste-corpus.txt`) se met à
 * jour DÉLIBÉRÉMENT : `./gradlew :lsp:kotlin:test
 * -Dparite.majBase=true` — le diff de baseline fait alors partie de la
 * revue du re-sync (cf. VENDOR.md du vendoring).
 *
 * Racine du corpus : le dépôt (remontée depuis le répertoire de travail
 * du test jusqu'à `settings.gradle.kts`) ; `-Dparite.racineCorpus=…` la
 * force (utile pour un sweep externe, ex. un checkout du compilateur).
 */
class PariteCorpusDepotTest {
    private val parseur = ParseurKotlin()

    private fun racineDepot(): File? {
        System.getProperty("parite.racineCorpus")?.let { return File(it) }
        return generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .take(8)
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
    }

    private fun fichiersCorpus(racine: File): List<File> {
        // Marche récursive explicite (déterministe : enfants triés par nom) —
        // build/ (générés), .git, .gradle exclus : le corpus est le SOURCE
        // du dépôt, pas ses sorties de build.
        val exclus = setOf("build", ".git", ".gradle")
        val resultat = mutableListOf<File>()
        fun parcourir(dossier: File) {
            val enfants = dossier.listFiles() ?: return
            for (enfant in enfants.sortedBy { it.name }) {
                when {
                    enfant.isDirectory -> if (enfant.name !in exclus) parcourir(enfant)
                    enfant.isFile && enfant.extension == "kt" && enfant.name != "digeste-corpus.txt" ->
                        resultat.add(enfant)
                }
            }
        }
        parcourir(racine)
        return resultat.sortedBy { it.relativeTo(racine).path }
    }

    @Test
    fun `tout le depôt se parse sans marqueur d'erreur`() {
        val racine = racineDepot()
        assumeNotNull(racine)
        val fichiers = fichiersCorpus(racine!!)
        assumeTrue("corpus vide ?", fichiers.isNotEmpty())

        val fautifs = mutableListOf<String>()
        var totalJeton = 0L
        val debut = System.nanoTime()
        for (fichier in fichiers) {
            val arbre = parseur.analyser(fichier.readText())
            totalJeton += arbre.nombreJetons
            if (arbre.nombreErreurs > 0) {
                fautifs.add("${fichier.relativeTo(racine).path} (${arbre.nombreErreurs} erreurs)")
            }
        }
        val dureeMs = (System.nanoTime() - debut) / 1_000_000

        assertEquals(
            "des fichiers du dépôt produisent des marqueurs d'erreur :\n" +
                fautifs.joinToString("\n"),
            emptyList<String>(),
            fautifs,
        )

        // Budget volontairement large (CI 2 cœurs) : un dépassement massif
        // signale une régression algorithmique, pas une latence à grinder.
        assertTrue(
            "sweep du corpus trop lent : ${dureeMs} ms pour ${fichiers.size} fichiers",
            dureeMs < 120_000,
        )
        println("[parite] ${fichiers.size} fichiers, $totalJeton jetons, ${dureeMs} ms")
    }

    @Test
    fun `le digeste structurel du corpus correspond a la baseline`() {
        val racine = racineDepot()
        assumeNotNull(racine)
        val fichiers = fichiersCorpus(racine!!)
        assumeTrue(fichiers.isNotEmpty())

        // Digeste par fichier (chemin + digeste de l'arbre), lui-même
        // condensé : la FORME seule compte, pas les textes de jetons.
        val condense = MessageDigest.getInstance("SHA-256")
        for (fichier in fichiers) {
            val arbre = parseur.analyser(fichier.readText())
            condense.update("${fichier.relativeTo(racine).path} ${arbre.digeste()}".toByteArray(Charsets.UTF_8))
        }
        val digesteCorpus = condense.digest().joinToString("") { "%02x".format(it) }
        val ligneCorpus = "fichiers=${fichiers.size} digeste=$digesteCorpus"

        val fichierBase = File("src/test/resources/parite/digeste-corpus.txt")
        if (System.getProperty("parite.majBase") == "true") {
            fichierBase.parentFile.mkdirs()
            fichierBase.writeText(
                buildString {
                    appendLine("# Baseline de parité — suite de parité du dépôt (regénérée délibérément).")
                    appendLine("# Voir lsp/kotlin/src/main/vendor/VENDOR.md : la mise à jour fait partie de la revue.")
                    appendLine(ligneCorpus)
                },
            )
            println("[parite] baseline mise à jour : $ligneCorpus")
            return
        }
        assertTrue(
            "baseline de parité absente (${fichierBase.path}) — générer une première fois : " +
                "./gradlew :lsp:kotlin:test --tests '*PariteCorpusDepotTest*' -Dparite.majBase=true",
            fichierBase.isFile,
        )
        val ligneAttendue = fichierBase.readLines().lastOrNull { it.isNotBlank() }
        assertEquals(
            "digeste du corpus divergé de la baseline — grammaire modifiée ? " +
                "si le changement est délibéré (re-sync), régénérer -Dparite.majBase=true",
            ligneAttendue,
            ligneCorpus,
        )
    }
}
