package jo.lspkotlin.completion

import jo.lspkotlin.api.DRAPEAU_ENUM_KOTLIN
import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.DRAPEAU_STATIQUE_KOTLIN
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.PertinencesKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtAnnotationEntry
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.estExpression
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.SignaturesAppels
import jo.lspkotlin.resolve.TypageExpression
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Contextes DÉDIÉS de la complétion Kotlin (vague 1 du chantier
 * « complétion contextuelle » — rapport d'analyse du 28/10/2026) : le
 * portage des trois contextes que le moteur Java sert depuis la 1.16.0,
 * adaptés à la syntaxe Kotlin.
 *
 *  - **ARGUMENTS NOMMÉS** d'un appel (`foo(na|` → `nom = `) : les noms
 *    de paramètres des surcharges PROUVÉES de l'appel englobant
 *    ([SignaturesAppels] — même graphe que l'aide à la signature),
 *    exclus des arguments déjà fournis en forme nommée. FUSIONNÉ au
 *    régime de nom (un argument accepte toute expression — la locale
 *    `nom` reste offerte à côté de `nom =`).
 *  - **CONSTANTES D'ENUM** des branches d'un `when` à sujet enum
 *    (`when (c) { RO| ->` → `ROUGE`, `VERT`…) : les entrées de
 *    l'énumération du sujet, PROJET (déclarations) ou CLASSPATH
 *    (champs statiques typés par l'enum elle-même, miroir du moteur
 *    Java). Contexte EXCLUSIF — une condition de branche n'écrit pas
 *    d'autre nom que ses constantes.
 *  - **ATTRIBUTS D'ANNOTATION** (`@Anno(lev|` → `level = `) : les
 *    méthodes publiques du type d'annotation non déjà fournies,
 *    insérées avec «&#160;= » (le séparateur voyage avec le candidat —
 *    [PropositionCompletionKotlin.texteInsertion], miroir du moteur
 *    Java). Contexte EXCLUSIF — miroir du `@Anno(` ecj.
 *
 * Conservateur (D3) : un contexte non prouvé (annotation non résolue,
 * sujet de `when` non typé, appel sans candidate nommée) rend VIDE ou
 * rend la main au régime de nom — jamais un candidat inventé. Les
 * méthodes JVM du classpath ne portent pas leurs noms de paramètres
 * (descripteurs) : elles ne contribuent AUCUN argument nommé.
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque garde
 * (hors liste d'arguments, hors position de nom, sujet non typé,
 * annotation non résolue, enum introuvable) est une ISSUE distincte du
 * conservatisme D3 ; CyclomaticComplexMethod — la sonde de position
 * (argument nu, argument nommé, entre arguments) est le catalogue des
 * formes de la grammaire ; TooManyFunctions — une sonde par contexte
 * (catalogue, pas une dérive) ; LongParameterList — le CONTEXTE de
 * complétion est filé tel quel (patron [SignaturesAppels.Contexte]) ;
 * LoopWithTooManyJumpStatements — chaque continue SAUTE une source de
 * candidats (le patron même de la fusion).
 */
@Suppress(
    "ReturnCount",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
)
internal object ContextesCompletionKotlin {
    /** Drapeau JVM `abstract` (membres des interfaces du classpath). */
    private const val DRAPEAU_ABSTRAIT: Int = 0x0400

    /** Drapeau JVM `synthetic` (à écarter des attributs d'annotation). */
    private const val DRAPEAU_SYNTHETIQUE: Int = 0x1000

    /**
     * Contexte DÉDIÉ à [borne] : attributs d'annotation ou constantes
     * d'enum du `when` — les deux positions EXCLUSIVES (elles rendent
     * TOUT, ou rien d'autre). `null` quand aucune ne s'applique : le
     * régime de nom reprend ses droits.
     */
    internal fun contexteDedie(
        racine: NoeudSyntaxique,
        texte: CharSequence,
        debutPrefixe: Int,
        prefixe: String,
        borne: Int,
        contexte: CompletionKotlin.Contexte,
    ): List<PropositionCompletionKotlin>? =
        attributsAnnotation(racine, texte, debutPrefixe, prefixe, borne, contexte)
            ?: constantesEnumDuWhen(racine, prefixe, borne, contexte)

    /**
     * Arguments NOMMÉS de l'appel englobant [borne] — fusionnés au
     * régime de nom par l'appelant (VIDE hors appel, hors position de
     * nom, ou sans candidate dont les noms sont connus).
     */
    internal fun argumentsNomes(
        racine: NoeudSyntaxique,
        texte: CharSequence,
        debutPrefixe: Int,
        prefixe: String,
        borne: Int,
        contexte: CompletionKotlin.Contexte,
    ): List<PropositionCompletionKotlin> {
        // L'appel ENGLOBANT au sens de la COMPLÉTION : la frontière
        // droite FERMÉE compte (le curseur collé à la fin de
        // l'identifiant en cours de frappe — `creer(na|` — est LA
        // position de la complétion ; l'aide à la signature, elle,
        // exige le curseur strictement DANS l'appel).
        val appel =
            chaineAncetresA(racine, borne).lastOrNull {
                it.type == KtNodeTypes.CALL_EXPRESSION && it.debut <= borne && borne <= it.fin
            } ?: return emptyList()
        val liste = listeArgumentsDe(appel) ?: return emptyList()
        if (!curseurDansListe(liste, borne, texte)) return emptyList()
        if (!positionDeNom(liste, borne, texte, debutPrefixe)) return emptyList()
        val dejaNomes = nomsFournis(liste)
        val lots =
            SignaturesAppels.nomsParametresToutes(
                racine,
                appel,
                SignaturesAppels.Contexte(
                    contexteNom = contexte.contexteNom,
                    resolution = contexte.resolution,
                    declarationsProjet = contexte.declarationsProjet,
                    index = contexte.index,
                    vue = contexte.vue,
                ),
            )
        val propositions = mutableListOf<PropositionCompletionKotlin>()
        for ((etiquette, noms) in lots) {
            for (nom in noms) {
                if (nom in dejaNomes || !nom.startsWith(prefixe)) continue
                propositions += nomme(nom, etiquette)
            }
        }
        return propositions
    }

    /**
     * Attributs de l'ANNOTATION englobante : `@Anno(lev|` → les
     * attributs non fournis, insérés avec «&#160;= ». `null` hors
     * annotation/hors position de nom/annotation non résolue (le régime
     * de nom reprend) ; VIDE quand TOUT est fourni (le contexte gagne,
     * rien d'autre n'est offert — miroir du moteur Java).
     */
    private fun attributsAnnotation(
        racine: NoeudSyntaxique,
        texte: CharSequence,
        debutPrefixe: Int,
        prefixe: String,
        borne: Int,
        contexte: CompletionKotlin.Contexte,
    ): List<PropositionCompletionKotlin>? {
        val chaine = chaineAncetresA(racine, borne)
        val entree = chaine.lastOrNull { it.type == KtNodeTypes.ANNOTATION_ENTRY } ?: return null
        val liste = listeArgumentsDe(entree) ?: return null
        if (!curseurDansListe(liste, borne, texte)) return null
        if (!positionDeNom(liste, borne, texte, debutPrefixe)) return null
        val nomEcrit = KtAnnotationEntry(entree).nomEcrit() ?: return null
        val fqcn =
            if ('.' in nomEcrit) {
                nomEcrit
            } else {
                contexte.resolution.fqcnDuNomEcrit(nomEcrit, contexte.contexteNom) ?: return null
            }
        val membres = contexte.resolution.membresDe(fqcn) ?: return null
        val fournis = KtAnnotationEntry(entree).arguments().mapNotNull { it.nom }.toSet()
        return membres.methodes
            .asSequence()
            .filter { attribut -> (attribut.drapeaux and DRAPEAU_PUBLIC_KOTLIN) != 0 }
            .filter { attribut -> (attribut.drapeaux and DRAPEAU_STATIQUE_KOTLIN) == 0 }
            .filter { attribut -> (attribut.drapeaux and DRAPEAU_ABSTRAIT) != 0 }
            .filter { attribut -> (attribut.drapeaux and DRAPEAU_SYNTHETIQUE) == 0 }
            .filter { attribut -> nomPropre(attribut.nom) }
            .filter { attribut -> attribut.nom !in fournis }
            .filter { attribut -> attribut.nom.startsWith(prefixe) }
            .map { attribut ->
                PropositionCompletionKotlin(
                    libelle = "${attribut.nom} =",
                    genre = GenreCompletionKotlin.PROPRIETE,
                    detail = fqcn,
                    texteTri = CompletionKotlin.texteTri(PertinencesKotlin.MEMBRE, attribut.nom),
                    texteInsertion = "${attribut.nom} = ",
                )
            }
            .toList()
    }

    /**
     * Constantes de l'énumération sujette d'un `when` : les branches
     * écrivent les constantes NON QUALIFIÉES (`when (c) { ROUGE -> }`).
     * `null` hors `when`/hors condition de branche/sujet non typé ou
     * non enum (le régime de nom reprend).
     */
    private fun constantesEnumDuWhen(
        racine: NoeudSyntaxique,
        prefixe: String,
        borne: Int,
        contexte: CompletionKotlin.Contexte,
    ): List<PropositionCompletionKotlin>? {
        val chaine = chaineAncetresA(racine, borne)
        val indexWhen = chaine.indexOfLast { it.type == KtNodeTypes.WHEN }
        if (indexWhen < 0) return null
        val quand = chaine[indexWhen]
        val sous = chaine.subList(indexWhen + 1, chaine.size)
        // Position de CONDITION : dans un WHEN_CONDITION_EXPRESSION, ou
        // position VIDE entre les entrées (aucune WHEN_ENTRY sous le
        // caret — un corps de branche ou une garde n'écrit pas une
        // constante).
        val enCondition = sous.any { it.type == KtNodeTypes.WHEN_CONDITION_EXPRESSION }
        val positionVide = sous.none { it.type == KtNodeTypes.WHEN_ENTRY }
        if (!enCondition && !positionVide) return null
        // Le sujet : premier enfant non-entrée du `when` (une expression
        // — `when (val x = …)` déclare une propriété, non typée ici).
        val sujet = quand.sousNoeuds.firstOrNull { it.type != KtNodeTypes.WHEN_ENTRY } ?: return null
        if (!estExpression(sujet.type)) return null
        if (borne <= sujet.fin) return null // le curseur est DANS le sujet
        val fqcn =
            TypageExpression.typeExpression(
                sujet,
                racine,
                contexte.contexteNom,
                contexte.resolution,
                contexte.declarationsProjet,
            ) ?: return null
        val propositions = mutableListOf<PropositionCompletionKotlin>()
        // 1. Entrées de l'enum PROJET : le scan émet chaque entrée comme
        //    déclaration de genre OBJET sous le conteneur qualifié de
        //    l'enum (l'entrée n'a ni héritage écrit ni compagnon — le
        //    drapeau distingue d'un éventuel objet NIÉ au même conteneur,
        //    qui porte toujours un supertype écrit ou le drapeau « sans
        //    héritage » d'extraireObjet).
        for (declaration in contexte.declarationsProjet) {
            if (declaration.genre != GenreDeclaration.OBJET) continue
            if (declaration.conteneur != fqcn) continue
            if (declaration.estSansHeritageEcrit) continue
            if (!declaration.nom.startsWith(prefixe)) continue
            propositions += constante(declaration.nom, fqcn)
        }
        // 2. Constantes de l'enum CLASSPATH : champs statiques dont le
        //    type déclaré est l'enum elle-même (miroir du moteur Java).
        val membres = contexte.resolution.membresDe(fqcn)
        if (membres != null && (membres.drapeauxClasse and DRAPEAU_ENUM_KOTLIN) != 0) {
            val descripteurEnum = "L${fqcn.replace('.', '/')};"
            for (champ in membres.champs) {
                if ((champ.drapeaux and DRAPEAU_STATIQUE_KOTLIN) == 0) continue
                if (champ.descripteur != descripteurEnum) continue
                if (!nomPropre(champ.nom) || !champ.nom.startsWith(prefixe)) continue
                propositions += constante(champ.nom, fqcn)
            }
        }
        // Sujet NON enum (aucune constante ni du projet ni du classpath) :
        // le contexte ne s'applique pas — le régime de nom reprend ses
        // droits (les conditions `is`/`in` et les valeurs calculées y
        // vivent), jamais un contexte vide qui éteindrait tout.
        if (propositions.isEmpty()) return null
        return propositions
    }

    // ── Sondes partagées ───────────────────────────────────────────────

    /** Liste d'arguments (VALUE_ARGUMENT_LIST) d'un appel/annotation. */
    private fun listeArgumentsDe(noeud: NoeudSyntaxique): NoeudSyntaxique? =
        noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_ARGUMENT_LIST }

    /**
     * Le curseur est-il DANS la portée des arguments de [liste] :
     * strictement entre les parenthèses, ou à la frontière droite quand
     * la liste est NON REFERMÉE (frappe en cours — `foo(na|` n'a pas
     * encore son `)`). Une liste REFERMÉE (`foo(x)|`) garde le curseur
     * HORS contexte : le caractère qui précède est `)`.
     */
    private fun curseurDansListe(
        liste: NoeudSyntaxique,
        borne: Int,
        texte: CharSequence,
    ): Boolean {
        if (borne <= liste.debut) return false
        if (borne < liste.fin) return true
        if (liste.fin <= liste.debut + 1) return false
        return texte[liste.fin - 1] != ')'
    }

    /**
     * Le curseur est-il en POSITION DE NOM dans [liste] : dans un
     * argument NU (référence seule ou vide), ou ENTRE les arguments
     * (juste après «&#160;( » ou «&#160;, ») ? Un argument NOMMÉ
     * (`n = v`), un littéral ou une expression composée sont des
     * positions de VALEUR — le contexte ne s'applique pas.
     */
    private fun positionDeNom(
        liste: NoeudSyntaxique,
        borne: Int,
        texte: CharSequence,
        debutPrefixe: Int,
    ): Boolean {
        val arguments = liste.sousNoeuds.filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
        val contenant = arguments.firstOrNull { it.debut <= borne && borne <= it.fin }
        if (contenant != null) {
            if (contenant.fin <= contenant.debut) return true // argument VIDE (frappe entre virgules)
            if (contenant.sousNoeuds.any { it.type == KtNodeTypes.VALUE_ARGUMENT_NAME }) return false
            if (contenant.jetonsDirects.any { it.type == KtTokens.EQ }) return false
            // Le nœud de VALEUR : premier enfant non-marqué (un argument
            // vide porte un ERROR_ELEMENT de largeur nulle — la grammaire
            // tolérante marque ce qu'elle n'a pas reconnu).
            val valeur = contenant.sousNoeuds.firstOrNull { !it.marqueErreur } ?: return true
            return valeur.type == KtNodeTypes.REFERENCE_EXPRESSION && borne <= valeur.fin
        }
        var index = debutPrefixe - 1
        while (index > liste.debut && texte[index].isWhitespace()) index--
        return index >= liste.debut && (texte[index] == '(' || texte[index] == ',')
    }

    /** Noms déjà fournis en forme NOMMÉE dans la liste d'arguments. */
    private fun nomsFournis(liste: NoeudSyntaxique): Set<String> =
        liste.sousNoeuds
            .asSequence()
            .filter { it.type == KtNodeTypes.VALUE_ARGUMENT }
            .flatMap { argument ->
                argument.sousNoeuds
                    .asSequence()
                    .filter { it.type == KtNodeTypes.VALUE_ARGUMENT_NAME }
                    .mapNotNull { nom ->
                        nom.jetonsDescendants()
                            .firstOrNull { jeton -> jeton.type == KtTokens.IDENTIFIER }
                    }
            }
            .map { jeton -> jeton.texte.toString() }
            .toSet()

    /** Nom offrable : non vide, sans `$` ni crochet (membres synthétiques). */
    private fun nomPropre(nom: String): Boolean = nom.isNotEmpty() && '$' !in nom && !nom.startsWith("<")

    /** Candidat « argument nommé » : libellé `nom =`, insertion `nom = `. */
    private fun nomme(
        nom: String,
        etiquette: String,
    ): PropositionCompletionKotlin =
        PropositionCompletionKotlin(
            libelle = "$nom =",
            genre = GenreCompletionKotlin.VARIABLE,
            detail = etiquette,
            texteTri = CompletionKotlin.texteTri(PertinencesKotlin.MEMBRE, nom),
            texteInsertion = "$nom = ",
        )

    /** Candidat « constante d'enum » : nom nu, détail l'enum. */
    private fun constante(
        nom: String,
        fqcnEnum: String,
    ): PropositionCompletionKotlin =
        PropositionCompletionKotlin(
            libelle = nom,
            genre = GenreCompletionKotlin.ENUM,
            detail = fqcnEnum.substringAfterLast('.'),
            texteTri = CompletionKotlin.texteTri(PertinencesKotlin.MEMBRE, nom),
        )
}
