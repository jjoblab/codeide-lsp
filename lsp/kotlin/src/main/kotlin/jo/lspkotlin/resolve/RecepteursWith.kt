package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomAppele
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Récepteur IMPLICITE d'un littéral `with` (K5 — le reliquat K4
 * documenté « récepteurs `with` : K5+ ») : dans `with(vue) { … }`, le
 * corps du littéral voit les membres de `vue` SANS sélecteur —
 * complétion de nom, typage des références nues et résolution
 * `kt.unresolved` en dépendent.
 *
 * LITTERAUX EMBOÎTÉS : le littéral le plus PROFOND gagne
 * (`with(a) { with(b) { … } }` — le corps voit `b`) ; un littéral
 * intermédiaire COUPE la recherche (`with(a) { f({ … }) }` : le
 * littéral de `f` n'est PAS un récepteur — on remonte à celui de
 * `with`).
 *
 * Le type du récepteur est celui de l'ARGUMENT ORDINAIRE d'indice 0
 * de l'appel `with` — démontré par [TypageExpression] (D3 : non
 * démontrable ⇒ `null`, le corps sous-rapporte, jamais faux).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
 * (pas de littéral, appel introuvable, autre littéral intermédiaire,
 * argument absent, type non démontrable) est une ISSUE distincte du
 * catalogue D3 ; LoopWithTooManyJumpStatements — le continue du
 * littéral non consommé est le patron du parcours.
 */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
internal object RecepteursWith {
    /**
     * FQCN du récepteur implicite du littéral `with` englobant
     * [decalage], le plus profond d'abord.
     *
     * @param racine racine du document porteur.
     * @param decalage position du curseur (corps du littéral).
     * @param contexteNom contexte de nommage (résolution des types écrits).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet.
     * @return le FQCN du récepteur, `null` sinon.
     */
    internal fun recepteurImplicite(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? {
        val chaine = chaineAncetresA(racine, decalage)
        for (profondeur in chaine.indices.reversed()) {
            val litteral = chaine[profondeur]
            if (litteral.type != KtNodeTypes.FUNCTION_LITERAL) continue
            if (litteral.debut > decalage || decalage > litteral.fin) continue
            val recepteur = recepteurDuLitteral(chaine, profondeur, contexteNom, resolution, declarationsProjet)
            if (recepteur != null) return recepteur
        }
        return null
    }

    /**
     * Récepteur d'UN littéral : l'appel CONSOMMATEUR le plus proche
     * au-dessus, sans AUTRE littéral entre les deux — son callee doit
     * être `with` (stdlib), son premier argument porte le récepteur.
     */
    private fun recepteurDuLitteral(
        chaine: List<NoeudSyntaxique>,
        indiceLitteral: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? {
        val ancetres = chaine.subList(0, indiceLitteral)
        val appel = ancetres.lastOrNull { it.type == KtNodeTypes.CALL_EXPRESSION } ?: return null
        // Un littéral ENTRE les deux : le consommateur direct de NOTRE
        // littéral est ailleurs — remonte au littéral suivant.
        val span = ancetres.subList(ancetres.indexOf(appel) + 1, ancetres.size)
        if (span.any { it.type == KtNodeTypes.FUNCTION_LITERAL }) return null
        if (nomAppele(appel) != "with") return null
        val argument = premierArgument(appel) ?: return null
        return TypageExpression.typeExpression(argument, chaine[0], contexteNom, resolution, declarationsProjet)
    }

    /** Expression du PREMIER argument ordinaire de l'appel. */
    private fun premierArgument(appel: NoeudSyntaxique): NoeudSyntaxique? {
        val liste = appel.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST } ?: return null
        val argument = liste.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT } ?: return null
        return argument.sousNoeuds.firstOrNull()
    }
}
