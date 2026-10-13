package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.ContenuSurvoleXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.EmplacementXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.completion.ClassementXml
import jo.lspxml.completion.CompletionXml
import jo.lspxml.context.Contexte
import jo.lspxml.context.ContexteCurseur
import jo.lspxml.parse.ParseurXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement
import jo.lspxml.resolution.ReferencesXml
import jo.lspxml.resources.IndexRessources
import jo.lspxml.rules.GenreDocument
import jo.lspxml.rules.ReglesReferences
import jo.lspxml.rules.ReglesStructure
import java.util.concurrent.ConcurrentHashMap

/**
 * Implémentation de [jo.lspxml.api.SessionLangageXml] (XE1,
 * LSP_XML.md §4.1 « session ») — documents qui entrent, diagnostics
 * neutres qui sortent ; le moteur ne connaît pas LSP4J, l'hôte ne
 * connaît pas le parseur (X2).
 *
 * Tolérance (X1) : aucune méthode ne lève sur du texte invalide — le
 * parseur est tolérant par construction, les erreurs voyagent comme
 * diagnostics. Réouvrir un document ouvert le REMPLACE ; modifier un
 * document inconnu équivaut à l'ouvrir ; fermer un document inconnu est
 * sans effet.
 *
 * Performance (cadrage §6) : la re-parse COMPLÈTE est le budget
 * incrémental à l'échelle visée (< 10 ms pour 1 500 lignes, un seul
 * passage mémoire bornée par l'arbre) ; les diagnostics ne sont JAMAIS
 * mis en cache (le coût est un parcours d'arbre, la source est là).
 *
 * @param parseur parseur tolérant — PARTAGÉ par les sessions du moteur
 *        (sans état entre deux appels, sûr par construction).
 * @param catalogues cache de catalogues de plateforme PARTAGÉ par le
 *        moteur (XE2 : chargé une fois par plateforme, §4.2) — la
 *        session y consulte la plateforme de SON classpath.
 * @param indexRessources index des ressources du PROJET (XE3) —
 *        PARTAGÉ par le moteur (un projet = un moteur côté façade) :
 *        scan incrémental par mtime, les connexions successives ne
 *        re-scannent que les fichiers changés.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — une méthode
 * publique PAR requête du contrat de session (documents, diagnostics,
 * complétion, survol, définitions, classpath) — la découper en
 * fichiers casserait le miroir du contrat.
 */
@Suppress("TooManyFunctions")
internal class SessionLangageXmlImpl(
    private val parseur: ParseurXml,
    private val catalogues: CataloguesPartages = CataloguesPartages(),
    private val indexRessources: IndexRessources = IndexRessources(),
) : jo.lspxml.api.SessionLangageXml {
    /** Documents ouverts : URI → dernier texte + DOM. */
    private val documents = ConcurrentHashMap<String, DocumentOuvert>()

    /** Classpath courant (XE2/XE3 le consomment ; XE1 le garde). */
    @Volatile
    private var classpath: ClasspathXml? = null

    @Volatile
    private var fermee = false

    override val nombreDocuments: Int get() = documents.size

    override fun ouvrirDocument(
        uri: String,
        texte: CharSequence,
    ) {
        if (fermee) return
        documents[uri] = DocumentOuvert(texte.toString(), parseur.analyser(texte))
    }

    override fun modifierDocument(
        uri: String,
        texte: CharSequence,
    ) {
        // Modifier un document inconnu ÉQUIVAUT à l'ouvrir (contrat de
        // la session, l'hôte LSP4J ne garantit pas l'ordre).
        ouvrirDocument(uri, texte)
    }

    override fun fermerDocument(uri: String) {
        documents.remove(uri)
    }

    override fun diagnostics(uri: String): List<DiagnosticXml> {
        val document = documents[uri] ?: return emptyList()
        val genre = GenreDocument.depuisUri(uri)
        val diagnostics = mutableListOf<DiagnosticXml>()
        // Recouvrements du parseur : la SYNTAXE, gravité ERREUR.
        document.document.recuperations.forEach { recuperation ->
            diagnostics.add(
                DiagnosticXml(
                    code = recuperation.code,
                    severite = SeveriteXml.ERREUR,
                    message = recuperation.message,
                    debut = recuperation.plage.debut,
                    fin = recuperation.plage.fin,
                ),
            )
        }
        diagnostics.addAll(ReglesStructure.diagnostiquer(document.document, genre))
        // XE3 : références non résolues (COUTURE — index + catalogue,
        // garde dumb mode : index froid = silence).
        diagnostics.addAll(
            ReglesReferences.diagnostiquer(document.document, indexRessources, catalogueCourant()),
        )
        // Publication : ordre du document (stable — le recouvrement
        // précède les règles au même décalage par construction du
        // parcours ; même décalage = même cause).
        return diagnostics.sortedBy { it.debut }
    }

    override fun definirClasspath(classpath: ClasspathXml) {
        // Tolérant : après close c'est un NO-OP ; un appel identique
        // est un no-op chaud (le catalogue XE2 se charge par empreinte,
        // l'index XE3 re-scanne par mtime — fichiers inchangés épargnés).
        if (fermee) return
        this.classpath = classpath
        // XE3 : l'index des ressources suit les RACINES SOURCES du
        // classpath (scan incrémental — deltas seuls).
        indexRessources.definir(classpath.racinesSources)
    }

    /**
     * Classpath courant (SONDAGE de test — XE2/XE3 le consommeront
     * pour le catalogue plateforme et l'index ressources).
     */
    internal fun classpathCourant(): ClasspathXml? = classpath

    /** Catalogue courant (de la plateforme du classpath). */
    private fun catalogueCourant() = catalogues.catalogue(classpath?.dossierPlateforme)

    /**
     * Complétion à [decalage] (XE2/XE3) — le scanner de contexte tranche
     * la ZONE, les contributeurs fusionnent : balises (catalogue
     * plateforme), attributs (styleables de la chaîne + layout du
     * parent + tools:) et VALEURS (références `@type/` de l'index
     * projet + framework + énumérations du catalogue). XE2/XE3
     * couvrent le genre LAYOUT seul (values et manifest suivent XE4) —
     * les autres genres sous-rapportent (X4).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
     * sont des RÉGIMES distincts (session fermée, genre hors périmètre,
     * document inconnu) — chacun porte sa décision de silence.
     */
    @Suppress("ReturnCount")
    override fun completion(
        uri: String,
        decalage: Int,
    ): List<PropositionCompletionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        if (GenreDocument.depuisUri(uri) != GenreDocument.LAYOUT) return emptyList()
        return when (val contexte = ContexteCurseur.analyser(document.texte, decalage, document.document)) {
            is Contexte.NomDeBalise -> CompletionXml.balises(contexte, catalogueCourant())

            is Contexte.NomAttribut -> CompletionXml.attributs(contexte, catalogueCourant())

            is Contexte.Valeur ->
                jo.lspxml.completion.CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources)

            Contexte.Muet -> emptyList()
        }
    }

    /**
     * Survol à [decalage] (XE3) — référence de ressource (projet /
     * framework, déclarant), nom de BALISE (classe du catalogue) ou
     * nom d'ATTRIBUT (format + énumérations). `null` si rien n'est
     * résolu (sous-rapport honnête, X4).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les TROIS régimes du survol (référence, balise, attribut)
     * — chacun porte sa décision de silence.
     */
    @Suppress("ReturnCount")
    override fun survol(
        uri: String,
        decalage: Int,
    ): ContenuSurvoleXml? {
        if (fermee) return null
        val document = documents[uri] ?: return null
        // 1. Référence sous le curseur (valeur d'attribut).
        referenceAuCurseur(document, decalage)?.let { reference ->
            return ReferencesXml.survol(reference, indexRessources)
        }
        val catalogue = catalogueCourant()
        val element = elementSous(document.document, decalage) ?: return null
        // 2. Nom de balise → classe du catalogue.
        if (decalage > element.plageNom.debut && decalage <= element.plageNom.fin) {
            catalogue?.balises[element.nom]?.let { balise ->
                return ContenuSurvoleXml("class ${balise.fqcn}")
            }
        }
        // 3. Nom d'attribut → format + énumérations.
        return survolAttribut(element, decalage, catalogue)
    }

    /** Survol du NOM D'ATTRIBUT de l'élément (format + énumérations). */
    private fun survolAttribut(
        element: XmlElement,
        decalage: Int,
        catalogue: jo.lspxml.catalog.CataloguePlateforme?,
    ): ContenuSurvoleXml? {
        val attr =
            element.attributs
                .firstOrNull { attribut -> surNom(attribut, decalage) }
                ?.let { catalogue?.attributs?.get(it.nom.substringAfter(':')) }
            ?: return null
        val parties = mutableListOf<String>()
        if (attr.formats.isNotEmpty()) parties.add("format: ${attr.formats.joinToString("|")}")
        if (attr.enumerations.isNotEmpty()) {
            parties.add("enum: ${attr.enumerations.take(ClassementXml.APERCU_VALEURS).joinToString("|")}")
        }
        return if (parties.isEmpty()) null else ContenuSurvoleXml(parties.joinToString(" — "))
    }

    /** Le curseur est-il sur le NOM de cet attribut ? */
    private fun surNom(
        attribut: jo.lspxml.parse.XmlAttribut,
        decalage: Int,
    ): Boolean = decalage > attribut.plageNom.debut && decalage <= attribut.plageNom.fin

    /**
     * Référence sous le curseur (n'importe quel attribut de n'importe
     * quel élément) — parcours en SÉQUENCE plate, première trouvée.
     */
    private fun referenceAuCurseur(
        document: DocumentOuvert,
        decalage: Int,
    ): ReferencesXml.Reference? =
        elementsDe(document.document)
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

    /** Tous les éléments du document, en FLUX plat (racines comprises). */
    private fun elementsDe(document: jo.lspxml.parse.DocumentXml): Sequence<XmlElement> =
        document.racines.asSequence().flatMap { sequenceOf(it) + it.descendants().asSequence() }

    /**
     * Définitions à [decalage] (XE3) — la référence `@type/nom` saute
     * au `res/values` DÉCLARANT (projet uniquement : les ressources
     * framework n'ont pas de source locale).
     */
    override fun definitions(
        uri: String,
        decalage: Int,
    ): List<EmplacementXml> {
        val document = if (fermee) null else documents[uri]
        val reference = document?.let { referenceAuCurseur(it, decalage) }
        return reference?.let { ReferencesXml.definitions(it, indexRessources) } ?: emptyList()
    }

    /** Élément le plus profond dont une PLAGE (nom/attribut) couvre le curseur. */
    private fun elementSous(
        document: jo.lspxml.parse.DocumentXml,
        decalage: Int,
    ): XmlElement? {
        var trouve: XmlElement? = null
        fun sonder(element: XmlElement) {
            val dansOuvrante =
                decalage > element.plageOuvrante.debut && decalage <= element.plageOuvrante.fin
            if (dansOuvrante) trouve = element
            element.enfants.forEach { sonder(it) }
        }
        document.racines.forEach { sonder(it) }
        return trouve
    }

    override fun close() {
        if (fermee) return
        fermee = true
        documents.clear()
        classpath = null
    }

    /** Document ouvert : texte intégral + DOM analysé. */
    private class DocumentOuvert(
        val texte: String,
        val document: jo.lspxml.parse.DocumentXml,
    )
}
