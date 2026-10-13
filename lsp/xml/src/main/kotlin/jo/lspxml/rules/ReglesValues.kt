package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.XmlElement

/**
 * Règles de VALUES (XE1 §4.4, genre `res/values*`) — les entrées
 * nommées : `name` exigé, `name` unique par type dans le fichier.
 */
internal object ReglesValues {
    /**
     * Balises d'ENTRÉES de `res/values` exigeant un `name` (toutes les
     * ressources simples ; `item` n'est jamais de premier niveau et
     * vit sous style/plurals — exclu, conservatoire).
     */
    private val ENTREES_NOMMEES: Set<String> =
        setOf(
            "string", "plurals", "color", "dimen", "style", "integer",
            "bool", "array", "string-array", "integer-array",
            "drawable", "mipmap", "font", "attr", "declare-styleable",
        )

    /**
     * `xml.entreeSansNom` : entrée de premier niveau de `res/values`
     * (enfant de `<resources>`) sans attribut `name`.
     */
    internal fun regleEntreeSansNom(
        element: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val exigee =
            element.parent?.nom == "resources" &&
                element.nom in ENTREES_NOMMEES &&
                element.attribut("name") == null
        if (exigee) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.ENTREE_SANS_NOM,
                    severite = SeveriteXml.ERREUR,
                    message = "entrée « ${element.nom} » sans attribut name (ressource innommable)",
                    debut = element.plageNom.debut,
                    fin = element.plageNom.fin,
                ),
            )
        }
    }

    /**
     * `xml.nomDuplique` : deux entrées de PREMIER niveau du même TYPE
     * (balise) portant le même `name` dans le même fichier — la clé est
     * (balise, name) : `string/x` et `color/x` sont deux ressources
     * DISTINCTES pour aapt (conservatoire, jamais de faux positif).
     * Marqueur sur le nom de la SECONDE entrée.
     */
    internal fun regleNomsDupliques(
        contextes: List<Contexte>,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        val vus = mutableMapOf<Pair<String, String>, XmlElement>()
        for (contexte in contextes) {
            val element = contexte.element
            val nom = contexte.nomEntree
            if (element.parent?.nom == "resources" && nom != null) {
                val cle = element.nom to nom
                if (vus.containsKey(cle)) {
                    diagnostics.add(
                        DiagnosticXml(
                            code = CodesDiagnosticsXml.NOM_DUPLIQUE,
                            severite = SeveriteXml.ERREUR,
                            message = "« ${element.nom} » nommé « $nom » déjà déclaré dans ce fichier",
                            debut = element.plageNom.debut,
                            fin = element.plageNom.fin,
                        ),
                    )
                } else {
                    vus[cle] = element
                }
            }
        }
    }
}
