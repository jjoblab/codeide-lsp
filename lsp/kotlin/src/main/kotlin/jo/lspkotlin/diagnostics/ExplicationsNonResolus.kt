package jo.lspkotlin.diagnostics

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Journal « explain » des `kt.unresolved` ÉMIS (mission fiabilisation,
 * étape A — instrumentation) : pour chaque diagnostic émis, enregistrer
 * en niveau FINE — **jamais actif par défaut en production** —
 *
 *  - la **forme syntaxique** de la référence (nom nu, sélecteur
 *    qualifié, callee d'appel qualifié, premier segment d'une chaîne
 *    qualifiée) ;
 *  - l'issue de la **sélection par types complets** (ADR 0223) : la
 *    « voie de preuve » qui a conclu à l'absence — `ABSENCE_PROUVEE`
 *    (classe du récepteur lisible + index portant + aucune extension
 *    homonyme), `RECEPTEUR_TYPE` ou `INCONNU` (voie normale) ;
 *  - le **récepteur typé** (FQCN complet du récepteur de la
 *    sélection, quand il se prouve) ;
 *  - l'**état de l'univers des preuves** à cet instant : scan du
 *    projet complet (`Contexte.scanProjetComplet`, ADR 0212/0216),
 *    index des appelables portant des entrées (ADR 0211/0223), régime
 *    script (ADR 0221).
 *
 * API : `java.util.logging` — le SEUL autorisé par detekt dans les
 * modules LSP (miroir de l'hôte). L'enregistrement est PARESSEUX : la
 * construction de l'explication ne court QUE si le journal écoute
 * (coût nul en production, patron des fournisseurs paresseux JUL).
 */
internal object ExplicationsNonResolus {
    /** Logger du chemin des diagnostics — FINE = hors production par défaut. */
    private val JOURNAL: Logger = Logger.getLogger(ExplicationsNonResolus::class.java.name)

    /**
     * Une explication d'émission `kt.unresolved`.
     *
     * @property nom nom de la référence émise.
     * @property ligne ligne 1-basée de la référence dans le document.
     * @property colonne colonne 1-basée de la référence dans la ligne.
     * @property forme forme syntaxique (`nom-nu`,
     *           `selecteur-qualifie-absence-prouvee`, `callee-d-appel-qualifie`…).
     * @property selection issue ADR 0223 (`ABSENCE_PROUVEE`,
     *           `RECEPTEUR_TYPE`, `INCONNU`).
     * @property recepteurType FQCN du récepteur typé de la sélection,
     *           `null` si la référence n'est pas une sélection ou que
     *           le récepteur reste sans type.
     * @property scanProjetComplet l'univers des sources projet est-il
     *           COMPLET (preuve d'absence projet fondée, vague 3) ?
     * @property indexPortant l'index des appelables connaît-il des
     *           entrées (sinon l'absence d'extension n'est pas prouvable) ?
     * @property script la référence vit-elle dans un script `.kts`
     *           (récepteur implicite du contexte hôte, ADR 0221) ?
     */
    @Suppress("LongParameterList") // le CONTEXTE COMPLET de l'émission est FILÉ (patron Contexte)
    internal data class Explication(
        val nom: String,
        val ligne: Int,
        val colonne: Int,
        val forme: String,
        val selection: String,
        val recepteurType: String?,
        val scanProjetComplet: Boolean,
        val indexPortant: Boolean,
        val script: Boolean,
    )

    /**
     * Enregistre une explication — PARESSEUX : [construire] ne court
     * que si le journal écoute (production : aucun coût).
     */
    internal fun enregistrer(construire: () -> Explication) {
        if (!JOURNAL.isLoggable(Level.FINE)) return
        JOURNAL.fine(construire().rendu())
    }

    /**
     * Ligne et colonne 1-basées d'un décalage dans un texte — pour le
     * rendu lisible du journal (miroir des positions LSP).
     */
    internal fun position(
        texte: CharSequence,
        decalage: Int,
    ): Pair<Int, Int> {
        var ligne = 1
        var colonne = 1
        for (i in 0 until decalage.coerceIn(0, texte.length)) {
            if (texte[i] == '\n') {
                ligne++
                colonne = 1
            } else {
                colonne++
            }
        }
        return ligne to colonne
    }

    /** Rendu UNE LIGNE, greppable : chaque champ sous la forme `clé=valeur`. */
    private fun Explication.rendu(): String =
        "explain: kt.unresolved « $nom » $ligne:$colonne — " +
            "forme=$forme ; selection=$selection ; " +
            "recepteur=${recepteurType ?: "aucun"} ; " +
            "scan=${if (scanProjetComplet) "complet" else "INCOMPLET"} ; " +
            "index=${if (indexPortant) "portant" else "VIDE"} ; " +
            "script=${if (script) "oui" else "non"}"
}
