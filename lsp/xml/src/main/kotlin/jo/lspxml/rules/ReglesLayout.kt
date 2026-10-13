package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement

/**
 * Règles de LAYOUT (XE1 §4.4, genre `res/layout*`) — la hiérarchie de
 * vues : espace de noms, tailles du gonfleur, identifiants, inclusions,
 * fusion.
 */
internal object ReglesLayout {
    /**
     * Balises SPÉCIALES d'un layout (hors classes de vues — pas de
     * tailles exigées) : `<merge>` (fusion), `<include>` (inclusion),
     * `<fragment>` (support), `<requestFocus>`, et la racine de
     * data-binding `<layout>` avec `<variable>`/`<import>`/`<data>`.
     */
    private val BALISES_SPECIALES: Set<String> =
        setOf(
            "merge", "include", "fragment", "requestFocus",
            "layout", "variable", "import", "data",
        )

    /**
     * `xml.manqueXmlnsAndroid` : un attribut `android:` est utilisé et
     * AUCUN élément du document ne déclare `xmlns:android` — le marqueur
     * va sur le NOM de la racine (c'est là qu'Android Studio l'insère).
     * Conservatoire X4 : sans usage du préfixe, SILENCE (un layout
     * `tools:` seul n'exige rien).
     */
    internal fun regleXmlnsAndroid(
        document: DocumentXml,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val racine = document.racine ?: return
        var prefixeUtilise = false
        var declare = false
        fun sonder(element: XmlElement) {
            for (attribut in element.attributs) {
                if (attribut.estXmlns) {
                    if (attribut.prefixeDeclare == "android") declare = true
                } else if (attribut.prefixe == "android") {
                    prefixeUtilise = true
                }
            }
            element.enfants.forEach { sonder(it) }
        }
        document.racines.forEach { sonder(it) }
        if (prefixeUtilise && !declare) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID,
                    severite = SeveriteXml.ERREUR,
                    message = "xmlns:android manquant sur la racine « ${racine.nom} » (attribut android: utilisé)",
                    debut = racine.plageNom.debut,
                    fin = racine.plageNom.fin,
                ),
            )
        }
    }

    /**
     * `xml.taillesManquantes` : élément de layout SANS `layout_width`
     * ou `layout_height` — AVERTISSEMENT (le cadrage dit « hint, pas
     * error » : un include stylé ou un style parent peut l'expliquer).
     * Un seul marqueur par élément, sur le nom.
     */
    internal fun regleTaillesManquantes(
        element: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        if (element.nom in BALISES_SPECIALES) return
        if (element.enEdition) return
        val largeur = element.valeurAndroid("layout_width")
        val hauteur = element.valeurAndroid("layout_height")
        if (largeur == null || hauteur == null) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.TAILLES_MANQUANTES,
                    severite = SeveriteXml.AVERTISSEMENT,
                    message =
                        "layout_width/layout_height manquants sur « ${element.nom} » " +
                            "(exigés par le gonfleur sauf style porteur)",
                    debut = element.plageNom.debut,
                    fin = element.plageNom.fin,
                ),
            )
        }
    }

    /**
     * `xml.idDuplique` : `@+id/…` déclaré DEUX FOIS dans le MÊME
     * fichier — marqueur sur la valeur du SECOND porteur (le premier
     * est la référence).
     */
    internal fun regleIdDuplique(
        contexte: Contexte,
        vus: MutableMap<String, Plage>,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val plage = contexte.plageId ?: return
        val identifiant = contexte.element.attribut("android:id")?.valeur?.substringAfter("@+id/") ?: return
        val premier = vus[identifiant]
        if (premier != null) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.ID_DUPLIQUE,
                    severite = SeveriteXml.ERREUR,
                    message = "identifiant « $identifiant » déjà déclaré dans ce fichier",
                    debut = plage.debut,
                    fin = plage.fin,
                ),
            )
        } else {
            vus[identifiant] = plage
        }
    }

    /** Passe de fichier : [regleIdDuplique] sur chaque contexte. */
    internal fun regleIdsDupliques(
        contextes: List<Contexte>,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val vus = mutableMapOf<String, Plage>()
        for (contexte in contextes) {
            regleIdDuplique(contexte, vus, diagnostics)
        }
    }

    /**
     * `xml.includeSansLayout` : `<include>` sans attribut `layout`
     * (sans préfixe — c'est la convention du gonfleur).
     */
    internal fun regleIncludeSansLayout(
        element: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        if (element.nom != "include") return
        if (element.attribut("layout") != null) return
        diagnostics.add(
            DiagnosticXml(
                code = CodesDiagnosticsXml.INCLUDE_SANS_LAYOUT,
                severite = SeveriteXml.ERREUR,
                message = "<include> sans attribut layout (la vue incluse est inconnue)",
                debut = element.plageNom.debut,
                fin = element.plageNom.fin,
            ),
        )
    }

    /**
     * `xml.mergeNonRacine` : `<merge>` imbriqué (parent non null) ou
     * seconde racine — le gonfleur n'admet merge QU'en racine.
     */
    internal fun regleMergeNonRacine(
        element: XmlElement,
        estPremiereRacine: Boolean,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        if (element.nom != "merge") return
        if (element.parent == null && estPremiereRacine) return
        diagnostics.add(
            DiagnosticXml(
                code = CodesDiagnosticsXml.MERGE_NON_RACINE,
                severite = SeveriteXml.ERREUR,
                message = "<merge> admis uniquement comme racine d'un layout",
                debut = element.plageNom.debut,
                fin = element.plageNom.fin,
            ),
        )
    }
}
