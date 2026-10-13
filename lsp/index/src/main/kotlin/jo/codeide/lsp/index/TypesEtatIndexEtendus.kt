package jo.codeide.lsp.index

/**
 * Phase détaillée de la construction d'un index — affinage de
 * [PhaseIndex.CONSTRUCTION] pour l'écran d'indexation (Phase B prompt 2,
 * ADR 0117 §4.4). Le libellé est transporté par une ÉNUMÉRATION (jamais
 * par une chaîne comparable — règle §5.11 du prompt 2).
 *
 * Les phases correspondent aux deux temps documentés dans la
 * bibliothèque de référence privée (lecture interne, jamais attribuée) :
 * la phase « bibliothèques et SDK » (indexation des jars du classpath)
 * puis la phase « sources du projet » (ré-analyse des fichiers `.java`
 * du projet). CodeIDE ajoute une phase « INITIAL » avant tout
 * démarrage (l'index existe mais aucune entrée n'a été traitée).
 */
public enum class PhaseDetailleeIndex {
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
 * État d'une entrée en cours d'indexation (Phase B prompt 2, ADR 0117
 * §4.4 — `listeTravail`). Une entrée correspond à un jar, un AAR ou un
 * dossier du classpath.
 *
 * @property nom nom lisible de l'entrée (ex. `androidx.core 1.19.0`,
 *           `material 1.14.0`, `okio 3.9.0`).
 * @property etat état courant de l'entrée dans la liste de travail.
 */
public data class EntreeTravail(
    public val nom: String,
    public val etat: EtatEntreeTravail,
)

/**
 * État d'une entrée dans la liste de travail (Phase B prompt 2).
 * Transporté par une ÉNUMÉRATION (jamais par une chaîne comparable).
 */
public enum class EtatEntreeTravail {
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
 * Répartition du temps par indexeur (Phase B prompt 2, ADR 0117 §4.4 —
 * `breakdownIndexeurs`). Permet de répondre à la question « quel index
 * coûte le plus de temps ? ».
 *
 * @property identifiant identifiant stable de l'indexeur (ex.
 *           `noms-classes-java`, `appelables-kotlin`, `ressources-android`).
 * @property libelle libellé lisible localisable (ex. « Noms de classes
 *           Java », « Appelables Kotlin », « Ressources Android »).
 * @property millisecondesCumulees temps cumulé passé par cet indexeur
 *           en millisecondes.
 * @property nombreEntrees nombre d'entrées traitées par cet indexeur.
 */
public data class BreakdownIndexeur(
    public val identifiant: String,
    public val libelle: String,
    public val millisecondesCumulees: Long,
    public val nombreEntrees: Int,
)

/**
 * Durées par phase de la construction (Phase B prompt 2, ADR 0117 §4.4 —
 * `dureesPhases`). `null` si les durées ne sont pas encore mesurées
 * (avant la fin de la construction).
 *
 * @property bibliothequesMs durée de la phase bibliothèques et SDK
 *           (indexation des jars du classpath) en millisecondes.
 * @property sourcesMs durée de la phase sources du projet
 *           (ré-analyse des fichiers `.java`) en millisecondes.
 */
public data class DureesPhases(
    public val bibliothequesMs: Long,
    public val sourcesMs: Long,
)

/**
 * Bilan du dernier passage d'indexation (Phase B prompt 2, ADR 0117 §4.4 —
 * `bilanDernierPassage`). `null` si aucun passage n'a encore eu lieu
 * (premier démarrage) ou si le bilan n'est pas persisté.
 *
 * @property dureeMs durée totale du dernier passage en millisecondes.
 * @property artefactsTotal nombre total d'artefacts (entrées du classpath).
 * @property artefactsRepris nombre d'artefacts repris sans reconstruction
 *           (segments déjà valides — reprise « à chaud » ADR 0111).
 * @property artefactsReconstruits nombre d'artefacts reconstruits
 *           (segments nouveaux ou périmés).
 * @property fichiersParcourus nombre de fichiers parcourus pendant la
 *           phase sources (`.java` du projet).
 * @property fichiersReanalyses nombre de fichiers ré-analysés (ceux dont
 *           le contenu a changé depuis le dernier passage).
 * @property indexeurPlusCouteux identifiant de l'indexeur qui a coûté le
 *           plus de temps (peut être `null` si aucun indexeur n'a tourné).
 * @property horodatageMs horodatage du passage en millisecondes (epoch UTC).
 */
public data class BilanDernierPassage(
    public val dureeMs: Long,
    public val artefactsTotal: Int,
    public val artefactsRepris: Int,
    public val artefactsReconstruits: Int,
    public val fichiersParcourus: Int,
    public val fichiersReanalyses: Int,
    public val indexeurPlusCouteux: String?,
    public val horodatageMs: Long,
)
