package jo.lspkotlin.session

import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.SymbolesProjet
import java.io.File

/**
 * Scan des RACINES SOURCES du projet (K3, LSP_KOTLIN.md §4.1
 * « symbols » — « index de déclarations des sources projet » motif
 * projet de référence, ici volontairement SIMPLE : un parcours complet,
 * paresseux, borné — l'incrémental viendra avec K4/K5 si la mesure le
 * justifie).
 *
 * PARTAGÉ par le moteur (toutes les sessions d'un projet) : les
 * connexions LSP successives ne re-scannent pas ; le scan est
 * INVALIDÉ quand les racines changent (nouvelle sync) — et les
 * documents OUVERTS dominent de toute façon côté session (un fichier
 * édité vit son arbre frais, le scan ne sert que les fichiers FERMÉS :
 * la fraîcheur d'un fichier fermé modifié hors éditeur est bornée à la
 * prochaine sync, écart documenté et accepté).
 *
 * URI des déclarations : `file://` + chemin FUSE (File.toURI) — la
 * cible de go-to-definition s'ouvre dans l'éditeur par le chemin
 * courant, même mécanique que le branchement LspEditor.
 */
internal class ScanProjet(
    private val parseur: ParseurKotlin,
) {
    /** Racines courantes (identité d'invalidation). */
    private var racines: List<File> = emptyList()

    /** Déclarations du dernier scan (vide avant le premier). */
    private var scannees: List<DeclarationProjet> = emptyList()

    /**
     * (Re)définit les racines — un appel aux MÊMES racines est un NO-OP
     * chaud (sync redondante) ; un changement re-scanne paresseusement.
     */
    internal fun definirRacines(nouvelles: List<File>) {
        if (nouvelles == racines) return
        racines = nouvelles
        scannees = emptyList()
    }

    /**
     * Déclarations de toutes les racines — scan paresseux au premier
     * accès (le coût est celui du parse des .kt, ~4 ms/1000 lignes sur
     * le corpus du dépôt) ; borné [BORN_FICHIERS] (au-delà : troncature
     * honnête — un projet qui dépasse ça a d'autres problèmes que la
     * complétion).
     *
     * @param exclureUri URI à EXCLURE (le document courant vit son
     *        propre arbre côté session — son doublon du scan serait
     *        périmé).
     */
    internal fun declarations(exclureUri: String? = null): List<DeclarationProjet> {
        if (scannees.isEmpty() && racines.isNotEmpty()) {
            scannees = scanner()
        }
        if (exclureUri == null) return scannees
        return scannees.filter { it.uri != exclureUri }
    }

    /** Parcours complet des racines (fichiers .kt, ordre déterministe).
 *
SwallowedException ciblé (règle 16) : fichier illisible = absent (D3). */
    @Suppress("SwallowedException")
    private fun scanner(): List<DeclarationProjet> {
        val resultat = mutableListOf<DeclarationProjet>()
        for (racine in racines) {
            val fichiers = mutableListOf<File>()
            parcourir(racine, fichiers)
            for (fichier in fichiers) {
                val arbre =
                    try {
                        parseur.analyser(fichier.readText(), script = false)
                    } catch (t: Throwable) {
                        // Illisible : absent du scan (partiel honnête D3) —
                        // le `continue` EST le contrat, l'exception est
                        // le signal, pas une perte.
                        continue
                    }
                val paquet = KtFile(arbre.racine).directivePackage()?.nomQualifie()
                resultat += SymbolesProjet.declarations(arbre.racine, fichier.toURI().toString(), paquet)
            }
        }
        return resultat
    }

    /**
     * Marche récursive bornée (dossiers cachés/build exclus).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/LoopWithTooManyJump-
     * Statements — les bornes (fichiers au max, dossier illisible,
     * exclusion) sont des ISSUES du parcours borné.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun parcourir(
        dossier: File,
        fichiers: MutableList<File>,
    ) {
        if (fichiers.size >= BORN_FICHIERS) return
        val enfants = dossier.listFiles() ?: return
        for (enfant in enfants.sortedBy { it.name }) {
            if (fichiers.size >= BORN_FICHIERS) return
            when {
                enfant.isDirectory ->
                    if (enfant.name !in EXCLUSIONS && !enfant.name.startsWith(".")) {
                        parcourir(enfant, fichiers)
                    }

                enfant.isFile && enfant.extension == "kt" && !enfant.name.endsWith(".kts") ->
                    fichiers.add(enfant)
            }
        }
    }

    private companion object {
        /** Borne de fichiers scannés (troncation honnête au-delà). */
        private const val BORN_FICHIERS: Int = 2000

        /** Dossiers exclus (sorties, caches — le scan est le SOURCE). */
        private val EXCLUSIONS: Set<String> = setOf("build", ".git", ".gradle")
    }
}
