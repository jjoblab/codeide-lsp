package jo.codeide.lsp.index

/**
 * Cache LRU des segments décodés — budget tas EXPLICITE (ADR 0111 §6 :
 * cible < 30 Mo pour 100 jars, mesurée par le banc). Chaque segment
 * décodé est compté par son estimation
 * [SegmentDecode.octetsMemoire] ; le budget est respecté en évinçant les
 * segments les moins récemment utilisés — un segment plus gros que le
 * budget seul reste admis (un cache vide qui refuse TOUT ne sert à
 * rien : il évince alors tous les autres).
 *
 * L'accès aux segments rafraîchit leur horodatage disque (LRU du
 * nettoyage, [EntrepotSegments.toucher]).
 */
internal class CacheSegments(
    private val budgetOctets: Long,
    private val entrepot: EntrepotSegments,
) {
    /** LRU par ordre d'accès (synchronisé — lectures concurrentes sûres). */
    private val lru = LinkedHashMap<String, SegmentDecode>(CAPACITE_INITIALE, FACTEUR_CHARGE, true)

    /** Empreintes refusées à la lecture (corruption — pas de re-lecture). */
    private val refuses = mutableSetOf<String>()

    /** Octets actuellement comptés dans le budget. */
    @get:Synchronized
    internal val octetsCourants: Long
        get() = lru.values.sumOf { it.octetsMemoire }

    /**
     * Obtient un segment décodé — traversant : charge et valide depuis
     * l'entrepôt si absent du cache.
     *
     * @param empreinte empreinte-clé du segment.
     * @param tailleAttendue/mtimeAttendue validation W7 à la lecture.
     * @param surCorruption appelé une fois par segment refusé (crc,
     *        troncature) — l'appelant peut relancer une reconstruction
     *        de CE segment (auto-guérison, ADR 0111 §4).
     * @return le segment décodé, ou `null` si refusé (jamais d'exception).
     */
    @Suppress("ReturnCount")
    @Synchronized
    internal fun obtenir(
        empreinte: String,
        tailleAttendue: Long?,
        mtimeAttendue: Long?,
        surCorruption: (empreinte: String, raison: String) -> Unit,
    ): SegmentDecode? {
        lru[empreinte]?.let {
            entrepot.toucher(empreinte)
            return it
        }
        val empreinteDejaRefusee = refuses.contains(empreinte)
        if (empreinteDejaRefusee) {
            return null
        }
        val lecture = entrepot.lire(empreinte, tailleAttendue, mtimeAttendue)
        val segment =
            when (lecture) {
                is LectureSegment.Ok -> {
                    lecture.segment
                }

                is LectureSegment.Refuse -> {
                    // Refus mémorisé : une requête suivante ne relit pas le
                    // fichier corrompu (la reconstruction invalidera le refus).
                    refuses.add(empreinte)
                    surCorruption(empreinte, lecture.raison)
                    return null
                }
            }
        inserer(empreinte, segment)
        entrepot.toucher(empreinte)
        return segment
    }

    /** Invalide le refus mémorisé d'un segment (après reconstruction). */
    @Synchronized
    internal fun invaliderRefus(empreinte: String) {
        refuses.remove(empreinte)
        lru.remove(empreinte)
    }

    /** Vide le cache (fin de vie, transition d'état). */
    @Synchronized
    internal fun vider() {
        lru.clear()
        refuses.clear()
    }

    /** Insère un segment en respectant le budget (évicition LRU). */
    private fun inserer(
        empreinte: String,
        segment: SegmentDecode,
    ) {
        lru[empreinte] = segment
        evincerJusquAuBudget(empreinte)
    }

    /** Évince les moins récents jusqu'au budget (le nouvel entrant reste). */
    private fun evincerJusquAuBudget(empreinteGardee: String) {
        val iterateur = lru.entries.iterator()
        var total = lru.values.sumOf { it.octetsMemoire }
        while (total > budgetOctets && lru.size > 1) {
            val (empreinte, segment) = iterateur.next().let { it.key to it.value }
            if (empreinte == empreinteGardee) {
                // Le nouvel entrant vit en FIN d'ordre d'accès — ne peut
                // apparaître ici que s'il reste seul : la boucle s'arrête.
                break
            }
            total -= segment.octetsMemoire
            iterateur.remove()
        }
    }

    private companion object {
        private const val CAPACITE_INITIALE: Int = 64
        private const val FACTEUR_CHARGE: Float = 0.75f
    }
}
