package jo.lspkotlin.resolve

import jo.lspkotlin.api.GenreSymboleKotlin
import jo.lspkotlin.api.SymboleEspaceKotlin

/**
 * Symboles de l'ESPACE de travail (`workspace/symbol`, L2 — plan
 * 1.3.0) : les déclarations NOMMÉES du projet qui répondent à une
 * requête par nom — la palette « aller au symbole » de l'éditeur en
 * est le consommateur.
 *
 * Sources (fournies par la session, miroir du balayage des
 * occurrences) : documents OUVERTS (arbres vivants) + racines
 * sources du scan (fichiers fermés). Les locales et paramètres ne
 * remontent JAMAIS (des centaines par fichier : du bruit pour une
 * palette) ; le résultat est plafonné et ordonné par nom.
 */
internal object SymbolesEspaceKotlin {
    /** Plafond de résultats (une palette infinie = un bruit). */
    private const val PLAFOND_SYMBOLES = 200

    /**
     * Symboles répondant à [requete] (CONTIENT, insensible à la
     * casse ; requête vide = tout, plafonné) — ordonnés par nom.
     */
    internal fun rechercher(
        requete: String,
        declarations: List<DeclarationProjet>,
    ): List<SymboleEspaceKotlin> {
        val aiguille = requete.trim().lowercase()
        return declarations
            .asSequence()
            .filter { declaration ->
                declaration.genre != GenreDeclaration.PARAMETRE &&
                    declaration.genre != GenreDeclaration.LOCALE
            }
            .filter { declaration -> declaration.uri.isNotBlank() }
            .mapNotNull { declaration ->
                val genre = genreDe(declaration.genre) ?: return@mapNotNull null
                if (aiguille.isNotEmpty() && !declaration.nom.lowercase().contains(aiguille)) {
                    return@mapNotNull null
                }
                SymboleEspaceKotlin(
                    nom = declaration.nom,
                    genre = genre,
                    uri = declaration.uri,
                    debutNom = declaration.debutNom,
                    finNom = declaration.finNom,
                    conteneur = declaration.conteneur,
                )
            }
            .sortedBy { symbole -> symbole.nom }
            .take(PLAFOND_SYMBOLES)
            .toList()
    }

    /** Genre de palette d'un genre de déclaration (`null` = sans rendu). */
    private fun genreDe(genre: GenreDeclaration): GenreSymboleKotlin? =
        when (genre) {
            GenreDeclaration.CLASSE -> GenreSymboleKotlin.CLASSE
            GenreDeclaration.INTERFACE -> GenreSymboleKotlin.INTERFACE
            GenreDeclaration.OBJET -> GenreSymboleKotlin.OBJET
            GenreDeclaration.FONCTION -> GenreSymboleKotlin.FONCTION
            GenreDeclaration.PROPRIETE -> GenreSymboleKotlin.PROPRIETE
            GenreDeclaration.ENTREE_ENUM -> GenreSymboleKotlin.ENTREE_ENUM
            GenreDeclaration.ALIAS -> GenreSymboleKotlin.ALIAS
            GenreDeclaration.PARAMETRE, GenreDeclaration.LOCALE -> null
        }
}
