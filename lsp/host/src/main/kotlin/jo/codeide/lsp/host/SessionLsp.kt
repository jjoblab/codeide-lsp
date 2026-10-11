package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.codeide.lsp.model.DepotModeleLsp
import jo.codeide.lsp.model.LecteurStatistiquesFichier
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspjava.engine.CompilationEnvironment
import jo.lspjava.lsp.JavaLanguageServer
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
import org.eclipse.lsp4j.services.LanguageServer
import java.io.Closeable
import java.io.File
import java.nio.file.Path
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
 * @param parentJob parent optionnel des coroutines de la session
 *        (l'app peut tout arrêter d'un coup) ; la session reste
 *        responsable de son arrêt PROPRE via [close].
 */
public class SessionLsp(
    racineProjet: File,
    stockageIndex: File =
        jo.codeide.lsp.index.ServiceIndexDisque
            .emplacementParDefaut(),
    dispatcheurIo: CoroutineDispatcher = Dispatchers.IO,
    lecteurStats: LecteurStatistiquesFichier = LecteurStatistiquesFichierDisque(),
    jdkHome: Path? = null,
    parentJob: Job? = null,
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Scope possédé par la session (fermé dans [close]). */
    private val scope = CoroutineScope(SupervisorJob(parentJob) + dispatcheurIo)

    /** Environnement de compilation partagé (plateforme + moteurs par module). */
    private val environnement = CompilationEnvironment()

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

    /** Miroir public de l'état d'indexation (consommé par l'UI, h2). */
    private val etatIndexationMutable = MutableStateFlow(EtatIndexation.INITIAL)

    /** Collecte courante de l'état de la vue fusion. */
    private var collecteEtat: Job? = null

    private var fermee = false

    init {
        if (jdkHome != null) {
            environnement.setJdkHome(jdkHome)
        }
        orchestrateur.purgerTemporaires()
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
     * chaud (sémantiques lot E).
     */
    public fun appliquerClasspath(resultat: ClasspathResult): ResultatApplication {
        verifierOuverte()
        val application = orchestrateur.appliquer(resultat)
        relancerCollecteEtat()
        return application.versPublic()
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
     * UN serveur LSP4J in-process pour une connexion (cel-lsp
     * approvisionne par le supplier du transport — jamais mis en cache :
     * chaque connexion vit son cycle initialize/shutdown/exit LSP 3.17).
     */
    public fun serveur(): LanguageServer {
        verifierOuverte()
        return JavaLanguageServer(environnement)
    }

    /**
     * La définition cel-lsp du serveur Java de CodeIDE — à enregistrer
     * dans un `LspProject` (h2, branchement éditeur) : extension
     * `java`, transport IN-PROCESS (ADR 0109).
     */
    public fun definition(): LanguageServerDefinition = DefinitionServeurLspJava { serveur() }

    /** Arrêt exhaustif : coroutines annulées, moteurs disposés, modèle vidé. */
    public override fun close() {
        if (fermee) return
        fermee = true
        try {
            scope.cancel("SessionLsp fermée")
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "annulation du scope échouée", t)
        }
        try {
            environnement.dispose()
        } catch (t: Throwable) {
            LOGGER.log(Level.WARNING, "dispose de l'environnement échoué", t)
        }
        depot.vider()
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
                    etatIndexationMutable.value =
                        EtatIndexation(
                            phase =
                                when (etat.phase) {
                                    jo.codeide.lsp.index.PhaseIndex.CONSTRUCTION -> PhaseIndexation.CONSTRUCTION
                                    jo.codeide.lsp.index.PhaseIndex.PRET -> PhaseIndexation.PRET
                                    jo.codeide.lsp.index.PhaseIndex.PARTIEL -> PhaseIndexation.PARTIEL
                                    jo.codeide.lsp.index.PhaseIndex.ERREUR -> PhaseIndexation.ERREUR
                                },
                            faits = etat.faits,
                            total = etat.total,
                            entreeCourante = etat.entreeCourante,
                            avertissements = etat.avertissements,
                            erreurs = etat.erreurs,
                        )
                }
            }
    }

    /** Définition cel-lsp in-process du serveur Java. */
    private class DefinitionServeurLspJava(
        private val fabrique: () -> LanguageServer,
    ) : LanguageServerDefinition(EXTENSION, NOM) {
        public override fun createConnectionProvider(uri: String): StreamConnectionProvider =
            InProcessStreamConnectionProvider(fabrique)
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger("SessionLsp")

        /** Extension et nom du serveur (langage Java, ADR 0109). */
        private const val EXTENSION: String = "java"
        private const val NOM: String = "codeide-java"
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

/** État d'indexation exposé à l'UI (découplé des types internes d'index). */
public data class EtatIndexation(
    public val phase: PhaseIndexation,
    public val faits: Int,
    public val total: Int,
    public val entreeCourante: String?,
    public val avertissements: List<String>,
    public val erreurs: List<String>,
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
