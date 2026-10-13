package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * Règles GÉNÉRIQUES (tout genre de document, XE1 §4.4) — syntaxe XML
 * que le GENRE ne conditionne pas : attribut dupliqué, esperluette non
 * échappée (valeurs d'attribut + texte).
 */
internal object ReglesGeneriques {
    /**
     * `xml.attributDuplique` : le même nom d'attribut porté deux fois
     * par un même élément (XML interdit, aapt rejette) — marqueur sur
     * le nom du SECOND porteur (et suivants).
     */
    internal fun regleAttributDuplique(
        element: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val vus = mutableSetOf<String>()
        for (attribut in element.attributs) {
            if (!vus.add(attribut.nom)) {
                diagnostics.add(
                    DiagnosticXml(
                        code = CodesDiagnosticsXml.ATTRIBUT_DUPLIQUE,
                        severite = SeveriteXml.ERREUR,
                        message = "attribut « ${attribut.nom} » déjà posé sur « ${element.nom} »",
                        debut = attribut.plageNom.debut,
                        fin = attribut.plageNom.fin,
                    ),
                )
            }
        }
    }

    /**
     * `xml.entiteInvalide` (valeurs d'attribut) : esperluette non
     * échappée dans une valeur — la même FORME légale que le parseur
     * ([jo.lspxml.parse.longueurEntiteLegale]), le registre exact
     * appartient à aapt.
     */
    internal fun regleEntitesValeurs(
        element: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        for (attribut in element.attributs) {
            sonderValeurAttribut(attribut, diagnostics)
        }
    }

    /** Sonne la valeur d'UN attribut (esperluettes non échappées). */
    private fun sonderValeurAttribut(
        attribut: jo.lspxml.parse.XmlAttribut,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val brute = attribut.valeurBrute ?: return
        val plage = attribut.plageValeur ?: return
        for (curseur in brute.indices) {
            val illegal = brute[curseur] == '&' && longueurEntite(brute, curseur) == 0
            if (illegal) {
                diagnostics.add(
                    DiagnosticXml(
                        code = CodesDiagnosticsXml.ENTITE_INVALIDE,
                        severite = SeveriteXml.ERREUR,
                        message = "esperluette non échappée dans la valeur de « ${attribut.nom} »",
                        debut = plage.debut + curseur,
                        fin = plage.debut + curseur + 1,
                    ),
                )
            }
        }
    }

    /**
     * `xml.entiteInvalide` (texte) : les esperluettes non échappées des
     * sections textuelles — plages NOTÉES PAR LE PARSEUR (zéro re-scan,
     * le DOM porte déjà le verdict).
     */
    internal fun regleEntitesTexte(
        document: DocumentXml,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        for (section in document.sectionsTexte) {
            for (esperluette in section.esperluettes) {
                diagnostics.add(
                    DiagnosticXml(
                        code = CodesDiagnosticsXml.ENTITE_INVALIDE,
                        severite = SeveriteXml.ERREUR,
                        message = "esperluette non échappée (écrire « &amp; »)",
                        debut = esperluette.debut,
                        fin = esperluette.fin,
                    ),
                )
            }
        }
    }

    /** Esperluette à [curseur] : entité LÉGALE ? (forme seule, X4). */
    private fun longueurEntite(
        brute: String,
        curseur: Int,
    ): Int = jo.lspxml.parse.longueurEntiteLegale(brute, curseur)
}
