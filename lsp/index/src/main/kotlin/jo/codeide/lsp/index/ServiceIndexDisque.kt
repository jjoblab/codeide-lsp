package jo.codeide.lsp.index

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service de l'index disque — construit en ARRIÈRE-PLAN les segments des
 * classpath (ADR 0111 §7 : priorité basse, parallélisme
 * `min(4, cœurs/2)`, ANNULABLE, REPRISE, progression par jar) et publie
 * les vues interrogables.
 *
 * Un même segment n'est construit qu'une fois par contenu (clé =
 * empreinte) : deux modules partageant une dépendance partagent le
 * segment — des constructions concurrentes du même contenu écrivent le
 * même fichier de façon atomique (dernier écrivain, contenu identique).
 *
 * Auto-guérison (ADR 0111 §4) : une corruption détectée à la lecture
 * (crc) reconstruit CE segment en arrière-plan — jamais de crash, jamais
 * d'index global reconstruit.
 */
public class ServiceIndexDisque internal constructor(
    internal val entrepot: EntrepotSegments,
    private val analyseur: AnalyseurClasspath,
    private val scope: CoroutineScope,
    private val dispatcheurIo: CoroutineDispatcher,
    private val parallelisme: Int,
    budgetCacheOctets: Long,
) {
    /** Constructeur système : stockage + analyseur réels. */
    public constructor(
        repertoire: File,
        scope: CoroutineScope,
        dispatcheurIo: CoroutineDispatcher,
        parallelisme: Int = parallelismeParDefaut(),
        budgetCacheOctets: Long = BUDGET_CACHE_PAR_DEFAUT,
    ) : this(
        entrepot = EntrepotSegments(repertoire),
        analyseur = AnalyseurClasspathSysteme(LecteurZipBrut()),
        scope = scope,
        dispatcheurIo = dispatcheurIo,
        parallelisme = parallelisme,
        budgetCacheOctets = budgetCacheOctets,
    )

    /** Cache LRU partagé (budget tas — mesurable par le banc). */
    private val cache = CacheSegments(budgetCacheOctets, entrepot)

    /** Positions par clé (auto-guérison : retrouver l'entrée d'un segment corrompu). */
    private val positionsParCle = Collections.synchronizedMap(HashMap<String, PositionSegment>())

    /** Reconstructions lancées (anti-rebond de l'auto-guérison). */
    private val reconstructionsEnCours = Collections.synchronizedSet(HashSet<String>())

    /**
     * Vue d'index d'un classpath — crée la position de chaque entrée
     * indexable (les entrées MODULE ne le sont PAS : l'identité du
     * module frère vit dans son propre snapshot, ADR 0111 §10 ; la
     * préséance des sources du module sur sa sortie compilée est réglée
     * par le consommateur).
     *
     * La vue est immédiatement interrogable (repli par sondage tant que
     * la construction n'a pas fini — ADR 0111 §8) ; appeler [construire]
     * ensuite.
     */
    public fun vuePour(classpath: ClasspathInstantane): VueIndex {
        val positions = classpath.entrees.mapNotNull { entree -> positionDe(entree) }
        val vue =
            VueIndex(
                positions = positions,
                cache = cache,
                reconstruction = { empreinte -> reconstruireCorrompu(empreinte) },
                etatInitial = EtatIndex.INITIAL.copy(total = positions.size),
            )
        positions.filter { it.cle().isNotEmpty() }.forEach { positionsParCle[it.cle()] = it }
        return vue
    }

    /**
     * Construit les segments de la vue en arrière-plan.
     *
     * @return le `Job` de construction (annulable — les positions déjà
     *         construites restent interrogables, l'état reste
     *         CONSTRUCTION : partial honnête, jamais de faux PRÊT).
     */
    public fun construire(vue: VueIndex): Job =
        scope.launch(dispatcheurIo) {
            construireInterne(vue)
        }

    /** Purge les `.tmp` d'écritures interrompues (au démarrage de l'hôte). */
    public fun purgerTemporaires(): Int = entrepot.purgerTemporaires()

    /**
     * Octets comptés dans le budget du cache LRU (transparence du budget
     * tas — affichable par l'hôte, mesuré par le banc).
     */
    public val octetsCache: Long get() = cache.octetsCourants

    /** Construction d'une vue (annulable, parallèle bornée). */
    private suspend fun construireInterne(vue: VueIndex) {
        val positions = vue.positionsInternes()
        val barriere = Semaphore(parallelisme.coerceAtLeast(1))
        val statistiques = StatistiquesConstruction(positions.size)
        vue.etatMutable.value = statistiques.etat(PhaseIndex.CONSTRUCTION, null)
        coroutineScope {
            positions
                .map { position ->
                    async {
                        barriere.withPermit {
                            construirePosition(vue, position, statistiques)
                        }
                    }
                }.awaitAll()
        }
        vue.etatMutable.value = statistiques.etat(phaseFinale(statistiques), null)
    }

    /** Construit (ou reprend) la position d'UNE entrée. */
    private suspend fun construirePosition(
        vue: VueIndex,
        position: PositionSegment,
        statistiques: StatistiquesConstruction,
    ) {
        currentCoroutineContext().ensureActive()
        vue.etatMutable.value = statistiques.etat(PhaseIndex.CONSTRUCTION, position.entree.chemin)
        val repriseValide: (String) -> Boolean = { cle ->
            entrepot.enTeteValide(cle, position.tailleValidation, position.mtimeValidation)
        }
        when (val resultat = analyseur.analyser(position.entree, repriseValide)) {
            is ResultatAnalyse.Reprise -> {
                adopter(position, resultat.cle)
                statistiques.reprise()
            }

            is ResultatAnalyse.Ok -> {
                try {
                    // v0.121.1 (retour CI du run 38412598199) : une session
                    // fermée n'écrit PLUS de segment — l'analyse ci-dessus est
                    // un travail bloquant (lecture de zip) qui ne voit
                    // l'annulation qu'ici ; écrire ensuite laisserait des
                    // fichiers apparaître APRÈS le retour de SessionLsp.close
                    // (course avec le nettoyage @TempDir des tests, fichiers
                    // fantômes en production). La CancellationException se
                    // propage (jamais avalée — règle 6) : la construction
                    // s'arrête honnêtement, l'état reste CONSTRUCTION.
                    currentCoroutineContext().ensureActive()
                    entrepot.ecrire(resultat.donnees, resultat.donnees.cle)
                    adopter(position, resultat.donnees.cle)
                    statistiques.terminee()
                } catch (e: IOException) {
                    // Stockage défaillant : entrée en erreur (état final
                    // ERREUR) — jamais de crash de la construction.
                    statistiques.erreur("écriture impossible (${position.entree.chemin}) : ${e.message}")
                }
            }

            is ResultatAnalyse.Illisible -> {
                statistiques.avertissement("${position.entree.chemin} : ${resultat.raison}")
            }
        }
        vue.etatMutable.value = statistiques.etat(PhaseIndex.CONSTRUCTION, null)
    }

    /** Position adopte sa clé définitive et devient prête. */
    private fun adopter(
        position: PositionSegment,
        cle: String,
    ) {
        position.adopter(cle)
        positionsParCle[cle] = position
    }

    /**
     * Reconstruction d'un segment CORROMPU (crc détecté à la lecture) —
     * ce segment seul, en arrière-plan, sans reconstruire la vue.
     */
    private fun reconstruireCorrompu(empreinte: String) {
        val position = positionsParCle[empreinte] ?: return
        if (!reconstructionsEnCours.add(empreinte)) {
            return
        }
        scope.launch(dispatcheurIo) {
            try {
                val resultat = analyseur.analyser(position.entree) { false }
                if (resultat is ResultatAnalyse.Ok) {
                    // v0.121.1 : même garde que construirePosition — une
                    // reconstruction lancée juste avant l'annulation n'écrit
                    // plus rien après celle-ci.
                    currentCoroutineContext().ensureActive()
                    entrepot.ecrire(resultat.donnees, resultat.donnees.cle)
                    cache.invaliderRefus(empreinte)
                    position.adopter(resultat.donnees.cle)
                }
            } finally {
                reconstructionsEnCours.remove(empreinte)
            }
        }
    }

    /** Phase finale selon les statistiques (prêt / partiel / erreur). */
    private fun phaseFinale(statistiques: StatistiquesConstruction): PhaseIndex =
        when {
            statistiques.erreurs.isNotEmpty() -> PhaseIndex.ERREUR
            statistiques.avertissements.isNotEmpty() -> PhaseIndex.PARTIEL
            else -> PhaseIndex.PRET
        }

    /** Position initiale d'une entrée du modèle (`null` si non indexable). */
    private fun positionDe(entree: EntreeClasspathInstantane): PositionSegment? {
        val type =
            when (entree.kind) {
                KindEntree.JAR -> TypeEntreeIndex.JAR
                KindEntree.AAR -> TypeEntreeIndex.ARR
                KindEntree.DOSSIER -> TypeEntreeIndex.DOSSIER
                KindEntree.MODULE -> return null
            }
        return PositionSegment(
            entree =
                EntreeAIndexer(
                    chemin = entree.chemin,
                    type = type,
                    empreinte = entree.empreinte,
                    portee = entree.portee,
                    taille = entree.taille,
                    mtime = entree.mtime,
                ),
            cle = if (type == TypeEntreeIndex.JAR) entree.empreinte else "",
        )
    }

    /** Comptes atomiques de la construction (progression par jar). */
    private class StatistiquesConstruction(
        val total: Int,
    ) {
        val faits = AtomicInteger()
        val reprises = AtomicInteger()
        val avertissements = ConcurrentLinkedQueue<String>()
        val erreurs = ConcurrentLinkedQueue<String>()

        fun reprise() {
            reprises.incrementAndGet()
            faits.incrementAndGet()
        }

        fun terminee() {
            faits.incrementAndGet()
        }

        fun avertissement(raison: String) {
            avertissements.add(raison)
        }

        fun erreur(raison: String) {
            erreurs.add(raison)
        }

        fun etat(
            phase: PhaseIndex,
            entreeCourante: String?,
        ): EtatIndex =
            EtatIndex(
                phase = phase,
                total = total,
                faits = faits.get(),
                entreeCourante = entreeCourante,
                reprises = reprises.get(),
                avertissements = avertissements.toList(),
                erreurs = erreurs.toList(),
            )
    }

    /** Compagnon public : emplacement par défaut et budget par défaut. */
    public companion object {
        /**
         * Emplacement par défaut du stockage partagé :
         * `~/.codeide/lsp/index/` (ADR 0111 §2 — aligné à l'étape 4 sur
         * le répertoire de l'hôte Android, chemin toujours injectable).
         */
        @JvmStatic
        public fun emplacementParDefaut(): File =
            File(File(File(System.getProperty("user.home"), ".codeide"), "lsp"), "index")

        /** Budget tas par défaut du cache LRU (30 Mo — ADR 0111 §6). */
        public const val BUDGET_CACHE_PAR_DEFAUT: Long = 30L * 1024L * 1024L

        /** Plafond du parallélisme de construction (ADR 0111 §7 : min(4, cœurs/2)). */
        private const val PARALLELISME_MAX: Int = 4

        /** Diviseur de cœurs (priorité basse — la moitié des cœurs). */
        private const val DIVISEUR_COEURS: Int = 2

        /** Parallélisme par défaut : `min(4, cœurs/2)` (ADR 0111 §7). */
        @JvmStatic
        public fun parallelismeParDefaut(): Int =
            minOf(PARALLELISME_MAX, Runtime.getRuntime().availableProcessors() / DIVISEUR_COEURS).coerceAtLeast(1)
    }
}
