package jo.codeide.lsp.host

import org.eclipse.lsp4j.ServerCapabilities

/**
 * Langage porté par l'hôte (lot LSP-1, ADR 0128 §4) — pilote l'écran
 * « Intelligence du code » (un écran par langage, identifiant de navigation).
 */
public enum class LangageLsp {
    /** Moteur Java vendored (ecj patché pour ART, ADR 0108). */
    JAVA,

    /** Moteur Kotlin — parseur sur la grammaire vendorée (ADR 0115). */
    KOTLIN,

    /** Moteur XML Android tolérant, zéro dépendance (décision X1). */
    XML,
}

/**
 * Capacité RÉGLABLE et consommable par l'éditeur (lot LSP-1, ADR 0128 §4) —
 * la liste des interrupteurs de l'écran L2 est DÉRIVÉE de ces valeurs :
 * jamais d'interrupteur pour une capacité que le serveur n'annonce pas.
 *
 * Les capacités consommées SANS réglage (symboles de document, repliage,
 * surlignage, jetons sémantiques, hiérarchies) vivent dans les
 * [ServerCapabilities] des serveurs, pas ici : l'énumération ne porte que
 * ce qui peut être COUPÉ côté client.
 */
public enum class CapaciteLsp {
    /** `textDocument/completion`. */
    COMPLETION,

    /** `textDocument/hover`. */
    SURVOL,

    /** `textDocument/publishDiagnostics` (toujours publiés par les 3 moteurs). */
    DIAGNOSTICS,

    /** `textDocument/signatureHelp`. */
    SIGNATURES,

    /** `textDocument/definition`. */
    DEFINITION,

    /** `textDocument/references`. */
    REFERENCES,

    /** `textDocument/rename` + `prepareRename`. */
    RENOMMAGE,

    /** `textDocument/codeAction` (quick-fixes). */
    ACTIONS_RAPIDES,

    /** `textDocument/formatting`. */
    FORMATAGE,

    /** `textDocument/inlayHint`. */
    INDICES_EN_LIGNE,
}

/**
 * Configuration immuable d'une session (lot LSP-1, ADR 0128) — chaque
 * champ projette un réglage RÉEL de l'écran « Intelligence du code »
 * (principe ADR 0059 : jamais de contrôle sans effet).
 *
 * Les valeurs par défaut sont le comportement HISTORIQUE de l'hôte
 * (v1.0.x–v1.1.x) : une session construite sans configuration est
 * indiscernable d'avant le lot LSP-1.
 *
 * Le sous-ensemble DYNAMIQUE (budget du cache, période de surveillance)
 * est appliquable en cours de session via
 * [SessionLsp.appliquer] ; le reste (JDK, plateforme, stockage) reste
 * fixé à la construction, fourni par l'environnement (écran L4 :
 * lecture seule).
 *
 * @property budgetCacheOctets plafond du budget du cache LRU de l'index
 *   (octets). `null` = **Auto** : le budget dérivé du corpus
 *   (`max(30 Mo, 512 Kio/jar)`, leçon du banc lot F2, ADR 0111 §6) —
 *   comportement historique. Une valeur explicite (256/512/1024 Mo à
 *   l'écran L3) BORNE ce budget : le service d'index est recréé, la
 *   reconstruction repart des segments persistés (zéro ré-indexation,
 *   ADR 0111 §3).
 * @property surveillanceFichiersMs période du surveillant de fichiers
 *   externes (S1-d, I4) en millisecondes ; `0` = surveillant DÉSACTIVÉ
 *   (l'interrupteur de l'écran L3). Défaut 5 s : un git pull / édition
 *   externe atteint le cache de parse sans réouverture. Bornes de
 *   l'écran : 2 s · 5 s · 10 s · 30 s (la valeur est libre côté API,
 *   positive ou nulle).
 */
public data class ConfigurationLsp(
    public val budgetCacheOctets: Long? = BUDGET_AUTO,
    public val surveillanceFichiersMs: Long = SURVEILLANCE_PAR_DEFAUT_MS,
) {
    init {
        require(surveillanceFichiersMs >= 0) {
            "surveillanceFichiersMs doit être >= 0 (0 = désactivée) : $surveillanceFichiersMs"
        }
        if (budgetCacheOctets != null) {
            require(budgetCacheOctets > 0) {
                "budgetCacheOctets doit être > 0 ou null (Auto) : $budgetCacheOctets"
            }
        }
    }

    public companion object {
        /** Période historique du surveillant (5 s, S1-d). */
        public const val SURVEILLANCE_PAR_DEFAUT_MS: Long = 5_000L

        /** Interrupteur « Surveiller les modifications externes » coupé. */
        public const val SURVEILLANCE_DESACTIVEE_MS: Long = 0L

        /** Budget « Auto » — plafond dérivé du corpus (comportement historique). */
        public val BUDGET_AUTO: Long? = null

        /** La configuration par défaut (= comportement historique v1.0.x). */
        @JvmStatic
        public fun parDefaut(): ConfigurationLsp = ConfigurationLsp()
    }
}

/**
 * Capacités ANNONCÉES par moteur (lot LSP-1, ADR 0128 §4) — la TABLE des
 * interrupteurs de l'écran L2 en est DÉRIVÉE. Miroir des `initialize`
 * réels des trois serveurs : la parité est VERROUILLÉE par test
 * (`CapacitesLspPariteTest` : les `ServerCapabilities` retournées par un
 * `initialize` réel se traduisent exactement en cette table) — la table
 * ne peut pas mentir sans faire tomber le test.
 *
 * DIAGNOSTICS : les trois moteurs publient des diagnostics (sync Full +
 * `publishDiagnostics`) — pas de champ `ServerCapabilities` à traduire,
 * la valeur vient du contrat de publication.
 */
public object CapacitesAnnoncees {
    /** Table des capacités RÉGLABLES consommables, par langage. */
    public fun capacites(langage: LangageLsp): Set<CapaciteLsp> =
        when (langage) {
            LangageLsp.JAVA -> CAPACITES_JAVA
            LangageLsp.KOTLIN -> CAPACITES_KOTLIN
            LangageLsp.XML -> CAPACITES_XML
        }

    /**
     * Traduction des [ServerCapabilities] RÉELLES d'un serveur (moteur du
     * test de parité) — ne porte pas DIAGNOSTICS (implicite par le contrat
     * de publication, cf. KDoc objet). Les champs LSP4J `Either` sont lus
     * par leur bras gauche (booléen posé tel quel) ou leur simple
     * présence (options posées : rename avec `prepareProvider`, inlay).
     */
    internal fun depuisServeur(capacites: ServerCapabilities): Set<CapaciteLsp> {
        val resultat = mutableSetOf<CapaciteLsp>()
        if (capacites.completionProvider != null) resultat.add(CapaciteLsp.COMPLETION)
        if (capacites.hoverProvider?.left == true) resultat.add(CapaciteLsp.SURVOL)
        if (capacites.signatureHelpProvider != null) resultat.add(CapaciteLsp.SIGNATURES)
        if (capacites.definitionProvider?.left == true) resultat.add(CapaciteLsp.DEFINITION)
        if (capacites.referencesProvider?.left == true) resultat.add(CapaciteLsp.REFERENCES)
        if (capacites.renameProvider != null) resultat.add(CapaciteLsp.RENOMMAGE)
        if (capacites.codeActionProvider != null) resultat.add(CapaciteLsp.ACTIONS_RAPIDES)
        if (capacites.documentFormattingProvider?.left == true) {
            resultat.add(CapaciteLsp.FORMATAGE)
        }
        if (capacites.inlayHintProvider != null) resultat.add(CapaciteLsp.INDICES_EN_LIGNE)
        return resultat
    }

    private val CAPACITES_JAVA: Set<CapaciteLsp> =
        setOf(
            CapaciteLsp.COMPLETION,
            CapaciteLsp.SURVOL,
            CapaciteLsp.DIAGNOSTICS,
            CapaciteLsp.SIGNATURES,
            CapaciteLsp.DEFINITION,
            CapaciteLsp.REFERENCES,
            CapaciteLsp.RENOMMAGE,
            CapaciteLsp.ACTIONS_RAPIDES,
            CapaciteLsp.FORMATAGE,
            CapaciteLsp.INDICES_EN_LIGNE,
        )

    private val CAPACITES_KOTLIN: Set<CapaciteLsp> =
        setOf(
            CapaciteLsp.COMPLETION,
            CapaciteLsp.SURVOL,
            CapaciteLsp.DIAGNOSTICS,
            CapaciteLsp.SIGNATURES,
            CapaciteLsp.DEFINITION,
            CapaciteLsp.REFERENCES,
            CapaciteLsp.RENOMMAGE,
            CapaciteLsp.ACTIONS_RAPIDES,
            // L2 (plan 1.3.0) : formateur borné + indices en ligne
            // (réglables — la table CapacitesAnnoncees du client les
            // alimente par la parité).
            CapaciteLsp.FORMATAGE,
            CapaciteLsp.INDICES_EN_LIGNE,
        )

    private val CAPACITES_XML: Set<CapaciteLsp> =
        setOf(
            CapaciteLsp.COMPLETION,
            CapaciteLsp.SURVOL,
            CapaciteLsp.DIAGNOSTICS,
            CapaciteLsp.DEFINITION,
            // L3 (plan 1.3.0) : références des ressources projet
            // (réglable — la table CapacitesAnnoncees l'alimente).
            CapaciteLsp.REFERENCES,
            CapaciteLsp.RENOMMAGE,
            CapaciteLsp.ACTIONS_RAPIDES,
            CapaciteLsp.FORMATAGE,
        )
}

/** Raison du refus d'un vidage de cache (mesuré et signalé, jamais silencieux). */
public enum class RaisonRefusVidage {
    /** Une indexation est en vol : vider pendant la construction laisserait
     *  des segments à moitié écrits et des états contradictoires — le bouton
     *  de l'écran L3 est désactivé avec explication. */
    INDEXATION_EN_COURS,
}

/**
 * Résultat du vidage du cache d'index (écran L3 « Vider le cache »,
 * lot LSP-1) : le contrat « refusé pendant une indexation » voyage en
 * DONNÉE, jamais en exception.
 */
public sealed interface ResultatVidageCache {
    /** Cache vidé : segments supprimés du disque et octets libérés. */
    public data class Videe(
        public val segmentsSupprimes: Int,
        public val octetsLiberes: Long,
    ) : ResultatVidageCache

    /** Vidage refusé — la raison est affichable telle quelle. */
    public data class Refusee(
        public val raison: RaisonRefusVidage,
    ) : ResultatVidageCache
}
