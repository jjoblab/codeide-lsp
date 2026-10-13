package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.parse.kt.typeTexteDepuis
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Inférence BORNÉE des paramètres de lambda (K4, LSP_KOTLIN.md §4.3
 * « paramètres/récepteurs de lambda et SAM en K4 ») — le type des
 * paramètres EXPLICITES d'un littéral de fonction et du `it` implicite,
 * déduit de l'ATTENTE DÉMONTRABLE :
 *
 *  - appel d'une fonction du PROJET dont le paramètre correspondant
 *    porte un TYPE DE FONCTION écrit (`action: (Int) -> Unit`) ;
 *  - appel de CONSTRUCTEUR d'une interface du projet à méthode UNIQUE
 *    (SAM) : les paramètres du littéral suivent ceux de la méthode ;
 *  - INITIALISEUR d'une propriété dont le type écrit est un type de
 *    fonction (`val cb: (Int) -> Unit = { … }`).
 *
 * BORNES (D3) : candidates du PROJET seules (le classpath a des types
 * inférés que nous ne lisons pas), types de texte SIMPLES (les
 * imbriqués `((Int) -> Unit) -> Unit` sous-rapportent), nullabilité non
 * modélisée (la base est rendue). Les récepteurs de lambda (`with`)
 * restent K5+.
 *
 * Exemptions detekt ciblées (règle 16) : LongParameterList — le
 * CONTEXTE de résolution (nommage, classpath, projet) est FILÉ tel quel
 * le long des appels, patron TypageExpression ; ReturnCount — chaque
 * garde conservatrice (paramètre absent, attente introuvable, texte non
 * simple) est une ISSUE distincte, D3 ; TooManyFunctions — le catalogue
 * des CONSOMMATEURS d'attente (propriété, appel, SAM) ; LoopWithTooMany
 * JumpStatements — le continue de la candidate non porteuse est le
 * patron même du parcours.
 */
@Suppress("LongParameterList", "ReturnCount", "TooManyFunctions", "LoopWithTooManyJumpStatements")
internal object ParametresLambda {
    /**
     * Type (FQCN) du paramètre [nom] du littéral de fonction qui
     * ENGLOBE [decalage] — explicite ou `it` implicite.
     *
     * @param racine racine du document porteur.
     * @param decalage position de la référence (corps du littéral).
     * @param nom nom du paramètre recherché (`it` pour l'implicite).
     * @param contexteNom contexte de nommage (résolution des types écrits).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet (document d'abord).
     */
    internal fun typeParametre(
        racine: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? {
        val chaine = chaineAncetresA(racine, decalage)
        val indiceLitteral = indiceLitteralEnglobant(chaine, decalage) ?: return null
        val litteral = chaine[indiceLitteral]
        val indexParametre = indexParametre(litteral, nom) ?: return null
        val types =
            typesAttendus(chaine, indiceLitteral, litteral, contexteNom, resolution, declarationsProjet)
                ?: return null
        return types.getOrNull(indexParametre)
    }

    /** Indice du littéral ENGLOBANT dans la chaîne (le plus profond). */
    private fun indiceLitteralEnglobant(
        chaine: List<NoeudSyntaxique>,
        decalage: Int,
    ): Int? {
        val litteral =
            chaine.lastOrNull { it.type == KtNodeTypes.FUNCTION_LITERAL && it.debut <= decalage && decalage <= it.fin }
                ?: return null
        return chaine.indexOf(litteral)
    }

    /** Index du paramètre [nom] dans le littéral (`it` implicite → 0). */
    private fun indexParametre(
        litteral: NoeudSyntaxique,
        nom: String,
    ): Int? {
        val liste = litteral.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
        if (liste == null) return if (nom == "it") 0 else null
        val parametres = liste.sousNoeuds.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
        parametres.forEachIndexed { indice, parametre ->
            if (nomReference(parametre) == nom) return indice
        }
        return null
    }

    /**
     * Types attendus des paramètres du littéral selon son CONSOMMATEUR :
     * propriété typée, fonction projet, ou SAM de projet.
     */
    private fun typesAttendus(
        chaine: List<NoeudSyntaxique>,
        indiceLitteral: Int,
        litteral: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): List<String>? {
        // Ancêtres STRICTS du littéral (le consommateur vit au-dessus) ;
        // un AUTRE littéral entre les deux coupe l'attente directe.
        val ancetres = chaine.subList(0, indiceLitteral)
        val anzLitteraux = ancetres.count { it.type == KtNodeTypes.FUNCTION_LITERAL }
        if (anzLitteraux == 0) {
            typesDepuisPropriete(ancetres, contexteNom, resolution)?.let { return it }
            typesDepuisAppel(ancetres, litteral, contexteNom, resolution, declarationsProjet)?.let { return it }
        }
        return null
    }

    /** (a) `val cb: (Int, String) -> Unit = { … }` — l'attente est ÉCRITE. */
    private fun typesDepuisPropriete(
        ancetres: List<NoeudSyntaxique>,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): List<String>? {
        // La propriété la plus PROCHE au-dessus du littéral (le dernier
        // PROPERTY de la chaîne avant le premier appel croisé).
        val propriete = ancetres.lastOrNull { it.type == KtNodeTypes.PROPERTY } ?: return null
        val reference = propriete.sousNoeuds.firstOrNull { it.type == KtNodeTypes.TYPE_REFERENCE } ?: return null
        val fonction = reference.sousNoeuds.firstOrNull { it.type == KtNodeTypes.FUNCTION_TYPE } ?: return null
        return typesDuNoeudFonction(fonction, contexteNom, resolution)
    }

    /**
     * (b) Appel englobant : `f(2) { … }` — l'attente vient du paramètre
     * D'INDICE correspondant de la candidate du projet (fonction ou
     * interface SAM à méthode unique).
     */
    private fun typesDepuisAppel(
        ancetres: List<NoeudSyntaxique>,
        litteral: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): List<String>? {
        // L'appel le plus PROCHE au-dessus du littéral — les arguments
        // (LAMBDA_ARGUMENT/VALUE_ARGUMENT/VALUE_ARGUMENT_LIST) vivent
        // entre les deux, ce qui est attendu.
        val appel = ancetres.lastOrNull { it.type == KtNodeTypes.CALL_EXPRESSION } ?: return null
        val nomAppele = nomAppele(appel) ?: return null
        val indiceArgument = indiceArgumentLambda(appel, litteral)
        // Candidates FONCTION du projet (fichier courant dominants :
        // declarationsProjet est ordonné document d'abord, scan ensuite).
        val fonctions = declarationsProjet.filter { it.genre == GenreDeclaration.FONCTION && it.nom == nomAppele }
        for (fonction in fonctions) {
            if (fonction.parametres.size <= indiceArgument) continue
            val typeEcrit = fonction.parametres[indiceArgument].second ?: continue
            typesDuTexteFonction(typeEcrit, contexteNom, resolution)?.let { return it }
        }
        // Candidate CONSTRUCTEUR : interface du projet à méthode UNIQUE (SAM).
        return typesSam(nomAppele, declarationsProjet, contexteNom, resolution)
    }

    /** Types des paramètres d'un SAM du projet (interface à méthode unique). */
    private fun typesSam(
        nomAppele: String,
        declarationsProjet: List<DeclarationProjet>,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): List<String>? {
        val interfaces = declarationsProjet.filter { it.genre == GenreDeclaration.INTERFACE && it.nom == nomAppele }
        if (interfaces.size != 1) return null
        val interfaceCible = interfaces.single()
        val fqcnInterface = (interfaceCible.paquet?.let { "$it." } ?: "") + interfaceCible.nom
        val methodes =
            declarationsProjet.filter { it.conteneur == fqcnInterface && it.genre == GenreDeclaration.FONCTION }
        if (methodes.size != 1) return null
        val types = mutableListOf<String>()
        for ( (_, typeEcrit) in methodes.single().parametres) {
            val type = typeEcrit ?: return null
            types.add(
                resolution.fqcnDuNomEcrit(type.substringBefore('<').trim().trimEnd('?'), contexteNom)
                    ?: return null,
            )
        }
        return types
    }

    /** Indice de l'argument LAMBDA dans la liste (les lambdas ferment l'appel). */
    private fun indiceArgumentLambda(
        appel: NoeudSyntaxique,
        litteral: NoeudSyntaxique,
    ): Int {
        val liste = appel.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST } ?: return 0
        val arguments = liste.sousNoeuds.filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
        val ordinaire =
            arguments.indexOfFirst { argument -> argument.debut <= litteral.debut && litteral.fin <= argument.fin }
        return if (ordinaire >= 0) ordinaire else arguments.size
    }

    /** Types des paramètres depuis le NŒUD FUNCTION_TYPE (types simples). */
    private fun typesDuNoeudFonction(
        fonction: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): List<String>? {
        val liste = fonction.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST } ?: return null
        val types = mutableListOf<String>()
        for (parametre in liste.sousNoeuds.filter { it.type == KtNodeTypes.VALUE_PARAMETER }) {
            val reference = parametre.sousNoeuds.firstOrNull { it.type == KtNodeTypes.TYPE_REFERENCE } ?: return null
            val texte = typeTexteDepuis(reference) ?: return null
            types.add(fqcnDeTypeSimple(texte, contexteNom, resolution) ?: return null)
        }
        return types
    }

    /**
     * Texte « (A, B) -> R » découpé en types de paramètres — les
     * parenthèses IMBRIQUÉES sont refusées (D3, sous-rapporte).
     */
    private fun typesDuTexteFonction(
        texte: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): List<String>? {
        val net = texte.trim()
        if (!net.startsWith("(")) return null
        val finListe = net.indexOf(')')
        if (finListe < 0) return null
        val interieur = net.substring(1, finListe).trim()
        if (interieur.contains('(') || interieur.contains(')')) return null
        val morceaux = if (interieur.isEmpty()) emptyList() else interieur.split(',').map { it.trim() }
        return morceaux.map { fqcnDeTypeSimple(it, contexteNom, resolution) ?: return null }
    }

    /** FQCN d'un type de texte simple (`Int`, `Vue`, `List<String>` → base). */
    private fun fqcnDeTypeSimple(
        texte: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): String? {
        val nomEcrit = texte.substringBefore('<').trim().trimEnd('?')
        if (nomEcrit.isEmpty() || nomEcrit.contains(' ')) return null
        return resolution.fqcnDuNomEcrit(nomEcrit, contexteNom)
    }
}
