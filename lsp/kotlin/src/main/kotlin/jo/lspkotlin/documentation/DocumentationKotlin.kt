package jo.lspkotlin.documentation

import com.intellij.platform.syntax.lexer.performLexing
import com.intellij.platform.syntax.parser.SyntaxTreeBuilderFactory
import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.JetonSyntaxique
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.ReconstructeurArbre
import jo.lspkotlin.parse.kt.KtDeclaration
import org.jetbrains.kotlin.kmp.lexer.KDocKnownTag
import org.jetbrains.kotlin.kmp.lexer.KDocLexer
import org.jetbrains.kotlin.kmp.lexer.KDocTokens
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KDocParseNodes
import org.jetbrains.kotlin.kmp.parser.KDocParser

/**
 * Extraction du KDoc d'une déclaration pour le survol riche (S2 — plan
 * de stabilisation LSP §3.2, « utiliser KDocParser déjà vendoré »).
 *
 * Chaîne d'extraction (miroir de [jo.lspkotlin.parse.ParseurKotlin],
 * même patron documenté par l'amont) :
 * 1. le commentaire DOC_COMMENT PORTEUR PAR LA DÉCLARATION ELLE-MÊME —
 *    la politique de liaison des commentaires du parseur vendoré
 *    (« right-bound », sondage d'arbre v0.134.9) attache le KDoc
 *    comme JETON ENFANT DIRECT de la déclaration qu'il documente
 *    (`CLASS → [KDoc] → MODIFIER_LIST → class …`), AVANT les
 *    modificateurs et annotations ;
 * 2. lexification du texte du commentaire par le [KDocLexer] vendored
 *    (il découpe START/END/TEXT/TAG_NAME et STRIPPE les astérisques
 *    d'attente) ;
 * 3. parse par le [KDocParser] vendored (sections KDOC_SECTION,
 *    tags KDOC_TAG — jusqu'ici INUTILISÉ du dépôt) ;
 * 4. reconstruction en arbre immuable ([ReconstructeurArbre]) puis
 *    rendu en TEXTE NEUTRE : sections libres en paragraphes, tags en
 *    lignes `@param nom — texte` / `@return texte`.
 *
 * Le rendu reste du TEXTE (pas du markdown) : la traduction hôte
 * décide de l'enveloppe (bloc de code Kotlin + documentation — même
 * patron que les serveurs tiers de référence), le moteur n'expose jamais LSP4J (D6).
 *
 * Tolérance (D3) : un commentaire vide, dégénéré ou incompris rend
 * `null` (survol signature seule) — JAMAIS d'exception, une doc
 * absente n'est pas une erreur.
 */
internal object DocumentationKotlin {
    /**
     * KDoc de la déclaration : le jeton DOC_COMMENT enfant direct de
     * la déclaration (politique de liaison du parseur vendoré — le
     * KDoc documente CE qu'il précède), RENDU en texte lisible.
     * `null` sans commentaire KDoc (un BLOCK_COMMENT n'est PAS un
     * KDoc — il n'est pas rétro-analysé).
     */
    internal fun kdocDe(declaration: KtDeclaration): String? =
        commentaireDeDeclaration(declaration)?.let { rendre(it.texte) }

    /**
     * Le DOC_COMMENT de la déclaration : jeton enfant DIRECT du nœud
     * (sondage d'arbre : le KDoc atterrit À L'INTÉRIEUR de la
     * déclaration qu'il documente, avant modificateurs/annotations).
     */
    private fun commentaireDeDeclaration(declaration: KtDeclaration): JetonSyntaxique? =
        declaration.noeud.enfants
            .filterIsInstance<JetonSyntaxique>()
            .firstOrNull { it.type == KtTokens.DOC_COMMENT }

    /**
     * Rendu du texte d'un KDoc (`/** … */`) : lex + parse vendorés puis
     * marche de rendu. `null` si rien de lisible (commentaire vide).
     */
    private fun rendre(texteKdoc: CharSequence): String? {
        val arbre = arbreKdoc(texteKdoc) ?: return null
        val lignes = mutableListOf<String>()
        arbre.racine.sousNoeuds
            .filter { it.type == KDocParseNodes.KDOC_SECTION }
            .forEach { section -> rendreSection(section, lignes) }
        return normaliser(lignes)
    }

    /**
     * Lex + parse du KDoc vendorés, reconstruction en arbre immuable.
     * Toute exception du chemin vendoré se dégrade en `null` (D3 :
     * une doc qui résiste au parse n'est pas une erreur de survol).
     */
    private fun arbreKdoc(texteKdoc: CharSequence): ArbreSyntaxiqueKotlin? =
        try {
            val jetons = performLexing(texteKdoc, KDocLexer(), null, null)
            val builder =
                SyntaxTreeBuilderFactory
                    .builder(texteKdoc, jetons, KDocParser.whitespaces, KDocParser.comments)
                    .withWhitespaceOrCommentBindingPolicy(KDocParser.whitespaceOrCommentBindingPolicy)
                    .build()
            KDocParser.parse(builder)
            ReconstructeurArbre.construire(builder)
        } catch (_: Throwable) {
            null
        }

    /** Rendu d'une section : texte libre en un paragraphe, tags en lignes. */
    private fun rendreSection(
        section: NoeudSyntaxique,
        lignes: MutableList<String>,
    ) {
        val libre = StringBuilder()
        section.enfants.forEach { enfant -> accumulerOuTaguer(enfant, libre, lignes) }
        texteLibre(libre)?.let { lignes.add(it) }
    }

    /**
     * Un enfant de section : soit un TAG rendu en ligne (le texte libre
     * accumulé est flushé avant), soit du contenu libre accumulé, soit
     * de la structure ignorée (astérisques d'attente, balises markdown,
     * sections imbriquées inattendues).
     */
    private fun accumulerOuTaguer(
        enfant: jo.lspkotlin.parse.ElementSyntaxique,
        libre: StringBuilder,
        lignes: MutableList<String>,
    ) {
        val tag =
            if (enfant is NoeudSyntaxique && enfant.type == KDocParseNodes.KDOC_TAG) {
                tagLigne(enfant)
            } else {
                null
            }
        if (tag != null) {
            texteLibre(libre)?.let { lignes.add(it) }
            lignes.add(tag)
            return
        }
        texteDeContenu(enfant)?.let { libre.append(it) }
    }

    /** Le texte du jeton de CONTENU, ou `null` (structure ignorée). */
    private fun texteDeContenu(enfant: jo.lspkotlin.parse.ElementSyntaxique): String? =
        when {
            enfant !is JetonSyntaxique -> null

            enfant.type == KtTokens.WHITE_SPACE ||
                enfant.type == KDocTokens.TEXT ||
                enfant.type == KDocTokens.CODE_BLOCK_TEXT -> enfant.texte.toString()

            else -> null
        }

    /**
     * Ligne d'un tag : `@param nom — le texte` (tag à référence), ou
     * `@return le texte` (tag sans référence). Sans contenu : le tag
     * seul (`@return`). Les blancs du contenu sont ramenés à des
     * espaces (une ligne de tag reste UNE ligne de survol).
     */
    private fun tagLigne(tag: NoeudSyntaxique): String? {
        val nomTag =
            tag.jetonsDescendants().firstOrNull { it.type == KDocTokens.TAG_NAME } ?: return null
        // Le jeton TAG_NAME PORTE le `@` (« @param ») : rendu sans, la
        // recherche vendored (findByTagName) le tolère tel quel.
        val brut = nomTag.texte.toString()
        val nom = brut.removePrefix("@")
        val contenu =
            tag.jetonsDescendants()
                .filter { it.type != KDocTokens.TAG_NAME && it.type != KDocTokens.LEADING_ASTERISK }
                .joinToString("") { it.texte.toString() }
                .replace(Regex("\\s+"), " ")
                .trim()
        val referenceRequise = KDocKnownTag.findByTagName(brut)?.isReferenceRequired ?: false
        return when {
            contenu.isEmpty() -> "@$nom"

            referenceRequise -> {
                val espace = contenu.indexOf(' ')
                if (espace <= 0) "@$nom $contenu" else "@$nom ${contenu.take(espace)} — ${contenu.drop(espace + 1)}"
            }

            else -> "@$nom $contenu"
        }
    }

    /**
     * Normalise le texte libre accumulé : les blancs de l'arbre
     * reproduisent la source (retours à la ligne compris) — chaque
     * ligne est ramenée aux espaces utiles, les lignes vides de bords
     * disparaissent. `null` si rien de significatif.
     */
    private fun texteLibre(accummule: StringBuilder): String? {
        if (accummule.isBlank()) return null
        val rendu = accummule.lines().joinToString("\n") { ligne -> ligne.trim() }
        accummule.clear()
        return rendu.ifBlank { null }
    }

    /** Nettoie la liste de lignes (trim, bords vides) ; `null` si vide. */
    private fun normaliser(lignes: List<String>): String? {
        val propres = lignes.map { it.trim() }
        val debut = propres.indexOfFirst { it.isNotEmpty() }
        val fin = propres.indexOfLast { it.isNotEmpty() }
        if (debut < 0 || debut > fin) return null
        return propres.subList(debut, fin + 1).joinToString("\n").ifBlank { null }
    }
}
