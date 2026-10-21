package jo.lspxml.resolution

import jo.lspxml.api.EmplacementXml
import jo.lspxml.api.GenreSurlignageXml
import jo.lspxml.api.PlageSurligneeXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.ParseurXml
import jo.lspxml.parse.Plage
import jo.lspxml.resources.IndexRessources
import java.io.File

/**
 * RÉFÉRENCES et SURLIGNAGE d'occurrences des ressources projet (lot L3
 * du plan 1.3.0 — `textDocument/references` et `documentHighlight`) :
 * le MÊME substrat de preuve que le renommage borné S3
 * ([RenommageXml]) — cible qualifiée par la référence sous le curseur
 * (`@type/nom`, `@+type/nom`, `?attr/nom`) ou la déclarante `values`,
 * occurrences DÉDUPLIQUÉES par (uri, plage). Une seule source de
 * vérité : ce que l'index ne prouve pas n'apparaît pas (X4/D3).
 *
 * Différence de contrat avec le renommage : une ressource est
 * RÉFÉRENÇABLE dès que ses occurrences sont prouvées (déclarant
 * `values` de l'index OU déclarante `@+id` dans un document ouvert) —
 * la garde RENOMMABLE de S3 (au moins un déclarant `values` à plage
 * réelle) ne s'applique PAS : trouver les usages d'un id déclaré
 * `@+id` dans un layout est un service honnête, seul l'ÉDITER hors
 * `values` resterait borné.
 */
internal object ReferencesOccurrencesXml {
    /** Une occurrence qualifiée : emplacement + nature (écriture/lecture). */
    internal data class Occurrence(
        val uri: String,
        val plage: Plage,
        val ecriture: Boolean,
    )

    /**
     * Occurrences PROUVÉES de la ressource sous le curseur —
     * déclarations d'abord (chaque fichier déclarant de l'index à
     * plage réelle, tampon ouvert puis disque : une ÉCRITURE), puis
     * références des documents OUVERTS (document courant en tête :
     * des LECTURES, la déclarante `@+id` comptant comme ÉCRITURE).
     * Liste vide sans cible prouvée (référence framework, curseur
     * hors ressource, index froid sans déclarant ni déclarante).
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod —
     * les DEUX phases (déclarants de l'index, références des ouverts)
     * sont deux machines DISTINCTES du même contrat de preuve ;
     * LoopWithTooManyJumpStatements — les gardes du scan déclarants
     * (plage fantôme, illisible, introuvable) sont des états DISTINCTS
     * (miroir RenommageXml.declarationsDe).
     *
     * @param uriDocument uri du document COURANT (priorité de tri).
     * @param documentsOuverts les documents OUVERTS de la session
     *        (uri → texte) — bornage du balayage références.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    internal fun occurrences(
        document: DocumentXml,
        uriDocument: String,
        decalage: Int,
        index: IndexRessources,
        documentsOuverts: Map<String, String>,
    ): List<Occurrence> {
        val cible = RenommageXml.cibleAuCurseur(document, decalage, index) ?: return emptyList()
        val resultats = mutableListOf<Occurrence>()
        val vues = mutableSetOf<Pair<String, Int>>()
        // 1. Déclarants `values` de l'index (plage réelle) — ÉCRITURES.
        for (declarant in index.declarantsDe(cible.type, cible.nom)) {
            if (declarant.plage == Plage(0, 0)) continue // ressource par fichier : nom = fichier, pas un jeton
            val uri = uriOuverteDe(declarant.fichier, documentsOuverts)
                ?: ("file://" + declarant.fichier.absolutePath)
            val texte = texteDe(declarant.fichier, documentsOuverts) ?: continue
            val plage = RenommageXml.plageDeclarante(ParseurXml().analyser(texte), cible, index) ?: continue
            if (vues.add(uri to plage.debut)) {
                resultats.add(Occurrence(uri, plage, ecriture = true))
            }
        }
        // 2. Références des documents OUVERTS (courant en tête) — la
        //    déclarante `@+id` est une ÉCRITURE, les autres des LECTURES.
        val urisTriees = documentsOuverts.keys.sortedBy { if (it == uriDocument) "" else it }
        for (uri in urisTriees) {
            val texteOuvert = documentsOuverts[uri] ?: continue
            for (plage in RenommageXml.occurrencesDansDocument(ParseurXml().analyser(texteOuvert), cible)) {
                if (vues.add(uri to plage.debut)) {
                    resultats.add(Occurrence(uri, plage, ecriture = estDeclarante(texteOuvert, plage)))
                }
            }
        }
        return resultats
    }

    /**
     * Emplacements LSP des occurrences (references) — la déclaration
     * incluse ou non selon [inclureDeclaration] (elle vit en tête).
     */
    internal fun emplacements(
        occurrences: List<Occurrence>,
        inclureDeclaration: Boolean,
    ): List<EmplacementXml> =
        occurrences
            .filter { occurrence -> inclureDeclaration || !occurrence.ecriture }
            .map { occurrence -> EmplacementXml(occurrence.uri, occurrence.plage.debut, occurrence.plage.fin) }

    /**
     * Surlignages du document COURANT (documentHighlight) — occurrences
     * de ce seul document, ÉCRITURE en écriture, LECTURE en lecture.
     */
    internal fun surlignages(
        occurrences: List<Occurrence>,
        uriDocument: String,
    ): List<PlageSurligneeXml> =
        occurrences
            .filter { occurrence -> occurrence.uri == uriDocument }
            .map { occurrence ->
                PlageSurligneeXml(
                    occurrence.plage.debut,
                    occurrence.plage.fin,
                    if (occurrence.ecriture) GenreSurlignageXml.ECRITURE else GenreSurlignageXml.LECTURE,
                )
            }

    /**
     * La plage du document OUVERT est-elle une DÉCLARANTE `@+type/nom`
     * (elle CRÉE la ressource — une écriture) ? On remonte du début du
     * nom vers l'amorce `@`/`?` de la référence (le nom ne contient ni
     * espace ni chevron — miroir de [ReferencesXml.referenceSous]) : la
     * déclarante porte un `+` IMMÉDIATEMENT après l'amorce.
     */
    @Suppress("ReturnCount")
    private fun estDeclarante(
        texte: String,
        plage: Plage,
    ): Boolean {
        var indice = plage.debut - 1
        while (indice >= 0) {
            val c = texte[indice]
            if (c == '@' || c == '?') {
                return c == '@' && texte.getOrNull(indice + 1) == '+'
            }
            if (c.isWhitespace()) return false
            indice -= 1
        }
        return false
    }

    /** Texte du déclarant : tampon ouvert (version vive) PUIS disque. */
    @Suppress("SwallowedException")
    private fun texteDe(
        fichier: File,
        documentsOuverts: Map<String, String>,
    ): String? {
        uriOuverteDe(fichier, documentsOuverts)?.let { return documentsOuverts[it] }
        return try {
            fichier.readText()
        } catch (t: Throwable) {
            null // illisible : déclarant OMIS (jamais d'emplacement faux)
        }
    }

    /** Uri OUVERTE d'un fichier (chemins canonisés) — `null` si fermé. */
    @Suppress("SwallowedException")
    private fun uriOuverteDe(
        fichier: File,
        documentsOuverts: Map<String, String>,
    ): String? {
        val canonique =
            try {
                fichier.canonicalPath
            } catch (t: Throwable) {
                fichier.absolutePath
            }
        return documentsOuverts.keys.firstOrNull { uri ->
            uri.removePrefix("file://").let { chemin ->
                try {
                    File(chemin).canonicalPath == canonique
                } catch (t: Throwable) {
                    chemin == canonique
                }
            }
        }
    }
}
