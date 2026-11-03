package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.kotlin.api.ClasspathKotlin
import jo.codeide.lsp.kotlin.api.EntreeClasspathKotlin
import jo.codeide.lsp.kotlin.api.KindEntreeKotlin
import jo.codeide.lsp.kotlin.api.PreviewKotlin
import jo.codeide.lsp.kotlin.api.ServiceMoteurLangageKotlin
import jo.codeide.lsp.kotlin.api.SessionLangageKotlin
import jo.codeide.lsp.kotlin.protocole.ServeurLangageKotlin
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.KindEntree
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
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
 * Façade de la session LSP Kotlin d'un projet — miroir volontairement
 * MINCE de [SessionLsp] (Java) : même frontière nette, même cycle de vie
 * piloté par le ViewModel, même transport in-process cel-lsp (ADR
 * 0109 D2 — un changement de transport se brancherait ici sans toucher
 * au moteur ni à l'éditeur).
 *
 * **Bascule ADR 0136 (03/11/2026)** : le serveur servi est désormais le
 * SERVEUR LSP4J DU PACK NEUF (`jo-codeide-lsp-kotlin`, coordinate
 * `jo.codeide.lsp.kotlin:jo-codeide-lsp-kotlin` — l'adaptateur
 * historique `KotlinLanguageServer` est retiré du chemin de connexion,
 * miroir exact de la bascule Java §3.2.2 option B) : chaque connexion
 * reçoit un [ServeurLangageKotlin] dont la session naît du SPI du pack
 * ([ServiceMoteurLangageKotlin.unique] — chemin de production D6, jamais
 * une classe interne) et se nourrit du classpath courant. Le pack ANCIEN
 * (moteur vendored `:lsp:kotlin` de ce dépôt) reste au build, gelé,
 * plus consommé par AUCUN module — son retrait physique suit la règle
 * « 2 versions sans régression » (I6).
 *
 * Le nourrissage suit l'ADR 0211 (une fois par synchronisation) :
 * [appliquerClasspath] / [appliquerModele] convertissent le résultat
 * tooling en entrées NEUTRES du pack neuf (jars/AAR d'existence vérifiée,
 * racines sources, dossier d'index) et les confient au MOTEUR
 * ([ServiceMoteurLangageKotlin.definirClasspath] — le pack neuf est
 * nourricier : la passe part sur son fil dédié, les sessions se branchent
 * sans rien relire). Le pack neuf décodant LUI-MÊME ses jars (index
 * dédié — segments « kotlin-appelables » par empreinte), la VUE d'index fusion
 * de la session Java n'est plus consommée ici — la couture
 * [SessionLsp.vueClasspathKotlin] est retirée avec lui.
 *
 * @param racineProjet racine du projet ouvert (identité de la session).
 * @param moteur SPI du pack langue (injectable pour les tests — la
 *        résolution par défaut passe par [ServiceMoteurLangageKotlin.unique] :
 *        ServiceLoader sur le descripteur `META-INF/services` embarqué
 *        dans le jar du pack, repli moteur noyau du pack).
 * @param lecteurStats couture de statistiques (existence des entrées),
 *        injectable pour les tests.
 * @param dossierIndex répertoire de l'index partagé où persistent les
 *        segments d'appelables du pack (`kotlin-appelables/<sha>.kidx` —
 *        le même contrat d'habitat que le pack ancien : les segments
 *        existants se réutilisent, l'auto-guérison du pack couvre les
 *        autres cas).
 * @param sessionJava session Java du MÊME projet (lot L1) — quand elle
 *        est fournie, la façade s'inscrit à son surveillant de fichiers :
 *        les événements externes (`.kt`/`.kts` modifiés hors éditeur)
 *        atteignent les serveurs Kotlin VIVANTS par le MÊME chemin que
 *        les serveurs Java. `null` = façade autonome (tests, usage hors
 *        projet).
 */
@Suppress("TooManyFunctions")
public class SessionLspKotlin(
    racineProjet: File,
    private val moteur: ServiceMoteurLangageKotlin = ServiceMoteurLangageKotlin.unique,
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
     * Serveurs LSP4J du pack neuf des connexions vivantes (lot L1) — la
     * destination des événements de fichiers externes quand la façade est
     * branchée sur le surveillant de la session Java ; chaque serveur se
     * retire à son arrêt (rappelArret — le serveur referme lui-même sa
     * session à `exit`).
     */
    private val serveursVivants = CopyOnWriteArrayList<ServeurLangageKotlin>()

    /** Classpath courant du pack (poussé au moteur à chaque sync utile). */
    @Volatile
    private var classpathCourant: ClasspathKotlin? = null

    /**
     * Poignée de désinscription au surveillant de la session Java (lot
     * L1) — fermée à [close] : un destinataire mort ne reçoit plus rien.
     */
    private val desinscriptionSurveillance: Closeable? =
        sessionJava?.enregistrerDestinataireFichiers(::diffuserEvenementsFichiers)

    /**
     * Miroir public de l'état d'indexation Kotlin (écran multi-moteurs).
     *
     * **Fenêtre de prise en charge** (bascule ADR 0136) : le pack neuf
     * n'expose PAS la progression fine de sa passe (liste de travail,
     * breakdown, durées) par son SPI — l'état publié ici est une FENÊTRE
     * honnête : CONSTRUCTION au départ de sync, PRET quand le guichet de
     * nourrissage du moteur a PRIS EN CHARGE la passe (retour de
     * [ServiceMoteurLangageKotlin.definirClasspath]). Pendant la fenêtre,
     * la sous-lecture honnête du pack (guichet `indexSiPret` — aucune
     * réponse servie sur un index non couvert) garantit qu'aucun symbole
     * fantôme n'est servi : l'écran ne ment pas, il sous-rapporte.
     */
    private val etatIndexationMutable = MutableStateFlow(EtatIndexation.INITIAL)

    /** État d'indexation Kotlin observable (type public commun aux trois langues). */
    public val etatIndexation: StateFlow<EtatIndexation>
        get() = etatIndexationMutable.asStateFlow()

    private var fermee = false

    /**
     * Diffusion des événements de fichiers externes aux serveurs
     * VIVANTS (lot L1) — chaque poussée est gardée : un serveur en
     * arrêt n'empêche pas les autres (miroir de
     * [SessionLsp.pousserEvenementsFichiers]). Le serveur du pack neuf
     * y rafraîchit le scan des racines sur son FIL DÉDIÉ (le fil
     * JSON-RPC n'attend jamais un scan).
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
     * supplier du transport) : le SERVEUR DU PACK NEUF (bascule ADR
     * 0136 — miroir de [SessionLsp.serveur] côté Java) ; sa session
     * moteur naît du SPI à `initialize` (fabrique ci-dessous), NOURRIE
     * du classpath courant s'il y en a un (K3), suivie ici et refermée
     * par le serveur à `exit` — [close] rattrape les autres.
     */
    public fun serveur(): LanguageServer {
        synchronized(this) {
            check(!fermee) { "SessionLspKotlin fermée — créer une nouvelle session" }
        }
        // Le rappel aLArret capture le serveur et SA session par variables
        // locales — affectées une fois construites (même patron que
        // [SessionLsp.serveur] côté Java).
        var inscrit: ServeurLangageKotlin? = null
        var sessionInscrite: SessionLangageKotlin? = null
        val serveur =
            ServeurLangageKotlin(
                fabriqueSession = { racine ->
                    val sessionLangage = moteur.creerSession()
                    val classpath = synchronized(this) { classpathCourant }
                    // Le BRANCHEMENT initial est GARDÉ : un pack exotique
                    // qui refuse la pose (poussée par session du monde
                    // d'avant) ne doit pas tuer la CONNEXION — la session
                    // vit, les poussées suivantes sont gardées de même.
                    try {
                        sessionLangage.definirClasspath(
                            classpath
                                ?: ClasspathKotlin(
                                    entrees = emptyList(),
                                    racinesSources =
                                        racine?.let { listOf(it.toString()) } ?: emptyList(),
                                    dossierIndex = dossierIndex?.absolutePath,
                                ),
                        )
                    } catch (t: Throwable) {
                        LOGGER.log(
                            Level.WARNING,
                            "branchement initial d'une session moteur refusé (ignoré)",
                            t,
                        )
                    }
                    synchronized(this) {
                        if (!fermee) {
                            sessionsVivantes.add(sessionLangage)
                            sessionInscrite = sessionLangage
                        }
                    }
                    sessionLangage
                },
                rappelArret = {
                    inscrit?.let { vivant -> serveursVivants.remove(vivant) }
                    sessionInscrite?.let { session -> sessionsVivantes.remove(session) }
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
     * complète (modèle v8 → entrées neutres du pack + racines sources).
     *
     * Tolérant : les entrées INEXISTANTES sont filtrées ici (le pack
     * n'a pas à deviner) ; un appel REDONDANT est un no-op chaud
     * (segments par empreinte côté moteur).
     *
     * @param resultat résultat BRUT du protocole tooling (post-sync).
     */
    public fun appliquerClasspath(resultat: ClasspathResult) {
        val projet = ConvertisseurModeleLsp.depuisClasspathResult(resultat, lecteurStats)
        appliquerModele(projet)
    }

    /**
     * Applique un modèle DÉJÀ construit (K3, réouverture : le modèle
     * PERSISTÉ W7 de la session Java — même pipeline que
     * [SessionLsp.nourrirDepuisModelePersiste] côté Java) : le pack
     * retrouve ses segments QUASI INSTANTANÉMENT, la sync fraîche
     * remplacera si l'empreinte a changé.
     */
    public fun appliquerModele(projet: ProjetLsp) {
        val classpath: ClasspathKotlin
        synchronized(this) {
            if (fermee) return
            classpath =
                ClasspathKotlin(
                    entrees = entreesIndexables(projet),
                    racinesSources = racinesSources(projet).map { it.absolutePath },
                    dossierIndex = dossierIndex?.absolutePath,
                )
            classpathCourant = classpath
        }
        // ADR 0211 (demande D1) : nourrissage MOTEUR une fois par sync,
        // sur le fil dédié du pack — le supplier de connexion ne paie
        // plus RIEN. `false` = pack exotique sans nourrissage moteur : la
        // poussée SYNCHRONE ci-dessous reste LE nourrissage (tolérance
        // de version du contrat D6, monde d'avant).
        etatIndexationMutable.value =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val prisEnCharge =
            try {
                moteur.definirClasspath(classpath)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "nourrissage moteur refusé (poussée par session)", t)
                false
            }
        if (!prisEnCharge) {
            // Pack exotique : la poussée par session a nourri
            // SYNCHRONEMENT — fenêtre PRET honnête (jamais de PARTIEL
            // inventé, le pack n'est pas observable).
            sessionsVivantes.forEach { session ->
                try {
                    session.definirClasspath(classpath)
                } catch (t: Throwable) {
                    LOGGER.log(
                        Level.WARNING,
                        "poussée de classpath à une session moteur échouée (ignorée)",
                        t,
                    )
                }
            }
        }
        // Fenêtre de prise en charge (KDoc de [etatIndexationMutable]) :
        // le guichet du moteur a rendu son verdict — le pack neuf y est
        // TOUJOURS nourricier (retour immédiat, la passe part sur son
        // fil dédié, les requêtes sous-lisent honnêtement tant que les
        // segments ne sont pas couverts).
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

    /**
     * Annule le passage d'indexation Kotlin EN VOL — **fenêtre honnête
     * de la bascule ADR 0136** : le pack neuf n'expose pas l'annulation
     * coopérative par son SPI (ADR 0214 D5 côté ancien) ; la méthode
     * reste au contrat public (l'agrégat CodeIDE l'appelle pour les
     * trois langues) et rend `false` : AUCUN passage n'est déclaré
     * annulable, le bouton ne compte pas le Kotlin. L'annulation
     * redevient réelle quand le pack expose le guichet (vague
     * capacités).
     *
     * @return `false` (aucun passage annulable via le pack neuf).
     */
    public fun annulerPassage(): Boolean {
        // Journal unique : le no-op est un état CONNU, pas une panne.
        LOGGER.fine("annulation de passage non exposée par le pack neuf (no-op honnête)")
        return false
    }

    /**
     * Retente les ÉCHECS du dernier passage — même fenêtre honnête que
     * [annulerPassage] : le pack neuf ne l'expose pas par son SPI ;
     * `false` = rien à retenter.
     *
     * @return `false` (aucune reprise exposée par le pack neuf).
     */
    public fun reesayerEchecs(): Boolean {
        LOGGER.fine("reprise des échecs non exposée par le pack neuf (no-op honnête)")
        return false
    }

    /**
     * Union des entrées JAR/AAR EXISTANTES de tous les modules +
     * plateforme android.jar des modules actifs (miroir volontairement
     * MINCE d'unionIndexable/jarsDe de l'orchestrateur Java — mêmes
     * sémantiques : première occurrence gardée, existence vérifiée ;
     * le pack consomme l'UNION, pas de contexte par module à K3).
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
     * passe-plat vers les sessions moteur VIVANTES du pack — la session
     * qui détient le document ouvert répond, les autres rendent VIDE
     * (première réponse non vide emportée). Aucune session vivante :
     * liste VIDE, jamais d'exception — le consommateur dégrade honnêtement
     * (pas de carte d'aperçu Compose).
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

    /**
     * Arrêt exhaustif : sessions des connexions jamais arrêtées refermées,
     * décompte vidé, désinscription au surveillant de la session Java
     * (lot L1 — la façade morte ne reçoit plus d'événements).
     * Idempotente (le Java l'est aussi — contrat h1).
     */
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
