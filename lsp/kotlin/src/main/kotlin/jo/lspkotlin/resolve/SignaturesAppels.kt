package jo.lspkotlin.resolve

import jo.lspkotlin.api.AideSignatureKotlin
import jo.lspkotlin.api.SignatureKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import jo.lspkotlin.index.AppelableKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.recepteurQualifie
import jo.lspkotlin.parse.kt.selecteurQualifie
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Aide à la signature (`textDocument/signatureHelp`, S1 — plan de
 * stabilisation LSP §3.2) — les candidates de l'appel ENGLOBANT le
 * curseur, avec le paramètre sous le curseur et la première candidate
 * d'arité compatible préselectionnés.
 *
 * Sources de candidates, par précédence (miroir PertinencesKotlin —
 * la portée la plus proche gagne) :
 *  1. appel SIMPLE (callee référence) : membre VIF de la classe
 *     englobante (paramètres réels de l'arbre), fonctions de premier
 *     niveau du projet (arité du contrôle `kt.argumentCount`),
 *     constructeurs primaires des classes du projet (hors Java,
 *     même garde D3 que l'arité), appelables de premier niveau du
 *     classpath (index Kotlin — fonctions seules, extensions exclues :
 *     un appel simple n'a pas de récepteur) ;
 *  2. appel QUALIFIÉ (`a.b(…)`, `a?.b(…)`), miroir du survol
 *     qualifié : récepteur typé ([TypageExpression]) → membres du
 *     récepteur projet (conteneur homonyme), méthodes du récepteur
 *     classpath (vue JVM, surcharges comprises), extensions de
 *     l'index sur le récepteur (supertypes et variantes compris).
 *
 * BORNES (D3) : ce que la résolution ne prouve pas n'apparaît pas —
 * constructeurs du classpath non indexés, paramètres par défaut
 * comptés, vararg ouvre l'arité, Java sans arité extraite est écarté.
 * La documentation des candidates reste `null` à S1 (re-parser des
 * fichiers tiers pour leur KDoc n'est pas un coût assumé).
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount/CyclomaticComplex
 * Method — chaque garde (pas d'appel, callee sans nom, récepteur non
 * typé, aucune candidate) est une ISSUE distincte du conservatisme D3 ;
 * LongParameterList — le CONTEXTE de résolution est filé tel quel,
 * patron [InterrogationsSymboles.Contexte] ; TooManyFunctions — une
 * source de candidates = une méthode (catalogue, pas une dérive).
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList", "TooManyFunctions")
internal object SignaturesAppels {
    /** Plafond de candidates rendues (un popup de 30 surcharges = bruit). */
    private const val PLAFOND_SIGNATURES = 12

    /**
     * Contexte d'interrogation — miroir de celui du survol
     * ([InterrogationsSymboles.Contexte]) : nommage, résolveur,
     * déclarations projet, index d'appelables, vue classpath.
     */
    internal data class Contexte(
        val contexteNom: ResolutionKotlin.ContexteNom,
        val resolution: ResolutionKotlin,
        val declarationsProjet: List<DeclarationProjet>,
        val index: IndexAppelablesKotlin,
        val vue: VueClasspathKotlin?,
    )

    /** Une candidate rendue avec son arité (pour la préselection). */
    private data class Candidate(
        val signature: SignatureKotlin,
        val total: Int,
        val minimum: Int,
        val aVararg: Boolean,
        /** Noms BRUTS des paramètres ("" = inconnu — indice absent). */
        val noms: List<String> = emptyList(),
    ) {
        /** L'arité [nombre] est-elle légale pour cette candidate ? */
        fun couvre(nombre: Int): Boolean =
            aVararg || (minimum <= nombre && nombre <= total)
    }

    /**
     * Aide à la signature à [decalage] : l'appel le plus PROFOND dont
     * la plage couvre le curseur (un argument `foo(bar(|))` interroge
     * `bar`), ses candidates et la position active. `null` hors appel
     * ou sans candidate prouvée.
     */
    internal fun aide(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexte: Contexte,
    ): AideSignatureKotlin? {
        val chaine = chaineAncetresA(racine, decalage)
        val appel =
            chaine.lastOrNull {
                it.type == KtNodeTypes.CALL_EXPRESSION && it.debut <= decalage && decalage < it.fin
            } ?: return null
        val nom = nomAppele(appel) ?: return null
        val arguments = argumentsOrdinaires(appel)
        val nombreArguments = arguments.size + if (aLambdaFinal(appel)) 1 else 0
        val candidates =
            if (estQualifie(chaine, appel)) {
                candidatesQualifie(chaine, appel, nom, racine, contexte)
            } else {
                candidatesSimples(appel, nom, racine, contexte)
            }
        if (candidates.isEmpty()) return null
        val signatureActive =
            candidates.indexOfFirst { it.couvre(nombreArguments) }.takeIf { it >= 0 } ?: 0
        return AideSignatureKotlin(
            signatures = candidates.take(PLAFOND_SIGNATURES).map { it.signature },
            signatureActive = signatureActive,
            parametreActif = parametreSousCurseur(arguments, decalage),
        )
    }

    // ── Candidates : appel simple ──────────────────────────────────────

    /** Candidates d'un appel SIMPLE (callee référence directe). */
    private fun candidatesSimples(
        appel: NoeudSyntaxique,
        nom: String,
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<Candidate> {
        // 1. Membre VIF de la classe englobante (this implicite) —
        //    paramètres RÉELS de l'arbre (noms + types écrits).
        val membres =
            SymbolesProjet.membresEnglobants(racine, appel.debut)
                .filterIsInstance<KtFunction>()
                .filter { membre -> membre.nom() == nom }
                .map { membre -> candidateMembreVif(membre) }
        // 2. Fonctions de PREMIER NIVEAU du projet (membres et
        //    extensions exclus : l'un vit ci-dessus, l'autre exige un
        //    récepteur qu'un appel simple ne porte pas).
        val fonctions =
            contexte.declarationsProjet
                .filter { declaration ->
                    declaration.genre == GenreDeclaration.FONCTION &&
                        declaration.nom == nom &&
                        declaration.conteneur == null &&
                        declaration.recepteur == null
                }.map { declaration -> candidateFonction(declaration) }
        // 3. Constructeurs primaires des classes du projet (hors Java :
        //    arité non extraite, garde D3 du contrôle kt.argumentCount).
        val constructeurs =
            contexte.declarationsProjet
                .filter { declaration ->
                    declaration.genre == GenreDeclaration.CLASSE &&
                        declaration.nom == nom &&
                        !declaration.estJava
                }.map { declaration -> candidateConstructeur(declaration) }
        val candidates = membres + fonctions + constructeurs
        // 4. Appelables de premier niveau du classpath (index Kotlin) —
        //    fonctions seules, extensions exclues (pas de récepteur),
        //    quand AUCUNE candidate projet n'existe (précédence projet).
        if (candidates.isNotEmpty()) return dedupliquer(candidates)
        val index =
            contexte.index.topParNom(nom)
                .filter { appelable ->
                    appelable.genre == jo.lspkotlin.index.GenreAppelable.FONCTION &&
                        appelable.recepteur == null
                }.map { appelable -> candidateAppelable(appelable) }
        return dedupliquer(candidates + index)
    }

    // ── Candidates : appel qualifié ────────────────────────────────────

    /** Candidates d'un appel QUALIFIÉ (récepteur typé, miroir survol). */
    private fun candidatesQualifie(
        chaine: List<NoeudSyntaxique>,
        appel: NoeudSyntaxique,
        nom: String,
        racine: NoeudSyntaxique,
        contexte: Contexte,
    ): List<Candidate> {
        // Le récepteur vit sur l'expression qualifiée PARENTE de l'appel
        // (`v.setText()` → DOT_QUALIFIED(v, CALL) — le callee de l'appel
        // est une référence SÈCHE, le récepteur est un niveau au-dessus).
        val parent = chaine.getOrNull(chaine.indexOf(appel) - 1) ?: return emptyList()
        if (parent.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
            parent.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
        ) {
            return emptyList()
        }
        val recepteur = recepteurQualifie(parent) ?: return emptyList()
        val typeRecepteur =
            TypageExpression.typeExpression(
                recepteur,
                racine,
                contexte.contexteNom,
                contexte.resolution,
                contexte.declarationsProjet,
            )
                // ADR 0222 : récepteur CLASSE (statique — `Boite.creer()`,
                // `Int.MAX_VALUE`) : les méthodes du COMPANION du
                // classpath et du projet (double émission) sont des
                // candidates de l'appel par le nom de la classe.
                ?: TypageExpression.typeStatiqueDe(recepteur, contexte.contexteNom, contexte.resolution)
                ?: return emptyList()
        val shortRecepteur = typeRecepteur.substringAfterLast('.')
        // 1. Membres du récepteur PROJET : conteneur homonyme du type
        //    résolu (le qualifié court correspond), extensions projet
        //    dont le récepteur ÉCRIT correspond.
        val membresProjet =
            contexte.declarationsProjet
                .filter { declaration ->
                    declaration.genre == GenreDeclaration.FONCTION &&
                        declaration.nom == nom &&
                        (declaration.conteneur?.substringAfterLast('.') == shortRecepteur ||
                            declaration.recepteur?.substringAfterLast('.') == shortRecepteur)
                }.map { declaration -> candidateFonction(declaration) }
        // 2. Méthodes du récepteur CLASSPATH (vue JVM — TOUTES les
        //    surcharges du premier porteur de la chaîne de supertypes).
        val methodesJvm =
            contexte.resolution.methodesNommees(typeRecepteur, nom)
                .map { methode -> candidateMethodeJvm(nom, methode, contexte) }
        // 2 bis. ADR 0222 : méthodes du COMPANION du classpath — le nom
        //    de la classe prête les membres d'instance du compagnon.
        val methodesCompagnon =
            contexte.resolution.methodesNommees(typeRecepteur + "\$Companion", nom)
                .map { methode -> candidateMethodeJvm(nom, methode, contexte) }
        // 3. Extensions de l'index sur ce récepteur (supertypes et
        //    variantes de nommage compris, miroir du survol qualifié).
        val extensions = extensionsSur(typeRecepteur, nom, contexte).map { candidateAppelable(it) }
        return dedupliquer(membresProjet + methodesJvm + methodesCompagnon + extensions)
    }

    /** Extensions de l'index portant [nom] SUR le récepteur résolu. */
    private fun extensionsSur(
        typeRecepteur: String,
        nom: String,
        contexte: Contexte,
    ): List<AppelableKotlin> =
        contexte.resolution.chaineSupertypes(typeRecepteur)
            .asSequence()
            .flatMap { courant -> contexte.resolution.variantesRecepteur(courant) }
            .flatMap { variante -> contexte.index.extensionsSur(variante) }
            .filter { extension -> extension.nom == nom }
            .toList()

    // ── Rendu des candidates ───────────────────────────────────────────

    /** Candidate d'une déclaration FONCTION du projet (params (nom, type)). */
    private fun candidateFonction(declaration: DeclarationProjet): Candidate {
        val parametres = declaration.parametres.map { (nom, type) -> libelleParametre(nom, type) }
        return Candidate(
            signature =
                SignatureKotlin(etiquette(declaration.nom, parametres, declaration.typeRetour), null, parametres),
            total = declaration.parametres.size,
            minimum = declaration.parametres.size - declaration.nombreParametresDefaut,
            aVararg = declaration.aVararg,
            noms = declaration.parametres.map { (nom, _) -> nom.orEmpty() },
        )
    }

    /** Candidate d'un CONSTRUCTEUR primaire de classe projet. */
    private fun candidateConstructeur(declaration: DeclarationProjet): Candidate {
        val parametres = declaration.parametres.map { (nom, type) -> libelleParametre(nom, type) }
        return Candidate(
            signature = SignatureKotlin(etiquette(declaration.nom, parametres, null), null, parametres),
            total = declaration.parametres.size,
            minimum = declaration.parametres.size - declaration.nombreParametresDefaut,
            aVararg = declaration.aVararg,
            noms = declaration.parametres.map { (nom, _) -> nom.orEmpty() },
        )
    }

    /** Candidate d'un membre VIF de la classe englobante (arbre réel). */
    private fun candidateMembreVif(fonction: KtFunction): Candidate {
        val parametres =
            fonction.parametres().map { parametre -> libelleParametre(parametre.nom(), parametre.type()) }
        return Candidate(
            signature =
                SignatureKotlin(etiquette(fonction.nom() ?: "?", parametres, fonction.typeRetour()), null, parametres),
            total = parametres.size,
            minimum = parametres.size,
            aVararg = false,
            noms = fonction.parametres().map { parametre -> parametre.nom().orEmpty() },
        )
    }

    /** Candidate d'un appelable de premier niveau (index Kotlin). */
    private fun candidateAppelable(appelable: AppelableKotlin): Candidate {
        val parametres = appelable.parametres.map { libelleParametre(it.nom, it.type) }
        return Candidate(
            signature = SignatureKotlin(etiquette(appelable.nom, parametres, appelable.typeRetour), null, parametres),
            total = parametres.size,
            minimum = parametres.size,
            aVararg = false,
            noms = appelable.parametres.map { parametre -> parametre.nom.orEmpty() },
        )
    }

    /** Candidate d'une méthode JVM du récepteur (descripteur traduit). */
    private fun candidateMethodeJvm(
        nom: String,
        methode: ResolutionKotlin.MemeJvmNomme,
        contexte: Contexte,
    ): Candidate {
        val traduit = contexte.resolution.traduireDescripteur(methode.methode.descripteur)
        val parametres = traduit.parametres.map { type -> contexte.resolution.renduCourt(type) }
        val retour = contexte.resolution.renduCourt(traduit.retour)
        return Candidate(
            signature = SignatureKotlin(etiquette(nom, parametres, retour), null, parametres),
            total = parametres.size,
            minimum = parametres.size,
            aVararg = false,
        )
    }

    /** Étiquette d'appel prête à afficher (`nom(p: T): R`). */
    private fun etiquette(
        nom: String,
        parametres: List<String>,
        retour: String?,
    ): String = "$nom(${parametres.joinToString(", ")})${retour?.let { ": $it" } ?: ""}"

    /** Libellé d'un paramètre : `nom: type`, le type seul, ou le nom seul. */
    private fun libelleParametre(nom: String?, type: String?): String =
        when {
            nom != null && type != null -> "$nom: $type"
            type != null -> type
            nom != null -> nom
            else -> "?"
        }

    /** Déduplique par étiquette (premier porteur gagne — précédence). */
    private fun dedupliquer(candidates: List<Candidate>): List<Candidate> {
        val vues = mutableSetOf<String>()
        return candidates.filter { vues.add(it.signature.etiquette) }
    }

    /**
     * Noms de paramètres de l'appel [appel] PROUVÉS par la résolution
     * (L2 — indices en ligne) : ceux de la première candidate d'arité
     * compatible — le même conservatisme que l'aide à la signature,
     * sans le popup (le consommateur est le moteur des indices).
     * `null` sans candidate prouvée (aucun indice, jamais deviné).
     */
    internal fun nomsParametres(
        racine: NoeudSyntaxique,
        appel: NoeudSyntaxique,
        contexte: Contexte,
    ): List<String>? {
        val nom = nomAppele(appel) ?: return null
        val arguments = argumentsOrdinaires(appel)
        val nombreArguments = arguments.size + if (aLambdaFinal(appel)) 1 else 0
        val chaine = chaineAncetresA(racine, appel.debut)
        val candidates =
            if (estQualifie(chaine, appel)) {
                candidatesQualifie(chaine, appel, nom, racine, contexte)
            } else {
                candidatesSimples(appel, nom, racine, contexte)
            }
        return candidates
            .firstOrNull { candidate -> candidate.couvre(nombreArguments) }
            ?.noms
            ?.takeIf { noms -> noms.any { nomNu -> nomNu.isNotEmpty() } }
    }

    // ── Position active ────────────────────────────────────────────────

    /** Arguments ORDINAIRES de l'appel (le lambda final est à part). */
    internal fun argumentsOrdinaires(appel: NoeudSyntaxique): List<NoeudSyntaxique> =
        appel.sousNoeuds
            .firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST }
            ?.sousNoeuds
            ?.filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
            ?: emptyList()

    /** L'appel porte-t-il un lambda final (`foo(1) { … }`) ? */
    internal fun aLambdaFinal(appel: NoeudSyntaxique): Boolean =
        appel.sousNoeuds.any { it.type == KtNodeTypes.LAMBDA_ARGUMENT }

    /**
     * L'appel est-il le SÉLECTEUR d'une expression QUALIFIÉE
     * (`a.b(…)`, `a?.b(…)`) ? Le PARENT dans la chaîne porte la
     * réponse (l'arbre niche l'appel SOUS le qualifié — son callee
     * reste une référence sèche, cf. sondes ProbeCtor).
     */
    private fun estQualifie(
        chaine: List<NoeudSyntaxique>,
        appel: NoeudSyntaxique,
    ): Boolean {
        val parent = chaine.getOrNull(chaine.indexOf(appel) - 1) ?: return false
        return (parent.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            parent.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) &&
            selecteurQualifie(parent) == appel
    }

    /**
     * Indice du paramètre SOUS LE CURSEUR : l'argument CONTENANT le
     * curseur gagne (bornes comprises — un curseur en fin d'argument
     * le touche encore) ; entre deux arguments (virgule, parenthèse,
     * lambda final), le nombre d'arguments ENTIÈREMENT avant le
     * curseur — le lambda final compte ainsi pour l'argument suivant.
     */
    private fun parametreSousCurseur(
        arguments: List<NoeudSyntaxique>,
        decalage: Int,
    ): Int {
        val dansArgument = arguments.indexOfFirst { it.debut <= decalage && decalage <= it.fin }
        if (dansArgument >= 0) return dansArgument
        return arguments.count { it.fin <= decalage }
    }
}
