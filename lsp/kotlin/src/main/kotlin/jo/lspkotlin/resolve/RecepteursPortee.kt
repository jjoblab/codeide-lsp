package jo.lspkotlin.resolve

import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomAppele
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * RÉCEPTEURS DE PORTÉE des littéraux de fonction (vague 5, retour
 * utilisateurs 25/10/2026 — U1 « weight »/« align » : le récepteur
 * implicite du lambda de SCOPE) : dans `Row { … }`, le paramètre
 * `content: @Composable RowScope.() -> Unit` prête `RowScope` comme
 * this IMPLICITE à TOUT le corps du littéral — les EXTENSIONS DE
 * SCOPE (`Modifier.weight(1f)`, membre-extension de RowScope) et les
 * membres NUS (`align(Alignment.BottomCenter)`) y résolvent, miroir
 * kotlinc.
 *
 * D'OU VIENT LE RÉCEPTEUR (le consommateur du littéral) :
 *  - PROJET : fonction candidate dont le paramètre correspondant
 *    porte un type de fonction ÉCRIT AVEC RÉCEPTEUR (`A.(B) -> C` —
 *    le récepteur vit AVANT la parenthèse, grammaire Kotlin) — texte
 *    exact, aucune ambiguïté ;
 *  - CLASSPATH : appelable INDEXÉ (métadonnées) dont le paramètre
 *    correspondant rend `FunctionN<A, …, R>` — le PREMIER argument
 *    de type est le récepteur POTENTIEL. BORNE (D3, documentée) :
 *    les métadonnées 2.x ne DISTINGUENT PAS `A.() -> B` de
 *    `(a: A) -> B` (l'annotation `kotlin.ExtensionFunctionType` du
 *    paramètre n'est PAS rendue par l'API publique kotlin-metadata
 *    2.2.10 — expérience vague 5 : les DEUX sérialisent
 *    `Function1<A, B>` octet pour octet) : le premier argument d'un
 *    type fonctionnel de PLUSIEURS paramètres peut être un simple
 *    PREMIER PARAMÈTRE — un nom nu membre de ce type dans le corps
 *    est alors TENU POUR résolu (sous-rapportage, jamais de rouge
 *    mensonger) ; les récepteurs VRAIS (Compose : RowScope,
 * BoxScope,
 *    LazyListScope…) dominent massivement ce périmètre en pratique.
 *
 * Le FQCN du récepteur écrit COURT (« RowScope ») se résout par le
 * contexte du fichier (imports — `RowScope` n'y figure JAMAIS dans
 * le code réel : la classe de portée ne s'importe pas), puis par
 * l'UNICITÉ du nom simple dans la vue (vague 4 —
 * [ResolutionKotlin.fqcnUniqueParNomSimple]) ; il doit ÊTRE LISIBLE
 * dans la vue (membres non null) pour être retenu.
 *
 * PILE : les littéraux emboîtés prêtent leurs récepteurs du plus
 * PROFOND au plus externe (`Row { Column { … } }` — ColumnScope
 * puis RowScope, miroir kotlinc : les récepteurs s'empilent).
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque garde
 * (pas de littéral, consommateur introuvable, autre littéral
 * intermédiaire, paramètre absent, type illisible) est une ISSUE
 * distincte du catalogue D3 ; LoopWithTooManyJumpStatements — le
 * continue du littéral non consommé est le patron du parcours ;
 * LongParameterList — le contexte complet de résolution est FILÉ le
 * long des appels, patron TypageExpression.
 */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements", "LongParameterList")
internal object RecepteursPortee {
    /** Plafond de récepteurs de portée par référence (pile réelle bornée). */
    private const val RECEPTEURS_MAXI: Int = 8

    /**
     * FQCN des récepteurs de portée des littéraux englobant
     * [decalage], du plus PROFOND au plus externe — vide si aucun.
     *
     * @param racine racine du document porteur.
     * @param decalage position du curseur (corps du littéral).
     * @param contexteNom contexte de nommage (résolution des types écrits).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet.
     * @param index appelables indexés du classpath (récepteurs classpath).
     */
    internal fun recepteursDePortee(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        index: IndexAppelablesKotlin?,
    ): List<String> {
        val chaine = chaineAncetresA(racine, decalage)
        val resultat = mutableListOf<String>()
        for (profondeur in chaine.indices.reversed()) {
            if (resultat.size >= RECEPTEURS_MAXI) break
            val litteral = chaine[profondeur]
            if (litteral.type != KtNodeTypes.FUNCTION_LITERAL) continue
            if (litteral.debut > decalage || decalage > litteral.fin) continue
            recepteurDuLitteral(chaine, profondeur, litteral, contexteNom, resolution, declarationsProjet, index)
                ?.let { if (it !in resultat) resultat.add(it) }
        }
        return resultat
    }

    /**
     * Récepteur de portée d'UN littéral : l'appel CONSOMMATEUR le plus
     * proche au-dessus, sans AUTRE littéral entre les deux (patron
     * [RecepteursWith.recepteurDuLitteral]) — son callee donne la
     * candidate, le paramètre PORTANT le littéral donne le type
     * fonctionnel attendu.
     */
    @Suppress("ReturnCount")
    private fun recepteurDuLitteral(
        chaine: List<NoeudSyntaxique>,
        indiceLitteral: Int,
        litteral: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        index: IndexAppelablesKotlin?,
    ): String? {
        val ancetres = chaine.subList(0, indiceLitteral)
        val appel = ancetres.lastOrNull { it.type == KtNodeTypes.CALL_EXPRESSION } ?: return null
        // Un littéral ENTRE les deux coupe la liaison consommateur.
        val span = ancetres.subList(ancetres.indexOf(appel) + 1, ancetres.size)
        if (span.any { it.type == KtNodeTypes.FUNCTION_LITERAL }) return null
        val nom = nomAppele(appel) ?: return null
        // [RecepteursWith] : `with`/`apply`/`run` portent le récepteur
        // de l'APPEL, pas un récepteur de PORTÉE.
        if (nom in RECEPTEURS_APPEL) return null
        // Indice du paramètre PORTANT le littéral : trailing (ferme
        // l'appel) = DERNIER, ordinaire = son indice dans la liste.
        val lambdaTrailing =
            appel.sousNoeuds.any {
                it.type == KtNodeTypes.LAMBDA_ARGUMENT && it.debut <= litteral.debut && litteral.fin <= it.fin
            }
        val indiceParametre =
            if (lambdaTrailing) -1 else indiceArgumentOrdinaire(appel, litteral)
        // (a) Candidate PROJET : le type ÉCRIT porte le récepteur.
        recepteurProjet(nom, indiceParametre, declarationsProjet, contexteNom, resolution)?.let { return it }
        // (b) Candidate CLASSPATH : l'index rend le type fonctionnel.
        return recepteurClasspath(nom, indiceParametre, contexteNom, resolution, index)
    }

    /** Indice de l'argument LAMBDA ORDINAIRE (non trailing), `-1` si absent. */
    private fun indiceArgumentOrdinaire(
        appel: NoeudSyntaxique,
        litteral: NoeudSyntaxique,
    ): Int {
        val liste = appel.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST } ?: return -1
        val arguments = liste.sousNoeuds.filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
        return arguments.indexOfFirst { argument -> argument.debut <= litteral.debut && litteral.fin <= argument.fin }
    }

    /**
     * (a) Récepteur d'une candidate du PROJET : `fun Row(…, content:
     * RowScope.() -> Unit)` — le type écrit du paramètre correspondant
     * porte `Récepteur.` AVANT la parenthèse (grammaire Kotlin, texte
     * exact — l'ambiguïté classpath ne s'applique PAS aux sources).
     */
    private fun recepteurProjet(
        nom: String,
        indiceParametre: Int,
        declarationsProjet: List<DeclarationProjet>,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): String? {
        for (fonction in declarationsProjet) {
            if (fonction.genre != GenreDeclaration.FONCTION || fonction.nom != nom) continue
            val typeEcrit =
                typeDuParametre(fonction, indiceParametre) ?: continue
            // `A.(B) -> C` : récepteur AVANT la parenthèse OUVRANTE
            // de tête — un nom (éventuellement qualifié), puis `.(`
            // ou `.)`... la parenthèse fermante du récepteur.
            val receveur = recepteurEcritDe(typeEcrit) ?: continue
            return fqcnDuNomCourt(receveur, contexteNom, resolution)
        }
        return null
    }

    /**
     * (b) Récepteur d'une candidate du CLASSPATH (index) : le type
     * rendu `FunctionN<A, …, R>` — le PREMIER argument de type (BORNE
     * D3 documentée dans l'en-tête d'objet).
     */
    private fun recepteurClasspath(
        nom: String,
        indiceParametre: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        index: IndexAppelablesKotlin?,
    ): String? {
        val appelables = index?.topParNom(nom).orEmpty()
        for (appelable in appelables) {
            if (!resolution.appelableEnPortee(appelable, contexteNom)) continue
            val parametres = appelable.parametres
            if (parametres.isEmpty()) continue
            val indice = if (indiceParametre >= 0) indiceParametre else parametres.size - 1
            val type = parametres.getOrNull(indice)?.type ?: continue
            val premier = premierArgumentDeTypeFonction(type) ?: continue
            return fqcnDuNomCourt(premier, contexteNom, resolution)
        }
        return null
    }

    /**
     * Type écrit du paramètre d'indice [indice] (`-1` = dernier) de
     * la déclaration FONCTION projet — `null` si absent.
     */
    private fun typeDuParametre(
        fonction: DeclarationProjet,
        indice: Int,
    ): String? {
        if (fonction.parametres.isEmpty()) return null
        return if (indice >= 0) {
            fonction.parametres.getOrNull(indice)?.second
        } else {
            fonction.parametres.last().second
        }
    }

    /**
     * Récepteur ÉCRIT d'un type de fonction textuel : `RowScope.()
     * -> Unit` → « RowScope » ; null si la tête n'est PAS une
     * parenthèse de liste de paramètres (type simple, `(A) -> B`
     * sans récepteur). Le récepteur peut être QUALIFIÉ
     * (`a.b.Scope.(…)`), jamais GÉNÉRIQUE dans ce périmètre (les
     * types de portée réels sont des interfaces simples).
     */
    private fun recepteurEcritDe(typeEcrit: String): String? {
        val net = typeEcrit.trim()
        if (!net.contains(".(")) return null
        val tete = net.substringBefore(".(").trim()
        if (tete.isEmpty() || '(' in tete || '<' in tete) return null
        // Le récepteur est un nom (segments `.`), PAS une liste de
        // paramètres : `(A, B) -> C` n'a PAS de tête `Nom.(`.
        if (!tete.all { it.isLetterOrDigit() || it == '_' || it == '.' }) return null
        return tete
    }

    /** Extensions à récepteur D'APPEL (patron [RecepteursWith]). */
    private val RECEPTEURS_APPEL = setOf("with", "apply", "run")

    /**
     * Premier argument de type d'un rendu `FunctionN<A, B, R>` —
     * niveau SOMMET (les `<>` imbriqués restent UN segment), `null`
     * si le rendu n'est pas un type fonctionnel ou n'a pas
     * d'argument.
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod —
     * le découpage des niveaux `<>`/`,` EST le catalogue du parseur
     * textuel (miroir des exemptions parsing du dépôt) ;
     * ComplexCondition — la garde de nom (lettre/chiffre/_/.) est
     * UNE seule règle d'écriture.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun premierArgumentDeTypeFonction(typeRendu: String): String? {
        val net = typeRendu.trim()
        val ouverture = net.indexOf('<')
        if (ouverture <= 0) return null
        if (!net.startsWith("Function")) return null
        if (!net.endsWith(">")) return null
        val interieur = net.substring(ouverture + 1, net.length - 1)
        var profondeur = 0
        var fin = -1
        for ((indice, caractere) in interieur.withIndex()) {
            when (caractere) {
                '<' -> profondeur++
                '>' -> profondeur--
                ',' -> if (profondeur == 0) {
                    fin = indice
                    break
                }
            }
        }
        val premier = if (fin < 0) interieur else interieur.substring(0, fin)
        val nom = premier.trim().substringBefore('<').trim().trimEnd('?')
        if (nom.isEmpty()) return null
        if (nom.any { caractere -> !caractere.isLetterOrDigit() && caractere != '_' && caractere != '.' }) return null
        return nom
    }

    /**
     * FQCN d'un nom COURT de récepteur : contexte du fichier d'abord
     * (imports — rare : la classe de portée ne s'importe pas), puis
     * unicité du nom simple dans la vue (voie vague 4) ; la classe
     * doit ÊTRE LISIBLE (membres) pour être retenue — un type
     * paramètre (« T ») ou introuvable ne prête rien.
     */
    private fun fqcnDuNomCourt(
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): String? {
        val fqcn =
            resolution.fqcnDuNomEcrit(nom, contexteNom)
                ?: resolution.fqcnUniqueParNomSimple(nom)
                ?: return null
        if (resolution.membresDe(fqcn) == null) return null
        return fqcn
    }
}
