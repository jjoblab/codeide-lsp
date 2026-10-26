package jo.lspkotlin.session

import jo.lspkotlin.api.AideSignatureKotlin
import jo.lspkotlin.api.ChaineSelectionKotlin
import jo.lspkotlin.api.CibleNavigationKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.DiagnosticKotlin
import jo.lspkotlin.api.EditionSourceKotlin
import jo.lspkotlin.api.EmplacementKotlin
import jo.lspkotlin.api.IndiceEnLigneKotlin
import jo.lspkotlin.api.PlagePliableKotlin
import jo.lspkotlin.api.PlageRenommableKotlin
import jo.lspkotlin.api.PlageSurligneeKotlin
import jo.lspkotlin.api.PreviewKotlin
import jo.lspkotlin.api.PropositionCompletionKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.SymboleDocumentKotlin
import jo.lspkotlin.api.SymboleEspaceKotlin
import jo.lspkotlin.completion.CompletionKotlin
import jo.lspkotlin.diagnostics.DiagnosticsSemantiques
import jo.lspkotlin.diagnostics.DiagnosticsSyntaxiques
import jo.lspkotlin.documentation.DocumentationKotlin
import jo.lspkotlin.format.FormateurKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.plis.PlisDocumentKotlin
import jo.lspkotlin.preview.PreviewsDocumentKotlin
import jo.lspkotlin.resolve.CandidatsImport
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import jo.lspkotlin.resolve.IndicesKotlin
import jo.lspkotlin.resolve.InterrogationsSymboles
import jo.lspkotlin.resolve.OccurrencesKotlin
import jo.lspkotlin.resolve.ResolutionKotlin
import jo.lspkotlin.resolve.SelectionsKotlin
import jo.lspkotlin.resolve.SignaturesAppels
import jo.lspkotlin.resolve.SuperKotlin
import jo.lspkotlin.resolve.SymbolesProjet
import jo.lspkotlin.resolve.SymbolesEspaceKotlin
import jo.lspkotlin.resolve.TypesKotlin
import jo.lspkotlin.symboles.SymbolesDocumentKotlin
import jo.lspkotlin.synthetics.ClassesSynthetiques
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
 * Concurrence : verrou LECTURE-ÉCRITURE (ADR 0212, demande D2 de
 * CodeIDE) — les callbacks LSP4J de l'hôte arrivent sur son pool ;
 * les MUTATIONS (didOpen/didChange/didClose, definirClasspath,
 * close) prennent le verrou d'écriture, TOUTES les requêtes celui de
 * LECTURE : deux documents se diagnosent, se complètent et se
 * survolent EN PARALLÈLE (l'audit de pureté ADR 0212 a verrouillé les
 * helpers — purs ou à état par appel — et synchronisé
 * [ClassesSynthetiques] en interne). Jamais de réentrance
 * lecture→écriture : aucune requête ne mute l'état de session.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — la classe
 * IMPLÉMENTE le contrat de session (10 méthodes d'interface K2+K3) +
 * les 2 constructeurs de contextes : le compte épouse le CONTRAT, pas
 * une dérive (même patron que les sessions du LSP Java).
 * LargeClass — la session est le DÉPOSITAIRE unique des documents
 * ouverts + du classpath poussé ; chaque requête LSP y vit comme UN
 * bloc synchronisé lisant l'état (documents, scan, synthétiques) —
 * l'éclater serait un partage d'état entre objets, leçon inverse du
 * contrat K2/K3. Les chantiers ADR 0208 (port KDoc cross-fichier) et
 * 0210 (port GO TO Super) y ajoutent les DEUX ports de lecture
 * bornée, même frontière.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class SessionLangageKotlinParse(
    private val parseur: ParseurKotlin,
    private val indexAppelables: IndexAppelablesKotlin,
    private val scanProjet: ScanProjet,
) : SessionLangageKotlin {
    /** Synthétiques Android (K5 : R, BuildConfig, bindings). */
    private val synthetiques = ClassesSynthetiques()

    /**
     * Verrou de l'état (documents + classpath + fermeture) — LECTURE-
     * ÉCRITURE (ADR 0212) : mutations en écriture, requêtes en lecture
     * parallèle.
     */
    private val verrou = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** Ouvre le verrou de LECTURE le temps du bloc (requêtes parallèles). */
    private inline fun <T> enLecture(bloc: () -> T): T {
        val cadenas = verrou.readLock()
        cadenas.lock()
        try {
            return bloc()
        } finally {
            cadenas.unlock()
        }
    }

    /** Ouvre le verrou d'ÉCRITURE le temps du bloc (mutations sérialisées). */
    private inline fun <T> enEcriture(bloc: () -> T): T {
        val cadenas = verrou.writeLock()
        cadenas.lock()
        try {
            return bloc()
        } finally {
            cadenas.unlock()
        }
    }

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
        get() = enLecture { documents.size }

    override fun ouvrirDocument(
        uri: String,
        texte: CharSequence,
        script: Boolean,
    ) {
        enEcriture {
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
        enEcriture {
            documents.remove(uri)
        }
    }

    override fun diagnostics(uri: String): List<DiagnosticKotlin> =
        enLecture {
            val document = documents[uri] ?: return emptyList()
            val syntaxiques = DiagnosticsSyntaxiques.calculer(document.arbre)
            // K4 : les diagnostics SÉMANTIQUES vivent sous le MÊME verrou
            // (le contexte de résolution lit l'état de classpath).
            val contexteNom = contexteNomDe(document, uri)
            val declarations = declarationsProjet(document, uri, contexteNom.paquet)
            val semantiques =
                DiagnosticsSemantiques.calculer(
                    document.arbre,
                    DiagnosticsSemantiques.Contexte(
                        texte = document.texte,
                        contexteNom = contexteNom,
                        resolution = resolution,
                        declarationsProjet = declarations,
                        // ADR 0221 — .gradle.kts/.main.kts : le récepteur
                        // implicite du CONTEXTE HÔTE (DSL Gradle, shell)
                        // n'est pas modélisé — les noms NUS y sont muets.
                        script = document.script,
                        // ADR 0211 — garde de sous-lecture : index non
                        // couvert (passe moteur en vol) ⇒ AUCUN diagnostic
                        // non résolu sur le classpath (impossible de
                        // prouver une absence, miroir « index froid =
                        // silence » du LSP XML) ; recalculé à CHAQUE
                        // requête — jamais d'état périmé après publication.
                        indexAppelables = indexSiPret(),
                        // ADR 0216 — voie « membres hérités » : le port du
                        // GO TO Super (arbre vif du frère OUVERT, sinon
                        // lecture disque + parse bornées) traverse les
                        // maillons PROJET de la fermeture des supertypes.
                        portFichier = portFichierDeclaration(declarations),
                        // Vague 3 (retour utilisateurs 25/10/2026) : la
                        // preuve d'absence sur un type PROJET sans
                        // héritage écrit exige l'univers des sources
                        // COMPLET — un scan amorcé mais pas publié
                        // rendrait une absence « prouvée » fausse
                        // (extension d'un fichier non scanné), D3.
                        scanProjetComplet = scanProjet.estUniversConnu(),
                    ),
                )
            (syntaxiques + semantiques).sortedWith(compareBy({ it.debut }, { it.code }))
        }

    override fun definirClasspath(classpath: ClasspathKotlin) {
        enEcriture {
            if (fermee) return
            this.classpath = classpath
            resolution = ResolutionKotlin(classpath.vue)
            // ADR 0211 (demande D1 de CodeIDE) : BRANCHEMENT quand le
            // moteur possède le nourrissage — passe en vol ou snapshot
            // couvrant ces empreintes — AUCUNE re-lecture ici (le
            // supplier de connexion ne paie plus d'E/S lourde). Hors
            // moteur nourricier (usages directs, packs anciens), le
            // contrat SYNCHRONE historique tient : la définition
            // construit le snapshot partagé ici et maintenant.
            if (!indexAppelables.couvre(classpath.entrees) &&
                !indexAppelables.nourrissageParMoteur
            ) {
                indexAppelables.definir(classpath.entrees, classpath.dossierIndex)
            }
            // Scan partagé : redéfini si les racines ont changé (un
            // appel identique est un no-op — les documents OUVERTS
            // dominent les déclarations de toute façon).
            scanProjet.definirRacines(classpath.racinesSources)
            // K5 : les MÊMES racines nourrissent les synthétiques
            // (paresseux, coupés par les classes générées réelles).
            synthetiques.definirRacines(classpath.racinesSources)
        }
    }

    override fun completion(
        uri: String,
        decalage: Int,
    ): List<PropositionCompletionKotlin> =
        enLecture {
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
        enLecture {
            if (fermee) return null
            val document = documents[uri] ?: return null
            InterrogationsSymboles.survol(
                document.arbre.racine,
                decalage,
                uri,
                contexteInterrogations(document, uri),
            )
        }

    override fun definitions(
        uri: String,
        decalage: Int,
    ): List<EmplacementKotlin> =
        enLecture {
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
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val contexteNom = contexteNomDe(document, uri)
            val declarations = declarationsProjet(document, uri, contexteNom.paquet)
            CandidatsImport.fqcns(
                racine = document.arbre.racine,
                decalage = decalage,
                contexteNom = contexteNom,
                resolution = resolution,
                declarationsProjet = declarations,
                // ADR 0211 : même garde de sous-lecture que les
                // diagnostics — un index non couvert n'invente NI
                // candidat NI absence.
                index = indexSiPret(),
                vue = classpath?.vue,
                // ADR 0216 : membres hérités — même prédicat que le
                // diagnostic (jamais d'import proposé pour un appel NU
                // déjà résolu par la fermeture des supertypes).
                portFichier = portFichierDeclaration(declarations),
            )
        }

    /**
     * Symboles de document (S1) — miroir AST → outline LSP. Le travail
     * de classification (CLASSE / FONCTION / PROPRIETE…) vit dans
     * [SymbolesDocumentKotlin] ; la session ne fait que piocher le
     * document courant. Document inconnu/fermé : liste VIDE.
     */
    override fun symbolesDocument(uri: String): List<SymboleDocumentKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val fichier = KtFile(document.arbre.racine)
            SymbolesDocumentKotlin.depuis(fichier)
        }

    /**
     * Previews Compose du document (C1) — la marche vit dans
     * [PreviewsDocumentKotlin] ; la session pioche le document courant
     * et fournit le PORT projet (documents ouverts d'abord, disque du
     * scan ensuite — même hiérarchie que [balayerAutresFichiers]).
     * Document inconnu/fermé : liste VIDE.
     */
    override fun previews(uri: String): List<PreviewKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val paquet = KtFile(document.arbre.racine).directivePackage()?.nomQualifie()
            val contexteNom = contexteNomDe(document, uri)
            PreviewsDocumentKotlin.collecter(
                PreviewsDocumentKotlin.Contexte(
                    uri = uri,
                    texte = document.texte,
                    contexteNom = contexteNom,
                    resolution = resolution,
                    declarationsProjet = declarationsProjet(document, uri, paquet),
                    port = portPreviews,
                ),
                document.arbre.racine,
                paquet,
            )
        }

    /**
     * Port de la collecte previews vers le RESTE du projet : arbre
     * VIVANT du document ouvert d'abord, fichier du scan ensuite
     * (re-parsé à la demande — le coût est borné par le budget de la
     * collecte, un fichier illisible rend un inline ÉCHOUÉ typé).
     */
    private val portPreviews: PreviewsDocumentKotlin.Port =
        object : PreviewsDocumentKotlin.Port {
            // Exemption detekt ciblée (règle 16) : SwallowedException —
            // l'exception EST le signal du sous-rapport D3 (miroir
            // balayerAutresFichiers).
            @Suppress("SwallowedException")
            override fun fichierDe(uri: String): Pair<ArbreSyntaxiqueKotlin, CharSequence>? {
                documents[uri]?.let { document -> return document.arbre to document.texte }
                return try {
                    val texte = File(java.net.URI(uri)).readText()
                    parseur.analyser(texte, script = false) to texte
                } catch (t: Throwable) {
                    null // illisible : inline échoué typé (D3)
                }
            }

            override fun contexteNomDe(racine: NoeudSyntaxique): ResolutionKotlin.ContexteNom =
                contexteNomPourArbre(racine, scanProjet.declarations())
        }

    /**
     * Plages pliables (S2) — le travail de collecte vit dans
     * [PlisDocumentKotlin] ; la session ne fait que piocher le document
     * courant. Document inconnu/fermé : liste VIDE.
     */
    override fun plagesPliables(uri: String): List<PlagePliableKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            PlisDocumentKotlin.depuis(document.arbre.racine)
        }

    /**
     * Aide à la signature (S1) — le travail de résolution vit dans
     * [SignaturesAppels] ; la session ne fait que piocher le document
     * courant et filer son contexte. Document inconnu/fermé : `null`.
     */
    override fun signature(
        uri: String,
        decalage: Int,
    ): AideSignatureKotlin? =
        enLecture {
            if (fermee) return null
            val document = documents[uri] ?: return null
            val contexteNom = contexteNomDe(document, uri)
            SignaturesAppels.aide(
                document.arbre.racine,
                decalage,
                SignaturesAppels.Contexte(
                    contexteNom = contexteNom,
                    resolution = resolution,
                    declarationsProjet = declarationsProjet(document, uri, contexteNom.paquet),
                    index = indexAppelables,
                    vue = classpath?.vue,
                ),
            )
        }

    /**
     * Références (S2) — cible résolue par la chaîne des définitions,
     * occurrences prouvées fichier par fichier par [OccurrencesKotlin]
     * (document courant d'abord, autres fichiers ensuite).
     */
    override fun references(
        uri: String,
        decalage: Int,
        inclureDeclaration: Boolean,
    ): List<EmplacementKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val cible = cibleAuCurseur(document, uri, decalage) ?: return emptyList()
            val resultat = mutableListOf<EmplacementKotlin>()
            if (inclureDeclaration) {
                resultat.add(EmplacementKotlin(cible.uri, cible.debutNom, cible.finNom))
            }
            for ((uriFichier, occurrences) in occurrencesParFichier(document, uri, cible)) {
                for (occurrence in occurrences) {
                    // La déclaration vit DÉJÀ en tête (inclureDeclaration)
                    // ou n'est PAS demandée — dans les deux cas, la liste
                    // des occurrences ne la répète jamais.
                    if (occurrence.estDeclaration) continue
                    resultat.add(EmplacementKotlin(uriFichier, occurrence.debut, occurrence.fin))
                }
            }
            resultat
        }

    /**
     * Surlignages (S2) — occurrences du document COURANT seul, la
     * déclaration en écriture, les références en lecture.
     */
    override fun surlignagesDocument(uri: String, decalage: Int): List<PlageSurligneeKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val cible = cibleAuCurseur(document, uri, decalage) ?: return emptyList()
            val contexteNom = contexteNomDe(document, uri)
            OccurrencesKotlin.surlignages(
                document.arbre.racine,
                uri,
                cible,
                OccurrencesKotlin.ContexteFichier(
                    contexteNom,
                    declarationsProjet(document, uri, contexteNom.paquet),
                ),
                resolution,
            )
        }

    /** Plage renommable (S2) — miroir du prepareRename honnête Java. */
    override fun plageRenommable(uri: String, decalage: Int): PlageRenommableKotlin? =
        enLecture {
            if (fermee) return null
            val document = documents[uri] ?: return null
            OccurrencesKotlin.plageRenommable(
                document.arbre.racine,
                decalage,
                uri,
                contexteInterrogations(document, uri),
            )
        }

    /**
     * Occurrences à renommer (S2) — déclaration TOUJOURS incluse
     * (le rename la remplace comme les usages) ; les occurrences
     * reviennent ordonnées par fichier (document déclarant d'abord).
     */
    override fun renommage(uri: String, decalage: Int): List<EmplacementKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val cible = cibleAuCurseur(document, uri, decalage) ?: return emptyList()
            val resultat = mutableListOf<EmplacementKotlin>()
            for ((uriFichier, occurrences) in occurrencesParFichier(document, uri, cible)) {
                for (occurrence in occurrences) {
                    resultat.add(EmplacementKotlin(uriFichier, occurrence.debut, occurrence.fin))
                }
            }
            resultat
        }

    /**
     * Formatage BORNÉ du document ENTIER (L2, ADR 0202) — le calcul
     * vit dans [FormateurKotlin] (ré-indentation canonique seule, le
     * contenu n'est jamais réécrit, refus honnête sur arbre en erreur).
     * Document inconnu/fermé : liste VIDE.
     */
    override fun formatage(
        uri: String,
        tailleIndentation: Int,
    ): List<EditionSourceKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            FormateurKotlin.editer(document.arbre, document.texte, tailleIndentation)
        }

    /**
     * Formatage BORNÉ d'une PLAGE (L2, ADR 0202) — mêmes règles que
     * [formatage], restreintes aux lignes qui INTERSECTENT la plage
     * demandée (une édition hors plage décalerait le buffer du client).
     */
    override fun formatage(
        uri: String,
        debutPlage: Int,
        finPlage: Int,
        tailleIndentation: Int,
    ): List<EditionSourceKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            FormateurKotlin.editer(document.arbre, document.texte, tailleIndentation, debutPlage, finPlage)
        }

    /**
     * Définitions de TYPE (L2) — la chaîne de résolution vit dans
     * [TypesKotlin] (même conservatisme que les définitions : une
     * cible sans source dans le projet n'est PAS servie). Document
     * inconnu/fermé : liste VIDE.
     */
    override fun definitionsType(
        uri: String,
        decalage: Int,
    ): List<EmplacementKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            TypesKotlin.definitionsType(
                document.arbre.racine,
                decalage,
                uri,
                contexteInterrogations(document, uri),
            )
        }

    /**
     * Implémentations du type en contexte (L2) — preuve par FQCN
     * ([TypesKotlin.implementationsDansArbre]) : chaque candidate du
     * PROJET prouve son supertype par les imports de SON fichier.
     * Balayage miroir des occurrences : document courant (arbre frais)
     * en tête, puis les autres fichiers — pré-filtre textuel sur le
     * nom du supertype (un héritier l'écrit dans sa clause).
     */
    override fun implementations(
        uri: String,
        decalage: Int,
    ): List<EmplacementKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val cible =
                TypesKotlin.typeEnContexte(
                    document.arbre.racine,
                    decalage,
                    uri,
                    contexteInterrogations(document, uri),
                ) ?: return emptyList()
            val resultat = mutableListOf<EmplacementKotlin>()
            val contexteNom = contexteNomDe(document, uri)
            resultat.addAll(
                TypesKotlin.implementationsDansArbre(
                    document.arbre.racine,
                    uri,
                    cible,
                    OccurrencesKotlin.ContexteFichier(
                        contexteNom,
                        declarationsProjet(document, uri, contexteNom.paquet),
                    ),
                    resolution,
                ),
            )
            for (fichier in balayerAutresFichiers(uri, cible.nom)) {
                resultat.addAll(
                    TypesKotlin.implementationsDansArbre(
                        fichier.arbre.racine,
                        fichier.uri,
                        cible,
                        fichier.contexte,
                        resolution,
                    ),
                )
            }
            resultat
        }

    /**
     * Cibles GO TO « Super » (ADR 0210) — les DEUX chemins du moteur
     * [SuperKotlin] : (1) MEMBRE au curseur (cible occurrences —
     * déclaration outrepassante ou usage résolu) → le même-nommé
     * dans chaque supertype TRANSITIF projet du type déclarant ;
     * (2) sinon le TYPE en contexte (référence de type, à défaut la
     * classe englobante — miroir contextType ecj) → ses supertypes
     * DIRECTS. Chaque maillon projet est lu par le PORT de fichier
     * (arbre vif du document ouvert, sinon lecture disque bornée —
     * même budget que le KDoc cross-fichier ADR 0208) et prouve sa
     * clause ÉCRITE par les imports de SON fichier.
     */
    override fun superDefinition(
        uri: String,
        decalage: Int,
    ): List<CibleNavigationKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val contexte = contexteInterrogations(document, uri)
            val port = portFichierDeclaration(contexte.declarationsProjet)
            // 1) CHEMIN MEMBRE — la cible occurrences prouve le membre
            //    visé (conteneur FQCN — jamais d'homonymie).
            val cible = OccurrencesKotlin.cibleAuCurseur(document.arbre.racine, decalage, uri, contexte)
            if (cible != null && cible.conteneur != null && cible.genre in SuperKotlin.GENRES_MEMBRES) {
                SuperKotlin.superDuMembre(cible, port, contexte.declarationsProjet, resolution)
                    .takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            // 2) CHEMIN TYPE — supertypes DIRECTS du type en contexte.
            SuperKotlin.typeEnContexte(document.arbre.racine, decalage, uri, contexte)
                ?.let { return SuperKotlin.superDuType(it, port, contexte.declarationsProjet, resolution) }
            emptyList()
        }

    /**
     * Indices en ligne d'une plage (L2) — noms de paramètres aux
     * appels POSITIONNELS prouvés (chaîne de l'aide à la signature)
     * et types CERTAINS des déclarations sans type écrit (littéraux
     * canoniques, constructeurs résolus) ; le calcul vit dans
     * [IndicesKotlin], l'incertain ne rend RIEN (D3).
     */
    override fun indices(
        uri: String,
        debutPlage: Int,
        finPlage: Int,
    ): List<IndiceEnLigneKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            val contexteNom = contexteNomDe(document, uri)
            val declarations = declarationsProjet(document, uri, contexteNom.paquet)
            IndicesKotlin.indices(
                document.arbre.racine,
                uri,
                debutPlage,
                finPlage,
                IndicesKotlin.Contexte(
                    signatures =
                        SignaturesAppels.Contexte(
                            contexteNom = contexteNom,
                            resolution = resolution,
                            declarationsProjet = declarations,
                            index = indexAppelables,
                            vue = classpath?.vue,
                        ),
                    interrogations =
                        InterrogationsSymboles.Contexte(
                            contexteNom = contexteNom,
                            resolution = resolution,
                            declarationsProjet = declarations,
                            index = indexAppelables,
                            vue = classpath?.vue,
                        ),
                ),
            )
        }

    /**
     * Symboles de l'ESPACE de travail (L2) — déclarations nommées des
     * documents OUVERTS (arbres vivants) puis des racines du scan
     * (fichiers fermés), filtrées par [SymbolesEspaceKotlin] (requête
     * contient, locales exclues, plafonné, ordonné par nom).
     */
    override fun symbolesEspace(requete: String): List<SymboleEspaceKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val declarations = mutableListOf<DeclarationProjet>()
            for ((uriOuvert, document) in documents) {
                val paquet = KtFile(document.arbre.racine).directivePackage()?.nomQualifie()
                declarations.addAll(SymbolesProjet.declarations(document.arbre.racine, uriOuvert, paquet))
            }
            declarations.addAll(scanProjet.declarations())
            SymbolesEspaceKotlin.rechercher(requete, declarations)
        }

    /**
     * Chaînes de sélection (L2) — le calcul (chaîne des ancêtres,
     * fusion des plages égales) vit dans [SelectionsKotlin].
     */
    override fun selections(
        uri: String,
        decalages: List<Int>,
    ): List<ChaineSelectionKotlin> =
        enLecture {
            if (fermee) return emptyList()
            val document = documents[uri] ?: return emptyList()
            decalages.map { decalage -> SelectionsKotlin.chaine(document.arbre.racine, decalage) }
        }

    /**
     * Modifications externes (lot L1) — le scan des racines sources est
     * RAFRAÎCHI (delta mtime) : la prochaine republication des
     * diagnostics voit les déclarations des fichiers fermés modifiés
     * hors éditeur. Les documents ouverts (arbres vivants) dominent de
     * toute façon.
     */
    override fun rafraichirSources() {
        enLecture {
            if (fermee) return
        }
        scanProjet.rafraichir()
    }

    override fun close() {
        enEcriture {
            fermee = true
            documents.clear()
            classpath = null
            resolution = ResolutionKotlin(null)
            // K5 : les synthétiques suivent le scan (racines perdues).
            synthetiques.definirRacines(emptyList())
        }
    }

    // ── Contextes de requête ───────────────────────────────────────────

    /**
     * Index des appelables SI le snapshot partagé COUVERT le classpath
     * courant (ADR 0211) — `null` sinon : une passe moteur en vol ou
     * un passage annulé laisse l'index partiel, et les requêtes qui
     * prouvent par l'ABSENCE (diagnostics `kt.unresolved`, candidats
     * d'import) doivent se TAIRE plutôt qu'inventer un faux négatif.
     * Recalculé à CHAQUE requête : la publication de la passe rend
     * l'index à la requête SUIVANTE, sans redéfinition ni pousse.
     */
    private fun indexSiPret(): IndexAppelablesKotlin? {
        val classpathCourant = classpath ?: return null
        return if (indexAppelables.couvre(classpathCourant.entrees)) indexAppelables else null
    }

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

    /** Contexte d'interrogation (survol/définition).
     *
     * ADR 0208 (option 2 de l'ADR 0207) : le contexte porte le PORT
     * KDoc cross-fichier — la déclaration élue d'un AUTRE fichier du
     * projet retrouve sa KDoc (document ouvert → arbre vif, fichier
     * fermé → lecture disque + parse bornées, miroir du coût du
     * balayage d'occurrences). */
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
            kdocDeclaration = { declaration -> kdocDeDeclaration(declaration) },
        )
    }

    // ── Occurrences S2 (references / documentHighlight / rename) ────────

    /** Cible du symbole au curseur (miroir de la chaîne des définitions). */
    private fun cibleAuCurseur(
        document: DocumentOuvert,
        uri: String,
        decalage: Int,
    ): OccurrencesKotlin.Cible? =
        OccurrencesKotlin.cibleAuCurseur(document.arbre.racine, decalage, uri, contexteInterrogations(document, uri))

    /**
     * Occurrences de la cible PAR FICHIER : document courant (arbre
     * frais) d'abord, autres documents ouverts ensuite, fichiers fermés
     * des racines en marche finale (pré-filtre textuel — un fichier qui
     * ne porte pas le nom n'est pas parsé).
     */
    private fun occurrencesParFichier(
        document: DocumentOuvert,
        uri: String,
        cible: OccurrencesKotlin.Cible,
    ): Map<String, List<OccurrencesKotlin.Occurrence>> {
        val resultat = LinkedHashMap<String, List<OccurrencesKotlin.Occurrence>>()
        val contexteNom = contexteNomDe(document, uri)
        resultat[uri] =
            OccurrencesKotlin.occurrencesDansArbre(
                document.arbre.racine,
                uri,
                cible,
                OccurrencesKotlin.ContexteFichier(
                    contexteNom,
                    declarationsProjet(document, uri, contexteNom.paquet),
                ),
                resolution,
            )
        for (fichier in balayerAutresFichiers(uri, cible.nom)) {
            resultat[fichier.uri] =
                OccurrencesKotlin.occurrencesDansArbre(
                    fichier.arbre.racine,
                    fichier.uri,
                    cible,
                    fichier.contexte,
                    resolution,
                )
        }
        return resultat
    }

    /** Fichier balayé : URI + arbre + contexte prêt pour les preuves. */
    private class FichierBalaye(
        val uri: String,
        val arbre: ArbreSyntaxiqueKotlin,
        val contexte: OccurrencesKotlin.ContexteFichier,
    )

    /**
     * KDoc d'une déclaration TIERCE (ADR 0208 — option 2 de l'ADR
     * 0207, port du [contexteInterrogations]) : document OUVERT → son
     * arbre VIF (aucun re-parse, miroir du document courant) ; fichier
     * FERMÉ → lecture disque + parse bornées (le même coût que le
     * balayage d'occurrences d'UN fichier, une fois par survol tiers —
     * budget documenté de l'ADR). Une lecture impossible (fichier
     * disparu, illisible, inanalysable) ou une déclaration sans KDoc
     * sous-rapportent HONNÊTEMENT (null → signature seule, jamais
     * d'exception).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException —
     * l'exception EST le signal du sous-rapport D3 (miroir exact de
     * [balayerAutresFichiers]).
     */
    @Suppress("SwallowedException")
    private fun kdocDeDeclaration(declaration: DeclarationProjet): String? {
        // 1. Document OUVERT : l'arbre est déjà en mémoire — jumelage
        //    EXACT par plage du nom (même garde que le document
        //    courant), jamais le disque périmé.
        documents[declaration.uri]?.let { document ->
            return InterrogationsSymboles.declarationViveAPlage(document.arbre.racine, declaration)
                ?.let { vive -> DocumentationKotlin.kdocDe(vive) }
        }
        // 2. Fichier FERMÉ : lecture disque + parse (plage du scan =
        //    plage du disque — le scan ne sonde QUE les fichiers
        //    fermés, jamais un tampon vivant).
        return try {
            val texte = File(java.net.URI(declaration.uri)).readText()
            val arbre = parseur.analyser(texte, script = false)
            InterrogationsSymboles.declarationViveAPlage(arbre.racine, declaration)
                ?.let { vive -> DocumentationKotlin.kdocDe(vive) }
        } catch (t: Throwable) {
            null // illisible/inanalysable : signature seule (D3)
        }
    }

    /**
     * Port GO TO « Super » (ADR 0210) : le FICHIER DÉCLARANT d'une
     * déclaration projet — arbre vif du document OUVERT, sinon lecture
     * disque + parse bornées (même budget que [kdocDeDeclaration]) —
     * portant SON contexte FQCN (imports de CE fichier, classes
     * réelles). Un maillon illisible est ABSENT (clôture transitive
     * partielle honnête D3).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException —
     * l'exception EST le signal du sous-rapport D3 (miroir exact de
     * [balayerAutresFichiers]).
     */
    private fun portFichierDeclaration(declarations: List<DeclarationProjet>): SuperKotlin.PortFichier =
        SuperKotlin.PortFichier { declaration -> fichierDeclarationDe(declaration, declarations) }

    @Suppress("SwallowedException")
    private fun fichierDeclarationDe(
        declaration: DeclarationProjet,
        declarations: List<DeclarationProjet>,
    ): SuperKotlin.FichierDeclaration? {
        // 1. Document OUVERT : arbre vif (jamais le disque périmé).
        documents[declaration.uri]?.let { document ->
            return SuperKotlin.FichierDeclaration(
                document.arbre,
                OccurrencesKotlin.ContexteFichier(
                    contexteNomPourArbre(document.arbre.racine, declarations),
                    declarations,
                ),
            )
        }
        // 2. Fichier FERMÉ : lecture disque + parse bornées.
        return try {
            val texte = File(java.net.URI(declaration.uri)).readText()
            val arbre = parseur.analyser(texte, script = false)
            SuperKotlin.FichierDeclaration(
                arbre,
                OccurrencesKotlin.ContexteFichier(
                    contexteNomPourArbre(arbre.racine, declarations),
                    declarations,
                ),
            )
        } catch (t: Throwable) {
            null // illisible/inanalysable : maillon absent (D3)
        }
    }

    /**
     * Balayage des AUTRES fichiers du projet : documents ouverts (arbres
     * vivants, le disque serait périmé) puis fichiers fermés des racines
     * (re-parsés à la demande, bornés par le plafond du scan). Un
     * fichier illisible est ABSENT (partiel honnête D3).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue des DEUX tolérances (illisible, inanalysable) et
     * SwallowedException — l'exception EST le signal du sous-rapport
     * D3 (miroir exact de ScanProjet.scanner).
     */
    @Suppress("LoopWithTooManyJumpStatements", "SwallowedException")
    private fun balayerAutresFichiers(
        uriCourant: String,
        nom: String,
    ): List<FichierBalaye> {
        val resultat = mutableListOf<FichierBalaye>()
        val autresOuverts = documents.filterKeys { cle -> cle != uriCourant }
        for ((uri, document) in autresOuverts) {
            if (!document.texte.contains(nom)) continue
            val paquet = KtFile(document.arbre.racine).directivePackage()?.nomQualifie()
            val declarations =
                scanProjet.declarations(exclureUri = uri) +
                    SymbolesProjet.declarations(document.arbre.racine, uri, paquet)
            resultat.add(
                FichierBalaye(
                    uri,
                    document.arbre,
                    OccurrencesKotlin.ContexteFichier(
                        contexteNomPourArbre(document.arbre.racine, declarations),
                        declarations,
                    ),
                ),
            )
        }
        val couverts = autresOuverts.keys + uriCourant
        for (uri in scanProjet.fichiersSources(exclure = couverts)) {
            val texte =
                try {
                    File(java.net.URI(uri)).readText()
                } catch (t: Throwable) {
                    continue // illisible : absent (D3)
                }
            if (!texte.contains(nom)) continue
            val arbre =
                try {
                    parseur.analyser(texte, script = false)
                } catch (t: Throwable) {
                    continue // inanalysable : absent (D3)
                }
            val declarations = scanProjet.declarations()
            resultat.add(
                FichierBalaye(
                    uri,
                    arbre,
                    OccurrencesKotlin.ContexteFichier(
                        contexteNomPourArbre(arbre.racine, declarations),
                        declarations,
                    ),
                ),
            )
        }
        return resultat
    }

    /**
     * Contexte de NOMMAGE du document : paquet, imports (noms effectifs
     * → FQCN, jokers → paquets), classes du projet (FQCN → FQCN).
     */
    private fun contexteNomDe(
        document: DocumentOuvert,
        uri: String,
    ): ResolutionKotlin.ContexteNom {
        val paquet = KtFile(document.arbre.racine).directivePackage()?.nomQualifie()
        // Dominance des documents OUVERTS (ADR 0208) : leurs arbres
        // vivants évincent leurs copies du scan — un type renommé dans un
        // document ouvert résout par SON nom frais (les FQCN des fichiers
        // fermés restent au scan).
        val reelles =
            scanProjet.declarations().filter { it.uri !in documents.keys } +
                autresDeclarationsOuvertes(uri) +
                declarationsDocument(document, uri, paquet)
        return contexteNomPourArbre(document.arbre.racine, reelles + synthetiquesCoupees(reelles))
    }

    /**
     * Contexte de nommage d'un ARBRE QUELCONQUE (document courant ou
     * fichier balayé par les occurrences S2) : paquet + imports lus
     * depuis l'arbre, classes du projet fournies par l'appelant
     * (top-level CLASSE/INTERFACE seulement, FQCN → FQCN — lookups
     * « paquet.nom » de fqcnDuNomEcrit ; K5 multi-langue : les types
     * JAVA du scan sont dedans, la bascule Java↔Kotlin résout sans
     * frontière).
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod — le
     * when des DIRECTIVES d'import (joker/alias/plein) est le catalogue
     * des trois formes Kotlin, chaque branche est une ISSUE.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun contexteNomPourArbre(
        racine: NoeudSyntaxique,
        classesSources: List<DeclarationProjet>,
    ): ResolutionKotlin.ContexteNom {
        val fichier = KtFile(racine)
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
        // Classes de premier niveau du projet (clé FQCN — correctif K4 :
        // la double garde K3 `!= CLASSE` suivie de `!= INTERFACE`
        // rejetait TOUT, classesProjet restait vide).
        // ADR 0220 : les OBJETS nommés sont des TYPES référençables
        // comme les classes (`Fabrique.creer()`, `expect object` KMP,
        // singletons Kotlin) — sans eux la complétion membre après
        // `Objet.` était MUETTE (fqcnDuNomEcrit ne résolvait pas).
        val classesProjet = HashMap<String, String>()
        for (declaration in classesSources) {
            if (declaration.genre != GenreDeclaration.CLASSE &&
                declaration.genre != GenreDeclaration.INTERFACE &&
                declaration.genre != GenreDeclaration.OBJET
            ) {
                continue
            }
            if (declaration.conteneur != null) continue
            val fqcn = (declaration.paquet?.let { "$it." } ?: "") + declaration.nom
            classesProjet[fqcn] = fqcn
        }
        return ResolutionKotlin.ContexteNom(paquet, importsParNom, importsJoker, classesProjet)
    }

    /**
     * Déclarations du projet CONSOMMÉES par les requêtes : celles du
     * document courant (arbre frais) + celles des AUTRES documents
     * OUVERTS (arbres vivants — ADR 0208 : leurs copies du scan seraient
     * PÉRIMÉES dès qu'ils sont édités, plages comprises ; la règle
     * documentée « les documents OUVERTS dominent » s'applique à TOUS
     * les documents, pas au seul courant) + celles du scan partagé
     * (fichiers FERMÉS, `.kt` ET `.java` — multi-langue K5), le document
     * courant DOMINANT son doublon du scan ; les SYNTHÉTIQUES Android
     * (K5) ferment la marche, COUPÉES dès qu'une classe réelle
     * homonyme existe (génération post-build = source de vérité, motif
     * sonde v0.108.0).
     */
    private fun declarationsProjet(
        document: DocumentOuvert,
        uri: String,
        paquet: String?,
    ): List<DeclarationProjet> {
        val urisOuverts = documents.keys + uri
        val reelles =
            declarationsDocument(document, uri, paquet) +
                autresDeclarationsOuvertes(uri) +
                scanProjet.declarations().filter { it.uri !in urisOuverts }
        return reelles + synthetiquesCoupees(reelles)
    }

    /**
     * Déclarations VIVES des AUTRES documents ouverts (arbres en
     * mémoire — jamais le disque périmé), chaque fichier portant SON
     * paquet (miroir de [balayerAutresFichiers]).
     */
    private fun autresDeclarationsOuvertes(uriCourant: String): List<DeclarationProjet> =
        documents.entries
            .filter { (cle, _) -> cle != uriCourant }
            .flatMap { (cle, doc) ->
                SymbolesProjet.declarations(
                    doc.arbre.racine,
                    cle,
                    KtFile(doc.arbre.racine).directivePackage()?.nomQualifie(),
                )
            }

    /**
     * Synthétiques hors des classes réelles — la COUPURE retire le
     * groupe ENTIER (classe + membres : la classe générée porte les
     * siens, garder les nôtres ferait des doublons de complétion).
     */
    private fun synthetiquesCoupees(reelles: List<DeclarationProjet>): List<DeclarationProjet> {
        val fqcnsReels =
            reelles
                .asSequence()
                .filter {
                    (it.genre == GenreDeclaration.CLASSE || it.genre == GenreDeclaration.INTERFACE) &&
                        it.conteneur == null
                }
                .map { (it.paquet?.let { paquet -> "$paquet." } ?: "") + it.nom }
                .toSet()
        return synthetiques.groupes()
            .filter { groupe -> groupe.fqcn !in fqcnsReels }
            .flatMap { groupe -> groupe.declarations }
    }

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
