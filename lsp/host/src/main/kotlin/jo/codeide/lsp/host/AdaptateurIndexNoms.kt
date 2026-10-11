package jo.codeide.lsp.host

import jo.codeide.lsp.index.Conteneur
import jo.codeide.lsp.index.DRAPEAU_STATIC
import jo.codeide.lsp.index.PhaseIndex
import jo.codeide.lsp.index.VueIndex
import jo.lspjava.api.IndexNoms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Adaptateur de PRODUCTION de la SPI [IndexNoms] sur la vue d'index
 * disque (mission « LSP Java » étape 4 — l'homologue de TEST vit dans
 * `:lsp:testing` depuis v0.96.3).
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
    private val observateurs = CopyOnWriteArrayList<Runnable>()

    /** Miroir volatil de la phase PRÊT (lecture sans suspension). */
    @Volatile
    private var pret: Boolean = false

    override fun estPret(): Boolean = pret || vue.etat.value.phase == PhaseIndex.PRET

    override fun ajouterObservateurPret(observateur: Runnable) {
        if (estPret()) {
            // Déjà prêt : notification immédiate, une fois (contrat SPI).
            observateur.run()
            return
        }
        observateurs.add(observateur)
    }

    // ReturnCount justifié (règle 8) : chaque clause de garde (vide,
    // introuvable, conteneur non-jar, levée) est une ISSUE distincte.
    @Suppress("ReturnCount")
    override fun jarDe(fqcn: String): Path? {
        if (fqcn.isEmpty()) return null
        return try {
            val localisation = vue.localiser(fqcn) ?: return null
            val conteneur = localisation.conteneur
            if (conteneur is Conteneur.Jar) {
                // File.toPath() (API 26) plutôt que Path.of (API 34) —
                // même résultat, minSdk respecté.
                File(conteneur.chemin).toPath()
            } else {
                // Dossier (sortie d'un module) : illisible côté moteur —
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
    ): Map<String, List<String>> {
        val fusion = LinkedHashMap<String, MutableList<String>>()
        try {
            val noms = vue.nomsParPrefixe(prefixe, limite) ?: return fusion
            for (nom in noms) {
                fusion.getOrPut(nom.nomSimple) { mutableListOf() }.addAll(nom.fqcns)
            }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "nomsParPrefixe a levé — $prefixe", t)
        }
        return fusion
    }

    override fun estPaquet(nom: String): Boolean =
        try {
            vue.estPaquet(nom)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "estPaquet a levé — $nom", t)
            false
        }

    override fun sousPaquetsDirects(nom: String): List<String> =
        try {
            vue.sousPaquetsDirects(nom)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "sousPaquetsDirects a levé — $nom", t)
            emptyList()
        }

    override fun typesDansPaquet(nom: String): List<String> =
        try {
            vue.typesDansPaquet(nom)
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "typesDansPaquet a levé — $nom", t)
            emptyList()
        }

    override fun membresStatiques(fqcn: String): List<String> {
        val noms = ArrayList<String>()
        try {
            val membres = vue.membres(fqcn) ?: return noms
            for (champ in membres.champs) {
                if (champ.drapeaux and DRAPEAU_STATIC != 0) noms.add(champ.nom)
            }
            for (methode in membres.methodes) {
                if (methode.drapeaux and DRAPEAU_STATIC != 0) noms.add(methode.nom)
            }
        } catch (t: Throwable) {
            LOGGER.log(Level.FINE, "membresStatiques a levé — $fqcn", t)
        }
        return noms
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
                        observateur.run()
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
    }
}
