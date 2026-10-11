package jo.codeide.lsp.host

import jo.codeide.lsp.index.ServiceIndexDisque
import jo.codeide.lsp.index.VueIndex
import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.DepotModeleLsp
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspjava.engine.CompilationEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * Orchestrateur de l'hôte (mission « LSP Java » étape 4) — applique un
 * résultat de classpath tooling (protocole v8, ADR 0113/0058) au TRIPTYQUE
 * modèle / index / moteurs :
 *
 * 1. **Modèle** : [ConvertisseurModeleLsp.depuisClasspathResult] →
 *    [ProjetLsp][jo.codeide.lsp.model.ProjetLsp] publié dans le
 *    [DepotModeleLsp] (instantanés immuables, empreintes W6) ;
 * 2. **Index** : UNE vue FUSION sur l'union des classpaths des modules
 *    (ADR 0111 §3 : l'union ordonnée des segments — jamais de gros index
 *    reconstruit ; deux modules partageant un jar partagent le MÊME
 *    segment, l'empreinte de contenu fait la déduplication) — les
 *    entrées `MODULE` ne sont pas indexées (identité du frère dans son
 *    propre instantané), les `DOSSIER` non plus en ce premier
 *    branchement (les sources du frère couvrent ses types, cf. point 3 ;
 *    évolution documentée) ;
 * 3. **Moteurs** : UN contexte PAR module via l'API qualifiée du lot E
 *    ([CompilationEnvironment.remplacerContexteModule]) — ses racines
 *    sources = les siennes + celles des frères déclarés en entrée
 *    `MODULE` (résolution en sources du frère, motif du lot C), son
 *    classpath = ses jars existants. Les modules `ignore` (variante
 *    non résolue…) ne reçoivent PAS de contexte (moteur vide au
 *    premier abord, invalidé à leur premier sync réel).
 *
 * **Budget de cache** (leçon du banc lot F2, §15.8 point 3) : dérivé du
 * modèle — la cible §9 « tas < 30 Mo / 100 jars » est un budget PAR
 * JAR. Le budget ne fait que CROÎTRE en cours de session : une
 * croissance au-delà du courant recrée le service (les segments
 * persistent sur disque — zéro ré-indexation), ce qui invalide les
 * moteurs (indexNoms changé) — honnête : le classpath a changé.
 *
 * Les sync REDONDANTS ne coûtent rien : empreinte de l'union inchangée
 * → même adaptateur → `setIndexNoms` idempotent → contextes
 * remplacés à l'identique → moteurs et caches conservés (sémantiques
 * lot E).
 *
 * TooManyFunctions justifié (règle 8) : chaque étape du triptyque
 * modèle/index/moteurs a SA fonction (union, alignement d'index,
 * poussée des moteurs, budget, racines sources, jars) — les extraire
 * plus loin noyerait l'ordre du pipeline.
 */
@Suppress("TooManyFunctions")
internal class OrchestrateurLsp(
    private val environnement: CompilationEnvironment,
    private val depot: DepotModeleLsp,
    private val stockage: File,
    private val scope: CoroutineScope,
    private val dispatcheurIo: CoroutineDispatcher,
    private val lecteurStats: LecteurStatistiquesFichier,
) {
    /** Résultat observable d'une application de classpath (journal + tests). */
    internal data class Application(
        val modulesActifs: Int,
        val modulesIgnores: Int,
        val entreesIndex: Int,
        val budgetCacheMo: Long,
        val unionChangee: Boolean,
    )

    private var service: ServiceIndexDisque? = null

    /** Vue fusion courante (remplacée quand l'union change). */
    private var vue: VueIndex? = null

    /** Adaptateur courant — REMPLACÉ seulement si la vue change. */
    private var adaptateur: AdaptateurIndexNoms? = null

    /** Construction courante de la vue (annulable avec la session). */
    private var construction: Job? = null

    /** Budget courant — ne croît jamais en cours de session. */
    private var budgetOctets: Long = ServiceIndexDisque.BUDGET_CACHE_PAR_DEFAUT

    /** Empreinte de l'union courante (détection de sync redondante). */
    private var empreinteUnion: String = ""

    /**
     * Applique un résultat de classpath complet (post-sync) : modèle,
     * index fusion, moteurs par module.
     */
    internal fun appliquer(resultat: ClasspathResult): Application {
        val projet = ConvertisseurModeleLsp.depuisClasspathResult(resultat, lecteurStats)
        depot.remplacer(projet)
        val entrees = unionIndexable(projet)
        val union =
            ClasspathInstantane(
                entrees = entrees.values.toList(),
                empreinte = empreinteDe(entrees.values.toList()),
            )
        val unionChangee = union.empreinte != empreinteUnion
        alignerIndex(entrees.size, union, unionChangee)
        val compteurs = pousserMoteurs(projet)
        return Application(
            modulesActifs = compteurs.first,
            modulesIgnores = compteurs.second,
            entreesIndex = entrees.size,
            budgetCacheMo = budgetOctets / OCTETS_PAR_MIO,
            unionChangee = unionChangee,
        )
    }

    /** Union ordonnée des entrées INDEXABLES de tous les modules (JAR/AAR, première occurrence gardée). */
    private fun unionIndexable(
        projet: jo.codeide.lsp.model.ProjetLsp,
    ): LinkedHashMap<String, EntreeClasspathInstantane> {
        val entrees = LinkedHashMap<String, EntreeClasspathInstantane>()
        for (module in projet.modules) {
            for (entree in module.classpath.entrees) {
                if (entree.kind == KindEntree.JAR || entree.kind == KindEntree.AAR) {
                    entrees.putIfAbsent(entree.empreinte, entree)
                }
            }
        }
        return entrees
    }

    /**
     * Recrée service/vue/adaptateur si nécessaire (budget croissant ou
     * union changée) — l'injection du NOUVEL adaptateur invalide les
     * moteurs, reconstruits paresseusement par `jdt(module)`.
     */
    private fun alignerIndex(
        nbEntrees: Int,
        union: ClasspathInstantane,
        unionChangee: Boolean,
    ) {
        val budgetVoulu = budgetPour(nbEntrees)
        val serviceARecree = service == null || budgetVoulu > budgetOctets
        if (serviceARecree) {
            budgetOctets = maxOf(budgetOctets, budgetVoulu)
            service =
                ServiceIndexDisque(
                    repertoire = stockage,
                    scope = scope,
                    dispatcheurIo = dispatcheurIo,
                    parallelisme = ServiceIndexDisque.parallelismeParDefaut(),
                    budgetCacheOctets = budgetOctets,
                )
        }
        if (serviceARecree || unionChangee) {
            val svc = service ?: error("service d'index absent après création")
            construction?.cancel()
            val nouvelleVue = svc.vuePour(union)
            val nouvelAdaptateur = AdaptateurIndexNoms(nouvelleVue, scope)
            vue = nouvelleVue
            adaptateur = nouvelAdaptateur
            empreinteUnion = union.empreinte
            environnement.setIndexNoms(nouvelAdaptateur)
            construction = svc.construire(nouvelleVue)
        }
    }

    /** Pousse UN contexte PAR module actif — rend (actifs, ignorés). */
    private fun pousserMoteurs(projet: jo.codeide.lsp.model.ProjetLsp): Pair<Int, Int> {
        val modulesParNom = projet.modules.associateBy { it.nom }
        var actifs = 0
        var ignores = 0
        for (module in projet.modules) {
            if (module.ignore) {
                ignores++
                continue
            }
            actifs++
            environnement.remplacerContexteModule(
                module.nom,
                racinesSourcesDe(module, modulesParNom),
                jarsDe(module),
            )
        }
        return Pair(actifs, ignores)
    }

    /**
     * Relance la construction de la vue (action « Réindexer ») : les
     * segments persistés sont REVALIDÉS (en-tête + W7), un segment
     * corrompu se reconstruit seul (auto-guérison ADR 0111 §4).
     */
    internal fun reindexer() {
        val vueCourante = vue ?: return
        construction?.cancel()
        construction = service?.construire(vueCourante)
    }

    /** État d'indexation de la vue fusion (flux consommé par l'UI). */
    internal fun etat(): VueIndex? = vue

    /** Purge les temporaires d'écritures interrompues (démarrage). */
    internal fun purgerTemporaires(): Int = service?.purgerTemporaires() ?: 0

    /** Budget dérivé du corpus (plancher = défaut ADR 0111). */
    private fun budgetPour(entrees: Int): Long =
        maxOf(
            ServiceIndexDisque.BUDGET_CACHE_PAR_DEFAUT,
            entrees * OCTETS_PAR_JAR,
        )

    /**
     * Racines sources d'un module : les siennes (principal + générés,
     * W5) + celles des frères déclarés en entrée `MODULE` (résolution
     * en sources du frère — motif du lot C ; un niveau, pas transitif :
     * le frère a SON propre moteur).
     *
     * LoopWithTooManyJumpStatements justifié (règle 8) : le continue du
     * frère introuvable et la garde de dédoublonnage sont le patron
     * même du parcours (précédent VueIndex.typesDansPaquet).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun racinesSourcesDe(
        module: ModuleLsp,
        modulesParNom: Map<String, ModuleLsp>,
    ): List<File> {
        val racines = ArrayList<File>()
        for (dossier in module.sources.principal + module.sources.generes) {
            racines.add(File(dossier))
        }
        for (entree in module.classpath.entrees) {
            if (entree.kind != KindEntree.MODULE) continue
            val frere = modulesParNom[entree.chemin]
            if (frere == null) {
                LOGGER.warning("entrée MODULE sans frère dans le modèle : ${entree.chemin} (module ${module.nom})")
                continue
            }
            for (dossier in frere.sources.principal) {
                racines.add(File(dossier))
            }
        }
        return racines
    }

    /** Jars EXISTANTS du module (le moteur ne lit que des jars). */
    private fun jarsDe(module: ModuleLsp): List<File> =
        module.classpath.entrees
            .filter { it.kind == KindEntree.JAR && it.existe }
            .map { File(it.chemin) }

    /** Empreinte sha256 des identités des entrées (clé de l'union). */
    private fun empreinteDe(entrees: List<EntreeClasspathInstantane>): String {
        val condense = MessageDigest.getInstance("SHA-256")
        for (entree in entrees) {
            condense.update("${entree.kind}|${entree.chemin}|${entree.empreinte}\n".toByteArray())
        }
        return condense.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger("OrchestrateurLsp")

        /** Budget par entrée indexée (512 Kio — leçon banc F2, §15.8). */
        private const val OCTETS_PAR_JAR: Long = 512L * 1024L

        private const val OCTETS_PAR_MIO: Long = 1024L * 1024L
    }
}
