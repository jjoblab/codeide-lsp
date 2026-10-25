package jo.lspkotlin.diagnostics

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.InformationLieeKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.api.TagDiagnosticKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtImportDirective
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Calcul des diagnostics SYNTAXIQUES d'un arbre Kotlin (K2,
 * LSP_KOTLIN.md §4.1 « diagnostics », §4.3 périmètre initial) — fonctions
 * PURES sur l'arbre reconstruit : aucune entrée/sortie, aucun état, la
 * session ([jo.lspkotlin.session.SessionLangageKotlinParse]) les appelle
 * à chaque didOpen/didChange.
 *
 * Catalogue K2 (conservateur, D3 — sous-rapporte plutôt que faux positif) :
 * - [CodesDiagnosticsKotlin.SYNTAXE] : marqueurs d'erreur du parseur —
 *   le VRAI verdict de la grammaire vendorée, aucun diagnostic inventé ;
 * - [CodesDiagnosticsKotlin.IMPORT_INUTILISE] : nom effectif absent des
 *   identifiants du fichier (hors directives package/import) — joker et
 *   directives en édition jamais signalés ; porte le TAG [TagDiagnosticKotlin.INUTILE]
 *   (KP15 : le client qui le comprend grille la directive) ;
 * - [CodesDiagnosticsKotlin.IMPORT_CONFLIT] : deux imports non joker de
 *   noms qualifiés distincts partageant le même nom effectif — porté avec
 *   une INFORMATION LIÉE sur la directive PREMIÈRE (KP15 : les deux
 *   directives du conflit sont visibles d'un coup d'œil).
 *
 * Les diagnostics sémantiques (résolution, types, surcharges) arrivent
 * K3/K4 avec le modèle de symboles — rien ici ne devine.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque
 * diagnostic d'imports a SA fonction (utilisés, conflits, noms
 * effectifs, délégation by — retour utilisateurs 25/10/2026).
 */
@Suppress("TooManyFunctions")
internal object DiagnosticsSyntaxiques {
    /**
     * Calcule tous les diagnostics syntaxiques d'un arbre, triés par
     * position (puis code, déterminisme du test).
     *
     * @param arbre arbre reconstruit (mode fichier OU script — la marche
     *        saute les sous-arbres import/package quel que soit le racine).
     * @return les diagnostics ordonnés ; liste VIDE pour un arbre sain
     *         sans import fautif.
     */
    public fun calculer(arbre: ArbreSyntaxiqueKotlin): List<DiagnosticKotlin> {
        val diagnostics = mutableListOf<DiagnosticKotlin>()
        diagnostics += erreursParse(arbre.racine)
        diagnostics += diagnosticsImports(arbre.racine)
        return diagnostics.sortedWith(compareBy({ it.debut }, { it.code }))
    }

    // ── Erreurs du parseur ──────────────────────────────────────────────

    /**
     * Collecte les marqueurs d'erreur du parseur en profondeur d'abord :
     * chaque nœud marqué est UNE erreur réelle de la grammaire (la
     * reconstruction déduplique déjà les marqueurs superposés, cf.
     * ReconstructeurArbre) — le message du parseur est repris tel quel,
     * générique sinon.
     */
    private fun erreursParse(racine: NoeudSyntaxique): List<DiagnosticKotlin> {
        val erreurs = mutableListOf<DiagnosticKotlin>()
        fun parcourir(noeud: NoeudSyntaxique) {
            if (noeud.marqueErreur) {
                erreurs += erreurDe(noeud)
            }
            noeud.enfants.forEach { enfant ->
                if (enfant is NoeudSyntaxique) parcourir(enfant)
            }
        }
        parcourir(racine)
        return erreurs
    }

    /** Diagnostic d'un nœud d'erreur (message du parseur ou générique). */
    private fun erreurDe(noeud: NoeudSyntaxique): DiagnosticKotlin =
        DiagnosticKotlin(
            code = CodesDiagnosticsKotlin.SYNTAXE,
            severite = SeveriteKotlin.ERREUR,
            message = noeud.messageErreur ?: "Fragment non reconnu par la grammaire",
            debut = noeud.debut,
            fin = noeud.fin,
        )

    // ── Imports ─────────────────────────────────────────────────────────

    /**
     * Diagnostics d'imports : inutilisés et conflictuels. Conservative :
     * toute directive incomplète (nom qualifié null — édition en cours),
     * joker (`.*`) ou redondante (même nom qualifié répété) n'est JAMAIS
     * signalée.
     */
    private fun diagnosticsImports(racine: NoeudSyntaxique): List<DiagnosticKotlin> {
        val directives = directivesImportDe(racine)
        if (directives.isEmpty()) return emptyList()
        val resultats = mutableListOf<DiagnosticKotlin>()
        resultats += importsInutilises(directives, identifiantsUtilises(racine), aDelegation(racine))
        resultats += importsConflicts(directives)
        return resultats
    }

    /** Directives d'import de la racine (façade [KtFile], mode script compris). */
    private fun directivesImportDe(racine: NoeudSyntaxique): List<KtImportDirective> =
        KtFile(racine).directivesImport()

    /**
     * Identifiants « utilisés » du fichier : tout jeton IDENTIFIER hors
     * des sous-arbres package et imports — un nom présent uniquement en
     * commentaire ou chaîne n'est PAS un identifiant (le lexer les sépare
     * déjà), donc l'import est réellement inutilisé.
     */
    private fun identifiantsUtilises(racine: NoeudSyntaxique): Set<String> {
        val noms = mutableSetOf<String>()
        fun parcourir(noeud: NoeudSyntaxique) {
            if (noeud.type == KtNodeTypes.IMPORT_LIST || noeud.type == KtNodeTypes.PACKAGE_DIRECTIVE) return
            noeud.enfants.forEach { enfant ->
                when (enfant) {
                    is JetonSyntaxique ->
                        if (enfant.type == KtTokens.IDENTIFIER) noms.add(enfant.texte.toString())

                    is NoeudSyntaxique -> parcourir(enfant)
                }
            }
        }
        parcourir(racine)
        return noms
    }

    /**
     * Imports inutilisés : le nom effectif (alias, sinon dernier segment)
     * n'apparaît pas parmi les identifiants utilisés.
     *
     * [aDelegation] (retour utilisateurs 25/10/2026 — U1 « getValue/setValue ») :
     * le fichier porte une délégation `by` — les OPÉRATEURS de délégation
     * (getValue/setValue importés de androidx.compose.runtime) sont
     * APPELÉS IMPLICITEMENT par `var x by remember { }` sans que le jeton
     * n'apparaisse : un tel import n'est JAMAIS inutilisé si une
     * délégation vit dans le fichier (le DSL Compose du state).
     */
    private fun importsInutilises(
        directives: List<KtImportDirective>,
        identifiants: Set<String>,
        aDelegation: Boolean,
    ): List<DiagnosticKotlin> =
        directives.mapNotNull { directive ->
            val nomQualifie = directive.nomQualifie() ?: return@mapNotNull null
            val nomEffectif = nomEffectifDe(directive, nomQualifie) ?: return@mapNotNull null
            if (nomEffectif in identifiants) return@mapNotNull null
            if (aDelegation && nomEffectif in OPERATEURS_DELEGATION) return@mapNotNull null
            DiagnosticKotlin(
                code = CodesDiagnosticsKotlin.IMPORT_INUTILISE,
                severite = SeveriteKotlin.AVERTISSEMENT,
                message = "Import inutilisé : « $nomQualifie » — le nom « $nomEffectif » " +
                    "n'apparaît pas dans ce fichier",
                debut = directive.debut,
                fin = directive.fin,
                tags = listOf(TagDiagnosticKotlin.INUTILE),
            )
        }

    /**
     * Imports conflictuels : premier nom effectif déjà importé sous un nom
     * qualifié DIFFÉRENT — le conflit est signalé sur la directive la plus
     * tardive (la chronologie du fichier), le message porte les deux noms
     * qualifiés.
     */
    private fun importsConflicts(directives: List<KtImportDirective>): List<DiagnosticKotlin> {
        val conflits = mutableListOf<DiagnosticKotlin>()
        val importsParNom = mutableMapOf<String, Pair<String, KtImportDirective>>()
        directives.forEach { directive ->
            val nomQualifie = directive.nomQualifie() ?: return@forEach
            val nomEffectif = nomEffectifDe(directive, nomQualifie) ?: return@forEach
            val premier = importsParNom[nomEffectif]
            if (premier == null) {
                importsParNom[nomEffectif] = Pair(nomQualifie, directive)
            } else if (premier.first != nomQualifie) {
                conflits += conflitDe(directive, nomEffectif, premier.first, premier.second, nomQualifie)
            }
        }
        return conflits
    }

    /**
     * Diagnostic de conflit sur la directive tardive [directive] — l'
     * INFORMATION LIÉE porte la plage de la directive PREMIÈRE
     * [premiereDirective] (KP15 : le client l'affiche au clic).
     */
    private fun conflitDe(
        directive: KtImportDirective,
        nomEffectif: String,
        premier: String,
        premiereDirective: KtImportDirective,
        second: String,
    ): DiagnosticKotlin =
        DiagnosticKotlin(
            code = CodesDiagnosticsKotlin.IMPORT_CONFLIT,
            severite = SeveriteKotlin.ERREUR,
            message = "Imports conflictuels : « $nomEffectif » importé de " +
                "« $premier » et « $second »",
            debut = directive.debut,
            fin = directive.fin,
            informationsLiees = listOf(
                InformationLieeKotlin(
                    "Première directive d'import en conflit : « $premier »",
                    premiereDirective.debut,
                    premiereDirective.fin,
                ),
            ),
        )

    /**
     * Nom effectif d'une directive : l'alias s'il existe, sinon le dernier
     * segment du nom qualifié. Null pour un joker (`.*`) ou un nom vide
     * (jamais signalés — conservateur).
     */
    private fun nomEffectifDe(
        directive: KtImportDirective,
        nomQualifie: String,
    ): String? {
        directive.alias()?.let { return it }
        val segment = nomQualifie.substringAfterLast('.')
        return segment.takeIf { it.isNotEmpty() && it != "*" }
    }

    /**
     * Le fichier porte-t-il une DÉLÉGATION `by` (propriété déléguée —
     * `var x by remember { }`) : le jeton BY_KEYWORD suffit (retour
     * utilisateurs 25/10/2026, U1). Parcours local (même marche que
     * [identifiantsUtilises]).
     */
    private fun aDelegation(racine: NoeudSyntaxique): Boolean {
        fun marcher(noeud: NoeudSyntaxique) {
            noeud.enfants.forEach { enfant ->
                when (enfant) {
                    is JetonSyntaxique ->
                        if (enfant.type == KtTokens.BY_KEYWORD) throw DelegationTrouvee()

                    is NoeudSyntaxique -> marcher(enfant)
                }
            }
        }
        // Le signal interne de fin de parcours est AVALE volontairement
        // (sortie par exception — SwallowedException exemptée, règle 16).
        @Suppress("SwallowedException")
        return try {
            marcher(racine)
            false
        } catch (e: DelegationTrouvee) {
            true
        }
    }

    /** Signal interne du parcours [aDelegation] (sortie par exception). */
    private class DelegationTrouvee : RuntimeException()

    /** Opérateurs de délégation appelés IMPLICITEMENT par `by`. */
    private val OPERATEURS_DELEGATION = setOf("getValue", "setValue")
}
