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
     *        fournit le port de [SuperKotlin], miroir du GO TO Super.
     * @param universProjetComplet l'univers des sources projet est-il
     *        COMPLET (étape B, H1) ? Un scan TRONQUÉ rend les voies
     *        4/5 inaptes à prouver l'ABSENCE — la cascade rend VRAI
     *        après les voies FICHIER (miroir de la garde
     *        `scanProjetComplet` de `selectionProjet`, vague 3).
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
        universProjetComplet: Boolean = true,
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
        // 2-quater. Vague 5 (retour utilisateurs 25/10/2026 — U1
        // « weight »/« align ») : membre du RÉCEPTEUR DE PORTÉE d'un
        // lambda de SCOPE englobant — `Row { Modifier.weight(1f) }`
        // prête RowScope comme this implicite à TOUT le corps du
        // littéral (miroir kotlinc : les récepteurs s'empilent, le
        // plus profond d'abord).
        for (fqcnPortee in RecepteursPortee.recepteursDePortee(
                racine, decalage, contexteNom, resolution, declarationsProjet, index,
            )
        ) {
            if (resolution.champsNommes(fqcnPortee, nom).isNotEmpty()) return true
            if (resolution.methodesNommees(fqcnPortee, nom).isNotEmpty()) return true
            if (resolution.accesseurSynthetique(fqcnPortee, nom) != null) return true
            if (declarationsProjet.any { it.conteneur == fqcnPortee && it.nom == nom }) return true
        }
        // 3. Déclarations de premier niveau du FICHIER.
        if (KtFile(racine).declarations().any { it.nom() == nom }) return true
        // Garde H1 (mission fiabilisation, étape B) : univers des
        // sources projet TRONQUÉ — les voies 4/5 ne peuvent prouver NI
        // résolution NI absence : un nom qui échoue aux voies LOCALES
        // et FICHIER peut vivre derrière la borne du scan. La preuve
        // d'absence est COUPÉE (D3) — miroir de la garde
        // `scanProjetComplet` de `selectionProjet` (vague 3), au
        // niveau de la CASCADE de noms.
        if (!universProjetComplet) return true
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
        // Étape B (§7.2) — INTRINSÈQUES du compilateur : les
        // constructeurs de tableaux et `enumValues`/`enumValueOf` sont
        // @InlineOnly SANS miroir bytecode (vérifié sur le stdlib) —
        // l'index ne peut JAMAIS les connaître ; réputation miroir
        // d'IMPORTS_DEFAUT (voie 5), top-level du paquet racine
        // `kotlin` importé par défaut sur JVM.
        if (resolution.estAppeableIntrinseque(nom)) return true
        val appelablesPortee = index?.topParNom(nom).orEmpty()
        if (appelablesPortee.any { appelable -> resolution.appelableEnPortee(appelable, contexteNom) }) return true
        // Rejeu §4 (mission fiabilisation, étape B) : TYPEALIAS publics
        // du classpath EN PORTÉE du fichier — le MÊME prédicat ADR 0203
        // (paquet courant, import exact, joker, paquet par défaut).
        // Sans cette sonde, un alias SANS fonction usine expect/actual
        // (kotlinx-datetime `ZoneOffset`) restait rouge derrière un
        // import joker — les usines (`fun Month(int)`) ne sauvent que
        // leurs propres expect classes.
        val aliasPortee = index?.aliasTopParNom(nom).orEmpty()
        if (aliasPortee.any { alias -> resolution.appelableEnPortee(alias, contexteNom) }) return true
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
     * une Activity, `title = …` nu via setTitle.
     *
     * Vague 4 (retour utilisateurs 25/10/2026 — U2 « FilterResults ») :
     * un TYPE IMBRIQUÉ du maillon porte le nom aussi — la règle
     * kotlinc PRUVÉE par expérience : HÉRITER d'une classe met ses
     * classes imbriquées en portée par NOM SIMPLE dans toute la
     * sous-classe (`FilterResults` nu dans un `object : Filter()`,
     * bytecode `android.widget.Filter$FilterResults`) ; l'IMPORT du
     * conteneur seul ne suffit PAS (expérience kotlinc 2.2.10 :
     * unresolved), l'héritage FQN sans import suffit (expérience H3).
     * La fermeture transitive y compris : RecyclerView.ViewHolder
     * vit dans le corps d'un adaptant qui hérite de ListAdapter… */
    private fun membreBinaire(
        resolution: ResolutionKotlin,
        fqcn: String,
        nom: String,
    ): Boolean =
        resolution.methodesNommees(fqcn, nom).isNotEmpty() ||
            resolution.champsNommes(fqcn, nom).isNotEmpty() ||
            resolution.accesseurSynthetique(fqcn, nom) != null ||
            resolution.membresDe("$fqcn\$$nom") != null

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
     *
     * <p>Vague 4 (retour utilisateurs 25/10/2026 — U2 « submitList »
     * dans un `object : Filter()`) : les this IMPLICITES sont EMPILÉS
     * en portée lexicale — un objet ANONYME englobant ouvre la marche
     * par SA clause ÉCRITE (le this le plus proche d'abord), PUIS les
     * classes nommées englobantes prêtent leurs clauses chacune (les
     * noms nus de l'objet anonyme voient les membres des this
     * externes, miroir kotlinc). Un TYPE IMBRIQUÉ d'un maillon porte
     * le nom aussi (règle prouvée par expérience kotlinc : hériter
     * met les imbriqués en portée par nom simple).</p>
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
        val noms = nomsLexicaux(englobantes)
        // Un objet anonyme SANS classe nommée au-dessus (this unique,
        // niveau fonction) : le FQCN de départ est symbolique — les
        // `vus` ne servent QUE d'anti-cycle, le point de départ réel
        // est la clause ÉCRITE de l'objet (marche 2 a).
        val fqcnDepart =
            if (noms.isEmpty()) {
                "anonyme@${englobantes.last().debut}"
            } else {
                (contexteNom.paquet?.let { "$it." }.orEmpty()) + noms.joinToString(".")
            }
        val vus = mutableSetOf(fqcnDepart)

        // 2. BFS borné sur la fermeture mixte classpath + projet : le
        //    niveau de départ lit les clauses ÉCRITES de la PILE des
        //    this implicites (marches 2 a/2 b), les suivants les
        //    clauses des frères projet (port de session).
        var clausesNiveau: List<Pair<String, OccurrencesKotlin.ContexteFichier>>? =
            clausesDesThis(englobantes, contexteNom, declarationsProjet)
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

    /**
     * Noms LEXICAUX des classes/objets englobants nommés (vague 4 : un
     * objet ANONYME est un this SÉPARÉ — sauté du chemin lexical ; ADR
     * 0222 : un companion anonyme porte « Companion »).
     */
    private fun nomsLexicaux(englobantes: List<NoeudSyntaxique>): List<String> {
        val noms = mutableListOf<String>()
        for (noeud in englobantes) {
            if (noeud.type == KtNodeTypes.OBJECT_DECLARATION) {
                val objet = jo.lspkotlin.parse.kt.KtObjectDeclaration(noeud)
                val nom =
                    objet.nomObjet()?.texte?.toString()
                        ?: if (objet.estCompanion()) "Companion" else null
                if (nom != null) noms.add(nom)
            } else {
                (KtElement.depuis(noeud) as? KtDeclaration)?.nomJeton()?.texte?.toString()
                    ?.let { noms.add(it) }
            }
        }
        return noms
    }

    /**
     * Clauses de supertypes ÉCRITES de la PILE des this implicites
     * (vague 4 — miroir kotlinc, récepteurs empilés) : l'objet ANONYME
     * le plus interne D'ABORD (son this est le plus proche), PUIS les
     * classes/objets nommés du plus interne au plus externe — un nom nu
     * voit les membres et types IMBRIQUÉS de chaque this.
     */
    private fun clausesDesThis(
        englobantes: List<NoeudSyntaxique>,
        contexteNom: ResolutionKotlin.ContexteNom,
        declarationsProjet: List<DeclarationProjet>,
    ): List<Pair<String, OccurrencesKotlin.ContexteFichier>> {
        val contexte = OccurrencesKotlin.ContexteFichier(contexteNom, declarationsProjet)
        val clauses = mutableListOf<Pair<String, OccurrencesKotlin.ContexteFichier>>()
        // 2 a. L'objet anonyme le plus INTERNE (this le plus proche).
        englobantes.lastOrNull()
            ?.takeIf { it.type == KtNodeTypes.OBJECT_DECLARATION }
            ?.takeIf {
                jo.lspkotlin.parse.kt.KtObjectDeclaration(it).let { objet ->
                    objet.nomObjet() == null && !objet.estCompanion()
                }
            }
            ?.let { anonyme -> TypesKotlin.superTypesDe(anonyme) }
            ?.forEach { clauses.add(it to contexte) }
        // 2 b. Les classes/objets NOMMÉS, du plus interne au plus externe.
        for (indice in englobantes.indices.reversed()) {
            val noeud = englobantes[indice]
            val nomme =
                if (noeud.type == KtNodeTypes.OBJECT_DECLARATION) {
                    val objet = jo.lspkotlin.parse.kt.KtObjectDeclaration(noeud)
                    objet.nomObjet() != null || objet.estCompanion()
                } else {
                    (KtElement.depuis(noeud) as? KtDeclaration)?.nomJeton() != null
                }
            if (nomme) {
                TypesKotlin.superTypesDe(noeud).forEach { clauses.add(it to contexte) }
            }
        }
        return clauses
    }
}
