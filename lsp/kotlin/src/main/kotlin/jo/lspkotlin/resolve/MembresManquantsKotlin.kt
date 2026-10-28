package jo.lspkotlin.resolve

import jo.lspkotlin.api.CorrectifMembresManquantsKotlin
import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.DRAPEAU_STATIQUE_KOTLIN
import jo.lspkotlin.api.VueClasspathKotlin
import jo.lspkotlin.classpath.LecteurClassBrut
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtClass
import jo.lspkotlin.parse.kt.KtElement
import jo.lspkotlin.parse.kt.KtFunction
import jo.lspkotlin.parse.kt.KtObjectDeclaration
import jo.lspkotlin.parse.kt.KtParameter
import jo.lspkotlin.parse.kt.KtProperty
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.estExpression
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import java.util.regex.Matcher

/**
 * Quick-fix « IMPLÉMENTER LES MEMBRES MANQUANTS » (chantier P1-C, ADR
 * 0229 — premier correctif Kotlin AU-DELÀ des imports) : les stubs des
 * membres ABSTRAITS de la clôture des supertypes d'une classe/objet,
 * non déjà implémentés, indentés comme le corps, rendus comme UNE
 * édition concrète.
 *
 * ## La marche (miroir du GO TO Super, ADR 0210)
 *  1. la classe/objet ENGLOBANT le curseur (la plus profonde) ;
 *  2. chaque supertype ÉCRIT, résolu par les IMPORTS du fichier
 *     déclarant (preuve FQCN — jamais d'homonymie) ;
 *  3. la clôture TRANSITIVE : un lien PROJET se lit dans SON arbre
 *     (port de la session — arbre vif du document ouvert, sinon lecture
 *     disque bornée) et prouve SA propre clause dans SON fichier ; un
 *     lien CLASSPATH parcourt sa chaîne BINAIRE (vue JVM).
 *
 * ## Fidélité des stubs
 *  - **PROJET** : noms et types RÉELS de l'arbre — les paramètres de
 *    type du supertype sont SUBSTITUÉS par les arguments ÉCRITS de la
 *    clause (`class Repo : Depot<String>` rend `fun lire(id: String)`) ;
 *    un argument absent (clause nue) rend le membre muet s'il mentionne
 *    le paramètre.
 *  - **CLASSPATH** : vue JVM érasée — paramètres `p0, p1…`, types
 *    `renduCourt` ; les getters/setters abstraits sont APPARIÉS en
 *    propriétés (`getX()` seul → `override val x`, avec setX → `var`).
 *    Un membre portant l'attribut `Signature` du bytecode (variable de
 *    type OU type paramétré — son rendu érasé ne compilerait pas) est
 *    ÉCARTÉ : un stub faux est pire que pas de stub (D3),
 *    [LecteurClassBrut.methodesGeneriques].
 *
 * ## Bornes honnêtes (D3)
 *  - hors classe/objet, INTERFACE, classe ABSTRAITE (rien n'est exigé),
 *    sans supertype écrit, tout déjà implémenté : `null` ;
 *  - les sources JAVA du scan ne sont jamais traversées (même garde que
 *    le GO TO Super) ;
 *  - un membre fourni CONCRETEMENT quelque part dans la clôture n'est
 *    jamais collecté (seuls les ABSTRAITS le sont) ;
 *  - exclusions par NOM+ARITÉ pour les fonctions, par NOM pour les
 *    propriétés (le corps de la classe, constructeur primaire compris) ;
 *  - profondeur et volume bornés ([PROFONDEUR_MAXI],
 *    [PLAFOND_MEMBRES]).
 *
 * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque garde est
 * une ISSUE du conservatisme D3 ; TooManyFunctions — une étape de la
 * marche par méthode (catalogue) ; LoopWithTooManyJumpStatements —
 * chaque continue SAUTE un candidat non prouvé (le patron de la
 * collecte) ; LongMethod — le rendu d'un stub est une SECTION unique ;
 * CyclomaticComplexMethod/NestedBlockDepth — la marche d'un lien
 * (projet ou classpath) est le CATALOGUE des formes de membres
 * (fonction/propriété, getter/setteur, générique écarté), chaque
 * branche est une issue documentée de l'ADR.
 */
@Suppress(
    "ReturnCount",
    "TooManyFunctions",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
)
internal object MembresManquantsKotlin {
    /** Drapeau JVM `abstract` (membres des supertypes binaires). */
    private const val DRAPEAU_ABSTRAIT: Int = 0x0400

    /** Plafond de profondeur de la clôture (miroir SuperKotlin ×¾). */
    private const val PROFONDEUR_MAXI: Int = 12

    /** Plafond de stubs rendus (défense hiérarchies pathologiques). */
    private const val PLAFOND_MEMBRES: Int = 64

    /** Indentation d'un membre quand le corps n'en montre aucune. */
    private const val INDENTATION_DEFAUT: String = "    "

    /** Genres de déclaration qui SONT des types (miroir SuperKotlin). */
    private val GENRES_TYPES =
        setOf(GenreDeclaration.CLASSE, GenreDeclaration.INTERFACE, GenreDeclaration.OBJET)

    /**
     * Contexte de la marche : nommage du fichier courant, résolveur,
     * déclarations projet, port de lecture des fichiers déclarants,
     * vue classpath (octets pour la sonde générique).
     */
    internal class Contexte(
        val contexteNom: ResolutionKotlin.ContexteNom,
        val resolution: ResolutionKotlin,
        val declarationsProjet: List<DeclarationProjet>,
        val port: SuperKotlin.PortFichier,
        val vue: VueClasspathKotlin?,
    )

    /** Un membre à rendre (fonction ou propriété). */
    private data class MembreManquant(
        /** Clé d'exclusion/dédup : `f:nom/arité` ou `p:nom`. */
        val cle: String,
        val estFonction: Boolean,
        val estVar: Boolean,
        val nom: String,
        /** Fonctions : paramètres rendus `(nom, type)`. */
        val parametres: List<Pair<String, String>>,
        /** Fonctions : type de RETOUR (null = omis) ; propriétés : le type. */
        val type: String?,
    )

    /** Exclusions du corps de la classe ciblée. */
    private data class Exclusions(
        /** Fonctions déclarées : nom → arités. */
        val fonctions: Map<String, Set<Int>>,
        /** Propriétés déclarées (corps + constructeur primaire). */
        val proprietes: Set<String>,
    )

    /**
     * Le correctif de la classe/objet englobant [decalage], ou `null`
     * (aucune des gardes ne passe / rien ne manque).
     */
    internal fun correctif(
        racine: NoeudSyntaxique,
        texte: CharSequence,
        decalage: Int,
        contexte: Contexte,
    ): CorrectifMembresManquantsKotlin? {
        val chaine = chaineAncetresA(racine, decalage)
        val noeud =
            chaine.lastOrNull {
                it.type == KtNodeTypes.CLASS || it.type == KtNodeTypes.OBJECT_DECLARATION
            } ?: return null
        val classe = KtElement.depuis(noeud)
        val supertypesEcrites: List<String> =
            when (classe) {
                is KtClass -> {
                    if (classe.estInterface()) return null
                    if (classe.modificateurs().any { it == KtTokens.ABSTRACT_MODIFIER }) return null
                    classe.superTypes()
                }

                is KtObjectDeclaration -> classe.superTypes()

                else -> return null
            }
        if (supertypesEcrites.isEmpty()) return null
        val membres = collecter(supertypesEcrites, exclusionsDe(classe), contexte)
        if (membres.isEmpty()) return null
        return edition(noeud, classe, membres, texte)
    }

    // ── Collecte : la clôture des supertypes ───────────────────────────

    /**
     * Marche la clôture : les liens DIRECTS écrits, puis BFS — un lien
     * PROJET contribue ses membres abstraits (arbre vif via le port) et
     * sa PROPRE clause ; un lien CLASSPATH contribue ses membres
     * abstraits (vue JVM + sonde générique) et sa chaîne BINAIRE.
     */
    private fun collecter(
        supertypesEcrites: List<String>,
        exclusions: Exclusions,
        contexte: Contexte,
    ): List<MembreManquant> {
        val resultat = mutableListOf<MembreManquant>()
        val vues = HashSet<String>()
        val file = ArrayDeque<Lien>()
        val contexteFichier =
            OccurrencesKotlin.ContexteFichier(contexte.contexteNom, contexte.declarationsProjet)
        for (ecrit in supertypesEcrites) {
            val fqcn = SuperKotlin.fqcnSupertype(ecrit, contexteFichier, contexte.resolution) ?: continue
            file.addLast(Lien(fqcn, argumentsTypeDe(ecrit)))
        }
        var profondeur = 0
        while (file.isNotEmpty() && profondeur < PROFONDEUR_MAXI && resultat.size < PLAFOND_MEMBRES) {
            val lien = file.removeFirst()
            profondeur++
            if (!vues.add(lien.fqcn)) continue
            val declaration =
                contexte.declarationsProjet.firstOrNull { candidat ->
                    candidat.uri.isNotBlank() &&
                        !candidat.estJava &&
                        candidat.genre in GENRES_TYPES &&
                        SuperKotlin.fqcnDe(candidat) == lien.fqcn
                }
            if (declaration != null) {
                for (enfant in liensProjetDe(declaration, lien.arguments, contexte)) {
                    file.addLast(enfant)
                }
                resultat += membresProjetDe(declaration, lien.arguments, contexte)
            } else {
                for (maillon in contexte.resolution.chaineSupertypes(lien.fqcn).drop(1)) {
                    file.addLast(Lien(maillon, emptyList()))
                }
                resultat += membresClasspathDe(lien.fqcn, contexte)
            }
        }
        return dedupliquer(resultat, exclusions)
    }

    /** Un maillon de la file : FQCN + arguments de type ÉCRITS (concrets). */
    private data class Lien(
        val fqcn: String,
        val arguments: List<String>,
    )

    /**
     * Membres ABSTRAITS d'un lien PROJET : lus dans SON arbre (port) —
     * une fonction sans corps (ni bloc ni expression), une propriété
     * sans initialiseur ni accesseur. Les paramètres de type du lien
     * sont substitués par les arguments de la clause ; un type
     * introuvable/inféré rend le membre MUET (jamais deviné).
     */
    private fun membresProjetDe(
        declaration: DeclarationProjet,
        arguments: List<String>,
        contexte: Contexte,
    ): List<MembreManquant> {
        val fichier = contexte.port.fichierDe(declaration) ?: return emptyList()
        val noeudDeclaration =
            InterrogationsSymboles.declarationViveAPlage(fichier.arbre.racine, declaration)?.noeud
                ?: return emptyList()
        val parametresType = parametresTypeDe(noeudDeclaration)
        val substitution =
            if (parametresType.size == arguments.size) {
                parametresType.zip(arguments).toMap()
            } else {
                emptyMap() // clause nue sur un type générique : sans substitution fiable
            }
        val resultat = mutableListOf<MembreManquant>()
        val conteneur = SuperKotlin.fqcnDe(declaration)
        for (membre in contexte.declarationsProjet) {
            if (membre.conteneur != conteneur) continue
            if (membre.genre != GenreDeclaration.FONCTION && membre.genre != GenreDeclaration.PROPRIETE) continue
            val vive =
                InterrogationsSymboles.declarationViveAPlage(fichier.arbre.racine, membre)
                    ?: continue
            when (membre.genre) {
                GenreDeclaration.FONCTION ->
                    if (vive is KtFunction) {
                        fonctionManquante(vive, membre, parametresType, substitution)?.let { resultat.add(it) }
                    }

                GenreDeclaration.PROPRIETE ->
                    if (vive is KtProperty) {
                        proprieteManquante(vive, membre, parametresType, substitution)?.let { resultat.add(it) }
                    }

                else -> Unit
            }
        }
        return resultat
    }

    /**
     * Le stub d'une FONCTION abstraite du projet — `null` si concrète
     * (corps présent) ou muette (type écrit introuvable, paramètre de
     * type sans substitution fiable : jamais deviné).
     */
    private fun fonctionManquante(
        vive: KtFunction,
        membre: DeclarationProjet,
        parametresType: List<String>,
        substitution: Map<String, String>,
    ): MembreManquant? {
        if (aCorps(vive)) return null
        val parametres = mutableListOf<Pair<String, String>>()
        for ((indice, parametre) in vive.parametres().withIndex()) {
            val typeEcrit = parametre.type()
            if (typeEcrit == null || mentionne(typeEcrit, parametresType, substitution)) return null
            parametres.add(Pair(parametre.nom() ?: "p$indice", substituer(typeEcrit, substitution)))
        }
        val retourEcrit = vive.typeRetour() ?: return stubFonction(membre.nom, parametres, null)
        if (mentionne(retourEcrit, parametresType, substitution)) return null
        return stubFonction(membre.nom, parametres, substituer(retourEcrit, substitution))
    }

    /** Le squelette de stub d'une fonction (paramètres + retour). */
    private fun stubFonction(
        nom: String,
        parametres: List<Pair<String, String>>,
        retour: String?,
    ): MembreManquant =
        MembreManquant(
            cle = "f:$nom/${parametres.size}",
            estFonction = true,
            estVar = false,
            nom = nom,
            parametres = parametres,
            type = retour,
        )

    /**
     * Le stub d'une PROPRIÉTÉ abstraite du projet — `null` si concrète
     * (initialiseur ou accesseur) ou muette (type non écrit).
     */
    private fun proprieteManquante(
        vive: KtProperty,
        membre: DeclarationProjet,
        parametresType: List<String>,
        substitution: Map<String, String>,
    ): MembreManquant? {
        if (vive.initialisateur() != null) return null
        if (vive.noeud.sousNoeuds.any { it.type == KtNodeTypes.PROPERTY_ACCESSOR }) return null
        val typeEcrit = vive.type() ?: return null
        if (mentionne(typeEcrit, parametresType, substitution)) return null
        return MembreManquant(
            cle = "p:${membre.nom}",
            estFonction = false,
            estVar = vive.noeud.jetonsDescendants().any { it.type == KtTokens.VAR_KEYWORD },
            nom = membre.nom,
            parametres = emptyList(),
            type = substituer(typeEcrit, substitution),
        )
    }

    /**
     * Les liens FILS d'un lien PROJET : sa clause ÉCRITE, résolue par
     * les imports de SON fichier — les arguments de type sont substitués
     * par la substitution du lien (la clôture compose les arguments).
     */
    private fun liensProjetDe(
        declaration: DeclarationProjet,
        arguments: List<String>,
        contexte: Contexte,
    ): List<Lien> {
        val fichier = contexte.port.fichierDe(declaration) ?: return emptyList()
        val noeudDeclaration =
            InterrogationsSymboles.declarationViveAPlage(fichier.arbre.racine, declaration)?.noeud
                ?: return emptyList()
        val parametresType = parametresTypeDe(noeudDeclaration)
        val substitution =
            if (parametresType.size == arguments.size) {
                parametresType.zip(arguments).toMap()
            } else {
                emptyMap()
            }
        val resultat = mutableListOf<Lien>()
        for (ecrit in TypesKotlin.superTypesDe(noeudDeclaration)) {
            val fqcn = SuperKotlin.fqcnSupertype(ecrit, fichier.contexte, contexte.resolution) ?: continue
            val args = argumentsTypeDe(ecrit).map { argument -> substituer(argument, substitution) }
            resultat.add(Lien(fqcn, args))
        }
        return resultat
    }

    /**
     * Membres ABSTRAITS d'un lien CLASSPATH : vue JVM + sonde
     * générique ([LecteurClassBrut.methodesGeneriques] — `null` sur
     * octets illisibles : le lien ENTIER se tait, jamais un stub
     * incertain). Les getters/setters abstraits sont appariés en
     * propriétés `val`/`var` (miroir des propriétés synthétiques de la
     * complétion membre, ADR 0219).
     */
    private fun membresClasspathDe(
        fqcn: String,
        contexte: Contexte,
    ): List<MembreManquant> {
        val membres = contexte.resolution.membresDe(fqcn) ?: return emptyList()
        val octets = contexte.vue?.octetsClasse(fqcn) ?: return emptyList()
        val generiques = LecteurClassBrut.methodesGeneriques(octets) ?: return emptyList()
        val abstraits =
            membres.methodes.filter {
                (it.drapeaux and DRAPEAU_ABSTRAIT) != 0 &&
                    (it.drapeaux and DRAPEAU_PUBLIC_KOTLIN) != 0 &&
                    (it.drapeaux and DRAPEAU_STATIQUE_KOTLIN) == 0 &&
                    nomPropre(it.nom)
            }
        val accesseurs = HashMap<String, MembreManquant>() // propriété → accesseur en attente
        val resultat = mutableListOf<MembreManquant>()
        for (methode in abstraits) {
            if ("${methode.nom}:${methode.descripteur}" in generiques) continue
            val traduit = contexte.resolution.traduireDescripteur(methode.descripteur)
            // GETTER : 0 paramètre, retour non void, motif get*/is*.
            val proprieteGet = contexte.resolution.proprieteSynthetiqueDe(methode)
            if (proprieteGet != null && traduit.parametres.isEmpty() && traduit.retour != "void") {
                accesseurs[proprieteGet] =
                    MembreManquant(
                        cle = "p:$proprieteGet",
                        estFonction = false,
                        estVar = false,
                        nom = proprieteGet,
                        parametres = emptyList(),
                        type = contexte.resolution.renduCourt(traduit.retour),
                    )
                continue
            }
            // SETTEUR : 1 paramètre, void, motif set*.
            val proprieteSet = nomProprieteSetteur(methode)
            if (proprieteSet != null && traduit.parametres.size == 1 && traduit.retour == "void") {
                val existant = accesseurs[proprieteSet]
                if (existant != null) {
                    accesseurs[proprieteSet] = existant.copy(estVar = true)
                } else {
                    accesseurs[proprieteSet] =
                        MembreManquant(
                            cle = "p:$proprieteSet",
                            estFonction = false,
                            estVar = true,
                            nom = proprieteSet,
                            parametres = emptyList(),
                            type = contexte.resolution.renduCourt(traduit.parametres.first()),
                        )
                }
                continue
            }
            // FONCTION ordinaire : paramètres p0…, retour omis si Unit.
            val parametres =
                traduit.parametres.mapIndexed { indice, type ->
                    Pair("p$indice", contexte.resolution.renduCourt(type))
                }
            val retour = contexte.resolution.renduCourt(traduit.retour)
            resultat +=
                MembreManquant(
                    cle = "f:${methode.nom}/${parametres.size}",
                    estFonction = true,
                    estVar = false,
                    nom = methode.nom,
                    parametres = parametres,
                    type = if (retour == "Unit") null else retour,
                )
        }
        // Les accesseurs non appariés restent ce qu'ils sont (val seul,
        // var seule) — tous rejoignent les fonctions ordinaires.
        resultat.addAll(accesseurs.values)
        return resultat
    }

    /** Nom de propriété d'un setteur (`setTitre` → `titre`), ou null. */
    private fun nomProprieteSetteur(methode: jo.lspkotlin.api.MembreJvmKotlin): String? {
        val apresSet = methode.nom.removePrefix("set")
        if (apresSet.length == methode.nom.length || apresSet.isEmpty()) return null
        if (!apresSet.first().isUpperCase()) return null
        if (apresSet.any { it == '$' }) return null
        return if (apresSet.length > 1 && apresSet[0].isUpperCase() && apresSet[1].isUpperCase()) {
            apresSet
        } else {
            apresSet.replaceFirstChar { it.lowercaseChar() }
        }
    }

    /**
     * La fonction a-t-elle un CORPS — BLOC `{ … }` OU expression
     * `= …` (une fonction d'interface avec corps est CONCRÈTE, elle ne
     * manque à personne) ?
     */
    private fun aCorps(fonction: KtFunction): Boolean {
        if (fonction.corps() != null) return true
        val liste =
            fonction.noeud.sousNoeuds.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
        return fonction.noeud.sousNoeuds.any { enfant ->
            (liste == null || enfant.debut >= liste.fin) && estExpression(enfant.type)
        }
    }

    // ── Exclusions (le corps de la classe ciblée) ──────────────────────

    /** Fonctions par nom+arité, propriétés par nom (corps + primaire). */
    private fun exclusionsDe(classe: KtElement): Exclusions {
        val fonctions = HashMap<String, MutableSet<Int>>()
        val proprietes = mutableSetOf<String>()
        val membres: List<jo.lspkotlin.parse.kt.KtDeclaration> =
            when (classe) {
                is KtClass -> classe.membres()
                is KtObjectDeclaration -> classe.membres()
                else -> emptyList()
            }
        for (membre in membres) {
            when (membre) {
                is KtFunction -> fonctions.getOrPut(membre.nom() ?: continue) { mutableSetOf() }
                    .add(membre.parametres().size)

                is KtProperty -> proprietes.add(membre.nom() ?: continue)

                else -> Unit
            }
        }
        // Constructeur PRIMAIRE : chaque paramètre val/var EST une
        // propriété membre (ADR 0206).
        if (classe is KtClass) {
            for (parametre in parametresPrimaires(classe)) {
                if (parametre.estPropriete()) proprietes.add(parametre.nom() ?: continue)
            }
        }
        return Exclusions(fonctions, proprietes)
    }

    /** Paramètres du constructeur primaire (support ADR 0206). */
    private fun parametresPrimaires(classe: KtClass): List<KtParameter> =
        classe.noeud.sousNoeuds
            .firstOrNull { it.type == KtNodeTypes.PRIMARY_CONSTRUCTOR }
            ?.sousNoeuds
            ?.firstOrNull { it.type == KtNodeTypes.VALUE_PARAMETER_LIST }
            ?.sousNoeuds
            ?.filter { it.type == KtNodeTypes.VALUE_PARAMETER }
            ?.map { KtParameter(it) }
            ?: emptyList()

    /** Déduplique (clé) et EXCLUT les membres déjà implémentés. */
    private fun dedupliquer(
        membres: List<MembreManquant>,
        exclusions: Exclusions,
    ): List<MembreManquant> {
        val vues = HashSet<String>()
        val resultat = mutableListOf<MembreManquant>()
        for (membre in membres) {
            if (!vues.add(membre.cle)) continue
            if (membre.estFonction) {
                val nom = membre.nom
                if (membre.parametres.size in (exclusions.fonctions[nom] ?: emptySet())) continue
            } else {
                if (membre.nom in exclusions.proprietes) continue
            }
            resultat.add(membre)
        }
        return resultat
    }

    // ── Sonde des paramètres de type / substitution ────────────────────

    /** Noms des paramètres de type d'une déclaration (`<T, R>`). */
    private fun parametresTypeDe(noeud: NoeudSyntaxique): List<String> =
        noeud.sousNoeuds
            .firstOrNull { it.type == KtNodeTypes.TYPE_PARAMETER_LIST }
            ?.sousNoeuds
            ?.filter { it.type == KtNodeTypes.TYPE_PARAMETER }
            ?.mapNotNull { parametre ->
                parametre.jetonsDescendants().firstOrNull { it.type == KtTokens.IDENTIFIER }?.texte?.toString()
            }
            ?: emptyList()

    /**
     * Arguments de type d'une entrée ÉCRITE (`Depot<String, Int>` →
     * `[String, Int]`) — virgules de PROFONDEUR zéro seulement
     * (`Map<K, List<V>>` reste entière).
     */
    private fun argumentsTypeDe(ecrit: String): List<String> {
        val ouverture = ecrit.indexOf('<')
        if (ouverture < 0) return emptyList()
        val fermeture = ecrit.lastIndexOf('>')
        if (fermeture <= ouverture) return emptyList()
        val arguments = mutableListOf<String>()
        val courant = StringBuilder()
        var profondeur = 0
        for (car in ecrit.substring(ouverture + 1, fermeture)) {
            when {
                car == '<' -> {
                    profondeur++
                    courant.append(car)
                }

                car == '>' -> {
                    profondeur--
                    courant.append(car)
                }

                car == ',' && profondeur == 0 -> {
                    arguments.add(courant.toString().trim())
                    courant.setLength(0)
                }

                else -> courant.append(car)
            }
        }
        if (courant.isNotBlank()) arguments.add(courant.toString().trim())
        return arguments.filter { it.isNotEmpty() }
    }

    /**
     * Le type écrit mentionne-t-il un paramètre de type SANS
     * substitution fiable (clause nue) ? Ces membres sont muets.
     */
    private fun mentionne(
        type: String,
        parametres: List<String>,
        substitution: Map<String, String>,
    ): Boolean {
        if (substitution.isNotEmpty()) return false
        return parametres.any { parametre -> typeMot(type, parametre) }
    }

    /** Substitue les paramètres de type (mot entier) par leurs arguments. */
    private fun substituer(
        type: String,
        substitution: Map<String, String>,
    ): String {
        var rendu = type
        for ((parametre, argument) in substitution) {
            rendu = Regex("\\b$parametre\\b").replace(rendu, Matcher.quoteReplacement(argument))
        }
        return rendu
    }

    /** Le type contient-il le mot [mot] (frontières de mot) ? */
    private fun typeMot(
        type: String,
        mot: String,
    ): Boolean = Regex("\\b$mot\\b").containsMatchIn(type)

    // ── Rendu et édition ───────────────────────────────────────────────

    /**
     * L'édition concrète : stubs indentés comme le corps — corps VIDE :
     * le blanc entre accolades est REMPLACÉ ; corps PEUPLÉ : le blanc
     * avant l'accolade fermante ; corps ABSENT : le corps est CRÉÉ en
     * fin d'en-tête. Le corps hérite l'indentation du premier membre,
     * sinon l'unité heuristique (indent de l'en-tête, sinon 4 espaces).
     */
    private fun edition(
        noeud: NoeudSyntaxique,
        classe: KtElement,
        membres: List<MembreManquant>,
        texte: CharSequence,
    ): CorrectifMembresManquantsKotlin {
        val corps: NoeudSyntaxique? =
            when (classe) {
                is KtClass -> classe.corps()?.noeud
                is KtObjectDeclaration -> classe.corps()?.noeud
                else -> null
            }
        val indentEntete = indentationDeLigne(texte, noeud.debut)
        val membresDeclares: List<jo.lspkotlin.parse.kt.KtDeclaration> =
            when (classe) {
                is KtClass -> classe.membres()
                is KtObjectDeclaration -> classe.membres()
                else -> emptyList()
            }
        val indentMembre =
            membresDeclares.firstOrNull()?.debut?.let { debut -> indentationDeLigne(texte, debut) }
                ?: (indentEntete + if (indentEntete.isNotEmpty()) indentEntete else INDENTATION_DEFAUT)
        val rendu = rendu(membres, indentMembre, indentMembre + INDENTATION_DEFAUT)
        if (corps == null) {
            // Corps ABSENT : créé en fin d'en-tête (`class A : B` →
            // `class A : B {\n  …\n}`).
            return CorrectifMembresManquantsKotlin(
                debut = noeud.fin,
                fin = noeud.fin,
                texte = " {\n$rendu\n$indentEntete}",
                nombre = membres.size,
            )
        }
        val fermeture =
            corps.jetonsDirects.lastOrNull { it.type == KtTokens.RBRACE }?.debut
                ?: (corps.fin - 1)
        if (membresDeclares.isEmpty()) {
            // Corps VIDE : le blanc entre accolades est remplacé.
            return CorrectifMembresManquantsKotlin(
                debut = corps.debut + 1,
                fin = fermeture,
                texte = "\n$rendu\n$indentEntete",
                nombre = membres.size,
            )
        }
        val finDernier = membresDeclares.last().fin
        val blanc = texte.substring(finDernier, fermeture)
        return if (blanc.isBlank()) {
            // Corps PEUPLÉ : le blanc du dernier membre à l'accolade.
            CorrectifMembresManquantsKotlin(
                debut = finDernier,
                fin = fermeture,
                texte = "\n$rendu\n$indentEntete",
                nombre = membres.size,
            )
        } else {
            // Un commentaire vit entre le dernier membre et l'accolade :
            // insertion AVANT l'accolade, sans rien détruire.
            CorrectifMembresManquantsKotlin(
                debut = fermeture,
                fin = fermeture,
                texte = "\n$rendu\n$indentEntete",
                nombre = membres.size,
            )
        }
    }

    /** Les stubs rendus, une ligne par membre (`var` : 3 lignes). */
    private fun rendu(
        membres: List<MembreManquant>,
        indentMembre: String,
        indentAccesseur: String,
    ): String =
        membres.joinToString("\n") { membre ->
            when {
                membre.estFonction -> {
                    val parametres =
                        membre.parametres.joinToString(", ") { (nom, type) -> "$nom: $type" }
                    val retour = membre.type?.let { ": $it" } ?: ""
                    indentMembre + "override fun ${membre.nom}($parametres)$retour = TODO()"
                }

                membre.estVar ->
                    indentMembre + "override var ${membre.nom}: ${membre.type}\n" +
                        "${indentAccesseur}get() = TODO()\n" +
                        "${indentAccesseur}set(value) { TODO() }"

                else -> indentMembre + "override val ${membre.nom}: ${membre.type} get() = TODO()"
            }
        }

    /** Blancs de tête de la ligne contenant [decalage]. */
    private fun indentationDeLigne(
        texte: CharSequence,
        decalage: Int,
    ): String {
        var debutLigne = decalage - 1
        while (debutLigne >= 0 && texte[debutLigne] != '\n') debutLigne--
        val debut = StringBuilder()
        for (position in (debutLigne + 1) until decalage) {
            debut.append(texte[position])
        }
        return debut.toString().takeWhile { it == ' ' || it == '\t' }
    }

    /** Nom offrable : non vide, sans `$` ni crochet. */
    private fun nomPropre(nom: String): Boolean = nom.isNotEmpty() && '$' !in nom && !nom.startsWith("<")
}
