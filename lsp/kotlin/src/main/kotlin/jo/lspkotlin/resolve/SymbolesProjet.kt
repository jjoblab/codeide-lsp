package jo.lspkotlin.resolve

import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtDeclaration
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.KtObjectDeclaration
import jo.lspkotlin.parse.kt.KtParameter
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.chaineAncetresA
import org.jetbrains.kotlin.kmp.lexer.KtTokens
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
 * @property estJava la déclaration vient d'une source JAVA du projet
 *           (K5 multi-langue) — l'arité des constructeurs/membres
 *           Java n'est PAS extraite : le contrôle d'arité
 *           `kt.argumentCount` NE LA CONSUlte pas (sous-rapport D3,
 *           jamais de faux « 0 attendu » sur un constructeur Java à
 *           N paramètres).
 * @property estSansHeritageEcrit un TYPE sans clause ÉCRITE de
 *           supertype (`class Foo`/`object Foo`/`interface Foo` Kotlin,
 *           `class`/`interface` Java sans `extends`/`implements` —
 *           retour utilisateurs 25/10/2026, vague 3) : son jeu de
 *           membres est alors exactement les DÉCLARÉS du scan + les
 *           membres IMPLICITES de Any/Object — la preuve d'ABSENCE
 *           d'un sélecteur y est donc fondée (D3 : un type avec
 *           héritage écrit — ou enum/annotation, supertype implicite
 *           Comparable/Annotation — ne prouve JAMAIS l'absence de
 *           ses membres hérités).
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
    public val estJava: Boolean = false,
    public val estSansHeritageEcrit: Boolean = false,
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
        // ADR 0222 : le COMPANION a sa propre grammaire de nommage — le
        // repli générique nomJeton() lirait le PREMIER MEMBRE d'un
        // companion ANONYME (enregistré « creer », membres sous
        // « pkg.Boite.creer ») : détection par MODIFIER_LIST + nom
        // effectif « Companion » (miroir kotlinc).
        if (declaration is KtObjectDeclaration) {
            extraireObjet(declaration, uri, paquet, conteneur, resultat)
            return
        }
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
                    // Retour utilisateurs U1/U3 (25/10/2026) : la candidate
                    // CONSTRUCTEUR doit connaître ses paramètres À DÉFAUT
                    // — `Article("Pommes", 4)` sur 6 paramètres dont 4 avec
                    // valeur par défaut est LÉGAL, le contrôle d'arité des
                    // appels de classe comptait 0 défaut (la branche
                    // FONCTION seule remplissait le champ) et produisait
                    // « 4 fourni(s), 6 attendu(s) » — le faux positif n° 1
                    // du quotidien Kotlin (data classes à défauts).
                    nombreParametresDefaut = parametresPrimairesKt(declaration)
                        .count { parametre -> parametre.aDefaut() },
                    // Vague 3 : SANS supertype ÉCRIT et hors enum
                    // (Comparable implicite)/annotation (Annotation
                    // implicite) — le jeu de membres est exactement
                    // DECLARÉS + synthétiques data + Any implicite :
                    // l'absence d'un sélecteur y est prouvable (D3).
                    estSansHeritageEcrit = declaration.superTypes().isEmpty() &&
                        !declaration.estEnum() && !declaration.estAnnotation(),
                )
                // ADR 0206 : chaque paramètre `val`/`var` du constructeur
                // primaire est AUSSI une PROPRIÉTÉ membre de la classe —
                // forme de déclaration la plus idiomatique de Kotlin
                // (`class Vue(private val ctx: Context)`). L'entrée
                // supplémentaire porte le conteneur qualifié et la plage
                // du nom du paramètre : les occurrences (surlignage,
                // références, renommage) y passent, l'arité garde sa
                // liste signée ci-dessus. Une propriété de CORPS garde
                // le même genre/conteneur — miroir exact.
                parametresPrimairesKt(declaration).forEach { parametre ->
                    if (!parametre.estPropriete()) return@forEach
                    val nomParam = parametre.nomJeton() ?: return@forEach
                    resultat += DeclarationProjet(
                        genre = GenreDeclaration.PROPRIETE,
                        nom = nomParam.texte.toString(),
                        uri = uri,
                        debutNom = nomParam.debut,
                        finNom = nomParam.fin,
                        paquet = paquet,
                        recepteur = null,
                        typeRetour = parametre.type(),
                        parametres = emptyList(),
                        conteneur = qualifieConteneur,
                    )
                }
                parcourirConteneur(declaration.membres(), uri, paquet, qualifieConteneur, resultat)

                // Retour utilisateurs U1/U3 (25/10/2026) — membres SYNTHÉTISÉS
                // des data classes : kotlinc génère `copy(x1 = v1, ...)` (un
                // paramètre par propriété du constructeur primaire, TOUS à
                // défaut) et `component1..N` (destructuration). Léa : «
                // existant.copy(achete = true) — non résolue, sur CHAQUE
                // data class » ; Nina : idem ×3, et la complétion `article.`
                // offrait les 6 propriétés mais PAS copy. Miroir de la règle
                // ADR 0206 (les paramètres val/var deviennent propriétés) :
                // les membres générés par `data` deviennent des déclarations
                // membre — mêmes genres/conteneurs que les fonctions écrites.
                if (declaration.estData()) {
                    val ktParams = parametresPrimairesKt(declaration)
                    if (ktParams.isNotEmpty()) {
                        val paramsExtraits = ktParams.map { parametre -> parametreExtraire(parametre) }
                        resultat += DeclarationProjet(
                            genre = GenreDeclaration.FONCTION,
                            nom = "copy",
                            uri = uri,
                            debutNom = nom.debut,
                            finNom = nom.fin,
                            paquet = paquet,
                            recepteur = null,
                            typeRetour = qualifieConteneur,
                            parametres = paramsExtraits,
                            conteneur = qualifieConteneur,
                            // Tous les paramètres de copy portent un défaut
                            // (valeur de la propriété courante) : minimum 0.
                            nombreParametresDefaut = paramsExtraits.size,
                        )
                        ktParams.forEachIndexed { index, parametre ->
                            val nomParam = parametre.nomJeton() ?: return@forEachIndexed
                            resultat += DeclarationProjet(
                                genre = GenreDeclaration.FONCTION,
                                nom = "component${index + 1}",
                                uri = uri,
                                debutNom = nomParam.debut,
                                finNom = nomParam.fin,
                                paquet = paquet,
                                recepteur = null,
                                typeRetour = parametre.type(),
                                parametres = emptyList(),
                                conteneur = qualifieConteneur,
                            )
                        }
                    }
                }
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

    /**
     * Extrait une déclaration d'OBJET — ADR 0220 (membres scannés,
     * objets référençables) et ADR 0222 (COMPANION : nom effectif
     * « Companion » si anonyme, membres émis DEUX FOIS — sous
     * l'objet qualifié `pkg.Boite.Companion` (accès explicite) ET
     * sous la CLASSE ENGLOBANTE `pkg.Boite` (accès par le nom de la
     * classe, `Boite.creer()` — miroir kotlinc : le nom de la classe
     * PRÊTE les membres de son compagnon).
     */
    private fun extraireObjet(
        declaration: KtObjectDeclaration,
        uri: String,
        paquet: String?,
        conteneur: String?,
        resultat: MutableList<DeclarationProjet>,
    ) {
        val nomJeton = declaration.nomObjet()
        // Companion hors classe (conteneur null) : syntaxe invalide
        // Kotlin — traité comme un objet simple anonyme (ignoré D3).
        val compagnon = declaration.estCompanion() && conteneur != null
        val nomEffectif =
            when {
                nomJeton != null -> nomJeton.texte.toString()
                compagnon -> "Companion"
                else -> return
            }
        val qualifieObjet =
            if (conteneur == null) {
                (paquet?.let { "$it." } ?: "") + nomEffectif
            } else {
                "$conteneur.$nomEffectif"
            }
        resultat +=
            DeclarationProjet(
                genre = GenreDeclaration.OBJET,
                nom = nomEffectif,
                uri = uri,
                debutNom = nomJeton?.debut ?: declaration.noeud.debut,
                finNom = nomJeton?.fin ?: declaration.noeud.fin,
                paquet = paquet,
                recepteur = null,
                typeRetour = null,
                parametres = emptyList(),
                conteneur = conteneur,
                // Vague 3 : `object Foo` SANS supertype écrit ne porte
                // que ses membres DÉCLARÉS + Any implicite (un object
                // n'a NI compagnon prêt, NI méthode synthétique — la
                // preuve d'absence y est fondée, D3).
                estSansHeritageEcrit = declaration.superTypes().isEmpty(),
            )
        // Membres sous l'OBJET (accès `Boite.Companion.creer()` et
        // corps du compagnon — this implicite).
        parcourirConteneur(declaration.membres(), uri, paquet, qualifieObjet, resultat)
        // ADR 0222 : les MÊMES membres sous la CLASSE ENGLOBANTE —
        // `Boite.creer()` (le nom de classe prête le compagnon).
        if (compagnon) {
            parcourirConteneur(declaration.membres(), uri, paquet, conteneur, resultat)
        }
    }

    /** (nom, type écrit) d'un paramètre. */
    private fun parametreExtraire(parametre: KtParameter): Pair<String?, String?> =
        Pair(parametre.nom(), parametre.type())

    /**
     * Paramètres du constructeur PRIMAIRE d'une classe (K4) : la liste
     * de paramètres vit SOUS le nœud PRIMARY_CONSTRUCTOR (enfant du
     * nœud CLASS — sondé v0.134.14 : la lecture « enfant directe du
     * nœud CLASS » ne rendait RIEN, tout appel de constructeur à
     * N arguments devenait un faux `kt.argumentCount` ; le chemin
     * direct reste sondé en repli, celles des constructeurs
     * secondaires vivent dans SECONDARY_CONSTRUCTOR).
     */
    private fun parametresPrimaires(classe: KtClass): List<Pair<String?, String?>> =
        parametresPrimairesKt(classe).map { parametre -> parametreExtraire(parametre) }

    /** Paramètres KtParameter du constructeur primaire (support ADR 0206). */
    private fun parametresPrimairesKt(classe: KtClass): List<KtParameter> {
        val liste =
            classe.noeud.sousNoeuds
                .firstOrNull { it.type == KtNodeTypes.PRIMARY_CONSTRUCTOR }
                ?.sousNoeuds
                ?.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
                ?: classe.noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
                ?: return emptyList()
        return liste.sousNoeuds
            .filter { it.type == KtNodeTypes.VALUE_PARAMETER }
            .map { KtParameter(it) }
    }

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
        // Vague 4 (retour utilisateurs 25/10/2026 — U2 « super(contexte,
        // attributs, styleDef) », « field »/« valeur ») : les paramètres
        // du constructeur PRIMAIRE ne sont lisibles que dans la zone de
        // CONSTRUCTION de la classe — clause super, initialisateurs de
        // propriétés, blocs init (expérience kotlinc 2.2.10 : un setter
        // custom et un corps de méthode les REFUSENT « unresolved » ;
        // un initialisateur et un init les ACCEPTENT). Le drapeau coupe
        // la portée dès qu'un CORPS (méthode, setter, constructeur
        // secondaire, classe imbriquée) sépare la référence du CLASS.
        var coupe = false
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
                    // Corps de méthode : les paramètres du primaire de
                    // TOUTE classe englobante y sont HORS portée.
                    coupe = true
                }

                KtNodeTypes.PROPERTY_ACCESSOR -> {
                    // Vague 4 : accesseur custom — le PARAMÈTRE du
                    // setter (`set(valeur) { … }`) est en portée dans
                    // tout le corps de l'accesseur (expérience kotlinc :
                    // seuls le paramètre et `field` y sont lisibles).
                    val liste =
                        noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
                    liste?.sousNoeuds
                        ?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
                        ?.forEach { parametre ->
                            parametreDeclarationLocal(jo.lspkotlin.parse.kt.KtParameter(parametre))
                                ?.let { resultat.add(LocaleEnPortee(it, null)) }
                        }
                    // `field` — l'identifiant de champ PORTANT est
                    // SYNTHÉTIQUE (aucun jeton de déclaration) : prêté
                    // comme locale de portée l'accesseur (miroir Android
                    // Studio, qui le propose en complétion du setter).
                    resultat += LocaleEnPortee(
                        DeclarationProjet(
                            genre = GenreDeclaration.LOCALE,
                            nom = "field",
                            uri = "",
                            debutNom = noeud.debut,
                            finNom = noeud.fin,
                            paquet = null,
                            recepteur = null,
                            typeRetour = null,
                            parametres = emptyList(),
                            conteneur = null,
                        ),
                        null,
                    )
                    // Corps d'accesseur : les paramètres du primaire y
                    // sont HORS portée (expérience kotlinc).
                    coupe = true
                }

                KtNodeTypes.FUNCTION_LITERAL -> {
                    // Paramètres EXPLICITES du littéral (découverte E1 du
                    // rapport Compose, ADR 0217) : `{ innerPadding -> … }`,
                    // `{ nombre -> … }` — en portée dans TOUT le corps du
                    // littéral. Sans cette collecte, TOUT paramètre nommé
                    // de lambda était un faux `kt.unresolved` (le code
                    // Compose en est saturé : trailing lambdas partout) —
                    // miroir kotlinc : le paramètre lie TOUTE la fonction.
                    // Vague 5 (retour utilisateurs 25/10/2026 — U3
                    // « sortedByDescending { (_, total) -> total } ») :
                    // un paramètre DESTRUCTURÉ (parenthèse directe — le
                    // type écrit vit dans un NŒUD enfant TYPE_REFERENCE,
                    // jamais en jeton direct) prête CHAQUE composant
                    // nommé (`_` exclu) comme locale, miroir kotlinc :
                    // `{ (a, b) -> a + b }` lie les deux noms.
                    val liste =
                        noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
                    liste?.sousNoeuds
                        ?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
                        ?.forEach { parametre ->
                            if (parametre.jetonsDirects.any { it.type == KtTokens.LPAR }) {
                                parametre.jetonsDirects
                                    .filter { jeton ->
                                        jeton.type == KtTokens.IDENTIFIER &&
                                            jeton.texte.toString() != "_"
                                    }
                                    .forEach { identifiant ->
                                        resultat +=
                                            LocaleEnPortee(
                                                DeclarationProjet(
                                                    genre = GenreDeclaration.LOCALE,
                                                    nom = identifiant.texte.toString(),
                                                    uri = "",
                                                    debutNom = identifiant.debut,
                                                    finNom = identifiant.fin,
                                                    paquet = null,
                                                    recepteur = null,
                                                    typeRetour = null,
                                                    parametres = emptyList(),
                                                    conteneur = null,
                                                ),
                                                null,
                                            )
                                    }
                            } else {
                                parametreDeclarationLocal(jo.lspkotlin.parse.kt.KtParameter(parametre))
                                    ?.let { resultat.add(LocaleEnPortee(it, null)) }
                            }
                        }
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

                KtNodeTypes.BLOCK, KtNodeTypes.CLASS_BODY -> {
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

                KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION -> {
                    // Vague 4 : la CLASSE prête les paramètres de son
                    // constructeur PRIMAIRE tant qu'aucun CORPS (méthode,
                    // setter, constructeur secondaire, classe imbriquée)
                    // n'a été croisé plus profond — zone de CONSTRUCTION
                    // (clause super, initialisateurs, init), miroir
                    // kotlinc prouvé par expérience. Un objet n'a pas de
                    // constructeur primaire utile : il ne prête rien.
                    if (!coupe && noeud.type == KtNodeTypes.CLASS) {
                        jo.lspkotlin.parse.kt.KtClass(noeud).let { classe ->
                            parametresPrimairesKt(classe).forEach { parametre ->
                                parametreDeclarationLocal(parametre)?.let {
                                    resultat.add(LocaleEnPortee(it, null))
                                }
                            }
                        }
                    }
                    // Classe imbriquée intermédiaire : les primaires des
                    // classes PLUS EXTERNES ne fuient pas dedans.
                    coupe = true
                }

                KtNodeTypes.SECONDARY_CONSTRUCTOR -> {
                    // Corps d'un constructeur secondaire : les paramètres
                    // du primaire y sont HORS portée (miroir kotlinc).
                    coupe = true
                }

                else -> {
                    // BLOCK, CLASS_INITIALIZER, littéraux, types : ils ne
                    // coupent PAS la portée du primaire (expérience
                    // kotlinc — un initialisateur et un init la gardent).
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
