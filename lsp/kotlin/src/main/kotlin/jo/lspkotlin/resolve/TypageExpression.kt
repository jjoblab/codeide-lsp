package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.genreLitteral
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.parse.kt.recepteurQualifie
import jo.lspkotlin.parse.kt.selecteurQualifie
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Typage CONSERVATEUR d'une expression (K3, LSP_KOTLIN.md §4.3
 * « résolution » — « locaux typés par leur initialiseur ; retours de
 * membres et d'appels ; constructeurs ; chaînes a.b().c »).
 *
 * Le résultat est un FQCN (« android.widget.TextView », «
 * kotlin.String ») ou `null` : ce que la preuve syntaxique + classpath
 * ne démontre pas n'est PAS deviné (D3 — la complétion membre qui en
 * dépend est omise, jamais fausse). K4 : SMART CASTS positionnels et
 * inférence BORNÉE des paramètres de lambda (génériques et récepteurs
 * `with` restent K5+).
 *
 * Bornes volontaires : profondeur de chaîne bornée (garde anti-cycle),
 * identifiants résolus par les PORTÉES locales/membres/fichier puis
 * par les déclarations du PROJET (types écrits seuls, pas
 * d'initialiseur hors du fichier courant).
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — le CONTEXTE
 * de typage (racine, nommage, résolution, projet, profondeur bornée)
 * est FILÉ tel quel le long de la récursion : un data class par étape
 * noierait la profondeur, paramètre le plus important du garde
 * anti-cycle ; CyclomaticComplexMethod — chaque genre d'expression est
 * une ISSUE distincte du même when, patron du résolveur.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "LongMethod")
internal object TypageExpression {
    /** Profondeur maximale de résolution d'une chaîne a.b().c.d… */
    private const val BORN_PROFONDEUR: Int = 8

    /**
     * Type (FQCN) d'une expression.
     *
     * @param expression nœud de l'expression (référence, appel,
     *        chaîne qualifiée, littéral, this…).
     * @param racine racine du document porteur.
     * @param contexteNom contexte de nommage (imports, paquet, projet).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations de TOUT le projet (scan +
     *        documents ouverts) — propriétés/fonctions de premier
     *        niveau par nom.
     * @return le FQCN, ou `null` (non démontrable).
     */
    internal fun typeExpression(
        expression: NoeudSyntaxique,
        racine: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: jo.lspkotlin.resolve.ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? = typeExpression(expression, racine, contexteNom, resolution, declarationsProjet, 0)

    /** Implémentation récursive bornée. */
    private fun typeExpression(
        expression: NoeudSyntaxique,
        racine: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        profondeur: Int,
    ): String? {
        if (profondeur > BORN_PROFONDEUR) return null
        return when (expression.type) {
            KtNodeTypes.STRING_TEMPLATE -> "kotlin.String"
            KtNodeTypes.INTEGER_CONSTANT -> typeEntier(expression)
            KtNodeTypes.FLOAT_CONSTANT -> "kotlin.Double"
            KtNodeTypes.BOOLEAN_CONSTANT -> "kotlin.Boolean"
            KtNodeTypes.CHARACTER_CONSTANT -> "kotlin.Char"
            KtNodeTypes.NULL -> null
            KtNodeTypes.PARENTHESIZED ->
                expression.sousNoeuds.firstOrNull()
                    ?.let { typeExpression(it, racine, contexteNom, resolution, declarationsProjet, profondeur + 1) }

            KtNodeTypes.THIS_EXPRESSION -> classeEnglobante(racine, expression.debut, contexteNom)
            // ADR 0219 (B3 — parité Views) : le récepteur `super.` se TYPE
            // comme le PREMIER supertype ÉCRIT résolu de la classe
            // ENGLOBANTE (miroir Android Studio : complétion et survol
            // après `super.` offrent les membres du SUPERTYPE) ; repli
            // sur la classe ENGLOBANTE (objet sans clause, classe
            // synthétique) — ses membres déclarés restent visibles.
            KtNodeTypes.SUPER_EXPRESSION ->
                fqcnsSupertypesEnglobants(racine, expression.debut, contexteNom, resolution, declarationsProjet)
                    .firstOrNull()
                    ?: classeEnglobante(racine, expression.debut, contexteNom)
            KtNodeTypes.REFERENCE_EXPRESSION -> typeReference(
                nomReference(expression) ?: return null,
                racine,
                expression.debut,
                contexteNom,
                resolution,
                declarationsProjet,
                profondeur,
            )

            KtNodeTypes.CALL_EXPRESSION ->
                typeAppel(
                    nomAppele(expression) ?: return null,
                    contexteNom,
                    resolution,
                    declarationsProjet,
                )
                    // ADR 0219 : appel NU à un membre de la classe
                    // ENGLOBANTE (this implicite) — `getString(…)` dans
                    // une Activity, `setTitle(…)`… le typeMembreImplicite
                    // porte champs, méthodes ET getters synthétiques.
                    ?: typeMembreImplicite(
                        nomAppele(expression) ?: return null,
                        racine,
                        expression.debut,
                        contexteNom,
                        resolution,
                        declarationsProjet,
                    )

            KtNodeTypes.DOT_QUALIFIED_EXPRESSION, KtNodeTypes.SAFE_ACCESS_EXPRESSION -> {
                val recepteur = recepteurQualifie(expression) ?: return null
                val selecteur = selecteurQualifie(expression) ?: return null
                val typeRecepteur =
                    typeExpression(recepteur, racine, contexteNom, resolution, declarationsProjet, profondeur + 1)
                        ?: typeStatiqueDe(recepteur, contexteNom, resolution)
                        ?: return null
                typeMembreSur(typeRecepteur, selecteur, resolution, declarationsProjet, contexteNom)
            }

            else -> null
        }
    }

    /** Littéral entier : Long si suffixe L, Int sinon. */
    private fun typeEntier(expression: NoeudSyntaxique): String =
        if (expression.jetonsDescendants().any { jeton ->
                jeton.texte.endsWith("L", ignoreCase = true) && jeton.texte.length > 1
            }
        ) {
            "kotlin.Long"
        } else {
            "kotlin.Int"
        }

    /**
     * FQCN des SUPERTYPES ÉCRITS de la classe/objet ENGLOBANT(E) le
     * décalage, résolus par les imports du document (ADR 0219) — la
     * classe PROJET elle-même est INVISIBLE de la vue classpath : sa
     * chaîne binaire ne peut être atteinte que par ses CLAUSES
     * ÉCRITES, miroir exact de la voie 2-ter du diagnostic
     * (`estMembreHerite` — même source de vérité, l'arbre VIF).
     */
    internal fun fqcnsSupertypesEnglobants(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): List<String> {
        val englobante =
            chaineAncetresA(racine, decalage).lastOrNull {
                it.type == KtNodeTypes.CLASS || it.type == KtNodeTypes.OBJECT_DECLARATION
            } ?: return emptyList()
        val contexteFichier = OccurrencesKotlin.ContexteFichier(contexteNom, declarationsProjet)
        return TypesKotlin.superTypesDe(englobante).mapNotNull { clause ->
            SuperKotlin.fqcnSupertype(clause, contexteFichier, resolution)
        }
    }

    /**
     * Classe ENGLOBANTE d'une position — FQCN du chemin de classes
     * imbriquées (paquet compris) ; null hors classe. Internal (ADR
     * 0219) : le survol du membre IMPLICITE this (InterrogationsSymboles)
     * partage la MÊME notion de classe porteuse.
     */
    internal fun classeEnglobante(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
    ): String? {
        val chaine = chaineAncetresA(racine, decalage)
        val noms = mutableListOf<String>()
        for (noeud in chaine) {
            if (noeud.type == KtNodeTypes.CLASS || noeud.type == KtNodeTypes.OBJECT_DECLARATION) {
                val nom =
                    noeud.jetonsDescendants()
                        .firstOrNull { it.type == org.jetbrains.kotlin.kmp.lexer.KtTokens.IDENTIFIER }
                        ?.texte
                        ?.toString() ?: return null
                noms.add(nom)
            }
        }
        if (noms.isEmpty()) return null
        val chemin = noms.joinToString(".")
        return contexteNom.paquet?.let { "$it.$chemin" } ?: chemin
    }

    /**
     * Type d'une RÉFÉRENCE par nom : locale/paramètre (type écrit puis
     * INITIALISEUR), membre englobant (idem), propriété de premier
     * niveau du fichier (initialisateur compris) puis du projet (type
     * écrit seul), classe (l'identifiant EST un type — companion
     * exclu, D3). K4 : SMART CAST positionnel d'abord, paramètre de
     * lambda attendu ensuite (inférence bornée du consommateur).
 *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * le continue du nom non porté est le patron même du parcours des
     * portées (précédent VueIndex.typesDansPaquet).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun typeReference(
        nom: String,
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        profondeur: Int,
    ): String? {
        // 0. SMART CAST positionnel (K4) — le cast raffine TOUTE portée
        // sauf une locale déclarée APRÈS la condition (ombrage).
        val cast = SmartCasts.castPositionnel(racine, decalage, nom, contexteNom, resolution)
        if (cast != null) {
            val ombre =
                SymbolesProjet.localesEnPortee(racine, decalage)
                    .firstOrNull { it.declaration.nom == nom }
            if (ombre == null || ombre.declaration.debutNom < cast.decalageCondition) {
                return cast.fqcn
            }
        }
        // 0 bis. Paramètre de LAMBDA (K4 — inférence bornée : attente du
        // consommateur projet, `it` implicite compris).
        ParametresLambda.typeParametre(racine, decalage, nom, contexteNom, resolution, declarationsProjet)?.let {
            return it
        }
        // 1. Locales/paramètres (la plus PROCHE gagne — ordre de
        // localesEnPortee) : type écrit, sinon initialiseur.
        for (locale in SymbolesProjet.localesEnPortee(racine, decalage)) {
            if (locale.declaration.nom != nom) continue
            locale.declaration.typeRetour?.let { ecrit ->
                resolution.fqcnDuNomEcrit(ecrit, contexteNom)?.let { return it }
            }
            locale.propriete?.initialisateur()?.let { initialiseur ->
                typeExpression(initialiseur.noeud, racine, contexteNom, resolution, declarationsProjet, profondeur + 1)
                    ?.let { return it }
            }
        }
        // 2. Membres des classes englobantes (this implicite).
        for (membre in SymbolesProjet.proprietesEnglobantes(racine, decalage)) {
            val nomMembre = membre.nomJeton()?.texte?.toString() ?: continue
            if (nomMembre != nom) continue
            membre.type()?.let { ecrit ->
                resolution.fqcnDuNomEcrit(ecrit, contexteNom)?.let { return it }
            }
            membre.initialisateur()?.let { initialiseur ->
                typeExpression(initialiseur.noeud, racine, contexteNom, resolution, declarationsProjet, profondeur + 1)
                    ?.let { return it }
            }
        }
        // 2 bis. K5 : membre du RÉCEPTEUR IMPLICITE d'un `with`
        // englobant (nom NU — `with(vue) { texte… }` : type du champ
        // `texte` de `vue`).
        val recepteurWith =
            RecepteursWith.recepteurImplicite(racine, decalage, contexteNom, resolution, declarationsProjet)
        if (recepteurWith != null) {
            typeMembreNomme(recepteurWith, nom, resolution, declarationsProjet, contexteNom)?.let { return it }
        }
        // 2-ter. ADR 0219 : membre IMPLICITE this sur la chaîne de la
        // classe ENGLOBANTE — le nom NU `layoutInflater` dans une
        // Activity (getter synthétique de Context/Activity) doit TYPER
        // (LayoutInflater), sinon la chaîne `layoutInflater.inflate(…)`
        // reste muette en complétion/survol.
        typeMembreImplicite(nom, racine, decalage, contexteNom, resolution, declarationsProjet)?.let { return it }
        // 3. Propriétés de premier niveau du FICHIER courant (l'arbre
        // est là : type écrit, sinon initialiseur).
        for (declaration in jo.lspkotlin.parse.kt.KtFile(racine).declarations()) {
            if (declaration !is KtProperty) continue
            if (declaration.nomJeton()?.texte?.toString() != nom) continue
            declaration.type()?.let { ecrit ->
                resolution.fqcnDuNomEcrit(ecrit, contexteNom)?.let { return it }
            }
            declaration.initialisateur()?.let { initialiseur ->
                typeExpression(initialiseur.noeud, racine, contexteNom, resolution, declarationsProjet, profondeur + 1)
                    ?.let { return it }
            }
        }
        // 4. Propriétés du PROJET hors fichier courant : type ÉCRIT
        // seul (le scan n'a pas d'arbre — sous-rapport D3).
        for (declaration in declarationsProjet) {
            if (declaration.nom != nom) continue
            if (declaration.genre != GenreDeclaration.PROPRIETE) continue
            declaration.typeRetour?.let { ecrit ->
                resolution.fqcnDuNomEcrit(ecrit, contexteNom)?.let { return it }
            }
        }
        // 5. Classe : la référence est peut-être le COMPANION d'une
        // classe — non résolu à K3 (sous-rapport).
        return null
    }

    /**
     * Type d'un APPEL par nom : CONSTRUCTEUR (le nom est une classe
     * importable/du projet — vérifiée contre la vue quand elle y est),
     * sinon retour ÉCRIT d'une fonction du fichier/projet, sinon
     * appelable de premier niveau de l'index (retour court résolu).
     */
    private fun typeAppel(
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? {
        // 1. Constructeur : le nom résout en une classe.
        val fqcnClasse = resolution.fqcnDuNomEcrit(nom, contexteNom) ?: run {
            // Classe du projet non importée portant EXACTEMENT ce nom ?
            val porteurs = declarationsProjet.filter { it.nom == nom && it.genre == GenreDeclaration.CLASSE }
            if (porteurs.size == 1) {
                val declaration = porteurs.single()
                (declaration.paquet?.let { "$it." } ?: "") + declaration.nom
            } else {
                null
            }
        }
        if (fqcnClasse != null && estConstructeurProbable(fqcnClasse, resolution, declarationsProjet)) {
            return fqcnClasse
        }
        // 2. Retour écrit d'une fonction locale/membre/projet.
        for (declaration in declarationsProjet) {
            if (declaration.nom != nom || declaration.genre != GenreDeclaration.FONCTION) continue
            declaration.typeRetour?.let { ecrit ->
                resolution.fqcnDuNomEcrit(ecrit, contexteNom)?.let { return it }
            }
        }
        // 3. Non démontrable (fonction du classpath au retour inféré,
        // companion, générateur…) — sous-rapport D3.
        return null
    }

    /**
     * Le FQCN est-il une classe CONSTRUISIBLE — vue consultée (membres
     * présents) ou classe du projet (constructeur par défaut admis) ?
     */
    private fun estConstructeurProbable(
        fqcn: String,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): Boolean {
        resolution.membresDe(fqcn)?.let { return true }
        return declarationsProjet.any { declaration ->
            declaration.genre == GenreDeclaration.CLASSE &&
                ((declaration.paquet?.let { "$it." } ?: "") + declaration.nom) == fqcn
        }
    }

    /**
     * Type STATIQUE d'un récepteur qui ne type pas en VALEUR (K5) — la
     * référence est peut-être une CLASSE (chaîne `R.layout.…`, membre
     * statique de compagnon) : le nom écrit résolu en FQCN.
     */
    private fun typeStatiqueDe(
        recepteur: NoeudSyntaxique,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): String? {
        if (recepteur.type != KtNodeTypes.REFERENCE_EXPRESSION) return null
        val nom = nomReference(recepteur) ?: return null
        return resolution.fqcnDuNomEcrit(nom, contexteNom)
    }

    /**
     * Type du SÉLECTEUR sur un récepteur typé : champ (descripteur =
     * type du champ) ou méthode (descripteur de retour), premier
     * porteur de la chaîne de supertypes — surcharges par nom seule
     * (K3), arité K4 ; K5 : membre du PROJET aussi (conteneur = FQCN
     * — types projet ET synthétiques).
     */
    private fun typeMembreSur(
        typeRecepteur: String,
        selecteur: NoeudSyntaxique,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        contexteNom: ResolutionKotlin.ContexteNom,
    ): String? {
        val nomMembre =
            when (selecteur.type) {
                KtNodeTypes.CALL_EXPRESSION -> nomAppele(selecteur) ?: return null
                else -> nomReference(selecteur) ?: return null
            }
        return typeMembreNomme(typeRecepteur, nomMembre, resolution, declarationsProjet, contexteNom)
    }

    /**
     * Type d'un membre NOMMÉ sur le this IMPLICITE — la classe
     * ENGLOBANTE porte le nom (ADR 0219) : champs, méthodes et GETTERS
     * SYNTHÉTIQUES de sa chaîne de supertypes (`layoutInflater` →
     * LayoutInflater, `getString(…)` → String). La classe PROJET est
     * invisible de la vue : ses SUPERTYPES ÉCRITS résolus portent la
     * chaîne binaire (miroir de estMembreHerite).
     */
    private fun typeMembreImplicite(
        nom: String,
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
    ): String? {
        val classe = classeEnglobante(racine, decalage, contexteNom) ?: return null
        // La classe elle-même (synthétiques K5 — vue) puis ses
        // SUPERTYPES ÉCRITS, chacun avec TOUTE sa chaîne binaire.
        val porteurs =
            listOf(classe) +
                fqcnsSupertypesEnglobants(racine, decalage, contexteNom, resolution, declarationsProjet)
        for (porteur in porteurs) {
            typeMembreNomme(porteur, nom, resolution, declarationsProjet, contexteNom)?.let { return it }
        }
        return null
    }

    /**
     * Type d'un membre NOMMÉ sur un récepteur typé : classpath d'abord
     * (champ/méthode de la chaîne de supertypes), déclaration du projet
     * ensuite (conteneur = FQCN — récepteur de type projet).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue du conteneur et du nom non portés sont le patron
     * même du filtrage par conteneur (précédent propositionsMembre).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun typeMembreNomme(
        typeRecepteur: String,
        nomMembre: String,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        contexteNom: ResolutionKotlin.ContexteNom,
    ): String? {
        // Champ : le descripteur D'UN champ est son type.
        val champ = resolution.champsNommes(typeRecepteur, nomMembre).firstOrNull()
        if (champ != null) {
            return traduireTypeChamp(champ.descripteur, resolution)
        }
        // Méthode : descripteur de retour de la première homonyme.
        val methode = resolution.methodesNommees(typeRecepteur, nomMembre).firstOrNull()
        if (methode != null) {
            return resolution.traduireDescripteur(methode.methode.descripteur).retour
        }
        // Propriété SYNTHÉTIQUE Java (ADR 0219) : le GETTER porte le
        // type — `layoutInflater` → LayoutInflater, `binding.root`-style.
        val getter = resolution.getterSynthetique(typeRecepteur, nomMembre)
        if (getter != null) {
            return resolution.traduireDescripteur(getter.methode.descripteur).retour
        }
        // Membre du PROJET (K5) : conteneur = FQCN du récepteur.
        for (declaration in declarationsProjet) {
            if (declaration.conteneur != typeRecepteur) continue
            if (declaration.nom != nomMembre) continue
            declaration.typeRetour?.let { ecrit -> return traduireTypeEcrit(ecrit, contexteNom, resolution) }
        }
        return null
    }

    /**
     * Type ÉCRIT traduit en FQCN — nom simple résolu par le contexte
     * (`Int` → `kotlin.Int`), FQCN/texte repris tel quel (les types
     * synthétiques écrivent déjà leur FQCN).
     */
    private fun traduireTypeEcrit(
        ecrit: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
    ): String? {
        val net = ecrit.substringBefore('<').trim().trimEnd('?')
        if (net.isEmpty()) return null
        return resolution.fqcnDuNomEcrit(net, contexteNom) ?: net
    }

    /** Descripteur de champ (`Ljava/lang/String;`) → FQCN. */
    private fun traduireTypeChamp(
        descripteur: String,
        resolution: ResolutionKotlin,
    ): String? {
        if (descripteur == "V") return "void"
        val traduit = resolution.traduireDescripteur("()$descripteur")
        return if (traduit.retour == "void") null else traduit.retour
    }
}
