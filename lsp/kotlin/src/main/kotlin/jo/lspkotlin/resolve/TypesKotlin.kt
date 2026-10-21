package jo.lspkotlin.resolve

import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.nomTypeEcrit
import jo.lspkotlin.parse.kt.typeTexteDepuis
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Navigation de TYPES (L2 — plan 1.3.0) : `textDocument/typeDefinition`
 * (la déclaration du TYPE du symbole visé) et `textDocument/
 * implementation` (les héritiers PROUVÉS du type en contexte).
 *
 * ## typeDefinition — la chaîne
 * Le curseur désigne soit un TYPE (référence de type, constructeur
 * appelé), soit un SYMBOLE TYPÉ (locale/paramètre/propriété de type
 * écrit, fonction de retour écrit). Le type visé est le PREMIER
 * prouvable de la chaîne :
 *  1. référence de TYPE sous le curseur (USER_TYPE/TYPE_REFERENCE) —
 *     le type EST la cible ;
 *  2. constructeur appelé (callee d'un CALL_EXPRESSION englobant le
 *     curseur) résolu en classe PROJET — la classe construite ;
 *  3. nom DÉCLARÉ sous le curseur (locale/paramètre/propriété/
 *     fonction du projet) portant un type ÉCRIT — ce type ;
 *  4. référence vers une locale en portée / une déclaration du projet
 *     portant un type ÉCRIT — ce type.
 * Le NOM DU TYPE est ensuite résolu dans les déclarations du PROJET
 * (même conservatisme que [InterrogationsSymboles.definitions] : les
 * cibles du classpath n'ont pas de source — liste VIDE).
 *
 * ## implementations — la preuve
 * Le type en contexte (référence de type sous le curseur, sinon la
 * classe ENGLOBANTE) ; chaque candidate est une classe/objet du PROJET
 * dont un supertype ÉCRIT résout vers le FQCN de la cible PAR LES
 * IMPORTS DE SON PROPRE FICHIER (une homonymie de nom simple ne
 * suffit pas — preuve par FQCN, miroir de la preuve d'occurrences).
 * Les sources JAVA du scan ne portent pas de supertypes extraits :
 * jamais candidates (sous-rapport D3, jamais de faux héritier).
 */
/**
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque
 * méthode est une ÉTAPE distincte de la chaîne de preuve (cible en
 * contexte, définitions, candidates d'héritage, supertypes, preuve
 * FQCN, déclaration au curseur, identifiant) : le compte épouse le
 * CONTRAT de navigation de types, pas une dérive.
 */
@Suppress("TooManyFunctions")
internal object TypesKotlin {
    /** Genres de déclaration qui SONT des types. */
    private val GENRES_TYPES =
        setOf(GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE, GenreDeclaration.OBJET, GenreDeclaration.ALIAS)

    /**
     * Le TYPE visé à [decalage] (cible commune typeDefinition/
     * implementations) : la DÉCLARATION de type projet visée par la
     * chaîne du KDoc de classe, ou `null` si rien n'est prouvé.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/
     * CyclomaticComplexMethod — chaque étape de la chaîne (référence
     * de type, constructeur, nom déclaré, référence typée) est une
     * ISSUE distincte du conservatisme D3 (miroir
     * InterrogationsSymboles.definitions).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    internal fun typeEnContexte(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): DeclarationProjet? {
        val chaine = chaineAncetresA(racine, decalage)
        // 1. Référence de TYPE sous le curseur : le type EST la cible.
        chaine.lastOrNull { it.type == KtNodeTypes.USER_TYPE || it.type == KtNodeTypes.TYPE_REFERENCE }
            ?.let { usage -> nomTypeEcrit(usage)?.let { nom -> return typeProjet(nom, uri, contexte) } }

        // 2. Constructeur appelé : le callee d'un CALL_EXPRESSION
        //    englobant le curseur résolu en classe PROJET.
        val appel = chaine.lastOrNull { it.type == KtNodeTypes.CALL_EXPRESSION }
        if (appel != null) {
            nomAppele(appel)?.let { nom -> typeProjet(nom, uri, contexte)?.let { return it } }
        }

        // 3. Nom DÉCLARÉ sous le curseur : sa déclaration projet
        //    (locale comprise — la locale vit dans le fichier courant).
        declarationAuCurseur(racine, decalage, uri, contexte)?.let { declaration ->
            typeDeDeclaration(declaration)
                ?.let { nomType -> typeProjet(nomType, uri, contexte) }
                ?.let { return it }
            declarationSiType(declaration)?.let { return it }
        }

        // 4. Référence vers un symbole TYPÉ : locale en portée, puis
        //    déclaration projet homonyme.
        identifiantA(racine, decalage)?.let { jeton ->
            val nom = jeton.texte.toString()
            SymbolesProjet.localesEnPortee(racine, decalage)
                .firstOrNull { it.declaration.nom == nom }
                ?.declaration
                ?.let { locale ->
                    typeDeDeclaration(locale)?.let { nomType -> return typeProjet(nomType, uri, contexte) }
                }
            contexte.declarationsProjet
                .filter { it.nom == nom && it.genre !in setOf(GenreDeclaration.PARAMETRE, GenreDeclaration.LOCALE) }
                .firstOrNull { declaration -> declaration.uri.isNotBlank() }
                ?.let { declaration ->
                    typeDeDeclaration(declaration)
                        ?.let { nomType -> typeProjet(nomType, uri, contexte) }
                        ?.let { return it }
                    declarationSiType(declaration)?.let { return it }
                }
        }
        return null
    }

    /**
     * Définitions du TYPE du symbole visé (`textDocument/typeDefinition`)
     * — emplacements du NOM déclaré du type cible, projet seul.
     */
    internal fun definitionsType(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): List<EmplacementKotlin> =
        typeEnContexte(racine, decalage, uri, contexte)
            ?.let { cible -> listOf(EmplacementKotlin(cible.uri, cible.debutNom, cible.finNom)) }
            ?: emptyList()

    /**
     * Implémentations du type [cible] dans UN arbre : les classes/objets
     * dont un supertype ÉCRIT résout vers le FQCN de la cible PAR LES
     * IMPORTS DE CE FICHIER ([contexteFichier] — preuve par FQCN).
     */
    internal fun implementationsDansArbre(
        racine: NoeudSyntaxique,
        uri: String,
        cible: DeclarationProjet,
        contexteFichier: OccurrencesKotlin.ContexteFichier,
        resolution: ResolutionKotlin,
    ): List<EmplacementKotlin> {
        val fqcnCible = (cible.paquet?.let { "$it." } ?: "") + cible.nom
        val resultat = mutableListOf<EmplacementKotlin>()
        for (declaration in declarationsTypesDe(racine, uri)) {
            // La cible elle-même n'est pas sa propre implémentation.
            if (declaration.debutNom == cible.debutNom && declaration.uri == cible.uri) continue
            for (supertype in declaration.superTypes) {
                if (supertypeResoutVers(supertype, fqcnCible, contexteFichier, resolution)) {
                    resultat.add(EmplacementKotlin(declaration.uri, declaration.debutNom, declaration.finNom))
                    break
                }
            }
        }
        return resultat
    }

    /**
     * Les classes et objets NOMMÉS d'un arbre avec leurs supertypes
     * ÉCRITS — les candidates à l'héritage (l'arbre vit : le scan
     * projet ne porte pas les supertypes, seul l'arbre les prouve).
     * Les objets portent une clause `object X : Iface` : la liste de
     * supertypes est lue sur le NŒUD (mêmes entrées que KtClass).
     */
    private fun declarationsTypesDe(
        racine: NoeudSyntaxique,
        uri: String,
    ): List<CandidateType> {
        val resultat = mutableListOf<CandidateType>()
        fun parcourir(noeud: NoeudSyntaxique) {
            val element = KtElement.depuis(noeud)
            val declaration = element as? KtDeclaration
            val estType =
                noeud.type == KtNodeTypes.CLASS || noeud.type == KtNodeTypes.OBJECT_DECLARATION
            if (declaration != null && estType) {
                declaration.nomJeton()?.let { jeton ->
                    resultat.add(
                        CandidateType(
                            uri = uri,
                            debutNom = jeton.debut,
                            finNom = jeton.fin,
                            superTypes = superTypesDe(noeud),
                        ),
                    )
                }
            }
            noeud.sousNoeuds.forEach { parcourir(it) }
        }
        parcourir(racine)
        return resultat
    }

    /** Entrées de la clause de supertypes d'un nœud classe/objet. */
    private fun superTypesDe(noeud: NoeudSyntaxique): List<String> {
        val clause =
            noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.SUPER_TYPE_LIST } ?: return emptyList()
        return clause.sousNoeuds
            .filter {
                it.type == KtNodeTypes.SUPER_TYPE_ENTRY ||
                    it.type == KtNodeTypes.SUPER_TYPE_CALL_ENTRY ||
                    it.type == KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY
            }
            .mapNotNull { typeTexteDepuis(it) }
    }

    /** Une candidate à l'héritage : nom + supertypes écrits. */
    private data class CandidateType(
        val uri: String,
        val debutNom: Int,
        val finNom: Int,
        val superTypes: List<String>,
    )

    /**
     * Un supertype ÉCRIT (`View`, `Base()`, `Iface<String>`, `a.b.C`)
     * résout-il vers [fqcnCible] ? Le texte est NETTOYÉ (arguments de
     * construction, génériques, nullabilité), puis résolu par les
     * imports du fichier — preuve par FQCN, jamais par homonymie.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * du nettoyage (parenthèses, génériques, nullabilité, vide) est
     * une ISSUE distincte.
     */
    @Suppress("ReturnCount")
    private fun supertypeResoutVers(
        supertype: String,
        fqcnCible: String,
        contexteFichier: OccurrencesKotlin.ContexteFichier,
        resolution: ResolutionKotlin,
    ): Boolean {
        var nom = supertype.trim()
        val parenthese = nom.indexOf('(')
        if (parenthese >= 0) nom = nom.substring(0, parenthese)
        val generique = nom.indexOf('<')
        if (generique >= 0) nom = nom.substring(0, generique)
        nom = nom.trimEnd('?').trim()
        if (nom.isEmpty()) return false
        if ('.' in nom) {
            // Nom qualifié écrit : la cible porte-t-elle CE fqcn ?
            return nom == fqcnCible
        }
        return resolution.fqcnDuNomEcrit(nom, contexteFichier.contexteNom) == fqcnCible
    }

    /**
     * Déclaration projet de TYPE homonyme (l'étape « le nom du type
     * résout ») — PREUVE PAR FQCN côté curseur : le nom écrit résout
     * d'abord par les IMPORTS du fichier du curseur (puis par unicité
     * projet, miroir de [ResolutionKotlin.fqcnDuNomEcrit]) ; la
     * déclaration portant CE fqcn est servie. Sans preuve (homonymie
     * ambiguë sans import, type du classpath sans source) : `null` —
     * jamais la première homonyme venue.
     */
    private fun typeProjet(
        nom: String,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): DeclarationProjet? {
        val fqcn = contexte.resolution.fqcnDuNomEcrit(nom, contexte.contexteNom) ?: return null
        return contexte.declarationsProjet
            .filter { declaration ->
                declaration.genre in GENRES_TYPES &&
                    declaration.uri.isNotBlank() &&
                    (declaration.paquet?.let { "$it." } ?: "") + declaration.nom == fqcn
            }
            .sortedBy { it.uri != uri }
            .firstOrNull()
    }

    /** La déclaration elle-même si elle EST un type (constructeur visé). */
    private fun declarationSiType(declaration: DeclarationProjet): DeclarationProjet? =
        if (declaration.genre in GENRES_TYPES && declaration.uri.isNotBlank()) declaration else null

    /**
     * Déclaration (projet) dont le NOM est sous le curseur — miroir du
     * [InterrogationsSymboles] privé du même nom : la plus profonde
     * gagne (locale avant membre avant premier niveau).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue des étapes non concluantes par PROFONDEUR sont le
     * contrat même du balayage (miroir identifiantA).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun declarationAuCurseur(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): DeclarationProjet? {
        val chaine = chaineAncetresA(racine, decalage)
        for (profondeur in chaine.indices.reversed()) {
            val declaration = KtElement.depuis(chaine[profondeur]) as? KtDeclaration ?: continue
            val jeton = declaration.nomJeton() ?: continue
            if (jeton.debut > decalage || decalage > jeton.fin) continue
            return SymbolesProjet.declarations(racine, uri, contexte.contexteNom.paquet)
                .firstOrNull { it.debutNom == jeton.debut && it.finNom == jeton.fin }
        }
        return null
    }

    /**
     * Type ÉCRIT d'une déclaration (retour d'une fonction, valeur d'une
     * propriété/paramètre) — le TEXTE brut (`List<String>`, `Vue?`) :
     * le NOM SIMPLE (dernier segment, génériques et nullabilité
     * retirés) est extrait par l'appelant.
     */
    private fun typeDeDeclaration(declaration: DeclarationProjet): String? =
        declaration.typeRetour?.let { texte ->
            var nom = texte.trim()
            val generique = nom.indexOf('<')
            if (generique >= 0) nom = nom.substring(0, generique)
            nom = nom.trimEnd('?').trim()
            nom.substringAfterLast('.').ifBlank { null }
        }

    /** Jeton IDENTIFIER au curseur (miroir du résolveur d'interrogations). */
    private fun identifiantA(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): JetonSyntaxique? {
        val chaine = chaineAncetresA(racine, decalage)
        for (profondeur in chaine.indices.reversed()) {
            val jeton =
                chaine[profondeur].jetonsDescendants().firstOrNull {
                    it.type == KtTokens.IDENTIFIER && it.debut <= decalage && decalage <= it.fin
                }
            if (jeton != null) return jeton
        }
        return null
    }
}
