package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.EvenementIndexationXml
import jo.lspxml.api.MoteurLangageXml
import jo.lspxml.api.ServiceMoteurLangageXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.api.VueNomsXml
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
 * Façade de la session LSP XML d'un projet (XE1, LSP_XML.md §4.1
 * « session ») — miroir volontairement MINCE de [SessionLspKotlin] :
 * même frontière nette, même cycle de vie piloté par le ViewModel,
 * même transport in-process cel-lsp (ADR 0109 D2 — un changement de
 * transport se brancherait ici sans toucher au moteur ni à l'éditeur).
 *
 * Le moteur est résolu par le SPI ServiceLoader ([MoteurLangageXml],
 * X2 : c'est le SEUL type du moteur visible de l'hôte — jamais
 * l'implémentation concrète, jamais le parseur) ; chaque connexion LSP
 * reçoit sa PROPRE session moteur ([serveur]), les sessions vivantes
 * sont suivies et [close] referme celles qu'une connexion n'aurait pas
 * arrêtées (contrat « 0 ressource orpheline » du prompt 3).
 *
 * Nourrissage (XE1 le stocke, XE2/XE3 le consommeront) :
 * [appliquerClasspath] (post-sync utile) et [appliquerModele]
 * (réouverture, modèle persisté W7) convertissent le résultat tooling
 * en [ClasspathXml] NEUTRE : plateforme du projet (dossier
 * `platforms/android-*` — le catalogue XE2 vivra dans ses `data/`),
 * racines sources de l'union des modules actifs (`res/` et manifest y
 * vivent, convention AGP), vue de noms [VueNomsXml] fournie par la
 * session Java qui possède l'index fusion (UNE seule vue, D4).
 *
 * @param racineProjet racine du projet ouvert (identité de la session).
 * @param moteur moteur de langue (SPI) — injectable pour les tests,
 *        résolution ServiceLoader par défaut (échec explicite si le
 *        module n'est pas branché : erreur de build, pas fantôme).
 * @param lecteurStats couture de statistiques (existence des entrées),
 *        injectable pour les tests.
 * @param sessionJava session Java du MÊME projet (lot L1 du plan 1.3.0) —
 *        quand elle est fournie, la façade s'inscrit à son surveillant
 *        de fichiers : les événements externes (`.xml` modifiés hors
 *        éditeur) atteignent les serveurs XML VIVANTS par le MÊME chemin
 *        que les serveurs Java. `null` = façade autonome.
 */
@Suppress("TooManyFunctions")
public class SessionLspXml(
    racineProjet: File,
    private val moteur: MoteurLangageXml = ServiceMoteurLangageXml.unique(),
    private val lecteurStats: LecteurStatistiquesFichier = LecteurStatistiquesFichierDisque(),
    sessionJava: SessionLsp? = null,
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Sessions moteur des connexions vivantes (fermées à [close] aussi). */
    private val sessionsVivantes = CopyOnWriteArrayList<SessionLangageXml>()

    /**
     * Serveurs LSP4J des connexions vivantes (lot L1) — la destination
     * des événements de fichiers externes quand la façade est branchée
     * sur le surveillant de la session Java ; chaque serveur se retire
     * à son arrêt (aLArret).
     */
    private val serveursVivants = CopyOnWriteArrayList<XmlLanguageServer>()

    /** Classpath courant (poussé aux sessions futures à la création). */
    @Volatile
    private var classpathCourant: ClasspathXml? = null

    /**
     * Poignée de désinscription au surveillant de la session Java (lot
     * L1) — fermée à [close] : un destinataire mort ne reçoit plus rien.
     */
    private val desinscriptionSurveillance: Closeable? =
        sessionJava?.enregistrerDestinataireFichiers(::diffuserEvenementsFichiers)

    /**
     * Miroir public de l'état d'indexation du MOTEUR XML (ADR 0213 —
     * demande D3 de CodeIDE) : le MÊME type public que
     * [SessionLsp.etatIndexation] côté Java et [SessionLspKotlin.etatIndexation].
     */
    private val etatIndexationMutable = MutableStateFlow(EtatIndexation.INITIAL)

    /** État d'indexation XML observable (type public commun aux trois langues). */
    public val etatIndexation: StateFlow<EtatIndexation>
        get() = etatIndexationMutable.asStateFlow()

    /**
     * Désinscription de l'observateur moteur (ADR 0213) — fermée à
     * [close] ; un pack ancien rend la poignée vide du SPI (les
     * FENÊTRES de [appliquerModele] prennent le relais).
     */
    private val desinscriptionObservateur: Closeable =
        try {
            moteur.enregistrerObservateurIndexation { evenement ->
                etatIndexationMutable.value = traduire(evenement)
                // ADR 0211/0213 : la passe ABOUTIT — les documents ouverts
                // pendant la fenêtre de sous-lecture (index froid) se
                // REPUBLIENT avec les VRAIS diagnostics.
                if (evenement.phase == jo.lspxml.api.PhaseIndexationXml.PRET ||
                    evenement.phase == jo.lspxml.api.PhaseIndexationXml.PARTIEL
                ) {
                    republierServeursVivants()
                }
            }
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "observateur d'indexation refusé par le moteur (fenêtres seules)", t)
            java.io.Closeable { }
        }

    /**
     * Republie les documents ouverts de CHAQUE serveur vivant (ADR
     * 0211/0213) — un serveur fautif n'empêche pas les autres.
     */
    private fun republierServeursVivants() {
        for (serveur in serveursVivants) {
            try {
                serveur.republierDocumentsOuverts()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "republication d'un serveur XML échouée (ignorée)", t)
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
                LOGGER.log(Level.WARNING, "événements de fichiers à un serveur XML échoués (ignorés)", t)
            }
        }
    }

    /**
     * UN serveur LSP4J pour une connexion (cel-lsp approvisionne par le
     * supplier du transport) : la session moteur est NEUVE et suivie —
     * shutdown/exit la referme et la retire, [close] rattrape les autres.
     * La session naît NOURRIE du classpath courant s'il y en a un.
     */
    public fun serveur(): LanguageServer {
        synchronized(this) {
            check(!fermee) { "SessionLspXml fermée — créer une nouvelle session" }
        }
        val sessionLangage = moteur.creerSession()
        classpathCourant?.let { sessionLangage.definirClasspath(it) }
        sessionsVivantes.add(sessionLangage)
        // Le rappel aLArret capture le serveur par variable locale —
        // affectée une fois le serveur construit (même patron que
        // [SessionLsp.serveur] côté Java).
        var inscrit: XmlLanguageServer? = null
        val serveur =
            XmlLanguageServer(
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
     * La définition cel-lsp du serveur XML de CodeIDE — à enregistrer
     * dans le `LspProject` de l'espace (extension `xml` : layouts,
     * values, manifest, drawables — le GENRE est déduit de l'URI côté
     * moteur, X1).
     */
    public fun definition(): LanguageServerDefinition = DefinitionServeurLspXml { serveur() }

    /**
     * Applique un résultat de classpath POST-SYNC : conversion complète
     * (modèle v8 → classpath neutre XML). Tolérant : plateforme absente
     * = sans catalogue (XE2 sous-rapportera), un appel REDONDANT est un
     * no-op chaud côté moteur.
     *
     * @param resultat résultat BRUT du protocole tooling (post-sync).
     * @param vueNoms vue de noms neutre (peut être null avant le
     *        premier index PRÊT — garde « balise inconnue » XE4 active).
     */
    public fun appliquerClasspath(
        resultat: ClasspathResult,
        vueNoms: VueNomsXml?,
    ) {
        val projet = ConvertisseurModeleLsp.depuisClasspathResult(resultat, lecteurStats)
        appliquerModele(projet, vueNoms)
    }

    /**
     * Applique un modèle DÉJÀ construit (réouverture : le modèle
     * PERSISTÉ W7 de la session Java — même pipeline que
     * [SessionLsp.nourrirDepuisModelePersiste] côté Java).
     */
    public fun appliquerModele(
        projet: ProjetLsp,
        vueNoms: VueNomsXml?,
    ) {
        synchronized(this) {
            if (fermee) return
            val classpath =
                ClasspathXml(
                    dossierPlateforme = plateformeDu(projet),
                    racinesSources = racinesSources(projet),
                    vueNoms = vueNoms,
                    racinesAar = aarsDu(projet),
                )
            classpathCourant = classpath
        }
        // ADR 0211 (annexe D1-d) : nourrissage MOTEUR une fois par sync,
        // sur le fil dédié — le supplier de connexion ne paie plus rien.
        // `false` = pack ancien : la poussée SYNCHRONE ci-dessous reste
        // LE nourrissage (monde d'avant, tolérance de version).
        val prisEnCharge =
            try {
                moteur.definirClasspath(classpathCourant!!)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "nourrissage moteur refusé (poussée par session)", t)
                false
            }
        // ADR 0213 (demande D3) : fenêtre CONSTRUCTION au départ de sync —
        // le premier événement moteur (s'il y en a) reprend la main.
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
                LOGGER.log(Level.WARNING, "poussée de classpath à une session XML échouée (ignorée)", t)
            }
        }
        if (!prisEnCharge) {
            // Pack ancien : la poussée ci-dessus a nourri SYNCHRONEMENT —
            // fenêtre PRET honnête (jamais de PARTIEL inventé).
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
     * Annule le passage d'indexation XML EN VOL (ADR 0214 — demande
     * D5) ; `false` si aucun passage n'est en vol (ou pack ancien).
     */
    public fun annulerPassage(): Boolean =
        try {
            moteur.annulerPassage()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "annulation refusée par le moteur (ignorée)", t)
            false
        }

    /**
     * Retente les ÉCHECS du dernier passage XML SEULEMENT (ADR 0214 —
     * demande D4) : les unités saines sont reprises du cache mtime,
     * seuls les dossiers/AAR douteux et les pendances sont re-payés.
     * `false` s'il n'y a rien à retenter (ou pack ancien).
     */
    public fun reesayerEchecs(): Boolean =
        try {
            moteur.reesayerEchecs()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "reprise des échecs refusée par le moteur (ignorée)", t)
            false
        }

    /**
     * Traduit un événement moteur XML (types neutres du pack, ADR
     * 0213) en [EtatIndexation] public — MÊME patron de découplage
     * que [SessionLsp.traduireEtatIndex] (Java) et
     * [SessionLspKotlin.traduire] (Kotlin).
     *
     * Exemption detekt ciblée (règle 8 : exception commentée) :
     * LongMethod/CyclomaticComplexMethod — traduction EXHAUSTIVE par
     * construction, miroir assumé de `traduireEtatIndex` (SessionLsp).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun traduire(evenement: EvenementIndexationXml): EtatIndexation =
        EtatIndexation(
            phase =
                when (evenement.phase) {
                    jo.lspxml.api.PhaseIndexationXml.INITIAL -> PhaseIndexation.INITIAL
                    jo.lspxml.api.PhaseIndexationXml.CONSTRUCTION -> PhaseIndexation.CONSTRUCTION
                    jo.lspxml.api.PhaseIndexationXml.PRET -> PhaseIndexation.PRET
                    jo.lspxml.api.PhaseIndexationXml.PARTIEL -> PhaseIndexation.PARTIEL
                    jo.lspxml.api.PhaseIndexationXml.ERREUR -> PhaseIndexation.ERREUR
                },
            faits = evenement.faits,
            total = evenement.total,
            entreeCourante = evenement.entreeCourante,
            avertissements = evenement.avertissements,
            erreurs = evenement.erreurs,
            phaseDetaillee =
                when (evenement.phaseDetaillee) {
                    jo.lspxml.api.PhaseDetailleeIndexationXml.AUCUNE -> PhaseDetailleeIndexation.AUCUNE
                    jo.lspxml.api.PhaseDetailleeIndexationXml.BIBLIOTHEQUES -> PhaseDetailleeIndexation.BIBLIOTHEQUES
                    jo.lspxml.api.PhaseDetailleeIndexationXml.SOURCES -> PhaseDetailleeIndexation.SOURCES
                },
            listeTravail =
                evenement.listeTravail.map { entree ->
                    EntreeTravailIndexation(
                        nom = entree.nom,
                        etat =
                            when (entree.etat) {
                                jo.lspxml.api.EtatEntreeTravailXml.EN_ATTENTE -> EtatEntreeTravailIndexation.EN_ATTENTE
                                jo.lspxml.api.EtatEntreeTravailXml.ACTIF -> EtatEntreeTravailIndexation.ACTIF
                                jo.lspxml.api.EtatEntreeTravailXml.TERMINE -> EtatEntreeTravailIndexation.TERMINE
                                jo.lspxml.api.EtatEntreeTravailXml.REPRIS -> EtatEntreeTravailIndexation.REPRIS
                                jo.lspxml.api.EtatEntreeTravailXml.ECARTE -> EtatEntreeTravailIndexation.ECARTE
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
     * Archives AAR des DÉPENDANCES du projet (P3, racine de XP1) — la
     * provenance v8 (`ClasspathEntry.aar`, l'AAR d'ORIGINE d'une entrée
     * normalisée) est collectée sur tous les modules actifs, épure les
     * disparus et déduplique (plusieurs entrées peuvent partager le
     * MÊME conteneur) ; l'ordre est TRIÉ (déterministe — le premier AAR
     * d'une homonymie gagne le détail côté moteur).
     */
    private fun aarsDu(projet: ProjetLsp): List<File> {
        val chemins = sortedSetOf<String>()
        for (module in projet.modules) {
            if (module.ignore) continue
            for (entree in module.classpath.entrees) {
                val aar = entree.aar ?: continue
                if (lecteurStats.existe(aar)) chemins.add(aar)
            }
        }
        return chemins.map { File(it) }
    }

    /**
     * Plateforme du projet : le PARENT du `android.jar` du PREMIER
     * module actif qui en porte un (`platforms/android-XX` — le
     * catalogue XE3 vit dans ses `data/`, versionné par plateforme,
     * risque §7 du cadrage) ; `null` = sans catalogue.
     */
    private fun plateformeDu(projet: ProjetLsp): File? =
        projet.modules
            .asSequence()
            .filter { !it.ignore }
            .mapNotNull { it.androidJar }
            .filter { lecteurStats.existe(it) }
            .map { File(it).parentFile }
            .firstOrNull { dossier -> dossier != null && lecteurStats.existe(dossier.absolutePath) }

    /**
     * Racines sources de l'UNION (principal + générés — les TESTS
     * suivront avec le raffinement par module ; miroir de
     * [SessionLspKotlin] : l'index ressources XE3 découvrira les `res/`
     * et le manifest dans ces racines, convention AGP `src/main/…`).
     */
    private fun racinesSources(projet: ProjetLsp): List<File> {
        val racines = LinkedHashSet<File>()
        for (module in projet.modules) {
            if (module.ignore) continue
            (module.sources.principal + module.sources.generes).forEach { racines.add(File(it)) }
        }
        return racines.toList()
    }

    /** Arrêt exhaustif : sessions des connexions jamais arrêtées refermées,
     * décompte vidé, désinscription au surveillant de la session Java
     * (lot L1 — la façade morte ne reçoit plus d'événements) et à
     * l'observateur d'indexation du moteur (ADR 0213).
     * Idempotente (le Java/Kotlin le sont aussi). */
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
                LOGGER.log(Level.WARNING, "fermeture d'une session moteur XML échouée (ignorée)", t)
            }
        }
        sessionsVivantes.clear()
    }

    /** Définition cel-lsp in-process du serveur XML (extension `xml`). */
    private class DefinitionServeurLspXml(
        private val fabrique: () -> LanguageServer,
    ) : LanguageServerDefinition(EXTENSION_XML, NOM) {
        public override fun createConnectionProvider(uri: String): StreamConnectionProvider =
            InProcessStreamConnectionProvider(fabrique)
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(SessionLspXml::class.java.name)

        /** Extension et nom du serveur (langage XML, XE1). */
        private const val EXTENSION_XML: String = "xml"

        private const val NOM: String = "codeide-xml"
    }
}
