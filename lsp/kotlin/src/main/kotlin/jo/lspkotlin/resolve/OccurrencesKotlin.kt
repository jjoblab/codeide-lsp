package jo.lspkotlin.resolve

import jo.lspkotlin.api.GenreSurlignageKotlin
import jo.lspkotlin.api.PlageRenommableKotlin
import jo.lspkotlin.api.PlageSurligneeKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.recepteurQualifie
import jo.lspkotlin.parse.kt.selecteurQualifie
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Occurrences d'un symbole — S2 du plan de stabilisation LSP (§3.2,
 * audit Kotlin §8.3/§8.4) : le SUBSTRAT commun de `references`,
 * `documentHighlight` et du rename borné. Le moteur vendoré n'a PAS de
 * bindings (pas de compilation) : chaque occurrence est prouvée par des
 * gardes STRUCTURALES conservatrices (D3) — une référence non prouvée
 * n'est JAMAIS rendue.
 *
 * ## Cible
 * La cible se résout par la CHAÎNE des définitions (miroir
 * [InterrogationsSymboles.definitions]) : nom DÉCLARÉ sous le curseur →
 * locale en portée → déclarations du projet. Trois FAMILLES :
 *  1. **file-local** (locale/paramètre) — occurrences confinées au
 *     fichier déclarant, preuve par portée ([SymbolesProjet.localesEnPortee]
 *     au site de chaque occurrence) ;
 *  2. **type de premier niveau / niche** (CLASSE/INTERFACE/OBJET/ALIAS) —
 *     preuve par FQCN ([ResolutionKotlin.fqcnDuNomEcrit] lu avec le
 *     contexte d'imports du FICHIER de l'occurrence) ;
 *  3. **appelable** (fonction/propriété, top-level ou membre, extension
 *     comprise) — preuve par imports/paquet (top-level), par TYPAGE du
 *     récepteur ([TypageExpression]) pour les sélecteurs qualifiés et
 *     extensions, par CLASSE ENGLOBANTE pour les membres non qualifiés.
 *
 * ## Occurrence
 * Un jeton IDENTIFIER portant le nom cible, retenu SEULEMENT si sa
 * preuve passe : le jeton de la déclaration (ÉCRITURE), le segment de
 * nom d'un import du FQCN cible, ou une référence prouvée (LECTURE).
 * Les homonymes non prouvés restent HORS liste (le renommage Java les
 * écarte par binding ; ici la prudence joue le même rôle).
 *
 * Les gardes systématiques : jetons de DIRECTIVE PACKAGE ignorés
 * (un paquet homonyme d'un symbole ne se renomme pas), occurrences
 * ombragées par une locale de même nom écartées (la référence résout
 * vers la locale, pas la cible).
 */
@Suppress("TooManyFunctions")
internal object OccurrencesKotlin {
    /** Une occurrence collectée : plage + nature. */
    internal data class Occurrence(
        val debut: Int,
        val fin: Int,
        val estDeclaration: Boolean,
    ) {
        /** Genre LSP du surlignage (déclaration = écriture). */
        val genre: GenreSurlignageKotlin
            get() = if (estDeclaration) GenreSurlignageKotlin.ECRITURE else GenreSurlignageKotlin.LECTURE
    }

    /**
     * Cible résolue au curseur — la déclaration VISÉE, assez décrite
     * pour prouver (ou réfuter) chaque occurrence homonyme d'un
     * quelconque fichier du projet.
     */
    internal data class Cible(
        val nom: String,
        val uri: String,
        val debutNom: Int,
        val finNom: Int,
        val genre: GenreDeclaration,
        val paquet: String?,
        /** FQCN du conteneur (`a.b.Vue`) pour un membre, `null` sinon. */
        val conteneur: String?,
        /** Texte ÉCRIT du récepteur d'extension (`Vue`), `null` sinon. */
        val recepteur: String?,
        /** FQCN de la cible pour un TYPE (null pour les appelables). */
        val fqcn: String?,
        /** Locale/paramètre : occurrences confinées au fichier déclarant. */
        val fileLocal: Boolean,
    )

    /** Contexte d'un FICHIER balayé (occurrences hors document courant). */
    internal data class ContexteFichier(
        val contexteNom: ResolutionKotlin.ContexteNom,
        val declarations: List<DeclarationProjet>,
    )

    /**
     * Preuve prête pour UN fichier : contexte + résolveur + le FQCN que
     * ce fichier résout pour le nom cible (calculé UNE fois, pas par
     * jeton — et goulot d'étranglement de signature évité).
     */
    private data class PreuveFichier(
        val contexte: ContexteFichier,
        val resolution: ResolutionKotlin,
        val fqcnResolu: String?,
    )

    // ── Résolution de la cible ──────────────────────────────────────────

    /**
     * Cible visée à [decalage] — `null` si rien n'est prouvable (curseur
     * hors identifiant, cible classpath, appelable homonyme ambigu).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque étape de
     * la chaîne (déclaration au curseur, locale en portée, déclaration
     * projet désambiguïsée) est une ISSUE, même patron que les
     * résolveurs du LSP Java.
     */
    @Suppress("ReturnCount")
    internal fun cibleAuCurseur(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): Cible? {
        val jeton = InterrogationsSymboles.identifiantA(racine, decalage) ?: return null
        val nom = jeton.texte.toString()
        // 1. Nom DÉCLARÉ sous le curseur (locale comprise) — la plus
        //    profonde des déclarations VIVES porte le jeton.
        declarationViveAuCurseur(racine, decalage, uri, contexte)?.let { declaration ->
            val fileLocal =
                declaration.genre == GenreDeclaration.LOCALE || declaration.genre == GenreDeclaration.PARAMETRE
            return cibleDe(declaration, fileLocal = fileLocal)
        }
        // 2. Locale/paramètre en portée homonyme (référence à une locale).
        SymbolesProjet.localesEnPortee(racine, decalage)
            .firstOrNull { it.declaration.nom == nom }
            ?.let { locale ->
                return Cible(
                    nom = nom,
                    uri = locale.declaration.uri.ifBlank { uri },
                    debutNom = locale.declaration.debutNom,
                    finNom = locale.declaration.finNom,
                    genre = locale.declaration.genre,
                    paquet = locale.declaration.paquet,
                    conteneur = null,
                    recepteur = null,
                    fqcn = null,
                    fileLocal = true,
                )
            }
        // 3. Déclarations du projet homonymes — désambiguïsées
        //    (même paquet d'abord ; ambiguë = refus conservateur).
        //    ADR 0204 : le document COURANT vit son arbre propre ; la
        //    copie DISQUE du même fichier (URI distincte — convention de
        //    test fixture://, normalisation d'URI en production) porte
        //    la MÊME plage : c'est UNE cible, pas une ambiguïté. La
        //    candidate VIVE domine (l'arbre frais est la source de
        //    vérité, miroir de la branche plage d'occurrenceJeton) ;
        //    deux plages DISTINCTES restent deux candidates (homonymes
        //    réels — ambiguës).
        val vivantes =
            contexte.declarationsProjet.filter { declaration ->
                declaration.nom == nom &&
                    declaration.uri == uri &&
                    declaration.genre != GenreDeclaration.PARAMETRE &&
                    declaration.genre != GenreDeclaration.LOCALE
            }
        val candidates =
            contexte.declarationsProjet.filter { declaration ->
                declaration.nom == nom &&
                    declaration.uri.isNotBlank() &&
                    declaration.genre != GenreDeclaration.PARAMETRE &&
                    declaration.genre != GenreDeclaration.LOCALE
            }.filter { declaration ->
                // Copie disque d'une candidate VIVE (même nom, même
                // plage) : périmée, écartée.
                declaration.uri == uri ||
                    vivantes.none { vivante ->
                        vivante.debutNom == declaration.debutNom &&
                            vivante.finNom == declaration.finNom &&
                            vivante.nom == declaration.nom
                    }
            }
        return cibleDesambiguee(candidates, contexte.contexteNom.paquet)
    }

    /**
     * Déclaration VIVE dont le nom est sous le curseur (miroir de la
     * marche d'[InterrogationsSymboles], conteneur calculé) — la plus
     * profonde gagne. Une propriété sous un CORPS (bloc/lambda) est une
     * LOCALE (file-local) — pas une propriété projet : le classement
     * décide du régime de preuve des occurrences.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — chaque garde du parcours d'ancêtres (pas une déclaration, nom
     * hors plage) est une ISSUE, même patron que le survol.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun declarationViveAuCurseur(
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
            val regime = regimeDe(chaine, profondeur, contexte.contexteNom.paquet)
            val genre =
                when {
                    regime is RegimePortee.LOCALE -> GenreDeclaration.LOCALE
                    // ADR 0206 : paramètre-propriété du constructeur
                    // primaire — le classement PARAMÈTRE (file-local)
                    // laissait les usages non qualifiés SANS preuve
                    // (surlignage/références/renommage incomplets sur la
                    // forme la plus idiomatique du langage). Sous une
                    // CLASSE, `val`/`var` en fait une PROPRIÉTÉ membre :
                    // la preuve passe par occurrenceMembreEnglobant.
                    regime is RegimePortee.MEMBRE &&
                        declaration is jo.lspkotlin.parse.kt.KtParameter &&
                        declaration.estPropriete() -> GenreDeclaration.PROPRIETE
                    else -> genreVif(declaration)
                }
            return DeclarationProjet(
                genre = genre,
                nom = jeton.texte.toString(),
                uri = uri,
                debutNom = jeton.debut,
                finNom = jeton.fin,
                paquet = contexte.contexteNom.paquet,
                recepteur = recepteurVif(declaration),
                typeRetour = null,
                parametres = emptyList(),
                conteneur = regime.conteneur,
            )
        }
        return null
    }

    /**
     * Régime d'une déclaration VIVE au sein de la chaîne d'ancêtres :
     * locale (sous un corps bloc/lambda), membre d'une classe (conteneur
     * QUALIFIÉ par le paquet — convention DeclarationProjet), ou
     * top-level (fichier).
     */
    private sealed interface RegimePortee {
        data object LOCALE : RegimePortee

        data class MEMBRE(val conteneur: String?) : RegimePortee

        data object TOPLEVEL : RegimePortee
    }

    /** Conteneur d'un régime (null sauf MEMBRE). */
    private val RegimePortee.conteneur: String?
        get() = (this as? RegimePortee.MEMBRE)?.conteneur

    /**
     * Régime d'une déclaration VIVE au sein de la chaîne d'ancêtres :
     * locale (sous un corps bloc/lambda), membre d'une classe (conteneur
     * QUALIFIÉ par le paquet — convention DeclarationProjet), ou
     * top-level (fichier).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/LoopWithTooManyJump-
     * Statements — l'ordre des portées (bloc AVANT classe = locale) est
     * une décision de PARCOURS séquentiel, chaque régime est une ISSUE.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun regimeDe(
        chaine: List<NoeudSyntaxique>,
        profondeurDeclaration: Int,
        paquet: String?,
    ): RegimePortee {
        var conteneur: String? = null
        for (index in 0 until profondeurDeclaration) {
            val noeud = chaine[index]
            when (noeud.type) {
                KtNodeTypes.BLOCK, KtNodeTypes.FUNCTION_LITERAL -> return RegimePortee.LOCALE

                KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION -> {
                    val nom = nomConteneur(noeud) ?: return RegimePortee.LOCALE
                    conteneur = empilerConteneur(nom, conteneur, paquet)
                }

                else -> {}
            }
        }
        if (conteneur != null) return RegimePortee.MEMBRE(conteneur)
        return RegimePortee.TOPLEVEL
    }

    /** Empile un segment de conteneur (le premier reçoit le paquet). */
    private fun empilerConteneur(
        nom: String,
        conteneur: String?,
        paquet: String?,
    ): String =
        conteneur?.let { "$it.$nom" } ?: ((paquet?.let { "$it." } ?: "") + nom)

    /**
     * Cible d'une déclaration résolue (vive ou projet) — la plage du
     * nom voyage dans la déclaration elle-même (debutNom/finNom).
     */
    private fun cibleDe(
        declaration: DeclarationProjet,
        fileLocal: Boolean,
    ): Cible =
        Cible(
            nom = declaration.nom,
            uri = declaration.uri,
            debutNom = declaration.debutNom,
            finNom = declaration.finNom,
            genre = declaration.genre,
            paquet = declaration.paquet,
            conteneur = declaration.conteneur,
            recepteur = declaration.recepteur,
            fqcn = fqcnType(declaration),
            fileLocal = fileLocal,
        )

    /** FQCN d'une cible TYPE (classe/interface/objet/alias), `null` sinon. */
    private fun fqcnType(declaration: DeclarationProjet): String? {
        val estType =
            declaration.genre == GenreDeclaration.CLASSE ||
                declaration.genre == GenreDeclaration.INTERFACE ||
                declaration.genre == GenreDeclaration.OBJET ||
                declaration.genre == GenreDeclaration.ALIAS
        if (!estType) return null
        return if (declaration.conteneur != null) {
            // Type NICHÉ : le conteneur est déjà qualifié (`a.b.Outer`).
            "${declaration.conteneur}.${declaration.nom}"
        } else {
            (declaration.paquet?.let { "$it." } ?: "") + declaration.nom
        }
    }

    /**
     * Désambiguïsation d'un appelable/type homonyme : UNE candidate →
     * elle gagne ; plusieurs → la même PAQUET que le fichier courant
     * (précédence du résolveur Kotlin) ; encore ambiguë → refus (D3 :
     * une cible incertaine ne se renomme pas).
     */
    private fun cibleDesambiguee(
        candidates: List<DeclarationProjet>,
        paquetCourant: String?,
    ): Cible? {
        val elue = elueDesambiguee(candidates, paquetCourant) ?: return null
        return cibleDe(elue, fileLocal = false)
    }

    /** Élue d'un ensemble homonyme (même paquet d'abord), `null` si ambigu. */
    private fun elueDesambiguee(
        candidates: List<DeclarationProjet>,
        paquetCourant: String?,
    ): DeclarationProjet? {
        if (candidates.size <= 1) return candidates.singleOrNull()
        val memePaquet = candidates.filter { it.paquet == paquetCourant }
        return if (memePaquet.size == 1) memePaquet.single() else null
    }

    /** Genre d'une déclaration VIVE (miroir [InterrogationsSymboles]). */
    private fun genreVif(declaration: KtDeclaration): GenreDeclaration =
        when (declaration) {
            is KtClass -> if (declaration.estInterface()) GenreDeclaration.INTERFACE else GenreDeclaration.CLASSE
            is jo.lspkotlin.parse.kt.KtFunction -> GenreDeclaration.FONCTION
            is jo.lspkotlin.parse.kt.KtProperty -> GenreDeclaration.PROPRIETE
            is jo.lspkotlin.parse.kt.KtParameter -> GenreDeclaration.PARAMETRE
            else -> GenreDeclaration.OBJET
        }

    /** Récepteur d'extension écrit d'une déclaration VIVE. */
    private fun recepteurVif(declaration: KtDeclaration): String? =
        (declaration as? jo.lspkotlin.parse.kt.KtFunction)?.recepteurExtension()
            ?: (declaration as? jo.lspkotlin.parse.kt.KtProperty)?.recepteurExtension()

    // ── Collecte des occurrences ────────────────────────────────────────

    /**
     * Occurrences du symbole dans UN arbre (document courant, autres
     * documents ouverts, fichiers fermés) : déclaration (si le fichier
     * est le déclarant), imports du FQCN cible, références prouvées.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue sont les gardes du parcours (directive ignorée,
     * homonyme non prouvé), le patron même de la collecte.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun occurrencesDansArbre(
        racine: NoeudSyntaxique,
        uri: String,
        cible: Cible,
        contexteFichier: ContexteFichier,
        resolution: ResolutionKotlin,
    ): List<Occurrence> {
        val resultat = mutableListOf<Occurrence>()
        // Locales/paramètres : CONFINÉS au fichier déclarant.
        if (cible.fileLocal && uri != cible.uri) return resultat
        // Preuve de TYPE calculée une fois par fichier : le FQCN que ce
        // fichier résout pour le nom cible (null si rien).
        val preuve =
            PreuveFichier(
                contexte = contexteFichier,
                resolution = resolution,
                fqcnResolu =
                    cible.fqcn?.let { fqcn ->
                        resolution.fqcnDuNomEcrit(cible.nom, contexteFichier.contexteNom)
                    },
            )
        for (directive in KtFile(racine).directivesImport()) {
            occurrenceImport(directive.noeud, cible)?.let { resultat.add(it) }
        }
        for (jeton in identifiantsHomonymes(racine, cible.nom)) {
            occurrenceJeton(jeton, uri, cible, preuve, racine)?.let { resultat.add(it) }
        }
        return resultat.sortedBy { it.debut }
    }

    /**
     * Tous les jetons IDENTIFIER homonymes HORS directives package/import
     * (les usages — les imports ont leur propre preuve par FQCN).
     */
    private fun identifiantsHomonymes(
        racine: NoeudSyntaxique,
        nom: String,
    ): List<JetonSyntaxique> {
        val resultat = mutableListOf<JetonSyntaxique>()
        fun parcourir(noeud: NoeudSyntaxique) {
            if (noeud.type == KtNodeTypes.PACKAGE_DIRECTIVE || noeud.type == KtNodeTypes.IMPORT_DIRECTIVE) return
            for (enfant in noeud.enfants) {
                when (enfant) {
                    is JetonSyntaxique ->
                        if (enfant.type == KtTokens.IDENTIFIER && enfant.texte.toString() == nom) {
                            resultat.add(enfant)
                        }

                    is NoeudSyntaxique -> parcourir(enfant)
                }
            }
        }
        parcourir(racine)
        return resultat
    }

    /**
     * Preuve d'un jeton homonyme — le CŒUR conservateur de l'objet.
     * Chaque branche est une ISSUE : déclaration, non prouvé, référence
     * (locale par portée, type par FQCN, appelable par paquet/imports,
     * membre par récepteur typé ou classe englobante).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/CyclomaticComplexMethod
     * — la cascade de preuves EST le conservatisme D3, chaque échec est
     * un sous-rapport volontaire (jamais une fausse occurrence).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun occurrenceJeton(
        jeton: JetonSyntaxique,
        uri: String,
        cible: Cible,
        preuve: PreuveFichier,
        racine: NoeudSyntaxique,
    ): Occurrence? {
        // Déclaration elle-même (plage exacte du NOM cible) — dans le
        // fichier déclarant OU dans sa copie DISQUE du balayage (le
        // document ouvert vit sous l'URI de l'hôte, la copie scan du
        // même fichier porte la MÊME plage : elle n'est JAMAIS une
        // référence, quelle que soit l'URI sous laquelle elle revient).
        if (jeton.debut == cible.debutNom && jeton.fin == cible.finNom) {
            return if (uri == cible.uri) Occurrence(jeton.debut, jeton.fin, true) else null
        }
        // Référence ombragée par une locale de même nom : la référence
        // résout vers la locale, PAS vers la cible (garde systématique).
        if (cible.fileLocal) {
            return occurrenceLocale(jeton, cible, racine)
        }
        if (selectionDe(jeton, racine) != null) {
            // Sélecteur qualifié : cible MEMBRE (récepteur typé) ou
            // EXTENSION (récepteur = type du récepteur). Un type ou un
            // appelable top-level n'est JAMAIS un sélecteur.
            return occurrenceSelection(jeton, cible, preuve, racine)
        }
        // Type : le FQCN que ce fichier résout pour le nom doit être le
        // sien (imports exacts, paquet, joker, unicité projet).
        if (cible.fqcn != null) {
            return if (preuve.fqcnResolu == cible.fqcn) Occurrence(jeton.debut, jeton.fin, false) else null
        }
        // Membre non qualifié DANS son fichier déclarant : la classe
        // ENGLOBANTE du site doit être exactement le conteneur.
        if (cible.conteneur != null) {
            return occurrenceMembreEnglobant(jeton, uri, cible, preuve, racine)
        }
        // Appelable top-level : le fichier doit VOIR la cible (même
        // paquet, import exact, joker) SANS homonyme top-level local.
        return occurrenceTopLevel(jeton, uri, cible, preuve, racine)
    }

    /** Occurrence d'une cible file-local : preuve PAR PORTÉE. */
    private fun occurrenceLocale(
        jeton: JetonSyntaxique,
        cible: Cible,
        racine: NoeudSyntaxique,
    ): Occurrence? {
        val locale = SymbolesProjet.localesEnPortee(racine, jeton.debut).firstOrNull { it.declaration.nom == cible.nom }
        if (locale == null || locale.declaration.debutNom != cible.debutNom) return null
        return Occurrence(jeton.debut, jeton.fin, false)
    }

    /**
     * Occurrence au SÉLECTEUR d'un accès qualifié : récepteur typé →
     * le type (ou un supertype) doit porter la cible. Membre : conteneur
     * dans la chaîne de supertypes. Extension : le récepteur écrit de la
     * cible doit résoudre (dans CE fichier) au type du récepteur.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — récepteur non
     * typé, chaîne absente, cible ni membre ni extension : ISSUES.
     */
    @Suppress("ReturnCount")
    private fun occurrenceSelection(
        jeton: JetonSyntaxique,
        cible: Cible,
        preuve: PreuveFichier,
        racine: NoeudSyntaxique,
    ): Occurrence? {
        val recepteur = recepteurDeLaSelection(jeton, racine) ?: return null
        val typeRecepteur =
            TypageExpression.typeExpression(
                recepteur,
                racine,
                preuve.contexte.contexteNom,
                preuve.resolution,
                preuve.contexte.declarations,
            ) ?: return null
        val attendu =
            cible.conteneur ?: cible.recepteur?.let { recepteur ->
                preuve.resolution.fqcnDuNomEcrit(recepteur, preuve.contexte.contexteNom)
            } ?: return null
        return if (preuve.resolution.chaineSupertypes(typeRecepteur).contains(attendu)) {
            Occurrence(jeton.debut, jeton.fin, false)
        } else {
            null
        }
    }

    /** Récepteur du sélecteur contenant [jeton] (le nœud avant le point). */
    private fun recepteurDeLaSelection(
        jeton: JetonSyntaxique,
        racine: NoeudSyntaxique,
    ): NoeudSyntaxique? = selectionDe(jeton, racine)?.let { noeud -> recepteurQualifie(noeud) }

    /**
     * Le nœud d'ACCÈS QUALIFIÉ dont [jeton] est le SÉLECTEUR (nom après
     * le point) — null pour le qualifieur `a` et pour toute référence
     * non qualifiée (un sélecteur `b` de `a.b` est retenu).
     */
    private fun selectionDe(
        jeton: JetonSyntaxique,
        racine: NoeudSyntaxique,
    ): NoeudSyntaxique? {
        val chaine = chaineAncetresA(racine, jeton.debut)
        return chaine.asReversed().firstOrNull { noeud ->
            (noeud.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION || noeud.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION) &&
                selecteurContient(noeud, jeton)
        }
    }

    /** Vrai si la plage du sélecteur du nœud couvre le jeton entier. */
    private fun selecteurContient(
        noeud: NoeudSyntaxique,
        jeton: JetonSyntaxique,
    ): Boolean {
        val selecteur = selecteurQualifie(noeud) ?: return false
        return jeton.debut >= selecteur.debut && jeton.fin <= selecteur.fin
    }

    /**
     * Occurrence d'un MEMBRE non qualifié dans son fichier déclarant :
     * la classe ENGLOBANTE du site (chaîne d'ancêtres) doit être
     * exactement le conteneur de la cible — un membre homonyme d'une
     * classe interne n'est PAS retenu.
     */
    private fun occurrenceMembreEnglobant(
        jeton: JetonSyntaxique,
        uri: String,
        cible: Cible,
        preuve: PreuveFichier,
        racine: NoeudSyntaxique,
    ): Occurrence? {
        if (uri != cible.uri) return null
        val fqcnEnglobant = fqcnClasseEnglobante(racine, jeton.debut, preuve.contexte.contexteNom.paquet)
        val estDansConteneur = fqcnEnglobant == cible.conteneur
        return if (estDansConteneur) Occurrence(jeton.debut, jeton.fin, false) else null
    }

    /** FQCN (préfixé du paquet) de la classe/objet englobant un décalage. */
    private fun fqcnClasseEnglobante(
        racine: NoeudSyntaxique,
        decalage: Int,
        paquet: String?,
    ): String? {
        val segments =
            chaineAncetresA(racine, decalage)
                .asSequence()
                .filter { noeud -> noeud.debut <= decalage && decalage < noeud.fin }
                .filter { noeud ->
                    noeud.type == KtNodeTypes.CLASS || noeud.type == KtNodeTypes.OBJECT_DECLARATION
                }
                .mapNotNull { noeud -> nomConteneur(noeud) }
                .toList()
        if (segments.isEmpty()) return null
        return (paquet?.let { "$it." } ?: "") + segments.joinToString(".")
    }

    /** Nom de la classe/objet porté par un nœud conteneur (null sans nom). */
    private fun nomConteneur(noeud: NoeudSyntaxique): String? =
        noeud
            .jetonsDescendants()
            .firstOrNull { it.type == KtTokens.IDENTIFIER }
            ?.texte
            ?.toString()

    /**
     * Occurrence d'un APPELABLE top-level : le fichier doit VOIR la
     * cible — même paquet, import exact du FQCN, ou joker du paquet —
     * SANS homonyme top-level local (précédence/ambiguïté) et SANS
     * locale ombrageante en portée.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque règle de
     * visibilité est une ISSUE (miroir estEnPortee du survol).
     */
    @Suppress("ReturnCount")
    private fun occurrenceTopLevel(
        jeton: JetonSyntaxique,
        uri: String,
        cible: Cible,
        preuve: PreuveFichier,
        racine: NoeudSyntaxique,
    ): Occurrence? {
        if (estOmbrageeParLocale(jeton, racine)) return null
        val fqcnCible = (cible.paquet?.let { "$it." } ?: "") + cible.nom
        // Import exact : c'est LUI qui porte le nom (l'alias serait un
        // autre identifiant — jamais homonyme).
        if (preuve.contexte.contexteNom.importsParNom[cible.nom] == fqcnCible) {
            return Occurrence(jeton.debut, jeton.fin, false)
        }
        // Le fichier D'OCCURRENCE déclare un top-level homonyme :
        // la référence résout vers LUI (ou est ambiguë) — pas la cible.
        // PAR FICHIER (pas partout dans le projet : le scan porte toutes
        // les déclarations, y compris celle de la cible elle-même — la
        // cible et sa copie scan ne sont JAMAIS des homonymes : identité
        // uri + plage du nom, ADR 0204).
        if (preuve.contexte.declarations.any { declaration ->
                estHomonymeTopLevel(declaration, cible, uri)
            }
        ) {
            return null
        }
        // Même paquet (le fichier déclarant compris) OU joker du paquet.
        if (preuve.contexte.contexteNom.paquet == cible.paquet) return Occurrence(jeton.debut, jeton.fin, false)
        if (cible.paquet != null && cible.paquet in preuve.contexte.contexteNom.importsJoker) {
            return Occurrence(jeton.debut, jeton.fin, false)
        }
        return null
    }

    /** Vrai si la déclaration est un top-level homonyme DU FICHIER [uri]
     * DISTINCT de la cible — la cible elle-même et sa copie du scan
     * portent la même uri et la même plage de nom : elles ne comptent
     * pas (ADR 0204 : sans cette exclusion, TOUT usage same-file d'un
     * appelable top-level était rejeté — rename produisait du code
     * cassé, déclaration éditée mais usages du fichier déclarant non). */
    private fun estHomonymeTopLevel(
        declaration: DeclarationProjet,
        cible: Cible,
        uri: String,
    ): Boolean =
        declaration.nom == cible.nom &&
            declaration.conteneur == null &&
            declaration.uri == uri &&
            !estLocaleOuParam(declaration) &&
            !(declaration.uri == cible.uri &&
                declaration.debutNom == cible.debutNom &&
                declaration.finNom == cible.finNom)

    /** Vrai si la déclaration est une LOCALE ou un PARAMÈTRE. */
    private fun estLocaleOuParam(declaration: DeclarationProjet): Boolean =
        declaration.genre == GenreDeclaration.PARAMETRE || declaration.genre == GenreDeclaration.LOCALE

    /** Vrai si une LOCALE/paramètre de même nom est en portée au site. */
    private fun estOmbrageeParLocale(
        jeton: JetonSyntaxique,
        racine: NoeudSyntaxique,
    ): Boolean =
        SymbolesProjet
            .localesEnPortee(racine, jeton.debut)
            .any { it.declaration.nom == jeton.texte.toString() }

    /**
     * Segment de NOM d'un import du FQCN cible (déclaration exclue).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/CyclomaticComplexMethod
     * — chaque garde (pas une directive, sans identifiant, nom différent,
     * FQCN différent) est une ISSUE du filtrage conservateur.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun occurrenceImport(
        noeud: NoeudSyntaxique,
        cible: Cible,
    ): Occurrence? {
        if (noeud.type != KtNodeTypes.IMPORT_DIRECTIVE) return null
        // `import a.b.Nom` → le DERNIER identifiant est le nom importé.
        val dernier = nomImporte(noeud) ?: return null
        if (dernier.texte.toString() != cible.nom) return null
        val nomQualifie = jo.lspkotlin.parse.kt.KtImportDirective(noeud).nomQualifie() ?: return null
        if (!estImportDeCible(nomQualifie, fqcnImportCible(cible))) return null
        return Occurrence(dernier.debut, dernier.fin, false)
    }

    /** Dernier IDENTIFIER d'une directive import (le nom importé). */
    private fun nomImporte(noeud: NoeudSyntaxique): JetonSyntaxique? =
        noeud
            .jetonsDescendants()
            .filter { it.type == KtTokens.IDENTIFIER }
            .lastOrNull()

    /** FQCN que l'import doit porter pour concerner la cible. */
    private fun fqcnImportCible(cible: Cible): String =
        when {
            cible.fqcn != null -> cible.fqcn
            cible.conteneur != null -> "${cible.conteneur}.${cible.nom}"
            cible.paquet != null -> "${cible.paquet}.${cible.nom}"
            else -> cible.nom
        }

    /** Import EXACT du FQCN, ou PREFIXE dont la suite appartient à la cible. */
    private fun estImportDeCible(
        nomQualifie: String,
        fqcnCible: String,
    ): Boolean = nomQualifie == fqcnCible || (nomQualifie.startsWith("$fqcnCible.") && !nomQualifie.endsWith(".*"))

    // ── Formes LSP ──────────────────────────────────────────────────────

    /** Plage renommable au curseur (prepareRename) : la cible suffit. */
    internal fun plageRenommable(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): PlageRenommableKotlin? {
        val cible = cibleAuCurseur(racine, decalage, uri, contexte) ?: return null
        return PlageRenommableKotlin(cible.debutNom, cible.finNom, cible.nom)
    }

    /** Surlignages du document courant (documentHighlight). */
    internal fun surlignages(
        racine: NoeudSyntaxique,
        uri: String,
        cible: Cible,
        contexteFichier: ContexteFichier,
        resolution: ResolutionKotlin,
    ): List<PlageSurligneeKotlin> =
        occurrencesDansArbre(racine, uri, cible, contexteFichier, resolution)
            .map { occurrence -> PlageSurligneeKotlin(occurrence.debut, occurrence.fin, occurrence.genre) }

}
