package jo.lspkotlin.resolve

import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.chaineAncetresA
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Résolution des références SIMPLES par nom (K4) — le prédicat PARTAGÉ
 * du diagnostic `kt.unresolved` et des quick-fixes d'import : la chaîne
 * COMPLÈTE du modèle K3/K4 doit échouer pour qu'un nom soit déclaré
 * non résolu :
 *
 *  1. locales/paramètres en portée (la plus proche gagne) ;
 *  2. membres des classes englobantes (this implicite) ;
 *  2-ter. **membres HÉRITÉS** (ADR 0216 — découverte E3 du rapport
 *     d'audit §14) : supertypes ÉCRITS de la classe englobante, résolus
 *     en FQCN, sondés sur la fermeture transitive mixte classpath+projet
 *     — sans cette voie, un appel NU à un membre du supertype binaire
 *     (`setContentView(…)` dans un `class MainActivity :
 *     AppCompatActivity()`, `instantiate(…)` dans un `FragmentFactory`)
 *     était un FAUX `kt.unresolved` alors même que le classpath portait
 *     la classe et sa méthode ;
 *  3. déclarations de premier niveau du FICHIER ;
 *  4. déclarations du PROJET (scan — tous genres) ;
 *  5. classes : nom écrit résolvable (imports, paquet courant, imports
 *     par défaut de Kotlin) ou classe homonyme du projet ;
 *  6. appelables de premier niveau du CLASSPATH **en portée du
 *     fichier** (index Kotlin, `println`… — ADR 0203 : paquet courant,
 *     import exact, joker ou paquet par défaut ; le prédicat est
 *     partagé avec le survol d'appelable en portée).
 *
 * Conservateur (D3) : une voie qui réussit suffit — on ne distingue pas
 * les raisons d'échec, l'appelant tranche (diagnostic ou candidat).
 *
 * Exemptions detekt ciblées (règle 16) : LongParameterList — le
 * CONTEXTE de résolution complet est FILÉ (patron TypageExpression) ;
 * ReturnCount — chaque voie est une ISSUE distincte du catalogue ;
 * CyclomaticComplexMethod (ADR 0219) — la voie `with` sonde aussi les
 * PROPRIÉTÉS SYNTHÉTIQUES Java du récepteur implicite : une ISSUE de
 * plus du même catalogue.
 */
@Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
internal object ResolutionReferences {
    /**
     * Le nom [nom] utilisé à [decalage] résout-il par QUELCONQUE voie ?
     *
     * @param racine racine du document porteur.
     * @param decalage position de la référence.
     * @param nom nom de la référence.
     * @param contexteNom contexte de nommage (imports, paquet, projet).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet (document d'abord).
     * @param index appelables de premier niveau du classpath (`null` en
     *        régime dégradé — la voie 6 échoue alors silencieusement).
     * @param portFichier lecture des fichiers FRÈRES déclarants (voie
     *        2-ter — `null` = fermeture limitée aux clauses ÉCRITES du
     *        document courant, comportement antérieur ; la session
     *        fournit le port de [SuperKotlin], miroir du GO TO Super).
     */
    internal fun estResolu(
        racine: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        index: IndexAppelablesKotlin?,
        portFichier: SuperKotlin.PortFichier? = null,
    ): Boolean {
        // 1. Locales/paramètres en portée.
        if (SymbolesProjet.localesEnPortee(racine, decalage).any { it.declaration.nom == nom }) return true
        // 2. Membres des classes englobantes (this implicite).
        if (SymbolesProjet.membresEnglobants(racine, decalage).any { it.nom() == nom }) return true
        // 2-ter. Membres HÉRITÉS (ADR 0216) : la fermeture transitive des
        // supertypes de la classe englobante, classpath ET projet.
        if (estMembreHerite(
                racine, decalage, nom, contexteNom, resolution, declarationsProjet, portFichier)
        ) {
            return true
        }
        // 2 bis. K5 : membre du RÉCEPTEUR IMPLICITE d'un `with`
        // englobant (classpath ou projet — sinon faux `kt.unresolved`
        // sur TOUT nom NU dans un corps de `with`). ADR 0219 : les
        // PROPRIÉTÉS SYNTHÉTIQUES Java sont sondées aussi (getter du
        // récepteur — `with(activity) { layoutInflater }`).
        RecepteursWith.recepteurImplicite(racine, decalage, contexteNom, resolution, declarationsProjet)?.let { fqcn ->
            if (resolution.champsNommes(fqcn, nom).isNotEmpty()) return true
            if (resolution.methodesNommees(fqcn, nom).isNotEmpty()) return true
            if (resolution.accesseurSynthetique(fqcn, nom) != null) return true
            if (declarationsProjet.any { it.conteneur == fqcn && it.nom == nom }) return true
        }
        // 3. Déclarations de premier niveau du FICHIER.
        if (KtFile(racine).declarations().any { it.nom() == nom }) return true
        // 4. Déclarations du PROJET (tous genres — objets, alias, entrées).
        if (declarationsProjet.any { it.nom == nom }) return true
        // 5. Classes : nom écrit résolvable ou classe homonyme du projet.
        if (resolution.fqcnDuNomEcrit(nom, contexteNom) != null) return true
        if (contexteNom.classesProjet.values.any { it.endsWith(".$nom") }) return true
        // 6. Appelables de premier niveau du CLASSPATH (println…) —
        //    EN PORTÉE du fichier (ADR 0203) : paquet courant, import
        //    exact, joker, ou paquet par défaut de Kotlin. Sans la
        //    preuve de visibilité, TOUT appelable indexé résolvait un
        //    nom nu (`PI`/`sqrt` sans import passaient sous silence le
        //    diagnostic que kotlinc signale) ; le prédicat est le MÊME
        //    que celui du survol d'appelable en portée — les extensions
        //    homonymes restent sondées (corps de `with` : un nom nu y
        //    résout via l'extension importée du récepteur implicite).
        val appelablesPortee = index?.topParNom(nom).orEmpty()
        if (appelablesPortee.any { appelable -> resolution.appelableEnPortee(appelable, contexteNom) }) return true
        return false
    }

    // -----------------------------------------------------------------
    // Voie 2-ter : membres HÉRITÉS (ADR 0216)
    // -----------------------------------------------------------------

    /** Plafond de profondeur de la fermeture transitive (miroir SuperKotlin). */
    private const val PROFONDEUR_HERITAGE_MAXI = 16

    /** Budget total de sondages de la fermeture (défense fichiers pathologiques). */
    private const val SONDES_HERITAGE_MAXI = 96

    /** Genres de déclaration qui SONT des types (miroir SuperKotlin.GENRES_TYPES). */
    private val GENRES_TYPES =
        setOf(GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE, GenreDeclaration.OBJET)

    /** FQCN d'une déclaration projet (conteneur qualifié, sinon paquet + nom). */
    private fun fqcnDe(declaration: DeclarationProjet): String =
        (declaration.conteneur ?: declaration.paquet)?.let { "$it." }.orEmpty() + declaration.nom

    /** Le membre vit-il sur la chaîne BINAIRE du maillon [fqcn] (classpath) ?
     *
     * ADR 0219 : un nom de PROPRIÉTÉ SYNTHÉTIQUE Java (getter/setter)
     * prouve sa résolution par son accesseur — `layoutInflater` nu dans
     * une Activity, `title = …` nu via setTitle. */
    private fun membreBinaire(
        resolution: ResolutionKotlin,
        fqcn: String,
        nom: String,
    ): Boolean =
        resolution.methodesNommees(fqcn, nom).isNotEmpty() ||
            resolution.champsNommes(fqcn, nom).isNotEmpty() ||
            resolution.accesseurSynthetique(fqcn, nom) != null

    /** Le membre vit-il sur les DÉCLARÉS du frère projet [fqcn] ? */
    private fun membreProjet(
        declarationsProjet: List<DeclarationProjet>,
        fqcn: String,
        nom: String,
    ): Boolean = declarationsProjet.any { it.conteneur == fqcn && it.nom == nom }

    /**
     * Sonde UNE clause de supertype : le membre vit-il sur son maillon
     * (binaire ou projet), sinon quelles clauses suivantes porte-t-elle ?
     *
     * @return `null` si le membre est TROUVÉ (issue courte de l'appelant),
     *         sinon les clauses du NIVEAU suivant (frère projet lu par le
     *         port — chaîne vide si maillon illisible).
     */
    private fun sonderClause(
        clauseEcrite: String,
        contexte: OccurrencesKotlin.ContexteFichier,
        nom: String,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        portFichier: SuperKotlin.PortFichier?,
        vus: MutableSet<String>,
    ): List<Pair<String, OccurrencesKotlin.ContexteFichier>>? {
        val fqcn = SuperKotlin.fqcnSupertype(clauseEcrite, contexte, resolution) ?: return emptyList()
        if (!vus.add(fqcn)) return emptyList()
        if (membreBinaire(resolution, fqcn, nom)) return null
        if (membreProjet(declarationsProjet, fqcn, nom)) return null
        val declaration =
            declarationsProjet.firstOrNull {
                it.uri.isNotBlank() &&
                    it.genre in GENRES_TYPES &&
                    !it.estJava &&
                    fqcnDe(it) == fqcn
            } ?: return emptyList()
        val fichier = portFichier?.fichierDe(declaration) ?: return emptyList()
        val viveFrere =
            InterrogationsSymboles.declarationViveAPlage(fichier.arbre.racine, declaration)
                ?: return emptyList()
        return TypesKotlin.superTypesDe(viveFrere.noeud).map { it to fichier.contexte }
    }

    /**
     * Le nom NU [nom] à [decalage] est-il porté par la fermeture
     * transitive des SUPERTYPES de la classe/objet englobant(e) —
     * classpath (binaire, chaîne entière via
     * [ResolutionKotlin.methodesNommees]) ou projet (membres DÉCLARÉS
     * puis clause ÉCRITE lue dans SON fichier via le port) ?
     *
     * <p>Le maillon de départ se prouve par ses clauses ÉCRITES dans
     * l'arbre VIF du document (aucun port requis) ; les maillons frères
     * exigent [portFichier] (`null` : la fermeture s'arrête au premier
     * maillon classpath — conservateur, jamais de faux négatif
     * supplémentaire sur le document seul). Bornes honnêtes (D3) :
     * profondeur [PROFONDEUR_HERITAGE_MAXI], budget
     * [SONDES_HERITAGE_MAXI], garde anti-cycle par FQCN, source JAVA du
     * scan jamais traversée (le moteur Kotlin ne prouve pas l'héritage
     * d'une source Java — miroir des bornes de [SuperKotlin]).</p>
     */
    @Suppress("ReturnCount", "LongParameterList", "LoopWithTooManyJumpStatements")
    internal fun estMembreHerite(
        racine: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        portFichier: SuperKotlin.PortFichier?,
    ): Boolean {
        if (nom.isBlank()) return false
        // 1. Chaîne des classes/objets ENGLOBANTS le caret (externe →
        //    interne) — le FQCN de départ prouve le paquet ET l'imbrication.
        val chaine = chaineAncetresA(racine, decalage)
        val englobantes =
            chaine.filter {
                it.type == KtNodeTypes.CLASS || it.type == KtNodeTypes.OBJECT_DECLARATION
            }
        if (englobantes.isEmpty()) return false
        val noms = englobantes.mapNotNull { noeud ->
            // ADR 0222 : un companion ANONYME porte son nom effectif
            // « Companion » — le repli générique nomJeton() y lirait
            // le PREMIER MEMBRE (« creer »), brisant le FQCN de départ.
            if (noeud.type == KtNodeTypes.OBJECT_DECLARATION) {
                val objet = jo.lspkotlin.parse.kt.KtObjectDeclaration(noeud)
                objet.nomObjet()?.texte?.toString()
                    ?: if (objet.estCompanion()) "Companion" else null
            } else {
                (KtElement.depuis(noeud) as? KtDeclaration)?.nomJeton()?.texte?.toString()
            }
        }
        if (noms.isEmpty() || noms.size != englobantes.size) return false
        val fqcnDepart =
            (contexteNom.paquet?.let { "$it." }.orEmpty()) + noms.joinToString(".")
        val vus = mutableSetOf(fqcnDepart)

        // 2. BFS borné sur la fermeture mixte classpath + projet : le
        //    niveau de départ lit les clauses ÉCRITES du nœud le plus
        //    INTERNE (arbre vif du document), les suivants les clauses
        //    des frères projet (port de session).
        val contexteDepart = OccurrencesKotlin.ContexteFichier(contexteNom, declarationsProjet)
        var clausesNiveau: List<Pair<String, OccurrencesKotlin.ContexteFichier>>? =
            TypesKotlin.superTypesDe(englobantes.last()).map { it to contexteDepart }
        var profondeur = 0
        var sondes = SONDES_HERITAGE_MAXI
        while (clausesNiveau != null && clausesNiveau.isNotEmpty() && profondeur < PROFONDEUR_HERITAGE_MAXI) {
            val clausesSuivantes = mutableListOf<Pair<String, OccurrencesKotlin.ContexteFichier>>()
            for ((clauseEcrite, contexte) in clausesNiveau) {
                if (sondes-- <= 0) return false
                val issues = sonderClause(clauseEcrite, contexte, nom, resolution, declarationsProjet, portFichier, vus)
                if (issues == null) return true
                clausesSuivantes.addAll(issues)
            }
            clausesNiveau = clausesSuivantes
            profondeur++
        }
        return false
    }
}
