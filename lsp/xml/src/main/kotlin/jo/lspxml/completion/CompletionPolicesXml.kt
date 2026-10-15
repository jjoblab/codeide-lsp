package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre POLICE (XP12, plan de stabilisation LSP §3.3
 * S2 — « genres étendus ») : familles de polices
 * (`res/font/nom.xml`) — racine `font-family`, enfant `font`
 * (polices locales : `android:font`, `fontStyle`, `fontWeight`) ;
 * famille TÉLÉCHARGEABLE (Google Fonts) via les attributs
 * `app:fontProvider*` sur la racine.
 *
 * Catalogue FIXE par la documentation (motif XE5). Conservateur
 * (X4) : parent inconnu = silence.
 */
internal object CompletionPolicesXml {
    /** Balises d'une famille de polices (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "font-family" to "racine de famille (locale ou téléchargeable)",
            "font" to "police de la famille (font, fontStyle, fontWeight)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "font-family" to listOf("font"),
        )

    /** Attributs PAR BALISE (nom complet → détail court). */
    private val ATTRIBUTS: Map<String, List<Pair<String, String>>> =
        mapOf(
            "font-family" to listOf(
                "app:fontProviderAuthority" to "autorité du fournisseur (téléchargeable)",
                "app:fontProviderPackage" to "paquet du fournisseur",
                "app:fontProviderQuery" to "requête (nom de famille)",
                "app:fontProviderCerts" to "certificats (@array/…)",
                "app:fontProviderSystemFontFamily" to "famille système (API 34)",
            ),
            "font" to listOf(
                "android:font" to "fichier de police (@font/…, @raw/…)",
                "android:fontStyle" to "style : normal, italic",
                "android:fontWeight" to "graisse (100–900, centaines)",
                "android:tint" to "teinte (@color/…)",
                "android:fontVariationSettings" to "axes variables (API 26)",
            ),
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "fontStyle" to listOf("normal", "italic"),
            "fontWeight" to listOf("100", "200", "300", "400", "500", "600", "700", "800", "900"),
        )

    /**
     * Complétion du NOM DE BALISE — `font-family` en racine, `font`
     * sous la famille (X4 : autre parent = silence).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> listOf("font-family")

                ENFANTS.containsKey(parent.nom) -> ENFANTS.getValue(parent.nom)

                else -> return emptyList()
            }
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in admissibles) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.BALISE,
                        detail = BALISES[nom],
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT — PAR BALISE, déclarations xmlns:
     * en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, balise inconnue) —
     * chacun porte sa décision de silence X4.
     */
    @Suppress("ReturnCount")
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        if (CompletionXml.frappeDeDeclarationXmlns(prefixe)) {
            return CompletionXml.attributsXmlns(prefixe)
        }
        val element = contexte.element ?: return emptyList()
        val candidats = ATTRIBUTS[element.nom] ?: return emptyList()
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in candidats) {
            if (CompletionXml.correspondancePrefixe(prefixe, nom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = detail,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangAttribut(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /** Complétion de VALEUR — énumérations de police connues. */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> {
        val valeurs = ENUMERATIONS[contexte.attribut.substringAfter(':')] ?: return emptyList()
        val prefixe = contexte.prefixeTape
        return valeurs
            .filter { prefixe.isEmpty() || it.startsWith(prefixe) }
            .map { nom ->
                PropositionCompletionXml(
                    libelle = nom,
                    genre = GenreCompletionXml.VALEUR_ENUM,
                    detail = "enum",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                )
            }
    }
}
