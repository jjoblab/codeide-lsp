package jo.lspkotlin.preview

import jo.lspkotlin.api.AppelFonctionKotlin
import jo.lspkotlin.api.ArgumentAppelKotlin
import jo.lspkotlin.api.ExpressionNonInterpreteeKotlin
import jo.lspkotlin.api.GenreArgumentAppelKotlin
import jo.lspkotlin.api.GenreInlineKotlin
import jo.lspkotlin.api.NoeudAppelKotlin
import jo.lspkotlin.api.ParametrePreviewKotlin
import jo.lspkotlin.api.PreviewKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtAnnotationEntry
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.annotations
import jo.lspkotlin.parse.kt.genreLitteral
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.recepteurQualifie
import jo.lspkotlin.parse.kt.selecteurQualifie
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.ResolutionKotlin
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Collecte des fonctions `@Preview @Composable` d'un document (preview
 * Compose C1) — la session pioche le document courant et délègue TOUTE
 * la marche ici : preuve FQCN des annotations, lecture des paramètres
 * `@Preview`, réduction du corps en ARBRE D'APPELS NEUTRE, inlining
 * des composables du projet (cycles + budgets).
 *
 * Garde-fous (PC4 côté moteur) : budget TOTAL de nœuds [BUDGET_NOEUDS]
 * et profondeur d'inline [PROFONDEUR_INLINE] — une marche qui touche un
 * plafond est TRONQUÉE et signalée ([PreviewKotlin.coupureBudget]),
 * jamais silencieuse (PC1 : jamais un faux rendu).
 *
 * Conservateur (D3) : annotation non résolue par les imports = pas de
 * preview ; composable du projet non prouvé = pas d'inline (le rendu
 * en tire une erreur typée) ; corps absent = preview SANS corps.
 */
internal object PreviewsDocumentKotlin {
    /** FQCN de l'annotation `@Preview` (ui-tooling-preview). */
    private const val FQCN_PREVIEW: String = "androidx.compose.ui.tooling.preview.Preview"

    /** FQCN de l'annotation `@Composable` (runtime). */
    private const val FQCN_COMPOSABLE: String = "androidx.compose.runtime.Composable"

    /** Budget TOTAL de nœuds rendus par document (garde-fou PC4). */
    internal const val BUDGET_NOEUDS: Int = 500

    /** Profondeur MAXIMALE d'inline composable-projet (A → B → C…). */
    internal const val PROFONDEUR_INLINE: Int = 6

    /** Troncature du texte d'une expression non interprétée. */
    private const val LONGUEUR_EXPRESSION: Int = 120

    /** Troncature du nom de repli d'un appel en édition. */
    private const val LONGUEUR_NOM_REPLI: Int = 32

    /**
     * Accès de la collecte au RESTE du projet : arbre + texte d'un
     * fichier QUELCONQUE (document ouvert d'abord, fichier du scan
     * ensuite) et contexte de nommage d'un arbre — implémenté par la
     * session, qui possède les documents et le scan partagé.
     */
    internal interface Port {
        /** Arbre + texte du fichier [uri], ou null (illisible/inconnu). */
        fun fichierDe(uri: String): Pair<ArbreSyntaxiqueKotlin, CharSequence>?

        /** Contexte de nommage (imports, paquet, classes) de l'arbre donné. */
        fun contexteNomDe(racine: NoeudSyntaxique): ResolutionKotlin.ContexteNom
    }

    /**
     * Le document à parcourir : son identité, son texte, son contexte
     * de nommage, son résolveur, les déclarations du PROJET (document
     * + scan) et le port vers les autres fichiers.
     */
    internal class Contexte(
        internal val uri: String,
        internal val texte: CharSequence,
        internal val contexteNom: ResolutionKotlin.ContexteNom,
        internal val resolution: ResolutionKotlin,
        internal val declarationsProjet: List<DeclarationProjet>,
        internal val port: Port,
    )

    /**
     * Collecte les previews du document, dans l'ordre du document
     * (fonctions top-level puis membres de classes, profondeur
     * d'abord). Le budget est PARTAGÉ par toutes les previews du
     * fichier ; une fonction SANS preview n'en consomme AUCUN (la
     * marche du corps ne démarre que pour une preview retenue). La
     * fonction RETENUE est SEMÉE dans la pile d'inline AVANT la marche
     * de son corps — une auto-récursion (`Apercu()` s'appelant) est un
     * CYCLE typé, jamais une boucle infinie (garde-fou PC4).
     */
    internal fun collecter(
        contexte: Contexte,
        racine: NoeudSyntaxique,
        paquet: String?,
    ): List<PreviewKotlin> {
        val etat = Etat(BUDGET_NOEUDS)
        val previews = mutableListOf<PreviewKotlin>()
        val fonctions = fonctionsDe(KtFile(racine))
        var indice = 0
        while (indice < fonctions.size && etat.budget > 0) {
            Marche(contexte, etat)
                .previewDe(fonctions[indice], paquet)
                ?.let { previews.add(it) }
            indice++
        }
        return previews
    }

    /** Fonctions du fichier : top-level, puis membres de chaque classe. */
    private fun fonctionsDe(fichier: KtFile): List<KtFunction> =
        fichier.declarations().flatMap { declaration ->
            when (declaration) {
                is KtFunction -> listOf(declaration)
                is KtClass -> declaration.membres().filterIsInstance<KtFunction>()
                else -> emptyList()
            }
        }

    /**
     * FQCN d'un nom d'annotation ÉCRIT : un nom DÉJÀ QUALIFIÉ
     * (`@androidx.compose.runtime.Composable`) EST le FQCN tel
     * qu'écrit — comparaison directe ; un nom COURT passe par les
     * imports du fichier (fqcnDuNomEcrit : imports exacts, jokers,
     * unicité projet). Conservateur, null si non résolu (D3).
     */
    private fun fqcnAnnotation(
        nomEcrit: String?,
        resolution: ResolutionKotlin,
        contexte: ResolutionKotlin.ContexteNom,
    ): String? =
        when {
            nomEcrit == null -> null
            '.' in nomEcrit -> nomEcrit
            else -> resolution.fqcnDuNomEcrit(nomEcrit, contexte)
        }

    /** Le BLOCK d'un lambda final (LAMBDA_ARGUMENT → … → BLOCK). */
    private fun blocDeLambda(lambdaArgument: NoeudSyntaxique): NoeudSyntaxique? {
        val portee =
            lambdaArgument.sousNoeuds.firstOrNull { it.type == KtNodeTypes.LAMBDA_EXPRESSION }
                ?: lambdaArgument.sousNoeuds.firstOrNull { it.type == KtNodeTypes.FUNCTION_LITERAL }
        val litteral =
            portee?.sousNoeuds?.firstOrNull { it.type == KtNodeTypes.FUNCTION_LITERAL } ?: portee
        val conteneur = litteral ?: lambdaArgument
        return conteneur.sousNoeuds.firstOrNull { it.type == KtNodeTypes.BLOCK }
    }

    /** La fonction du fichier dont le NOM commence à [debutNom]. */
    private fun fonctionANom(
        racine: NoeudSyntaxique,
        debutNom: Int,
    ): KtFunction? =
        fonctionsDe(KtFile(racine)).firstOrNull { fonction ->
            fonction.nomJeton()?.debut == debutNom
        }

    /** État MUTABLE partagé par les marches du document (budget + cycles). */
    private class Etat(
        var budget: Int,
        /** Chaîne d'inline en cours : « uri#nom » du composable visité. */
        val inlineEnCours: LinkedHashSet<String> = LinkedHashSet(),
    )

    /**
     * Marche d'UN fichier (le document, ou le fichier d'un composable
     * inliné) : tranche son texte, résout SES annotations, déduit du
     * budget PARTAGÉ [etat] et déclare ses cycles dans la pile commune.
     *
     * Exemption detekt ciblée (règle 16) : TooManyFunctions — la marche
     * épouse les PRODUCTIONS du parseur (preview, bloc, instruction,
     * appel, argument, valeur, inline ×3) : le compte épouse le
     * CONTRAT C1, pas une dérive.
     */
    @Suppress("TooManyFunctions")
    private class Marche(
        private val contexte: Contexte,
        private val etat: Etat,
    ) {
        /**
         * La preview d'une fonction, ou null (pas `@Preview @Composable`).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — nom absent,
         * preuve d'annotation absente, rendu final : TROIS issues
         * distinctes du même contrat.
         */
        @Suppress("ReturnCount")
        fun previewDe(
            fonction: KtFunction,
            paquet: String?,
        ): PreviewKotlin? {
            val nom = fonction.nom() ?: return null
            val annotationPreview = annotationPreviewDe(fonction) ?: return null
            val nomJeton = fonction.nomJeton()
            val blocCorps = fonction.corps()?.noeud
            // Ligne 1-indexée de l'entrée d'annotation `@Preview` (le
            // marqueur de gouttière vit SUR cette ligne — écho picker de
            // la référence ; 0 si la plage est inconnue, le consommateur
            // renonce honnêtement à placer un marqueur).
            val ligneAnnotation = ligneDe(annotationPreview.noeud.debut)
            // La fonction racine est SEMÉE dans la pile d'inline : son
            // propre rappel (direct ou via un composable inliné) est un
            // CYCLE typé — jamais une récursion infinie (PC4).
            etat.inlineEnCours.add("${contexte.uri}#$nom")
            val corps = blocCorps?.let { marcherBloc(it) } ?: emptyList()
            etat.inlineEnCours.remove("${contexte.uri}#$nom")
            return PreviewKotlin(
                nom = nom,
                paquet = paquet,
                debutNom = nomJeton?.debut ?: fonction.debut,
                finNom = nomJeton?.fin ?: fonction.debut,
                parametres =
                    annotationPreview.arguments().map { argument ->
                        ParametrePreviewKotlin(
                            nom = argument.nom,
                            valeurTexte = tranche(argument.debutValeur, argument.finValeur),
                        )
                    },
                corps = corps,
                coupureBudget = etat.budget <= 0,
                ligneAnnotation = ligneAnnotation,
            )
        }

        /**
         * L'annotation `@Preview` de la fonction, À CONDITION qu'elle
         * soit aussi `@Composable` (les DEUX preuves par FQCN, imports
         * du document) — sinon null (conservateur D3).
         */
        private fun annotationPreviewDe(fonction: KtFunction): KtAnnotationEntry? {
            val annotations = fonction.annotations()
            val composable =
                annotations.any { fqcnDe(it.nomEcrit()) == FQCN_COMPOSABLE }
            val preview =
                annotations.firstOrNull { fqcnDe(it.nomEcrit()) == FQCN_PREVIEW }
            return if (composable) preview else null
        }

        /**
         * FQCN d'un nom d'annotation ÉCRIT du fichier COURANT — noms
         * qualifiés comparés tels quels, noms courts par les imports
         * ([fqcnAnnotation]) ; conservateur, null si non résolu (D3).
         */
        private fun fqcnDe(nomEcrit: String?): String? =
            fqcnAnnotation(nomEcrit, contexte.resolution, contexte.contexteNom)

        /** Texte tranché dans la source (null si plage vide/inverse). */
        private fun tranche(
            debut: Int,
            fin: Int,
        ): String? {
            if (debut < 0 || fin > contexte.texte.length || debut >= fin) return null
            return contexte.texte.substring(debut, fin)
        }

        /** Ligne 1-indexée d'un décalage. */
        private fun ligneDe(decalage: Int): Int {
            var ligne = 1
            for (i in 0 until decalage.coerceAtMost(contexte.texte.length)) {
                if (contexte.texte[i] == '\n') ligne++
            }
            return ligne
        }

        /**
         * Marche d'un BLOC (corps, lambda final) : chaque instruction
         * directe devient appel interprétable ou expression non
         * interprétée honnête. Le budget épuisé coupe la marche.
         *
         * Exemption detekt ciblée (règle 16) :
         * LoopWithTooManyJumpStatements — la coupe de budget (break) et
         * l'instruction ignorée (continue) sont les DEUX bornes du
         * parcours (miroir [ScanProjet.parcourir]).
         */
        @Suppress("LoopWithTooManyJumpStatements")
        fun marcherBloc(bloc: NoeudSyntaxique): List<NoeudAppelKotlin> {
            val noeuds = mutableListOf<NoeudAppelKotlin>()
            for (enfant in bloc.sousNoeuds) {
                if (etat.budget <= 0) break
                val noeud = instructionDe(enfant) ?: continue
                noeuds.add(noeud)
            }
            return noeuds
        }

        /** L'instruction directe d'un bloc, ou null si à ignorer. */
        private fun instructionDe(enfant: NoeudSyntaxique): NoeudAppelKotlin? =
            when (enfant.type) {
                KtNodeTypes.CALL_EXPRESSION -> marcherAppel(enfant)
                KtNodeTypes.PROPERTY,
                KtNodeTypes.FUNCTION_LITERAL, KtNodeTypes.IF, KtNodeTypes.WHEN,
                KtNodeTypes.FOR, KtNodeTypes.WHILE, KtNodeTypes.DO_WHILE,
                KtNodeTypes.TRY,
                -> expressionNonInterpretee(enfant)

                else ->
                    if (estExpressionSimple(enfant)) {
                        expressionNonInterpretee(enfant)
                    } else {
                        null
                    }
            }

        /** Une expression non appel, rendue honnêtement (erreur typée). */
        private fun expressionNonInterpretee(noeud: NoeudSyntaxique): NoeudAppelKotlin {
            etat.budget--
            val brut = tranche(noeud.debut, noeud.fin) ?: ""
            return ExpressionNonInterpreteeKotlin(
                texte = brut.replace('\n', ' ').take(LONGUEUR_EXPRESSION),
                ligne = ligneDe(noeud.debut),
            )
        }

        /** L'expression est-elle du TEXTE interprétable à trancher ? */
        private fun estExpressionSimple(noeud: NoeudSyntaxique): Boolean =
            noeud.type in TYPES_EXPRESSION_NON_APPEL

        /**
         * Marche d'un APPEL : arguments explicites (nommés/positionnels,
         * littéraux distingués), contenu du LAMBDA FINAL, inlining du
         * composable du PROJET le cas échéant (le genre final reflète le
         * résultat de la tentative — jamais de faux inline).
         */
        private fun marcherAppel(appel: NoeudSyntaxique): NoeudAppelKotlin {
            etat.budget--
            val nom = nomAppele(appel)
            val callee = appel.sousNoeuds.firstOrNull()
            val recepteur =
                if (callee != null &&
                    (callee.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                        callee.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION)
                ) {
                    recepteurQualifie(callee)?.let { tranche(it.debut, it.fin) }
                } else {
                    null
                }
            val arguments = marcherArguments(appel)
            val blocFinal =
                appel.sousNoeuds
                    .firstOrNull { it.type == KtNodeTypes.LAMBDA_ARGUMENT }
                    ?.let { blocDeLambda(it) }
            val contenuLambda = blocFinal?.let { marcherBloc(it) } ?: emptyList()
            val (genreInline, contenu) = inlineProjet(nom, recepteur, contenuLambda)
            return AppelFonctionKotlin(
                nom = nom ?: tranche(appel.debut, appel.fin)?.take(LONGUEUR_NOM_REPLI) ?: "",
                recepteur = recepteur,
                ligne = ligneDe(appel.debut),
                arguments = arguments,
                contenu = contenu,
                genreInline = genreInline,
            )
        }

        /** Arguments explicites de l'appel (la liste entre parenthèses). */
        private fun marcherArguments(appel: NoeudSyntaxique): List<ArgumentAppelKotlin> {
            val liste =
                appel.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST }
                    ?: return emptyList()
            return liste.sousNoeuds
                .filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
                .mapNotNull(::argumentDe)
        }

        /** Un argument de l'appel, ou null (valeur absente — édition). */
        private fun argumentDe(argument: NoeudSyntaxique): ArgumentAppelKotlin? {
            val nom =
                argument.sousNoeuds
                    .firstOrNull { enfant -> enfant.type == KtNodeTypes.VALUE_ARGUMENT_NAME }
                    ?.jetonsDescendants()
                    ?.firstOrNull { it.type == KtTokens.IDENTIFIER }
                    ?.texte
                    ?.toString()
            val valeur =
                argument.sousNoeuds.firstOrNull { enfant ->
                    enfant.type != KtNodeTypes.VALUE_ARGUMENT_NAME
                } ?: return null
            return ArgumentAppelKotlin(
                nom = nom,
                genre = genreDeValeur(valeur),
                texte = tranche(valeur.debut, valeur.fin),
            )
        }

        /** Le genre d'interprétation de la VALEUR d'un argument. */
        private fun genreDeValeur(valeur: NoeudSyntaxique): GenreArgumentAppelKotlin =
            when {
                genreLitteral(valeur) != null -> GenreArgumentAppelKotlin.LITTERAL
                valeur.type == KtNodeTypes.REFERENCE_EXPRESSION -> GenreArgumentAppelKotlin.REFERENCE
                (valeur.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                    valeur.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) ->
                    // a.b : référence ; a.b() : APPEL qualifié — AUTRE.
                    if (selecteurQualifie(valeur)?.type == KtNodeTypes.CALL_EXPRESSION) {
                        GenreArgumentAppelKotlin.AUTRE
                    } else {
                        GenreArgumentAppelKotlin.REFERENCE
                    }

                else -> GenreArgumentAppelKotlin.AUTRE
            }

        /**
         * Tentative d'inline d'un appel vers un composable du PROJET :
         * candidate top-level homonyme SANS récepteur, prouvée
         * `@Composable` par les imports de SON PROPRE fichier, SANS
         * paramètres (C1 : les arguments ne traversent pas l'inline).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
         * successives (appel non éligible, candidate absente, CYCLE,
         * BUDGET, délégation) sont des ISSUES DISTINCTES du même
         * garde-fou, pas une dérive.
         */
        @Suppress("ReturnCount")
        private fun inlineProjet(
            nom: String?,
            recepteur: String?,
            contenuLambda: List<NoeudAppelKotlin>,
        ): Pair<GenreInlineKotlin, List<NoeudAppelKotlin>> {
            if (nom == null || recepteur != null) return GenreInlineKotlin.AUCUN to contenuLambda
            val candidate = candidateProjet(nom) ?: return GenreInlineKotlin.AUCUN to contenuLambda
            val cle = "${candidate.uri}#${candidate.nom}"
            if (cle in etat.inlineEnCours) return GenreInlineKotlin.PROJET_CYCLE to contenuLambda
            if (etat.inlineEnCours.size >= PROFONDEUR_INLINE || etat.budget <= 0) {
                return GenreInlineKotlin.PROJET_BUDGET to contenuLambda
            }
            return ouvrirCandidate(candidate, contenuLambda)
        }

        /** La candidate top-level du projet pour un appel simple, ou null. */
        private fun candidateProjet(nom: String): DeclarationProjet? =
            contexte.declarationsProjet.firstOrNull { declaration ->
                declaration.genre == GenreDeclaration.FONCTION &&
                    declaration.conteneur == null &&
                    declaration.recepteur == null &&
                    declaration.nom == nom
            }

        /**
         * Ouverture + preuve + marche du corps de la candidate — le
         * genre rendu reflète le résultat : [PROJET_INLINE] avec le
         * corps marché en contenu, ou un genre d'échec TYPIQUE. Une
         * candidate qui n'est PAS composable retombe à [AUCUN] (appel
         * inconnu du rendu — l'inline ne concerne que les COMPOSABLES).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — les issues
         * (illisible ×3, non-composable, paramétré) sont DISTINCTES.
         */
        @Suppress("ReturnCount")
        private fun ouvrirCandidate(
            candidate: DeclarationProjet,
            contenuLambda: List<NoeudAppelKotlin>,
        ): Pair<GenreInlineKotlin, List<NoeudAppelKotlin>> {
            val paire = contexte.port.fichierDe(candidate.uri)
                ?: return GenreInlineKotlin.PROJET_ILLISIBLE to contenuLambda
            val (arbreCible, texteCible) = paire
            val fonctionCible = fonctionANom(arbreCible.racine, candidate.debutNom)
                ?: return GenreInlineKotlin.PROJET_ILLISIBLE to contenuLambda
            val contexteCible = contexte.port.contexteNomDe(arbreCible.racine)
            val composable =
                fonctionCible.annotations().any { annotation ->
                    fqcnAnnotation(
                        annotation.nomEcrit(),
                        contexte.resolution,
                        contexteCible,
                    ) == FQCN_COMPOSABLE
                }
            if (!composable) return GenreInlineKotlin.AUCUN to contenuLambda
            if (fonctionCible.parametres().isNotEmpty()) {
                return GenreInlineKotlin.PROJET_PARAMETRES to contenuLambda
            }
            val blocCible = fonctionCible.corps()?.noeud
                ?: return GenreInlineKotlin.PROJET_ILLISIBLE to contenuLambda
            val cle = "${candidate.uri}#${candidate.nom}"
            etat.inlineEnCours.add(cle)
            val marcheCible =
                Marche(
                    Contexte(
                        uri = candidate.uri,
                        texte = texteCible,
                        contexteNom = contexteCible,
                        resolution = contexte.resolution,
                        declarationsProjet = contexte.declarationsProjet,
                        port = contexte.port,
                    ),
                    etat,
                )
            val noeuds = marcheCible.marcherBloc(blocCible)
            etat.inlineEnCours.remove(cle)
            return GenreInlineKotlin.PROJET_INLINE to noeuds
        }
    }

    /** Les expressions non-appel qui se tranchent honnêtement (C1). */
    private val TYPES_EXPRESSION_NON_APPEL: Set<com.intellij.platform.syntax.SyntaxElementType> =
        setOf(
            KtNodeTypes.REFERENCE_EXPRESSION,
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION,
            KtNodeTypes.BINARY_EXPRESSION,
            KtNodeTypes.BINARY_WITH_TYPE,
            KtNodeTypes.PREFIX_EXPRESSION,
            KtNodeTypes.POSTFIX_EXPRESSION,
            KtNodeTypes.STRING_TEMPLATE,
            KtNodeTypes.INTEGER_CONSTANT,
            KtNodeTypes.FLOAT_CONSTANT,
            KtNodeTypes.BOOLEAN_CONSTANT,
            KtNodeTypes.RETURN,
            KtNodeTypes.OBJECT_LITERAL,
            KtNodeTypes.ANNOTATED_EXPRESSION,
            KtNodeTypes.DESTRUCTURING_DECLARATION,
            KtNodeTypes.IS_EXPRESSION,
            KtNodeTypes.LAMBDA_EXPRESSION,
        )
}
