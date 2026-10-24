package jo.codeide.lsp.host

import jo.codeide.lsp.index.ServiceIndexDisque
import jo.codeide.lsp.index.VueIndex
import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.DepotModeleLsp
import jo.codeide.lsp.model.Empreintes
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
 * JAR. En Auto, le budget ne fait que CROÎTRE en cours de session : une
 * croissance au-delà du courant recrée le service (les segments
 * persistent sur disque — zéro ré-indexation), ce qui invalide les
 * moteurs (indexNoms changé) — honnête : le classpath a changé. Depuis
 * le lot LSP-1 (ADR 0128), un PLAFOND EXPLICITE (ConfigurationLsp) borne
 * le dérivé et s'applique dans les DEUX sens (l'écran L3 propose
 * Auto / 256 / 512 / 1024 Mo).
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

    /** Plafond de budget EXPLICITE (lot LSP-1) — `null` = Auto (dérivé du corpus). */
    private var plafondBudget: Long? = null

    /** Dernière union alignée — rejouée par [appliquerPlafondBudget]. */
    private var derniereUnion: ClasspathInstantane? = null

    /** Nombre d'entrées de la dernière union alignée. */
    private var derniersNbEntrees: Int = 0

    /**
     * Applique un résultat de classpath complet (post-sync) : modèle,
     * index fusion, moteurs par module.
     */
    internal fun appliquer(resultat: ClasspathResult): Application =
        appliquerModele(ConvertisseurModeleLsp.depuisClasspathResult(resultat, lecteurStats))

    /**
     * Applique le pipeline complet depuis un modèle DÉJÀ construit —
     * v0.102.0 (lot h4, écart 2) : le MÊME triptyque modèle/index/moteurs
     * rejoué depuis un [ProjetLsp] PERSISTÉ (`.codeide/local/lsp-modele.json`,
     * W7) au lieu d'un ClasspathResult frais — la réouverture d'un projet
     * inchangé nourrit la session AVANT la revalidation silencieuse.
     * Idempotent par construction : un modèle identique à l'union courante
     * est un no-op chaud (sémantiques lot E).
     */
    internal fun appliquerModele(projet: jo.codeide.lsp.model.ProjetLsp): Application {
        depot.remplacer(projet)
        val entrees = unionIndexable(projet)
        val union =
            ClasspathInstantane(
                entrees = entrees.values.toList(),
                empreinte = empreinteDe(entrees.values.toList()),
            )
        val unionChangee = union.empreinte != empreinteUnion
        derniereUnion = union
        derniersNbEntrees = entrees.size
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

    /**
     * Union ordonnée des entrées INDEXABLES de tous les modules (JAR/AAR,
     * première occurrence gardée) — v0.102.0 (lot h4 écart 1) : la
     * PLATEFORME de chaque module Android (androidJar, ADR 0112) y vit
     * AUSSI. L'index disque PRÊT est AUTORITATIF côté moteur (ADR 0111 §8
     * : négatif DÉFINITIF, la sonde mémoire est sautée) : un jar du
     * classpath moteur HORS union y est INVISIBLE — sans cette entrée,
     * android.* restait « cannot be resolved » même après sync. Un même
     * android.jar partagé par plusieurs modules ne produit QU'UN segment
     * (déduplication par empreinte de contenu).
     *
     * LoopWithTooManyJumpStatements justifié (règle 8) : les continue du
     * module ignoré et de la plateforme absente sont le patron même du
     * parcours (précédent racinesSourcesDe, même fichier).
     */
    @Suppress("LoopWithTooManyJumpStatements")
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
        for (module in projet.modules) {
            if (module.ignore) continue
            val plateforme = module.androidJar ?: continue
            if (!lecteurStats.existe(plateforme)) continue
            val empreinte = Empreintes.empreinteFichier(plateforme, lecteurStats)
            entrees.putIfAbsent(
                empreinte,
                EntreeClasspathInstantane(
                    kind = KindEntree.JAR,
                    chemin = plateforme,
                    portee = null,
                    sources = null,
                    aar = null,
                    empreinte = empreinte,
                    existe = true,
                    taille = lecteurStats.taille(plateforme),
                    mtime = lecteurStats.mtime(plateforme),
                ),
            )
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
        // Lot LSP-1 : avec un plafond EXPLICITE, toute différence du budget
        // cible recrée le service (le réglage a été DEMANDÉ, croissant ou
        // décroissant) ; en Auto, le contrat historique tient (croissance
        // seulement — une sync redondante ne recrée jamais rien).
        val serviceARecree =
            service == null || budgetVoulu > budgetOctets ||
                (plafondBudget != null && budgetVoulu != budgetOctets)
        if (serviceARecree) {
            // Lot LSP-1 : en Auto le budget ne croît jamais (contrat
            // historique) ; avec un plafond explicite, le budget DEMANDÉ
            // s'applique tel quel (croissant ou décroissant).
            budgetOctets =
                if (plafondBudget != null) {
                    budgetVoulu
                } else {
                    maxOf(budgetOctets, budgetVoulu)
                }
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

    /**
     * Pousse UN contexte PAR module actif — rend (actifs, ignorés).
     *
     * S1-a : le niveau Java DU module ([ModuleLsp.compatibiliteSource],
     * normalisé « 8 »/« 11 »/« 17 »/« 21 » ou `null` par le convertisseur)
     * accompagne ses listes — un module Java 8 reçoit les diagnostics de
     * SON niveau (var refusé), un module 21 les siens (records/switch).
     * `null` = retour explicite au niveau par défaut du moteur (l'outil
     * de build n'a pas su lire le niveau de CE module).
     */
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
                module.compatibiliteSource,
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

    /**
     * Annule le passage de construction EN VOL (ADR 0214 — demande D5
     * de CodeIDE) : le `Job` structuré meurt au prochain point
     * coopératif (`ensureActive` entre les entrées) et le service
     * publie PARTIEL « annulé par l'utilisateur ».
     *
     * @return `false` si aucune construction n'est en vol.
     */
    internal fun annulerPassage(): Boolean {
        val courante = construction
        if (courante?.isActive == true) {
            courante.cancel()
            return true
        }
        return false
    }

    /**
     * Reprise ciblée des entrées ÉCARTÉES du dernier passage (ADR 0214
     * — demande D4 de CodeIDE) : SEULES les entrées marquées ECARTE
     * sont relancées (les saines gardent leur segment), les
     * statistiques sont semées — la reprise est visible et bon marché.
     * Refusée pendant une construction en vol (elle travaille déjà).
     *
     * @return `false` sans vue, sans écart à retenter, ou en vol.
     *
     * Exemption detekt ciblée (règle 8 : exception commentée) :
     * ReturnCount — les issues sont les REFUS distincts du contrat D4
     * (sans vue, en vol, rien à retenter) plus le lancement.
     */
    @Suppress("ReturnCount")
    internal fun reesayerEchecs(): Boolean {
        val vueCourante = vue ?: return false
        if (construction?.isActive == true) return false
        val reprise = service?.reesayerEchecs(vueCourante) ?: return false
        construction = reprise
        return true
    }

    /**
     * Plafond de budget explicite (lot LSP-1, ADR 0128 §4) — rejoue
     * l'alignement de l'union COURANTE : le service est recréé si le
     * budget cible change (croissance OU décroissance), la construction
     * repart des segments persistés (zéro ré-indexation, ADR 0111 §3).
     * Sans union alignée (avant le premier sync), le plafond est RETENU :
     * le premier alignement l'appliquera.
     */
    internal fun appliquerPlafondBudget(nouveauPlafond: Long?) {
        plafondBudget = nouveauPlafond
        val union = derniereUnion ?: return
        alignerIndex(derniersNbEntrees, union, unionChangee = false)
    }

    /** Taille sur DISQUE de l'entrepôt de segments (lot LSP-1, écran L3). */
    internal fun tailleCacheOctets(): Long = service?.tailleStockage() ?: ServiceIndexDisque.tailleStockageDe(stockage)

    /** Une construction d'index est-elle EN VOL (refus du vidage, lot LSP-1). */
    internal fun constructionActive(): Boolean = construction?.isActive == true

    /**
     * Vide l'entrepôt de segments SUR DISQUE + le cache LRU mémoire (lot
     * LSP-1, écran L3 « Vider le cache ») — retour (segments supprimés,
     * octets libérés). La construction courante est ANNULÉE avant la
     * purge ; le refus « indexation en vol » est géré par l'appelant
     * (SessionLsp.viderCache refuse AVANT d'ici).
     */
    internal fun viderCache(): Pair<Int, Long> {
        construction?.cancel()
        val serviceCourant = service
        return if (serviceCourant != null) {
            serviceCourant.viderStockage()
        } else {
            ServiceIndexDisque.viderStockageDe(stockage)
        }
    }

    /** État d'indexation de la vue fusion (flux consommé par l'UI). */
    internal fun etat(): VueIndex? = vue

    /** Purge les temporaires d'écritures interrompues (démarrage). */
    internal fun purgerTemporaires(): Int = service?.purgerTemporaires() ?: 0

    /**
     * Budget dérivé du corpus (plancher = défaut ADR 0111), BORNÉ par le
     * plafond explicite du lot LSP-1 quand il existe (Auto = sans borne).
     */
    private fun budgetPour(entrees: Int): Long {
        val derive =
            maxOf(
                ServiceIndexDisque.BUDGET_CACHE_PAR_DEFAUT,
                entrees * OCTETS_PAR_JAR,
            )
        return minOf(derive, plafondBudget ?: Long.MAX_VALUE)
    }

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

    /**
     * Jars EXISTANTS du module (le moteur ne lit que des jars) — v0.102.0
     * (lot h4, écart 1) : la plateforme `android.jar` du module (ADR 0112 —
     * champ du modèle v8, JAMAIS consommé jusqu'ici) rejoint le classpath
     * de SON moteur : sans elle, `Activity`/`Bundle`/`TextView` restaient
     * « cannot be resolved » même après sync. La garde d'existence est
     * celle de [remplacerContexteModule] (chemins filtrés) — un SDK
     * absent laisse le moteur dégradé, jamais faux.
     */
    private fun jarsDe(module: ModuleLsp): List<File> {
        val jars =
            module.classpath.entrees
                .filter { it.kind == KindEntree.JAR && it.existe }
                .map { File(it.chemin) }
                .toMutableList()
        module.androidJar?.let { jars.add(File(it)) }
        return jars
    }

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
