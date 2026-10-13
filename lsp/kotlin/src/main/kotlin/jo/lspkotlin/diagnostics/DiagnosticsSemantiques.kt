package jo.lspkotlin.diagnostics

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.ResolutionKotlin
import jo.lspkotlin.resolve.SymbolesProjet
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Calcul des diagnostics SÉMANTIQUES conservateurs (K4,
 * LSP_KOTLIN.md §4.3 « diagnostics initiaux ») — fonctions PURES sur
 * l'arbre + le contexte de résolution : [kt.unresolved]
 * [CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE], [kt.typeMismatch],
 * [kt.argumentCount], [kt.unsafeNullable], [kt.missingReturn],
 * [kt.unreachable], [kt.valReassign].
 *
 * GARDE GÉNÉRALE (D3) : un document portant le MOINDRE marqueur d'erreur
 * du parseur n'est PAS diagnostiqué sémantiquement — l'arbre tolérant
 * d'une édition en cours n'est pas une base de preuve. De même, sans
 * classpath (vue absente), `kt.unresolved` ne signale RIEN : impossible
 * de prouver qu'un nom est absent d'un index qu'on ne lit pas.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le catalogue
 * EST le contrat du cadrage (un diagnostic = une famille de preuves),
 * patron DiagnosticsSyntaxiques ; CyclomaticComplexMethod — chaque
 * diagnostic est une ISSUE distincte du même when.
 */
@Suppress(
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LongMethod",
    "LongParameterList",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
)
internal object DiagnosticsSemantiques {
    /**
     * Contexte de calcul — ce que la session sait du document et du
     * classpath au moment du diagnostic.
     *
     * @property texte texte intégral du document (messages).
     * @property contexteNom imports/paquet/classes du projet.
     * @property resolution résolveur classpath (peut être dégradé).
     * @property declarationsProjet déclarations du projet (document d'abord).
     * @property indexAppelables index des appelables Kotlin (premier
     *        niveau du classpath) — `null` : régime sans classpath.
     */
    internal data class Contexte(
        val texte: CharSequence,
        val contexteNom: ResolutionKotlin.ContexteNom,
        val resolution: ResolutionKotlin,
        val declarationsProjet: List<DeclarationProjet>,
        val indexAppelables: IndexAppelablesKotlin?,
    )

    /**
     * Calcule tous les diagnostics sémantiques d'un arbre, triés par
     * position (puis code, déterminisme du test).
     */
    internal fun calculer(
        arbre: ArbreSyntaxiqueKotlin,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        // Garde : arbre en édition — aucune preuve sémantique.
        if (arbre.nombreErreurs > 0) return emptyList()
        val diagnostics = mutableListOf<DiagnosticKotlin>()
        diagnostics += referencesNonResolues(arbre.racine, contexte)
        diagnostics += typesIncompatibles(arbre.racine, contexte)
        diagnostics += nombresArguments(arbre.racine, contexte)
        diagnostics += accesNonSurs(arbre.racine, contexte)
        diagnostics += retoursManquants(arbre.racine, contexte)
        diagnostics += codesInatteignables(arbre.racine)
        diagnostics += reaffectationsVal(arbre.racine, contexte)
        return diagnostics.sortedWith(compareBy({ it.debut }, { it.code }))
    }

    // ── kt.unresolved ──────────────────────────────────────────────────

    /**
     * Références simples non résolues — la chaîne COMPLÈTE de K3/K4
     * (portées, projet, classes, appelables de l'index) doit échouer.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue de la position sélecteur, du paramètre implicite et
     * du nom résolu sont le patron même du parcours.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun referencesNonResolues(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        // Sans classpath lisible, aucune preuve d'absence — sous-rapport.
        val index = contexte.indexAppelables ?: return emptyList()
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { reference ->
            if (reference.type != KtNodeTypes.REFERENCE_EXPRESSION) return@parcourir
            val nom = nomReference(reference) ?: return@parcourir
            if (nom == "it") return@parcourir
            // Segments d'une DIRECTIVE import/package (K5) : ce sont des
            // noms de PAQUETS (« com » de `import com.ex.app.R`) — la
            // résolution de NOMS ne les concerne pas (quick-fixes et
            // complétion d'import les traitent), jamais `kt.unresolved`.
            if (estDansDirective(racine, reference)) return@parcourir
            // Sélecteur qualifié : le récepteur peut être inconnu.
            if (estSelecteurQualifie(racine, reference)) return@parcourir
            if (estResolu(racine, reference, nom, contexte, index)) return@parcourir
            val jeton = reference.jetonsDescendants().firstOrNull { it.type == KtTokens.IDENTIFIER }
            resultats +=
                DiagnosticKotlin(
                    code = CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE,
                    severite = SeveriteKotlin.ERREUR,
                    message = "Référence non résolue : « $nom »",
                    debut = jeton?.debut ?: reference.debut,
                    fin = jeton?.fin ?: reference.fin,
                )
        }
        return resultats
    }

    /** La référence vit-elle sous une directive import/package ? */
    private fun estDansDirective(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        return chaine.any {
            it.type == KtNodeTypes.IMPORT_DIRECTIVE || it.type == KtNodeTypes.PACKAGE_DIRECTIVE
        }
    }

    /** La référence est-elle le SÉLECTEUR d'une expression qualifiée ? */
    private fun estSelecteurQualifie(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        val indice = chaine.indexOf(reference)
        if (indice <= 0) return false
        for (profondeur in (indice - 1) downTo 0) {
            val parent = chaine[profondeur]
            if (parent.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                parent.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION ||
                parent.type == KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION
            ) {
                // Le PREMIER nœud enfant est le récepteur : tout autre
                // enfant (ou descendant d'un autre enfant) est sélecteur.
                return parent.sousNoeuds.firstOrNull()?.let { it != reference } ?: false
            }
            if (parent.type == KtNodeTypes.CALL_EXPRESSION) break
        }
        return false
    }

    /** [parent] couvre-t-il [enfant] (plages) ? */
    private fun couvre(
        parent: NoeudSyntaxique,
        enfant: NoeudSyntaxique,
    ): Boolean = parent.debut <= enfant.debut && enfant.fin <= parent.fin

    /** La référence résout-elle par QUELCONQUE voie du modèle K3/K4 ? */
    private fun estResolu(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        nom: String,
        contexte: Contexte,
        index: IndexAppelablesKotlin,
    ): Boolean =
        jo.lspkotlin.resolve.ResolutionReferences.estResolu(
            racine,
            reference.debut,
            nom,
            contexte.contexteNom,
            contexte.resolution,
            contexte.declarationsProjet,
            index,
        )

    // ── kt.typeMismatch ────────────────────────────────────────────────

    /** Types d'initialiseurs LITTÉRAUX incompatibles avec le type écrit. */
    private fun typesIncompatibles(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { declaration ->
            if (declaration.type != KtNodeTypes.PROPERTY) return@parcourir
            val propriete = KtProperty(declaration)
            val typeEcrit = propriete.type() ?: return@parcourir
            // Type NULLABLE : l'affectation d'un littéral nullable-legal
            // (« s: String? = "a" ») n'est jamais une erreur — la preuve
            // d'incompatibilité est impossible, on s'abstient.
            if (typeEcrit.endsWith("?")) return@parcourir
            val initialiseur = propriete.initialisateur() ?: return@parcourir
            val attendues = famillesDuLitteral(initialiseur.noeud) ?: return@parcourir
            val fqcn =
                contexte.resolution.fqcnDuNomEcrit(
                    typeEcrit.substringBefore('<').trim(),
                    contexte.contexteNom,
                ) ?: return@parcourir
            if (fqcn in attendues) return@parcourir
            resultats +=
                DiagnosticKotlin(
                    code = CodesDiagnosticsKotlin.TYPE_INCOMPATIBLE,
                    severite = SeveriteKotlin.ERREUR,
                    message = "Type incompatible : littéral ${attendues.famille()} " +
                        "affecté à « $typeEcrit »",
                    debut = initialiseur.debut,
                    fin = initialiseur.fin,
                )
        }
        return resultats
    }

    /** FQCN autorisés pour le littéral [noeud] (famille), null si non littéral. */
    private fun famillesDuLitteral(noeud: NoeudSyntaxique): Set<String>? =
        when (noeud.type) {
            KtNodeTypes.STRING_TEMPLATE -> setOf("kotlin.String")
            KtNodeTypes.BOOLEAN_CONSTANT -> setOf("kotlin.Boolean")
            KtNodeTypes.CHARACTER_CONSTANT -> setOf("kotlin.Char")
            KtNodeTypes.INTEGER_CONSTANT ->
                setOf(
                    "kotlin.Int", "kotlin.Long", "kotlin.Short",
                    "kotlin.Byte", "kotlin.Float", "kotlin.Double",
                )
            KtNodeTypes.FLOAT_CONSTANT ->
                if (noeud.jetonsDescendants().any { it.texte.endsWith("f", ignoreCase = true) }) {
                    setOf("kotlin.Float", "kotlin.Double")
                } else {
                    setOf("kotlin.Double", "kotlin.Float")
                }
            else -> null
        }

    /** Nom de famille lisible (message). */
    private fun Set<String>.famille(): String =
        when (firstOrNull()) {
            "kotlin.String" -> "de chaîne"
            "kotlin.Boolean" -> "booléen"
            "kotlin.Char" -> "de caractère"
            else -> "numérique"
        }

    // ── kt.argumentCount ───────────────────────────────────────────────

    /** Appels de candidates du PROJET dont aucune arité ne convient. */
    private fun nombresArguments(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { appel ->
            if (appel.type != KtNodeTypes.CALL_EXPRESSION) return@parcourir
            // Appel SIMPLE seulement (callee référence directe).
            val callee = appel.sousNoeuds.firstOrNull() ?: return@parcourir
            if (callee.type != KtNodeTypes.REFERENCE_EXPRESSION) return@parcourir
            val nom = nomReference(callee) ?: return@parcourir
            val nombreArguments = ariteAppel(appel)
            val candidates = candidatesAppel(racine, appel, nom, contexte)
            if (candidates.isEmpty()) return@parcourir
            val legale =
                candidates.any { candidate ->
                    if (candidate.aVararg) return@any true
                    val total = candidate.parametres.size
                    val minimum = total - candidate.nombreParametresDefaut
                    minimum <= nombreArguments && nombreArguments <= total
                }
            if (legale) return@parcourir
            val attendus = candidates.map { it.parametres.size }.distinct().sorted().joinToString("/")
            resultats +=
                DiagnosticKotlin(
                    code = CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS,
                    severite = SeveriteKotlin.ERREUR,
                    message = "Nombre d'arguments incompatible : $nombreArguments " +
                        "fourni(s), $attendus attendu(s) pour « $nom »",
                    debut = callee.debut,
                    fin = callee.fin,
                )
        }
        return resultats
    }

    /** Nombre d'arguments d'un appel (arguments ORDINAIRES + lambda final). */
    private fun ariteAppel(appel: NoeudSyntaxique): Int {
        val liste = appel.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST }
        val ordinaires = liste?.sousNoeuds?.count { it.type == KtNodeTypes.VALUE_ARGUMENT } ?: 0
        val lambdaFinal = appel.sousNoeuds.any { it.type == KtNodeTypes.LAMBDA_ARGUMENT }
        return ordinaires + if (lambdaFinal) 1 else 0
    }

    /** Candidates du PROJET pour un appel par nom (fonctions + constructeurs). */
    private fun candidatesAppel(
        racine: NoeudSyntaxique,
        appel: NoeudSyntaxique,
        nom: String,
        contexte: Contexte,
    ): List<DeclarationProjet> {
        val fonctions =
            contexte.declarationsProjet.filter { it.genre == GenreDeclaration.FONCTION && it.nom == nom }
        // K5 : les candidates JAVA n'ont pas d'arité extraite (types
        // seuls) — le contrôle d'arité les IGNORE (sous-rapport D3,
        // jamais de faux « 0 attendu » sur un constructeur Java).
        val classes =
            contexte.declarationsProjet.filter {
                it.genre == GenreDeclaration.CLASSE && it.nom == nom && !it.estJava
            }
        // Fonctions MEMBRES des classes englobantes (this implicite) :
        // leur arité vit dans l'arbre, pas dans les déclarations projet
        // (conteneur qualifié) — on sonde les membres englobants.
        val membres =
            SymbolesProjet.membresEnglobants(racine, appel.debut)
                .filterIsInstance<KtFunction>()
                .map { it.nom() to it.parametres().size }
        val resultats = mutableListOf<DeclarationProjet>()
        resultats += fonctions
        resultats += classes
        if (membres.isNotEmpty() && fonctions.isEmpty() && classes.isEmpty()) {
            // Membre englobant homonyme : une candidate synthétique à
            // l'arité exacte (aucun défaut connu — sous-rapport D3).
            membres.forEach { (nomMembre, arite) ->
                if (nomMembre == nom) {
                    val parametres = ArrayList<Pair<String?, String?>>(arite)
                    repeat(arite) { parametres.add(Pair(null, null)) }
                    resultats += DeclarationProjet(
                        GenreDeclaration.FONCTION,
                        nom,
                        "",
                        0,
                        0,
                        null,
                        null,
                        null,
                        parametres,
                        null,
                    )
                }
            }
        }
        return resultats
    }

    // ── kt.unsafeNullable ──────────────────────────────────────────────

    /** Sélecteurs `.` sur récepteurs NULLABLES démontrables, sans garde. */
    private fun accesNonSurs(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { expression ->
            if (expression.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION) return@parcourir
            val recepteur = expression.sousNoeuds.firstOrNull() ?: return@parcourir
            if (recepteur.type != KtNodeTypes.REFERENCE_EXPRESSION) return@parcourir
            val nom = nomReference(recepteur) ?: return@parcourir
            val typeEcrit = typeEcritNomSimple(racine, recepteur, nom, contexte) ?: return@parcourir
            if (!typeEcrit.endsWith("?")) return@parcourir
            // Garde de nullité dominante : `nom != null` au-dessus ou
            // garde `nom == null` à sortie précoce — accès légal.
            if (estProtegeNonNull(racine, recepteur, nom)) return@parcourir
            resultats +=
                DiagnosticKotlin(
                    code = CodesDiagnosticsKotlin.ACCES_NON_SUR,
                    severite = SeveriteKotlin.ERREUR,
                    message = "Appel non sûr sur un type nullable (« $typeEcrit ») : " +
                        "utiliser « ?. » ou « !! »",
                    debut = recepteur.debut,
                    fin = recepteur.fin,
                )
        }
        return resultats
    }

    /** Type ÉCRIT d'un nom simple (locale, membre englobant, premier niveau). */
    private fun typeEcritNomSimple(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        nom: String,
        contexte: Contexte,
    ): String? {
        for (locale in SymbolesProjet.localesEnPortee(racine, reference.debut)) {
            if (locale.declaration.nom != nom) continue
            locale.declaration.typeRetour?.let { return it }
            locale.propriete?.type()?.let { return it }
        }
        for (membre in SymbolesProjet.proprietesEnglobantes(racine, reference.debut)) {
            if (membre.nomJeton()?.texte?.toString() != nom) continue
            membre.type()?.let { return it }
        }
        for (declaration in KtFile(racine).declarations()) {
            if (declaration !is KtProperty) continue
            if (declaration.nomJeton()?.texte?.toString() != nom) continue
            declaration.type()?.let { return it }
        }
        for (declaration in contexte.declarationsProjet) {
            if (declaration.nom != nom || declaration.genre != GenreDeclaration.PROPRIETE) continue
            declaration.typeRetour?.let { return it }
        }
        return null
    }

    /** Garde `nom != null` dominante (alors/&& droite) ou précoce (== null → sortie). */
    private fun estProtegeNonNull(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        nom: String,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        for (ancetre in chaine) {
            when (ancetre.type) {
                KtNodeTypes.IF -> {
                    val condition = ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.CONDITION } ?: continue
                    val alors = ancetre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.THEN }
                    if (alors != null && couvre(alors, reference) &&
                        estComparaisonNullite(condition, nom, nonNul = true)
                    ) {
                        return true
                    }
                }

                KtNodeTypes.BINARY_EXPRESSION -> {
                    val operands = ancetre.sousNoeuds.filter { it.type != KtNodeTypes.OPERATION_REFERENCE }
                    if (operands.size < 2) continue
                    val et = ancetre.jetonsDescendants().any { it.type == KtTokens.ANDAND }
                    if (et && couvre(operands.last(), reference) &&
                        estComparaisonNullite(operands.first(), nom, nonNul = true)
                    ) {
                        return true
                    }
                }

                KtNodeTypes.BLOCK -> {
                    // Garde précoce : `if (nom == null) return/throw` AVANT.
                    for (enfant in ancetre.sousNoeuds) {
                        if (enfant.type != KtNodeTypes.IF || enfant.fin > reference.debut) continue
                        val condition =
                            enfant.sousNoeuds.firstOrNull { it.type == KtNodeTypes.CONDITION } ?: continue
                        val alors = enfant.sousNoeuds.firstOrNull { it.type == KtNodeTypes.THEN } ?: continue
                        if (estComparaisonNullite(condition, nom, nonNul = false) && sortDefinitivement(alors)) {
                            return true
                        }
                    }
                }

                else -> {
                    // WHEN/BINARY autre : non traité (sous-rapport).
                }
            }
        }
        return false
    }

    /** `nom != null` (nonNul) ou `nom == null` (nul) comme condition ENTIÈRE. */
    private fun estComparaisonNullite(
        condition: NoeudSyntaxique,
        nom: String,
        nonNul: Boolean,
    ): Boolean {
        val expression = condition.sousNoeuds.firstOrNull() ?: condition
        if (expression.type != KtNodeTypes.BINARY_EXPRESSION) return false
        val operation =
            expression.jetonsDescendants().firstOrNull { it.type == KtTokens.EQEQ || it.type == KtTokens.EXCLEQ }
        val attendu = if (nonNul) KtTokens.EXCLEQ else KtTokens.EQEQ
        if (operation?.type != attendu) return false
        val operands = expression.sousNoeuds.filter { it.type != KtNodeTypes.OPERATION_REFERENCE }
        if (operands.size < 2) return false
        val nomGauche = nomReference(operands.first()) ?: return false
        return nomGauche == nom && operands.last().type == KtNodeTypes.NULL
    }

    /** La branche THEN se termine par return/throw (sans retour). */
    private fun sortDefinitivement(alors: NoeudSyntaxique): Boolean =
        alors.jetonsDescendants().any { jeton ->
            jeton.type == KtTokens.RETURN_KEYWORD || jeton.type == KtTokens.THROW_KEYWORD
        }

    // ── kt.missingReturn ───────────────────────────────────────────────

    /** Fonctions à bloc sans return/throw pour un type de retour écrit. */
    private fun retoursManquants(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { declaration ->
            if (declaration.type != KtNodeTypes.FUN) return@parcourir
            val fonction = KtFunction(declaration)
            val typeRetour = fonction.typeRetour() ?: return@parcourir
            val fqcn =
                contexte.resolution.fqcnDuNomEcrit(typeRetour, contexte.contexteNom) ?: return@parcourir
            if (fqcn == "kotlin.Unit") return@parcourir
            val corps = declaration.sousNoeuds.firstOrNull { it.type == KtNodeTypes.BLOCK } ?: return@parcourir
            if (contientTerminal(corps)) return@parcourir
            val jeton = fonction.nomJeton() ?: return@parcourir
            val nomFonction = jeton.texte.toString()
            resultats +=
                DiagnosticKotlin(
                    code = CodesDiagnosticsKotlin.RETOUR_MANQUANT,
                    severite = SeveriteKotlin.ERREUR,
                    message = "Retour manquant : « $nomFonction » déclare « $typeRetour » " +
                        "mais son corps ne retourne rien ni ne lève",
                    debut = jeton.debut,
                    fin = jeton.fin,
                )
        }
        return resultats
    }

    /** Le bloc contient un return/throw HORS lambdas et fonctions imbriquées. */
    private fun contientTerminal(bloc: NoeudSyntaxique): Boolean {
        if (bloc.type == KtNodeTypes.RETURN || bloc.type == KtNodeTypes.THROW) return true
        if (bloc.type == KtNodeTypes.FUNCTION_LITERAL || bloc.type == KtNodeTypes.FUN) return false
        return bloc.sousNoeuds.any { contientTerminal(it) }
    }

    // ── kt.unreachable ─────────────────────────────────────────────────

    /** Première déclaration qui suit un return/throw FRÈRE dans un bloc. */
    private fun codesInatteignables(racine: NoeudSyntaxique): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { bloc ->
            if (bloc.type != KtNodeTypes.BLOCK) return@parcourir
            val declarations = bloc.sousNoeuds
            var apresTerminal = false
            for (declaration in declarations) {
                val terminal = declaration.type == KtNodeTypes.RETURN || declaration.type == KtNodeTypes.THROW
                if (terminal) {
                    apresTerminal = true
                    continue
                }
                if (apresTerminal) {
                    // La PREMIÈRE déclaration morte seulement (une seule
                    // pile par bloc — la plage couvre le début du mort).
                    resultats +=
                        DiagnosticKotlin(
                            code = CodesDiagnosticsKotlin.CODE_INATTEIGNABLE,
                            severite = SeveriteKotlin.AVERTISSEMENT,
                            message = "Code inatteignable : cette déclaration suit un " +
                                "return/throw du même bloc",
                            debut = declaration.debut,
                            fin = declaration.fin,
                        )
                    break
                }
            }
        }
        return resultats
    }

    // ── kt.valReassign ─────────────────────────────────────────────────

    /** Affectations `=` à des noms simples déclarés `val` (ou paramètres). */
    private fun reaffectationsVal(
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<DiagnosticKotlin> {
        val resultats = mutableListOf<DiagnosticKotlin>()
        parcourir(racine) { expression ->
            if (expression.type != KtNodeTypes.BINARY_EXPRESSION) return@parcourir
            // Affectation seule : le jeton EQ (== est un EQEQ distinct).
            expression.jetonsDescendants().firstOrNull { it.type == KtTokens.EQ } ?: return@parcourir
            val cible = expression.sousNoeuds.firstOrNull() ?: return@parcourir
            if (cible.type != KtNodeTypes.REFERENCE_EXPRESSION) return@parcourir
            val nom = nomReference(cible) ?: return@parcourir
            when (mutabiliteNom(racine, expression, nom, contexte)) {
                Mutabilite.VAL ->
                    resultats +=
                        DiagnosticKotlin(
                            code = CodesDiagnosticsKotlin.REAFFECTATION_VAL,
                            severite = SeveriteKotlin.ERREUR,
                            message = "Réaffectation d'un « val » : « $nom » est immuable",
                            debut = cible.debut,
                            fin = cible.fin,
                        )

                Mutabilite.VAR, Mutabilite.INCONNU -> return@parcourir
            }
        }
        return resultats
    }

    /** Mutabilité du nom le plus PROCHE (locale > membre > premier niveau). */
    private fun mutabiliteNom(
        racine: NoeudSyntaxique,
        affectation: NoeudSyntaxique,
        nom: String,
        contexte: Contexte,
    ): Mutabilite {
        // Locale/paramètre la plus proche : les paramètres sont des VAL
        // par construction (propriete null), les locales portent leur
        // propre val/var.
        val locale =
            SymbolesProjet.localesEnPortee(racine, affectation.debut)
                .firstOrNull { it.declaration.nom == nom }
        if (locale != null) {
            return if (locale.declaration.genre == GenreDeclaration.PARAMETRE) {
                Mutabilite.VAL
            } else {
                mutabilitePropriete(locale.propriete)
            }
        }
        for (membre in SymbolesProjet.proprietesEnglobantes(racine, affectation.debut)) {
            if (membre.nomJeton()?.texte?.toString() != nom) continue
            return mutabilitePropriete(membre)
        }
        for (declaration in KtFile(racine).declarations()) {
            if (declaration !is KtProperty) continue
            if (declaration.nomJeton()?.texte?.toString() != nom) continue
            return mutabilitePropriete(declaration)
        }
        // Propriété du projet hors fichier (scan) : mutabilité lue dans
        // le TEXTE du type écrit ? Non portée — sous-rapport (INCONNU).
        if (contexte.declarationsProjet.any { it.nom == nom && it.genre == GenreDeclaration.PROPRIETE }) {
            return Mutabilite.INCONNU
        }
        return Mutabilite.INCONNU
    }

    /** Mutabilité d'un nœud PROPERTY (jeton `var` sinon `val`). */
    private fun mutabilitePropriete(propriete: KtProperty?): Mutabilite {
        if (propriete == null) return Mutabilite.INCONNU
        val varr = propriete.noeud.jetonsDirects.any { it.type == KtTokens.VAR_KEYWORD }
        return if (varr) Mutabilite.VAR else Mutabilite.VAL
    }

    /** Mutabilité d'un nom pour la réaffectation. */
    private enum class Mutabilite {
        VAL,
        VAR,
        INCONNU,
    }

    // ── Parcours générique ─────────────────────────────────────────────

    /** Parcours profondeur d'abord du sous-arbre (nœuds seulement). */
    private fun parcourir(
        racine: NoeudSyntaxique,
        visite: (NoeudSyntaxique) -> Unit,
    ) {
        visite(racine)
        racine.sousNoeuds.forEach { parcourir(it, visite) }
    }
}
