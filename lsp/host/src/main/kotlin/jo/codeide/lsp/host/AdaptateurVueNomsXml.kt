package jo.codeide.lsp.host

import jo.codeide.lsp.index.VueIndex
import jo.lspxml.api.VueNomsXml

/**
 * Adaptateur de PRODUCTION de la vue de noms neutre XML (XE1,
 * LSP_XML.md §3 X3/X4 : la couture « une classe portant ce nom simple
 * existe-t-elle ? » — la garde « balise inconnue » du moteur XML
 * exige que le classpath soit D'ACCORD, le moteur ne connaît ni
 * VueIndex ni :lsp:index).
 *
 * Source : l'extension `java.classNames` de la vue FUSION (requêtes
 * identiques à celles du moteur Java/Kotlin — D4 : une seule vue, un
 * seul budget de cache).
 *
 * Tolérant (X4/D3) : toute requête rend son verdict VIDE (l'index
 * sous-lit tant qu'il n'est pas PRÊT — comportement contractuel de la
 * vue fusion), jamais d'exception vers le moteur.
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — la
 * TOLÉRANCE EST LE CONTRAT (X4/D3 : chaque requête rend son verdict
 * vide, jamais d'exception vers le moteur — l'exception est le signal
 * de dégradation, pas une perte). Même patron qu'AdaptateurVueClasspath.
 *
 * @param vue la vue d'index fusion de la session Java (ADR 0111 §3).
 */
@Suppress("SwallowedException")
internal class AdaptateurVueNomsXml(
    private val vue: VueIndex,
) : VueNomsXml {
    override fun nomsCommencantPar(
        prefixe: String,
        limite: Int,
    ): List<String> =
        try {
            vue.nomsParPrefixe(prefixe, limite).flatMap { nom -> nom.fqcns }
        } catch (t: Throwable) {
            emptyList()
        }
}
