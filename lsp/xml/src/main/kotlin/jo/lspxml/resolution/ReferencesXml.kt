package jo.lspxml.resolution

import jo.lspxml.api.ContenuSurvoleXml
import jo.lspxml.api.EmplacementXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement
import jo.lspxml.resources.IndexRessources

/**
 * RÉSOLUTION des références de ressources (XE3, décision X5) —
 * `@type/nom`, `@+type/nom` (déclarante), `@android:type/nom`
 * (framework) et `?attr/nom` (attribut de style) : la complétion de
 * VALEURS, le SURVOL et la DÉFINITION consomment la même analyse.
 *
 * Sources de vérité : l'INDEX PROJET ([IndexRessources], racines
 * sources du classpath) et le CATALOGUE plateforme (ressources
 * PUBLIQUES du framework — `public-final.xml`, X4 : sans catalogue,
 * une référence framework n'est JAMAIS signalée).
 */
public object ReferencesXml {
    /** Une référence de ressource extraite d'une valeur d'attribut. */
    public data class Reference(
        /** Type visé (`string`, `layout`, `attr`…). */
        public val type: String,
        /** Nom visé (SANS le type). */
        public val nom: String,
        /** Référence FRAMEWORK (`@android:…`) ? */
        public val framework: Boolean,
        /** Référence DÉCLARANTE (`@+id/…`) ? */
        public val declarante: Boolean,
        /** Attribut de STYLE (`?attr/…`) ? */
        public val attributStyle: Boolean,
        /** Plage du NOM dans la source (le marqueur va sur le nom). */
        public val plageNom: Plage,
    )

    /**
     * Extrait la référence sous [decalage] dans la valeur [valeur]
     * (plage [plageValeur] dans la source) — `null` si le curseur n'est
     * pas sur une forme de référence.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod/Complexity/
     * ReturnCount — l'extraction est UNE machine lexicale (borne
     * arrière, préfixes « + », « android: », « * », deux formes
     * @type/nom et ?attr/nom) — la couper casserait la lecture du
     * format.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    public fun referenceSous(
        valeur: String,
        decalage: Int,
        plageValeur: Plage,
    ): Reference? {
        val borne = decalage.coerceIn(0, valeur.length)
        // Trouver le début de la référence : dernier @/? pertinent.
        var debut = -1
        var framework = false
        var declarante = false
        var attributStyle = false
        for (indice in borne downTo 0) {
            val c = valeur.getOrNull(indice) ?: continue
            if (c == '@' || c == '?') {
                debut = indice
                attributStyle = c == '?'
                break
            }
            // Le nom ne contient ni espace ni chevron : on remonte
            // tant qu'on est DANS la référence.
            if (c.isWhitespace()) break
        }
        if (debut < 0) return null
        var curseur = debut + 1
        if (valeur.getOrNull(curseur) == '+') {
            declarante = true
            curseur += 1
        }
        if (valeur.startsWith("android:", curseur)) {
            framework = true
            curseur += "android:".length
        } else if (valeur.getOrNull(curseur) == '*') {
            // `@*android:…` (privée) : même régime que le framework.
            framework = true
            curseur += 1
            if (valeur.startsWith("android:", curseur)) {
                curseur += "android:".length
            }
        }
        if (attributStyle) {
            // `?attr/nom` (et la forme courte héritée `?nom`).
            if (valeur.startsWith("attr/", curseur)) curseur += "attr/".length
            val debutNom = curseur
            while (curseur < valeur.length && !estFinNom(valeur[curseur])) curseur += 1
            if (curseur <= debutNom) return null
            return Reference(
                type = "attr",
                nom = valeur.substring(debutNom, curseur),
                framework = framework,
                declarante = false,
                attributStyle = true,
                plageNom = Plage(plageValeur.debut + debutNom, plageValeur.debut + curseur),
            )
        }
        // `@type/nom` : le type jusqu'au `/`.
        val separant = valeur.indexOf('/', curseur)
        if (separant < 0) return null
        val type = valeur.substring(curseur, separant)
        if (type.isEmpty()) return null
        var fin = separant + 1
        while (fin < valeur.length && !estFinNom(valeur[fin])) fin += 1
        if (fin <= separant + 1) return null
        return Reference(
            type = type,
            nom = valeur.substring(separant + 1, fin),
            framework = framework,
            declarante = declarante,
            attributStyle = false,
            plageNom = Plage(plageValeur.debut + separant + 1, plageValeur.debut + fin),
        )
    }

    /** Caractère qui TERMINE un nom de ressource dans une valeur. */
    private fun estFinNom(c: Char): Boolean = c.isWhitespace() || c == ',' || c == '|'

    // ── Résolution ─────────────────────────────────────────────────

    /** Le nom existe-t-il dans le PROJET (index chaud) ? */
    private fun dansProjet(
        index: IndexRessources,
        type: String,
        nom: String,
    ): Boolean = index.resoudre(type, nom) != null

    /** Le nom existe-t-il dans le FRAMEWORK (catalogue chargé) ? */
    private fun dansFramework(
        catalogue: CataloguePlateforme?,
        type: String,
        nom: String,
    ): Boolean = catalogue?.ressourcesFramework?.get(type)?.contains(nom) == true

    /**
     * Une référence est-elle RÉSOLUE ? Garde X4 : une référence
     * DÉCLARANTE (`@+id`) ne se résout jamais (elle CRÉE) ; sans index
     * chaud ni catalogue, TOUT est résolu (dumb mode — la règle ne
     * signale que ce qu'elle SAIT manquer). P3 : l'INDEX BIBLIOTHÈQUE
     * (AAR des dépendances) complète projet et framework.
     */
    internal fun estResolue(
        reference: Reference,
        index: IndexRessources,
        catalogue: CataloguePlateforme?,
    ): Boolean =
        when {
            reference.declarante -> true
            reference.framework -> !catalogueEstCharge(catalogue) ||
                dansFramework(catalogue, reference.type, reference.nom)

            index.estChaud -> dansProjet(index, reference.type, reference.nom) ||
                dansLib(index, reference.type, reference.nom) ||
                (catalogueEstCharge(catalogue) && dansFramework(catalogue, reference.type, reference.nom))

            else -> true // index froid : silence (dumb mode)
        }

    /** Le nom existe-t-il dans les BIBLIOTHÈQUES (AAR indexées) ? */
    private fun dansLib(
        index: IndexRessources,
        type: String,
        nom: String,
    ): Boolean = index.resoudreLib(type, nom) != null

    /** Le catalogue porte-t-il les ressources framework ? */
    private fun catalogueEstCharge(catalogue: CataloguePlateforme?): Boolean =
        catalogue != null && catalogue.ressourcesFramework.isNotEmpty()

    /**
     * SURVOL d'une référence — `string « hello » — projet,
     * values/strings.xml`, `style « Theme.Material3.DayNight » —
     * bibliothèque, material-1.12.0.aar` (P3) ou `string « ok » » —
     * framework android` ; `null` si rien n'est résolu (sous-rapport
     * honnête).
     */
    internal fun survol(
        reference: Reference,
        index: IndexRessources,
    ): ContenuSurvoleXml? {
        if (reference.declarante) {
            return ContenuSurvoleXml("${reference.type} « ${reference.nom} » — déclaré ici (@+)")
        }
        val declaration = index.resoudre(reference.type, reference.nom)
        return when {
            declaration != null ->
                ContenuSurvoleXml(
                    "${reference.type} « ${reference.nom} » — projet, ${declaration.fichier.name}",
                )

            // XP6 : si l'index est froid (dumb mode), on NE MENT PAS —
            // retourner null (sous-rapport honnête) au lieu d'affirmer
            // "framework android" pour une ressource projet non indexée.
            reference.framework -> ContenuSurvoleXml("${reference.type} « ${reference.nom} » — framework android")
            !index.estChaud -> null
            // P3 : le nom vit dans une DÉPENDANCE (AAR) — détail = nom
            // du conteneur déclarant.
            else ->
                index.resoudreLib(reference.type, reference.nom)?.let { cheminAar ->
                    ContenuSurvoleXml(
                        "${reference.type} « ${reference.nom} » — bibliothèque, " +
                            "${java.io.File(cheminAar).name}",
                    )
                }
        }
    }

    /**
     * DÉFINITIONS d'une référence — le `res/values` DÉCLARANT (projet
     * uniquement : les ressources framework n'ont pas de source locale
     * et les entrées de BIBLIOTHÈQUE vivent DANS une archive — D3
     * conservateur, jamais d'URI factice vers un conteneur) ; liste
     * vide sinon.
     */
    internal fun definitions(
        reference: Reference,
        index: IndexRessources,
    ): List<EmplacementXml> {
        val declaration =
            if (reference.framework || reference.declarante) {
                null
            } else {
                index.resoudre(reference.type, reference.nom)
            }
        return declaration?.let { declarationProjet ->
            listOf(
                EmplacementXml(
                    uri = "file://${declarationProjet.fichier.absolutePath}",
                    debut = declarationProjet.plage.debut,
                    fin = declarationProjet.plage.fin,
                ),
            )
        } ?: emptyList()
    }
    /** Cherche l'attribut portant une valeur de référence à [decalage]. */
    internal fun attributPortant(
        element: XmlElement,
        decalage: Int,
    ): jo.lspxml.parse.XmlAttribut? =
        element.attributs.firstOrNull { attribut ->
            val plage = attribut.plageValeur
            val valeur = attribut.valeur
            plage != null && valeur != null &&
                decalage > plage.debut && decalage <= plage.fin &&
                (valeur.startsWith("@") || valeur.startsWith("?"))
        }
}
