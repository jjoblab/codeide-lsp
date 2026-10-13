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
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
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
 */
@Suppress("LongParameterList")
public class SessionLspKotlin(
    racineProjet: File,
    private val moteur: MoteurLangageKotlin = ServiceMoteurLangageKotlin.unique(),
    private val lecteurStats: LecteurStatistiquesFichier = LecteurStatistiquesFichierDisque(),
    private val dossierIndex: File? =
        jo.codeide.lsp.index.ServiceIndexDisque
            .emplacementParDefaut(),
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Sessions moteur des connexions vivantes (fermées à [close] aussi). */
    private val sessionsVivantes = CopyOnWriteArrayList<SessionLangageKotlin>()

    /** Classpath courant (poussé aux sessions futures à la création). */
    @Volatile
    private var classpathCourant: ClasspathKotlin? = null

    private var fermee = false

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
        return KotlinLanguageServer(
            session = sessionLangage,
            versionServeur = moteur.version,
            aLArret = { sessionsVivantes.remove(sessionLangage) },
        )
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
        // Poussé HORS verrou : les sessions moteur sérialisent en
        // interne, la façade reste responsive (le push est idempotent).
        sessionsVivantes.forEach { session ->
            try {
                session.definirClasspath(classpathCourant!!)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "poussée de classpath à une session moteur échouée (ignorée)", t)
            }
        }
    }

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

    /** Arrêt exhaustif : sessions des connexions jamais arrêtées refermées,
     * décompte vidé. Idempotente (le Java l'est aussi — contrat h1). */
    public override fun close() {
        synchronized(this) {
            if (fermee) return
            fermee = true
        }
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
