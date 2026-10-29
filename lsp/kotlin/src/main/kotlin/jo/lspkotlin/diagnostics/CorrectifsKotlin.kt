package jo.lspkotlin.diagnostics

import jo.lspkotlin.api.CorrectifTexteKotlin
import jo.lspkotlin.api.GenreCorrectifKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.kt.chaineAncetresA
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Quick-fixes d'EXPRESSION (chantier 3, ADR 0232 — wrap / cast /
 * surround) : les correctifs des diagnostics PROUVÉS `kt.unsafeNullable`
 * et `kt.typeMismatch`, rendus comme des éditions CONCRÈTES.
 *
 * ## L'alignement est par construction
 * Les correctifs dérivent des MÊMES marches que les diagnostics —
 * [DiagnosticsSemantiques.accesNonSursProuves] et
 * [DiagnosticsSemantiques.litterauxIncompatibles], régions propres
 * comprises ([DiagnosticsSemantiques.regionsPropres]) : un correctif ne
 * s'offre QUE là où le diagnostic peut s'exprimer, JAMAIS sur du code
 * légal (D3). Le moteur re-prouve à la REQUÊTE sur l'arbre courant —
 * un diagnostic périmé (texte édité depuis la publication) rend VIDE,
 * aucune mémoire du passé.
 *
 * ## Les familles (miroir Android Studio)
 *  - **`?.`** : remplacer le `.` de l'accès non sûr par un appel sûr
 *    (`s.length` → `s?.length`) — le plus sûr, l'appel devient
 *    conditionnel ;
 *  - **`!!`** : encadrer le récepteur de l'assertion non nulle
 *    (`s.length` → `s!!.length`) — le « cast » de nullité T? → T ;
 *  - **`if (nom != null)`** : entourer l'INSTRUCTION englobante
 *    (`s.length` → `if (s != null) {\n  s.length\n}`) — le correctif
 *    structurel, SEULEMENT dans un bloc (une propriété de premier
 *    niveau ne s'entoure pas : `if` n'est pas légal au sommet d'un
 *    fichier Kotlin) ;
 *  - **changement de type** : remplacer le type ÉCRIT par le
 *    représentant de la famille du littéral (`val x: Int = "5"` →
 *    `val x: String = "5"`) — toujours légal, le littéral affecte son
 *    propre type ;
 *  - **conversion** : encadrer l'initialiseur d'une conversion
 *    STANDARD du langage (`"5"` → `"5".toInt()`) — matrice fermée des
 *    conversions stdlib par famille (une conversion qui n'existe pas
 *    dans le langage n'est JAMAIS offerte).
 *
 * ## Pourquoi PAS de cast `as`
 * Le diagnostic `kt.typeMismatch` ne vit que sur des littéraux de
 * familles DISJOINTES du type écrit (`"s"` affecté à `Int`) : le cast
 * `as` entre types finaux sans relation est REJETÉ par le compilateur
 * Kotlin (« incompatible types ») — l'offrir produirait du code cassé,
 * la pire trahison d'un correctif (D3). Le « cast » du chantier vit
 * côté nullité : `!!`.
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque garde
 * (jeton introuvable, hors bloc, conversion inadmise) est une ISSUE du
 * conservatisme D3 ; CyclomaticComplexMethod — le rendu de l'entourage
 * est le catalogue des formes (multi-ligne / en ligne) d'une même
 * édition.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal object CorrectifsKotlin {
    /** Pas d'indentation du corps entouré (miroir Android Studio). */
    private const val PAS_INDENTATION: String = "    "

    /** Nom de conversion standard par FQCN cible (stdlib Kotlin). */
    private val CONVERSIONS_PAR_FQCN =
        mapOf(
            "kotlin.Int" to "toInt",
            "kotlin.Long" to "toLong",
            "kotlin.Short" to "toShort",
            "kotlin.Byte" to "toByte",
            "kotlin.Float" to "toFloat",
            "kotlin.Double" to "toDouble",
            "kotlin.Char" to "toChar",
            "kotlin.String" to "toString",
            "kotlin.Boolean" to "toBoolean",
        )

    /** FQCN numériques (cibles des conversions de littéraux numériques). */
    private val NUMERIQUES =
        setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Short",
            "kotlin.Byte", "kotlin.Float", "kotlin.Double",
        )

    /**
     * Les correctifs aux DÉCALAGES des diagnostics prouvés : chaque
     * preuve dont la plage (RÉCEPTEUR pour l'accès non sûr,
     * INITIALISEUR pour le littéral incompatible) CONTIENT
     * [decalage] rend ses éditions — l'hôte appelle avec le début de
     * la plage du diagnostic, la preuve structurale fait le reste.
     */
    fun correctifs(
        arbre: ArbreSyntaxiqueKotlin,
        texte: CharSequence,
        decalage: Int,
        contexte: DiagnosticsSemantiques.Contexte,
    ): List<CorrectifTexteKotlin> {
        val regions = DiagnosticsSemantiques.regionsPropres(arbre)
        val resultats = mutableListOf<CorrectifTexteKotlin>()
        for (preuve in DiagnosticsSemantiques.accesNonSursProuves(arbre.racine, contexte)) {
            if (!regions.contient(preuve.recepteur, decalage)) continue
            resultats += correctifsAccesNonSur(arbre.racine, texte, preuve)
        }
        for (preuve in DiagnosticsSemantiques.litterauxIncompatibles(arbre.racine, contexte)) {
            if (!regions.contient(preuve.initialisateur, decalage)) continue
            resultats += correctifsLitteral(preuve)
        }
        return resultats
    }

    /** La preuve vit-elle dans une région propre ET contient-elle l'offset ? */
    private fun List<DiagnosticsSemantiques.PlagePropre>?.contient(
        plage: jo.lspkotlin.parse.ElementSyntaxique,
        decalage: Int,
    ): Boolean {
        if (decalage < plage.debut || decalage >= plage.fin) return false
        // Document propre (null) : aucune région à vérifier — la preuve
        // structurelle suffit (miroir `calculer`, garde P1-A).
        if (this == null) return true
        return any { it.contient(plage.debut, plage.fin) }
    }

    // ── kt.unsafeNullable : ?., !!, entourage ─────────────────────────

    /** Les trois correctifs d'un accès non sûr (du plus sûr au plus structurel). */
    private fun correctifsAccesNonSur(
        racine: jo.lspkotlin.parse.NoeudSyntaxique,
        texte: CharSequence,
        preuve: DiagnosticsSemantiques.AccesNonSur,
    ): List<CorrectifTexteKotlin> {
        val resultats = mutableListOf<CorrectifTexteKotlin>()
        // `?.` : le point de CETTE expression (le premier jeton DOT
        // après le récepteur — une expression imbriquée `s.length.trim()`
        // corrige le point de SON segment, jamais celui du parent).
        val point =
            preuve.expression.jetonsDescendants()
                .firstOrNull { it.type == KtTokens.DOT && it.debut >= preuve.recepteur.fin }
        if (point != null) {
            resultats +=
                CorrectifTexteKotlin(
                    genre = GenreCorrectifKotlin.APPEL_SUR,
                    debut = point.debut,
                    fin = point.debut,
                    texte = "?",
                )
        }
        // `!!` : juste après le récepteur.
        resultats +=
            CorrectifTexteKotlin(
                genre = GenreCorrectifKotlin.ASSERTION_NON_NULLE,
                debut = preuve.recepteur.fin,
                fin = preuve.recepteur.fin,
                texte = "!!",
            )
        // `if (nom != null)` : l'instruction englobante, seulement
        // dans un bloc (une propriété de premier niveau ne s'entoure
        // pas — `if` illégal au sommet d'un fichier, D3).
        instructionEnglobante(racine, preuve.expression)?.let { instruction ->
            resultats +=
                CorrectifTexteKotlin(
                    genre = GenreCorrectifKotlin.TEST_NULLITE,
                    debut = instruction.debut,
                    fin = instruction.fin,
                    texte = renduEntourage(texte, instruction, preuve.nom),
                    argument = preuve.nom,
                )
        }
        return resultats
    }

    /**
     * L'instruction à entourer : l'enfant DIRECT du bloc le plus
     * profond qui englobe l'expression (la chaîne d'ancêtres racine →
     * expression ; le premier BLOCK en remontant depuis le fond, et
     * le maillon JUSTE EN DESSOUS). `null` sans bloc ancêtre
     * (premier niveau) — l'entourage n'a pas de cible légale.
     */
    private fun instructionEnglobante(
        racine: jo.lspkotlin.parse.NoeudSyntaxique,
        expression: jo.lspkotlin.parse.NoeudSyntaxique,
    ): jo.lspkotlin.parse.NoeudSyntaxique? {
        val chaine = chaineAncetresA(racine, expression.debut)
        for (indice in chaine.indices.reversed()) {
            if (chaine[indice].type == KtNodeTypes.BLOCK) {
                return chaine.getOrNull(indice + 1)
            }
        }
        return null
    }

    /**
     * Le rendu de l'instruction entourée — MULTI-LIGNE quand
     * l'instruction possède sa ligne des deux côtés (préfixe et suffixe
     * de ligne blancs, le corps gagne un pas d'indentation, miroir
     * Android Studio), EN LIGNE sinon (`if (s != null) { … }` — une
     * instruction qui partage sa ligne garde la ligne, jamais de
     * reformatage surprise).
     */
    private fun renduEntourage(
        texte: CharSequence,
        instruction: jo.lspkotlin.parse.NoeudSyntaxique,
        nom: String,
    ): String {
        val instructionTexte = texte.subSequence(instruction.debut, instruction.fin)
        val debutLigne = texte.lastIndexOf('\n', (instruction.debut - 1).coerceAtLeast(0)) + 1
        val finLigne =
            texte.indexOf('\n', instruction.fin).let { if (it == -1) texte.length else it }
        val possedeSaLigne =
            texte.subSequence(debutLigne, instruction.debut).isBlank() &&
                texte.subSequence(instruction.fin, finLigne).isBlank()
        if (!possedeSaLigne) {
            return "if ($nom != null) { $instructionTexte }"
        }
        val indentation = texte.subSequence(debutLigne, instruction.debut).toString()
        return "if ($nom != null) {\n$indentation$PAS_INDENTATION$instructionTexte\n$indentation}"
    }

    // ── kt.typeMismatch : changement de type, conversion ─────────────

    /** Les deux correctifs d'un littéral incompatible (du plus sûr au plus sémantique). */
    private fun correctifsLitteral(preuve: DiagnosticsSemantiques.LitteralIncompatible): List<CorrectifTexteKotlin> {
        val resultats = mutableListOf<CorrectifTexteKotlin>()
        // Changement de type : le représentant de la famille du littéral
        // (kotlin.Int → Int) — le littéral affecte TOUJOURS son propre
        // type, l'édition ne peut pas produire du code cassé.
        val representant = preuve.famille.first().removePrefix("kotlin.")
        resultats +=
            CorrectifTexteKotlin(
                genre = GenreCorrectifKotlin.CHANGEMENT_TYPE,
                debut = preuve.noeudType.debut,
                fin = preuve.noeudType.fin,
                texte = representant,
                argument = representant,
            )
        // Conversion standard : la matrice fermée par famille — une
        // conversion qui n'existe pas dans le langage (booléen →
        // numérique, caractère → entier déprécié…) n'est JAMAIS offerte.
        val conversion = CONVERSIONS_PAR_FQCN[preuve.fqcnEcrit]
        if (conversion != null && preuve.fqcnEcrit in conversionsAdmises(preuve.famille)) {
            resultats +=
                CorrectifTexteKotlin(
                    genre = GenreCorrectifKotlin.CONVERSION,
                    debut = preuve.initialisateur.fin,
                    fin = preuve.initialisateur.fin,
                    texte = ".$conversion()",
                    argument = ".$conversion()",
                )
        }
        return resultats
    }

    /**
     * Les cibles de conversion ADMISES pour une famille de littéral —
     * la matrice fermée du chantier (stdlib Kotlin, conversions
     * TOTALES ou de parse usuelles) :
     *  - numérique (entier/décimal) → numérique, Char (`toChar`),
     *    String (`toString`) ;
     *  - chaîne → numérique (`"5".toInt()` — parse, peut lever à
     *    l'exécution : c'est le choix sémantique de l'utilisateur, le
     *    code compile) et Boolean (`toBoolean`, total) ;
     *  - booléen → String ; caractère → String (`'a'.toString()`) ;
     *  - caractère → numérique : AUCUNE (`Char.toInt()` est DÉPRÉCIÉ
     *    depuis Kotlin 1.5 — offrir un correctif déprécié est une
     *    régression déguisée, D3).
     */
    private fun conversionsAdmises(famille: Set<String>): Set<String> =
        when (famille.firstOrNull()) {
            "kotlin.Int", "kotlin.Float", "kotlin.Double" ->
                NUMERIQUES + setOf("kotlin.Char", "kotlin.String")

            "kotlin.String" -> NUMERIQUES + setOf("kotlin.Boolean")
            "kotlin.Boolean" -> setOf("kotlin.String")
            "kotlin.Char" -> setOf("kotlin.String")
            else -> emptySet()
        }
}
