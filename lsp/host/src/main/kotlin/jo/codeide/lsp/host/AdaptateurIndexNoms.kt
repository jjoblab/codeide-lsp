package jo.codeide.lsp.host

import jo.codeide.lsp.index.Conteneur
import jo.codeide.lsp.index.PhaseIndex
import jo.codeide.lsp.index.VueIndex
import jo.codeide.lsp.java.IndexNoms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Adaptateur de PRODUCTION de la SPI [IndexNoms] sur la vue d'index
 * disque (mission « LSP Java » étape 4 — l'homologue de TEST vit dans
 * `:lsp:testing` depuis v0.96.3).
 *
 * **Bascule moteur neuf (v1.23.0, INTEGRATION-CODEIDE §3.2.2)** : la SPI
 * est désormais l'interface du moteur RÉÉCRIT (`jo.codeide.lsp.java.IndexNoms`)
 * — les signatures traduites : `jarDe` rend le CHEMIN du conteneur
 * (String — le moteur neuf lit le jar par chemin), `nomsParPrefixe` rend
 * les NOMS SIMPLES seuls (le matcher gradué du moteur fait le reste), et
 * `fqnsForSimpleName` (nouveau) sert la complétion d'imports par nom
 * exact — préfixe = nom, filtre d'égalité en aval (miroir de
 * `:lsp:testing` de code-lsp).
 *
 * Différences avec l'adaptateur de test, toutes deux VOLONTAIRES :
 *  - les clés de segments viennent du MODÈLE (empreintes W6
 *    `maven:…`/`transform:…` portées par `ClasspathInstantane` — la
 *    production ne relit JAMAIS les jars pour hacher, contrairement au
 *    sha256 de test) ;
 *  - la transition PRÊT vit par COLLECTE du `StateFlow` d'état dans le
 *    scope de la session — pas de fil surveillant à interrompre.
 *
 * Contrat P7/P8 (lot C) : les observateurs sont notifiés UNE fois à la
 * transition PRÊT (le moteur y vide son cache de localisation et passe
 * le localisateur en AUTORITATIF). Un état PARTIEL/ERREUR ne notifie
 * jamais : l'index reste un repli, jamais une autorité mensongère.
 *
 * @param vue la vue d'index du classpath FUSION (union des modules).
 * @param scope scope de la session (annulé à l'arrêt — la collecte
 *        s'arrête avec).
 */
internal class AdaptateurIndexNoms(
    private val vue: VueIndex,
    scope: CoroutineScope,
) : IndexNoms {
    init {
        // La collecte vit dans le scope de la session : annulée AVEC
        // lui (close) — pas de référence à retenir au-delà.
        scope.launch { observerTransitionPret() }
    }

    /** Observateurs P7/P8 — notifiés une fois, hors verrou d'état. */
    private val observateurs = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Miroir volatil de la phase PRÊT (lecture sans suspension). */
    @Volatile
    private var pret: Boolean = false

    override fun estPret(): Boolean = pret || vue.etat.value.phase == PhaseIndex.PRET

    override fun ajouterObservateurPret(observateur: () -> Unit) {
        if (estPret()) {
            // Déjà prêt : notification immédiate, une fois (contrat SPI).
            observateur()
            return
        }
        observateurs.add(observateur)
    }

    // ReturnCount justifié (règle 8) : chaque clause de garde (vide,
    // introuvable, conteneur non-jar, levée) est une ISSUE distincte.
    @Suppress("ReturnCount")
    override fun jarDe(fqcn: String): String? {
        if (fqcn.isEmpty()) return null
        return try {
            val localisation = vue.localiser(fqcn) ?: return null
            val conteneur = localisation.conteneur
            if (conteneur is Conteneur.Jar) {
                // Le moteur neuf consomme le CHEMIN du jar (String) — la
                // lecture brute (LecteurZipBrut) est la sienne.
                conteneur.chemin
            } else {
                // Dossier (sortie d'un module) : hors index de jars —
                // retour honnête, le repli disque du moteur tranche.
                null
            }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "localiser a levé — $fqcn", t)
            null
        }
    }

    override fun nomsParPrefixe(
        prefixe: String,
        limite: Int,
    ): List<String> =
        try {
            vue.nomsParPrefixe(prefixe, limite.coerceAtLeast(1)).map { it.nomSimple }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "nomsParPrefixe a levé — $prefixe", t)
            emptyList()
        }

    override fun fqnsForSimpleName(nom: String): List<String> =
        try {
            vue
                .nomsParPrefixe(nom, LIMITE_RECHERCHE_EXACTE)
                .filter { it.nomSimple == nom }
                .flatMap { it.fqcns }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "fqnsForSimpleName a levé — $nom", t)
            emptyList()
        }

    override fun estPaquet(paquet: String): Boolean =
        try {
            vue.estPaquet(paquet)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "estPaquet a levé — $paquet", t)
            false
        }

    override fun sousPaquetsDirects(paquet: String): List<String> =
        try {
            vue.sousPaquetsDirects(paquet)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "sousPaquetsDirects a levé — $paquet", t)
            emptyList()
        }

    override fun typesDansPaquet(paquet: String): List<String> =
        try {
            vue.typesDansPaquet(paquet)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "typesDansPaquet a levé — $paquet", t)
            emptyList()
        }

    override fun membresStatiques(fqcn: String): List<String> =
        try {
            vue.membresStatiques(fqcn).map { it.nom }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "membresStatiques a levé — $fqcn", t)
            emptyList()
        }

    /** Attend la transition PRÊT et notifie les observateurs (une fois). */
    private suspend fun observerTransitionPret() {
        vue.etat.collect { etat ->
            if (etat.phase == PhaseIndex.PRET) {
                pret = true
                val aNotifier = observateurs.toList()
                observateurs.clear()
                for (observateur in aNotifier) {
                    try {
                        observateur()
                    } catch (t: Throwable) {
                        // Un observateur fautif ne bloque pas les autres —
                        // journalisé, jamais avalé.
                        LOGGER.log(Level.WARNING, "observateur PRÊT fautif — ignoré", t)
                    }
                }
            }
            // PARTIEL/ERREUR : jamais « prêt » — l'index reste un REPLI,
            // pas une autorité ; CONSTRUCTION : la transition viendra.
        }
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger("AdaptateurIndexNoms")

        /** Garde des recherches de nom exact (préfixe = nom, égalité filtrée en aval). */
        private const val LIMITE_RECHERCHE_EXACTE: Int = 1_000
    }
}
