package jo.lspxml.parse

/**
 * DOM neutre du moteur XML (XE1, LSP_XML.md §4.1 « dom ») — arbre minimal
 * XmlElement/XmlAttribute + plages, décision X2 : les fonctionnalités de
 * l'éditeur (règles, complétion, résolution) ne dépendent d'AUCUN parseur
 * concret.
 *
 * Les plages sont des DÉCALAGES de caractères dans le texte analysé —
 * la conversion ligne/colonne appartient à l'hôte.
 */

/** Plage de texte (décalages de caractères, fin exclusive). */
public data class Plage(
    /** Décalage de début (inclus). */
    public val debut: Int,
    /** Décalage de fin (exclusif). */
    public val fin: Int,
)

/**
 * Attribut d'un élément — nom BRUT conservé (`android:text`, `xmlns:tools`)
 * + préfixe/locale scindés à la construction (la complétion distingue les
 * espaces de noms, X3 : `android:`/`app:`/`tools:`).
 *
 * @property nom nom complet (`android:id`).
 * @property prefixe préfixe d'espace de noms (`android`, `xmlns`),
 *           `null` sans deux-points.
 * @property locale nom local (`id`, `tools`).
 * @property valeur valeur DÉCODÉE (entités résolues) ou `null` sans `=`.
 * @property valeurBrute valeur telle qu'écrite (entités non résolues).
 * @property plageNom plage du nom dans la source.
 * @property plageValeur plage de la valeur SANS les guillemets (`null`
 *           sans valeur) ; pour une valeur non refermée (frappe en
 *           cours) : jusqu'au point de reprise.
 */
public data class XmlAttribut(
    public val nom: String,
    public val prefixe: String?,
    public val locale: String,
    public val valeur: String?,
    public val valeurBrute: String?,
    public val plageNom: Plage,
    public val plageValeur: Plage?,
) {
    /** L'attribut est-il une déclaration d'espace de noms (`xmlns…`)? */
    public val estXmlns: Boolean
        get() = prefixe == "xmlns" || (prefixe == null && locale == "xmlns")

    /** Préfixe DÉCLARÉ par cet attribut xmlns (`tools`, `android`…). */
    public val prefixeDeclare: String?
        get() =
            when {
                prefixe == "xmlns" -> locale
                prefixe == null && locale == "xmlns" -> ""
                else -> null
            }

    public companion object {
        /**
         * Construit un attribut depuis son nom brut (scission
         * préfixe/locale à la première étiquette de deux-points).
         */
        public fun deNomBrut(
            nom: String,
            valeur: String?,
            valeurBrute: String?,
            plageNom: Plage,
            plageValeur: Plage?,
        ): XmlAttribut {
            val deuxPoints = nom.indexOf(':')
            return XmlAttribut(
                nom = nom,
                prefixe = if (deuxPoints > 0) nom.substring(0, deuxPoints) else null,
                locale = if (deuxPoints > 0) nom.substring(deuxPoints + 1) else nom,
                valeur = valeur,
                valeurBrute = valeurBrute,
                plageNom = plageNom,
                plageValeur = plageValeur,
            )
        }
    }
}

/**
 * Élément du DOM — tag, attributs, enfants, plages.
 *
 * CLASSE À IDENTITÉ (pas une data class) : l'égalité structurelle d'un
 * graphe DOM cyclique (parent ↔ enfants) n'a aucun sens — deux nœuds
 * « égaux » ne désignent jamais la même position du document ; les
 * collections d'éléments (recherche, index) vivent par IDENTITÉ, les
 * CLÉS par nom/plage (chaînes). Même choix que le DOM W3C.
 *
 * États de fermeture distingués (tolérance X1) :
 * - [autoFermante] : `<TextView … />` — pas de fermante attendue ;
 * - fermée par `</nom>` : [plageFermante] non null ;
 * - [enEdition] : la balise ouvrante n'est PAS terminée (`>` non atteint,
 *   fin de fichier pendant la frappe) — l'élément existe quand même
 *   pour la complétion, SANS diagnostic de balise non fermée (le
 *   marqueur viendrait sous le curseur, à chaud).
 *
 * @property nom nom de balise BRUT (`TextView`, `androidx.…ConstraintLayout`
 *           — les noms pleinement qualifiés restent tels quels, X3).
 * @property attributs attributs dans l'ordre du document.
 * @property enfants éléments enfants (TEXTE/CDATA/commentaires ne
 *           montent pas : les sections textuelles utiles au diagnostic
 *           vivent dans [DocumentXml.sectionsTexte]).
 * @property parent parent (`null` pour la racine).
 * @property autoFermante `<…/>` ?
 * @property enEdition balise ouvrante non terminée ?
 * @property plageNom plage du nom dans la balise OUVRANTE.
 * @property plageOuvrante plage de la balise ouvrante COMPLÈTE (`<…>` ou
 *           `<…/>` ; pour un élément en édition : jusqu'à la fin du texte).
 * @property plageFermante plage de `</nom>` (`null` si non fermée).
 */
@Suppress("LongParameterList")
public class XmlElement(
    public val nom: String,
    public val attributs: List<XmlAttribut>,
    public val enfants: List<XmlElement>,
    public val parent: XmlElement?,
    public val autoFermante: Boolean,
    public val enEdition: Boolean,
    public val plageNom: Plage,
    public val plageOuvrante: Plage,
    public val plageFermante: Plage?,
) {
    /** Attribut par nom complet (`android:id`) — premier porteur. */
    public fun attribut(nom: String): XmlAttribut? = attributs.firstOrNull { it.nom == nom }

    /** Valeur d'un attribut par nom complet (`null` si absent). */
    public fun valeurDe(nom: String): String? = attribut(nom)?.valeur

    /**
     * Valeur de `android:xxx` par nom local (`id` → `android:id` — le
     * préfixe Android est le régime nominal des ressources).
     */
    public fun valeurAndroid(nomLocal: String): String? = attribut("android:$nomLocal")?.valeur

    /** L'élément est-il fermé (par `</nom>` ou auto-fermante) ? */
    public val estFermee: Boolean get() = autoFermante || plageFermante != null

    /** Descendants en PROFONDEUR D'ABORD (self exclu), ordre du document. */
    public fun descendants(): List<XmlElement> {
        val resultat = mutableListOf<XmlElement>()
        fun visiter(element: XmlElement) {
            element.enfants.forEach { enfant ->
                resultat.add(enfant)
                visiter(enfant)
            }
        }
        visiter(this)
        return resultat
    }

    /** Chaîne des ancêtres (parent → racine). */
    public fun ancetres(): List<XmlElement> {
        val resultat = mutableListOf<XmlElement>()
        var courant = parent
        while (courant != null) {
            resultat.add(courant)
            courant = courant.parent
        }
        return resultat
    }
}

/**
 * Document XML analysé — racine + sections textuelles utiles au
 * diagnostic (esperluettes non échappées, XE1 §4.4).
 *
 * @property racine PREMIER élément racine (`null` : aucune balise) —
 *           le document canonique n'en a qu'une ; les SŒURS de
 *           reprise (frappe d'une seconde racine, XML invalide) vivent
 *           dans [racines] : la complétion doit les VOIR, le document
 *           canonique reste [racine].
 * @property racines TOUS les éléments de premier niveau, ordre du
 *           document (souvent `[racine]` seule).
 * @property sectionsTexte plages des sections de TEXTE (entre balises)
 *           avec leurs esperluettes NON échappées (la règle
 *           `xml.entiteInvalide` vit dans rules/).
 * @property recuperations diagnostics de RECOUVREMENT du parseur
 *           (balise non fermée, fermante incohérente, guillemet
 *           non refermé — codes `xml.*`, XE1 §4.2) ; les RÈGLES de
 *           structure ne sont PAS ici : elles vivent dans rules/.
 * @property longueur longueur du texte analysé (bornage des plages).
 */
public data class DocumentXml(
    public val racine: XmlElement?,
    public val racines: List<XmlElement>,
    public val sectionsTexte: List<SectionTexte>,
    public val recuperations: List<RecuperationParseur>,
    public val longueur: Int,
) {
    /**
     * Section de texte entre balises (le contenu n'est pas conservé :
     * la source vit chez l'appelant, la PLAGE suffit aux diagnostics).
     *
     * @property plage plage du texte.
     * @property esperluetes plages des esperluettes non échappées —
     *           XML n'a AUCUNE esperluette légale non échappée
     *           (`&nom;`, `&#…;` et `&#x…;` seules).
     */
    public data class SectionTexte(
        public val plage: Plage,
        public val esperluettes: List<Plage>,
    )
}

/**
 * Diagnostic de recouvrement du parseur (XE1 §4.2) — forme de parse :
 * SANS gravité (la conversion vers `DiagnosticXml` — toujours ERREUR —
 * vit dans la session, qui possède les types d'api).
 *
 * @property code code stable (`xml.tagNonFermee`…).
 * @property message message lisible.
 * @property plage plage source (décalages, fin exclusive).
 */
public data class RecuperationParseur(
    public val code: String,
    public val message: String,
    public val plage: Plage,
)
