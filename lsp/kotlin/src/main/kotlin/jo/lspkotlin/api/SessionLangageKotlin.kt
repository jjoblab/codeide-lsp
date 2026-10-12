package jo.lspkotlin.api

/**
 * Session de langue d'un moteur Kotlin (K2, LSP_KOTLIN.md §5) — le
 * contrat de session TOUT ENTIER derrière le SPI (décision D6 : le
 * module reste découplable en « pack langue » téléchargeable ; l'hôte ne
 * voit ni le parseur, ni l'arbre, ni LSP4J — seulement des documents qui
 * entrent et des diagnostics neutres qui sortent).
 *
 * Cycle de vie : une session par CONNEXION LSP (l'hôte crée un serveur
 * par connexion cel-lsp, il lui fournit une session neuve issue de
 * [MoteurLangageKotlin.creerSession]) ; la session vit la vie de la
 * connexion — [fermerDocument] au didClose, [close] au shutdown. Hors
 * cela, elle est AUTOCONTENUE : aucun index, aucun classpath, aucun état
 * partagé (K2 = parse tolérant + diagnostics syntaxiques ; le classpath
 * v8 et l'index disque rejoignent la session en K3).
 *
 * Synchronisation : les appels peuvent venir du pool LSP4J de l'hôte —
 * l'implémentation sérialise (verrou interne) ; les opérations sont
 * courtes (parse complet d'un fichier).
 *
 * Tolérance (D3) : aucune méthode ne lève sur du texte invalide — la
 * grammaire vendorée est tolérante, l'arbre est toujours construit, les
 * erreurs voyagent comme diagnostics. Réouvrir un document ouvert le
 * REMPLACE ; modifier un document inconnu équivaut à l'ouvrir (tolérant,
 * l'hôte garantit l'ordre mais le moteur ne s'y fie pas) ; fermer un
 * document inconnu est sans effet.
 */
public interface SessionLangageKotlin : AutoCloseable {
    /**
     * Ouvre (ou remplace) le document [uri] avec le texte intégral
     * [texte] (synchronisation LSP COMPLÈTE — l'incrémental arrive avec
     * le re-parse de sous-arbre, postérieur à K2).
     *
     * @param uri identité du document (celle de l'hôte, opaque ici).
     * @param texte contenu intégral (aucune hypothèse de validité).
     * @param script vrai pour un `.kts` (libre en racine).
     */
    public fun ouvrirDocument(uri: String, texte: CharSequence, script: Boolean = false)

    /**
     * Remplace le texte intégral du document [uri] (didChange en
     * synchronisation complète).
     *
     * @param uri identité du document.
     * @param texte NOUVEAU contenu intégral.
     * @param script vrai pour un `.kts`.
     */
    public fun modifierDocument(uri: String, texte: CharSequence, script: Boolean = false)

    /**
     * Ferme le document [uri] (didClose) : l'état du document est
     * abandonné, ses diagnostics ne sont plus calculables (l'hôte publie
     * la vidange LSP). Document inconnu : sans effet.
     *
     * @param uri identité du document.
     */
    public fun fermerDocument(uri: String)

    /**
     * Diagnostics COURANTS du document [uri] — recalculés depuis l'arbre
     * du dernier texte connu (jamais mis en cache : la liste est
     * immuable, le coût est celui d'un parcours d'arbre).
     *
     * @param uri identité du document.
     * @return les diagnostics ordonnés par position ; liste VIDE pour un
     *         document inconnu ou fermé (jamais d'exception).
     */
    public fun diagnostics(uri: String): List<DiagnosticKotlin>

    /**
     * Nombre de documents ouverts (observabilité, tests de fuite : la
     * session refermée ne retient rien).
     */
    public val nombreDocuments: Int

    /**
     * Ferme TOUT (shutdown/exit LSP, fermeture de la façade hôte) :
     * abandonne chaque document. Idempotente — un appel après close est
     * sans effet, pas une erreur.
     */
    public override fun close()
}
