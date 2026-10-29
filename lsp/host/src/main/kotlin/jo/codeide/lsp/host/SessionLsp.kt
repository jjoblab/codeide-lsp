package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.model.AssociationFichier
import jo.codeide.lsp.model.DepotModeleLsp
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.PersistanceModeleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.lsp.java.EnvironnementCompilation
import jo.codeide.lsp.java.protocole.ServeurLangageJava
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.LanguageServer
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Session LSP d'un projet (mission « LSP Java » étape 4, ADR 0109 D2) —
 * l'interface aux FRONTIÈRES NETTES derrière laquelle le moteur est
 * isolé : démarrer/arrêter, requêtes (le serveur LSP4J), flux d'état
 * (modèle, indexation).
 *
 * **Transport abstrait** : [definition] livre le branchement cel-lsp
 * IN-PROCESS (un [JavaLanguageServer] par connexion, approvisionné par
 * le supplier — [InProcessStreamConnectionProvider]). C'est UNE
 * implémentation ; un transport par socket vers un sous-processus JDK
 * (plan B mesuré, ADR 0109 point 3) se brancherait sur la MÊME façade
 * sans toucher au moteur ni au consommateur — `lsp:host` est le point
 * unique de changement de transport.
 *
 * **Cycle de vie** : la session possède son scope de coroutines
 * (SupervisorJob) — [close] annule collecte d'état et constructions
 * d'index, dispose les moteurs (défaut + modules, lot E), vide le
 * modèle. Le test de fuite ([SessionLspTest]) prouve le contrat
 * « 0 ressource orpheline » (prompt 3) : le moteur garde l'index par
 * champ FINAL (constat du banc §15.8), l'hôte lâche donc TOUT.
 *
 * @param racineProjet racine du projet ouvert (un `LspProject` cel-lsp
 *        par projet côté h2 — le serveur reste mono-workspace).
 * @param stockageIndex répertoire du stockage partagé (défaut
 *        `~/.codeide/lsp/index`, ADR 0111 §2 — injectable par l'app).
 * @param jdkHome JDK du terminal (D5 : image jrt + src.zip pour
 *        `java.*`) — `null` = `java.home` du runtime.
 * @param androidJar plateforme Android installée par le bootstrap (lot h4
 *        écart 1, v0.102.0) — le `android.jar` du SDK du TERMINAL rejoint
 *        le contexte PAR DÉFAUT dès l'ouverture : `android.*` ET `java.*`
 *        (le boot classpath embarque les deux) résolvent AVANT toute
 *        sync, comme Android Studio branche `android.jar` dès la config
 *        SDK. `null`/absent : moteur par défaut en repli (mode instantané
 *        v0.101.0) — le didOpen annonce son régime, jamais de mensonge.
 *        Les moteurs PAR MODULE ne dépendent PAS de ce paramètre : leur
 *        plateforme vient du modèle Gradle (`ModuleLsp.androidJar`, ADR
 *        0112) à la sync suivante.
 * @param parentJob parent optionnel des coroutines de la session
 *        (l'app peut tout arrêter d'un coup) ; la session reste
 *        responsable de son arrêt PROPRE via [close].
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — le constructeur
 * de la façade publique porte les coutures INJECTABLES de la mission (h1
 * stockage/lecteurStats, h4 écart 1 androidJar, D5 jdkHome), chacune à
 * VALEUR PAR DÉFAUT — le nouveau paramètre n'ajoute AUCUN site d'appel
 * contraint (les constructeurs existants compilent inchangés).
 * TooManyFunctions — façade de mission : chaque méthode publique est une
 * FRONTIÈRE consommée (serveur, définitions, vues par langue —
 * vueClasspathKotlin K3, vueNomsXml XE1), pas un regroupement de
 * convenance.
 */
@Suppress("LongParameterList", "TooManyFunctions")
public class SessionLsp(
    racineProjet: File,
    stockageIndex: File =
        jo.codeide.lsp.index.ServiceIndexDisque
            .emplacementParDefaut(),
    dispatcheurIo: CoroutineDispatcher = Dispatchers.IO,
    lecteurStats: LecteurStatistiquesFichier = LecteurStatistiquesFichierDisque(),
    jdkHome: Path? = null,
    androidJar: File? = null,
    parentJob: Job? = null,
    /**
     * Configuration de la session (lot LSP-1, ADR 0128) — plafond du
     * budget du cache (Auto par défaut) et période du surveillant de
     * fichiers S1-d (I4 : 5 s par défaut, 0 = désactivé). Les défauts
     * sont le comportement HISTORIQUE (v1.0.x–v1.1.x) ; le sous-ensemble
     * dynamique est appliquable en cours de session par [appliquer].
     */
    private var config: ConfigurationLsp = ConfigurationLsp.parDefaut(),
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Scope possédé par la session (fermé dans [close]). */
    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcheurIo)

    /**
     * Environnement de compilation VIVANT du moteur Java RÉÉCRIT
     * (bascule INTEGRATION-CODEIDE §3.2.2, option B) : contextes PAR
     * MODULE nourris par l'orchestrateur, routeur fichier → module relu
     * à chaque requête, index de noms partagé (adaptateur SPI neuf) —
     * le serveur (`ServeurLangageJava`) y vit SANS jamais le libérer,
     * la session le détient et le dispose à [close].
     */
    private val environnement = EnvironnementCompilation(jdkHome = jdkHome)

    /** Dépôt du modèle LSP (instantanés publiés à chaque sync). */
    private val depot = DepotModeleLsp()

    /** Orchestrateur modèle/index/moteurs. */
    private val orchestrateur =
        OrchestrateurLsp(
            environnement = environnement,
            depot = depot,
            stockage = stockageIndex,
            scope = scope,
            dispatcheurIo = dispatcheurIo,
            lecteurStats = lecteurStats,
        )

    /** Persistance versionnée du modèle (lsp-modele.json, W7 — lot h4 écart 2). */
    private val persistanceModele = PersistanceModeleLsp()

    /** Lecteur de statistiques de la session (validation W7 du modèle relu). */
    private val lecteurStatsSession: LecteurStatistiquesFichier = lecteurStats

    /** Fichier du modèle persisté, à côté de sync-state.json (ADR 0058). */
    private val fichierModelePersiste: File =
        racineProjet.resolve(".codeide/local/lsp-modele.json")

    /** Miroir public de l'état d'indexation (consommé par l'UI, h2). */
    private val etatIndexationMutable = MutableStateFlow(EtatIndexation.INITIAL)

    /** Collecte courante de l'état de la vue fusion. */
    private var collecteEtat: Job? = null

    /**
     * Serveurs Java VIVANTS (S1-d) : chaque serveur créé par [serveur]
     * s'inscrit ici et se retire lui-même à son shutdown/exit (rappel
     * aLArret — aucun serveur mort ne reçoit d'événements de fichiers).
     */
    private val serveursJava = java.util.concurrent.CopyOnWriteArrayList<ServeurLangageJava>()

    /**
     * Destinataires EXTERNES des événements de fichiers (lot L1 du plan
     * 1.3.0 — généralisation de S1-d aux trois langues) : les façades
     * Kotlin ([SessionLspKotlin]) et XML ([SessionLspXml]) y inscrivent
     * leur diffuseur ; chaque inscription retourne une poignée de
     * DÉSINSCRIPTION (la façade se retire à sa fermeture — un
     * destinataire mort ne reçoit plus rien, miroir du rappel aLArret
     * des serveurs Java).
     */
    private val destinatairesFichiers =
        java.util.concurrent.CopyOnWriteArrayList<DestinataireEvenementsFichiers>()

    /** Boucle courante du surveillant S1-d (annulée/relancée par [appliquer], lot LSP-1). */
    private var boucleSurveillance: Job? = null

    /** Dispatcher de la session (relances dynamiques du surveillant, lot LSP-1). */
    private val dispatcheurSession = dispatcheurIo

    private var fermee = false

    init {
        // ADR 0215 (découverte D6) : ROUTAGE fichier → module — le serveur
        // Java analyse chaque fichier avec le moteur de SON module (racines
        // sources, jars, android.jar du modèle) au lieu du contexte défaut
        // (android.jar seul). Le routeur lit l'INSTANTANÉ COURANT du dépôt à
        // CHAQUE requête : un modèle remplacé par la sync suivante route
        // aussitôt sans re-pose. `AssociationFichier` (modèle) faisait déjà
        // l'association — il n'était branché NULLE PART. Le moteur neuf
        // pose le routeur par PROPRIÉTÉ VAR (relue à chaque requête — même
        // contrat fiche 047).
        environnement.routeurFichierModule = { chemin ->
            depot.instantane.value
                ?.let { projet -> AssociationFichier.associer(projet, chemin)?.nom }
        }
        // Lot h4 écart 1 (v0.102.0) : la plateforme du toolchain rejoint le
        // contexte PAR DÉFAUT AVANT tout branchement d'onglet — la voie
        // rapide d'Android Studio. Le moteur neuf nourrit le contexte
        // DÉFAUT par REMPLACEMENT (racines vides + jar de la plateforme :
        // garde d'existence du remplacement — un SDK non installé laisse le
        // moteur en repli SANS erreur ici).
        if (androidJar != null && androidJar.isFile) {
            environnement.remplacerContexteModule(
                null,
                emptyList(),
                listOf(androidJar.toPath()),
            )
            LOGGER.info("[h4] android.jar de la plateforme branche au moteur par defaut")
        }
        orchestrateur.purgerTemporaires()
        // S1-d « watched files côté hôte » (I4) : le balayage périodique
        // pousse les événements .java externes aux serveurs vivants — la
        // voie protocole n'est plus muette. Borné (plafond du scan),
        // testable (periodeMs <= 0 = neutre), annulé par le scope. Depuis
        // le lot LSP-1, la période est un réglage DYNAMIQUE (interrupteur
        // « Surveiller les modifications externes » de l'écran L3).
        relancerSurveillance(config.surveillanceFichiersMs)
    }

    /**
     * Destination S1-d (généralisée lot L1) : les événements de fichiers
     * externes rejoignent CHAQUE serveur VIVANT de l'hôte — serveurs
     * Java (leur notification didChangeWatchedFiles invalide le cache de
     * parse — le serveur reparse au prochain tour) ET destinataires
     * inscrits (façades Kotlin/XML, qui diffusent à leurs serveurs
     * vivants). Un destinataire FAUTIF (en arrêt, en erreur) n'empêche
     * pas les autres : chaque poussée est gardée, la désinscription
     * (aLArret côté Java, poignée Closeable côté façade) reste le chemin
     * principal.
     */
    internal fun pousserEvenementsFichiers(params: DidChangeWatchedFilesParams) {
        for (serveur in serveursJava) {
            try {
                serveur.workspaceService.didChangeWatchedFiles(params)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "événements de fichiers à un serveur échoués (ignorés)", t)
            }
        }
        for (destinataire in destinatairesFichiers) {
            try {
                destinataire.recevoir(params)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "événements de fichiers à un destinataire échoués (ignorés)", t)
            }
        }
    }

    /**
     * Enregistre un DESTINATAIRE des événements de fichiers externes
     * (lot L1 — la façade Kotlin/XML y branche son diffuseur vers ses
     * serveurs vivants). La poignée retournée DÉSINSCRIT le destinataire
     * (appel [Closeable.close] à la fermeture de la façade) ; la
     * désinscription est IDEMPOTENTE et tolère une session déjà fermée.
     */
    public fun enregistrerDestinataireFichiers(destinataire: DestinataireEvenementsFichiers): java.io.Closeable {
        destinatairesFichiers.add(destinataire)
        return java.io.Closeable {
            destinatairesFichiers.remove(destinataire)
        }
    }

    /** Modèle courant (null avant le premier sync réussi). */
    public val modele: StateFlow<ProjetLsp?> get() = depot.instantane

    /** État d'indexation de la vue fusion (flux public, découplé des types d'index). */
    public val etatIndexation: StateFlow<EtatIndexation>
        get() = etatIndexationMutable.asStateFlow()

    /**
     * Applique un résultat de classpath tooling (post-sync v8) : modèle
     * publié, index fusion reconstruit si l'union a changé, moteurs par
     * module remplacés, état d'indexation suivi. Redondant = no-op
     * chaud (sémantiques lot E). Le modèle appliqué est PERSISTÉ sous
     * `.codeide/local/lsp-modele.json` (lot h4 écart 2, v0.102.0) — la
     * réouverture le relira via [nourrirDepuisModelePersiste] avant même
     * la revalidation silencieuse.
     */
    public fun appliquerClasspath(resultat: ClasspathResult): ResultatApplication {
        verifierOuverte()
        val application = orchestrateur.appliquer(resultat)
        persisterModeleCourant()
        relancerCollecteEtat()
        return application.versPublic()
    }

    /**
     * Nourrit la session depuis le modèle PERSISTÉ (`.codeide/local/
     * lsp-modele.json`, à côté de sync-state.json) — lot h4 écart 2,
     * v0.102.0 : la réouverture d'un projet dont l'empreinte Gradle n'a
     * pas changé retrouve modèle + moteurs QUASI INSTANTANÉMENT, avant
     * la sync fraîche (Android Studio rouvre son index persisté).
     *
     * Le fichier est VALIDÉ à la lecture (W7 : taille + horodatage de
     * chaque entrée, [PersistanceModeleLsp.lire]) : un écart est SIGNALÉ,
     * jamais fatal — le modèle reste utilisable, la revalidation suivante
     * le remplace. Fraîcheur définitive : la sync (l'appelant ordonnance
     * l'empreinte Gradle — la clé de cache — AVANT d'appeler).
     *
     * @return `true` si un modèle a été relu et appliqué ; `false` sinon
     *         (fichier absent/illisible/version inconnue) — jamais d'exception.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — la lecture
     * tolérante est le contrat même du lot h4 écart 2 (fichier absent,
     * fichier refusé, succès) — même patron que LireSyncStateUseCase.
     */
    @Suppress("ReturnCount")
    public fun nourrirDepuisModelePersiste(): Boolean {
        verifierOuverte()
        if (!fichierModelePersiste.isFile) return false
        val persiste = persistanceModele.lire(fichierModelePersiste, lecteurStatsSession) ?: return false
        orchestrateur.appliquerModele(persiste.projet)
        relancerCollecteEtat()
        val verification = persiste.verification
        val verifie =
            when (verification) {
                is jo.codeide.lsp.model.EtatVerification.Coherant -> {
                    "coherent"
                }

                is jo.codeide.lsp.model.EtatVerification.NonVerifie -> {
                    "non verifie (${verification.ecarts.size} ecart(s))"
                }
            }
        LOGGER.info(
            "session LSP nourrie depuis le modele persiste " +
                "(${persiste.projet.modules.size} module(s), $verifie)",
        )
        return true
    }

    /**
     * Relance la construction de l'index (action « Réindexer » de l'UI) :
     * revalidation des segments persistés, auto-guérison des corrompus.
     */
    public fun reindexer() {
        verifierOuverte()
        orchestrateur.reindexer()
    }

    /**
     * Annule le passage de construction EN VOL (ADR 0214 — demande D5
     * de CodeIDE, maquette de l'écran d'indexation) : annulation
     * coopérative, l'état devient PARTIEL « annulé par l'utilisateur »
     * (l'acquis reste interrogable, jamais de faux PRÊT).
     *
     * @return `false` si aucune construction n'est en vol (l'écran
     *         retire l'offre).
     */
    public fun annulerPassage(): Boolean {
        verifierOuverte()
        return orchestrateur.annulerPassage()
    }

    /**
     * Reprise ciblée : ne RETENTE que les entrées ÉCARTÉES du dernier
     * passage (ADR 0214 — demande D4 de CodeIDE) — les deux boutons de
     * la maquette (« Réindexer » / « Réessayer les entrées échouées »)
     * deviennent ENFIN distincts : la reprise est visible et bon
     * marché, les segments sains ne sont pas revalidés.
     *
     * @return `false` sans écart à retenter ou pendant une construction
     *         en vol (l'écran grise le bouton).
     */
    public fun reesayerEchecs(): Boolean {
        verifierOuverte()
        return orchestrateur.reesayerEchecs()
    }

    /** Configuration COURANTE de la session (lot LSP-1) — l'écran L3
     *  affiche les valeurs réelles, jamais devinées. */
    public fun configuration(): ConfigurationLsp = config

    /**
     * Applique un sous-ensemble DYNAMIQUE de la configuration (lot LSP-1,
     * ADR 0128 §4) :
     *
     * - **plafond du budget du cache** : un plafond explicite recrée le
     *   service d'index avec le NOUVEAU budget (les segments persistent
     *   sur disque — zéro ré-indexation ; l'adaptateur changé invalide
     *   les moteurs, reconstruits paresseusement — même sémantique que
     *   la croissance historique) ; retour à `null` (Auto) = re-dérivation
     *   depuis le corpus courant ;
     * - **surveillance des fichiers externes** : la boucle courante est
     *   ANNULÉE puis relancée à la nouvelle période (`0` = désactivée) ;
     *   la nouvelle boucle réinstalle sa baseline au premier tour (aucun
     *   événement fantôme, même contrat que le démarrage).
     *
     * Ce qui n'est pas dynamique (JDK, plateforme, stockage) est fixé à la
     * construction par l'environnement (écran L4, lecture seule).
     */
    public fun appliquer(config: ConfigurationLsp) {
        verifierOuverte()
        orchestrateur.appliquerPlafondBudget(config.budgetCacheOctets)
        relancerSurveillance(config.surveillanceFichiersMs)
        this.config = config
    }

    /**
     * Taille sur DISQUE du cache d'index (octets) — écran L3 « Occupation ».
     * Mesure l'entrepôt de segments (y compris AVANT le premier sync : un
     * stockage hérité d'une session précédente compte) ; le budget LRU
     * (tas), lui, voyage dans [ResultatApplication.budgetCacheMo].
     */
    public fun tailleCacheOctets(): Long {
        verifierOuverte()
        return orchestrateur.tailleCacheOctets()
    }

    /**
     * Vide le cache d'index SUR DISQUE (écran L3 « Vider le cache », lot
     * LSP-1) : segments et temporaires supprimés, cache LRU mémoire vidé.
     * **Refusé pendant une indexation** ([ResultatVidageCache.Refusee]) :
     * vider pendant la construction laisserait des segments à moitié écrits
     * — l'écran désactive le bouton avec l'explication. Après un vidage
     * réussi, l'index est reconstruit À LA PROCHAINE OUVERTURE ou par
     * « Réindexer » (dialogue de l'écran L3) ; les requêtes vivantes
     * tombent sur des segments absents et se dégradent honnêtement.
     */
    public fun viderCache(): ResultatVidageCache {
        verifierOuverte()
        if (orchestrateur.constructionActive()) {
            return ResultatVidageCache.Refusee(RaisonRefusVidage.INDEXATION_EN_COURS)
        }
        val vidage = orchestrateur.viderCache()
        return ResultatVidageCache.Videe(
            segmentsSupprimes = vidage.first,
            octetsLiberes = vidage.second,
        )
    }

    /**
     * Capacités ANNONCÉES par le moteur du langage (lot LSP-1) — la liste
     * des interrupteurs de l'écran L2 en est DÉRIVÉE (jamais
     * d'interrupteur pour une capacité non annoncée, ADR 0128 §4) ; la
     * parité avec les `ServerCapabilities` réelles est verrouillée par
     * test ([CapacitesAnnoncees]).
     */
    public fun capacites(langage: LangageLsp): Set<CapaciteLsp> = CapacitesAnnoncees.capacites(langage)

    /**
     * (Re)branche la boucle du surveillant S1-d — appelée à la construction
     * puis à chaque [appliquer] : l'ancienne boucle est annulée, la
     * nouvelle réinstalle sa baseline au premier tour (zéro événement
     * synthétique, même contrat que le démarrage, [SurveillantFichiersHote]).
     */
    private fun relancerSurveillance(periodeMs: Long) {
        boucleSurveillance?.cancel()
        boucleSurveillance = null
        if (periodeMs > 0) {
            boucleSurveillance =
                SurveillantFichiersHote(racine, periodeMs)
                    .boucle(scope, dispatcheurSession, ::pousserEvenementsFichiers)
        }
    }

    /** Boucle de surveillance vivante (observation de test, lot LSP-1). */
    internal val surveillanceVivante: Boolean
        get() = boucleSurveillance?.isActive == true

    /**
     * UN serveur LSP4J in-process pour une connexion (cel-lsp
     * approvisionne par le supplier du transport — jamais mis en cache :
     * chaque connexion vit son cycle initialize/shutdown/exit LSP 3.17).
     */
    public fun serveur(): LanguageServer {
        verifierOuverte()
        // Le rappel aLArret (Runnable, SANS paramètre) capture le serveur
        // par variable locale — affectée une fois le serveur construit.
        // Moteur neuf (bascule §3.2.2) : le constructeur VIVANT
        // `ServeurLangageJava(environnement) { rappel }` — UNE session par
        // projet, moteur résolu PAR REQUÊTE (routeur fiche 047), contextes
        // remplacés À CHAUD par les syncs (replaceAll fiche 034).
        var inscrit: ServeurLangageJava? = null
        val serveur =
            ServeurLangageJava(environnement) {
                inscrit?.let { vivant -> serveursJava.remove(vivant) }
            }
        inscrit = serveur
        serveursJava.add(serveur)
        return serveur
    }

    /**
     * Vue de classpath NEUTRE Kotlin de l'union courante (K3,
     * LSP_KOTLIN.md §4.1 « classpath », D4 : la vue d'index fusion
     * ADR 0111 est PARTAGÉE avec le LSP Kotlin — l'adaptateur est
     * recréé à CHAQUE appel : les requêtes sous-lisent tant que la
     * construction n'est pas PRÊTE, jamais de double budget de cache).
     *
     * @return l'adaptateur sur la vue courante, `null` avant le premier
     *         sync réussi (la session Kotlin vit alors en régime
     *         dégradé : symboles projet seuls — jamais de mensonge).
     */
    public fun vueClasspathKotlin(): jo.lspkotlin.api.VueClasspathKotlin? {
        val vueCourante = orchestrateur.etat() ?: return null
        return AdaptateurVueClasspath(vueCourante)
    }

    /**
     * Vue de NOMS neutre XML de l'union courante (XE1, LSP_XML.md §4.1
     * « classpath » : couture VueNomsXml — la garde « balise inconnue »
     * XE4 exigera que le classpath soit d'accord) — même vue d'index
     * fusion, même contrat D4, adaptateur recréé à chaque appel.
     *
     * @return l'adaptateur sur la vue courante, `null` avant le premier
     *         sync réussi (la garde X4 reste alors ACTIVE : le moteur
     *         XML ne signale jamais de balise inconnue).
     */
    public fun vueNomsXml(): jo.lspxml.api.VueNomsXml? {
        val vueCourante = orchestrateur.etat() ?: return null
        return AdaptateurVueNomsXml(vueCourante)
    }

    /**
     * La définition cel-lsp du serveur Java de CodeIDE — à enregistrer
     * dans un `LspProject` (h2, branchement éditeur) : extension
     * `java`, transport IN-PROCESS (ADR 0109).
     */
    public fun definition(): LanguageServerDefinition = DefinitionServeurLspJava { serveur() }

    /**
     * Arrêt exhaustif : coroutines annulées **puis attendues dans la
     * borne** (v0.121.1 — retour CI du run 38412598199 : l'indexation en
     * vol continuait d'écrire des segments après le retour de [close],
     * en course avec le nettoyage `@TempDir` de JUnit), moteurs disposés,
     * modèle vidé.
     */
    public override fun close() {
        if (fermee) return
        fermee = true
        try {
            scope.cancel("SessionLsp fermée")
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "annulation du scope échouée", t)
        }
        attendreArretCoroutines()
        try {
            environnement.dispose()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "dispose de l'environnement échoué", t)
        }
        depot.vider()
    }

    /**
     * Attend l'arrêt EFFECTIF des coroutines de la session, borné dans le
     * temps (I2 : la fermeture ne bloque jamais indéfiniment — CYCLE_DE_VIE
     * §4) : `cancel` ne fait que SIGNALER, une analyse de jar en vol sur
     * `Dispatchers.IO` ne s'interrompt qu'à son prochain point de contrôle —
     * entre les deux, elle peut encore écrire un segment sur le disque.
     * Après cette attente, le contrat « arrêt propre exhaustif » est honnête
     * dans le cas courant ; le cas dégénéré (coroutine réellement coincée)
     * est JOURNALISÉ et la fermeture continue (jamais bloquante).
     *
     * [CountDownLatch] plutôt que `runBlocking` (interdit hors tests) :
     * [close] est un contrat SYNCHRONE de `Closeable`, l'attente borne le
     * fil appelant (registre de fermeture en production — FermablesEspace
     * consomme son délai, pas un fil de travail). L'appel ne peut pas se
     * joindre lui-même : les appelants de production vivent hors du scope
     * de la session ; un éventuel auto-join dégénéré retomberait sur le
     * délai borné ci-dessous (journalisé, jamais de blocage infini).
     */
    private fun attendreArretCoroutines() {
        val travail = scope.coroutineContext[Job] ?: return
        if (travail.isCompleted) return
        val fin = CountDownLatch(1)
        travail.invokeOnCompletion { fin.countDown() }
        try {
            if (!fin.await(DELAI_ARRET_COROUTINES_MS, TimeUnit.MILLISECONDS)) {
                LOGGER.log(
                    Level.WARNING,
                    "coroutines de session encore vivantes ${DELAI_ARRET_COROUTINES_MS} ms " +
                        "après l'annulation — fermeture forcée (écritures d'index possibles)",
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOGGER.log(Level.WARNING, "attente d'arrêt des coroutines interrompue", e)
        }
    }

    /**
     * Écrit le modèle courant sous `.codeide/local/lsp-modele.json` —
     * BEST EFFORT (lot h4 écart 2) : l'écriture passe par la MÊME vue
     * FUSE que le moteur (processus app, ADR 0038), un échec est
     * journalisé JUL et JAMAIS propagé — la session vit, la prochaine
     * application retentera.
     */
    private fun persisterModeleCourant() {
        val projet = depot.instantane.value ?: return
        try {
            persistanceModele.ecrire(projet, fichierModelePersiste)
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "ecriture du modele persiste echouee (ignoree)", t)
        }
    }

    /** Refuse l'usage après close (erreur explicite, pas de comportement fantôme). */
    private fun verifierOuverte() {
        check(!fermee) { "SessionLsp fermée — créer une nouvelle session" }
    }

    /** (Re)collecte l'état de la vue fusion — appelée à chaque application. */
    private fun relancerCollecteEtat() {
        collecteEtat?.cancel()
        val vue = orchestrateur.etat()
        if (vue == null) {
            etatIndexationMutable.value = EtatIndexation.INITIAL
            return
        }
        collecteEtat =
            scope.launch {
                vue.etat.collect { etat ->
                    etatIndexationMutable.value = traduireEtatIndex(etat)
                }
            }
    }

    /** B3 (Phase B prompt 2) : traduit `EtatIndex` (lsp:index, interne)
     *  en `EtatIndexation` (lsp:host, publique). Découplage des types :
     *  `lsp:index` est en `implementation` (non transitive), l'UI ne voit
     *  que les types publics de `lsp:host`.
     *
     *  Exemption detekt ciblée (règle 8 : exception commentée) :
     *  `LongMethod` et `CyclomaticComplexMethod` — la traduction exhaustive
     *  de TOUS les champs de `EtatIndex` (y compris les `when` sur les
     *  enums) est par construction longue et cyclomatiquement complexe.
     *  La découper en sous-fonctions par champ casserait la lisibilité
     *  (une traduction par champ) sans réduire la complexité réelle. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun traduireEtatIndex(etat: jo.codeide.lsp.index.EtatIndex): EtatIndexation =
        EtatIndexation(
            phase = traduirePhase(etat.phase),
            faits = etat.faits,
            total = etat.total,
            entreeCourante = etat.entreeCourante,
            avertissements = etat.avertissements,
            erreurs = etat.erreurs,
            phaseDetaillee = traduirePhaseDetaillee(etat.phaseDetaillee),
            listeTravail = etat.listeTravail.map(::traduireEntreeTravail),
            breakdownIndexeurs = etat.breakdownIndexeurs.map(::traduireBreakdown),
            dureesPhases = etat.dureesPhases?.let(::traduireDurees),
            bilanDernierPassage = etat.bilanDernierPassage?.let(::traduireBilan),
            settled = etat.settled,
            reprises = etat.reprises,
        )

    private fun traduirePhase(phase: jo.codeide.lsp.index.PhaseIndex): PhaseIndexation =
        when (phase) {
            jo.codeide.lsp.index.PhaseIndex.CONSTRUCTION -> PhaseIndexation.CONSTRUCTION
            jo.codeide.lsp.index.PhaseIndex.PRET -> PhaseIndexation.PRET
            jo.codeide.lsp.index.PhaseIndex.PARTIEL -> PhaseIndexation.PARTIEL
            jo.codeide.lsp.index.PhaseIndex.ERREUR -> PhaseIndexation.ERREUR
        }

    private fun traduirePhaseDetaillee(phase: jo.codeide.lsp.index.PhaseDetailleeIndex): PhaseDetailleeIndexation =
        when (phase) {
            jo.codeide.lsp.index.PhaseDetailleeIndex.INITIAL -> PhaseDetailleeIndexation.INITIAL
            jo.codeide.lsp.index.PhaseDetailleeIndex.BIBLIOTHEQUES -> PhaseDetailleeIndexation.BIBLIOTHEQUES
            jo.codeide.lsp.index.PhaseDetailleeIndex.SOURCES -> PhaseDetailleeIndexation.SOURCES
            jo.codeide.lsp.index.PhaseDetailleeIndex.AUCUNE -> PhaseDetailleeIndexation.AUCUNE
        }

    private fun traduireEntreeTravail(entree: jo.codeide.lsp.index.EntreeTravail): EntreeTravailIndexation =
        EntreeTravailIndexation(
            nom = entree.nom,
            etat = traduireEtatEntree(entree.etat),
        )

    private fun traduireEtatEntree(etat: jo.codeide.lsp.index.EtatEntreeTravail): EtatEntreeTravailIndexation =
        when (etat) {
            jo.codeide.lsp.index.EtatEntreeTravail.EN_ATTENTE -> EtatEntreeTravailIndexation.EN_ATTENTE
            jo.codeide.lsp.index.EtatEntreeTravail.ACTIF -> EtatEntreeTravailIndexation.ACTIF
            jo.codeide.lsp.index.EtatEntreeTravail.TERMINE -> EtatEntreeTravailIndexation.TERMINE
            jo.codeide.lsp.index.EtatEntreeTravail.REPRIS -> EtatEntreeTravailIndexation.REPRIS
            jo.codeide.lsp.index.EtatEntreeTravail.ECARTE -> EtatEntreeTravailIndexation.ECARTE
        }

    private fun traduireBreakdown(b: jo.codeide.lsp.index.BreakdownIndexeur): BreakdownIndexeurIndexation =
        BreakdownIndexeurIndexation(
            identifiant = b.identifiant,
            libelle = b.libelle,
            millisecondesCumulees = b.millisecondesCumulees,
            nombreEntrees = b.nombreEntrees,
        )

    private fun traduireDurees(d: jo.codeide.lsp.index.DureesPhases): DureesPhasesIndexation =
        DureesPhasesIndexation(
            bibliothequesMs = d.bibliothequesMs,
            sourcesMs = d.sourcesMs,
        )

    private fun traduireBilan(b: jo.codeide.lsp.index.BilanDernierPassage): BilanDernierPassageIndexation =
        BilanDernierPassageIndexation(
            dureeMs = b.dureeMs,
            artefactsTotal = b.artefactsTotal,
            artefactsRepris = b.artefactsRepris,
            artefactsReconstruits = b.artefactsReconstruits,
            fichiersParcourus = b.fichiersParcourus,
            fichiersReanalyses = b.fichiersReanalyses,
            indexeurPlusCouteux = b.indexeurPlusCouteux,
            horodatageMs = b.horodatageMs,
        )

    /** Définition cel-lsp in-process du serveur Java. */
    private class DefinitionServeurLspJava(
        private val fabrique: () -> LanguageServer,
    ) : LanguageServerDefinition(EXTENSION, NOM) {
        public override fun createConnectionProvider(uri: String): StreamConnectionProvider =
            InProcessStreamConnectionProvider(fabrique)
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger("SessionLsp")

        /** Extension du serveur (langage Java, ADR 0109). */
        private const val EXTENSION: String = "java"

        /** Nom du serveur (langage Java, ADR 0109). */
        private const val NOM: String = "codeide-java"

        /**
         * Borne de l'attente d'arrêt des coroutines dans [close] (I2) :
         * le travail restant est UNE écriture de segment (rapide, la mise
         * à jour CI a montré des courses au niveau de l'analyse) ; une
         * analyse de jar réellement coincée est coupée ici et JOURNALISÉE,
         * la fermeture ne l'attend jamais indéfiniment.
         */
        private const val DELAI_ARRET_COROUTINES_MS: Long = 2_000
    }
}

/** Traduction publique du résultat d'application (journal, tests, UI). */
public data class ResultatApplication(
    public val modulesActifs: Int,
    public val modulesIgnores: Int,
    public val entreesIndex: Int,
    public val budgetCacheMo: Long,
    public val unionChangee: Boolean,
)

/**
 * Destinataire des événements de fichiers EXTERNES du surveillant S1-d
 * (lot L1 du plan 1.3.0 — généralisation aux trois langues) : une façade
 * de langue ([SessionLspKotlin], [SessionLspXml]) s'inscrit par
 * [SessionLsp.enregistrerDestinataireFichiers] et reçoit les mêmes
 * `DidChangeWatchedFilesParams` que les serveurs Java vivants — la
 * notification traverse ensuite sa propre liste de serveurs.
 */
public fun interface DestinataireEvenementsFichiers {
    /** Reçoit les événements externes (garde par destinataire côté session). */
    public fun recevoir(params: DidChangeWatchedFilesParams)
}

/** Translation publique — (voir OrchestrateurLsp.Application). */
private fun OrchestrateurLsp.Application.versPublic(): ResultatApplication =
    ResultatApplication(
        modulesActifs = modulesActifs,
        modulesIgnores = modulesIgnores,
        entreesIndex = entreesIndex,
        budgetCacheMo = budgetCacheMo,
        unionChangee = unionChangee,
    )

/** Phase d'indexation exposée (découplée des types internes de l'index). */
public enum class PhaseIndexation {
    /** Aucune vue (avant le premier sync). */
    INITIAL,

    /** Construction en cours (progression par jar). */
    CONSTRUCTION,

    /** Prêt : localisateur autoritatif (P7/P8). */
    PRET,

    /** Partiel honnête — l'index reste un repli, jamais une autorité. */
    PARTIEL,

    /** Erreur de construction (segments corrompus irrécupérables). */
    ERREUR,
}

/** État d'indexation exposé à l'UI (découplé des types internes d'index).
 *
 *  B3 (Phase B prompt 2, ADR 0117 §4.4) : étendu avec les nouveaux champs
 *  transportés depuis `EtatIndex` (lsp:index). Tous ont des valeurs par
 *  défaut — la compatibilité binaire est préservée (les consommateurs
 *  existants qui ne lisent que `phase`/`faits`/`total`/`entreeCourante`/
 *  `avertissements`/`erreurs` ne sont pas affectés). */
public data class EtatIndexation(
    public val phase: PhaseIndexation,
    public val faits: Int,
    public val total: Int,
    public val entreeCourante: String?,
    public val avertissements: List<String>,
    public val erreurs: List<String>,
    public val phaseDetaillee: PhaseDetailleeIndexation = PhaseDetailleeIndexation.AUCUNE,
    public val listeTravail: List<EntreeTravailIndexation> = emptyList(),
    public val breakdownIndexeurs: List<BreakdownIndexeurIndexation> = emptyList(),
    public val dureesPhases: DureesPhasesIndexation? = null,
    public val bilanDernierPassage: BilanDernierPassageIndexation? = null,
    public val settled: Boolean = false,
    public val reprises: Int = 0,
) {
    public companion object {
        /** Avant tout sync : aucune vue, aucune construction. */
        public val INITIAL: EtatIndexation =
            EtatIndexation(
                phase = PhaseIndexation.INITIAL,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
    }
}

/** Lecteur de statistiques réel (disque — injecté par défaut). */
public class LecteurStatistiquesFichierDisque : LecteurStatistiquesFichier {
    /** Lecture bornée manuelle (`readNBytes` est API 33 — minSdk 26). */
    private fun lireBorne(
        flux: java.io.InputStream,
        cible: ByteArray,
    ): Int {
        var lus = 0
        while (lus < cible.size) {
            val n = flux.read(cible, lus, cible.size - lus)
            if (n < 0) break
            lus += n
        }
        return lus
    }

    private companion object {
        private val LOGGER: java.util.logging.Logger =
            java.util.logging.Logger
                .getLogger("LecteurStatistiquesFichierDisque")

        /** Morceau lu en tête du fichier (la taille+mtime complètent). */
        private const val OCTETS_EMPREINTE: Int = 4096
    }

    override fun existe(chemin: String): Boolean = File(chemin).exists()

    override fun taille(chemin: String): Long = File(chemin).length()

    override fun mtime(chemin: String): Long = File(chemin).lastModified()

    /**
     * Empreinte partielle du contenu : les premiers octets,
     * hexadécimaux (`null` si illisible) — la couture est OPAQUE pour
     * le modèle (identité « partiel:… » composée par `Empreintes`),
     * seule la STABILITÉ fait foi.
     *
     * SwallowedException ciblé (règle 16) : le contrat de la couture
     * EST `null` quand le fichier est illisible (identité
     * « partiel:illisible » composée en aval) — l'exception est
     * journalisée, elle n'est PAS perdue.
     */
    @Suppress("SwallowedException")
    override fun empreintePartielle(chemin: String): String? =
        try {
            File(chemin).inputStream().use { flux ->
                val debut = ByteArray(OCTETS_EMPREINTE)
                val lus = lireBorne(flux, debut)
                val hex = StringBuilder(lus * 2)
                for (octet in debut.copyOf(lus)) {
                    hex.append(String.format(java.util.Locale.ROOT, "%02x", octet))
                }
                hex.toString()
            }
        } catch (t: Throwable) {
            LOGGER.fine("empreintePartielle illisible : $chemin ($t)")
            null
        }
}
