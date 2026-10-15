package jo.lspxml.api

/**
 * Session de langue du moteur XML (XE1, LSP_XML.md §4.1 « session ») —
 * le contrat de session TOUT ENTIER derrière le SPI (miroir de
 * `SessionLangageKotlin` : documents qui entrent, diagnostics neutres
 * et services qui sortent — le moteur ne connaît pas LSP4J, l'hôte ne
 * connaît pas le parseur).
 *
 * Cycle de vie : une session par CONNEXION LSP (l'hôte crée un serveur
 * par connexion cel-lsp, il lui fournit une session neuve issue de
 * [MoteurLangageXml.creerSession]) ; la session vit la vie de la
 * connexion — [fermerDocument] au didClose, [close] au shutdown.
 *
 * Tolérance (X1) : aucune méthode ne lève sur du texte invalide — le
 * parseur est tolérant par construction, les erreurs voyagent comme
 * diagnostics. Réouvrir un document ouvert le REMPLACE ; modifier un
 * document inconnu équivaut à l'ouvrir ; fermer un document inconnu
 * est sans effet.
 *
 * ÉVOLUTION DE CONTRAT (XE2/XE3/XE4) : les méthodes de services ont des
 * IMPLÉMENTATIONS PAR DÉFAUT vides — un pack XE1 face à un hôte XE3
 * dégrade honnêtement (pas de complétion, jamais d'exception de
 * version), et les doubles de test n'implémentent que ce qu'ils
 * éprouvent (tolérance de version du pack, même patron que Kotlin).
 */
@Suppress("TooManyFunctions")
public interface SessionLangageXml : AutoCloseable {
    /**
     * Ouvre (ou remplace) le document [uri] avec le texte intégral
     * [texte] (synchronisation LSP COMPLÈTE — le cadrage vise la
     * re-parse < 10 ms d'un fichier ≤ 1 500 lignes, le parse complet
     * EST le budget incrémental à cette échelle).
     *
     * @param uri identité du document (celle de l'hôte — le genre
     *        layout/values/manifest s'en déduit, X1).
     * @param texte contenu intégral (aucune hypothèse de validité).
     */
    public fun ouvrirDocument(uri: String, texte: CharSequence)

    /** Remplace le texte intégral du document [uri] (didChange). */
    public fun modifierDocument(uri: String, texte: CharSequence)

    /**
     * Ferme le document [uri] (didClose) : l'état est abandonné, ses
     * diagnostics ne sont plus calculables. Document inconnu : sans
     * effet.
     */
    public fun fermerDocument(uri: String)

    /**
     * Diagnostics COURANTS du document [uri] — recalculés depuis le
     * DOM du dernier texte connu (jamais mis en cache : le coût est
     * celui d'un parcours d'arbre).
     *
     * @return les diagnostics ordonnés par position ; liste VIDE pour
     *         un document inconnu ou fermé (jamais d'exception).
     */
    public fun diagnostics(uri: String): List<DiagnosticXml>

    /** Nombre de documents ouverts (tests de fuite : rien n'orpheline). */
    public val nombreDocuments: Int

    /**
     * Définit (ou REMPLACE) le classpath de la session (XE2/XE3) —
     * poussé par la façade hôte après chaque sync utile et depuis le
     * modèle persisté à la réouverture. Tolérant : composants absents
     * (`null`) = régime dégradé ; un appel identique est un NO-OP
     * chaud. Après [close] : sans effet.
     */
    public fun definirClasspath(classpath: ClasspathXml) {}

    /**
     * Complétion à [decalage] dans le document [uri] (XE2/XE3) — nom
     * de balise (catalogue plateforme + vues du projet), nom
     * d'attribut (styleable + héritage + xmlns déclarés), valeur
     * (énumérations du catalogue, références `@type/`, dimensions et
     * couleurs de l'index projet + framework).
     *
     * Conservateur (X4) : ce que le catalogue et l'index ne prouvent
     * pas n'apparaît pas — jamais de candidat inventé.
     *
     * @return les propositions triées par pertinence ; liste VIDE pour
     *         un document inconnu, une position muette ou sans
     *         candidat.
     */
    public fun completion(uri: String, decalage: Int): List<PropositionCompletionXml> = emptyList()

    /**
     * Survol à [decalage] dans le document [uri] (XE3) — balise (classe
     * de vue), attribut (format + valeurs), référence `@type/nom` ou
     * `?attr/` (déclarant projet/framework + format).
     *
     * @return le contenu, `null` si rien n'est résolu (sous-rapport X4).
     */
    public fun survol(uri: String, decalage: Int): ContenuSurvoleXml? = null

    /**
     * Définitions à [decalage] dans le document [uri] (XE3/XE5) — la
     * cible saute au `res/values` DÉCLARANT la référence (projet
     * uniquement : les ressources framework n'ont pas de source
     * locale, le survol porte leur origine), ou au fichier de la vue
     * du projet pour une balise de classe projet.
     *
     * @return les emplacements (souvent un seul) ; liste VIDE si la
     *         cible n'est pas dans le projet.
     */
    public fun definitions(uri: String, decalage: Int): List<EmplacementXml> = emptyList()

    /**
     * Correctifs d'un diagnostic (XE4) — le moteur dérive les éditions
     * de la position [decalageDuDiagnostic] (insertion `xmlns:android`
     * sur la racine, renommage « did you mean » d'une balise inconnue).
     * L'hôte traduit en CodeAction LSP : il possède le texte, le
     * moteur possède la sémantique (miroir de `importsCandidats`
     * Kotlin — renommé : le moteur rend des ÉDITIONS complètes).
     *
     * @param uri identité du document.
     * @param decalageDuDiagnostic décalage du DÉBUT du diagnostic
     *        ciblé (la plage vient de l'hôte).
     * @return les correctifs triés par pertinence ; liste VIDE sans
     *         correctif (jamais d'exception).
     */
    public fun correctifs(uri: String, decalageDuDiagnostic: Int): List<CorrectifXml> = emptyList()

    /**
     * Symboles de document (`textDocument/documentSymbol`, S1) —
     * structure hiérarchique du DOM : racine → enfants → petits-enfants.
     * L'outline éditeur (bascule rapide vers une balise, pliage par
     * section) se branche ici. Document inconnu/fermé : liste VIDE.
     *
     * @param uri identité du document.
     * @return la liste des symboles RACINES (un document XML en a
     *         canoniquement une ; les sœurs de reprise vivent dans la
     *         liste), chaque racine portant ses [SymboleDocumentXml.enfants].
     */
    public fun symbolesDocument(uri: String): List<SymboleDocumentXml> = emptyList()

    /**
     * Plages pliables (`textDocument/foldingRange`, XP11 — plan de
     * stabilisation LSP §3.3 S2) : chaque balise fermée (fermante
     * explicite OU implicite de reprise — le DOM porte la plage depuis
     * XP14) est pliable, du début de l'ouvrante à la fin de la
     * fermante ; les AUTO-FERMANTES (`<…/>`) et les éléments en édition
     * ne le sont JAMAIS (rien à replier).
     *
     * Document inconnu/fermé : liste VIDE (jamais d'exception).
     *
     * @param uri identité du document.
     * @return les plages en ordre du document (profondeur d'abord).
     */
    public fun plagesPliables(uri: String): List<PlagePliableXml> = emptyList()

    /**
     * Ferme TOUT (shutdown/exit LSP, fermeture de la façade hôte) :
     * abandonne chaque document, vide l'index projet. Idempotente.
     */
    public override fun close()
}
