package jo.lspkotlin.api

/**
 * Types NEUTRES du formatage borné (`textDocument/formatting` et
 * `textDocument/rangeFormatting`, L2 — plan 1.3.0) : le moteur n'expose
 * AUCUN type LSP4J (condition D6 du découplage « pack langue »), l'hôte
 * traduit vers le protocole.
 */

/**
 * Édition de source du formateur borné — RÉ-INDENTATION seule : le
 * blanc de TÊTE d'une ligne est remplacé par [texte], le reste de la
 * ligne n'est JAMAIS touché (miroir exact de l'édition XML S3).
 *
 * @property debut décalage de début du blanc de tête (inclus).
 * @property fin décalage de fin du blanc de tête (exclusif).
 * @property texte le blanc de remplacement (`"    "`, `""`…).
 */
public data class EditionSourceKotlin(
    public val debut: Int,
    public val fin: Int,
    public val texte: String,
)
