package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.model.ConvertisseurModeleLsp
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.MoteurLangageXml
import jo.lspxml.api.ServiceMoteurLangageXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.api.VueNomsXml
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
        // Poussé HORS verrou : les sessions moteur sérialisent en
        // interne, la façade reste responsive (le push est idempotent).
        sessionsVivantes.forEach { session ->
            try {
                session.definirClasspath(classpathCourant!!)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "poussée de classpath à une session XML échouée (ignorée)", t)
            }
        }
    }

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
     * (lot L1 — la façade morte ne reçoit plus d'événements).
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
