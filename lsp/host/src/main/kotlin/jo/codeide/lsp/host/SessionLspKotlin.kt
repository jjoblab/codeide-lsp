package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.KindEntree
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.EtatEntreeTravailKotlin
import jo.lspkotlin.api.EvenementIndexationKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.PhaseIndexationKotlin
import jo.lspkotlin.api.PreviewKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.LanguageServer
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Façade de la session LSP Kotlin d'un projet (K2 → K3, LSP_KOTLIN.md
 * §5) — miroir volontairement MINCE de [SessionLsp] (Java) : même
 * frontière nette, même cycle de vie piloté par le ViewModel, même
 * transport in-process cel-lsp (ADR 0109 D2 — un changement de
 * transport se brancherait ici sans toucher au moteur ni à l'éditeur).
 *
 * Le moteur est résolu par le SPI ServiceLoader ([MoteurLangageKotlin],
 * D6 : c'est le SEUL type du moteur visible de l'hôte — jamais
 * l'implémentation concrète, jamais le parseur) ; chaque connexion LSP
 * reçoit sa PROPRE session moteur ([serveur]), les sessions vivantes
 * sont suivies et [close] referme celles qu'une connexion n'aurait pas
 * arrêtées (contrat « 0 ressource orpheline » du prompt 3).
 *
 * K3 : la façade NOURRIT les sessions — [appliquerClasspath] (post-sync
 * utile) et [appliquerModele] (réouverture, modèle persisté W7)
 * convertissent le résultat tooling en entrées NEUTRES (jars/AAR
 * d'existence vérifiée, racines sources, vue [VueClasspathKotlin]
 * fournie par la session Java qui possède l'index fusion — UNE seule
 * vue, un seul budget de cache, D4) et poussent l'état aux sessions
 * vivantes ; les sessions FUTURES ([serveur]) naissent nourries.
 *
 * @param racineProjet racine du projet ouvert (identité de la session).
 * @param moteur moteur de langue (SPI) — injectable pour les tests,
 *        résolution ServiceLoader par défaut (échec explicite si le
 *        module n'est pas branché : erreur de build, pas fantôme).
 * @param lecteurStats couture de statistiques (existence des entrées),
 *        injectable pour les tests.
 * @param sessionJava session Java du MÊME projet (lot L1 du plan 1.3.0) —
 *        quand elle est fournie, la façade s'inscrit à son surveillant
 *        de fichiers : les événements externes (`.kt`/`.kts` modifiés
 *        hors éditeur) atteignent les serveurs Kotlin VIVANTS par le
 *        MÊME chemin que les serveurs Java. `null` = façade autonome
 *        (les événements externes ne la touchent pas — tests, usage
 *        hors projet).
 */
@Suppress("LongParameterList", "TooManyFunctions")
public class SessionLspKotlin(
    racineProjet: File,
    private val moteur: MoteurLangageKotlin = ServiceMoteurLangageKotlin.unique(),
    private val lecteurStats: LecteurStatistiquesFichier = LecteurStatistiquesFichierDisque(),
    private val dossierIndex: File? =
        jo.codeide.lsp.index.ServiceIndexDisque
            .emplacementParDefaut(),
    sessionJava: SessionLsp? = null,
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Sessions moteur des connexions vivantes (fermées à [close] aussi). */
    private val sessionsVivantes = CopyOnWriteArrayList<SessionLangageKotlin>()

    /**
     * Serveurs LSP4J des connexions vivantes (lot L1) — la destination
     * des événements de fichiers externes quand la façade est branchée
     * sur le surveillant de la session Java ; chaque serveur se retire
     * à son arrêt (aLArret).
     */
    private val serveursVivants = CopyOnWriteArrayList<KotlinLanguageServer>()

    /** Classpath courant (poussé aux sessions futures à la création). */
    @Volatile
    private var classpathCourant: ClasspathKotlin? = null

    /**
     * Poignée de désinscription au surveillant de la session Java (lot
     * L1) — fermée à [close] : un destinataire mort ne reçoit plus rien.
     */
    private val desinscriptionSurveillance: Closeable? =
        sessionJava?.enregistrerDestinataireFichiers(::diffuserEvenementsFichiers)

    /**
     * Miroir public de l'état d'indexation du MOTEUR Kotlin (ADR 0213 —
     * demande D3 de CodeIDE, écran multi-moteurs) : le MÊME type public
     * que [SessionLsp.etatIndex] côté Java, alimenté par les événements
     * du moteur (phases, liste de travail, breakdown, bilan) — l'agrégat
     * CodeIDE branche le flux SANS autre changement côté hôte.
     */
    private val etatIndexationMutable = MutableStateFlow(EtatIndexation.INITIAL)

    /** État d'indexation Kotlin observable (type public commun aux trois langues). */
    public val etatIndexation: StateFlow<EtatIndexation>
        get() = etatIndexationMutable.asStateFlow()

    /**
     * Désinscription de l'observateur moteur (ADR 0213) — fermée à
     * [close] : une façade morte ne reçoit plus d'événements. Un pack
     * ancien rend la poignée vide du SPI (aucun événement — les
     * FENÊTRES de [appliquerModele] prennent le relais).
     */
    private val desinscriptionObservateur: Closeable =
        try {
            moteur.enregistrerObservateurIndexation { evenement ->
                etatIndexationMutable.value = traduire(evenement)
                // ADR 0211/0213 : la passe ABOUTIT (PRET/PARTIEL final) —
                // les documents ouverts pendant la fenêtre de
                // sous-lecture se REPUBLIENT avec les VRAIS diagnostics
                // (le client reçoit la vérité dès qu'elle existe).
                if (evenement.phase == jo.lspkotlin.api.PhaseIndexationKotlin.PRET ||
                    evenement.phase == jo.lspkotlin.api.PhaseIndexationKotlin.PARTIEL
                ) {
                    republierServeursVivants()
                }
            }
        } catch (t: Throwable) {
            // Pack exotique : fenêtres seules (le monde d'avant).
            LOGGER.log(Level.WARNING, "observateur d'indexation refusé par le moteur (fenêtres seules)", t)
            java.io.Closeable { }
        }

    /**
     * Republie les documents ouverts de CHAQUE serveur vivant (ADR
     * 0211/0213) — un serveur fautif n'empêche pas les autres (miroir
     * de [diffuserEvenementsFichiers]).
     */
    private fun republierServeursVivants() {
        for (serveur in serveursVivants) {
            try {
                serveur.republierDocumentsOuverts()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "republication d'un serveur Kotlin échouée (ignorée)", t)
            }
        }
    }

    private var fermee = false

    /**
     * Diffusion des événements de fichiers externes aux serveurs
     * VIVANTS (lot L1) — chaque poussée est gardée : un serveur en
     * arrêt n'empêche pas les autres (miroir de
     * [SessionLsp.pousserEvenementsFichiers]).
     */
    private fun diffuserEvenementsFichiers(params: DidChangeWatchedFilesParams) {
        for (serveur in serveursVivants) {
            try {
                serveur.workspaceService.didChangeWatchedFiles(params)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "événements de fichiers à un serveur Kotlin échoués (ignorés)", t)
            }
        }
    }

    /**
     * UN serveur LSP4J pour une connexion (cel-lsp approvisionne par le
     * supplier du transport) : la session moteur est NEUVE et suivie —
     * shutdown/exit la referme et la retire, [close] rattrape les autres.
     * La session naît NOURRIE du classpath courant s'il y en a un (K3).
     */
    public fun serveur(): LanguageServer {
        synchronized(this) {
            check(!fermee) { "SessionLspKotlin fermée — créer une nouvelle session" }
        }
        val sessionLangage = moteur.creerSession()
        classpathCourant?.let { sessionLangage.definirClasspath(it) }
        sessionsVivantes.add(sessionLangage)
        // Le rappel aLArret capture le serveur par variable locale —
        // affectée une fois le serveur construit (même patron que
        // [SessionLsp.serveur] côté Java).
        var inscrit: KotlinLanguageServer? = null
        val serveur =
            KotlinLanguageServer(
                session = sessionLangage,
                versionServeur = moteur.version,
                aLArret = {
                    sessionsVivantes.remove(sessionLangage)
                    inscrit?.let { vivant -> serveursVivants.remove(vivant) }
                },
            )
        inscrit = serveur
        serveursVivants.add(serveur)
        return serveur
    }

    /**
     * Les définitions cel-lsp du serveur Kotlin de CodeIDE — à enregistrer
     * dans le `LspProject` de l'espace (une par extension routée :
     * `.kt` et `.kts` — le mode script est déduit de l'URI côté serveur).
     */
    public fun definitions(): List<LanguageServerDefinition> =
        listOf(
            DefinitionServeurLspKotlin(EXTENSION_KT) { serveur() },
            DefinitionServeurLspKotlin(EXTENSION_SCRIPT) { serveur() },
        )

    /**
     * Applique un résultat de classpath POST-SYNC (K3) : conversion
     * complète (modèle v8 → entrées neutres + racines sources), la VUE
     * de classpath est fournie par l'appelant (la session Java qui
     * possède l'index fusion — [SessionLsp.vueClasspathKotlin]).
     *
     * Tolérant : vue `null` = régime dégradé (symboles projet seuls) ;
     * les entrées INEXISTANTES sont filtrées ici (le moteur n'a pas à
     * deviner) ; un appel REDONDANT est un no-op chaud (segments par
     * empreinte côté moteur).
     *
     * @param resultat résultat BRUT du protocole tooling (post-sync).
     * @param vue vue de classpath neutre (peut être null avant le
     *        premier index PRÊT).
     */
    public fun appliquerClasspath(
        resultat: ClasspathResult,
        vue: jo.lspkotlin.api.VueClasspathKotlin?,
    ) {
        val projet = ConvertisseurModeleLsp.depuisClasspathResult(resultat, lecteurStats)
        appliquerModele(projet, vue)
    }

    /**
     * Applique un modèle DÉJÀ construit (K3, réouverture : le modèle
     * PERSISTÉ W7 de la session Java — même pipeline que
     * [SessionLsp.nourrirDepuisModelePersiste] côté Java) : la session
     * Kotlin retrouve ses entrées QUASI INSTANTANÉMENT, la sync fraîche
     * remplacera si l'empreinte a changé.
     */
    public fun appliquerModele(
        projet: ProjetLsp,
        vue: jo.lspkotlin.api.VueClasspathKotlin?,
    ) {
        synchronized(this) {
            if (fermee) return
            val entrees = entreesIndexables(projet)
            val racines = racinesSources(projet)
            val classpath =
                ClasspathKotlin(
                    entrees = entrees,
                    vue = vue,
                    racinesSources = racines,
                    dossierIndex = dossierIndex,
                )
            classpathCourant = classpath
        }
        // ADR 0211 (demande D1) : nourrissage MOTEUR une fois par sync,
        // sur le fil dédié du moteur — le supplier de connexion ne paie
        // plus RIEN. `false` = pack ancien sans nourrissage moteur : la
        // poussée SYNCHRONE ci-dessous reste LE nourrissage (monde
        // d'avant, tolérance de version).
        val prisEnCharge =
            try {
                moteur.definirClasspath(classpathCourant!!)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "nourrissage moteur refusé (poussée par session)", t)
                false
            }
        // ADR 0213 (demande D3) : fenêtre CONSTRUCTION au départ de sync
        // — le premier événement moteur (s'il y en a) reprend la main.
        etatIndexationMutable.value =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        // Poussé HORS verrou : les sessions moteur sérialisent en
        // interne, la façade reste responsive (le push est idempotent).
        sessionsVivantes.forEach { session ->
            try {
                session.definirClasspath(classpathCourant!!)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "poussée de classpath à une session moteur échouée (ignorée)", t)
            }
        }
        if (!prisEnCharge) {
            // Pack ancien : la poussée ci-dessus a nourri SYNCHRONEMENT —
            // fenêtre PRET honnête (jamais de PARTIEL inventé, le moteur
            // n'est pas observable).
            etatIndexationMutable.value =
                EtatIndexation(
                    phase = PhaseIndexation.PRET,
                    faits = 0,
                    total = 0,
                    entreeCourante = null,
                    avertissements = emptyList(),
                    erreurs = emptyList(),
                )
        }
    }

    /**
     * Annule le passage d'indexation Kotlin EN VOL (ADR 0214 — demande
     * D5) : drapeau coopératif, le passage publie son acquis et se
     * marque PARTIEL « annulé par l'utilisateur ».
     *
     * @return `false` si aucun passage n'est en vol (ou pack ancien).
     */
    public fun annulerPassage(): Boolean =
        try {
            moteur.annulerPassage()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "annulation refusée par le moteur (ignorée)", t)
            false
        }

    /**
     * Retente les ÉCHECS du dernier passage Kotlin SEULEMENT (ADR 0214
     * — demande D4) : les entrées saines sont reprises du cache sans
     * re-lecture (REPRIS massifs visibles à l'écran), seuls les écarts
     * et les pendances d'une annulation sont re-payés.
     *
     * @return `false` s'il n'y a rien à retenter (ou pack ancien).
     */
    public fun reesayerEchecs(): Boolean =
        try {
            moteur.reesayerEchecs()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "reprise des échecs refusée par le moteur (ignorée)", t)
            false
        }

    /**
     * Traduit un événement moteur (types neutres du pack, ADR 0213) en
     * [EtatIndexation] public — MÊME patron de découplage que
     * [SessionLsp.traduireEtatIndex] côté Java (l'UI ne voit que les
     * types publics de `lsp:host`).
     *
     * Exemption detekt ciblée (règle 8 : exception commentée) :
     * LongMethod/CyclomaticComplexMethod — la traduction EXHAUSTIVE de
     * tous les champs de l'événement (les `when` sur les enums
     * compris) est par construction longue, miroir assumé de
     * `traduireEtatIndex` (SessionLsp) : la découper casserait la
     * lisibilité UN-À-UN sans réduire la complexité réelle.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun traduire(evenement: EvenementIndexationKotlin): EtatIndexation =
        EtatIndexation(
            phase =
                when (evenement.phase) {
                    jo.lspkotlin.api.PhaseIndexationKotlin.INITIAL -> PhaseIndexation.INITIAL
                    jo.lspkotlin.api.PhaseIndexationKotlin.CONSTRUCTION -> PhaseIndexation.CONSTRUCTION
                    jo.lspkotlin.api.PhaseIndexationKotlin.PRET -> PhaseIndexation.PRET
                    jo.lspkotlin.api.PhaseIndexationKotlin.PARTIEL -> PhaseIndexation.PARTIEL
                    jo.lspkotlin.api.PhaseIndexationKotlin.ERREUR -> PhaseIndexation.ERREUR
                },
            faits = evenement.faits,
            total = evenement.total,
            entreeCourante = evenement.entreeCourante,
            avertissements = evenement.avertissements,
            erreurs = evenement.erreurs,
            phaseDetaillee =
                when (evenement.phaseDetaillee) {
                    jo.lspkotlin.api.PhaseDetailleeIndexationKotlin.AUCUNE -> {
                        PhaseDetailleeIndexation.AUCUNE
                    }

                    jo.lspkotlin.api.PhaseDetailleeIndexationKotlin.BIBLIOTHEQUES -> {
                        PhaseDetailleeIndexation.BIBLIOTHEQUES
                    }

                    jo.lspkotlin.api.PhaseDetailleeIndexationKotlin.SOURCES -> {
                        PhaseDetailleeIndexation.SOURCES
                    }
                },
            listeTravail =
                evenement.listeTravail.map { entree ->
                    EntreeTravailIndexation(
                        nom = entree.nom,
                        etat =
                            when (entree.etat) {
                                EtatEntreeTravailKotlin.EN_ATTENTE -> EtatEntreeTravailIndexation.EN_ATTENTE
                                EtatEntreeTravailKotlin.ACTIF -> EtatEntreeTravailIndexation.ACTIF
                                EtatEntreeTravailKotlin.TERMINE -> EtatEntreeTravailIndexation.TERMINE
                                EtatEntreeTravailKotlin.REPRIS -> EtatEntreeTravailIndexation.REPRIS
                                EtatEntreeTravailKotlin.ECARTE -> EtatEntreeTravailIndexation.ECARTE
                            },
                    )
                },
            breakdownIndexeurs =
                evenement.breakdownIndexeurs.map { breakdown ->
                    BreakdownIndexeurIndexation(
                        identifiant = breakdown.identifiant,
                        libelle = breakdown.libelle,
                        millisecondesCumulees = breakdown.millisecondesCumulees,
                        nombreEntrees = breakdown.nombreEntrees,
                    )
                },
            dureesPhases =
                evenement.dureesPhases?.let { durees ->
                    DureesPhasesIndexation(
                        bibliothequesMs = durees.bibliothequesMs,
                        sourcesMs = durees.sourcesMs,
                    )
                },
            bilanDernierPassage =
                evenement.bilanDernierPassage?.let { bilan ->
                    BilanDernierPassageIndexation(
                        dureeMs = bilan.dureeMs,
                        artefactsTotal = bilan.artefactsTotal,
                        artefactsRepris = bilan.artefactsRepris,
                        artefactsReconstruits = bilan.artefactsReconstruits,
                        fichiersParcourus = bilan.fichiersParcourus,
                        fichiersReanalyses = bilan.fichiersReanalyses,
                        indexeurPlusCouteux = bilan.indexeurPlusCouteux,
                        horodatageMs = bilan.horodatageMs,
                    )
                },
            settled = evenement.settled,
            reprises = evenement.reprises,
        )

    /**
     * Union des entrées JAR/AAR EXISTANTES de tous les modules +
     * plateforme android.jar des modules actifs (miroir volontairement
     * MINCE d'unionIndexable/jarsDe de l'orchestrateur Java — mêmes
     * sémantiques : première occurrence gardée, existence vérifiée ;
     * le moteur Kotlin consomme l'UNION, pas de contexte par module à
     * K3 — documenté LSP_KOTLIN §4.2, le raffinement par module suit
     * les retours).
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod/
     * LoopWithTooManyJumpStatements — MIROIR d'unionIndexable de
     * l'orchestrateur Java (mêmes gardes : kind, existence, ignore,
     * plateforme absente — mêmes exemptions que ladite fonction).
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun entreesIndexables(projet: ProjetLsp): List<EntreeClasspathKotlin> {
        val entrees = LinkedHashMap<String, EntreeClasspathKotlin>()
        for (module in projet.modules) {
            for (entree in module.classpath.entrees) {
                if (entree.kind != KindEntree.JAR && entree.kind != KindEntree.AAR) continue
                if (!entree.existe || !lecteurStats.existe(entree.chemin)) continue
                entrees.putIfAbsent(
                    entree.empreinte,
                    EntreeClasspathKotlin(
                        kind = if (entree.kind == KindEntree.AAR) KindEntreeKotlin.AAR else KindEntreeKotlin.JAR,
                        chemin = entree.chemin,
                        empreinte = entree.empreinte,
                    ),
                )
            }
        }
        for (module in projet.modules) {
            if (module.ignore) continue
            val plateforme = module.androidJar ?: continue
            if (!lecteurStats.existe(plateforme)) continue
            val empreinte =
                jo.codeide.lsp.model.Empreintes
                    .empreinteFichier(plateforme, lecteurStats)
            entrees.putIfAbsent(
                empreinte,
                EntreeClasspathKotlin(KindEntreeKotlin.JAR, plateforme, empreinte),
            )
        }
        return entrees.values.toList()
    }

    /**
     * Racines sources de l'UNION (principal + générés — les TESTS
     * suivront avec le raffinement par module ; les sources du frère
     * transitent par le classpath, K3 sous-rapporte sciemment).
     */
    private fun racinesSources(projet: ProjetLsp): List<File> {
        val racines = LinkedHashSet<File>()
        for (module in projet.modules) {
            if (module.ignore) continue
            (module.sources.principal + module.sources.generes).forEach { racines.add(File(it)) }
        }
        return racines.toList()
    }

    /**
     * Previews Compose d'un document (C1 — découverte `@Preview
     * @Composable` consommée par la carte « goutte d'eau » de l'éditeur) :
     * passe-plat vers les sessions moteur VIVANTES — la session qui
     * détient le document ouvert répond, les autres rendent VIDE
     * (première réponse non vide emportée). Aucune session vivante ou
     * pack antérieur C1 (défaut vide du SPI) : liste VIDE, jamais
     * d'exception — le consommateur dégrade honnêtement (pas de carte
     * d'aperçu Compose).
     *
     * @param uri identité du document ouvert dans l'éditeur.
     * @return les previews dans l'ordre du document ; liste VIDE sans
     *         session détenant le document ou sans `@Preview`.
     */
    public fun previews(uri: String): List<PreviewKotlin> {
        for (session in sessionsVivantes) {
            try {
                val resultat = session.previews(uri)
                if (resultat.isNotEmpty()) return resultat
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "previews d'une session moteur échouées (ignorée)", t)
            }
        }
        return emptyList()
    }

    /** Arrêt exhaustif : sessions des connexions jamais arrêtées refermées,
     * décompte vidé, désinscription au surveillant de la session Java
     * (lot L1 — la façade morte ne reçoit plus d'événements) et à
     * l'observateur d'indexation du moteur (ADR 0213).
     * Idempotente (le Java l'est aussi — contrat h1). */
    public override fun close() {
        synchronized(this) {
            if (fermee) return
            fermee = true
        }
        desinscriptionSurveillance?.let { desinscription ->
            try {
                desinscription.close()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "désinscription du surveillant échouée (ignorée)", t)
            }
        }
        try {
            desinscriptionObservateur.close()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "désinscription de l'observateur d'indexation échouée (ignorée)", t)
        }
        serveursVivants.clear()
        sessionsVivantes.forEach { session ->
            try {
                session.close()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "fermeture d'une session moteur échouée (ignorée)", t)
            }
        }
        sessionsVivantes.clear()
    }

    /** Définition cel-lsp in-process du serveur Kotlin (une extension). */
    private class DefinitionServeurLspKotlin(
        private val extension: String,
        private val fabrique: () -> LanguageServer,
    ) : LanguageServerDefinition(extension, NOM) {
        public override fun createConnectionProvider(uri: String): StreamConnectionProvider =
            InProcessStreamConnectionProvider(fabrique)
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(SessionLspKotlin::class.java.name)

        /** Extensions et nom du serveur (langage Kotlin, K2/K3). */
        private const val EXTENSION_KT: String = "kt"
        private const val EXTENSION_SCRIPT: String = "kts"
        private const val NOM: String = "codeide-kotlin"
    }
}
