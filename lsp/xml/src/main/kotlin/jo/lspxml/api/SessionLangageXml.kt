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
     * Plage renommable à [decalage] (`textDocument/prepareRename`, S3
     * — plan de stabilisation LSP §3.3 « rename borné des entrées
     * values ») : le nom d'une RESSOURCE projet, sous deux formes
     * cibles — référence `@type/nom` / `?attr/nom` / `@+type/nom`
     * dans une valeur d'attribut, ou entrée déclarante de `values`
     * (valeur de son attribut `name`). Les références FRAMEWORK
     * (`@android:…`) ne sont JAMAIS renommables (ressource système,
     * pas de source projet) ; les ressources PAR FICHIER (layout,
     * drawable…) non plus (le renommage serait celui du FICHIER,
     * hors bornage texte).
     *
     * @return la plage du NOM + le placeholder (nom courant), `null`
     *         si rien n'est renommable (sous-rapport honnête X4 —
     *         l'éditeur ne propose pas le geste).
     */
    public fun plageRenommable(uri: String, decalage: Int): PlageRenommableXml? = null

    /**
     * Occurrences à renommer (`textDocument/rename`, S3 — BORNÉ, miroir
     * du « rename honnête » Java/Kotlin) : la DÉCLARATION (entrées
     * `values` — chaque fichier déclarant, tampon ouvert PUIS disque)
     * et les références `@type/nom` / `?attr/nom` des documents
     * OUVERTS (bornage assumé : un fichier fermé non déclarant ne
     * passe PAS — jamais d'édition à l'aveugle sur un texte non vu).
     *
     * AUCUN échec silencieux côté hôte : liste vide = erreur typée
     * RequestFailed (-32803), l'éditeur distingue « rien à renommer »
     * d'un succès vide.
     *
     * @return les emplacements (uri + décalages), déclaration
     *         d'abord, références ensuite par document ; liste VIDE
     *         sans cible renommable au curseur.
     */
    public fun renommage(uri: String, decalage: Int): List<EmplacementXml> = emptyList()

    /**
     * Formatage du document (`textDocument/formatting`, S3 — formateur
     * XML borné) : RÉ-INDENTATION canonique seule — la profondeur
     * logique de chaque ligne vient du DOM (ancêtres de l'élément
     * qu'elle ouvre/ferme/continue, texte = profondeur de contenu),
     * le CONTENU des lignes n'est JAMAIS touché (aucun re-ligne, aucune
     * retouche d'attribut ni de texte — les commentaires, CDATA et
     * entités restent octet-pour-octet).
     *
     * Refus honnête : document cassé (reprises du parseur, balise non
     * fermée) = liste VIDE (on ne reformate pas ce qu'on ne comprend
     * pas — X4).
     *
     * @param uri identité du document.
     * @param tailleIndentation espaces par niveau (convention Android
     *        : 4).
     * @return les éditions de REMPLACEMENT du blanc de tête de chaque
     *         ligne à réindenter (jamais superposées, ordonnées) ;
     *         liste VIDE si rien à changer ou refus.
     */
    public fun formatter(uri: String, tailleIndentation: Int = 4): List<EditionXml> = emptyList()

    /**
     * Signale des modifications de fichiers EXTERNES (lot L1 du plan
     * 1.3.0 — le surveillant de l'hôte pousse `didChangeWatchedFiles`
     * au serveur, qui rafraîchit SON index avant de republier les
     * diagnostics) : l'index des ressources est RE-SCANNÉ (delta
     * mtime — un fichier inchangé n'est jamais re-parsé, le coût est
     * un listage) : une ressource créée/modifiée hors éditeur devient
     * visible à la prochaine publication.
     *
     * Tolérance de version : défaut NO-OP — un pack XE1 face à un hôte
     * L1 dégrade honnêtement (republication seule, jamais d'exception).
     */
    public fun rafraichirSources() {}

    /**
     * Ferme TOUT (shutdown/exit LSP, fermeture de la façade hôte) :
     * abandonne chaque document, vide l'index projet. Idempotente.
     */
    public override fun close()
}
