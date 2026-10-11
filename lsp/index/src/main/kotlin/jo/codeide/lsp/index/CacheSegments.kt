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
 * Le rafraîchissement d'horodatage DISQUE (LRU du nettoyage,
 * [EntrepotSegments.toucher]) est AMORTI : il n'arrive au plus qu'une
 * fois par segment et par [SEUIL_TOUCHER_MS] — un toucher à CHAQUE
 * accès faisait une écriture disque par segment visité, et les requêtes
 * GLOBALES (paquets, localisation négative) visitent TOUS les segments :
 * 130–300 ms par requête à 250 jars, mesuré par le banc moteur (lot F2,
 * jeu lourd) — le chemin de lecture ne doit JAMAIS écrire.
 */
internal class CacheSegments(
    private val budgetOctets: Long,
    private val entrepot: EntrepotSegments,
) {
    /** LRU par ordre d'accès (synchronisé — lectures concurrentes sûres). */
    private val lru = LinkedHashMap<String, SegmentDecode>(CAPACITE_INITIALE, FACTEUR_CHARGE, true)

    /** Empreintes refusées à la lecture (corruption — pas de re-lecture). */
    private val refuses = mutableSetOf<String>()

    /** Dernier rafraîchissement DISQUE par empreinte (amortissement). */
    private val derniersTouchers = HashMap<String, Long>()

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
            toucherAmorti(empreinte)
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
        toucherAmorti(empreinte)
        return segment
    }

    /**
     * Rafraîchit l'horodatage disque au plus une fois par segment et par
     * [SEUIL_TOUCHER_MS] — l'ordre LRU MÉMOIRE vit dans `lru` (accès),
     * le disque n'a pas besoin de suivre chaque requête.
     */
    private fun toucherAmorti(empreinte: String) {
        val maintenant = System.currentTimeMillis()
        val dernier = derniersTouchers[empreinte] ?: 0L
        if (maintenant - dernier >= SEUIL_TOUCHER_MS) {
            entrepot.toucher(empreinte)
            derniersTouchers[empreinte] = maintenant
        }
    }

    /** Invalide le refus mémorisé d'un segment (après reconstruction). */
    @Synchronized
    internal fun invaliderRefus(empreinte: String) {
        refuses.remove(empreinte)
        lru.remove(empreinte)
        derniersTouchers.remove(empreinte)
    }

    /** Vide le cache (fin de vie, transition d'état). */
    @Synchronized
    internal fun vider() {
        lru.clear()
        refuses.clear()
        derniersTouchers.clear()
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

        /** Amortissement du rafraîchissement disque (10 min). */
        private const val SEUIL_TOUCHER_MS: Long = 10 * 60 * 1000L
    }
}
