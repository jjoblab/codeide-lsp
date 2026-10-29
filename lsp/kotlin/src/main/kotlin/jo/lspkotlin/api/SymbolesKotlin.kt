package jo.lspkotlin.api

/**
 * Types NEUTRES des requêtes de symboles K3 (LSP_KOTLIN.md §4.4 —
 * complétion, hover, définition) : le moteur n'expose AUCUN type LSP4J
 * (condition D6 du découplage « pack langue »), l'hôte traduit vers le
 * protocole (positions, gravités, kinds LSP — mêmes soins que les
 * diagnostics K2).
 *
 * Les positions sont des DÉCALAGES de caractères dans le texte source —
 * la conversion ligne/colonne UTF-16 appartient à l'hôte, qui possède
 * le document LSP.
 */

/**
 * Proposition de complétion (une entrée de la liste `textDocument/
 * completion` après traduction LSP).
 *
 * @property libelle texte affiché/inséré par défaut (fonctions :
 *           `nom()` ; le détail porte la signature complète).
 * @property genre genre du symbole (traduit en CompletionItemKind).
 * @property detail signature ou type, affiché après le libellé
 *           (`fun creer(nom: String): Vue`, `android.widget.TextView`).
 * @property texteTri clé de tri encodée par le moteur (pertinence
 *           décroissante + longueur + libellé — le client LSP trie par
 *           sortText seul, le tie-break voyage dans la clé).
 * @property fqnImport FQN à importer quand le candidat est hors de
 *           portée (`android.widget.TextView`) — l'hôte dérive
 *           l'auto-import (additionalTextEdits LSP) ; `null` pour les
 *           symboles déjà en portée.
 * @property texteInsertion texte à INSÉRER à l'acceptation quand il
 *           diffère du libellé affiché (vague 1 de la complétion
 *           contextuelle : « nom = » affiché, `nom = ` inséré — le
 *           séparateur voyage avec le candidat, l'utilisateur n'a plus
 *           qu'à taper la valeur). `null` = le client insère le
 *           libellé (contrat historique, miroir du `insertText` du
 *           moteur Java).
 */
public data class PropositionCompletionKotlin(
    public val libelle: String,
    public val genre: GenreCompletionKotlin,
    public val detail: String?,
    public val texteTri: String,
    public val fqnImport: String? = null,
    public val texteInsertion: String? = null,
)

/** Genre d'une proposition de complétion (→ CompletionItemKind LSP). */
public enum class GenreCompletionKotlin {
    /** Classe / data class. */
    CLASSE,

    /** Interface. */
    INTERFACE,

    /** Classe d'énumération / entrée d'énumération. */
    ENUM,

    /** Objet / objet compagnon. */
    OBJET,

    /** Fonction / constructeur. */
    FONCTION,

    /** Propriété / champ. */
    PROPRIETE,

    /** Variable locale / paramètre. */
    VARIABLE,

    /** Mot-clé du langage. */
    MOT_CLE,

    /** Paquet (segment de nom qualifié). */
    PAQUET,

    /** Alias de type. */
    ALIAS,
}

/**
 * Correctif « implémenter les membres manquants » (chantier P1-C —
 * ADR 0229) : l'édition CONCRÈTE qui insère les stubs des membres
 * abstraits non implémentés d'une classe/objet, calculée sur le texte
 * courant. L'hôte la traduit en `CodeAction` LSP (titre plurielisé,
 * `WorkspaceEdit`) — le moteur ne connaît ni LSP ni le titre.
 *
 * @property debut décalage de début de l'édition (inclus) —
 *           insertion quand `debut == fin`.
 * @property fin décalage de fin de l'édition (exclusif).
 * @property texte le texte de remplacement (stubs indentés, lignes
 *           complètes avec leurs sauts).
 * @property nombre le nombre de stubs rendus (le titre de l'action côté
 *           hôte : « le membre manquant » / « les N membres manquants »).
 */
public data class CorrectifMembresManquantsKotlin(
    public val debut: Int,
    public val fin: Int,
    public val texte: String,
    public val nombre: Int,
)

/**
 * Genre d'un correctif d'EXPRESSION (chantier 3, ADR 0232 — wrap /
 * cast / surround sur les diagnostics PROUVÉS `kt.unsafeNullable` et
 * `kt.typeMismatch`). Le moteur rend l'édition CONCRÈTE ; l'hôte
 * dérive le TITRE du genre (le moteur ne connaît ni LSP ni libellé).
 */
public enum class GenreCorrectifKotlin {
    /**
     * Remplacer le `.` de l'accès non sûr par `?.` (`s.length` →
     * `s?.length`) — le correctif le plus sûr : l'appel devient
     * conditionnel, jamais d'exception.
     */
    APPEL_SUR,

    /**
     * Encadrer le récepteur de `!!` (`s.length` → `s!!.length`) — le
     * « cast » de nullité T? → T : l'assertion non nulle lève si la
     * valeur est nulle, le type écrit est honoré tel quel.
     */
    ASSERTION_NON_NULLE,

    /**
     * Entourer l'instruction englobante d'un test de nullité
     * (`s.length` → `if (s != null) { s.length }`) — le correctif
     * STRUCTUREL : la garde protège TOUT le bloc.
     */
    TEST_NULLITE,

    /**
     * Changer le TYPE ÉCRIT pour le représentant de la famille du
     * littéral (`val x: Int = "5"` → `val x: String = "5"`) — toujours
     * légal : un littéral de sa propre famille affecte son type.
     */
    CHANGEMENT_TYPE,

    /**
     * Encadrer l'initialiseur d'une conversion standard (`"5"` →
     * `"5".toInt()`) — uniquement les conversions du LANGAGE (stdlib
     * Kotlin), jamais un cast `as` (illégal entre familles disjointes,
     * le correctif ne doit pas produire du code cassé, D3).
     */
    CONVERSION,
}

/**
 * Correctif d'expression CONCRET (chantier 3, ADR 0232) — une édition
 * de texte calculée sur l'arbre COURANT (le moteur re-prouve à la
 * requête : un diagnostic périmé ne produit AUCUN correctif, la preuve
 * est structurale). L'hôte la traduit en `CodeAction` LSP (titre dérivé
 * du genre, `WorkspaceEdit`) — miroir de
 * [CorrectifMembresManquantsKotlin].
 *
 * @property genre la nature du correctif (→ titre côté hôte).
 * @property debut décalage de début de l'édition (inclus) — insertion
 *           quand `debut == fin`.
 * @property fin décalage de fin de l'édition (exclusif).
 * @property texte le texte de remplacement (`?`, `!!`, `if (s != null)
 *           { … }`, `String`, `.toInt()`).
 * @property argument l'argument du TITRE côté hôte : le nom du
 *           récepteur ([GenreCorrectifKotlin.TEST_NULLITE]), le nom du
 *           type cible ([GenreCorrectifKotlin.CHANGEMENT_TYPE]) ou
 *           l'appel de conversion ([GenreCorrectifKotlin.CONVERSION]) ;
 *           `null` quand le genre se suffit.
 */
public data class CorrectifTexteKotlin(
    public val genre: GenreCorrectifKotlin,
    public val debut: Int,
    public val fin: Int,
    public val texte: String,
    public val argument: String? = null,
)

/**
 * Contenu de survol (`textDocument/hover`) — texte simple (même forme
 * que le LSP Java : le client rend une chaîne, pas du markdown riche ;
 * la richesse arrive avec le mode profond, jamais par devinette).
 *
 * @property valeur texte du survol (`fun MutableList<T>.triRapide()`,
 *           `class android.widget.TextView`, `val nom: String`).
 * @property documentation KDoc RENDU de la déclaration visée (S2 —
 *           sections libres + tags `@param`/`@return`… en texte
 *           lisible, extrait du commentaire DOC_COMMENT qui précède
 *           la déclaration, parsé par le KDocParser vendoré) ; `null`
 *           quand la déclaration n'a pas de KDoc. ADR 0207 : la KDoc
 *           accompagne AUSSI les sites d'usage quand la déclaration
 *           vit dans le document courant (arbre vif, aucun re-parse) ;
 *           les déclarations du SCAN (autres fichiers) gardent le
 *           survol signature seule — re-parser un fichier tiers pour
 *           son doc n'est pas un coût K3, choix documenté.
 */
public data class ContenuSurvoleKotlin(
    public val valeur: String,
    public val documentation: String? = null,
)

/**
 * Plage pliable (`textDocument/foldingRange`, S2 — plan de
 * stabilisation LSP §3.2) — décalages dans le texte du document, un
 * pli par région repliable : corps à accolades (classe/objet, bloc,
 * fonction, lambda, when), liste d'imports, commentaire multi-ligne
 * (KDoc / bloc).
 *
 * @property debut décalage de début de la région (inclus).
 * @property fin décalage de fin de la région (exclusif).
 */
public data class PlagePliableKotlin(
    public val debut: Int,
    public val fin: Int,
)

/**
 * Emplacement d'une définition (`textDocument/definition`) — URI + plage
 * du NOM déclarant (offsets dans le texte du document CIBLE).
 *
 * @property uri URI du document porteur (celle de l'hôte — `file://`
 *           FUSE en production, fixture en test).
 * @property debut décalage de début du nom déclaré (inclus).
 * @property fin décalage de fin du nom déclaré (exclusif).
 */
public data class EmplacementKotlin(
    public val uri: String,
    public val debut: Int,
    public val fin: Int,
)

/**
 * Pertinences de tri K3 (décroissantes) — le local bat le membre, le
 * membre bat le projet, le projet bat le classpath, le mot-clé ferme la
 * marche. Encodées dans [PropositionCompletionKotlin.texteTri] par le
 * moteur (le client LSP trie par sortText seul).
 */
public object PertinencesKotlin {
    /** Locale/paramètre en portée. */
    public const val LOCALE: Int = 900

    /** Membre du récepteur résolu. */
    public const val MEMBRE: Int = 800

    /** Déclaration du projet (même fichier/package). */
    public const val PROJET: Int = 700

    /** Type ou appelable du classpath (import nécessaire). */
    public const val CLASSPATH: Int = 500

    /** Mot-clé du langage. */
    public const val MOT_CLE: Int = 100
}

/**
 * Symbole de document (`textDocument/documentSymbol`, S1) — un nœud
 * HIÉRARCHIQUE de l'arbre Kotlin (classe, fonction, propriété…),
 * miroir AST → outline éditeur. La traduction vers `DocumentSymbol`
 * LSP (kinds, ranges UTF-16, enfants imbriqués) appartient à l'hôte.
 *
 * Plages : décalages de caractères dans le texte source (la conversion
 * ligne/colonne UTF-16 appartient à l'hôte, comme pour
 * `EmplacementKotlin`).
 *
 * @property nom nom de la déclaration (`Foo`, `bar`, `x`).
 * @property detail détail optionnel — signature courte pour les
 *           fonctions (`(a: Int): String`), supertypes pour les classes
 *           (`: Base`), type pour les propriétés (`: String`). `null`
 *           si non pertinent.
 * @property genre genre du symbole (→ SymbolKind LSP côté hôte).
 * @property debutNom décalage de début du NOM (sélection fine —
 *           curseur « aller à »).
 * @property finNom décalage de fin du NOM (exclusif).
 * @property debutTotal décalage de début de la DÉCLARATION COMPLÈTE
 *           (du `class`/`fun`/`val` au `}` fermant).
 * @property finTotal décalage de fin de la DÉCLARATION COMPLÈTE (exclusif).
 * @property enfants symboles enfants (membres d'une classe, entrées
 *           d'énumération…). Récursif — l'arbre LSP == l'arbre AST.
 */
public data class SymboleDocumentKotlin(
    public val nom: String,
    public val detail: String?,
    public val genre: GenreSymboleKotlin,
    public val debutNom: Int,
    public val finNom: Int,
    public val debutTotal: Int,
    public val finTotal: Int,
    public val enfants: List<SymboleDocumentKotlin>,
)

/** Genre d'un symbole de document Kotlin (→ SymbolKind LSP côté hôte). */
public enum class GenreSymboleKotlin {
    /** Classe (data / abstract / sealed / enum class). */
    CLASSE,

    /** Interface. */
    INTERFACE,

    /** Objet (compagnon compris). */
    OBJET,

    /** Fonction (extension comprise). */
    FONCTION,

    /** Propriété (extension comprise). */
    PROPRIETE,

    /** Entrée d'énumération. */
    ENTREE_ENUM,

    /** Alias de type. */
    ALIAS,
}

/** Genre d'un surlignage d'occurrence (→ DocumentHighlightKind LSP). */
public enum class GenreSurlignageKotlin {
    /** LECTURE du symbole (référence — DocumentHighlightKind.Read). */
    LECTURE,

    /** ÉCRITURE du symbole (nom déclaré — DocumentHighlightKind.Write). */
    ECRITURE,
}

/**
 * Surlignage d'une occurrence du symbole au curseur
 * (`textDocument/documentHighlight`, S2 — plan de stabilisation LSP
 * §3.2) — décalages dans le texte du document COURANT.
 *
 * @property debut décalage de début de l'occurrence (inclus).
 * @property fin décalage de fin de l'occurrence (exclusif).
 * @property genre nature de l'occurrence (lecture / écriture) —
 *           traduit en DocumentHighlightKind côté hôte.
 */
public data class PlageSurligneeKotlin(
    public val debut: Int,
    public val fin: Int,
    public val genre: GenreSurlignageKotlin,
)

/**
 * Plage renommable (`textDocument/prepareRename`, S2 — miroir du
 * « rename honnête » du LSP Java) : le nom déclaré visé, avec le
 * placeholder EXACT (le nom courant — la boîte de dialogue s'ouvre
 * pré-remplie, jamais devinée).
 *
 * @property debut décalage de début du nom (inclus).
 * @property fin décalage de fin du nom (exclusif).
 * @property placeholder nom courant du symbole (texte exact de la
 *           plage — le critère S0-b côté Java).
 */
public data class PlageRenommableKotlin(
    public val debut: Int,
    public val fin: Int,
    public val placeholder: String,
)

/**
 * Plage d'un maillon de la chaîne de sélection
 * (`textDocument/selectionRange`, L2 — plan 1.3.0) — décalages dans le
 * texte du document courant.
 *
 * @property debut décalage de début du maillon (inclus).
 * @property fin décalage de fin du maillon (exclusif).
 */
public data class PlageSelectionKotlin(
    public val debut: Int,
    public val fin: Int,
)

/**
 * Chaîne des nœuds englobants d'une position
 * (`textDocument/selectionRange`, L2) : l'expansion progressive de la
 * sélection — chaque maillon est STRICTEMENT plus grand que le
 * précédent (le plus profond d'abord, le fichier entier en dernier).
 *
 * @property plages les maillons du plus profond au plus englobant,
 *           STRICTEMENT croissants (une plage égale à la précédente
 *           est fusionnée : deux productions aux mêmes frontières ne
 *           font qu'un maillon pour l'utilisateur).
 */
public data class ChaineSelectionKotlin(
    public val plages: List<PlageSelectionKotlin>,
)

/**
 * Symbole d'espace (`workspace/symbol`, L2 — plan 1.3.0) : une
 * déclaration NOMMÉE du projet (documents ouverts + racines sources),
 * répondant à une requête par nom — la palette « aller au symbole »
 * de l'éditeur en est le consommateur.
 *
 * @property nom nom de la déclaration (`Foo`, `bar`).
 * @property genre genre du symbole (→ SymbolKind LSP côté hôte).
 * @property uri URI du document porteur.
 * @property debutNom décalage de début du nom déclaré (inclus).
 * @property finNom décalage de fin du nom déclaré (exclusif).
 * @property conteneur nom du conteneur (`Classe` pour un membre),
 *           `null` au premier niveau (colonne « conteneur » de la
 *           palette).
 */
public data class SymboleEspaceKotlin(
    public val nom: String,
    public val genre: GenreSymboleKotlin,
    public val uri: String,
    public val debutNom: Int,
    public val finNom: Int,
    public val conteneur: String?,
)
