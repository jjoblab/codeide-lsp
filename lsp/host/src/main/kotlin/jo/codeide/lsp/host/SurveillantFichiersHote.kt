package jo.codeide.lsp.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import java.io.File

/**
 * Surveillant de fichiers du projet côté HÔTE (S1-d « watched files »
 * du plan de stabilisation §3.1 — I4 de l'audit
 * rapport-analyse-lsp-java : le handler serveur
 * {@code didChangeWatchedFiles} invalide le cache de parse, mais AUCUN
 * watcher ne poussait d'événements — voie protocole muette).
 *
 * Remède (a) de l'audit, borné : un BALAYAGE PÉRIODIQUE du répertoire
 * projet (pas d'android.os.FileObserver — la façade vit aussi sur JVM
 * de bureau et le balayage est testable tel quel), extensions limitées
 * aux langues servies par l'hôte (`.java`, `.kt`, `.kts`, `.xml` — lot
 * L1 du plan 1.3.0 : les événements externes atteignent les TROIS
 * serveurs, plus Java seul à l'origine S1-d). Chaque tour : instantané
 * (chemin → mtime), diff avec le précédent → {@code FileEvent} Changed/
 * Created/Deleted → poussés aux serveurs VIVANTS (le premier tour
 * installe la BASELINE : zéro événement synthétique au démarrage).
 *
 * Tolérance (D3) : un tour fautif (dossier illisible, pousse levée)
 * n'arrête JAMAIS la boucle — l'événement manquant est rattrapé au
 * tour suivant. Coût : un walk borné [BORN_FICHIERS] par période —
 * le même plafond que le scan des moteurs.
 *
 * @property racine racine du projet ouvert.
 * @property periodeMs période du balayage (≤ 0 : surveillant NEUTRE —
 *          jamais lancé, les tests pilotent [unTour] à la main).
 */
internal class SurveillantFichiersHote(
    private val racine: File,
    private val periodeMs: Long,
) {
    /** Destination des événements (miroir de la notification LSP). */
    internal fun interface Destination {
        fun recevoir(params: DidChangeWatchedFilesParams)
    }

    /**
     * Installe la baseline (premier instantané SANS événements) puis
     * boucle : diff → événements → destination. Le job vit dans le
     * scope de la façade (annulé à close) ; le périodique est
     * STABLE aux dérive d'horloge (mtime seul, jamais l'heure murale).
     */
    internal fun boucle(
        scope: CoroutineScope,
        dispatcheur: CoroutineDispatcher,
        destination: Destination,
    ): Job {
        if (periodeMs <= 0) return Job()
        return scope.launch(dispatcheur) {
            var precedent = instantane()
            while (isActive) {
                delay(periodeMs)
                val courant = instantane()
                val evenements = evenementsEntre(precedent, courant)
                precedent = courant
                if (evenements.isNotEmpty()) {
                    try {
                        destination.recevoir(DidChangeWatchedFilesParams(evenements))
                    } catch (t: Throwable) {
                        // Destination fautive (serveur en arrêt) — le
                        // tour suivant retentera (baseline déjà mise à
                        // jour : pas de spam d'événements morts).
                        LoggerHote.journal.warning(
                            "poussée watched-files échouée (ignorée) : $t",
                        )
                    }
                }
            }
        }
    }

    /**
     * UN tour de diff SYNCHRONE (outillage de test + future
     * consommation « à la demande ») : diff [avant] → [apres],
     * évènements en ordre du chemin.
     */
    internal fun unTour(
        avant: Map<String, Long>,
        apres: Map<String, Long>,
    ): List<FileEvent> = evenementsEntre(avant, apres)

    /**
     * Instantané des fichiers surveillés : chemin absolu → mtime. Walk
     * borné (plafond du scan), dossiers cachés/build exclus — le même
     * périmètre que les balayages des moteurs (cohérence des invalidations).
     */
    internal fun instantane(): Map<String, Long> {
        val resultat = LinkedHashMap<String, Long>()
        parcourir(racine, resultat)
        return resultat
    }

    /**
     * Diff d'instantanés : mtime changé = Changed, apparu = Created,
     * disparu = Deleted — en ordre du chemin (deterministe, testable).
     */
    private fun evenementsEntre(
        avant: Map<String, Long>,
        apres: Map<String, Long>,
    ): List<FileEvent> {
        val evenements = mutableListOf<FileEvent>()
        for ((chemin, mtime) in apres) {
            val ancien = avant[chemin]
            when {
                ancien == null -> evenements.add(evenement(chemin, FileChangeType.Created))
                ancien != mtime -> evenements.add(evenement(chemin, FileChangeType.Changed))
            }
        }
        for (chemin in avant.keys) {
            if (chemin !in apres) evenements.add(evenement(chemin, FileChangeType.Deleted))
        }
        return evenements.sortedBy { it.uri }
    }

    /** Event LSP avec l'URI file:// du chemin (convention des moteurs). */
    private fun evenement(
        chemin: String,
        type: FileChangeType,
    ): FileEvent = FileEvent(File(chemin).toURI().toString(), type)

    /**
     * Marche récursive bornée (miroir ScanProjet.parcourir côté Kotlin) :
     * les extensions des TROIS langues servies (`.java`, `.kt`, `.kts`,
     * `.xml` — lot L1), dossiers build/.git/.gradle et cachés exclus,
     * plafond [BORN_FICHIERS].
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/LoopWithTooManyJump-
     * Statements — les bornes (plafond atteint, dossier illisible,
     * exclusion) sont des ISSUES du parcours borné, miroir du scan.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun parcourir(
        dossier: File,
        resultat: LinkedHashMap<String, Long>,
    ) {
        if (resultat.size >= BORN_FICHIERS) return
        val enfants = dossier.listFiles() ?: return
        for (enfant in enfants.sortedBy { it.name }) {
            if (resultat.size >= BORN_FICHIERS) return
            when {
                enfant.isDirectory -> {
                    if (enfant.name !in EXCLUSIONS && !enfant.name.startsWith(".")) {
                        parcourir(enfant, resultat)
                    }
                }

                enfant.isFile && enfant.extension in EXTENSIONS_SUIVIES -> {
                    resultat[enfant.absolutePath] = enfant.lastModified()
                }
            }
        }
    }

    private companion object {
        /** Borne de fichiers suivis (troncation honnête au-delà). */
        private const val BORN_FICHIERS: Int = 2000

        /** Dossiers exclus (sorties, caches — mêmes que les scans). */
        private val EXCLUSIONS: Set<String> = setOf("build", ".git", ".gradle")

        /**
         * Extensions suivies (lot L1) : les trois langues de l'hôte —
         * un événement externe Kotlin/XML rafraîchit désormais les
         * diagnostics du serveur correspondant (miroir du Java S1-d).
         * Le mode script `.kts` est couvert par son extension propre.
         */
        private val EXTENSIONS_SUIVIES: Set<String> = setOf("java", "kt", "kts", "xml")
    }
}

/** Journal partagé de l'hôte (micro-filet, aucun framework). */
internal object LoggerHote {
    val journal: java.util.logging.Logger =
        java.util.logging.Logger
            .getLogger("codeide-lsp-host")
}
