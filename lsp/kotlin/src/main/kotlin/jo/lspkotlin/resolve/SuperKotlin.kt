package jo.lspkotlin.resolve

import jo.lspkotlin.api.CibleNavigationKotlin
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomTypeEcrit
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Navigation GO TO « Super » Kotlin (ADR 0210 — miroir du moteur ecj
 * `EcjNavigationEngine.findSuper`, sémantique du NavMenu du projet
 * tiers de référence) : pour un MEMBRE au caret, le même-nommé dans
 * chaque supertype TRANSITIF du type déclarant (libellé
 * `« name  ·  Super »`) ; sinon les supertypes DIRECTS du type en
 * contexte — une référence de type sous le caret, à défaut la classe
 * ENGLOBANTE (libellé `« Simple  ·  pkg »`).
 *
 * ## Les deux chemins
 *  1. **MEMBRE** — la cible occurrences ([OccurrencesKotlin.cibleAuCurseur])
 *     prouve le membre visé (déclaration OUTREPASSANTE ou usage résolu,
 *     preuve par FQCN du conteneur) ; chaque supertype PROJET de la
 *     clôture transitive du type déclarant contribue ses membres
 *     homonymes (même genre : FONCTION ou PROPRIÉTÉ — les surcharges
 *     homonymes du supertype sont toutes listées, le picker montre
 *     chacune). BFS borné : garde anti-cycle par FQCN, plafond
 *     [PROFONDEUR_MAXI] niveaux — miroir du `allSupertypes` ecj.
 *  2. **TYPE** — le type en contexte est la même preuve que
 *     `textDocument/typeDefinition` pour une RÉFÉRENCE de type sous
 *     le curseur ; à DÉFAUT la classe/objet ENGLOBANT(E) le caret
 *     (miroir `contextType` ecj : le type d'une variable ne compte
 *     PAS, seule une référence/une déclaration de type compte). Les
 *     supertypes DIRECTS ÉCRITS de la clause sont résolus PAR LES
 *     IMPORTS DU FICHIER DÉCLARANT (preuve FQCN — jamais d'homonymie).
 *
 * ## Bornes honnêtes (D3)
 *  - Supertype du CLASSPATH (Any, View…) : aucune source projet →
 *    absent, comme le moteur ecj (« binaire — pas de source »).
 *  - Le scan ne porte pas les supertypes : la clause ÉCRITE de CHAQUE
 *    maillon projet est lue dans SON arbre (document ouvert → arbre
 *    vif, fichier fermé → port de lecture bornée de la session —
 *    même budget que le KDoc cross-fichier ADR 0208).
 *  - Sources JAVA du scan : jamais candidates (mêmes bornes que
 *    `implementations` — le moteur Kotlin ne prouve pas l'héritage
 *    d'une source Java).
 *  - L'arité des surcharges n'est PAS prouvée au niveau du parseur :
 *    toutes les homonymes du supertype sont listées (le libellé
 *    disambiguïse, l'éditeur ne saute jamais au faux endroit).
 */
internal object SuperKotlin {
    /** Genres de déclaration qui SONT des types (miroir TypesKotlin + objets). */
    private val GENRES_TYPES =
        setOf(GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE, GenreDeclaration.OBJET)

    /** Genres MEMBRES outrepassables (fonction/propriété). */
    internal val GENRES_MEMBRES = setOf(GenreDeclaration.FONCTION, GenreDeclaration.PROPRIETE)

    /** Plafond de profondeur de la clôture transitive (miroir ecj ×½). */
    private const val PROFONDEUR_MAXI = 16

    /** Plafond de cibles rendues (défense picker). */
    private const val CIBLES_MAXI = 64

    /**
     * Un fichier déclarant prêt pour les preuves : arbre + contexte
     * FQCN (imports de CE fichier). Fourni par le PORT de la session
     * (arbre vif du document ouvert, sinon lecture disque + parse
     * bornées) — le moteur reste sans E/S.
     */
    internal class FichierDeclaration(
        val arbre: ArbreSyntaxiqueKotlin,
        val contexte: OccurrencesKotlin.ContexteFichier,
    )

    /** Port : (déclaration) → son fichier déclarant prêt pour preuves. */
    internal fun interface PortFichier {
        fun fichierDe(declaration: DeclarationProjet): FichierDeclaration?
    }

    /** FQCN d'une déclaration (conteneur qualifié, sinon paquet + nom). */
    internal fun fqcnDe(declaration: DeclarationProjet): String =
        (declaration.conteneur ?: declaration.paquet)?.let { "$it." }.orEmpty() + declaration.nom

    /** Contexte de la marche BFS : la cible, les preuves et la collecte. */
    private class Marche(
        val membre: OccurrencesKotlin.Cible,
        val port: PortFichier,
        val declarationsProjet: List<DeclarationProjet>,
        val resolution: ResolutionKotlin,
        val vus: MutableSet<String> = mutableSetOf(),
        val cibles: MutableList<CibleNavigationKotlin> = mutableListOf(),
    )

    /**
     * Cibles Super du MEMBRE [membre] : le même-nommé de chaque
     * supertype TRANSITIF projet du type déclarant.
     *
     * @param membre la cible occurrences prouvée au caret (genre
     *        [GENRES_MEMBRES], conteneur FQCN non null)
     * @param port lecture des fichiers déclarants (session)
     * @param declarationsProjet déclarations réelles du projet
     * @param resolution le résolveur FQCN partagé
     * @return les cibles triées par libellé (peut être vide)
     */
    internal fun superDuMembre(
        membre: OccurrencesKotlin.Cible,
        port: PortFichier,
        declarationsProjet: List<DeclarationProjet>,
        resolution: ResolutionKotlin,
    ): List<CibleNavigationKotlin> {
        val classeDeclarante =
            declarationsProjet.firstOrNull { declaration ->
                declaration.uri.isNotBlank() &&
                    declaration.genre in GENRES_TYPES &&
                    fqcnDe(declaration) == membre.conteneur
            } ?: return emptyList()
        val marche = Marche(membre, port, declarationsProjet, resolution)
        // BFS : niveau par niveau de la clôture transitive, chaque
        // maillon projet prouve sa clause ÉCRITE dans SON arbre.
        var niveau: List<DeclarationProjet> = listOf(classeDeclarante)
        var profondeur = 0
        while (niveau.isNotEmpty() && marche.cibles.size < CIBLES_MAXI && profondeur < PROFONDEUR_MAXI) {
            niveau = niveau.flatMap { declaration -> visiterMaillon(declaration, marche) }
            profondeur++
        }
        return marche.cibles.distinctBy { "${it.uri}:${it.debutNom}" }.sortedBy { it.libelle }
    }

    /**
     * Visite UN maillon du BFS : éprouve ses supertypes ÉCRITS (dans
     * SON arbre via le port) et collecte les homonymes du membre
     * visé dans chaque supertype projet trouvé.
     *
     * @return les supertypes projet du maillon (le prochain niveau)
     */
    private fun visiterMaillon(
        declaration: DeclarationProjet,
        marche: Marche,
    ): List<DeclarationProjet> {
        if (marche.vus.add(fqcnDe(declaration))) {
            marche.port.fichierDe(declaration)?.let { fichier ->
                val supertypes =
                    supertypesProjetsDe(declaration, fichier, marche.declarationsProjet, marche.resolution)
                for (supertype in supertypes) {
                    marche.cibles.addAll(
                        membresHomonymesDe(marche.membre, supertype, marche.declarationsProjet, marche.cibles.size),
                    )
                }
                return supertypes
            }
        }
        return emptyList() // cycle ou maillon illisible (D3)
    }

    /**
     * Le même-nommé de [membre] dans [supertype] — toutes les
     * surcharges homonymes (le libellé disambiguïse, l'éditeur ne
     * saute jamais au faux endroit) ; borné par le reste du plafond.
     */
    private fun membresHomonymesDe(
        membre: OccurrencesKotlin.Cible,
        supertype: DeclarationProjet,
        declarationsProjet: List<DeclarationProjet>,
        deja: Int,
    ): List<CibleNavigationKotlin> {
        if (deja >= CIBLES_MAXI) return emptyList()
        return declarationsProjet
            .filter { homonyme ->
                homonyme.conteneur == fqcnDe(supertype) &&
                    homonyme.nom == membre.nom &&
                    homonyme.genre == membre.genre &&
                    homonyme.uri.isNotBlank()
            }.take(CIBLES_MAXI - deja)
            .map { homonyme ->
                CibleNavigationKotlin(
                    uri = homonyme.uri,
                    debutNom = homonyme.debutNom,
                    finNom = homonyme.finNom,
                    libelle = "${membre.nom}  ·  ${supertype.nom}",
                )
            }
    }

    /**
     * Cibles Super du TYPE en contexte : les supertypes DIRECTS
     * ÉCRITS, résolus par les imports du fichier déclarant — le
     * même-nommé n'est JAMAIS présumé (preuve FQCN).
     *
     * @param type la déclaration projet du type en contexte (référence
     *        de type sous le caret, sinon la classe englobante —
     *        [typeEnContexte])
     */
    internal fun superDuType(
        type: DeclarationProjet,
        port: PortFichier,
        declarationsProjet: List<DeclarationProjet>,
        resolution: ResolutionKotlin,
    ): List<CibleNavigationKotlin> {
        val fichier = port.fichierDe(type) ?: return emptyList()
        return supertypesProjetsDe(type, fichier, declarationsProjet, resolution)
            .map { supertype ->
                CibleNavigationKotlin(
                    uri = supertype.uri,
                    debutNom = supertype.debutNom,
                    finNom = supertype.finNom,
                    libelle = "${supertype.nom}  ·  ${supertype.paquet.orEmpty()}",
                )
            }
            .sortedBy { it.libelle }
    }

    /**
     * Les supertypes DIRECTS PROJET de [declaration] : chaque entrée
     * ÉCRITE de sa clause, résolue par les imports de SON fichier
     * ([fichier]), vers la déclaration projet portant CE FQCN.
     */
    private fun supertypesProjetsDe(
        declaration: DeclarationProjet,
        fichier: FichierDeclaration,
        declarationsProjet: List<DeclarationProjet>,
        resolution: ResolutionKotlin,
    ): List<DeclarationProjet> {
        val noeud = noeudDeclarationDe(fichier.arbre.racine, declaration) ?: return emptyList()
        val resultat = mutableListOf<DeclarationProjet>()
        for (supertypeEcrit in TypesKotlin.superTypesDe(noeud)) {
            val fqcn = fqcnSupertype(supertypeEcrit, fichier.contexte, resolution) ?: continue
            declarationsProjet
                .firstOrNull { candidat ->
                    candidat.uri.isNotBlank() &&
                        candidat.genre in GENRES_TYPES &&
                        !candidat.estJava &&
                        fqcnDe(candidat) == fqcn
                }?.let { resultat.add(it) }
        }
        return resultat.distinctBy { fqcnDe(it) }
    }

    /**
     * FQCN d'un supertype ÉCRIT, résolu par les imports du fichier
     * déclarant — un nom QUALIFIÉ écrit EST le FQCN ; un nom simple
     * passe par [ResolutionKotlin.fqcnDuNomEcrit] (preuve par FQCN).
     * `null` sans preuve (classpath, homonymie ambiguë).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
     * (vide, qualifié, sans preuve) sont les ISSUES du conservatisme
     * D3, miroir TypesKotlin.supertypeResoutVers.
     */
    @Suppress("ReturnCount")
    internal fun fqcnSupertype(
        supertypeEcrit: String,
        contexteFichier: OccurrencesKotlin.ContexteFichier,
        resolution: ResolutionKotlin,
    ): String? {
        val nom = TypesKotlin.nomSupertypeNettoye(supertypeEcrit)
        if (nom.isEmpty()) return null
        if ('.' in nom) return nom
        return resolution.fqcnDuNomEcrit(nom, contexteFichier.contexteNom)
    }

    /**
     * Le type en contexte du GO TO Super (miroir `contextType` ecj —
     * PAS le type du SYMBOLE) : une référence de TYPE sous le curseur
     * résout vers sa déclaration projet (même preuve que
     * typeDefinition) ; à défaut la classe/objet ENGLOBANT(E) le
     * caret (la plus profonde — celle du `@Override` qu'on édite).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les DEUX
     * issues (référence de type, classe englobante) sont les branches
     * du contrat, miroir TypesKotlin.typeEnContexte.
     */
    @Suppress("ReturnCount")
    internal fun typeEnContexte(
        racine: NoeudSyntaxique,
        decalage: Int,
        uri: String,
        contexte: InterrogationsSymboles.Contexte,
    ): DeclarationProjet? {
        val chaine = chaineAncetresA(racine, decalage)
        // 1. Référence de TYPE sous le curseur : même preuve que
        //    typeDefinition (fqcnDuNomEcrit par les imports du COURANT).
        chaine.lastOrNull { it.type == KtNodeTypes.USER_TYPE || it.type == KtNodeTypes.TYPE_REFERENCE }
            ?.let { usage -> nomTypeEcrit(usage)?.let { nom -> return TypesKotlin.typeProjet(nom, uri, contexte) } }
        // 2. La classe/objet ENGLOBANT(E) — le caret vit dans SON
        //    corps : c'est ELLE dont le menu montre les supertypes.
        val classe =
            chaine.lastOrNull { it.type == KtNodeTypes.CLASS || it.type == KtNodeTypes.OBJECT_DECLARATION }
                ?: return null
        val declaration = KtElement.depuis(classe) as? KtDeclaration ?: return null
        val jeton = declaration.nomJeton() ?: return null
        return contexte.declarationsProjet.firstOrNull { candidat ->
            candidat.uri == uri &&
                candidat.debutNom == jeton.debut &&
                candidat.finNom == jeton.fin &&
                candidat.genre in GENRES_TYPES
        }
    }

    /**
     * Nœud d'arbre d'une déclaration projet — jumelage EXACT par
     * plage du nom (la même garde que le KDoc cross-fichier ADR
     * 0208 : jamais de jumelage approximatif).
     */
    private fun noeudDeclarationDe(
        racine: NoeudSyntaxique,
        declaration: DeclarationProjet,
    ): NoeudSyntaxique? {
        val vive = InterrogationsSymboles.declarationViveAPlage(racine, declaration) ?: return null
        return vive.noeud
    }
}
