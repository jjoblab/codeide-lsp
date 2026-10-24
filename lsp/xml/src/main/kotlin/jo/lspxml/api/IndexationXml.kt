package jo.lspxml.api

import java.io.Closeable

/**
 * Types NEUTRES du contrat d'indexation observable du moteur XML
 * (ADR 0213 — demande D3 de CodeIDE, écran d'indexation multi-moteurs
 * v0.178.0) : miroir VOLONTAIRE de `jo.lspkotlin.api.IndexationKotlin`
 * (mêmes champs, mêmes sémantiques — la façade hôte traduit vers le
 * MÊME `EtatIndexation` public que Java et Kotlin).
 *
 * DÉLIBÉRÉMENT sans kotlinx-coroutines : le pack langue XML est une
 * entité indépendante (décision X2), le SPI n'impose aucun runtime de
 * concurrence à un pack tiers.
 */

/** Phase d'un passage — miroir de `PhaseIndexation` (`lsp:host`). */
public enum class PhaseIndexationXml {
    /** Aucun nourrissage demandé (avant la première sync). */
    INITIAL,

    /** Passage en cours (catalogue/AAR puis racines res). */
    CONSTRUCTION,

    /** Passage abouti sans écart. */
    PRET,

    /** Passage abouti AVEC écarts (unités douteuses, passage annulé). */
    PARTIEL,

    /** Réservé — erreur fatale de passage (jamais émis à ce jour). */
    ERREUR,
}

/** Phase détaillée — affinage pendant CONSTRUCTION. */
public enum class PhaseDetailleeIndexationXml {
    /** Hors construction. */
    AUCUNE,

    /** Catalogue XE2 de la plateforme + AAR des dépendances. */
    BIBLIOTHEQUES,

    /** Racines `res/` du projet (convention AGP). */
    SOURCES,
}

/** État d'une unité de la liste de travail (miroir Java/Kotlin). */
public enum class EtatEntreeTravailXml {
    /** Pas encore traitée par ce passage. */
    EN_ATTENTE,

    /** En cours de traitement. */
    ACTIF,

    /** Scannée pour la première fois (décodage/parse effectif). */
    TERMINE,

    /** Reprise sans re-travail (mtime inchangé — cache incrémental). */
    REPRIS,

    /** Écartée honnêtement (AAR illisible — doute X4, PARTIEL). */
    ECARTE,
}

/** Entrée de la liste de travail (nom lisible + état courant). */
public data class EntreeTravailXml(
    public val nom: String,
    public val etat: EtatEntreeTravailXml,
)

/** Temps cumulé d'un indexeur du passage. */
public data class BreakdownIndexeurXml(
    public val identifiant: String,
    public val libelle: String,
    public val millisecondesCumulees: Long,
    public val nombreEntrees: Int,
)

/** Durées des phases du passage. */
public data class DureesPhasesXml(
    public val bibliothequesMs: Long,
    public val sourcesMs: Long,
)

/** Bilan du dernier passage (parité Java/Kotlin). */
public data class BilanDernierPassageXml(
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
 * l'émission (remplace l'état courant, jamais de delta).
 */
public data class EvenementIndexationXml(
    public val phase: PhaseIndexationXml,
    public val faits: Int,
    public val total: Int,
    public val entreeCourante: String?,
    public val avertissements: List<String>,
    public val erreurs: List<String>,
    public val phaseDetaillee: PhaseDetailleeIndexationXml,
    public val listeTravail: List<EntreeTravailXml>,
    public val breakdownIndexeurs: List<BreakdownIndexeurXml>,
    public val dureesPhases: DureesPhasesXml?,
    public val bilanDernierPassage: BilanDernierPassageXml?,
    public val settled: Boolean,
    public val reprises: Int,
) {
    public companion object {
        /** Avant tout nourrissage (valeur initiale cohérente). */
        public val INITIAL: EvenementIndexationXml =
            EvenementIndexationXml(
                phase = PhaseIndexationXml.INITIAL,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
                phaseDetaillee = PhaseDetailleeIndexationXml.AUCUNE,
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
 * fil de fond du moteur, rapide, ne JAMAIS lever (un fautif est
 * journalisé et ignoré).
 */
public fun interface ObservateurIndexationXml {
    /** Reçoit l'état complet du passage. */
    public fun surEvenement(evenement: EvenementIndexationXml)
}

/** Poignée de désinscription vide (défaut des packs anciens). */
public object PoigneeObservationVideXml : Closeable {
    override fun close() = Unit
}
