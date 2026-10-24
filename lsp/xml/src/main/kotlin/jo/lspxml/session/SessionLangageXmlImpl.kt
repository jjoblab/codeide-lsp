package jo.lspxml.session

import jo.lspxml.api.ClasspathXml
import jo.lspxml.api.ContenuSurvoleXml
import jo.lspxml.api.CorrectifXml
import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.EmplacementXml
import jo.lspxml.api.CanauxCouleurXml
import jo.lspxml.api.ChaineSelectionXml
import jo.lspxml.api.CouleurDocumentXml
import jo.lspxml.api.PresentationCouleurXml
import jo.lspxml.api.PlageLieeXml
import jo.lspxml.api.PlageSurligneeXml
import jo.lspxml.api.EditionXml
import jo.lspxml.api.PlagePliableXml
import jo.lspxml.api.PlageRenommableXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.api.SymboleDocumentXml
import jo.lspxml.catalog.CataloguesPartages
import jo.lspxml.completion.ClassementXml
import jo.lspxml.completion.CompletionAnimateursXml
import jo.lspxml.completion.CompletionAnimationsXml
import jo.lspxml.completion.CompletionCouleursXml
import jo.lspxml.completion.CompletionDrawablesXml
import jo.lspxml.completion.CompletionEntreesXml
import jo.lspxml.completion.CompletionMenusXml
import jo.lspxml.completion.CompletionNavigationXml
import jo.lspxml.completion.CompletionPolicesXml
import jo.lspxml.format.FormatXml
import jo.lspxml.resolution.CouleursDocumentXml
import jo.lspxml.resolution.EditionLieeSelectionXml
import jo.lspxml.resolution.ReferencesOccurrencesXml
import jo.lspxml.resolution.RenommageXml
import jo.lspxml.completion.CompletionTransitionsXml
import jo.lspxml.completion.CompletionValeursXml
import jo.lspxml.completion.CompletionXmlApp
import jo.lspxml.completion.CompletionXml
import jo.lspxml.context.Contexte
import jo.lspxml.context.ContexteCurseur
import jo.lspxml.parse.ParseurXml
import jo.lspxml.parse.XmlElement
import jo.lspxml.plis.PlisDocumentXml
import jo.lspxml.resolution.ReferencesXml
import jo.lspxml.resources.IndexRessources
import jo.lspxml.rules.GenreDocument
import jo.lspxml.rules.ReglesManifeste
import jo.lspxml.rules.ReglesReferences
import jo.lspxml.rules.ReglesStructure
import jo.lspxml.rules.ReglesTagInconnu
import jo.lspxml.symboles.SymbolesDocumentXml
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
        // XE4 : règles de MANIFEST (package, composants nommés).
        if (genre == GenreDocument.MANIFEST) {
            diagnostics.addAll(ReglesManifeste.diagnostiquer(document.document))
        }
        // XE4 : balise de classe INCONNUE (couture conservatrice X4 —
        // catalogue + vue de noms TOUS d'accord, sinon silence).
        if (genre == GenreDocument.LAYOUT) {
            diagnostics.addAll(
                ReglesTagInconnu.diagnostiquer(
                    document.document,
                    catalogueCourant(),
                    classpath?.vueNoms,
                ),
            )
        }
        // Publication : ordre du document (stable — le recouvrement
        // précède les règles au même décalage par construction du
        // parcours ; même décalage = même cause).
        return diagnostics.sortedBy { it.debut }
    }

    override fun definirClasspath(classpath: ClasspathXml) {
        // Tolérant : après close c'est un NO-OP ; un appel identique
        // est un no-op chaud (le catalogue XE2 se charge par empreinte,
        // l'index XE3 re-scanne par mtime — fichiers inchangés épargnés,
        // les AAR P3 par chemin+mtime — archives inchangées épargnées).
        if (fermee) return
        this.classpath = classpath
        // ADR 0211 (annexe D1-d) : BRANCHEMENT quand le moteur possède
        // le nourrissage — AUCUN re-balayage ici (le supplier de
        // connexion ne paie plus d'E/S). Hors moteur nourricier
        // (usages directs, packs anciens), le contrat SYNCHRONE
        // historique tient : le balayage a lieu ici et maintenant.
        if (!indexRessources.nourrissageParMoteur) {
            // XE3 : l'index des ressources suit les RACINES SOURCES du
            // classpath (scan incrémental — deltas seuls).
            indexRessources.definir(classpath.racinesSources)
            // P3 : le volet BIBLIOTHÈQUES suit les AAR des dépendances
            // (même loi incrémentale — un conteneur inchangé n'est jamais
            // re-décodé).
            indexRessources.definirAar(classpath.racinesAar)
        }
    }

    /**
     * Classpath courant (SONDAGE de test — XE2/XE3 le consommeront
     * pour le catalogue plateforme et l'index ressources).
     */
    internal fun classpathCourant(): ClasspathXml? = classpath

    /**
     * Texte vivant d'un document ouvert (SONDAGE de test — le
     * renommage borné S3 vérifie que ses plages tombent sur le nom).
     * `null` pour un document inconnu/fermé.
     */
    internal fun texteDocumentInterne(uri: String): String? = documents[uri]?.texte

    /** Catalogue courant (de la plateforme du classpath). */
    private fun catalogueCourant() = catalogues.catalogue(classpath?.dossierPlateforme)

    /**
     * Complétion à [decalage] (XE2/XE3/XE4/XE5) — le scanner de contexte
     * tranche la ZONE, les contributeurs fusionnent PAR GENRE (chacun
     * délégué à sa méthode privée : LAYOUT, VALUES, DRAWABLE, MENU) ;
     * le manifest reste sans complétion (règles seules).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (session fermée, document inconnu,
     * genre hors périmètre) — chacun porte sa décision de silence.
     */
    @Suppress("ReturnCount")
    override fun completion(
        uri: String,
        decalage: Int,
    ): List<PropositionCompletionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val genre = GenreDocument.depuisUri(uri)
        if (genre !in GENRES_COMPLETABLES) return emptyList()
        val contexte = ContexteCurseur.analyser(document.texte, decalage, document.document)
        return completionParGenre(genre, contexte)
    }

    /**
     * Dispatch PAR GENRE de complétion (le manifest : règles seules,
     * jamais complété) — scindé en DEUX générations pour garder le
     * couplage lisible : XE2-XE5 (v0.118-0.121) puis XP12 (v0.134.10).
     */
    private fun completionParGenre(
        genre: GenreDocument,
        contexte: Contexte,
    ): List<PropositionCompletionXml> =
        when (genre) {
            GenreDocument.VALEURS -> completionValeurs(contexte)

            GenreDocument.LAYOUT -> completionLayout(contexte)

            GenreDocument.DRAWABLE -> completionDrawable(contexte)

            GenreDocument.MENU -> completionMenu(contexte)

            else -> completionGenreEtendu(genre, contexte)
        }

    /** Dispatch des genres ÉTENDUS (XP12) vers leur contributeur. */
    private fun completionGenreEtendu(
        genre: GenreDocument,
        contexte: Contexte,
    ): List<PropositionCompletionXml> =
        when (genre) {
            GenreDocument.COULEUR -> completionCouleur(contexte)

            GenreDocument.ANIMATION -> completionAnimation(contexte)

            GenreDocument.ANIMATEUR -> completionAnimateur(contexte)

            GenreDocument.XML_APP -> completionXmlApp(contexte)

            GenreDocument.NAVIGATION -> completionNavigation(contexte)

            GenreDocument.TRANSITION -> completionTransition(contexte)

            GenreDocument.POLICE -> completionPolice(contexte)

            else -> emptyList()
        }

    /** Genres dont la complétion est branchée (le manifest : règles seules). */
    private companion object {
        /** Nombre maximum de vues projet proposées en complétion de balise (XP5). */
        private const val LIMITE_VUES_PROJET: Int = 50

        private val GENRES_COMPLETABLES: Set<GenreDocument> =
            setOf(
                GenreDocument.LAYOUT,
                GenreDocument.VALEURS,
                GenreDocument.DRAWABLE,
                GenreDocument.MENU,
                GenreDocument.COULEUR,
                GenreDocument.ANIMATION,
                GenreDocument.ANIMATEUR,
                GenreDocument.XML_APP,
                GenreDocument.NAVIGATION,
                GenreDocument.TRANSITION,
                GenreDocument.POLICE,
            )
    }

    /** Complétion VALUES (XE4) — entrées nommées, styles du parent. */
    private fun completionValeurs(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionEntreesXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionEntreesXml.attributs(contexte)

            is Contexte.Valeur ->
                CompletionEntreesXml.valeurs(contexte, catalogueCourant(), indexRessources)

            Contexte.Muet -> emptyList()
        }

    /** Complétion LAYOUT (XE2/XE3/XP5) — catalogue plateforme +
     *  références + vues projet. */
    private fun completionLayout(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> {
                // XP5 : fusionner les balises du catalogue plateforme
                // avec les vues projet (VueNomsXml) si disponibles.
                val balisesCatalogue = CompletionXml.balises(contexte, catalogueCourant())
                val balisesProjet = completionBalisesProjet(contexte)
                (balisesCatalogue + balisesProjet).distinctBy { it.libelle }
            }

            is Contexte.NomAttribut -> CompletionXml.attributs(contexte, catalogueCourant())

            is Contexte.Valeur ->
                CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources)

            Contexte.Muet -> emptyList()
        }

    /**
     * XP5 : vues personnalisées du projet comme propositions de balises.
     * VueNomsXml porte les noms de classes du projet (venant de la
     * session Java) — on les propose comme balises quand l'utilisateur
     * tape `<`.
     */
    private fun completionBalisesProjet(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val vueNoms = classpath?.vueNoms ?: return emptyList()
        val prefixe = contexte.prefixeTape
        return vueNoms.nomsCommencantPar(prefixe, LIMITE_VUES_PROJET)
            .map { fqcn ->
                val nomSimple = fqcn.substringAfterLast('.')
                PropositionCompletionXml(
                    libelle = nomSimple,
                    genre = GenreCompletionXml.BALISE,
                    detail = "vue projet — $fqcn",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VUE_PROJET, nomSimple),
                )
            }
    }

    /**
     * Complétion DRAWABLE (XE5) — balises/attributs du catalogue fixe,
     * valeurs FUSIONNÉES avec le contributeur général (références `@…`
     * + énumérations du catalogue, dédoublonnées par libellé).
     */
    private fun completionDrawable(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionDrawablesXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionDrawablesXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionDrawablesXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion MENU (XE5) — comme DRAWABLE, contributeur menu. */
    private fun completionMenu(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionMenusXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionMenusXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionMenusXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /**
     * Complétion COULEUR (XP12) — contributeur couleurs (réutilisation
     * du contributeur drawables), valeurs FUSIONNÉES avec le
     * contributeur général (références `@color/…` du projet).
     */
    private fun completionCouleur(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionCouleursXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionCouleursXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionCouleursXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion ANIMATION (XP12) — tween, valeurs fusionnées (timing + références `@anim/…`). */
    private fun completionAnimation(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionAnimationsXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionAnimationsXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionAnimationsXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion ANIMATEUR (XP12) — propriété, valeurs fusionnées (timing + références `@animator/…`). */
    private fun completionAnimateur(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionAnimateursXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionAnimateursXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionAnimateursXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion XML_APP (XP12) — fichiers de configuration d'application. */
    private fun completionXmlApp(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionXmlApp.balises(contexte)

            is Contexte.NomAttribut -> CompletionXmlApp.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionXmlApp.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion NAVIGATION (XP12) — graphes de navigation, valeurs fusionnées. */
    private fun completionNavigation(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionNavigationXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionNavigationXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionNavigationXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion TRANSITION (XP12) — transitions du framework, valeurs fusionnées. */
    private fun completionTransition(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionTransitionsXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionTransitionsXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionTransitionsXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /** Complétion POLICE (XP12) — familles de polices, valeurs fusionnées. */
    private fun completionPolice(contexte: Contexte): List<PropositionCompletionXml> =
        when (contexte) {
            is Contexte.NomDeBalise -> CompletionPolicesXml.balises(contexte)

            is Contexte.NomAttribut -> CompletionPolicesXml.attributs(contexte)

            is Contexte.Valeur ->
                fusionValeurs(
                    CompletionValeursXml.valeurs(contexte, catalogueCourant(), indexRessources),
                    CompletionPolicesXml.valeurs(contexte),
                )

            Contexte.Muet -> emptyList()
        }

    /**
     * FUSION de listes de valeurs (XE5) — dédoublonnée par LIBELLÉ
     * (le catalogue et le contributeur de genre peuvent connaître la
     * même énumération), triée par clé de tri commune.
     */
    private fun fusionValeurs(vararg listes: List<PropositionCompletionXml>): List<PropositionCompletionXml> =
        listes.asSequence()
            .flatten()
            .distinctBy { it.libelle }
            .sortedBy { it.texteTri }
            .toList()

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

    /**
     * Correctifs d'un diagnostic (XE4) — le diagnostic visé est celui
     * dont le DÉBUT est [decalageDuDiagnostic] (l'hôte traduit la
     * plage LSP) ; PLUSIEURS diagnostics peuvent partager un début
     * (balise non fermée + xmlns manquant sur la même racine) : les
     * codes QUICK-FIXABLES sont essayés TOUS. Les éditions couvrent
     * ouvrante ET fermante quand la correction renomme une balise.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont des régimes distincts (session fermée, document inconnu,
     * codes sans correctif).
     */
    @Suppress("ReturnCount")
    override fun correctifs(
        uri: String,
        decalageDuDiagnostic: Int,
    ): List<CorrectifXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val candidats =
            diagnostics(uri).filter { it.debut == decalageDuDiagnostic }
        val correctifs = mutableListOf<CorrectifXml>()
        for (diagnostic in candidats) {
            when (diagnostic.code) {
                jo.lspxml.api.CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID ->
                    correctifs.addAll(CorrectifsXml.insererXmlnsAndroid(document.document))

                jo.lspxml.api.CodesDiagnosticsXml.TAG_INCONNU -> {
                    val element =
                        elementsDe(document.document)
                            .firstOrNull { it.plageNom.debut == decalageDuDiagnostic }
                    element?.let {
                        correctifs.addAll(CorrectifsXml.renommerBalise(it, catalogueCourant()))
                    }
                }

                else -> Unit
            }
        }
        return correctifs
    }

    /**
     * Symboles de document (S1) — miroir DOM → outline LSP. Le travail
     * de classification (RACINE / VUE / CONTENEUR / ENTREE_VALEURS…)
     * vit dans [SymbolesDocumentXml] ; la session ne fait que piocher
     * le document courant. Document inconnu/fermé : liste VIDE.
     */
    override fun symbolesDocument(uri: String): List<SymboleDocumentXml> =
        if (fermee) {
            emptyList()
        } else {
            documents[uri]?.let { SymbolesDocumentXml.depuis(it.document) } ?: emptyList()
        }

    /**
     * Plages pliables (XP11) — miroir DOM → foldingRange LSP. Le travail
     * de collecte vit dans [jo.lspxml.plis.PlisDocumentXml] ; la session
     * ne fait que piocher le document courant. Document inconnu/fermé :
     * liste VIDE.
     */
    override fun plagesPliables(uri: String): List<PlagePliableXml> =
        if (fermee) {
            emptyList()
        } else {
            documents[uri]?.let { PlisDocumentXml.depuis(it.document) } ?: emptyList()
        }

    /**
     * Plage renommable (S3 — rename borné des entrées values) — le
     * travail vit dans [RenommageXml] ; la session pioche le document
     * courant et l'index. Document inconnu/fermé : `null` (sous-rapport).
     */
        @Suppress("ReturnCount")
override fun plageRenommable(uri: String, decalage: Int): PlageRenommableXml? {
        if (fermee) return null
        val document = documents[uri] ?: return null
        val plage =
            RenommageXml.plageNomAuCurseur(document.document, decalage, indexRessources)
                ?: return null
        return PlageRenommableXml(plage, document.texte.substring(plage.debut, plage.fin))
    }

    /**
     * Occurrences à renommer (S3 — BORNÉ : déclarations de l'index +
     * références des documents ouverts). Les tampons ouverts voyagent
     * en COPIE (`uri → texte`) — le moteur ne prête jamais ses états
     * internes. Document inconnu/fermé : liste VIDE (l'hôte traduit en
     * erreur typée sans cible).
     */
        @Suppress("ReturnCount")
override fun renommage(uri: String, decalage: Int): List<EmplacementXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        return RenommageXml.occurrences(
            document.document,
            uri,
            decalage,
            indexRessources,
            documents.mapValues { (_, doc) -> doc.texte },
        )
    }

    /**
     * Références d'une ressource projet (lot L3) — le calcul vit dans
     * [ReferencesOccurrencesXml] (même preuve que le renommage borné :
     * déclarations de l'index, références des documents ouverts) ; la
     * session pioche le document courant et file les tampons OUVERTS
     * en copie. Document inconnu/fermé : liste VIDE.
     */
    @Suppress("ReturnCount")
    override fun references(
        uri: String,
        decalage: Int,
        inclureDeclaration: Boolean,
    ): List<EmplacementXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val occurrences =
            ReferencesOccurrencesXml.occurrences(
                document.document,
                uri,
                decalage,
                indexRessources,
                documents.mapValues { (_, doc) -> doc.texte },
            )
        return ReferencesOccurrencesXml.emplacements(occurrences, inclureDeclaration)
    }

    /**
     * Surlignage d'occurrences du document COURANT (lot L3) — les
     * occurrences prouvées de CE document (déclarante `@+id` en
     * écriture, références en lecture). Document inconnu/fermé :
     * liste VIDE.
     */
    @Suppress("ReturnCount")
    override fun surlignagesDocument(uri: String, decalage: Int): List<PlageSurligneeXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val occurrences =
            ReferencesOccurrencesXml.occurrences(
                document.document,
                uri,
                decalage,
                indexRessources,
                documents.mapValues { (_, doc) -> doc.texte },
            )
        return ReferencesOccurrencesXml.surlignages(occurrences, uri)
    }

    /**
     * Formatage (S3 — ré-indentation bornée) — le travail vit dans
     * [FormatXml] ; la session pioche le document courant. Document
     * inconnu/fermé : liste VIDE.
     */
        @Suppress("ReturnCount")
override fun formatter(uri: String, tailleIndentation: Int): List<EditionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        return FormatXml.editer(document.document, document.texte, tailleIndentation)
    }

    /**
     * Formatage BORNÉ d'une PLAGE (lot L3) — le calcul vit dans
     * [FormatXml.editer] (variante plage) ; seules les lignes qui
     * intersectent la plage sont candidates. Document inconnu/fermé :
     * liste VIDE.
     */
    @Suppress("ReturnCount")
    override fun formatter(
        uri: String,
        debutPlage: Int,
        finPlage: Int,
        tailleIndentation: Int,
    ): List<EditionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        return FormatXml.editer(document.document, document.texte, tailleIndentation, debutPlage, finPlage)
    }

    /**
     * Plages LIÉES de la balise sous le curseur (lot L3) — le calcul
     * vit dans [EditionLieeSelectionXml] ; `null` hors nom de balise
     * fermée (jamais de liaison inventée).
     */
    @Suppress("ReturnCount")
    override fun plagesLiees(uri: String, decalage: Int): PlageLieeXml? {
        if (fermee) return null
        val document = documents[uri] ?: return null
        return EditionLieeSelectionXml.plageLiee(document.document, decalage)
    }

    /**
     * Chaînes de sélection (lot L3) — le calcul (chaîne des nœuds
     * englobants, fusion des plages égales) vit dans
     * [EditionLieeSelectionXml].
     */
    @Suppress("ReturnCount")
    override fun selections(uri: String, decalages: List<Int>): List<ChaineSelectionXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        return decalages.map { decalage -> EditionLieeSelectionXml.chaine(document.document, decalage) }
    }

    /**
     * Couleurs du document (lot L3) — le calcul vit dans
     * [CouleursDocumentXml] (littéraux + références @color résolues
     * par l'index). Document inconnu/fermé : liste VIDE.
     */
    @Suppress("ReturnCount")
    override fun couleurs(uri: String): List<CouleurDocumentXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        return CouleursDocumentXml.couleurs(document.document, indexRessources)
    }

    /**
     * Présentation d'une couleur (lot L3) : le libellé canonique de la
     * couleur de la PLAGE d'origine (littéral ou référence) et son
     * édition de remplacement. Sans couleur sur la plage : liste VIDE
     * (l'éditeur garde sa forme — jamais d'invention).
     */
    @Suppress("ReturnCount")
    override fun presentationCouleur(
        uri: String,
        debut: Int,
        fin: Int,
        canaux: CanauxCouleurXml,
    ): List<PresentationCouleurXml> {
        if (fermee) return emptyList()
        val document = documents[uri] ?: return emptyList()
        val couleurs = CouleursDocumentXml.couleurs(document.document, indexRessources)
        val couleur =
            couleurs.firstOrNull { couleur -> couleur.debut == debut && couleur.fin == fin }
                ?: couleurs.firstOrNull { couleur -> debut < couleur.fin && fin > couleur.debut }
                ?: return emptyList()
        return listOf(CouleursDocumentXml.presentation(couleur))
    }

    /**
     * Modifications externes (lot L1) — l'index des ressources est
     * RE-SCANNÉ sur les racines courantes (delta mtime : un fichier
     * inchangé n'est jamais re-parsé) : une ressource créée ou modifiée
     * hors éditeur (build Gradle, terminal) devient visible à la
     * prochaine publication des diagnostics.
     */
    override fun rafraichirSources() {
        if (fermee) return
        val classpathCourant = classpath ?: return
        indexRessources.definir(classpathCourant.racinesSources)
        indexRessources.definirAar(classpathCourant.racinesAar)
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
}    // Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
    // sont des régimes distincts (session fermée, document inconnu,
    // garde cible — même patron que correctifs).

