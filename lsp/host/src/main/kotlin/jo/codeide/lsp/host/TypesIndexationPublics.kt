package jo.codeide.lsp.host

/**
 * Phase détaillée de l'indexation — traduction publique de
 * `jo.codeide.lsp.index.PhaseDetailleeIndex` (Phase B prompt 2, ADR
 * 0117 §4.4). L'hôte découple les types internes de l'index (en
 * `implementation`, non transitive) des types exposés à l'UI.
 */
public enum class PhaseDetailleeIndexation {
    /** Avant tout démarrage — l'index existe mais aucune entrée traitée. */
    INITIAL,

    /** Indexation des bibliothèques et SDK (jars du classpath). */
    BIBLIOTHEQUES,

    /** Ré-analyse des fichiers sources du projet (`.java` etc.). */
    SOURCES,

    /** Phase inconnue ou non applicable (phase PRET/PARTIEL/ERREUR). */
    AUCUNE,
}

/**
 * État d'une entrée dans la liste de travail — traduction publique de
 * `jo.codeide.lsp.index.EtatEntreeTravail`.
 */
public enum class EtatEntreeTravailIndexation {
    /** En attente de traitement (pas encore commencée). */
    EN_ATTENTE,

    /** En cours de traitement (entrée active). */
    ACTIF,

    /** Terminée avec succès (entrée indexée ou reprise valide). */
    TERMINE,

    /** Reprise sans reconstruction (segment déjà valide — ADR 0111). */
    REPRIS,

    /** Écartée honnêtement (jar illisible, AAR sans jar interne, etc.). */
    ECARTE,
}

/**
 * Entrée de la liste de travail — traduction publique de
 * `jo.codeide.lsp.index.EntreeTravail`.
 *
 * @property nom nom lisible de l'entrée (ex. `androidx.core 1.19.0`).
 * @property etat état courant de l'entrée.
 */
public data class EntreeTravailIndexation(
    public val nom: String,
    public val etat: EtatEntreeTravailIndexation,
)

/**
 * Répartition du temps par indexeur — traduction publique de
 * `jo.codeide.lsp.index.BreakdownIndexeur`.
 *
 * @property identifiant identifiant stable de l'indexeur.
 * @property libelle libellé lisible localisable.
 * @property millisecondesCumulees temps cumulé en millisecondes.
 * @property nombreEntrees nombre d'entrées traitées.
 */
public data class BreakdownIndexeurIndexation(
    public val identifiant: String,
    public val libelle: String,
    public val millisecondesCumulees: Long,
    public val nombreEntrees: Int,
)

/**
 * Durées par phase — traduction publique de
 * `jo.codeide.lsp.index.DureesPhases`.
 *
 * @property bibliothequesMs durée de la phase bibliothèques (ms).
 * @property sourcesMs durée de la phase sources (ms).
 */
public data class DureesPhasesIndexation(
    public val bibliothequesMs: Long,
    public val sourcesMs: Long,
)

/**
 * Bilan du dernier passage — traduction publique de
 * `jo.codeide.lsp.index.BilanDernierPassage`.
 *
 * @property dureeMs durée totale du dernier passage (ms).
 * @property artefactsTotal nombre total d'artefacts.
 * @property artefactsRepris nombre d'artefacts repris sans reconstruction.
 * @property artefactsReconstruits nombre d'artefacts reconstruits.
 * @property fichiersParcourus nombre de fichiers parcourus (phase sources).
 * @property fichiersReanalyses nombre de fichiers ré-analysés.
 * @property indexeurPlusCouteux identifiant de l'indexeur le plus coûteux.
 * @property horodatageMs horodatage du passage (epoch UTC, ms).
 */
public data class BilanDernierPassageIndexation(
    public val dureeMs: Long,
    public val artefactsTotal: Int,
    public val artefactsRepris: Int,
    public val artefactsReconstruits: Int,
    public val fichiersParcourus: Int,
    public val fichiersReanalyses: Int,
    public val indexeurPlusCouteux: String?,
    public val horodatageMs: Long,
)
