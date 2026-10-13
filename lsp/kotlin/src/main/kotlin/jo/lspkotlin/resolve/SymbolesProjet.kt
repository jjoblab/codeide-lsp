package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.KtParameter
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.chaineAncetresA
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * Déclaration NOMMÉE d'une source du PROJET (K3, LSP_KOTLIN.md §4.1
 * « symbols ») — extraite de l'arbre d'un document OUVERT ou d'un
 * fichier des racines sources (scan paresseux, sans build) : la
 * définition au sein du projet et la complétion des noms du projet
 * n'attendent AUCUNE génération (leçon databinding v0.108.0 : les
 * sources ÉCRITES suffisent, le reste est le travail du build).
 *
 * Le modèle est VOLONTAIREMENT plat et conservateur (D3) : types et
 * récepteurs sont les TEXTES ÉCRITS (`TextView`, `Vue`) — leur
 * résolution en FQCN appartient à [ResolutionKotlin], qui seul
 * consulte le classpath.
 *
 * @property genre genre de la déclaration.
 * @property nom nom déclaré.
 * @property uri URI du document porteur (celle de l'hôte pour les
 *           documents ouverts ; `file://` + chemin FUSE pour le scan).
 * @property debutNom décalage de début du jeton nom (cible de
 *           go-to-definition).
 * @property finNom décalage de fin du jeton nom (exclusif).
 * @property paquet package du fichier porteur.
 * @property recepteur récepteur d'extension ÉCRIT (`Vue`), `null` sinon.
 * @property typeRetour type de retour/valeur ÉCRIT, `null` sinon.
 * @property parametres paramètres (nom, type écrit) des fonctions ET
 *           du constructeur PRIMAIRE d'une classe (K4 — contrôle
 *           d'arité des appels de constructeur).
 * @property conteneur qualifié du conteneur (`pkg.Classe` pour un
 *           membre ; null au premier niveau).
 * @property nombreParametresDefaut paramètres portant une valeur PAR
 *           DÉFAUT (K4 — élargit la plage d'arité légale d'un appel).
 * @property aVararg la déclaration porte un paramètre VARARG (K4 —
 *           arité d'appel ouverte, contrôle d'arité écarté).
 */
public data class DeclarationProjet(
    public val genre: GenreDeclaration,
    public val nom: String,
    public val uri: String,
    public val debutNom: Int,
    public val finNom: Int,
    public val paquet: String?,
    public val recepteur: String?,
    public val typeRetour: String?,
    public val parametres: List<Pair<String?, String?>>,
    public val conteneur: String?,
    public val nombreParametresDefaut: Int = 0,
    public val aVararg: Boolean = false,
)

/** Genre de déclaration projet. */
public enum class GenreDeclaration {
    /** Classe (data/abstract/enum class comprise — [estEnums] plus fin). */
    CLASSE,

    /** Interface. */
    INTERFACE,

    /** Objet (compagnon compris). */
    OBJET,

    /** Fonction (extension comprise). */
    FONCTION,

    /** Propriété (extension comprise). */
    PROPRIETE,

    /** Paramètre de fonction/constructeur. */
    PARAMETRE,

    /** Variable locale. */
    LOCALE,

    /** Entrée d'énumération. */
    ENTREE_ENUM,

    /** Alias de type. */
    ALIAS,
}

/**
 * Extraction des déclarations d'un arbre — FONCTIONS PURES (aucun
 * état) : la session appelle à l'ouverture/scan, le test éprouve sur
 * fixtures.
 *
 * Exemption detekt ciblée (règle 16) : LongMethod (extraire — le when
 * du catalogue de déclarations, un genre = une branche complète) et
 * NestedBlockDepth (parcours d'ancêtres × enfants des portées — le
 * patron même des portées, précédent VueIndex.typesDansPaquet).
 */
@Suppress("LongMethod", "NestedBlockDepth", "TooManyFunctions", "ReturnCount", "CyclomaticComplexMethod")
public object SymbolesProjet {
    /**
     * Toutes les déclarations NOMMÉES d'un arbre, en profondeur d'abord
     * (membres de classes compris, paramets des fonctions et locales
     * comprises — les locales servent la portée, pas le scan projet).
     *
     * @param racine racine du document.
     * @param uri URI du document porteur.
     * @param paquet package du fichier (null si vide).
     * @return les déclarations ordonnées par position.
     */
    public fun declarations(
        racine: NoeudSyntaxique,
        uri: String,
        paquet: String?,
    ): List<DeclarationProjet> {
        val resultat = mutableListOf<DeclarationProjet>()
        val fichier = KtFile(racine)
        parcourirConteneur(fichier.declarations(), uri, paquet, conteneur = null, resultat)
        return resultat
    }

    /** Parcourt les déclarations d'un conteneur (fichier ou classe). */
    private fun parcourirConteneur(
        declarations: List<KtDeclaration>,
        uri: String,
        paquet: String?,
        conteneur: String?,
        resultat: MutableList<DeclarationProjet>,
    ) {
        for (declaration in declarations) {
            extraire(declaration, uri, paquet, conteneur, resultat)
        }
    }

    /**
     * Extrait UNE déclaration (et ses enfants : membres, paramètres) —
     * introuvable nommée : ignorée silencieusement (édition en cours,
     * D3).
     */
    private fun extraire(
        declaration: KtDeclaration,
        uri: String,
        paquet: String?,
        conteneur: String?,
        resultat: MutableList<DeclarationProjet>,
    ) {
        val nom = declaration.nomJeton() ?: return
        // Conteneur qualifié des MEMBRES : au premier niveau le paquet
        // est préfixé (« a.b.Vue »), aux niveaux suivants le parent
        // l'a déjà (« a.b.Vue.Interne ») — miroir du FQCN de
        // TypageExpression, clé de la complétion membre projet.
        val qualifieConteneur =
            if (conteneur == null) {
                (paquet?.let { "$it." } ?: "") + nom.texte.toString()
            } else {
                "$conteneur.${nom.texte}"
            }

        when (declaration) {
            is KtClass -> {
                resultat += DeclarationProjet(
                    genre = if (declaration.estInterface()) GenreDeclaration.INTERFACE else GenreDeclaration.CLASSE,
                    nom = nom.texte.toString(),
                    uri = uri,
                    debutNom = nom.debut,
                    finNom = nom.fin,
                    paquet = paquet,
                    recepteur = null,
                    typeRetour = null,
                    // K4 : paramètres du constructeur PRIMAIRE (contrôle
                    // d'arité des appels de constructeur du projet).
                    parametres = parametresPrimaires(declaration),
                    conteneur = conteneur,
                )
                parcourirConteneur(declaration.membres(), uri, paquet, qualifieConteneur, resultat)
            }

            is KtFunction -> {
                resultat += DeclarationProjet(
                    genre = GenreDeclaration.FONCTION,
                    nom = nom.texte.toString(),
                    uri = uri,
                    debutNom = nom.debut,
                    finNom = nom.fin,
                    paquet = paquet,
                    recepteur = declaration.recepteurExtension(),
                    typeRetour = declaration.typeRetour(),
                    parametres = declaration.parametres().map { parametre -> parametreExtraire(parametre) },
                    conteneur = conteneur,
                    nombreParametresDefaut = declaration.parametres().count { parametre -> parametre.aDefaut() },
                    aVararg = declaration.parametres().any { parametre -> parametre.aVararg() },
                )
                // Paramètres : portée du corps (déclarations à part).
                declaration.parametres().forEach { parametre ->
                    parametreDeclaration(
                        parametre,
                        uri,
                        paquet,
                        qualifieConteneur ?: conteneur,
                    )?.let { resultat.add(it) }
                }
            }

            is KtProperty -> {
                resultat += DeclarationProjet(
                    genre = GenreDeclaration.PROPRIETE,
                    nom = nom.texte.toString(),
                    uri = uri,
                    debutNom = nom.debut,
                    finNom = nom.fin,
                    paquet = paquet,
                    recepteur = declaration.recepteurExtension(),
                    typeRetour = declaration.type(),
                    parametres = emptyList(),
                    conteneur = conteneur,
                )
            }

            else -> {
                resultat += DeclarationProjet(
                    genre = GenreDeclaration.OBJET,
                    nom = nom.texte.toString(),
                    uri = uri,
                    debutNom = nom.debut,
                    finNom = nom.fin,
                    paquet = paquet,
                    recepteur = null,
                    typeRetour = null,
                    parametres = emptyList(),
                    conteneur = conteneur,
                )
            }
        }
    }

    /** (nom, type écrit) d'un paramètre. */
    private fun parametreExtraire(parametre: KtParameter): Pair<String?, String?> =
        Pair(parametre.nom(), parametre.type())

    /**
     * Paramètres du constructeur PRIMAIRE d'une classe (K4) : la liste
     * de paramètres ENFANT DIRECTE du nœud CLASS (celles des
     * constructeurs secondaires vivent dans SECONDARY_CONSTRUCTOR).
     */
    private fun parametresPrimaires(classe: KtClass): List<Pair<String?, String?>> =
        classe.noeud.sousNoeuds
            .firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
            ?.sousNoeuds
            ?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
            ?.map { KtParameter(it) }
            ?.map { parametre -> parametreExtraire(parametre) }
            ?: emptyList()

    /** Déclaration de PARAMÈTRE (portée du corps de fonction). */
    private fun parametreDeclaration(
        parametre: KtParameter,
        uri: String,
        paquet: String?,
        conteneur: String?,
    ): DeclarationProjet? {
        val nom = parametre.nomJeton() ?: return null
        return DeclarationProjet(
            genre = GenreDeclaration.PARAMETRE,
            nom = nom.texte.toString(),
            uri = uri,
            debutNom = nom.debut,
            finNom = nom.fin,
            paquet = paquet,
            recepteur = null,
            typeRetour = parametre.type(),
            parametres = emptyList(),
            conteneur = conteneur,
        )
    }

    /**
     * LOCALES + PARAMÈTRES en portée à un décalage : remonte la chaîne
     * des ancêtres — chaque FUN ancêtre prête ses paramètres, chaque
     * BLOCK/CORPS ancêtre prête les propriétés déclarées AVANT le
     * décalage (une locale n'existe pas avant sa ligne — sémantique
     * Kotlin, conservative par construction).
     *
     * @param racine racine du document.
     * @param decalage décalage du curseur.
     * @return les locales/paramètres visibles, de la PLUS proche à la
     *         plus lointaine (préséance d'ombrage côté appelant).
 *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * les continue du BLOCK non porteur et de la locale pas-encore-
     * déclarée sont le patron même du parcours des portées (précédent
     * racinesSourcesDe d'OrchestrateurLsp).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    public fun localesEnPortee(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): List<LocaleEnPortee> {
        val chaine = chaineAncetresA(racine, decalage)
        val resultat = mutableListOf<LocaleEnPortee>()
        // De la plus profonde à la racine : les locales proches
        // PRENNENT la préséance (l'appelant dédoublonne par nom).
        for (profondeur in chaine.indices.reversed()) {
            val noeud = chaine[profondeur]
            when (noeud.type) {
                KtNodeTypes.FUN -> {
                    val fonction = KtFunction(noeud)
                    fonction.parametres().forEach { parametre ->
                        parametreDeclarationLocal(parametre)?.let { resultat.add(LocaleEnPortee(it, null)) }
                    }
                }

                KtNodeTypes.BLOCK, KtNodeTypes.CLASS_BODY, KtNodeTypes.FUNCTION_LITERAL -> {
                    for (enfant in noeud.sousNoeuds) {
                        if (enfant.type != KtNodeTypes.PROPERTY) continue
                        if (enfant.fin > decalage) continue // pas encore déclarée
                        val propriete = KtProperty(enfant)
                        val jeton = propriete.nomJeton() ?: continue
                        resultat += LocaleEnPortee(
                            DeclarationProjet(
                                genre = GenreDeclaration.LOCALE,
                                nom = jeton.texte.toString(),
                                uri = "",
                                debutNom = jeton.debut,
                                finNom = jeton.fin,
                                paquet = null,
                                recepteur = null,
                                typeRetour = propriete.type(),
                                parametres = emptyList(),
                                conteneur = null,
                            ),
                            propriete,
                        )
                    }
                }

                else -> {
                    // CLASS/OBJECT : les membres sont portés par le
                    // CLASS_BODY ancêtre — rien ici.
                }
            }
        }
        return resultat
    }

    /**
     * Locale/paramètre en portée — [propriete] portante pour le typage
     * par initialiseur (null pour un paramètre).
     */
    public data class LocaleEnPortee(
        public val declaration: DeclarationProjet,
        public val propriete: KtProperty?,
    )

    /**
     * PROPRIÉTÉS membres des classes ENGLOBANTES une position (portée
     * membre implicite `this`) — typage par type écrit ou initialiseur.
     */
    public fun proprietesEnglobantes(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): List<KtProperty> {
        val chaine = chaineAncetresA(racine, decalage)
        val resultat = mutableListOf<KtProperty>()
        for (noeud in chaine) {
            if (noeud.type != KtNodeTypes.CLASS_BODY) continue
            for (enfant in noeud.sousNoeuds) {
                if (enfant.type == KtNodeTypes.PROPERTY) resultat.add(KtProperty(enfant))
            }
        }
        return resultat
    }

    /** Paramètre en déclaration locale (portée corps) — null si anonyme. */
    private fun parametreDeclarationLocal(parametre: KtParameter): DeclarationProjet? {
        val jeton = parametre.nomJeton() ?: return null
        return DeclarationProjet(
            genre = GenreDeclaration.PARAMETRE,
            nom = jeton.texte.toString(),
            uri = "",
            debutNom = jeton.debut,
            finNom = jeton.fin,
            paquet = null,
            recepteur = null,
            typeRetour = parametre.type(),
            parametres = emptyList(),
            conteneur = null,
        )
    }

    /**
     * Membres des classes ENGLOBANTES à un décalage (portée membre —
     * `this` implicite).
     *
     * @param racine racine du document.
     * @param decalage décalage du curseur.
     * @param declarations conteneur de sortie (toutes les déclarations
     *        du fichier, pour retrouver les membres par ancêtre).
     */
    public fun membresEnglobants(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): List<KtDeclaration> {
        val chaine = chaineAncetresA(racine, decalage)
        val membres = mutableListOf<KtDeclaration>()
        for (noeud in chaine) {
            if (noeud.type == KtNodeTypes.CLASS_BODY) {
                for (enfant in noeud.sousNoeuds) {
                    val decl = KtElement.depuis(enfant) as? KtDeclaration
                    if (decl != null) membres.add(decl)
                }
            }
        }
        return membres
    }
}
