package jo.lspkotlin.index

import jo.lspkotlin.api.BilanDernierPassageKotlin
import jo.lspkotlin.api.BreakdownIndexeurKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DureesPhasesKotlin
import jo.lspkotlin.api.EntreeTravailKotlin
import jo.lspkotlin.api.EtatEntreeTravailKotlin
import jo.lspkotlin.api.EvenementIndexationKotlin
import jo.lspkotlin.api.ObservateurIndexationKotlin
import jo.lspkotlin.api.PhaseDetailleeIndexationKotlin
import jo.lspkotlin.api.PhaseIndexationKotlin
import jo.lspkotlin.session.ScanProjet
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pilote des passages d'indexation du moteur (ADR 0211/0213/0214 —
 * demandes D1/D3/D4/D5 de CodeIDE) : UNE fois par sync, sur un fil
 * démon DÉDIÉ, il nourrit l'index des appelables (segments par
 * empreinte) puis attend le scan WARM des sources, publie les
 * ÉVÉNEMENTS de progression aux observateurs enregistrés, honore
 * l'ANNULATION coopérative (drapeau testé entre les entrées) et la
 * REPRISE ciblée des échecs (le cache rend les entrées saines
 * QUASI GRATUITES — seules les écarts sont re-payées).
 *
 * SÉQUENCE d'événements (ADR 0213) : CONSTRUCTION/BIBLIOTHEQUES au fil
 * des segments (EN_ATTENTE → ACTIF → REPRIS/TERMINE/ECARTE), puis
 * CONSTRUCTION/SOURCES au fil des fichiers du scan (X/N — marche
 * d'abord, total connu), puis PRET/PARTIEL final (PARTIEL si écarts ou
 * annulation, `settled=false` après annulation).
 *
 * Cohérence concurrente : le passage tourne sur UN fil ; les
 * événements du scan arrivent du fil du scan PENDANT que le pilote
 * l'attend (joignable — `attendrePassageCourant`), donc JAMAIS deux
 * émetteurs en même temps sur un même passage. Un observateur FAUTIF
 * est journalisé et ignoré — le passage continue (jamais d'exception
 * vers le moteur).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — UN verbe
 * par demande de CodeIDE (nourrir, observer, annuler, reprendre) plus
 * le catalogue d'événements du passage — même patron que les
 * `StatistiquesConstruction` d':lsp:index.
 */
@Suppress("TooManyFunctions")
internal class PasseurIndexationKotlin(
    private val index: IndexAppelablesKotlin,
    private val scan: ScanProjet,
) {
    /** Fil UNIQUE des passages (démon — la JVM ne l'attend pas). */
    private val executeur: ExecutorService =
        Executors.newSingleThreadExecutor { tache ->
            Thread(tache, "indexation-kotlin").apply { isDaemon = true }
        }

    /** Observateurs enregistrés (façades hôte — ADR 0213). */
    private val observateurs = CopyOnWriteArrayList<ObservateurIndexationKotlin>()

    /** Dernier classpath poussé par la sync (l'identité du passage). */
    @Volatile
    private var classpathCourant: ClasspathKotlin? = null

    /** Génération courante — un passage PÉRIMÉ s'arrête à la prochaine entrée. */
    @Volatile
    private var generation: Long = 0

    /** Un passage est-il en vol (annulation possible, ADR 0214) ? */
    @Volatile
    private var enVol: Boolean = false

    /** Annulation demandée pour le passage en vol (ADR 0214 — D5). */
    @Volatile
    private var annulationDemandee: Boolean = false

    /** Chemins ÉCARTÉS du dernier passage (reprise ciblée, ADR 0214 — D4). */
    @Volatile
    private var ecartsDernierPassage: List<String> = emptyList()

    /** Entrées NON TRAITÉES du dernier passage (annulation — reprise). */
    @Volatile
    private var pendancesDernierPassage: Int = 0

    /** Le scan du dernier passage a-t-il été ANNULÉ en route ? */
    @Volatile
    private var scanAnnuleDernierPassage: Boolean = false

    /** Enregistre un observateur (poignée de désinscription). */
    internal fun enregistrer(observateur: ObservateurIndexationKotlin): Closeable {
        observateurs.add(observateur)
        return Closeable { observateurs.remove(observateur) }
    }

    /**
     * Demande un passage pour [classpath] (une fois par sync, ADR
     * 0211) — retour IMMÉDIAT : le travail part sur le fil dédié, un
     * passage PÉRIMÉ (nouvelle sync en vol) s'arrête à la prochaine
     * entrée et laisse la place.
     */
    internal fun definirClasspath(classpath: ClasspathKotlin): Boolean {
        classpathCourant = classpath
        generation++
        executeur.execute { passer() }
        return true
    }

    /** Annule le passage en vol (ADR 0214 — D5) ; `false` hors vol. */
    internal fun annulerPassage(): Boolean {
        if (!enVol) return false
        annulationDemandee = true
        return true
    }

    /**
     * Retente les échecs du dernier passage (ADR 0214 — D4) : le cache
     * mémoire rend les entrées saines REPRIS sans re-lecture, seuls
     * les écarts (et les pendances d'une annulation, segments OU scan)
     * sont re-payés. `false` quand il n'y a rien à retenter.
     */
    internal fun reesayerEchecs(): Boolean {
        if (ecartsDernierPassage.isEmpty() && pendancesDernierPassage == 0 && !scanAnnuleDernierPassage) {
            return false
        }
        generation++
        executeur.execute { passer() }
        return true
    }

    // ── Le passage ────────────────────────────────────────────────────

    /**
     * UN passage complet : segments puis scan puis final. Tourne sur
     * le fil dédié — aucun appelant n'attend jamais ici.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — le passage EST
     * la séquence des demandes (segments, scan, final) en UN récit
     * linéaire ; le découper cacherait l'ORDONNANCEMENT qui fait le
     * contrat ADR 0213 (BIBLIOTHEQUES avant SOURCES avant final).
     * CyclomaticComplexMethod/ReturnCount — le récit des phases porte
     * les ISSUES des demandes (garde DEJA_COUVERT, annulation,
     * péremption de génération, final PRET/PARTIEL) : chaque branche est
     * une décision DU CONTRAT, pas une dérive (même patron que
     * construireInterne d':lsp:index).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun passer() {
        val classpath = classpathCourant ?: return
        val maGeneration = generation
        val debutPassage = System.currentTimeMillis()
        index.sonnerNourrissageMoteur()
        enVol = true
        annulationDemandee = false
        val etats = LinkedHashMap<String, EtatEntreeTravailKotlin>()
        val avertissements = mutableListOf<String>()
        var faits = 0
        var reprises = 0
        val ecarts = mutableListOf<String>()
        var pendances = 0
        var annule = false
        try {
            val entrees = classpath.entrees
            entrees.forEach { etats[it.chemin] = EtatEntreeTravailKotlin.EN_ATTENTE }
            publier(
                evenement(
                    phase = PhaseIndexationKotlin.CONSTRUCTION,
                    faits = 0,
                    total = entrees.size,
                    entreeCourante = null,
                    avertissements = avertissements,
                    erreurs = emptyList(),
                    phaseDetaillee = PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES,
                    etats = etats,
                    reprises = 0,
                    durees = null,
                    bilan = null,
                    settled = false,
                ),
            )
            val debutSegments = System.currentTimeMillis()
            val observation =
                IndexAppelablesKotlin.ObservationPassage { entree, issue ->
                    if (issue == null) {
                        // Entrée ACTIVE : l'écran montre l'entrée en cours
                        // (un décodage à froid peut durer des secondes).
                        etats[entree.chemin] = EtatEntreeTravailKotlin.ACTIF
                        publier(
                            evenement(
                                phase = PhaseIndexationKotlin.CONSTRUCTION,
                                faits = faits,
                                total = entrees.size,
                                entreeCourante = entree.chemin,
                                avertissements = avertissements,
                                erreurs = emptyList(),
                                phaseDetaillee = PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES,
                                etats = etats,
                                reprises = reprises,
                                durees = null,
                                bilan = null,
                                settled = false,
                            ),
                        )
                        return@ObservationPassage true
                    }
                    when (issue) {
                        IndexAppelablesKotlin.IssueSegment.REPRIS -> {
                            etats[entree.chemin] = EtatEntreeTravailKotlin.REPRIS
                            reprises++
                        }

                        IndexAppelablesKotlin.IssueSegment.TERMINE ->
                            etats[entree.chemin] = EtatEntreeTravailKotlin.TERMINE

                        IndexAppelablesKotlin.IssueSegment.ECARTE -> {
                            etats[entree.chemin] = EtatEntreeTravailKotlin.ECARTE
                            ecarts.add(entree.chemin)
                            avertissements.add("${entree.chemin} : entrée illisible (écartée)")
                        }
                    }
                    faits++
                    publier(
                        evenement(
                            phase = PhaseIndexationKotlin.CONSTRUCTION,
                            faits = faits,
                            total = entrees.size,
                            entreeCourante = null,
                            avertissements = avertissements,
                            erreurs = emptyList(),
                            phaseDetaillee = PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES,
                            etats = etats,
                            reprises = reprises,
                            durees = null,
                            bilan = null,
                            settled = false,
                        ),
                    )
                    // Continuation : annulation demandée ou passage
                    // PÉRIMÉ (une sync plus fraîche a remplacé).
                    !annulationDemandee && maGeneration == generation
                }
            val resultat = index.definir(entrees, classpath.dossierIndex, observation)
            if (resultat == IndexAppelablesKotlin.ResultatDefin.DEJA_COUVERT) {
                // No-op chaud (sync redondante) : tout est REPRIS sans
                // re-lecture — l'écran voit une reprise immédiate.
                for (entree in entrees) {
                    etats[entree.chemin] = EtatEntreeTravailKotlin.REPRIS
                }
                faits = entrees.size
                reprises = entrees.size
            }
            if (resultat == IndexAppelablesKotlin.ResultatDefin.PARTIEL_ANNULE) {
                annule = true
                pendances = entrees.count { etats[it.chemin] == EtatEntreeTravailKotlin.EN_ATTENTE }
                avertissements.add("passage annulé par l'utilisateur")
            }
            if (maGeneration != generation) {
                // PÉRIMÉ : le passage neuf prend la main — AUCUN final
                // ici (les événements du neuf réparent l'écran).
                return
            }
            val finSegments = System.currentTimeMillis()
            val dureeSegments = finSegments - debutSegments
            // ── Phase SOURCES : le scan WARM des racines ──────────────
            publier(
                evenement(
                    phase = PhaseIndexationKotlin.CONSTRUCTION,
                    faits = 0,
                    total = 0,
                    entreeCourante = null,
                    avertissements = avertissements,
                    erreurs = emptyList(),
                    phaseDetaillee = PhaseDetailleeIndexationKotlin.SOURCES,
                    etats = etats,
                    reprises = reprises,
                    durees = null,
                    bilan = null,
                    settled = false,
                ),
            )
            var scanAnnule = false
            scan.definirObservateur { faitsFichier, totalFichier, nom ->
                publier(
                    evenement(
                        phase = PhaseIndexationKotlin.CONSTRUCTION,
                        faits = faitsFichier,
                        total = totalFichier,
                        entreeCourante = nom,
                        avertissements = avertissements,
                        erreurs = emptyList(),
                        phaseDetaillee = PhaseDetailleeIndexationKotlin.SOURCES,
                        etats = etats,
                        reprises = reprises,
                        durees = null,
                        bilan = null,
                        settled = false,
                    ),
                )
                if (annulationDemandee || maGeneration != generation) {
                    scanAnnule = true
                    return@definirObservateur false
                }
                true
            }
            val debutScan = System.currentTimeMillis()
            if (scanAnnuleDernierPassage) {
                // Le scan du passage précédent a été ANNULÉ : la reprise
                // repart d'un RAFRAÎCHISSEMENT (l'acquis est en cache
                // mtime, seul le reste est re-parsé).
                scan.definirRacines(classpath.racinesSources)
                scan.rafraichir()
            } else {
                scan.definirRacines(classpath.racinesSources)
            }
            scan.attendrePassageCourant()
            val finScan = System.currentTimeMillis()
            scan.definirObservateur(null)
            if (scanAnnule) {
                annule = true
                avertissements.add("passage annulé par l'utilisateur")
            }
            val statsScan = scan.statsDernierPassage()
            scanAnnuleDernierPassage = scanAnnule
            if (maGeneration != generation) return
            // ── Final : PRET ou PARTIEL, bilan paritaire (ADR 0213) ──
            val phase =
                if (ecarts.isEmpty() && !annule) PhaseIndexationKotlin.PRET else PhaseIndexationKotlin.PARTIEL
            val durees = DureesPhasesKotlin(bibliothequesMs = dureeSegments, sourcesMs = finScan - debutScan)
            val plusCouteux =
                if (finScan - debutScan >= dureeSegments) IDENTIFIANT_SCAN else IDENTIFIANT_SEGMENTS
            publier(
                evenement(
                    phase = phase,
                    // Au final, la progression globale redevient celle
                    // des SEGMENTS (miroir Java : faits/total par jar —
                    // les fichiers du scan vivent dans le bilan).
                    faits = faits,
                    total = entrees.size,
                    entreeCourante = null,
                    avertissements = avertissements,
                    erreurs = emptyList(),
                    phaseDetaillee = PhaseDetailleeIndexationKotlin.AUCUNE,
                    etats = etats,
                    reprises = reprises,
                    durees = durees,
                    breakdown =
                        listOf(
                            BreakdownIndexeurKotlin(
                                identifiant = IDENTIFIANT_SEGMENTS,
                                libelle = "Segments Kotlin (kidx)",
                                millisecondesCumulees = dureeSegments,
                                nombreEntrees = entrees.size,
                            ),
                            BreakdownIndexeurKotlin(
                                identifiant = IDENTIFIANT_SCAN,
                                libelle = "Scan des sources Kotlin",
                                millisecondesCumulees = finScan - debutScan,
                                nombreEntrees = statsScan.total,
                            ),
                        ),
                    bilan =
                        BilanDernierPassageKotlin(
                            dureeMs = System.currentTimeMillis() - debutPassage,
                            artefactsTotal = entrees.size,
                            artefactsRepris = reprises,
                            artefactsReconstruits = faits - reprises,
                            fichiersParcourus = statsScan.total,
                            fichiersReanalyses = statsScan.reanalyses,
                            indexeurPlusCouteux = plusCouteux,
                            horodatageMs = System.currentTimeMillis(),
                        ),
                    settled = !annule,
                ),
            )
        } finally {
            if (maGeneration == generation) {
                ecartsDernierPassage = ecarts
                pendancesDernierPassage = pendances
            }
            enVol = false
        }
    }

    /** Publie un événement à CHAQUE observateur (un fautif n'arrête rien). */
    private fun publier(evenement: EvenementIndexationKotlin) {
        for (observateur in observateurs) {
            try {
                observateur.surEvenement(evenement)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "observateur d'indexation fautif (ignoré)", t)
            }
        }
    }

    /**
     * Assemble un événement (la liste de travail suit l'ordre du
     * classpath ; le breakdown n'est rempli qu'au final — miroir Java
     * qui le laisse vide pendant la construction).
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — UN
     * paramètre par champ de [EvenementIndexationKotlin], les regrouper
     * casserait la lisibilité de l'assemblage un-à-un.
     */
    @Suppress("LongParameterList")
    private fun evenement(
        phase: PhaseIndexationKotlin,
        faits: Int,
        total: Int,
        entreeCourante: String?,
        avertissements: List<String>,
        erreurs: List<String>,
        phaseDetaillee: PhaseDetailleeIndexationKotlin,
        etats: Map<String, EtatEntreeTravailKotlin>,
        reprises: Int,
        durees: DureesPhasesKotlin?,
        breakdown: List<BreakdownIndexeurKotlin> = emptyList(),
        bilan: BilanDernierPassageKotlin?,
        settled: Boolean,
    ): EvenementIndexationKotlin =
        EvenementIndexationKotlin(
            phase = phase,
            faits = faits,
            total = total,
            entreeCourante = entreeCourante,
            avertissements = avertissements.toList(),
            erreurs = erreurs.toList(),
            phaseDetaillee = phaseDetaillee,
            listeTravail = etats.map { EntreeTravailKotlin(it.key, it.value) },
            breakdownIndexeurs = breakdown,
            dureesPhases = durees,
            bilanDernierPassage = bilan,
            settled = settled,
            reprises = reprises,
        )

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(PasseurIndexationKotlin::class.java.name)

        /** Identifiants des indexeurs (breakdown — libellés affichables). */
        internal const val IDENTIFIANT_SEGMENTS: String = "kidx-segments"
        internal const val IDENTIFIANT_SCAN: String = "scan-warm"
    }
}
