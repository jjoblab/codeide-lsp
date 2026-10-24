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
@Suppress("TooManyFunctions")
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
     * ADR 0211 : quand le MOTEUR possède le nourrissage
     * ([MoteurLangageKotlin.definirClasspath] — passe en vol ou snapshot
     * couvrant), cet appel devient un BRANCHEMENT quasi gratuit : il
     * pose classpath/vue/racines et NE RE-LIT RIEN (le supplier de
     * connexion ne paie plus d'E/S lourde — demande D1 de CodeIDE).
     * Hors moteur nourricier (usages directs, packs anciens), le
     * contrat SYNCHRONE historique tient : la définition construit
     * l'index partagé ici et maintenant. Pendant une passe en vol, les
     * requêtes SOUS-LISENT honnêtement (diagnostics silencieux sur le
     * classpath — jamais de faux `kt.unresolved`).
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
     * Symboles de document (`textDocument/documentSymbol`, S1) —
     * structure hiérarchique de l'arbre Kotlin : classes, fonctions,
     * propriétés, objets, enum entries, typealias. L'outline éditeur
     * (bascule rapide vers une déclaration, pliage par membre) se
     * branche ici. Document inconnu/fermé : liste VIDE.
     *
     * @param uri identité du document.
     * @return la liste des symboles DE PREMIER NIVEAU (un fichier
     *         Kotlin peut en avoir plusieurs — classes, fonctions,
     *         propriétés top-level), chaque symbole portant ses
     *         [SymboleDocumentKotlin.enfants] (membres, paramètres…).
     */
    public fun symbolesDocument(uri: String): List<SymboleDocumentKotlin> = emptyList()

    /**
     * Plages pliables (`textDocument/foldingRange`, S2 — plan de
     * stabilisation LSP §3.2) : les régions REPLIABLES du document —
     * chaque corps à accolades (classe/objet, bloc, fonction, lambda,
     * when), la liste d'imports, chaque commentaire multi-ligne (KDoc,
     * bloc). La plage couvre la PAIRE d'accolades (ou le commentaire
     * entier) — un pli qui ne replie rien (mono-ligne) est filtré par
     * l'hôte, pas ici (le moteur ne connaît pas les lignes).
     *
     * Document inconnu/fermé : liste VIDE (jamais d'exception).
     *
     * Tolérance de version : défaut VIDE — un pack K3 face à un hôte
     * S2 dégrade honnêtement (pas de pliage, jamais d'exception).
     *
     * @param uri identité du document.
     * @return les plages en ordre du document (profondeur d'abord,
     *         dédupliquées).
     */
    public fun plagesPliables(uri: String): List<PlagePliableKotlin> = emptyList()

    /**
     * Aide à la signature (`textDocument/signatureHelp`, S1 — plan de
     * stabilisation LSP §3.2) : les candidates de l'appel ENGLOBANT
     * [decalage] — fonctions et constructeurs du projet (arité du
     * contrôle `kt.argumentCount`), membre englobant homonyme (arbre
     * vif), appelable de premier niveau du classpath (index Kotlin),
     * méthode du récepteur résolu pour un appel qualifié (vue JVM) ;
     * le paramètre sous le curseur et la première candidate d'arité
     * compatible sont préselectionnés.
     *
     * Conservateur (D3) : ce que la résolution ne prouve pas
     * n'apparaît pas — jamais de candidate inventée. Curseur hors
     * appel, appel sans candidate prouvée : `null`.
     *
     * Tolérance de version : défaut `null` — un pack K3 face à un hôte
     * S1 dégrade honnêtement (pas d'aide, jamais d'exception).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur dans le texte.
     * @return l'aide, `null` si rien n'est prouvé (sous-rapport D3) ou
     *         document inconnu/fermé.
     */
    public fun signature(uri: String, decalage: Int): AideSignatureKotlin? = null

    /**
     * Références du symbole visé à [decalage] (`textDocument/references`,
     * S2 — plan de stabilisation LSP §3.2) : les USAGES du symbole dans
     * les sources du projet (documents ouverts + fichiers des racines),
     * résolus CONSERVATEURMENT (D3 : une référence non prouvée n'est pas
     * rendue — les homonymes sans preuve de résolution restent hors
     * liste). Locales/paramètres : fichier déclarant seul (comme le
     * rename Java). Cibles classpath : liste vide (pas de source).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @param inclureDeclaration vrai pour ajouter l'emplacement du NOM
     *        déclaré en tête (miroir de `ReferenceContext.includeDeclaration`).
     * @return les emplacements (références, puis déclaration si
     *         demandée) ; liste VIDE sans cible prouvée ou document
     *         inconnu/fermé.
     */
    public fun references(
        uri: String,
        decalage: Int,
        inclureDeclaration: Boolean = false,
    ): List<EmplacementKotlin> = emptyList()

    /**
     * Surlignages du symbole visé à [decalage]
     * (`textDocument/documentHighlight`, S2) : TOUTES les occurrences du
     * symbole dans le document COURANT (déclaration en ÉCRITURE,
     * références en LECTURE — homonymes exclus, même preuve que
     * [references]). Affordance éditeur tactile : la sélection met en
     * évidence les usages.
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les occurrences en ordre du document ; liste VIDE sans
     *         cible prouvée ou document inconnu/fermé.
     */
    public fun surlignagesDocument(uri: String, decalage: Int): List<PlageSurligneeKotlin> = emptyList()

    /**
     * Plage renommable (`textDocument/prepareRename`, S2 — miroir du
     * « rename honnête » Java) : le NOM visé avec son placeholder EXACT,
     * ou `null` si rien n'est renommable au curseur (cible classpath,
     * curseur hors identifiant — la boîte de dialogue client refuse
     * proprement, jamais une plage devinée).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return la plage du nom + le nom courant, `null` si non renommable
     *         ou document inconnu/fermé.
     */
    public fun plageRenommable(uri: String, decalage: Int): PlageRenommableKotlin? = null

    /**
     * Occurrences À RENOMMER (`textDocument/rename`, S2) : le nom déclaré
     * + chaque référence PROUVÉE du symbole dans les sources du projet
     * (miroir borné du rename Java — les occurrences reviennent en
     * offsets, l'hôte compose le `WorkspaceEdit` avec le nouveau nom).
     *
     * Conservateur (D3) : cible absente/non prouvée → liste VIDE (l'hôte
     * la traduit en erreur typée, JAMAIS en édition vide silencieuse —
     * leçon I8 du LSP Java).
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les occurrences (déclaration incluse, ordonnées par
     *         fichier) ; liste VIDE sans cible renommable.
     */
    public fun renommage(uri: String, decalage: Int): List<EmplacementKotlin> = emptyList()

    /**
     * Éditions de ré-indentation du document ENTIER
     * (`textDocument/formatting`, L2 — plan 1.3.0, formateur BORNÉ,
     * ADR 0202) : ré-indentation canonique calculée depuis l'arbre
     * syntaxique — le CONTENU des lignes n'est jamais réécrit, seules
     * les plènes de tête décalées sont remplacées.
     *
     * Refus honnête (D3) : document avec marqueurs d'erreur de syntaxe
     * (édition en cours, fragment non reconnu) ⇒ liste VIDE — on ne
     * reformate pas ce qu'on ne comprend pas. Les lignes à l'intérieur
     * d'un jeton multi-ligne (chaîne brute, bloc de commentaire, KDoc)
     * ne sont JAMAIS touchées. Les lignes déjà justes ne produisent pas
     * d'édition (idempotence : reformater un document formaté ne change
     * rien).
     *
     * Tolérance de version : défaut VIDE — un pack S2 face à un hôte L2
     * dégrade honnêtement (pas de formatage, jamais d'exception).
     *
     * @param uri identité du document.
     * @param tailleIndentation nombre d'espaces par niveau (options du
     *        client ; défaut 4).
     * @return les éditions ordonnées par position ; liste VIDE pour un
     *         document inconnu/fermé ou refusé.
     */
    public fun formatage(
        uri: String,
        tailleIndentation: Int,
    ): List<EditionSourceKotlin> = emptyList()

    /**
     * Éditions de ré-indentation d'une PLAGE SEULE
     * (`textDocument/rangeFormatting`, L2) : mêmes règles que
     * [formatage] (borné, refus honnête, idempotent), mais seules les
     * lignes qui INTERSECTENT la plage [debutPlage, finPlage) sont
     * candidates — le reste du document ne bouge jamais (une édition
     * hors plage décalerait le buffer du client).
     *
     * @param uri identité du document.
     * @param debutPlage décalage de début de la plage demandée (inclus).
     * @param finPlage décalage de fin de la plage demandée (exclusif).
     * @param tailleIndentation nombre d'espaces par niveau.
     * @return les éditions ordonnées, TOUS dans la plage demandée ;
     *         liste VIDE pour un document inconnu/fermé ou refusé.
     */
    public fun formatage(
        uri: String,
        debutPlage: Int,
        finPlage: Int,
        tailleIndentation: Int,
    ): List<EditionSourceKotlin> = emptyList()

    /**
     * Définitions du TYPE du symbole visé (`textDocument/typeDefinition`,
     * L2 — plan 1.3.0) : l'emplacement de la déclaration du TYPE —
     * référence de type sous le curseur, type ÉCRIT d'une
     * locale/paramètre/propriété, ou constructeur appelé. Réutilise la
     * chaîne de résolution des définitions (même conservatisme).
     *
     * Conservateur (D3) : type non certain (littéral, cible classpath
     * sans source, curseur hors identifiant) ⇒ liste VIDE.
     *
     * Tolérance de version : défaut VIDE.
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les emplacements (souvent un seul) ; liste VIDE sans type
     *         prouvé dans les sources du projet.
     */
    public fun definitionsType(uri: String, decalage: Int): List<EmplacementKotlin> = emptyList()

    /**
     * Implémentations du type visé (`textDocument/implementation`, L2 —
     * plan 1.3.0) : les déclarations du PROJET dont les supertypes
     * ÉCRITS résolvent vers le type en contexte — une référence de type
     * sous le curseur, sinon la classe englobante. Les supertypes sont
     * résolus par les imports du fichier de CHAQUE candidate (une
     * homonymie de nom simple ne suffit PAS — preuve par FQCN).
     *
     * Conservateur (D3) : héritiers du classpath non listés (pas de
     * source) ; type non prouvé ⇒ liste VIDE.
     *
     * Tolérance de version : défaut VIDE.
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les emplacements des NOMS déclarants des types dérivés,
     *         ordonnés (document courant en tête) ; liste VIDE sans
     *         type prouvé ou sans héritier.
     */
    public fun implementations(uri: String, decalage: Int): List<EmplacementKotlin> = emptyList()

    /**
     * Cibles GO TO « Super » (`textDocument/superDefinition`, ADR
     * 0210 — méthode LSP personnalisée, miroir du LSP Java) : pour un
     * MEMBRE au curseur (déclaration outrepassante ou usage résolu),
     * le même-nommé dans chaque supertype TRANSITIF projet du type
     * déclarant (libellé `« name  ·  Super »`) ; sinon les supertypes
     * DIRECTS du type en contexte — une référence de type sous le
     * curseur, à défaut la classe englobante (libellé
     * `« Simple  ·  pkg »`). Chaque supertype est résolu par les
     * IMPORTS du fichier déclarant (preuve FQCN, jamais d'homonymie).
     *
     * Conservateur (D3) : supertypes du classpath sans source
     * exclus ; membre non prouvé ⇒ liste VIDE (l'éditeur ne montre
     * pas l'option Super, jamais de faux jalon).
     *
     * Tolérance de version : défaut VIDE — un pack antérieur à
     * l'ADR 0210 dégrade honnêtement.
     *
     * @param uri identité du document.
     * @param decalage décalage du curseur.
     * @return les cibles (URI + plage du nom + libellé picker), triées
     *         par libellé ; liste VIDE sans preuve.
     */
    public fun superDefinition(uri: String, decalage: Int): List<CibleNavigationKotlin> = emptyList()

    /**
     * Indices en ligne d'une plage (`textDocument/inlayHint`, L2 —
     * plan 1.3.0) : noms de paramètres aux appels de la plage
     * (arguments POSITIONNELS seulement — un argument nommé porte déjà
     * son nom) et types CERTAINS des déclarations sans type écrit de
     * la plage (littéraux et constructeurs — jamais un type inféré
     * deviné).
     *
     * Conservateur (D3) : un appel dont la candidate n'est pas prouvée
     * ne rend AUCUN indice de paramètre (réutilise l'extraction de
     * l'aide à la signature) ; une initialisation dont le type n'est
     * pas certain ne rend AUCUN indice de type.
     *
     * Tolérance de version : défaut VIDE.
     *
     * @param uri identité du document.
     * @param debutPlage décalage de début de la plage demandée (inclus).
     * @param finPlage décalage de fin de la plage demandée (exclusif).
     * @return les indices ordonnés par position ; liste VIDE pour un
     *         document inconnu/fermé ou une plage vide.
     */
    public fun indices(
        uri: String,
        debutPlage: Int,
        finPlage: Int,
    ): List<IndiceEnLigneKotlin> = emptyList()

    /**
     * Symboles de l'ESPACE de travail (`workspace/symbol`, L2 — plan
     * 1.3.0) : les déclarations NOMMÉES du projet (documents ouverts +
     * racines sources, multi-fichiers) dont le nom RÉPOND à la requête
     * (contient, insensible à la casse) — la palette « aller au
     * symbole » de l'éditeur en est le consommateur.
     *
     * Borné : plafond de résultats (le balayage des racines est celui
     * du scan projet, déjà plafonné) ; les locales/paramètres ne
     * remontent JAMAIS (des centaines par fichier : du bruit pour une
     * palette).
     *
     * Tolérance de version : défaut VIDE.
     *
     * @param requête texte de recherche (vide = tout, plafonné).
     * @return les symboles ordonnés par nom ; liste VIDE sans candidat.
     */
    public fun symbolesEspace(requete: String): List<SymboleEspaceKotlin> = emptyList()

    /**
     * Chaînes de sélection (`textDocument/selectionRange`, L2 — plan
     * 1.3.0) : pour CHAQUE position demandée, la chaîne des nœuds
     * englobants (du plus profond au fichier entier) — l'expansion
     * progressive de la sélection. Chaque maillon englobe STRICTEMENT
     * le précédent ; une position hors arbre rend la chaîne du
     * fichier seul.
     *
     * Tolérance de version : défaut VIDE — un pack S2 face à un hôte
     * L2 dégrade honnêtement.
     *
     * @param uri identité du document.
     * @param decalages les positions demandées (une chaîne par
     *        position, DANS L'ORDRE de la requête).
     * @return une chaîne par position ; liste VIDE pour un document
     *         inconnu/fermé.
     */
    public fun selections(
        uri: String,
        decalages: List<Int>,
    ): List<ChaineSelectionKotlin> = emptyList()

    /**
     * Fonctions `@Preview @Composable` du document (preview Compose C1 —
     * PREVIEW.md §4.4 : la découverte vit dans le moteur de langue, le
     * rendu Compose vit chez le consommateur ; seul le modèle NEUTRE
     * [PreviewKotlin] traverse la frontière D6 — jamais l'arbre, jamais
     * un type Compose).
     *
     * La preuve est par FQCN : `@Preview` résout à
     * `androidx.compose.ui.tooling.preview.Preview` et `@Composable` à
     * `androidx.compose.runtime.Composable` PAR LES IMPORTS du fichier
     * (un homonyme sans import est ignoré — conservateur D3). Le corps
     * est réduit à un ARBRE D'APPELS clos : appels directs + lambda
     * final de contenu, composables du PROJET inlinés (cycles et
     * budgets coupés honnêtement), toute autre expression rendue
     * [jo.lspkotlin.api.ExpressionNonInterpreteeKotlin].
     *
     * Tolérance de version : défaut VIDE — un pack antérieur C1 face à
     * un hôte C1 dégrade honnêtement (pas de preview, jamais
     * d'exception).
     *
     * @param uri identité du document.
     * @return les previews dans l'ordre du document ; liste VIDE pour
     *         un document inconnu/fermé ou sans `@Preview @Composable`.
     */
    public fun previews(uri: String): List<PreviewKotlin> = emptyList()

    /**
     * Signale des modifications de fichiers EXTERNES (lot L1 du plan
     * 1.3.0 — le surveillant de l'hôte pousse `didChangeWatchedFiles`
     * au serveur, qui rafraîchit SES sources avant de republier les
     * diagnostics) : le scan des racines sources est INVALIDÉ puis
     * ré-amorcé (incrémental par mtime — un fichier inchangé n'est
     * jamais re-parsé, le coût est un listage). Les déclarations des
     * documents OUVERTS dominent de toute façon (arbres vivants).
     *
     * Tolérance de version : défaut NO-OP — un pack K3 face à un hôte
     * L1 dégrade honnêtement (republication seule, jamais d'exception).
     */
    public fun rafraichirSources() {}

    /**
     * Ferme TOUT (shutdown/exit LSP, fermeture de la façade hôte) :
     * abandonne chaque document. Idempotente — un appel après close est
     * sans effet, pas une erreur.
     */
    public override fun close()
}
