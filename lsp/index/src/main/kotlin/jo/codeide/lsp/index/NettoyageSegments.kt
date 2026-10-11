package jo.codeide.lsp.index

import java.io.File
import java.util.concurrent.TimeUnit

/** Résultat d'un nettoyage (mesuré et signalé, jamais silencieux). */
public data class ResultatNettoyage(
    /** Segments supprimés (orphelins par âge, puis par budget). */
    public val segmentsSupprimes: Int,
    /** Octets libérés. */
    public val octetsLiberes: Long,
    /** Octets du stockage restant après nettoyage. */
    public val octetsRestants: Long,
    /** Segments référencés JAMAIS touchés (budget non tenu — signalé). */
    public val budgetDepasse: Boolean,
)

/**
 * Nettoyage du stockage partagé (ADR 0111 §9) :
 *
 * - un segment ORPHELIN (aucune référence depuis un classpath CONNU)
 *   dont le dernier accès date de plus de [joursRetention][nettoyer]
 *   jours est supprimé ;
 * - si le stockage dépasse le budget disque global, les orphelins les
 *   moins récemment utilisés sont supprimés jusqu'au budget ;
 * - les segments RÉFÉRENCÉS ne sont jamais supprimés : un dépassement
 *   restant est SIGNALÉ ([ResultatNettoyage.budgetDepasse]) — reconstruire
 *   un segment vivant coûterait plus cher que le gain de place.
 *
 * L'âge d'un segment est son dernier ACCÈS ([CacheSegments] rafraîchit
 * l'horodatage du fichier à chaque lecture), pas sa création.
 */
public class NettoyageSegments internal constructor(
    private val entrepot: EntrepotSegments,
    private val horloge: () -> Long,
) {
    /** Constructeur système (horloge réelle). */
    public constructor(repertoire: File) : this(EntrepotSegments(repertoire), System::currentTimeMillis)

    /**
     * Nettoie le stockage.
     *
     * @param references empreintes des segments référencés par les vues
     *        vivantes ([VueIndex.empreintesReferences]).
     * @param joursRetention jours de grâce d'un orphelin.
     * @param budgetOctets budget disque global du stockage.
     */
    public fun nettoyer(
        references: Set<String>,
        joursRetention: Int,
        budgetOctets: Long,
    ): ResultatNettoyage {
        // Les noms de fichiers sont opaques (sha256 de l'empreinte) : la
        // comparaison des références se fait sur les NOMS, pas les clés.
        val nomsReferences = references.map { ClesSegments.nomFichier(it) }.toSet()
        val fichiers = entrepot.lister()
        var supprimes = 0
        var liberes = 0L

        // 1. Orphelins par âge (aucune référence + dernier accès ancien).
        val limiteAge = horloge() - TimeUnit.DAYS.toMillis(joursRetention.toLong())
        val restants = mutableListOf<File>()
        for (fichier in fichiers) {
            if (fichier.name in nomsReferences) {
                restants.add(fichier)
                continue
            }
            if (fichier.lastModified() < limiteAge) {
                // La longueur se lit AVANT la suppression (elle vaudrait
                // 0 ensuite — le budget ne descendrait jamais).
                val longueur = fichier.length()
                if (fichier.delete()) {
                    supprimes++
                    liberes += longueur
                }
            } else {
                restants.add(fichier)
            }
        }

        // 2. Budget disque : orphelins les moins récemment utilisés d'abord.
        var taille = restants.sumOf { it.length() }
        if (taille > budgetOctets) {
            val orphelins =
                restants
                    .filter { it.name !in nomsReferences }
                    .sortedBy { it.lastModified() }
            for (orphelin in orphelins) {
                if (taille <= budgetOctets) break
                // Longueur AVANT suppression — cf. phase d'âge.
                val longueur = orphelin.length()
                if (orphelin.delete()) {
                    supprimes++
                    liberes += longueur
                    taille -= longueur
                }
            }
        }
        val restantFinal = entrepot.tailleTotale()
        return ResultatNettoyage(
            segmentsSupprimes = supprimes,
            octetsLiberes = liberes,
            octetsRestants = restantFinal,
            budgetDepasse = restantFinal > budgetOctets,
        )
    }
}
