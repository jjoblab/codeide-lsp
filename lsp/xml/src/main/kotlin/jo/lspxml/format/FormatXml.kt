package jo.lspxml.format

import jo.lspxml.api.EditionXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * FORMATEUR XML borné (S3 — plan de stabilisation LSP §3.3 « formateur
 * XML (restant) ») : RÉ-INDENTATION canonique seule.
 *
 * ## Le choix borné
 * Le DOM ne modélise PAS les commentaires ni les PI (le parseur les
 * consomme sans les garder) : un formateur qui RÉÉCRIRAIT l'arbre les
 * détruirait. Le formateur ne touche donc JAMAIS le contenu d'une
 * ligne — seule la PLENTE de tête est remplacée, par la profondeur
 * logique calculée depuis le DOM :
 *
 *  - ligne qui OUVRE une balise (`<elem` en tête) : profondeur = nombre
 *    d'ancêtres de l'élément (la racine est au niveau 0) ;
 *  - ligne qui FERME (`</elem`) : profondeur de l'élément fermé ;
 *  - ligne qui CONTINUE une balise multi-ligne (attributs alignés) :
 *    UN niveau PLUS PROFOND que la balise continuée (convention
 *    Android Studio — les attributs vivent sous l'ouvrante) ;
 *  - ligne de TEXTE/commentaire/CDATA : profondeur de contenu — le
 *    nombre d'éléments FERMÉS englobant strictement la position (le
 *    texte d'une entrée `values` vit un niveau SOUS ses balises).
 *
 * Les lignes VIDES gardent leur état (jamais d'édition) ; les lignes
 * déjà justes ne produisent pas d'édition.
 *
 * ## Refus honnête (X4)
 * Document avec reprises du parseur OU balise non fermée : liste VIDE
 * — on ne reformate pas ce qu'on ne comprend pas, une profondeur
 * calculée sur un arbre incomplet serait une invention. Une ligne
 * mélangeant texte et balise (`<string>ok</string>`) reste entière :
 * son PREMIER jeton décide (la balise ouvrante), le reste de la ligne
 * voyage intact.
 */
internal object FormatXml {
    /**
     * Éditions de ré-indentation de [document] (texte [texte],
     * [tailleIndentation] espaces par niveau) — ordonnées, jamais
     * superposées ; liste VIDE si refus ou rien à changer.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — refus honnête
     * (arbre incomplet) et rien à changer sont des RÉGIMES distincts.
     */
    @Suppress("ReturnCount")
internal fun editer(
        document: DocumentXml,
        texte: CharSequence,
        tailleIndentation: Int,
    ): List<EditionXml> = editer(document, texte, tailleIndentation, 0, texte.length)

    /**
     * Éditions de ré-indentation BORNÉES à la PLAGE [debutPlage, finPlage)
     * (lot L3 — `textDocument/rangeFormatting`) : mêmes règles que le
     * document entier (refus sur arbre cassé, contenu jamais touché,
     * idempotence), seules les lignes qui INTERSECTENT la plage sont
     * candidates — toute édition reste DANS la plage (une édition hors
     * plage décalerait le buffer du client).
     */
    @Suppress("ReturnCount")
    internal fun editer(
        document: DocumentXml,
        texte: CharSequence,
        tailleIndentation: Int,
        debutPlage: Int,
        finPlage: Int,
    ): List<EditionXml> {
        // Refus honnête : arbre incomplet ou réparé — profondeur non
        // prouvable. Plage vide : rien à servir.
        if (document.recuperations.isNotEmpty() || debutPlage >= finPlage) return emptyList()
        val elements = elementsDe(document)
        if (elements.any { !it.estFermee }) return emptyList()

        val analyse = Analyse(elements)
        val editions = mutableListOf<EditionXml>()
        var debutLigne = 0
        val longueur = texte.length
        while (debutLigne < longueur) {
            val finLigne =
                texte.indexOf('\n', debutLigne).let { if (it < 0) longueur else it }
            // La ligne INTERSECTE la plage demandée ? (le client
            // sélectionne par lignes — une ligne à cheval sur une
            // borne est candidate.)
            if (debutLigne < finPlage && finLigne > debutPlage) {
                val cible = analyse.niveauDe(texte, debutLigne, finLigne)
                if (cible != null) {
                    ajouterEditionSiDecalee(texte, debutLigne, cible, tailleIndentation, editions)
                }
            }
            debutLigne = finLigne + 1
        }
        return editions
    }

    /** Édition du SEUL blanc de tête, si la ligne est décalée. */
    private fun ajouterEditionSiDecalee(
        texte: CharSequence,
        debutLigne: Int,
        niveau: Int,
        tailleIndentation: Int,
        editions: MutableList<EditionXml>,
    ) {
        var premier = debutLigne
        while (premier < texte.length && texte[premier].isWhitespace() && texte[premier] != '\n') {
            premier += 1
        }
        val cible = " ".repeat(niveau.coerceAtLeast(0) * tailleIndentation)
        // Déjà juste : aucune édition (une édition à l'identique serait
        // du bruit pour l'éditeur — un TextEdit sans effet).
        if (texte.substring(debutLigne, premier) == cible) return
        editions.add(EditionXml(debutLigne, premier, cible))
    }

    /**
     * L'ANALYSE de profondeur d'un document — les index d'ouvrantes/
     * fermantes sont construits UNE fois, chaque ligne est classée en
     * O(éléments) au pire (documents cibles ≤ 1 500 lignes : budget
     * largement tenu).
     */
    private class Analyse(
        elements: List<XmlElement>,
    ) {
        /** Ouvrante début → élément (classification (a)). */
        private val ouvranteParDebut = HashMap<Int, XmlElement>()

        /** Fermante début → élément (classification (b)). */
        private val fermanteParDebut = HashMap<Int, XmlElement>()

        /** Tous les éléments (classifications (c) et (d)). */
        private val elements: List<XmlElement> = elements

        init {
            for (element in elements) {
                ouvranteParDebut[element.plageOuvrante.debut] = element
                element.plageFermante?.let { fermanteParDebut[it.debut] = element }
            }
        }

        /**
         * Niveau de profondeur cible de la ligne [debutLigne,
         * [finLigne]) — `null` pour une ligne blanche (aucune édition)
         * ou hors arbre (texte avant/après racine : niveau 0 rendu).
         */
        fun niveauDe(
            texte: CharSequence,
            debutLigne: Int,
            finLigne: Int,
        ): Int? {
            var premier = debutLigne
            while (premier < finLigne && texte[premier].isWhitespace()) premier += 1
            if (premier >= finLigne) return null // ligne blanche : le vide ne bouge pas

            val continuant = elementContinuant(premier)
            return when {
                // (a) la ligne OUVRE une balise — profondeur de l'élément.
                ouvranteParDebut[premier] != null ->
                    ancetresDe(ouvranteParDebut[premier]!!)
                // (b) la ligne FERME — profondeur de l'élément fermé.
                fermanteParDebut[premier] != null ->
                    ancetresDe(fermanteParDebut[premier]!!)
                // (c) continuation d'une balise multi-ligne (attribut
                // aligné SOUS l'ouvrante — un niveau plus profond).
                continuant != null -> ancetresDe(continuant) + 1
                // (d) contenu (texte/commentaire/CDATA/PI) — nombre
                // d'éléments FERMÉS englobant strictement la position.
                else -> contenuProfondeur(premier)
            }
        }

        /**
         * Élément dont la balise OUVRANTE déborde sur la ligne (ouverte
         * avant, fermée après la position) — l'attribut aligné sous
         * l'ouvrante hérite de sa profondeur (+1).
         */
        private fun elementContinuant(position: Int): XmlElement? =
            elements.firstOrNull { element ->
                position > element.plageOuvrante.debut && position < element.plageOuvrante.fin
            }

        /**
         * Profondeur de CONTENU : nombre d'éléments fermés englobant la
         * position (ouvrante finie AVANT, fermante commençant à ou
         * après — le texte DANS un élément compte cet élément ; un
         * élément dont la fermante commence ICI est classé (b)).
         */
        private fun contenuProfondeur(position: Int): Int =
            elements.count { element ->
                !element.autoFermante && element.plageFermante != null &&
                    element.plageOuvrante.fin <= position &&
                    element.plageFermante!!.debut >= position
            }

        /** Nombre d'ancêtres d'un élément (la racine est au niveau 0). */
        private fun ancetresDe(element: XmlElement): Int {
            var niveau = 0
            var courant: XmlElement? = element.parent
            while (courant != null) {
                niveau += 1
                courant = courant.parent
            }
            return niveau
        }
    }

    /** Tous les éléments du document, en FLUX plat (miroir session). */
    private fun elementsDe(document: DocumentXml): List<XmlElement> =
        document.racines.flatMap { listOf(it) + it.descendants() }
}
