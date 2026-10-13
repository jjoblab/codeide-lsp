package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.completion.CompletionKotlin
import jo.lspkotlin.diagnostics.DiagnosticsSemantiques
import jo.lspkotlin.diagnostics.DiagnosticsSyntaxiques
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.resolve.CandidatsImport
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.InterrogationsSymboles
import jo.lspkotlin.resolve.ResolutionKotlin
import jo.lspkotlin.resolve.SymbolesProjet
import java.io.File

/**
 * Session de langue du moteur vendoré (K2 → K3, LSP_KOTLIN.md §4.1
 * « session ») — dépositaire des documents ouverts : chaque didOpen/
 * didChange REPARSE intégralement (la synchronisation LSP est complète
 * à K2/K3 ; le re-parse de sous-arbre est un sujet postérieur), chaque
 * consultation relit l'arbre.
 *
 * K3 y AJOUTE l'état de classpath POUSSÉ par la façade
 * ([definirClasspath]) : la VUE de l'hôte (types/paquets/membres
 * binaires), l'INDEX des appelables Kotlin (PARTAGÉ par le moteur —
 * segments par empreinte, un jar inchangé n'est jamais redécodé) et le
 * SCAN des racines sources (partagé aussi : les fichiers OUVERTS
 * dominent — un document ouvert vit son arbre frais, le scan ne sert
 * que les fichiers FERMÉS).
 *
 * Le parse est celui du [ParseurKotlin] vendoré (tolérant : AUCUNE
 * entrée ne lève — cf. sa KDoc) ; les diagnostics sortent de
 * [DiagnosticsSyntaxiques] (purs, conservateurs D3) ; les requêtes de
 * symboles vivent dans [CompletionKotlin] et [InterrogationsSymboles]
 * (fonctions pures sur l'arbre + le contexte).
 *
 * Tolérance de fin de vie (protocole LSP : des notifications peuvent
 * encore arriver entre shutdown et exit) : après [close], les mutations
 * et requêtes sont des no-ops silencieux, [diagnostics] rend une liste
 * vide, [nombreDocuments] vaut 0 — la session ne retient RIEN (test de
 * fuite).
 *
 * Concurrence : verrou interne — les callbacks LSP4J de l'hôte arrivent
 * sur son pool ; les opérations sont courtes (parse complet d'un
 * document, requêtes sur index en mémoire), la sérialisation est
 * invisible.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — la classe
 * IMPLÉMENTE le contrat de session (10 méthodes d'interface K2+K3) +
 * les 2 constructeurs de contextes : le compte épouse le CONTRAT, pas
 * une dérive (même patron que les sessions du LSP Java).
 */
@Suppress("TooManyFunctions")
internal class SessionLangageKotlinParse(
    private val parseur: ParseurKotlin,
    private val indexAppelables: IndexAppelablesKotlin,
    private val scanProjet: ScanProjet,
) : SessionLangageKotlin {
    /** Verrou de l'état (documents + classpath + fermeture). */
    private val verrou = Any()

    /** Documents ouverts : uri → arbre du dernier texte connu. */
    private val documents = mutableMapOf<String, DocumentOuvert>()

    /** Classpath courant (null avant le premier definirClasspath). */
    private var classpath: ClasspathKotlin? = null

    /** Résolveur classpath courant (dégradé sans vue). */
    private var resolution: ResolutionKotlin = ResolutionKotlin(null)

    /** Fermée (shutdown/exit, fermeture de la façade hôte) ? */
    private var fermee = false

    /** Nombre de documents ouverts (observabilité, test de fuite). */
    override val nombreDocuments: Int
        get() = synchronized(verrou) { documents.size }

    override fun ouvrirDocument(
        uri: String,
        texte: CharSequence,
        script: Boolean,
    ) {
        synchronized(verrou) {
            if (fermee) return
            documents[uri] = DocumentOuvert(parseur.analyser(texte, script), texte, script)
        }
    }

    override fun modifierDocument(
        uri: String,
        texte: CharSequence,
        script: Boolean,
    ) {
        // Tolérant : un didChange sans didOpen préalable équivaut à une
        // ouverture (l'hôte ordonnance, le moteur ne s'y fie pas).
        ouvrirDocument(uri, texte, script)
    }

    override fun fermerDocument(uri: String) {
        synchronized(verrou) {
            documents.remove(uri)
        }
    }

    override fun diagnostics(uri: String): List<DiagnosticKotlin> =
        synchronized(verrou) {
            val document = documents[uri] ?: return emptyList()
            val syntaxiques = DiagnosticsSyntaxiques.calculer(document.arbre)
            // K4 : les diagnostics SÉMANTIQUES vivent sous le MÊME verrou
            // (le contexte de résolution lit l'état de classpath).
            val contexteNom = contexteNomDe(document, uri)
            val semantiques =
                DiagnosticsSemantiques.calculer(
                    document.arbre,
                    DiagnosticsSemantiques.Contexte(
                        texte = document.texte,
                        contexteNom = contexteNom,
                        resolution = resolution,
                        declarationsProjet = declarationsProjet(document, uri, contexteNom.paquet),
                        // Régime sans classpath poussé : AUCUN diagnostic
                        // non résolu (impossible de prouver une absence).
                        indexAppelables = if (classpath == null) null else indexAppelables,
                    ),
                )
            (syntaxiques + semantiques).sortedWith(compareBy({ it.debut }, { it.code }))
        }

    override fun definirClasspath(classpath: ClasspathKotlin) {
        synchronized(verrou) {
            if (fermee) return
            this.classpath = classpath
            resolution = ResolutionKotlin(classpath.vue)
            indexAppelables.definir(classpath.entrees, classpath.dossierIndex)
            // Scan partagé : redéfini si les racines ont changé (un
            // appel identique est un no-op — les documents OUVERTS
            // dominent les déclarations de toute façon).
            scanProjet.definirRacines(classpath.racinesSources)
        }
    }

    override fun completion(
        uri: String,
        decalage: Int,
    ): List<PropositionCompletionKotlin> =
        synchronized(verrou) {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            CompletionKotlin.propositions(
                document.arbre.racine,
                document.texte,
                decalage,
                contextePour(document, uri),
            )
        }

    override fun survol(
        uri: String,
        decalage: Int,
    ): ContenuSurvoleKotlin? =
        synchronized(verrou) {
            if (fermee) return null
            val document = documents[uri] ?: return null
            InterrogationsSymboles.survol(
                document.arbre.racine,
                decalage,
                contexteInterrogations(document, uri),
            )
        }

    override fun definitions(
        uri: String,
        decalage: Int,
    ): List<EmplacementKotlin> =
        synchronized(verrou) {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            InterrogationsSymboles.definitions(
                document.arbre.racine,
                decalage,
                uri,
                contexteInterrogations(document, uri),
            )
        }

    override fun importsCandidats(
        uri: String,
        decalage: Int,
    ): List<String> =
        synchronized(verrou) {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val contexteNom = contexteNomDe(document, uri)
            CandidatsImport.fqcns(
                racine = document.arbre.racine,
                decalage = decalage,
                contexteNom = contexteNom,
                resolution = resolution,
                declarationsProjet = declarationsProjet(document, uri, contexteNom.paquet),
                index = if (classpath == null) null else indexAppelables,
                vue = classpath?.vue,
            )
        }

    override fun close() {
        synchronized(verrou) {
            fermee = true
            documents.clear()
            classpath = null
            resolution = ResolutionKotlin(null)
        }
    }

    // ── Contextes de requête ───────────────────────────────────────────

    /** Contexte de complétion (document courant + projet + classpath). */
    private fun contextePour(
        document: DocumentOuvert,
        uri: String,
    ): CompletionKotlin.Contexte {
        val contexteNom = contexteNomDe(document, uri)
        return CompletionKotlin.Contexte(
            vue = classpath?.vue,
            index = indexAppelables,
            resolution = resolution,
            contexteNom = contexteNom,
            declarationsProjet = declarationsProjet(document, uri, contexteNom.paquet),
        )
    }

    /** Contexte d'interrogation (survol/définition). */
    private fun contexteInterrogations(
        document: DocumentOuvert,
        uri: String,
    ): InterrogationsSymboles.Contexte {
        val contexteNom = contexteNomDe(document, uri)
        return InterrogationsSymboles.Contexte(
            contexteNom = contexteNom,
            resolution = resolution,
            declarationsProjet = declarationsProjet(document, uri, contexteNom.paquet),
            index = indexAppelables,
            vue = classpath?.vue,
        )
    }

    /**
     * Contexte de NOMMAGE du document : paquet, imports (noms effectifs
     * → FQCN, jokers → paquets), classes du projet (FQCN → FQCN).
 *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod — le
     * when des DIRECTIVES d'import (joker/alias/plein) est le catalogue
     * des trois formes Kotlin, chaque branche est une ISSUE.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun contexteNomDe(
        document: DocumentOuvert,
        uri: String,
    ): ResolutionKotlin.ContexteNom {
        val fichier = KtFile(document.arbre.racine)
        val paquet = fichier.directivePackage()?.nomQualifie()
        val importsParNom = HashMap<String, String>()
        val importsJoker = mutableListOf<String>()
        for (directive in fichier.directivesImport()) {
            val nomQualifie = directive.nomQualifie() ?: continue
            if (nomQualifie.endsWith(".*")) {
                importsJoker.add(nomQualifie.removeSuffix(".*"))
                continue
            }
            val nomEffectif = directive.alias() ?: nomQualifie.substringAfterLast('.')
            if (nomEffectif.isNotEmpty()) {
                importsParNom[nomEffectif] = nomQualifie
            }
        }
        // Classes de premier niveau du projet (clé FQCN, lookups
        // « paquet.nom » de fqcnDuNomEcrit) — classes ET interfaces
        // (correctif K4 : la double garde K3 `!= CLASSE` suivie de
        // `!= INTERFACE` rejetait TOUT, classesProjet restait vide).
        val classesProjet = HashMap<String, String>()
        for (declaration in scanProjet.declarations() + declarationsDocument(document, uri, paquet)) {
            if (declaration.genre != GenreDeclaration.CLASSE && declaration.genre != GenreDeclaration.INTERFACE) {
                continue
            }
            val fqcn = (declaration.paquet?.let { "$it." } ?: "") + declaration.nom
            classesProjet[fqcn] = fqcn
        }
        return ResolutionKotlin.ContexteNom(paquet, importsParNom, importsJoker, classesProjet)
    }

    /**
     * Déclarations du projet CONSOMMÉES par les requêtes : celles du
     * document courant (arbre frais) + celles du scan partagé (fichiers
     * fermés), le document courant DOMINANT son doublon du scan.
     */
    private fun declarationsProjet(
        document: DocumentOuvert,
        uri: String,
        paquet: String?,
    ): List<DeclarationProjet> = declarationsDocument(document, uri, paquet) + scanProjet.declarations(uri)

    /** Déclarations du document courant (arbre frais). */
    private fun declarationsDocument(
        document: DocumentOuvert,
        uri: String,
        paquet: String?,
    ): List<DeclarationProjet> = SymbolesProjet.declarations(document.arbre.racine, uri, paquet)

    /** Document ouvert : arbre + texte du dernier contenu connu. */
    private class DocumentOuvert(
        public val arbre: ArbreSyntaxiqueKotlin,
        public val texte: CharSequence,
        public val script: Boolean,
    )
}
