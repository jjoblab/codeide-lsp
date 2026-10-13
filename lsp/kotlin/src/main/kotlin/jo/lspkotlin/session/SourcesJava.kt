package jo.lspkotlin.session

import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.GenreDeclaration
import java.io.File

/**
 * Déclarations de TYPES des sources JAVA du projet (K5 multi-langue,
 * LSP_KOTLIN.md §5 — « bascule Java↔Kotlin dans un même projet ») :
 * le moteur Kotlin voit les classes/interfaces/énumérations Java pour
 * la RÉSOLUTION de noms, la complétion auto-import et le survol —
 * un projet mixte n'est plus coupé en deux langues.
 *
 * Extraction VOLONTAIREMENT minimale (D3) : types de PREMIER niveau
 * seuls (les imbriquées Java sous-rapportent — rares depuis Kotlin),
 * aucun membre (l'arité des constructeurs Java n'est pas modélisée :
 * `kt.argumentCount` ignore ces candidates, marqueur [estJava]) ;
 * analyse lexicale à bornes (commentaires, chaînes et caractères
 * neutralisés) — un fichier Java illisible produit RIEN.
 *
 * URI et décalages RÉELS : le go-to-definition d'une référence Kotlin
 * vers une classe Java ouvre le fichier `.java` à la position du nom
 * (même mécanique que les cibles Kotlin du scan).
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — fichier
 * illisible = ABSENT (partiel honnête D3), l'exception est le signal ;
 * LoopWithTooManyJumpStatements — les sauts de la boucle de types
 * (plage absente, profondeur non nulle) sont les bornes du premier
 * niveau, patron du scan des racines.
 */
@Suppress("SwallowedException", "LoopWithTooManyJumpStatements")
internal object SourcesJava {
    /** Motif d'une déclaration de type (modificateurs + mot-clé + nom). */
    private val MOTIF_TYPE =
        Regex(
            "(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*" +
                "(class|interface|enum|record|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )

    /**
     * Déclarations de types de premier niveau d'un fichier Java.
     *
     * @param fichier source `.java` du projet.
     * @return les types déclarés, vides si le fichier ne se lit pas.
     */
    internal fun declarations(fichier: File): List<DeclarationProjet> {
        val brut =
            try {
                fichier.readText()
            } catch (t: Throwable) {
                // Illisible : absent (partiel honnête D3).
                return emptyList()
            }
        val paquet = PAQUET.find(brut)?.groupValues?.get(1)
        val uri = fichier.toURI().toString()
        val neutralise = neutraliser(brut)
        val resultat = mutableListOf<DeclarationProjet>()
        for (correspondance in MOTIF_TYPE.findAll(neutralise)) {
            // La neutralisation CONSERVE les longueurs : les décalages
            // du texte neutralisé s'appliquent tel quels à l'original.
            val plage = correspondance.groups[2]?.range ?: continue
            // PREMIER NIVEAU SEUL (D3) : une classe imbriquée ou locale
            // portée comme top-niveau donnerait un FQCN FAUX — jamais.
            if (profondeurA(neutralise, plage.first) != 0) continue
            resultat += declaration(correspondance, plage, paquet, uri)
        }
        return resultat
    }

    /** Une déclaration extraite du texte neutralisé. */
    private fun declaration(
        correspondance: MatchResult,
        plage: IntRange,
        paquet: String?,
        uri: String,
    ): DeclarationProjet {
        val motCle = correspondance.groupValues[1]
        val estInterface = motCle == "interface" || motCle == "@interface"
        return DeclarationProjet(
            genre = if (estInterface) GenreDeclaration.INTERFACE else GenreDeclaration.CLASSE,
            nom = correspondance.groupValues[2],
            uri = uri,
            debutNom = plage.first,
            finNom = plage.last + 1,
            paquet = paquet,
            recepteur = null,
            typeRetour = null,
            parametres = emptyList(),
            conteneur = null,
            estJava = true,
        )
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
