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
 * cela, elle est AUTOCONTENUE : le classpath v8 et l'index d'appelables
 * lui sont POUSSÉS par la façade ([definirClasspath], K3) — l'état de
 * classpath est partagé par le moteur (index disque par empreinte, ADR
 * 0111), les documents restent par session.
 *
 * Synchronisation : les appels peuvent venir du pool LSP4J de l'hôte —
 * l'implémentation sérialise (verrou interne) ; les opérations sont
 * courtes (parse complet d'un fichier, requêtes index en mémoire).
 *
 * Tolérance (D3) : aucune méthode ne lève sur du texte invalide — la
 * grammaire vendorée est tolérante, l'arbre est toujours construit, les
 * erreurs voyagent comme diagnostics. Réouvrir un document ouvert le
 * REMPLACE ; modifier un document inconnu équivaut à l'ouvrir (tolérant,
 * l'hôte garantit l'ordre mais le moteur ne s'y fie pas) ; fermer un
 * document inconnu est sans effet.
 *
 * ÉVOLUTION DE CONTRAT (K3) : les méthodes de symboles ont des
 * IMPLÉMENTATIONS PAR DÉFAUT vides — un pack K2 chargé face à un hôte
 * K3 dégrade honnêtement (pas de complétion, pas de survol — jamais
 * d'exception de version), et les doubles de test n'implémentent que
 * ce qu'ils éprouvent. C'est la tolérance de version du pack : le
 * descripteur (versionMinimaleApp) borne le passé, les défauts
 * supportent le présent.
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
     * Définit (ou REMPLACE) le classpath de la session (K3) — poussé par
     * la façade hôte après chaque sync utile et depuis le modèle
     * persisté à la réouverture. Tolérant : composants absents (`null`)
     * = régime dégradé (symboles projet seuls) ; entrées inexistantes
     * filtrées par le moteur ; répéter un appel identique est un NO-OP
     * chaud (segments par empreinte, jamais redécodés).
     *
     * Après [close] : sans effet (la session ne retient rien).
     */
    public fun definirClasspath(classpath: ClasspathKotlin) {}

    /**
     * Complétion à [decalage] dans le document [uri] (K3) — membres après
     * `.`/`?.` (résolution conservatrice du récepteur), sinon noms en
     * portée : locales + membres de l'englobante + premier niveau du
     * fichier + déclarations du projet + types du classpath + appelables
     * de premier niveau (index Kotlin) + mots-clés.
     *
     * Conservateur (D3) : ce que la résolution ne prouve pas n'apparaît
     * pas — jamais de proposition inventée.
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur dans le texte.
     * @return les propositions triées par pertinence (cap ~100) ; liste
     *         VIDE pour un document inconnu ou sans candidat.
     */
    public fun completion(uri: String, decalage: Int): List<PropositionCompletionKotlin> = emptyList()

    /**
     * Survol à [decalage] dans le document [uri] (K3) — signature de la
     * déclaration visée : locale/paramètre/membre du projet (arbre) ou
     * membre/type du classpath (vue JVM traduite + appelables Kotlin).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return le contenu, `null` si rien n'est résolu (sous-rapport D3).
     */
    public fun survol(uri: String, decalage: Int): ContenuSurvoleKotlin? = null

    /**
     * Définitions à [decalage] dans le document [uri] (K3) — emplacements
     * du NOM déclaré, projet uniquement (fichier courant + fichiers des
     * racines sources) : les cibles du CLASSPATH n'ont pas de source
     * attachée à K3 (le survol porte leur FQN — sources jars = K4+, D3
     * documenté).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les emplacements (souvent un seul) ; liste VIDE si la cible
     *         n'est pas dans les sources du projet.
     */
    public fun definitions(uri: String, decalage: Int): List<EmplacementKotlin> = emptyList()

    /**
     * FQCN CANDIDATS À L'IMPORT d'un nom non résolu (K4 — quick-fixes
     * d'import : « un correctif par FQN candidate, position triée »).
     *
     * Le moteur rend les FQCN du classpath portant EXACTEMENT le nom de
     * la référence à [decalage] (vue `nomsParPrefixe`, déjà importés
     * exclus) ; l'HÔTE dérive l'édition LSP (additionalTextEdits /
     * WorkspaceEdit — il possède le texte et l'heuristique d'insertion
     * après le dernier import, miroir du LSP Java). Une référence RÉSOLUE
     * ne rend AUCUN candidat (rien à importer) ; un document inconnu
     * rend vide.
     *
     * Tolérance de version : défaut VIDE — un pack K3 face à un hôte K4
     * dégrade honnêtement (pas de quick-fix, jamais d'exception).
     *
     * @param uri identité du document.
     * @param decalage décalage de la référence (plage du diagnostic
     *        `kt.unresolved` côté hôte).
     * @return les FQCN candidates, ordre du classpath (le premier a la
     *         préséance) ; liste VIDE sans candidat.
     */
    public fun importsCandidats(uri: String, decalage: Int): List<String> = emptyList()

    /**
     * Ferme TOUT (shutdown/exit LSP, fermeture de la façade hôte) :
     * abandonne chaque document. Idempotente — un appel après close est
     * sans effet, pas une erreur.
     */
    public override fun close()
}
