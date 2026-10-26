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
import jo.lspkotlin.parse.kt.nomAppele
import jo.lspkotlin.parse.kt.nomReference
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.RecepteursPortee
import jo.lspkotlin.resolve.ResolutionKotlin
import jo.lspkotlin.resolve.SymbolesProjet
import jo.lspkotlin.resolve.TypageExpression
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
    // ADR 0223 : le catalogue gagne la SÉLECTION PAR TYPES COMPLETS
    // (selectionParTypes + SelectionTypes) — le diagnostic
    // `kt.unresolved` reste UNE famille de preuves par cascade.
    "LargeClass",
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
     * @property portFichier lecture des fichiers FRÈRES déclarants
     *        (voie 2-ter « membres hérités », ADR 0216 — `null` = fermeture
     *        limitée aux clauses ÉCRITES du document courant : la session
     *        de production fournit toujours le port, miroir du GO TO Super).
     * @property script vrai pour un `.kts` (ADR 0221 — le récepteur
     *        implicite d'un script est le CONTEXTE HÔTE : DSL Gradle,
     *        arguments du shell… que le moteur ne modélise pas).
     * @property scanProjetComplet l'univers des sources projet est-il
     *        COMPLET (retour utilisateurs 25/10/2026, vague 3) ? La
     *        preuve d'absence sur un type PROJET sans héritage écrit
     *        n'est fondée QUE si le scan des racines a abouti — une
     *        extension vivant dans un fichier NON ENCORE scanné
     *        rendrait une absence « prouvée » fausse (D3).
     */
    internal data class Contexte(
        val texte: CharSequence,
        val contexteNom: ResolutionKotlin.ContexteNom,
        val resolution: ResolutionKotlin,
        val declarationsProjet: List<DeclarationProjet>,
        val indexAppelables: IndexAppelablesKotlin?,
        val portFichier: jo.lspkotlin.resolve.SuperKotlin.PortFichier? = null,
        val script: Boolean = false,
        val scanProjetComplet: Boolean = false,
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
            // Nom d'un ARGUMENT NOMMÉ (découverte E2 du rapport Compose,
            // ADR 0217) : `text = …`, `showBackground = true` — la
            // grammaire vendorée enveloppe le nom dans un
            // REFERENCE_EXPRESSION sous VALUE_ARGUMENT_NAME (amont :
            // parseValueArgument) ; le compilateur Kotlin ne le résout
            // JAMAIS comme une référence de nom — sa preuve exige la
            // sélection d'appelé par types complets (hors modèle T1,
            // LSP_KOTLIN §4.3) ; miroir kotlinc/Android Studio : jamais
            // « Référence non résolue » sur un nom d'argument (D3).
            if (estNomArgument(racine, reference)) return@parcourir
            // ADR 0223 : SÉLECTION PAR TYPES COMPLETS — le SÉLECTEUR
            // d'une expression qualifiée (et le CALLEE d'un appel
            // qualifié) dont le RÉCEPTEUR TYPE se prouve par le TYPE
            // COMPLET du récepteur :
            //  - membre PROUVÉ (champ/méthode/synthétique/companion du
            //    classpath ou du projet, extension sur le récepteur) →
            //    la référence RÉSOUT — c'est la réparation du callee
            //    membre (`layoutInflater.inflate(…)` était un FAUX
            //    `kt.unresolved` : le membre d'instance n'est PAS un
            //    appelable de portée) ;
            //  - ABSENCE TOTALEMENT prouvée (récepteur typé LISIBLE,
            //    aucun membre, aucune extension homonyme NULLE PART)
            //    → le sélecteur redevient DIAGNOSTIQUABLE (miroir
            //    Android Studio : `tv.setTexe(…)` y est rouge) ;
            //  - sinon (récepteur inconnu, classe illisible, homonyme
            //    d'extension) : silence D3 inchangé.
            val selection = selectionParTypes(racine, reference, contexte, index)
            if (selection == SelectionTypes.MEMBRE_TROUVE) return@parcourir
            // Sélecteur qualifié : le récepteur peut être inconnu.
            // ADR 0223 : sauf absence TOTALEMENT prouvable, le
            // sélecteur reste muet (D3 inchangé) — le CALLEE d'un appel
            // suit la fortune de SON récepteur (retour utilisateurs
            // 25/10/2026, famille E U1) : récepteur TYPÉ sans preuve
            // (`vueModele.effets.collect { }`, erasure `remember`) →
            // MUET ; récepteur SANS type (`java.io.Fichier(…)` garde
            // D3) → voie normale inchangée, le callee reste rouge si
            // `estResolu` échoue (miroir kotlinc : un callee après un
            // récepteur inconnu est signalé par le compilateur).
            if (selection == SelectionTypes.RECEPTEUR_TYPE && estCalleeDAppel(racine, reference)) {
                return@parcourir
            }
            if (selection != SelectionTypes.ABSENCE_PROUVEE &&
                estSelecteurQualifie(racine, reference)
            ) {
                return@parcourir
            }
            // E4 (découverte du rapport §14, ADR 0216) : SEGMENT d'un
            // TYPE QUALIFIÉ écrit (`android.os.Bundle` produit TROIS
            // REFERENCE_EXPRESSION — android, os, Bundle — que la
            // résolution de NOM ne résout pas individuellement : le
            // « beaucoup d'unresolved » des projets Android réels).
            if (estSegmentTypeQualifie(racine, reference, contexte)) return@parcourir
            // E4-ter (vague 4, retour utilisateurs 25/10/2026) : SEGMENT
            // d'un nom COMPLET QUALIFIÉ écrit EN EXPRESSION — le miroir
            // d'E4 pour les positions d'appel (`androidx.recycler...
            // .LinearLayoutManager(this)` sans import : le FQCN complet
            // vit dans la vue, ses segments sont muets ; introuvable,
            // seul le DERNIER reste faillible).
            if (estSegmentExpressionQualifiee(racine, reference, contexte)) return@parcourir
            // ADR 0221 — SCRIPT (.gradle.kts, .main.kts) : un nom NU
            // (sans récepteur écrit, sans type qualifié ancêtre) peut
            // venir du RÉCEPTEUR IMPLICITE DU CONTEXTE HÔTE — le DSL
            // Gradle injecte `plugins`/`android`/`dependencies`/
            // `implementation`/`libs`…, un script shell reçoit ses
            // arguments : l'ABSENCE n'est pas prouvable (D3 symétrique —
            // miroir Android Studio : jamais de tsunami d'« unresolved
            // reference » sur un build.gradle.kts sans modèle Gradle).
            // Les SEGMENTS QUALIFIÉS écrits restent faillibles (garde
            // dédiée : `java.io.Fichier` reste signalé).
            if (contexte.script && estNuDansScript(racine, reference)) return@parcourir
            if (estResolu(racine, reference, nom, contexte, index)) return@parcourir
            val jeton = reference.jetonsDescendants().firstOrNull { it.type == KtTokens.IDENTIFIER }
            // Mission fiabilisation, étape A — instrumentation « explain » :
            // chaque ÉMISSION est journalisée en FINE (jamais active par
            // défaut en production) : forme de la référence, issue de la
            // sélection par types complets (la voie de preuve de
            // l'absence), récepteur TYPÉ et état de l'univers des preuves
            // (scan du projet, index des appelables, régime script).
            // Paresseux : la construction ne court que si le journal
            // écoute — aucun coût en production.
            ExplicationsNonResolus.enregistrer {
                val (ligne, colonne) = ExplicationsNonResolus.position(
                    contexte.texte,
                    jeton?.debut ?: reference.debut,
                )
                ExplicationsNonResolus.Explication(
                    nom = nom,
                    ligne = ligne,
                    colonne = colonne,
                    forme = formeDeReference(racine, reference, selection),
                    selection = selection.name,
                    recepteurType = typeDuRecepteurSiSelection(racine, reference, contexte, index),
                    scanProjetComplet = contexte.scanProjetComplet,
                    indexPortant = index.connaitDesEntrees,
                    script = contexte.script,
                )
            }
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

    /**
     * ADR 0221 : la référence est-elle NUE au sens SCRIPT — sans
     * récepteur ÉCRIT (aucun ancêtre expression qualifiée), OU le
     * PREMIER SEGMENT (récepteur racine) d'une chaîne qualifiée — et
     * sans TYPE QUALIFIÉ ancêtre (les segments de `a.b.Inconnu`
     * restent faillibles) ? Un tel nom peut vivre dans le récepteur
     * implicite du contexte hôte (DSL Gradle : `libs`, `JavaVersion`,
     * `rootProject`…, shell) : l'absence n'est pas prouvable. Les
     * SÉLECTEURS au-delà du premier segment restent couverts par leur
     * propre règle (estSelecteurQualifie) — aucune perte de vrai
     * négatif.
     */
    private fun estNuDansScript(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        // TYPE QUALIFIÉ ancêtre : le segment porte le fardeau E4/E4bis
        // (dernier segment d'un type introuvable = diagnostiqué).
        val userTypes = chaine.filter { it.type == KtNodeTypes.USER_TYPE }
        if (userTypes.isNotEmpty()) {
            val externe = userTypes.last()
            return !externe.jetonsDescendants().any { it.type == KtTokens.DOT }
        }
        val qualifiee =
            chaine.lastOrNull {
                it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                    it.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION
            } ?: return true
        // Premier segment (récepteur racine) : NU — le contexte hôte
        // peut l'injecter (version catalog `libs`, imports du DSL).
        // La référence vit dans le PREMIER ENFANT de l'expression
        // qualifiée la plus PROCHE (le récepteur — jamais le sélecteur).
        val recepteur = qualifiee.sousNoeuds.firstOrNull() ?: return true
        return recepteur.debut <= reference.debut && reference.fin <= recepteur.fin
    }

    /**
     * La référence vit-elle sous un NOM D'ARGUMENT nommé —
     * `VALUE_ARGUMENT_NAME` (découverte E2, ADR 0217) ? Le nom d'un
     * argument d'appel ou d'annotation n'est PAS une référence de nom
     * Kotlin : il se prouve contre les PARAMÈTRES de l'appelé (types
     * complets) — le modèle conservateur le TAIT (D3 : sous-rapport).
     */
    private fun estNomArgument(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        return chaine.any { it.type == KtNodeTypes.VALUE_ARGUMENT_NAME }
    }

    /** Genres de déclaration qui SONT des types (miroir SuperKotlin). */
    private val GENRES_TYPES_SEGMENT =
        setOf(GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE, GenreDeclaration.OBJET)

    /**
     * E4 : la référence est-elle un SEGMENT d'un TYPE QUALIFIÉ écrit
     * qu'il faut TAIRE (`kt.unresolved` JAMAIS par segment) ?
     *
     * Le type COMPLET (`android.os.Bundle`) résout par la VUE du
     * classpath (membresDe) ou le PROJET (classes/scan) — pas par la
     * résolution de NOM du segment isolé. Si le type complet RÉSOUT :
     * TOUS ses segments sont muets. S'il est INTROUVABLE : seul le
     * DERNIER segment (nom de classe, celui que l'utilisateur corrige)
     * reste diagnostiqué par la voie normale — les segments PAQUET ne
     * le sont JAMAIS (miroir kotlinc : un segment faillible, pas
     * trois faux positifs).
     */
    private fun estSegmentTypeQualifie(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        contexte: Contexte,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        // Les segments d'un type QUALIFIÉ sont des USER_TYPE IMBRIQUÉS
        // (zz > zz.qqq > zz.qqq.Inconnu — grammaire vendorée PSI) : le
        // type COMPLET est l'USER_TYPE le plus EXTERNE de la chaîne
        // CONTIGUË (sans traverser TYPE_ARGUMENT_LIST : le segment
        // `a.b.C` de `List<a.b.C>` ne doit pas avaler `List`).
        val indexUser = chaine.indexOfLast { it.type == KtNodeTypes.USER_TYPE }
        if (indexUser < 0) return false
        var externe = chaine[indexUser]
        var parent = indexUser - 1
        while (parent >= 0 && chaine[parent].type == KtNodeTypes.USER_TYPE) {
            externe = chaine[parent]
            parent--
        }
        val ecrit = contexte.texte.subSequence(externe.debut, externe.fin)
        if ('.' !in ecrit) return false // type SIMPLE : voie normale inchangée
        val nomComplet = ecrit.toString().trim().trimEnd('?').trim()
        if (nomComplet.isEmpty()) return true
        // Le type COMPLET vit-il dans le classpath (vue) ou le projet ?
        if (contexte.resolution.membresDe(nomComplet) != null) return true
        if (contexte.contexteNom.classesProjet[nomComplet] != null) return true
        val fqcnProjet =
            contexte.declarationsProjet.any { d ->
                d.conteneur == null &&
                    d.genre in GENRES_TYPES_SEGMENT &&
                    (d.paquet?.let { "$it." }.orEmpty()) + d.nom == nomComplet
            }
        if (fqcnProjet) return true
        // Vague 4 (retour utilisateurs 25/10/2026 — U1
        // « ViewModelProvider.Factory » en position de TYPE) : le type
        // complet ÉCRIT peut désigner une classe IMBRIQUÉE par la
        // forme « Conteneur.Imbrique » — la vue la porte sous
        // « fqcn$Imbrique » (miroir de l'accès qualifié légal sans
        // import). La BASE résout par les imports (« ViewModelProvider
        // » → androidx.lifecycle.ViewModelProvider), puis la vue
        // sonde la forme dollar — TOUS les segments sont muets.
        val dernierPoint = nomComplet.lastIndexOf('.')
        if (dernierPoint > 0) {
            val base = nomComplet.substring(0, dernierPoint)
            val segment = nomComplet.substring(dernierPoint + 1)
            contexte.resolution.fqcnDuNomEcrit(base, contexte.contexteNom)?.let { fqcnBase ->
                if (contexte.resolution.membresDe("$fqcnBase\$$segment") != null) return true
                if (contexte.resolution.membresDe("$fqcnBase.$segment") != null) return true
            }
        }
        // Type complet introuvable : seul le DERNIER segment reste
        // diagnostiquable — les segments PAQUET sont TOUJOURS muets.
        val dernierIdentifiant =
            externe.jetonsDescendants().lastOrNull { it.type == KtTokens.IDENTIFIER }
                ?: return true
        return reference.debut != dernierIdentifiant.debut
    }

    /** La référence est-elle le SÉLECTEUR d'une expression qualifiée ?
     *
     * Les CALLEES d'appels (vivant dans un CALL_EXPRESSION) ne sont PAS
     * des sélecteurs purs : ils suivent la fortune de leur récepteur via
     * [estCalleeDAppel] (retour utilisateurs 25/10/2026, famille E U1) —
     * le `break` au CALL les laisse à cette voie dédiée. */
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

    /**
     * E4-ter (vague 4 — retour utilisateurs 25/10/2026, U2
     * `androidx.recyclerview.widget.LinearLayoutManager(this)` sans
     * import) : la référence est-elle un SEGMENT d'un nom COMPLET
     * QUALIFIÉ écrit EN EXPRESSION — le miroir EXACT d'
     * [estSegmentTypeQualifie] pour les positions d'expression ?
     *
     * La chaîne qualifiée RACINE (le DOT le plus externe de la chaîne
     * contiguë partant du récepteur, SANS appel intermédiaire — les
     * parenthèses d'un appel brisent le nom) est lue comme UN TEXTE :
     * si la VUE du classpath porte CE FQCN (`membresDe`), TOUS les
     * segments sont muets — l'accès qualifié complet est légal sans
     * import. Sinon, seul le DERNIER segment reste diagnostiquable
     * (miroir kotlinc : un segment faillible, pas trois faux
     * positifs).
     *
     * Ne s'applique qu'au PREMIER segment (récepteur racine de la
     * chaîne) ou au CALLEE final — les sélecteurs intermédiaires
     * relèvent déjà d'[estSelecteurQualifie] (D3 inchangé, une
     * absence prouvée y reste signalée).
     */
    private fun estSegmentExpressionQualifiee(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        contexte: Contexte,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        val indice = chaine.indexOf(reference)
        if (indice <= 0) return false
        // 1. Le PORT DIRECT de la référence : (a) un DOT/SAFE dont elle
        //    est le RÉCEPTEUR (premier segment — `androidx` de la
        //    chaîne), OU (b) le CALL dont elle est le callee ET dont le
        //    parent est un DOT/SAFE (callee final — le dernier segment
        //    du nom écrit porte l'appel). Les sélecteurs intermédiaires
        //    ne passent PAS ici (déjà muets par estSelecteurQualifie,
        //    une absence prouvée y reste signalée).
        var port = chaine[indice - 1]
        if (port.type == KtNodeTypes.CALL_EXPRESSION) {
            if (port.sousNoeuds.firstOrNull() != reference) return false
            if (indice < 2) return false
            port = chaine[indice - 2]
            if (port.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
                port.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
            ) {
                return false
            }
        } else if (port.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            port.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION
        ) {
            val recepteur = port.sousNoeuds.firstOrNull() ?: return false
            if (!couvre(recepteur, reference)) return false
        } else {
            return false
        }
        // 2. La chaîne qualifiée RACINE : remonter les parents DOT/SAFE
        //    contigus jusqu'au plus externe (tout autre parent stoppe).
        var externe = port
        var profondeur = indice - 2
        while (profondeur >= 0) {
            val candidat = chaine[profondeur]
            if (candidat.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
                candidat.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
            ) {
                break
            }
            externe = candidat
            profondeur--
        }
        // 3. Le NOM ÉCRIT complet de la chaîne racine : le RÉCEPTEUR du
        //    DOT racine porte tous les segments sauf le dernier — le
        //    dernier vit dans le SÉLECTEUR (référence, ou CALLEE du CALL
        //    sélecteur : la grammaire vendorée pose le CALL EN sélecteur
        //    du DOT, `exo.paq.ClassePublique()` = DOT(DOT(exo,paq),
        //    CALL(ClassePublique, args)) — le texte du DOT racine porte
        //    les PARENTHÈSES des arguments, il n'est PAS le nom).
        val recepteurRacine = externe.sousNoeuds.firstOrNull() ?: return false
        val selecteurRacine = externe.sousNoeuds.getOrNull(1) ?: return false
        val dernier =
            when (selecteurRacine.type) {
                KtNodeTypes.CALL_EXPRESSION -> selecteurRacine.sousNoeuds.firstOrNull()
                else -> selecteurRacine
            } ?: return false
        val texteRecepteur =
            contexte.texte.subSequence(recepteurRacine.debut, recepteurRacine.fin).toString()
        if ('(' in texteRecepteur || '?' in texteRecepteur) return false
        val texteDernier =
            contexte.texte.subSequence(dernier.debut, dernier.fin).toString()
        if ('(' in texteDernier || '?' in texteDernier) return false
        val nomComplet = "$texteRecepteur.$texteDernier".trim()
        if ('.' !in nomComplet || nomComplet.isEmpty()) return false
        // 4. Le FQCN complet vit-il dans la vue ? TOUS les segments
        //    sont muets (accès qualifié légal sans import).
        if (contexte.resolution.membresDe(nomComplet) != null) return true
        // 5. Introuvable : le PREMIER segment (récepteur racine — une
        //    référence inconnue, miroir kotlinc « unresolved: a » sur
        //    `ArticleRepoInconnu.tenter()`) et le DERNIER (callee — il
        //    suit la fortune de SON récepteur par les règles dédiées,
        //    estCalleeDAppel/estSelecteurQualifie) restent FAILLIBLES
        //    par la voie normale ; seuls les segments INTERMÉDIAIRES
        //    (paquets potentiels d'un FQN presque bon) sont muets —
        //    jamais trois faux positifs sur une seule faute.
        return reference.debut != dernier.debut && reference.debut != recepteurRacine.debut
    }

    /** La référence est-elle le CALLEE (premier enfant) d'un
     *  CALL_EXPRESSION porté par une expression QUALIFIÉE ?
     *  (`vueModele.effets.collect { … }` — le CALL est le sélecteur du
     *  DOT) — le callee suit alors la fortune de SON récepteur :
     *  [SelectionTypes.RECEPTEUR_TYPE] le tait, INCONNU le laisse à la
     *  voie normale (garde D3 — `java.io.Fichier(…)` reste signalé).
     *  Le parent DIRECT du CALL suffit : le callee est MUET dès que
     *  l'appel EST le sélecteur d'un qualifié (`a.b.f()`, `a?.b?.f()`),
     *  un appel PARENTHÉSÉ ou de bloc reste à la voie normale. */
    private fun estCalleeDAppel(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        val indice = chaine.indexOf(reference)
        if (indice <= 1) return false
        val appel = chaine[indice - 1]
        if (appel.type != KtNodeTypes.CALL_EXPRESSION) return false
        if (appel.sousNoeuds.firstOrNull() != reference) return false
        val parent = chaine[indice - 2]
        return parent.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
            parent.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION
    }

    /**
     * FORME syntaxique de la référence ÉMISE — pour le journal
     * « explain » (mission fiabilisation, étape A) : nom nu, sélecteur
     * qualifié, callee d'appel qualifié ou premier segment d'une
     * chaîne qualifiée. L'issue ADR 0223 prime sur la forme nue : une
     * `ABSENCE_PROUVEE` EST un sélecteur (ou un callee) dont la
     * preuve est complète — c'est la voie de preuve qui a conclu à
     * l'absence.
     */
    private fun formeDeReference(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        selection: SelectionTypes,
    ): String =
        when {
            selection == SelectionTypes.ABSENCE_PROUVEE -> "selecteur-qualifie-absence-prouvee"
            selection == SelectionTypes.RECEPTEUR_TYPE -> "recepteur-type-sans-preuve"
            estCalleeDAppel(racine, reference) -> "callee-d-appel-qualifie"
            estSelecteurQualifie(racine, reference) -> "selecteur-qualifie"
            estRecepteurRacineQualifie(racine, reference) -> "premier-segment-qualifie"
            else -> "nom-nu"
        }

    /**
     * La référence est-elle le RÉCEPTEUR RACINE (premier segment) d'une
     * expression qualifiée — `ArticleRepoInconnu` de
     * `ArticleRepoInconnu.tenter()` (E4-ter : premier segment FAILLIBLE
     * par la voie normale) ? Miroir du volet récepteur
     * d'[estNuDansScript], sans le régime script — sert à la FORME du
     * journal « explain » uniquement.
     */
    private fun estRecepteurRacineQualifie(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
    ): Boolean {
        val chaine = chaineAncetresA(racine, reference.debut)
        val indice = chaine.indexOf(reference)
        if (indice <= 0) return false
        val parent = chaine[indice - 1]
        if (parent.type != KtNodeTypes.DOT_QUALIFIED_EXPRESSION &&
            parent.type != KtNodeTypes.SAFE_ACCESS_EXPRESSION
        ) {
            return false
        }
        val recepteur = parent.sousNoeuds.firstOrNull() ?: return false
        return couvre(recepteur, reference)
    }

    /**
     * Issue de la SÉLECTION D'APPELÉ PAR TYPES COMPLETS (ADR 0223).
     */
    private enum class SelectionTypes {
        /** Membre PROUVÉ sur le type complet du récepteur — résout. */
        MEMBRE_TROUVE,

        /** Absence TOTALEMENT prouvée — le sélecteur est diagnostiquable. */
        ABSENCE_PROUVEE,

        /** Récepteur SANS type (introuvable, sous-chaîne) — aucun
         * renseignement : le callee garde la voie normale (garde D3
         * ADR 0221 : `java.io.Fichier(…)` reste signalé). */
        INCONNU,

        /** Récepteur TYPÉ mais sans preuve NI absence (classe illisible
         * dans la vue, type simple non résolu — écrit dans un fichier
         * frère, erasure `java.lang.Object`) : un CALLEE y est MUET
         * (D3 — retour utilisateurs 25/10/2026, famille E U1 : le type
         * écrit d'un membre se résout dans le contexte de l'APPELANT,
         * sans l'import du fichier déclarant). */
        RECEPTEUR_TYPE,
    }

    /**
     * SÉLECTION PAR TYPES COMPLETS (ADR 0223 — la suite K4+ du rapport
     * d'audit §8) : la référence est-elle le SÉLECTEUR (ou le CALLEE du
     * CALL sélecteur) d'une expression qualifiée dont le RÉCEPTEUR
     * TYPE ?
     *
     * La cascade PROUVÉE est celle du SURVOL qualifié (champ → méthode
     * → getter/setter synthétique → extension sur le récepteur) ÉTENDUE
     * du COMPANION (ADR 0222 : `Boite.creer()`, `Int.MAX_VALUE`) et du
     * membre du PROJET (conteneur = type du récepteur — companions
     * doublement émis).
     *
     * L'ABSENCE n'est prouvée que si : la classe du récepteur est
     * LISIBLE dans la vue (sa liste de membres déclarés est la source
     * de la preuve — une classe projet ou illisible ne prouve rien,
     * D3) ET AUCUNE extension homonyme n'existe NULLE PART dans
     * l'index (la preuve d'absence d'extension exige la complétude des
     * jars d'extensions : un seul homonyme — tout récepteur — impose
     * le silence). Miroir Android Studio : avec le classpath fourni,
     * l'absent est rouge.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque issue
     * (récepteur introuvable, sous-chaîne du récepteur, callee
     * d'argument, membre trouvé, absence prouvée) est une ISSUE
     * distincte du catalogue, patron estMembreHerite ;
     * CyclomaticComplexMethod — la cascade membre EST le catalogue.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun selectionParTypes(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        contexte: Contexte,
        index: IndexAppelablesKotlin,
    ): SelectionTypes {
        val nom = nomReference(reference) ?: return SelectionTypes.INCONNU
        if (nom.isBlank()) return SelectionTypes.INCONNU
        // 1-2. Porteur qualifié et type COMPLET du récepteur — extraits
        //      dans [typeDuRecepteurSiSelection] (mission fiabilisation,
        //      étape A — instrumentation « explain » : le journal des
        //      émissions rappelle la sonde SANS recalcul côté émission).
        val typeRecepteur =
            typeDuRecepteurSiSelection(racine, reference, contexte, index)
                ?: return SelectionTypes.INCONNU
        // 3. Membre PROUVÉ sur la chaîne de supertypes du récepteur :
        //    champ, méthode, accesseur synthétique, COMPANION du
        //    classpath, membre du projet, extension sur le récepteur.
        for (courant in contexte.resolution.chaineSupertypes(typeRecepteur)) {
            if (contexte.resolution.champsNommes(courant, nom).isNotEmpty()) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.methodesNommees(courant, nom).isNotEmpty()) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.getterSynthetique(courant, nom) != null) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.setterSynthetique(courant, nom) != null) return SelectionTypes.MEMBRE_TROUVE
            val compagnon = contexte.resolution.membresDe(courant + "\$Companion")
            if (compagnon != null &&
                (compagnon.champs.any { it.nom == nom } || compagnon.methodes.any { it.nom == nom })
            ) {
                return SelectionTypes.MEMBRE_TROUVE
            }
        }
        if (contexte.declarationsProjet.any { it.conteneur == typeRecepteur && it.nom == nom }) {
            return SelectionTypes.MEMBRE_TROUVE
        }
        for (courant in contexte.resolution.chaineSupertypes(typeRecepteur)) {
            for (variante in contexte.resolution.variantesRecepteur(courant)) {
                if (index.extensionsSur(variante).any { it.nom == nom }) {
                    return SelectionTypes.MEMBRE_TROUVE
                }
            }
        }
        // 3 bis. Type IMBRIQUÉ par nom (retour utilisateurs 25/10/2026 —
        //    U1 « Icons.Filled », « ViewModelProvider.Factory » ; U2
        //    « Paint.Cap », « RecyclerView.ViewHolder ») : un sélecteur
        //    qui désigne une classe imbriquée du récepteur (fqcn$Nom
        //    présent dans la vue) résout — miroir kotlinc, l'accès
        //    qualifié aux types imbriqués est légal sans import.
        if (contexte.resolution.membresDe(typeRecepteur + "\$" + nom) != null) {
            return SelectionTypes.MEMBRE_TROUVE
        }
        // 3 ter. Erasure des génériques (retour utilisateurs 25/10/2026
        //     — U1 « showSnackbar » après remember { … } typé Object) :
        //     java.lang.Object est le type EFFACÉ de T — une absence
        //     « prouvée » sur Object est une absence sur N'IMPORTE quel
        //     type générique inconnu : jamais prouvable (D3), le
        //     récepteur reste TYPÉ (un callee y est muet — l'erasure
        //     n'est pas une preuve d'absence).
        if (typeRecepteur == "java.lang.Object") return SelectionTypes.RECEPTEUR_TYPE
        // 3 quater. RÉCEPTEUR DE PORTÉE (vague 5, retour utilisateurs
        //     25/10/2026 — U1 « weight »/« align ») : le sélecteur peut
        //     être un MEMBRE-EXTENSION du this implicite d'un lambda de
        //     SCOPE englobant — `Modifier.weight(1f)` dans `Row { … }`
        //     (weight vit DANS RowScope, le récepteur d'extension est
        //     le PREMIER paramètre JVM, miroir kotlinc : les
        //     récepteurs de portée s'empilent, le plus profond
        //     d'abord). Un membre introuvable N'y prouve rien : la
        //     preuve d'absence reste celle de l'étape 4.
        for (fqcnPortee in RecepteursPortee.recepteursDePortee(
                racine, reference.debut, contexte.contexteNom, contexte.resolution,
                contexte.declarationsProjet, index,
            )
        ) {
            if (contexte.resolution.champsNommes(fqcnPortee, nom).isNotEmpty()) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.methodesNommees(fqcnPortee, nom).isNotEmpty()) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.getterSynthetique(fqcnPortee, nom) != null) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.resolution.setterSynthetique(fqcnPortee, nom) != null) return SelectionTypes.MEMBRE_TROUVE
            if (contexte.declarationsProjet.any { it.conteneur == fqcnPortee && it.nom == nom }) {
                return SelectionTypes.MEMBRE_TROUVE
            }
        }
        // 4. ABSENCE — conditions cumulatives (D3) : classe LISIBLE
        //    dans la vue + index PORTANT des entrées (une session nue ne
        //    connaît AUCUNE extension : l'absence n'y est pas prouvable)
        //    + aucune extension homonyme nulle part.
        if (contexte.resolution.membresDe(typeRecepteur) == null) {
            // Hors classpath : le récepteur peut être un type du PROJET
            // — son jeu de membres est ALORS prouvable par le scan
            // (vague 3, retour utilisateurs 25/10/2026).
            return selectionProjet(contexte, typeRecepteur, nom, index)
        }
        if (!index.connaitDesEntrees) return SelectionTypes.RECEPTEUR_TYPE
        if (index.topParNom(nom).any { it.recepteur != null }) {
            return SelectionTypes.RECEPTEUR_TYPE
        }
        return SelectionTypes.ABSENCE_PROUVEE
    }

    /**
     * Étapes 1-2 de [selectionParTypes] — le type COMPLET du
     * RÉCEPTEUR d'une sélection, `null` quand la référence n'est PAS
     * une sélection (nom nu, sous-chaîne du récepteur, argument
     * d'appel) ou que le récepteur reste SANS type.
     *
     * Extraite telle quelle (mission fiabilisation, étape A —
     * instrumentation « explain ») : le journal des `kt.unresolved`
     * émis rappelle cette sonde pour rendre le récepteur TYPÉ sans
     * recalcul ni duplication de la cascade.
     */
    @Suppress("ReturnCount") // chaque issue (porteur, sous-chaîne, récepteur, typage) est une ISSUE du catalogue
    private fun typeDuRecepteurSiSelection(
        racine: NoeudSyntaxique,
        reference: NoeudSyntaxique,
        contexte: Contexte,
        index: IndexAppelablesKotlin,
    ): String? {
        // 1. L'expression qualifiée PORTEUSE : remonte la chaîne — un
        //    DOT/SAFE/CALLABLE_REF dont la référence est HORS du
        //    récepteur (sélecteur, ou callee du CALL sélecteur) ; la
        //    remontée TRAVERSE le CALL qui PORTE la référence comme
        //    callee (`tv.setTexe(…)`), mais S'ARRÊTE aux arguments
        //    (premier enfant du CALL ≠ référence : un argument n'est
        //    pas une sélection).
        val chaine = chaineAncetresA(racine, reference.debut)
        val indice = chaine.indexOf(reference)
        if (indice <= 0) return null
        var porteur: NoeudSyntaxique? = null
        for (profondeur in (indice - 1) downTo 0) {
            val parent = chaine[profondeur]
            if (parent.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION ||
                parent.type == KtNodeTypes.SAFE_ACCESS_EXPRESSION ||
                parent.type == KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION
            ) {
                val recepteur = parent.sousNoeuds.firstOrNull() ?: return null
                // La référence DANS le récepteur : c'est une
                // sous-chaîne (`a.b.c` sur `a`), pas une sélection.
                if (couvre(recepteur, reference)) return null
                porteur = parent
                break
            }
            if (parent.type == KtNodeTypes.CALL_EXPRESSION) {
                if (parent.sousNoeuds.firstOrNull() != reference) return null
            }
        }
        val expression = porteur ?: return null
        val recepteur = expression.sousNoeuds.firstOrNull() ?: return null
        // 2. Type COMPLET du récepteur — valeur d'abord, CLASSE ensuite
        //    (`Boite.creer()`, `Int.MAX_VALUE` — ADR 0222), puis la voie
        //    GÉNÉRIQUE (vague 4, retour utilisateurs 25/10/2026 — U1
        //    « showSnackbar » après `remember { SnackbarHostState() }`) :
        //    un appel top-level SANS récepteur à retour TYPE GÉNÉRIQUE
        //    (`fun <T> remember(calculation: () -> T): T`) se LIE par
        //    son lambda — le type de l'appel est le type de la DERNIÈRE
        //    expression du corps du littéral (règle EXACTE sur ce
        //    périmètre : sans récepteur, T ne peut venir QUE du lambda ;
        //    les retours concrets — Long, Unit, List… — portent des noms
        //    de 3+ lettres et sont EXCLUS par le marqueur).
        return TypageExpression.typeExpression(
            recepteur,
            racine,
            contexte.contexteNom,
            contexte.resolution,
            contexte.declarationsProjet,
        ) ?: TypageExpression.typeStatiqueDe(recepteur, contexte.contexteNom, contexte.resolution)
            ?: typeAppelGenerique(recepteur, contexte, index)
            ?: typeLocaleGenerique(recepteur, racine, contexte, index)
    }

    /**
     * Membres du supertype IMPLICITE — Any/Object (miroir kotlinc
     * JVM) : présents sur TOUT type, jamais prouvable absent.
     * `clone`/`finalize` (protégés en Java) y sont inclus : les tenir
     * pour trouvés ne fait QUE TAIRE (D3), jamais signaler à tort.
     */
    private val MEMBRES_IMPLICITES =
        setOf(
            "toString", "equals", "hashCode", "wait", "notify", "notifyAll", "getClass", "clone", "finalize",
        )

    /**
     * Issue de la SÉLECTION D'APPELÉ PAR TYPES COMPLETS (ADR 0223) —
     * volet PROJET (vague 3, retour utilisateurs 25/10/2026) : le
     * récepteur n'est PAS dans la vue classpath mais le scan du
     * projet connaît SON TYPE. La preuve d'absence y est fondée
     * QUAND le jeu de membres est COMPLET : type SANS héritage écrit
     * ([DeclarationProjet.estSansHeritageEcrit] — membres DÉCLARÉS +
     * implicites Any/Object, ni hérités ni compagnons cachés) ET
     * l'univers des sources COMPLET ([Contexte.scanProjetComplet]).
     * Le pas 3 a DÉJÀ sondé les membres déclarés (`conteneur ==
     * récepteur`) — atteindre ici signifie « pas trouvés ».
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (implicites, univers incomplet, type introuvable/à héritage,
     * extensions classpath/projet) est une ISSUE du catalogue D3.
     */
    @Suppress("ReturnCount")
    private fun selectionProjet(
        contexte: Contexte,
        typeRecepteur: String,
        nom: String,
        index: IndexAppelablesKotlin,
    ): SelectionTypes {
        // a) Supertype IMPLICITE (Any/Object) : membres de TOUT type.
        if (nom in MEMBRES_IMPLICITES) return SelectionTypes.MEMBRE_TROUVE
        // b) L'univers des sources doit être COMPLET : un scan en vol
        //    rendrait une absence « prouvée » fausse (D3).
        if (!contexte.scanProjetComplet) return SelectionTypes.RECEPTEUR_TYPE
        // c) Le TYPE du récepteur doit être CONNU et SANS héritage
        //    écrit — un maillon manquant rouvre les membres hérités.
        if (typeProjet(contexte.declarationsProjet, typeRecepteur)?.estSansHeritageEcrit != true) {
            return SelectionTypes.RECEPTEUR_TYPE
        }
        // d) Extensions du CLASSPATH homonymes (tout récepteur) : la
        //    preuve d'absence d'extension exige l'index (ADR 0223).
        if (!index.connaitDesEntrees) return SelectionTypes.RECEPTEUR_TYPE
        if (index.topParNom(nom).any { it.recepteur != null }) return SelectionTypes.RECEPTEUR_TYPE
        // e) Extensions du PROJET homonymes (tout récepteur — la
        //    résolution de leur type écrit reste à faire, D3 large).
        if (contexte.declarationsProjet.any { it.recepteur != null && it.nom == nom }) {
            return SelectionTypes.RECEPTEUR_TYPE
        }
        return SelectionTypes.ABSENCE_PROUVEE
    }

    /**
     * Déclaration de TYPE du projet portant ce FQCN (conteneur
     * qualifié ou paquet + nom — miroir de la clé `conteneur` des
     * MEMBRES), `null` si le scan ne connaît pas ce type.
     */
    private fun typeProjet(
        declarations: List<DeclarationProjet>,
        fqcn: String,
    ): DeclarationProjet? =
        declarations.firstOrNull { decl ->
            (decl.genre == GenreDeclaration.CLASSE ||
                decl.genre == GenreDeclaration.OBJET ||
                decl.genre == GenreDeclaration.INTERFACE) &&
                decl.recepteur == null &&
                run {
                    val parent = decl.conteneur ?: decl.paquet
                    (parent?.plus('.')?.plus(decl.nom) ?: decl.nom) == fqcn
                }
        }

    /**
     * Type d'un APPEL top-level GÉNÉRIQUE lié par son lambda (vague 4,
     * retour utilisateurs 25/10/2026 — U1 « showSnackbar » : le type
     * EFFACÉ de `remember { … }` masquait SnackbarHostState) : quand
     * le récepteur est un APPEL dont le callee est un appelable de
     * premier niveau SANS récepteur, EN PORTÉE du fichier, au retour
     * TYPE GÉNÉRIQUE (`T`, `R` — les noms concrets Long/Unit/List
     * portent 3+ lettres), le type de l'appel est le type de la
     * DERNIÈRE expression du corps du littéral — la seule source de
     * la liaison de T sur ce périmètre (miroir kotlinc).
     */
    private fun typeAppelGenerique(
        recepteur: NoeudSyntaxique,
        contexte: Contexte,
        index: IndexAppelablesKotlin,
    ): String? {
        if (recepteur.type != KtNodeTypes.CALL_EXPRESSION) return null
        val nom = jo.lspkotlin.parse.kt.nomAppele(recepteur) ?: return null
        // L'appelable doit être un TOP-LEVEL PUR (sans récepteur —
        // apply/with sont des EXTENSIONS dont le T vient du RÉCEPTEUR,
        // pas du lambda) au RETOUR GÉNÉRIQUE, en portée du fichier.
        val generique =
            index.topParNom(nom).any { appelable ->
                appelable.recepteur == null &&
                    appelable.typeRetour.estRetourGenerique() &&
                    contexte.resolution.appelableEnPortee(appelable, contexte.contexteNom)
            }
        if (!generique) return null
        // Le LITTÉRAL liant : premier FUNCTION_LITERAL du CALL (trailing
        // ou argument ordinaire — T ne peut venir que de lui).
        var litteral: NoeudSyntaxique? = null
        parcourir(recepteur) { noeud ->
            if (litteral == null && noeud.type == KtNodeTypes.FUNCTION_LITERAL) litteral = noeud
        }
        val liant = litteral ?: return null
        // La DERNIÈRE expression du corps porte la valeur du littéral.
        val corps = liant.sousNoeuds.firstOrNull { it.type == KtNodeTypes.BLOCK } ?: return null
        val derniere = corps.sousNoeuds.lastOrNull() ?: return null
        return TypageExpression.typeExpression(
            derniere,
            liant,
            contexte.contexteNom,
            contexte.resolution,
            contexte.declarationsProjet,
        )
    }

    /**
     * Type d'une LOCALE dont l'INITIALISATEUR est un appel top-level
     * GÉNÉRIQUE lié par son lambda (vague 4 — la suite de
     * [typeAppelGenerique] : le récepteur du sélecteur est une
     * RÉFÉRENCE, `hoteSnackbar.showSnackbar(…)` où
     * `val hoteSnackbar = remember { SnackbarHostState() }` — le typage
     * de la référence passe par l'initialiseur, que seule la voie
     * générique d'ici sait lire).
     */
    private fun typeLocaleGenerique(
        recepteur: NoeudSyntaxique,
        racine: NoeudSyntaxique,
        contexte: Contexte,
        index: IndexAppelablesKotlin,
    ): String? {
        if (recepteur.type != KtNodeTypes.REFERENCE_EXPRESSION) return null
        val nom = nomReference(recepteur) ?: return null
        for (locale in SymbolesProjet.localesEnPortee(racine, recepteur.debut)) {
            if (locale.declaration.nom != nom) continue
            val initialiseur = locale.propriete?.initialisateur() ?: continue
            return typeAppelGenerique(initialiseur.noeud, contexte, index)
        }
        return null
    }

    /** Le type écrit d'un appelable est-il un PARAMÈTRE GÉNÉRIQUE (`T`, `R`) ?
     *  Les types concrets du runtime Kotlin portent tous 3+ lettres. */
    private fun String?.estRetourGenerique(): Boolean =
        this != null && length <= 2 && isNotEmpty() && first().isUpperCase() && '.' !in this

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
            contexte.portFichier,
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
