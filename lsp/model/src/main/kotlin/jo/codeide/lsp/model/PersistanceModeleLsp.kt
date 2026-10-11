package jo.codeide.lsp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistance versionnée du [ProjetLsp] (prompt 2 §7.1 — corrige W7) :
 * le modèle sérialisé sous `.codeide/local/lsp-modele.json` est utilisable
 * immédiatement au démarrage, mais VALIDÉ à la lecture (taille et
 * horodatage des entrées fichier) : tout écart marque le modèle non
 * vérifié — écart SIGNALÉ, jamais silencieux, jamais fatal. La fraîcheur
 * définitive vient de la synchronisation suivante, qui remplace le modèle
 * (le [DepotModeleLsp] publie alors l'instantané neuf).
 *
 * Format : JSON [ModelePersisteDto] avec version de format — une version
 * inconnue (modèle écrit par une étape future) est REFUSÉE proprement
 * (`null` : reconstruction depuis le classpath, pas de crash).
 */
public class PersistanceModeleLsp {
    /** Version courante du format de persistance. */
    public val formatVersion: Int = FORMAT_VERSION

    /** Écrit le modèle vers [fichier] (répertoire parent créé si absent). */
    public fun ecrire(
        projet: ProjetLsp,
        fichier: File,
    ) {
        fichier.parentFile?.mkdirs()
        fichier.writeText(
            json.encodeToString(ModelePersisteDto.serializer(), ModelePersisteDto(FORMAT_VERSION, projet)),
        )
    }

    /**
     * Lit et VALIDE un modèle persisté : chaque entrée fichier (JAR, AAR,
     * dossier) est recoupée avec le système via [stats] — un jar disparu,
     * retaillé ou retouché produit un écart listé (l'entrée reste dans le
     * modèle : c'est l'état, pas une amputation).
     *
     * @return `null` si le fichier est absent, illisible, ou d'une version
     *         de format inconnue (refus propre — jamais de crash).
     */
    @Suppress("ReturnCount")
    public fun lire(
        fichier: File,
        stats: LecteurStatistiquesFichier,
    ): ModelePersiste? {
        if (!fichier.isFile) {
            return null
        }
        // SwallowedException justifié (règle 8) : un JSON corrompu ou
        // tronqué (mort du process pendant l'écriture) est REFUSÉ
        // proprement — l'app reconstruit depuis le classpath (tolérance à
        // la corruption, ADR 0111), jamais de crash.
        @Suppress("SwallowedException")
        val dto =
            try {
                json.decodeFromString(ModelePersisteDto.serializer(), fichier.readText())
            } catch (e: Exception) {
                return null
            }
        if (dto.format != FORMAT_VERSION) {
            return null
        }
        return ModelePersiste(projet = dto.projet, verification = valider(dto.projet, stats))
    }

    /** Recoupe les entrées fichier du modèle avec le système (W7 :
     *  taille/horodatage des jars). Les sauts anticipés distinguent les
     *  TROIS écarts (disparu, retaille, retouche) — chacun a son message. */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun valider(
        projet: ProjetLsp,
        stats: LecteurStatistiquesFichier,
    ): EtatVerification {
        val ecarts = mutableListOf<String>()
        var nbEntrees = 0
        for (module in projet.modules) {
            for (entree in module.classpath.entrees) {
                if (entree.kind == KindEntree.MODULE) {
                    continue
                }
                nbEntrees++
                if (!stats.existe(entree.chemin)) {
                    ecarts += "${module.nom} : ${entree.chemin} a disparu"
                    continue
                }
                val taille = stats.taille(entree.chemin)
                if (taille != entree.taille) {
                    ecarts += "${module.nom} : ${entree.chemin} a changé de taille (${entree.taille} octets → $taille)"
                    continue
                }
                val mtime = stats.mtime(entree.chemin)
                if (mtime != entree.mtime) {
                    ecarts += "${module.nom} : ${entree.chemin} a été retouché (horodatage)"
                }
            }
            module.androidJar?.let { jar ->
                nbEntrees++
                if (!stats.existe(jar)) {
                    ecarts += "${module.nom} : android.jar absent ($jar)"
                }
            }
        }
        return if (ecarts.isEmpty()) {
            EtatVerification.Coherant(nbEntrees = nbEntrees)
        } else {
            EtatVerification.NonVerifie(ecarts = ecarts)
        }
    }

    private companion object {
        private const val FORMAT_VERSION: Int = 1

        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            }
    }

    /** Enveloppe sérialisée avec version de format. */
    @Serializable
    private data class ModelePersisteDto(
        val format: Int,
        val projet: ProjetLsp,
    )
}

/** Modèle persisté relu, avec son état de vérification (W7). */
public data class ModelePersiste(
    public val projet: ProjetLsp,
    public val verification: EtatVerification,
)

/**
 * État de vérification d'un modèle relu (W7) : un modèle NON vérifié reste
 * UTILISABLE immédiatement — la prochaine synchronisation le remplacera.
 */
public sealed interface EtatVerification {
    /** Toutes les entrées fichier correspondent (taille et mtime). */
    public data class Coherant(
        public val nbEntrees: Int,
    ) : EtatVerification

    /** Des écarts existent : listés, le modèle reste utilisable. */
    public data class NonVerifie(
        public val ecarts: List<String>,
    ) : EtatVerification
}
