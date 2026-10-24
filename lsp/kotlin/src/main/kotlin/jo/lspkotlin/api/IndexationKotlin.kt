package jo.lspkotlin.api

import java.io.Closeable

/**
 * Types NEUTRES du contrat d'indexation observable du moteur Kotlin
 * (ADR 0213 — demande D3 de CodeIDE, écran d'indexation multi-moteurs
 * v0.178.0) : le moteur publie sa progression à des
 * [ObservateurIndexationKotlin] enregistrés par
 * [MoteurLangageKotlin.enregistrerObservateurIndexation] ; la FAÇADE
 * hôte traduit [EvenementIndexationKotlin] en `EtatIndexation` public
 * (`lsp:host`, le MÊME type que Java).
 *
 * DÉLIBÉRÉMENT sans kotlinx-coroutines : le pack langue est une entité
 * indépendante (décision D6 — distribuable en pack téléchargé), le SPI
 * ne doit pas imposer de runtime de concurrence à un pack tiers. Un
 * observateur SAM + des data classes suffisent : l'hôte n'a besoin que
 * de l'état courant et de ses mises à jour.
 */

/**
 * Phase d'un passage d'indexation — miroir volontaire de
 * `PhaseIndexation` (`lsp:host`) et `PhaseIndex` (`lsp:index`).
 *
 * ERREUR est réservé par stabilité de « format » (le moteur Kotlin n'a
 * pas de mode d'erreur fatale : un segment illisible est un écart
 * honnête → PARTIEL) ; l'ajouter maintenant évite une casse de contrat
 * si un mode fatal apparaît (l'énumération est le « format » du pack).
 */
public enum class PhaseIndexationKotlin {
    /** Aucun nourrissage demandé (avant la première sync). */
    INITIAL,

    /** Passage en cours (segments puis scan des sources). */
    CONSTRUCTION,

    /** Passage abouti sans écart. */
    PRET,

    /** Passage abouti AVEC écarts (entrées écartées, passage annulé). */
    PARTIEL,

    /** Réservé — erreur fatale de passage (jamais émis à ce jour). */
    ERREUR,
}

/** Phase détaillée — affinage pendant CONSTRUCTION (miroir Java). */
public enum class PhaseDetailleeIndexationKotlin {
    /** Hors construction (initial, final). */
    AUCUNE,

    /** Chargement des segments kidx du classpath (remplacement ou décodage à froid). */
    BIBLIOTHEQUES,

    /** Scan WARM des sources du projet (fichiers des racines). */
    SOURCES,
}

/** État d'une entrée de la liste de travail (miroir Java). */
public enum class EtatEntreeTravailKotlin {
    /** Pas encore traitée par ce passage. */
    EN_ATTENTE,

    /** En cours de traitement (entrée active du passage). */
    ACTIF,

    /** Construite à froid puis persistée (décodage kotlin-metadata). */
    TERMINE,

    /** Reprise sans reconstruction (segment disque ou cache mémoire — ADR 0211). */
    REPRIS,

    /** Écartée honnêtement (entrée illisible — PARTIEL, jamais d'exception). */
    ECARTE,
}

/** Entrée de la liste de travail (nom lisible + état courant). */
public data class EntreeTravailKotlin(
    public val nom: String,
    public val etat: EtatEntreeTravailKotlin,
)

/** Temps cumulé d'un indexeur du passage (breakdown par outil). */
public data class BreakdownIndexeurKotlin(
    public val identifiant: String,
    public val libelle: String,
    public val millisecondesCumulees: Long,
    public val nombreEntrees: Int,
)

/** Durées des phases du passage (miroir Java). */
public data class DureesPhasesKotlin(
    public val bibliothequesMs: Long,
    public val sourcesMs: Long,
)

/** Bilan du dernier passage (miroir Java — parité totale de l'écran). */
public data class BilanDernierPassageKotlin(
    public val dureeMs: Long,
    public val artefactsTotal: Int,
    public val artefactsRepris: Int,
    public val artefactsReconstruits: Int,
    public val fichiersParcourus: Int,
    public val fichiersReanalyses: Int,
    public val indexeurPlusCouteux: String?,
    public val horodatageMs: Long,
)

/**
 * UN événement d'indexation — l'état complet du passage au moment de
 * l'émission (l'observateur ne reçoit JAMAIS de delta : il remplace
 * l'état courant, exactement comme un `StateFlow` conflate les
 * intermédiaires).
 *
 * @property phase phase du passage.
 * @property faits entrées traitées (segments ou fichiers selon la phase détaillée).
 * @property total entrées du passage (total connu — marche d'abord, ADR 0212).
 * @property entreeCourante nom de l'entrée active (`null` hors travail unitaire).
 * @property avertissements écarts honnêtes du passage (entrées écartées, annulation).
 * @property erreurs erreurs bloquantes (vide à ce jour — ERREUR réservé).
 * @property phaseDetaillee affinage de CONSTRUCTION.
 * @property listeTravail entrées du passage (segments ; les FICHIERS du scan
 *           n'y entrent pas — ils seraient du bruit à l'écran, miroir Java
 *           qui n'y liste que les jars).
 * @property breakdownIndexeurs temps par indexeur (segments kidx, scan WARM).
 * @property dureesPhases durées des phases du passage (au final).
 * @property bilanDernierPassage bilan (au final).
 * @property settled le passage est-il ALLÉ AU BOUT (même partiel) — `false`
 *           pendant le travail, `true` au final, `false` après annulation.
 * @property reprises entrées reprises sans reconstruction.
 */
public data class EvenementIndexationKotlin(
    public val phase: PhaseIndexationKotlin,
    public val faits: Int,
    public val total: Int,
    public val entreeCourante: String?,
    public val avertissements: List<String>,
    public val erreurs: List<String>,
    public val phaseDetaillee: PhaseDetailleeIndexationKotlin,
    public val listeTravail: List<EntreeTravailKotlin>,
    public val breakdownIndexeurs: List<BreakdownIndexeurKotlin>,
    public val dureesPhases: DureesPhasesKotlin?,
    public val bilanDernierPassage: BilanDernierPassageKotlin?,
    public val settled: Boolean,
    public val reprises: Int,
) {
    public companion object {
        /** Avant tout nourrissage (valeur initiale cohérente). */
        public val INITIAL: EvenementIndexationKotlin =
            EvenementIndexationKotlin(
                phase = PhaseIndexationKotlin.INITIAL,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndexationKotlin.AUCUNE,
                listeTravail = emptyList(),
                breakdownIndexeurs = emptyList(),
                dureesPhases = null,
                bilanDernierPassage = null,
                settled = false,
                reprises = 0,
            )
    }
}

/**
 * Observateur des événements d'indexation du moteur — appelé depuis le
 * fil de fond du moteur, doit être RAPIDE et ne JAMAIS lever (un
 * observateur fautif est journalisé et ignoré, le passage continue).
 */
public fun interface ObservateurIndexationKotlin {
    /** Reçoit l'état complet du passage (remplace l'état courant). */
    public fun surEvenement(evenement: EvenementIndexationKotlin)
}

/** Poignée de désinscription vide (défaut des packs anciens). */
public object PoigneeObservationVideKotlin : Closeable {
    override fun close() = Unit
}
