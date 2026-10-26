package jo.lspkotlin.session

import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import java.io.File

/**
 * Déclarations des sources JAVA du projet (K5 multi-langue,
 * LSP_KOTLIN.md §5 — « bascule Java↔Kotlin dans un même projet ») :
 * le moteur Kotlin voit les classes/interfaces/énumérations Java ET
 * LES MEMBRES de leurs types de premier niveau pour la RÉSOLUTION de
 * noms, la complétion auto-import, le survol et le typage en chaîne —
 * un projet mixte n'est plus coupé en deux langues (retour
 * utilisateurs 25/10/2026, rapport U2 — Marc, monde Views : « mes
 * POJO Java sont invisibles au moteur Kotlin »).
 *
 * Extraction VOLONTAIREMENT à bornes (D3) :
 *  - types et MEMBRES de PREMIER niveau seuls (les imbriquées Java et
 *    les membres HÉRITÉS d'une superclasse Java sous-rapportent) ;
 *  - membres = champs et méthodes au niveau 1 du corps du type : le
 *    texte des blocs imbriqués (corps de méthodes, classes
 *    anonymes) est ignoré par ANCRAGE de la signature au début de
 *    chaque segment (après `;` ou `}` de profondeur 1) — un appel
 *    dans un initialisateur (`x = Math.max(1, 2)`) n'est JAMAIS lu
 *    comme membre ;
 *  - arité des constructeurs/membres Java NON modélisée : le contrôle
 *    `kt.argumentCount` ignore ces candidates (marqueur [estJava],
 *    jamais de faux « 0 attendu ») ;
 *  - un getter 0-paramètre `getTitre()`/`isActif()` émet EN PLUS la
 *    PROPRIÉTÉ synthétique `titre`/`actif` — le miroir exact du
 *    classpath (ADR 0219) : depuis Kotlin, un POJO Java s'écrit en
 *    propriétés (`vinyle.titre`), pas en getters ;
 *  - un `class`/`interface` sans clause ÉCRITE `extends`/`implements`
 *    (hors contraintes de paramètres génériques) porte
 *    [DeclarationProjet.estSansHeritageEcrit] : son jeu de membres
 *    est exactement les DÉCLARÉS + les IMPLICITES de
 *    java.lang.Object — la preuve d'absence d'un sélecteur y est
 *    fondée (retour utilisateurs 25/10/2026, vague 3) ; les `enum`
 *    (Comparable implicite), `record` (java.lang.Record) et
 *    `@interface` (Annotation) ne le portent JAMAIS ;
 *  - analyse lexicale (commentaires, chaînes et caractères
 *    neutralisés) — un fichier Java illisible produit RIEN.
 *
 * URI et décalages RÉELS : le go-to-definition d'une référence Kotlin
 * vers une classe Java ouvre le fichier `.java` à la position du nom
 * (même mécanique que les cibles Kotlin du scan).
 *
 * Exemptions detekt ciblées (règle 16) : SwallowedException — fichier
 * illisible = ABSENT (partiel honnête D3), l'exception est le signal ;
 * LoopWithTooManyJumpStatements / ReturnCount / LongMethod /
 * CyclomaticComplexMethod — les sauts et gardes de l'itération membre
 * par membre (bornes : profondeur, accolades, plages) sont le patron
 * même du parcours à bornes, miroir du scan des racines ;
 * TooManyFunctions — un extracteur par ISSUE de l'analyse (type,
 * corps, segment, méthode, champ, paramètres, annotations), patron
 * SymbolesProjet/ClassesSynthetiques.
 */
@Suppress(
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
    "ReturnCount",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
)
internal object SourcesJava {
    /** Motif d'une déclaration de type (modificateurs + mot-clé + nom). */
    private val MOTIF_TYPE =
        Regex(
            "(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*" +
                "(class|interface|enum|record|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )

    /** Modificateurs de membres Java (espacés en tête de segment). */
    private val MOTIF_MODIFICATEURS =
        Regex(
            "^(\\s*(?:public|protected|private|static|final|abstract|synchronized|native|strictfp" +
                "|default|transient|volatile)\\s+)*",
        )

    /** Motif du MEMBRE : TYPE écrit puis NOM, ancré au début du segment. */
    private val MOTIF_MEMBRE =
        Regex(
            "^([A-Za-z_][A-Za-z0-9_.]*(?:<[^;(){}=]*>)?(?:\\[\\])*)" +
                "\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*",
        )

    /** Mots-clés que la position TYPE ne peut jamais occuper. */
    private val MOTS_CLES_TYPE =
        setOf(
            "new", "return", "throw", "else", "case", "break", "continue", "do", "while",
            "for", "if", "switch", "try", "catch", "finally", "instanceof", "assert",
            "extends", "implements", "throws", "import", "package", "super", "this",
            "synchronized", "default", "static",
        )

    /** Préfixes de getter Java porteurs d'une propriété synthétique. */
    private val PREFIXES_GETTER = listOf("get", "is")

    /** Mots-clés de SUPERTYPE écrit (bornes d'identifiant). */
    private val MOTS_HERITAGE = listOf("extends", "implements")

    /** Borne de membres extraits par corps (fichier dégénéré — D3). */
    private const val BORN_MEMBRES = 500

    /** Portée d'extraction d'un fichier : URI + paquet du porteur. */
    private class Portee(
        internal val uri: String,
        internal val paquet: String?,
    )

    /** Un nom positionné : décalages réels du jeton identifiant. */
    private class Nomme(
        internal val nom: String,
        internal val debutNom: Int,
        internal val finNom: Int,
    )

    /**
     * Déclarations (types de premier niveau + leurs membres) d'un
     * fichier Java.
     *
     * @param fichier source `.java` du projet.
     * @return les déclarations, vides si le fichier ne se lit pas.
     */
    internal fun declarations(fichier: File): List<DeclarationProjet> {
        val brut =
            try {
                fichier.readText()
            } catch (t: Throwable) {
                // Illisible : absent (partiel honnête D3).
                return emptyList()
            }
        val portee = Portee(fichier.toURI().toString(), PAQUET.find(brut)?.groupValues?.get(1))
        val neutralise = neutraliser(brut)
        val resultat = mutableListOf<DeclarationProjet>()
        for (correspondance in MOTIF_TYPE.findAll(neutralise)) {
            // La neutralisation CONSERVE les longueurs : les décalages
            // du texte neutralisé s'appliquent tel quels à l'original.
            val plage = correspondance.groups[2]?.range ?: continue
            // PREMIER NIVEAU SEUL (D3) : une classe imbriquée ou locale
            // portée comme top-niveau donnerait un FQCN FAUX — jamais.
            if (profondeurA(neutralise, plage.first) != 0) continue
            val type = declaration(correspondance, plage, portee, neutralise)
            resultat += type
            resultat += membresDuType(neutralise, type, portee)
        }
        return resultat
    }

    /** Une déclaration de type extraite du texte neutralisé. */
    private fun declaration(
        correspondance: MatchResult,
        plage: IntRange,
        portee: Portee,
        neutralise: String,
    ): DeclarationProjet {
        val motCle = correspondance.groupValues[1]
        val estInterface = motCle == "interface" || motCle == "@interface"
        // Vague 3 (retour utilisateurs 25/10/2026) : preuve d'absence
        // fondée pour `class`/`interface` sans supertype ÉCRIT — enum
        // (Comparable implicite), record (java.lang.Record implicite)
        // et @interface (Annotation implicite) ne le portent JAMAIS ;
        // un en-tête SANS accolade (`sealed interface Foo;` implicite)
        // n'est pas prouvable non plus (D3).
        val ouverture = ouvertureCorps(neutralise, plage.last + 1)
        val sansHeritage =
            (motCle == "class" || motCle == "interface") &&
                ouverture != null &&
                !heritageEcrit(neutralise, plage.last + 1, ouverture)
        return DeclarationProjet(
            genre = if (estInterface) GenreDeclaration.INTERFACE else GenreDeclaration.CLASSE,
            nom = correspondance.groupValues[2],
            uri = portee.uri,
            debutNom = plage.first,
            finNom = plage.last + 1,
            paquet = portee.paquet,
            recepteur = null,
            typeRetour = null,
            parametres = emptyList(),
            conteneur = null,
            estJava = true,
            estSansHeritageEcrit = sansHeritage,
        )
    }

    /**
     * L'en-tête d'un type ([debut, fin[ — du nom à l'accolade du corps)
     * porte-t-il `extends`/`implements` de NIVEAU 0 ? Une CONTRAINTE de
     * paramètre générique (`class Boite<T extends Comparable<T>>`)
     * vit à profondeur `<>` STRICTEMENT POSITIVE : elle ne compte PAS
     * — seul un SUPERTYPE ÉCRIT ferme la preuve d'absence (vague 3).
     */
    private fun heritageEcrit(
        neutralise: String,
        debut: Int,
        fin: Int,
    ): Boolean {
        var profondeur = 0
        var indice = debut
        while (indice < fin) {
            when (neutralise[indice]) {
                '<' -> profondeur++
                '>' -> if (profondeur > 0) profondeur--
                'e', 'i' ->
                    if (profondeur == 0 && motCleIci(neutralise, indice, fin)) {
                        return true
                    }
            }
            indice++
        }
        return false
    }

    /** `extends`/`implements` aux BORNES d'identifiant à [indice] ? */
    private fun motCleIci(
        texte: String,
        indice: Int,
        fin: Int,
    ): Boolean {
        for (mot in MOTS_HERITAGE) {
            val finMot = indice + mot.length
            if (finMot > fin) continue
            if (!texte.regionMatches(indice, mot, 0, mot.length)) continue
            val apres = if (finMot < fin) texte[finMot] else ' '
            if (apres.isJavaIdentifierPart()) continue
            if (indice > 0 && texte[indice - 1].isJavaIdentifierPart()) continue
            return true
        }
        return false
    }

    /**
     * Membres de niveau 1 du corps du type [type] — champs et méthodes
     * (constructeurs exclus : sans type de retour écrit, ils ne
     * portent AUCUN membre résolvable ; leur arité reste non
     * contrôlée, D3). Un membre se termine au `;` de profondeur 0
     * (champ, méthode abstraite) ou à la FERMANTE qui ramène la
     * profondeur à 0 (corps de méthode, classe anonyme d'un
     * initialisateur) — le segment porte la signature ANCRÉE en tête.
     */
    private fun membresDuType(
        neutralise: String,
        type: DeclarationProjet,
        portee: Portee,
    ): List<DeclarationProjet> {
        val ouverture = ouvertureCorps(neutralise, type.finNom) ?: return emptyList()
        val fermeture = fermanteCorrespondante(neutralise, ouverture) ?: return emptyList()
        val fqcn = (type.paquet?.let { "$it." } ?: "") + type.nom
        val membres = mutableListOf<DeclarationProjet>()
        var debutSegment = ouverture + 1
        var profondeur = 0
        for (indice in ouverture + 1 until fermeture) {
            when (neutralise[indice]) {
                '{' -> profondeur++
                '}' -> profondeur--
                ';' -> {}
                else -> continue
            }
            // Fin de membre : séparateur de NIVEAU 1 uniquement (« ; »
            // hors bloc, ou fermante d'un bloc de niveau 1).
            if (profondeur != 0) continue
            membre(neutralise, debutSegment, indice + 1, fqcn, portee)?.let { membres += it }
            debutSegment = indice + 1
            if (membres.size >= BORN_MEMBRES) return membres
        }
        return membres
    }

    /**
     * Le membre d'un segment [debut, fin[ : signature ANCRÉE (le
     * `Math.max(…)` d'un initialisateur vit APRÈS le `=` du champ, la
     * regex ancrée ne l'atteint jamais) — champ (nom suivi de `=`, `,`
     * ou fin) ou méthode (nom suivi de `(`). Les annotations de tête
     * sont espacées avant le match (elles ne portent pas de membre).
     */
    private fun membre(
        neutralise: String,
        debut: Int,
        fin: Int,
        fqcn: String,
        portee: Portee,
    ): List<DeclarationProjet>? {
        if (fin <= debut) return null
        val segment = espacerAnnotations(neutralise, debut, fin)
        val sansTete = MOTIF_MODIFICATEURS.find(segment) ?: return null
        val corps = segment.substring(sansTete.value.length)
        val correspondance = MOTIF_MEMBRE.find(corps) ?: return null
        val typeEcrit = correspondance.groupValues[1]
        if (typeEcrit in MOTS_CLES_TYPE) return null
        val nom = correspondance.groupValues[2]
        if (nom.isEmpty() || '$' in nom) return null
        val debutNom = debut + sansTete.value.length + correspondance.groups[2]!!.range.first
        val nomme = Nomme(nom, debutNom, debutNom + nom.length)
        val apresNom = corps.substring(correspondance.value.length)
        return when {
            apresNom.startsWith("(") -> membresMethode(typeEcrit, nomme, apresNom, fqcn, portee)
            else -> membreChamp(typeEcrit, nomme, fqcn, portee)?.let { listOf(it) }
        }
    }

    /**
     * Une méthode Java : FONCTION du conteneur + la PROPRIÉTÉ
     * SYNTHÉTIQUE d'un getter 0-paramètre (`getTitre()` → `titre`,
     * miroir du classpath ADR 0219 — depuis Kotlin, un POJO Java
     * s'écrit en propriétés).
     */
    private fun membresMethode(
        typeEcrit: String,
        nomme: Nomme,
        apresNom: String,
        fqcn: String,
        portee: Portee,
    ): List<DeclarationProjet> {
        val nombreParametres = compterParametres(apresNom)
        val methode =
            DeclarationProjet(
                genre = GenreDeclaration.FONCTION,
                nom = nomme.nom,
                uri = portee.uri,
                debutNom = nomme.debutNom,
                finNom = nomme.finNom,
                paquet = portee.paquet,
                recepteur = null,
                typeRetour = typeEcrit,
                parametres = List(nombreParametres) { null to null },
                conteneur = fqcn,
                estJava = true,
            )
        val proprietes = mutableListOf(methode)
        if (nombreParametres == 0) {
            proprieteSynthetique(nomme.nom, typeEcrit)?.let { propriete ->
                membreChamp(typeEcrit, Nomme(propriete, nomme.debutNom, nomme.finNom), fqcn, portee)
                    ?.let { proprietes += it }
            }
        }
        return proprietes
    }

    /** Le nom de propriété d'un getter `getTitre`/`isActif`, sinon null. */
    private fun proprieteSynthetique(
        nomMethode: String,
        typeEcrit: String,
    ): String? {
        for (prefixe in PREFIXES_GETTER) {
            if (nomMethode.startsWith(prefixe) && nomMethode.length > prefixe.length) {
                val reste = nomMethode.substring(prefixe.length)
                if (reste.isEmpty() || !reste[0].isUpperCase()) continue
                if (typeEcrit == "void") return null
                return reste[0].lowercaseChar() + reste.substring(1)
            }
        }
        return null
    }

    /** Un champ Java : PROPRIÉTÉ du conteneur, type écrit porté. */
    private fun membreChamp(
        typeEcrit: String,
        nomme: Nomme,
        fqcn: String,
        portee: Portee,
    ): DeclarationProjet? =
        if (typeEcrit.isEmpty()) {
            null
        } else {
            DeclarationProjet(
                genre = GenreDeclaration.PROPRIETE,
                nom = nomme.nom,
                uri = portee.uri,
                debutNom = nomme.debutNom,
                finNom = nomme.finNom,
                paquet = portee.paquet,
                recepteur = null,
                typeRetour = typeEcrit,
                parametres = emptyList(),
                conteneur = fqcn,
                estJava = true,
            )
        }

    /**
     * Nombre de paramètres de la liste `(...)` en tête de [apresNom]
     * (virgules à profondeur d'imbriquement `<>`/`()`/`[]` nulle).
     */
    private fun compterParametres(apresNom: String): Int {
        val fermante = apresNom.indexOf(')')
        if (fermante < 0) return 0
        val liste = apresNom.substring(1, fermante).trim()
        if (liste.isEmpty()) return 0
        var profondeur = 0
        var nombre = 1
        for (caractere in liste) {
            when (caractere) {
                '<', '(', '[' -> profondeur++
                '>', ')', ']' -> if (profondeur > 0) profondeur--
                ',' -> if (profondeur == 0) nombre++
                else -> {}
            }
        }
        return nombre
    }

    /**
     * Espace les ANNOTATIONS `@Nom(…)` d'un segment (les arguments
     * contiennent parenthèses et virgules — jamais de `;` Java légal)
     * : longueurs conservées, le membre commence après.
     */
    private fun espacerAnnotations(
        neutralise: String,
        debut: Int,
        fin: Int,
    ): String {
        val caracteres = neutralise.substring(debut, fin).toCharArray()
        var indice = 0
        while (indice < caracteres.size) {
            indice =
                if (estTeteAnnotation(caracteres, indice)) {
                    espaceAnnotation(caracteres, indice)
                } else {
                    indice + 1
                }
        }
        return String(caracteres)
    }

    /** `@` suivi d'une lettre : tête d'annotation Java ? */
    private fun estTeteAnnotation(
        caracteres: CharArray,
        indice: Int,
    ): Boolean = caracteres[indice] == '@' && indice + 1 < caracteres.size && caracteres[indice + 1].isLetter()

    /** Espace UNE annotation (nom puis arguments appariés). */
    private fun espaceAnnotation(
        caracteres: CharArray,
        debut: Int,
    ): Int {
        var indice = debut
        caracteres[indice] = ' '
        indice++
        while (indice < caracteres.size && estCaractereNomJava(caracteres[indice])) {
            caracteres[indice] = ' '
            indice++
        }
        if (indice < caracteres.size && caracteres[indice] == '(') {
            return espaceParentheses(caracteres, indice)
        }
        return indice
    }

    /** Espace les arguments d'une annotation (parenthèses appariées). */
    private fun espaceParentheses(
        caracteres: CharArray,
        ouverture: Int,
    ): Int {
        var profondeur = 0
        var indice = ouverture
        while (indice < caracteres.size) {
            val caractere = caracteres[indice]
            caracteres[indice] = ' '
            when (caractere) {
                '(' -> profondeur++
                ')' -> {
                    profondeur--
                    if (profondeur == 0) return indice + 1
                }
                else -> {}
            }
            indice++
        }
        return caracteres.size
    }

    /** Caractère d'un nom Java qualifié (annotation, type écrit) ? */
    private fun estCaractereNomJava(caractere: Char): Boolean =
        caractere.isLetterOrDigit() || caractere == '_' || caractere == '.'

    /**
     * Ouvrante du corps du type (premier `{` après le nom, sans
     * traverser de `;` — un typealias/fragments multiples n'a pas de
     * membres lisibles).
     */
    private fun ouvertureCorps(
        neutralise: String,
        depuis: Int,
    ): Int? {
        var indice = depuis
        while (indice < neutralise.length) {
            when (neutralise[indice]) {
                '{' -> return indice
                ';' -> return null
                else -> {}
            }
            indice++
        }
        return null
    }

    /** Fermante appariée de l'accolade ouvrante [ouverture]. */
    private fun fermanteCorrespondante(
        neutralise: String,
        ouverture: Int,
    ): Int? {
        var profondeur = 0
        var indice = ouverture
        while (indice < neutralise.length) {
            when (neutralise[indice]) {
                '{' -> profondeur++
                '}' -> {
                    profondeur--
                    if (profondeur == 0) return indice
                }
                else -> {}
            }
            indice++
        }
        return null
    }

    /**
     * Neutralise commentaires et littéraux (remplacés par des espaces
     * — les positions du texte ORIGINAL sont conservées, motifs et
     * décalages restent alignés).
     */
    private fun neutraliser(brut: String): String {
        val caracteres = brut.toCharArray()
        var indice = 0
        while (indice < caracteres.size) {
            indice =
                when {
                    estCommentaireLigne(caracteres, indice) -> espaceJusquaFinDeLigne(caracteres, indice)
                    estCommentaireBloc(caracteres, indice) -> espaceCommentaireBloc(caracteres, indice)
                    caracteres[indice] == '"' || caracteres[indice] == '\'' -> espaceLitteral(caracteres, indice)
                    else -> indice + 1
                }
        }
        return String(caracteres)
    }

    /** `//` en tête de commentaire de ligne ? */
    private fun estCommentaireLigne(
        caracteres: CharArray,
        indice: Int,
    ): Boolean = caracteres[indice] == '/' && indice + 1 < caracteres.size && caracteres[indice + 1] == '/'

    /** Début de commentaire de BLOC à cet indice (étoile après le slash) ? */
    private fun estCommentaireBloc(
        caracteres: CharArray,
        indice: Int,
    ): Boolean = caracteres[indice] == '/' && indice + 1 < caracteres.size && caracteres[indice + 1] == '*'

    /** Espace un commentaire de ligne (jusqu'au saut de ligne). */
    private fun espaceJusquaFinDeLigne(
        caracteres: CharArray,
        debut: Int,
    ): Int {
        var indice = debut
        while (indice < caracteres.size && caracteres[indice] != '\n') {
            caracteres[indice] = ' '
            indice++
        }
        return indice
    }

    /** Espace un commentaire de bloc (fermeture comprise). */
    private fun espaceCommentaireBloc(
        caracteres: CharArray,
        debut: Int,
    ): Int {
        var indice = debut + 2
        caracteres[debut] = ' '
        caracteres[debut + 1] = ' '
        while (indice + 1 < caracteres.size &&
            !(caracteres[indice] == '*' && caracteres[indice + 1] == '/')
        ) {
            if (caracteres[indice] != '\n') caracteres[indice] = ' '
            indice++
        }
        if (indice + 1 < caracteres.size) {
            caracteres[indice] = ' '
            caracteres[indice + 1] = ' '
            return indice + 2
        }
        return caracteres.size
    }

    /** Espace un littéral de chaîne/caractère (échappements compris). */
    private fun espaceLitteral(
        caracteres: CharArray,
        debut: Int,
    ): Int {
        val fermeture = caracteres[debut]
        caracteres[debut] = ' '
        var indice = debut + 1
        while (indice < caracteres.size) {
            val courant = caracteres[indice]
            if (courant == '\\') {
                caracteres[indice] = ' '
                if (indice + 1 < caracteres.size) caracteres[indice + 1] = ' '
                indice += 2
                continue
            }
            if (courant == fermeture || courant == '\n') {
                if (courant == fermeture) caracteres[indice] = ' '
                return indice + 1
            }
            caracteres[indice] = ' '
            indice++
        }
        return caracteres.size
    }

    /**
     * Profondeur d'accolades ouverte à [position] du texte neutralisé
     * (les accolades des littéraux/commentaires sont déjà espacées).
     */
    private fun profondeurA(
        neutralise: String,
        position: Int,
    ): Int {
        var profondeur = 0
        for (indice in 0 until position.coerceAtMost(neutralise.length)) {
            when (neutralise[indice]) {
                '{' -> profondeur++
                '}' -> profondeur--
                else -> {}
            }
        }
        return profondeur
    }

    /** Directive `package x.y.z;`. */
    private val PAQUET = Regex("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;")
}
