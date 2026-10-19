package jo.lspxml.resolution

import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.ParseurXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement
import jo.lspxml.resources.IndexRessources
import java.io.File

/**
 * RENOMMAGE BORNÉ des ressources projet (S3 — plan de stabilisation
 * LSP §3.3 « rename borné des entrées values », miroir du « rename
 * honnête » Java/I8 et du rename Kotlin) : le SUBSTRAT commun de
 * `plageRenommable` (prepareRename) et `renommage` (rename).
 *
 * ## Cible
 * Le nom d'une ressource projet sous le curseur, deux FAMILLES :
 *  1. **référence** `@type/nom` / `@+type/nom` (déclarante) /
 *     `?attr/nom` dans une valeur d'attribut — extraite par
 *     [ReferencesXml.referenceSous] (la MÊME analyse que le survol et
 *     la définition : une seule source de vérité) ;
 *  2. **déclarante `values`** — la valeur de l'attribut `name` d'une
     * entrée dont le TAG se qualifie ([IndexRessources.typeDEntree] :
     * le MÊME mappage que l'indexation).
 *
 * ## Bornage assumé
 * Les ÉDITIONS couvrent : les fichiers DÉCLARANTS connus de l'index
 * (tampon ouvert PUIS disque — miroir de l'« overlay des tampons
 * ouverts » Java) et les références des documents OUVERTS. Un fichier
 * fermé qui ne déclare pas la ressource ne passe PAS — jamais
 * d'édition à l'aveugle sur un texte non vu. Le renommage d'une
 * ressource PAR FICHIER (layout, drawable…) est HORS bornage (ce
 * serait un renommage de FICHIER, pas une édition texte) — le
 * renommable s'arrête aux entrées `values` et aux ids déclarants.
 *
 * ## Honnêteté (X4)
 * Référence framework (`@android:…`) : JAMAIS renommable (ressource
 * système). Index froid : pas de cible déclarante prouvable — refuse
 * (miroir XP6 : on ne renomme pas ce qu'on ne sait pas qualifier).
 * Le cursus des éditions ne MOUVEMENT jamais le contenu autour du
 * nom : seul le SEGMENT nom voyage.
 */
    @Suppress("TooManyFunctions")
    internal object RenommageXml {
    /** Cible qualifiée du curseur : type + nom de ressource projet. */
    internal data class Cible(
        val type: String,
        val nom: String,
        /** Le curseur est sur la DÉCLARANTE `@+type/nom` ? */
        val declarante: Boolean,
    )

    /** Plage FANTÔME d'une ressource PAR FICHIER (l'indexation pose
     *  `Plage(0, 0)` — le nom est le FICHIER, pas un jeton). */
    private val PLAGE_FICHIER = Plage(0, 0)

    /**
     * Cible du curseur — référence non-framework d'abord, déclarante
     * `values` ensuite ; `null` hors cible.
     */
        // Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
    // sont des RÉGIMES distincts (référence framework refusée,
    // référence projet, déclarante values — la couper brouillerait
    // la lecture de la chaîne de qualification).
    @Suppress("ReturnCount")
    internal fun cibleAuCurseur(
        document: DocumentXml,
        decalage: Int,
        index: IndexRessources,
    ): Cible? {
        val reference = referenceAuCurseur(document, decalage)
        if (reference != null) {
            if (reference.framework) return null
            return Cible(reference.type, reference.nom, reference.declarante)
        }
        return declaranteValuesAuCurseur(document, decalage, index)
    }

    /** Référence du curseur (miroir de la session — la MÊME analyse). */
    private fun referenceAuCurseur(
        document: DocumentXml,
        decalage: Int,
    ): ReferencesXml.Reference? =
        elementsDe(document)
            .mapNotNull { element -> ReferencesXml.attributPortant(element, decalage) }
            .mapNotNull { attribut ->
                val valeur = attribut.valeur
                val plage = attribut.plageValeur
                if (valeur != null && plage != null) {
                    ReferencesXml.referenceSous(valeur, decalage - plage.debut, plage)
                } else {
                    null
                }
            }
            .firstOrNull()

    /**
     * Déclarante `values` du curseur : l'élément le plus profond dont
     * la VALEUR de l'attribut `name` couvre le curseur, tag qualifié
     * par le mappage d'indexation.
     */
    private fun declaranteValuesAuCurseur(
        document: DocumentXml,
        decalage: Int,
        index: IndexRessources,
    ): Cible? =
        elementsDe(document)
            .mapNotNull { element ->
                val valeurNom = element.attribut("name") ?: return@mapNotNull null
                val plage = valeurNom.plageValeur ?: return@mapNotNull null
                val nom = valeurNom.valeur ?: return@mapNotNull null
                val type = index.typeDEntree(element.nom) ?: return@mapNotNull null
                // La VALEUR (entre guillemets) couvre le curseur —
                // inclusif à droite (curseur collé à la quote fermante).
                if (decalage in (plage.debut + 1)..plage.fin) {
                    Cible(type, nom, declarante = true)
                } else {
                    null
                }
            }
            // PREMIER en ordre du document (les entrées values ne
            // s'imbriquent pas — au plus une plage couvre le curseur ;
            // la lecture séquentielle est le choix honnête de reprise).
            .firstOrNull()

    /**
     * La cible est-elle RENOMMABLE ? Déclarante `@+` : oui (elle CRÉE
     * la ressource). Sinon l'index doit prouver AU MOINS un déclarant
     * `values` (plage réelle) — une ressource PAR FICHIER ou un index
     * froid refuse (honnêteté : on ne propose pas un geste qui
     * n'éditerait que des références orphelines).
     */
    internal fun renommable(cible: Cible, index: IndexRessources): Boolean =
        cible.declarante ||
            index.declarantsDe(cible.type, cible.nom).any { it.plage != PLAGE_FICHIER }

    /**
     * Plage du NOM de la cible au curseur (prepareRename) — la plage
     * de la référence (segment nom) ou de la VALEUR `name` (entre
     * guillemets, exclus) ; `null` hors cible renommable.
     */
        // Exemption detekt ciblée (règle 16) : ReturnCount — mêmes
    // régimes que cibleAuCurseur (framework, projet, déclarante).
    @Suppress("ReturnCount")
    internal fun plageNomAuCurseur(
        document: DocumentXml,
        decalage: Int,
        index: IndexRessources,
    ): Plage? {
        val reference = referenceAuCurseur(document, decalage)
        if (reference != null) {
            if (reference.framework) return null
            val cible = Cible(reference.type, reference.nom, reference.declarante)
            return if (renommable(cible, index)) reference.plageNom else null
        }
        val cible = declaranteValuesAuCurseur(document, decalage, index) ?: return null
        return plageDeclarante(document, cible, index)
    }

    /** Plage de la VALEUR `name` de la cible DÉCLARANTE dans [document]. */
    private fun plageDeclarante(
        document: DocumentXml,
        cible: Cible,
        index: IndexRessources,
    ): Plage? =
        elementsDe(document)
            .mapNotNull { element ->
                val valeurNom = element.attribut("name") ?: return@mapNotNull null
                if (valeurNom.valeur == cible.nom &&
                    index.typeDEntree(element.nom) == cible.type
                ) {
                    valeurNom.plageValeur
                } else {
                    null
                }
            }
            .firstOrNull()

    /**
     * Occurrences BORNÉES de la cible — déclarations d'abord (chaque
     * fichier déclarant de l'index, tampon ouvert PUIS disque),
     * références ensuite (documents ouverts, document courant en tête,
     * puis ordre d'uri). Liste vide sans cible renommable (l'hôte
     * traduit en erreur typée — jamais de succès vide silencieux).
     *
     * @param uriDocument uri du document COURANT (priorité de tri).
     * @param documentsOuverts les documents OUVERTS de la session
     *        (uri → texte) — le bornage du balayage références.
     */
        // Exemption detekt ciblée (règle 16) : ReturnCount — session
    // fermée, sans cible, non renommable : trois régimes distincts.
    @Suppress("ReturnCount")
    internal fun occurrences(
        document: DocumentXml,
        uriDocument: String,
        decalage: Int,
        index: IndexRessources,
        documentsOuverts: Map<String, String>,
    ): List<jo.lspxml.api.EmplacementXml> {
        val cible = cibleAuCurseur(document, decalage, index) ?: return emptyList()
        if (!renommable(cible, index)) return emptyList()

        val emplacements = mutableListOf<jo.lspxml.api.EmplacementXml>()
        val vues = mutableSetOf<Pair<String, Int>>()
        emplacements.addAll(declarationsDe(cible, index, documentsOuverts, vues))
        emplacements.addAll(referencesDesOuverts(cible, uriDocument, documentsOuverts, vues))
        return emplacements
    }

    /**
     * Éditions DÉCLARANTES de la cible — chaque fichier déclarant de
     * l'index (plage réelle), tampon ouvert PUIS disque ; le déclarant
     * illisible ou introuvable est OMIS (jamais d'édition fausse).
     */
        // Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
    // — les gardes du scan (plage fantôme, tampon illisible, plage
    // introuvable) sont des états DISTINCTS (miroir IndexRessources).
    @Suppress("LoopWithTooManyJumpStatements")
    private fun declarationsDe(
        cible: Cible,
        index: IndexRessources,
        documentsOuverts: Map<String, String>,
        vues: MutableSet<Pair<String, Int>>,
    ): List<jo.lspxml.api.EmplacementXml> {
        val emplacements = mutableListOf<jo.lspxml.api.EmplacementXml>()
        for (declarant in index.declarantsDe(cible.type, cible.nom)) {
            if (declarant.plage == PLAGE_FICHIER) continue // ressource par fichier : hors bornage
            val texte = texteDeclarant(declarant.fichier, documentsOuverts) ?: continue
            val plage = plageDeclarante(ParseurXml().analyser(texte), cible, index) ?: continue
            val uri = uriDeFichier(declarant.fichier, documentsOuverts)
                ?: ("file://" + declarant.fichier.absolutePath)
            if (vues.add(uri to plage.debut)) {
                emplacements.add(jo.lspxml.api.EmplacementXml(uri, plage.debut, plage.fin))
            }
        }
        return emplacements
    }

    /**
     * Éditions RÉFÉRENCES de la cible — documents OUVERTS seulement
     * (bornage assumé), document courant en tête puis ordre d'uri ;
     * chaque tampon est re-parcé (le DOM porte les plages exactes).
     */
    private fun referencesDesOuverts(
        cible: Cible,
        uriDocument: String,
        documentsOuverts: Map<String, String>,
        vues: MutableSet<Pair<String, Int>>,
    ): List<jo.lspxml.api.EmplacementXml> {
        val emplacements = mutableListOf<jo.lspxml.api.EmplacementXml>()
        val urisTriees =
            documentsOuverts.keys.sortedBy { if (it == uriDocument) "" else it }
        for (uri in urisTriees) {
            val texteOuvert = documentsOuverts[uri] ?: continue
            for (occurrence in occurrencesDans(ParseurXml().analyser(texteOuvert), cible)) {
                if (vues.add(uri to occurrence.debut)) {
                    emplacements.add(jo.lspxml.api.EmplacementXml(uri, occurrence.debut, occurrence.fin))
                }
            }
        }
        return emplacements
    }

    /** Texte du déclarant : tampon ouvert (version vive) PUIS disque. */
        // Exemption detekt ciblée (règle 16) : SwallowedException — la
    // lecture disque best-effort EST le contrat (miroir locationLspDe :
    // fichier illisible = déclarant omis, jamais une édition fausse).
    @Suppress("SwallowedException")
    private fun texteDeclarant(
        fichier: File,
        documentsOuverts: Map<String, String>,
    ): String? {
        uriDeFichier(fichier, documentsOuverts)?.let { return documentsOuverts[it] }
        return try {
            fichier.readText()
        } catch (t: Throwable) {
            null // illisible : déclarant OMIS (jamais d'édition fausse)
        }
    }

    /** Uri OUVERTE d'un fichier (chemins canonisés) — `null` si fermé. */
        // Exemption detekt ciblée (règle 16) : SwallowedException — la
    // canonisation best-effort EST le contrat (chemin non canonisable
    // = comparaison littérale, jamais une correspondance fausse).
    @Suppress("SwallowedException")
    private fun uriDeFichier(
        fichier: File,
        documentsOuverts: Map<String, String>,
    ): String? {
        val canonique = try {
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

    /**
     * TOUTES les références de [document] visant (type, nom) — une
     * valeur d'attribut peut porter PLUSIEURS références (sélecteurs
     * `|`, listes `,` : le terminateur de nom le sait) ; chaque
     * amorçage `@`/`?` de la valeur est sondé, dédoublonné par plage.
     */
    private fun occurrencesDans(
        document: DocumentXml,
        cible: Cible,
    ): List<Plage> =
        elementsDe(document)
            .flatMap { element -> element.attributs.asSequence() }
            .flatMap { attribut -> occurrencesValeur(attribut, cible).asSequence() }
            .distinct()
            .toList()

    /**
     * Les occurrences de la cible DANS UNE VALEUR d'attribut — chaque
     * amorçage `@`/`?` est un candidat (une valeur peut porter
     * PLUSIEURS références : sélecteurs `|`, listes `,`) ; les
     * références framework sont ignorées (ressource système).
     */
        // Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
    // de valeur (absente, plage absente, hors référence) sont des
    // états DISTINCTS du protocole de scan.
    @Suppress("ReturnCount")
    private fun occurrencesValeur(
        attribut: jo.lspxml.parse.XmlAttribut,
        cible: Cible,
    ): List<Plage> {
        val valeur = attribut.valeur ?: return emptyList()
        val plageValeur = attribut.plageValeur ?: return emptyList()
        if (!valeur.startsWith("@") && !valeur.startsWith("?")) return emptyList()
        return valeur.indices
            .filter { indice -> estAmorce(valeur[indice]) }
            .mapNotNull { indice -> ReferencesXml.referenceSous(valeur, indice, plageValeur) }
            .filter { reference -> vise(reference, cible) }
            .map { reference -> reference.plageNom }
    }

    /** Un caractère AMORCE de référence (la valeur commence par lui). */
    private fun estAmorce(c: Char): Boolean = c == '@' || c == '?'

    /** La référence vise-t-elle la cible (projet, même type et nom) ? */
    private fun vise(
        reference: ReferencesXml.Reference,
        cible: Cible,
    ): Boolean = !reference.framework && reference.type == cible.type && reference.nom == cible.nom

    /** Tous les éléments du document, en FLUX plat (miroir session). */
    private fun elementsDe(document: DocumentXml): Sequence<XmlElement> =
        document.racines.asSequence().flatMap { sequenceOf(it) + it.descendants().asSequence() }
}
