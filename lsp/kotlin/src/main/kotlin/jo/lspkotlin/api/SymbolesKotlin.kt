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
 */
public data class PropositionCompletionKotlin(
    public val libelle: String,
    public val genre: GenreCompletionKotlin,
    public val detail: String?,
    public val texteTri: String,
    public val fqnImport: String? = null,
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
 *           quand la déclaration n'a pas de KDoc (ou que la cible
 *           n'est pas du document courant — les déclarations du SCAN
 *           gardent le survol signature seule : re-parser un fichier
 *           tiers pour son doc n'est pas un coût K3, choix documenté).
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
