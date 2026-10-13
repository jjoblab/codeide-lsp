package jo.codeide.lsp.index

/**
 * Phase de la construction d'un index de classpath (ADR 0111 §8) —
 * publiée en flux, jamais pollée.
 */
public enum class PhaseIndex {
    /** Construction en cours (progression par jar). */
    CONSTRUCTION,

    /**
     * Tous les segments du classpath sont construits et valides — le
     * localisateur devient AUTORITATIF (les négatifs n'ont plus besoin
     * de sondage ; P7/P8 du prompt).
     */
    PRET,

    /**
     * Construction terminée avec des jars illisibles ou périmés — index
     * PARTIEL HONNÊTE : les entrées manquantes sont listées, jamais
     * silencieuses ; le consommateur maintient le repli par sondage.
     */
    PARTIEL,

    /**
     * Construction impossible (stockage illisible…) — le LSP vit sans
     * index (repli intégral par sondage, ADR 0111 §8).
     */
    ERREUR,
}

/**
 * État instantané de la construction d'un index — publié par
 * [VueIndex.etat] en `StateFlow` (progression par jar, ADR 0111 §7).
 *
 * @property phase phase courante.
 * @property total nombre d'entrées indexables du classpath.
 * @property faits nombre d'entrées déjà construites ou reprises valides.
 * @property entreeCourante entrée en cours de construction (`null` hors
 *           construction), à AFFICHER (progression par jar).
 * @property reprises entrées reprises sans reconstruction (segments
 *           déjà valides — la reprise « à chaud » d'ADR 0111).
 * @property avertissements entrées écartées honnêtement (jar illisible,
 *           AAR sans jar interne, dossier vide…).
 * @property erreurs erreurs bloquantes de la construction (phase ERREUR).
 * @property phaseDetaillee phase détaillée (Phase B prompt 2, ADR 0117
 *           §4.4) — affinage de [phase] quand elle vaut [PhaseIndex.CONSTRUCTION].
 *           `null` (défaut [PhaseDetailleeIndex.AUCUNE]) pour les autres phases.
 * @property listeTravail liste des entrées en cours d'indexation (Phase B
 *           prompt 2, ADR 0117 §4.4) — une entrée par jar/AAR/dossier, avec
 *           état (en attente / actif / terminé / repris / écarté). Vide
 *           par défaut (rétrocompatibilité : les consommateurs existants
 *           qui ne lisent que [phase]/[faits]/[total] ne sont pas affectés).
 * @property breakdownIndexeurs répartition du temps par indexeur (Phase B
 *           prompt 2). Vide par défaut (rétrocompatibilité).
 * @property dureesPhases durées par phase de la construction (Phase B
 *           prompt 2). `null` par défaut (rétrocompatibilité : les durées
 *           ne sont mesurées que si l'indexeur les publie).
 * @property bilanDernierPassage bilan du dernier passage (Phase B prompt 2).
 *           `null` par défaut (rétrocompatibilité : le bilan n'est persisté
 *           que si l'indexeur le publie).
 * @property settled un passage est-il allé au bout (même partiellement ou
 *           en échec) ? (Phase B prompt 2, ADR 0117 §4.4). `false` par
 *           défaut (rétrocompatibilité). Garde « mode dégradé » : avant
 *           [PhaseIndex.PRET], une recherche vide veut dire « pas encore »
 *           (settled = false) et non « absent » (settled = true).
 */
public data class EtatIndex(
    public val phase: PhaseIndex,
    public val total: Int,
    public val faits: Int,
    public val entreeCourante: String?,
    public val reprises: Int,
    public val avertissements: List<String>,
    public val erreurs: List<String>,
    public val phaseDetaillee: PhaseDetailleeIndex = PhaseDetailleeIndex.AUCUNE,
    public val listeTravail: List<EntreeTravail> = emptyList(),
    public val breakdownIndexeurs: List<BreakdownIndexeur> = emptyList(),
    public val dureesPhases: DureesPhases? = null,
    public val bilanDernierPassage: BilanDernierPassage? = null,
    public val settled: Boolean = false,
) {
    /** État initial d'un classpath avant toute construction. */
    public companion object {
        /** Valeur neutre (aucune entrée, avant le démarrage). */
        public val INITIAL: EtatIndex =
            EtatIndex(
                phase = PhaseIndex.CONSTRUCTION,
                total = 0,
                faits = 0,
                entreeCourante = null,
                reprises = 0,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
    }
}
