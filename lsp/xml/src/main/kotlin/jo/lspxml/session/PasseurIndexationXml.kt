package jo.lspxml.session

import jo.lspxml.api.BilanDernierPassageXml
import jo.lspxml.api.BreakdownIndexeurXml
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.DureesPhasesXml
import jo.lspxml.api.EntreeTravailXml
import jo.lspxml.api.EtatEntreeTravailXml
import jo.lspxml.api.EvenementIndexationXml
import jo.lspxml.api.ObservateurIndexationXml
import jo.lspxml.api.PhaseDetailleeIndexationXml
import jo.lspxml.api.PhaseIndexationXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.resources.IndexRessources
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pilote des passages d'indexation du moteur XML (ADR 0211/0213/0214 —
 * demandes D1-annexe/D3/D4/D5 de CodeIDE) : UNE fois par sync, sur un
 * fil démon DÉDIÉ, il PRÉCHAUFFE le catalogue XE2 de la plateforme (le
 * coût quitte la première frappe de diagnostic), scanne les AAR des
 * dépendances puis les racines `res/` du projet, publie les ÉVÉNEMENTS
 * de progression, honore l'ANNULATION coopérative (entre les unités)
 * et la REPRISE ciblée des échecs (un échec n'est JAMAIS mis en cache
 * mtime — la reprise le retente toujours).
 *
 * SÉQUENCE (ADR 0213) : CONSTRUCTION/BIBLIOTHEQUES (catalogue XE2 +
 * AAR P3), CONSTRUCTION/SOURCES (racines `res/`), PRET/PARTIEL final.
 *
 * Miroir volontaire de `jo.lspkotlin.index.PasseurIndexationKotlin`
 * (mêmes champs d'événement, mêmes sémantiques d'états) — la façade
 * hôte traduit vers le MÊME `EtatIndexation` public.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — UN verbe par
 * demande (nourrir, observer, annuler, reprendre) plus le catalogue
 * d'événements — miroir du passeur Kotlin.
 */
@Suppress("TooManyFunctions")
internal class PasseurIndexationXml(
    private val index: IndexRessources,
    private val catalogues: CataloguesPartages,
) {
    /** Fil UNIQUE des passages (démon — la JVM ne l'attend pas). */
    private val executeur: ExecutorService =
        Executors.newSingleThreadExecutor { tache ->
            Thread(tache, "indexation-xml").apply { isDaemon = true }
        }

    /** Observateurs enregistrés (façade hôte — ADR 0213). */
    private val observateurs = CopyOnWriteArrayList<ObservateurIndexationXml>()

    /** Dernier classpath poussé par la sync (l'identité du passage). */
    @Volatile
    private var classpathCourant: ClasspathXml? = null

    /** Génération courante — un passage PÉRIMÉ s'arrête à la prochaine unité. */
    @Volatile
    private var generation: Long = 0

    /** Un passage est-il en vol (annulation possible, ADR 0214) ? */
    @Volatile
    private var enVol: Boolean = false

    /** Annulation demandée pour le passage en vol (ADR 0214 — D5). */
    @Volatile
    private var annulationDemandee: Boolean = false

    /** Unités ÉCARTÉES du dernier passage (reprise ciblée, ADR 0214 — D4). */
    @Volatile
    private var ecartsDernierPassage: List<String> = emptyList()

    /** Unités NON TRAITÉES du dernier passage (annulation — reprise). */
    @Volatile
    private var pendancesDernierPassage: Int = 0

    /** Enregistre un observateur (poignée de désinscription). */
    internal fun enregistrer(observateur: ObservateurIndexationXml): Closeable {
        observateurs.add(observateur)
        return Closeable { observateurs.remove(observateur) }
    }

    /**
     * Demande un passage pour [classpath] (une fois par sync, ADR
     * 0211) — retour IMMÉDIAT, le travail part sur le fil dédié.
     */
    internal fun definirClasspath(classpath: ClasspathXml): Boolean {
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
     * mtime rend les unités saines REPRIS, seuls les dossiers/AAR
     * douteux (jamais mis en cache) et les pendances d'une annulation
     * sont re-payés. `false` quand il n'y a rien à retenter.
     */
    internal fun reesayerEchecs(): Boolean {
        if (ecartsDernierPassage.isEmpty() && pendancesDernierPassage == 0) return false
        generation++
        executeur.execute { passer() }
        return true
    }

    // ── Le passage ────────────────────────────────────────────────────

    /** Compteurs courants du passage (assemblage des événements). */
    private class Compteurs {
        var faits = 0
        var reprises = 0
        var annule = false
        val ecarts = mutableListOf<String>()
    }

    /**
     * Observation d'une PHASE du passage — tient les états à jour au
     * fil des notifications de l'index, publie un événement PAR unité
     * et honore l'annulation/le remplacement de génération.
     */
    private inner class ObservationPhase(
        private val maGeneration: Long,
        private val phase: PhaseDetailleeIndexationXml,
        private val etats: MutableMap<String, EtatEntreeTravailXml>,
        private val avertissements: MutableList<String>,
        private val compteurs: Compteurs,
    ) : IndexRessources.ObservationPassage {
        /** Total connu de la phase (0 = indéterminé tant que non découvert). */
        private var unites = 0

        override fun surUnite(
            nom: String,
            issue: IndexRessources.IssueUnite,
        ): Boolean {
            when (issue) {
                IndexRessources.IssueUnite.REPRIS -> {
                    etats[nom] = EtatEntreeTravailXml.REPRIS
                    compteurs.reprises++
                }

                IndexRessources.IssueUnite.TERMINE -> etats[nom] = EtatEntreeTravailXml.TERMINE

                IndexRessources.IssueUnite.ECARTE -> {
                    etats[nom] = EtatEntreeTravailXml.ECARTE
                    compteurs.ecarts.add(nom)
                    avertissements.add("$nom : unité illisible (écartée)")
                }
            }
            compteurs.faits++
            unites++
            publier(
                evenement(
                    phase = PhaseIndexationXml.CONSTRUCTION,
                    faits = unites,
                    total = unites,
                    entreeCourante = null,
                    avertissements = avertissements,
                    phaseDetaillee = phase,
                    etats = etats,
                    reprises = compteurs.reprises,
                    durees = null,
                    breakdown = emptyList(),
                    bilan = null,
                    settled = false,
                ),
            )
            // Continuation : annulation demandée ou passage PÉRIMÉ.
            if (annulationDemandee || maGeneration != generation) {
                compteurs.annule = true
                return false
            }
            return true
        }
    }

    /**
     * UN passage complet : catalogue + AAR (bibliothèques) puis racines
     * `res/` (sources) puis final. Tourne sur le fil dédié.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — le passage EST
     * la séquence des phases en UN récit linéaire (l'ordonnancement est
     * le contrat ADR 0213) ; CyclomaticComplexMethod/NestedBlockDepth/
     * ReturnCount — le récit porte les ISSUES des demandes (préchauffe
     * XE2 absente, annulation, péremption de génération, final
     * PRET/PARTIEL) : chaque branche est une décision DU CONTRAT
     * (même patron que le passeur Kotlin et construireInterne
     * d':lsp:index).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun passer() {
        val classpath = classpathCourant ?: return
        val maGeneration = generation
        val debutPassage = System.currentTimeMillis()
        index.sonnerNourrissageMoteur()
        enVol = true
        annulationDemandee = false
        val etats = LinkedHashMap<String, EtatEntreeTravailXml>()
        val avertissements = mutableListOf<String>()
        val compteurs = Compteurs()
        var pendances = 0
        var dureeCatalogue = 0L
        var dureeAar = 0L
        var dureeRacines = 0L
        var racinesUnites = 0
        var catalogueAbsent = false
        try {
            // ── Phase BIBLIOTHEQUES : catalogue XE2 + AAR P3 ───────────
            val aars = classpath.racinesAar
            val nomCatalogue = classpath.dossierPlateforme?.let { NOM_UNITE_CATALOGUE }
            (listOfNotNull(nomCatalogue) + aars.map { it.path }).forEach { nom ->
                etats[nom] = EtatEntreeTravailXml.EN_ATTENTE
            }
            val totalBibliotheques = etats.size
            publier(
                evenement(
                    phase = PhaseIndexationXml.CONSTRUCTION,
                    faits = 0,
                    total = totalBibliotheques,
                    entreeCourante = null,
                    avertissements = avertissements,
                    phaseDetaillee = PhaseDetailleeIndexationXml.BIBLIOTHEQUES,
                    etats = etats,
                    reprises = 0,
                    durees = null,
                    breakdown = emptyList(),
                    bilan = null,
                    settled = false,
                ),
            )
            if (nomCatalogue != null) {
                etats[nomCatalogue] = EtatEntreeTravailXml.ACTIF
                publier(
                    evenement(
                        phase = PhaseIndexationXml.CONSTRUCTION,
                        faits = compteurs.faits,
                        total = totalBibliotheques,
                        entreeCourante = nomCatalogue,
                        avertissements = avertissements,
                        phaseDetaillee = PhaseDetailleeIndexationXml.BIBLIOTHEQUES,
                        etats = etats,
                        reprises = compteurs.reprises,
                        durees = null,
                        breakdown = emptyList(),
                        bilan = null,
                        settled = false,
                    ),
                )
                val reprise = catalogues.dejaCharge(classpath.dossierPlateforme)
                val debutCatalogue = System.currentTimeMillis()
                val catalogue = catalogues.catalogue(classpath.dossierPlateforme)
                dureeCatalogue = System.currentTimeMillis() - debutCatalogue
                // X4 : plateforme absente = catalogue `null` caché —
                // l'entrée est ÉCARTÉE honnêtement (PARTIEL).
                if (catalogue == null) {
                    catalogueAbsent = true
                    etats[nomCatalogue] = EtatEntreeTravailXml.ECARTE
                    compteurs.ecarts.add(nomCatalogue)
                    avertissements.add("${classpath.dossierPlateforme!!.path} : catalogue XE2 illisible (écarté)")
                } else {
                    etats[nomCatalogue] =
                        if (reprise) EtatEntreeTravailXml.REPRIS else EtatEntreeTravailXml.TERMINE
                    if (reprise) compteurs.reprises++
                }
                compteurs.faits++
                publier(
                    evenement(
                        phase = PhaseIndexationXml.CONSTRUCTION,
                        faits = compteurs.faits,
                        total = totalBibliotheques,
                        entreeCourante = null,
                        avertissements = avertissements,
                        phaseDetaillee = PhaseDetailleeIndexationXml.BIBLIOTHEQUES,
                        etats = etats,
                        reprises = compteurs.reprises,
                        durees = null,
                        breakdown = emptyList(),
                        bilan = null,
                        settled = false,
                    ),
                )
            }
            if (annulationDemandee || maGeneration != generation) {
                compteurs.annule = true
            }
            if (!compteurs.annule) {
                val debutAar = System.currentTimeMillis()
                val observationAar =
                    ObservationPhase(
                        maGeneration,
                        PhaseDetailleeIndexationXml.BIBLIOTHEQUES,
                        etats,
                        avertissements,
                        compteurs,
                    )
                index.definirAar(aars, observationAar)
                dureeAar = System.currentTimeMillis() - debutAar
            }
            if (compteurs.annule) {
                pendances = etats.count { it.value == EtatEntreeTravailXml.EN_ATTENTE }
                avertissements.add("passage annulé par l'utilisateur")
            }
            if (maGeneration != generation) return
            // ── Phase SOURCES : racines res/ du projet ────────────────
            publier(
                evenement(
                    phase = PhaseIndexationXml.CONSTRUCTION,
                    faits = 0,
                    total = 0,
                    entreeCourante = null,
                    avertissements = avertissements,
                    phaseDetaillee = PhaseDetailleeIndexationXml.SOURCES,
                    etats = etats,
                    reprises = compteurs.reprises,
                    durees = null,
                    breakdown = emptyList(),
                    bilan = null,
                    settled = false,
                ),
            )
            if (!compteurs.annule) {
                val debutRacines = System.currentTimeMillis()
                val observationRacines =
                    ObservationPhase(
                        maGeneration,
                        PhaseDetailleeIndexationXml.SOURCES,
                        etats,
                        avertissements,
                        compteurs,
                    )
                index.definir(classpath.racinesSources, observationRacines)
                dureeRacines = System.currentTimeMillis() - debutRacines
                racinesUnites = etats.count {
                    it.key !in (listOfNotNull(nomCatalogue) + aars.map { aar -> aar.path })
                }
            }
            if (maGeneration != generation) return
            // ── Final : PRET ou PARTIEL, bilan paritaire ──────────────
            val phase =
                if (compteurs.ecarts.isEmpty() && !compteurs.annule) {
                    PhaseIndexationXml.PRET
                } else {
                    PhaseIndexationXml.PARTIEL
                }
            val durees = DureesPhasesXml(bibliothequesMs = dureeCatalogue + dureeAar, sourcesMs = dureeRacines)
            publier(
                evenement(
                    phase = phase,
                    faits = compteurs.faits,
                    total = etats.size,
                    entreeCourante = null,
                    avertissements = avertissements,
                    phaseDetaillee = PhaseDetailleeIndexationXml.AUCUNE,
                    etats = etats,
                    reprises = compteurs.reprises,
                    durees = durees,
                    breakdown =
                        listOf(
                            BreakdownIndexeurXml(
                                identifiant = IDENTIFIANT_CATALOGUE,
                                libelle = "Catalogue plateforme (XE2)",
                                millisecondesCumulees = dureeCatalogue,
                                nombreEntrees = if (nomCatalogue != null) 1 else 0,
                            ),
                            BreakdownIndexeurXml(
                                identifiant = IDENTIFIANT_AAR,
                                libelle = "Ressources des AAR (P3)",
                                millisecondesCumulees = dureeAar,
                                nombreEntrees = aars.size,
                            ),
                            BreakdownIndexeurXml(
                                identifiant = IDENTIFIANT_RESSOURCES,
                                libelle = "Ressources du projet (XE3)",
                                millisecondesCumulees = dureeRacines,
                                nombreEntrees = racinesUnites,
                            ),
                        ),
                    bilan =
                        BilanDernierPassageXml(
                            dureeMs = System.currentTimeMillis() - debutPassage,
                            artefactsTotal = etats.size,
                            artefactsRepris = compteurs.reprises,
                            artefactsReconstruits = (compteurs.faits - compteurs.reprises).coerceAtLeast(0),
                            fichiersParcourus = 0,
                            fichiersReanalyses = 0,
                            indexeurPlusCouteux =
                                listOf(
                                    dureeCatalogue to IDENTIFIANT_CATALOGUE,
                                    dureeAar to IDENTIFIANT_AAR,
                                    dureeRacines to IDENTIFIANT_RESSOURCES,
                                ).maxByOrNull { it.first }?.second,
                            horodatageMs = System.currentTimeMillis(),
                        ),
                    settled = !compteurs.annule,
                ),
            )
        } finally {
            if (maGeneration == generation) {
                ecartsDernierPassage = compteurs.ecarts
                pendancesDernierPassage = pendances
            }
            enVol = false
        }
    }

    /** Publie un événement à CHAQUE observateur (un fautif n'arrête rien). */
    private fun publier(evenement: EvenementIndexationXml) {
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
     * passage ; le breakdown n'est rempli qu'au final).
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — UN
     * paramètre par champ de [EvenementIndexationXml], l'assemblage
     * un-à-un est la lisibilité même (même exonération que le passeur
     * Kotlin).
     */
    @Suppress("LongParameterList")
    private fun evenement(
        phase: PhaseIndexationXml,
        faits: Int,
        total: Int,
        entreeCourante: String?,
        avertissements: List<String>,
        phaseDetaillee: PhaseDetailleeIndexationXml,
        etats: Map<String, EtatEntreeTravailXml>,
        reprises: Int,
        durees: DureesPhasesXml?,
        breakdown: List<BreakdownIndexeurXml>,
        bilan: BilanDernierPassageXml?,
        settled: Boolean,
    ): EvenementIndexationXml =
        EvenementIndexationXml(
            phase = phase,
            faits = faits,
            total = total,
            entreeCourante = entreeCourante,
            avertissements = avertissements.toList(),
            erreurs = emptyList(),
            phaseDetaillee = phaseDetaillee,
            listeTravail = etats.map { EntreeTravailXml(it.key, it.value) },
            breakdownIndexeurs = breakdown,
            dureesPhases = durees,
            bilanDernierPassage = bilan,
            settled = settled,
            reprises = reprises,
        )

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(PasseurIndexationXml::class.java.name)

        /** Nom de l'unité catalogue XE2 dans la liste de travail. */
        internal const val NOM_UNITE_CATALOGUE: String = "catalogue XE2 (plateforme)"

        /** Identifiants des indexeurs (breakdown — libellés affichables). */
        internal const val IDENTIFIANT_CATALOGUE: String = "catalogue-xe2"
        internal const val IDENTIFIANT_AAR: String = "aar-p3"
        internal const val IDENTIFIANT_RESSOURCES: String = "ressources-xe3"
    }
}
