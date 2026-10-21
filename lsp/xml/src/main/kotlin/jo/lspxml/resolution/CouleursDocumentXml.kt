package jo.lspxml.resolution

import jo.lspxml.api.CouleurDocumentXml
import jo.lspxml.api.PresentationCouleurXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement
import jo.lspxml.resources.IndexRessources
import java.io.File

/**
 * COULEURS DE DOCUMENT (`textDocument/documentColor` et
 * `colorPresentation`, lot L3 du plan 1.3.0) : chaque littéral
 * `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` d'une valeur d'attribut et
 * chaque référence `@color/nom` RÉSOLUE par l'index (la valeur du
 * déclarant `values`, tampon ouvert puis disque) devient une couleur
 * LSP (RGBA 0..1) — l'éditeur peut afficher la pastille et l'ouvrir
 * en sélecteur.
 *
 * Honnêteté (X4/D3) : une référence `@color/…` non résolue (index
 * froid, ressource par FICHIER sans valeur, déclarant illisible) ne
 * rend RIEN ; une valeur sans forme de couleur ne rend RIEN. Le
 * format `?attr/…` n'est PAS une couleur (un attribut de thème).
 */
/**
 * Exemptions detekt ciblées (règle 16) : LoopWithTooManyJumpStatements
 * — les continue du balayage (valeur absente, plage absente, déclarant
 * illisible) sont des états DISTINCTS du contrat best-effort D3 ;
 * MagicNumber — les longueurs de formes hexadécimales (3, 4, 6, 8)
 * sont le CATALOGUE du format de couleur Android, pas des nombres
 * magiques.
 */
@Suppress("LoopWithTooManyJumpStatements", "MagicNumber")
internal object CouleursDocumentXml {
    /** Formes hexadécimales admissibles : #RGB, #ARGB, #RRGGBB, #AARRGGBB. */
    private val FORME_HEXA = Regex("[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")
    /**
     * Couleurs du document : littéraux hexadécimaux et références
     * `@color/…` résolues, ordonnées par position.
     */
    internal fun couleurs(
        document: DocumentXml,
        index: IndexRessources,
    ): List<CouleurDocumentXml> {
        val resultats = mutableListOf<CouleurDocumentXml>()
        for (element in elementsDe(document)) {
            for (attribut in element.attributs) {
                val valeur = attribut.valeur ?: continue
                val plage = attribut.plageValeur ?: continue
                couleurLitterale(valeur, plage)?.let { resultats.add(it) }
                resultats.addAll(couleursReference(valeur, plage, index))
            }
        }
        return resultats.sortedBy { couleur -> couleur.debut }
    }

    /**
     * Présentation d'une couleur (colorPresentation) : le libellé
     * canonique `#AARRGGBB` (alpha omis s'il est opaque) et l'édition
     * de remplacement de la plage d'origine par CE libellé.
     */
    internal fun presentation(
        couleur: CouleurDocumentXml,
    ): PresentationCouleurXml {
        val libelle = libelleDe(couleur)
        return PresentationCouleurXml(
            debut = couleur.debut,
            fin = couleur.fin,
            libelle = libelle,
        )
    }

    /** Libellé canonique `#RRGGBB` (opaque) ou `#AARRGGBB`. */
    private fun libelleDe(couleur: CouleurDocumentXml): String {
        fun octet(valeur: Double): String =
            Math.round(valeur * 255.0).toInt().toString(16).padStart(2, '0')
        val rvb = octet(couleur.rouge) + octet(couleur.vert) + octet(couleur.bleu)
        return if (couleur.alpha >= 1.0) {
            "#$rvb"
        } else {
            "#" + octet(couleur.alpha) + rvb
        }
    }

    /**
     * Littéral hexadécimal de la valeur ENTIÈRE — `#RGB`, `#ARGB`,
     * `#RRGGBB`, `#AARRGGBB` ; `null` pour toute autre forme (le
     * préfixe canal `@android:color/…` n'est pas un littéral).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (sans #, forme non reconnue, longueur inconnue) est une ISSUE
     * distincte du catalogue de formes.
     */
    @Suppress("ReturnCount")
    private fun couleurLitterale(
        valeur: String,
        plage: jo.lspxml.parse.Plage,
    ): CouleurDocumentXml? {
        if (!valeur.startsWith("#")) return null
        val hex = valeur.substring(1)
        if (!hex.matches(FORME_HEXA)) {
            return null
        }
        return when (hex.length) {
            3 -> CouleurDocumentXml(
                debut = plage.debut + 1,
                fin = plage.fin,
                rouge = canal(hex[0], hex[0]),
                vert = canal(hex[1], hex[1]),
                bleu = canal(hex[2], hex[2]),
                alpha = 1.0,
            )
            4 -> couleurDeCanalSimple(plage, hex)
            6 -> CouleurDocumentXml(
                debut = plage.debut + 1,
                fin = plage.fin,
                rouge = canal(hex[0], hex[1]),
                vert = canal(hex[2], hex[3]),
                bleu = canal(hex[4], hex[5]),
                alpha = 1.0,
            )
            8 -> CouleurDocumentXml(
                debut = plage.debut + 1,
                fin = plage.fin,
                rouge = canal(hex[2], hex[3]),
                vert = canal(hex[4], hex[5]),
                bleu = canal(hex[6], hex[7]),
                alpha = canal(hex[0], hex[1]),
            )
            else -> null
        }
    }

    /** `#ARGB` : quatre nibbles (canal simple). */
    private fun couleurDeCanalSimple(
        plage: jo.lspxml.parse.Plage,
        hex: String,
    ): CouleurDocumentXml =
        CouleurDocumentXml(
            debut = plage.debut + 1,
            fin = plage.fin,
            rouge = canal(hex[1], hex[1]),
            vert = canal(hex[2], hex[2]),
            bleu = canal(hex[3], hex[3]),
            alpha = canal(hex[0], hex[0]),
        )

    /** Canal 0..1 d'une paire de nibbles hexadécimaux. */
    private fun canal(
        haut: Char,
        bas: Char,
    ): Double = ((Character.digit(haut, 16) * 16 + Character.digit(bas, 16)) / 255.0)

    /**
     * Références `@color/nom` de la valeur — résolues par l'index
     * (valeur du déclarant `values`, tampon ouvert puis disque) ; le
     * déclarant porte un littéral (récursif borné : un pas — une
     * référence qui pointe une référence n'est pas suivie, D3).
     *
     * Exemptions detekt ciblées (règle 16) : ReturnCount — chaque
     * garde (sans @color/, nom vide, déclarant sans littéral) est une
     * ISSUE distincte du conservatisme D3 ; SwallowedException — la
     * lecture disque best-effort EST le contrat (illisible = référence
     * non résolue, jamais une couleur fausse).
     */
    @Suppress("ReturnCount", "SwallowedException")
    private fun couleursReference(
        valeur: String,
        plage: jo.lspxml.parse.Plage,
        index: IndexRessources,
    ): List<CouleurDocumentXml> {
        if (!valeur.startsWith("@color/")) return emptyList()
        val nom = valeur.removePrefix("@color/")
        if (nom.isEmpty()) return emptyList()
        val declarants = index.declarantsDe("color", nom)
        for (declarant in declarants) {
            if (declarant.plage == jo.lspxml.parse.Plage(0, 0)) continue
            val texte =
                try {
                    declarant.fichier.readText()
                } catch (t: Throwable) {
                    continue // illisible : référence non résolue (D3)
                }
            // La valeur du déclarant : l'élément <color name="nom">.
            val couleur = parseurCouleurDe(texte, nom) ?: continue
            val plageNom = valeur.length - nom.length + plage.debut
            return listOf(
                CouleurDocumentXml(
                    debut = plageNom,
                    fin = plageNom + nom.length,
                    rouge = couleur.substringBefore(',').toDouble() / 255.0,
                    vert = couleur.substringAfter(',').substringBefore(',').toDouble() / 255.0,
                    bleu = couleur.substringAfterLast(',').toDouble() / 255.0,
                    alpha = 1.0,
                ),
            )
        }
        return emptyList()
    }

    /**
     * Littéral hexadécimal du déclarant `<color name="[nom]">#…</color>`
     * — renvoie « r,g,b » en octets DÉCIMAUX ; `null` sans littéral.
     */
    private fun parseurCouleurDe(
        texte: String,
        nom: String,
    ): String? {
        val regex = Regex("<color\\s+name=\"$nom\"\\s*>\\s*(#[0-9a-fA-F]+)\\s*</color>")
        val littéral = regex.find(texte)?.groupValues?.get(1) ?: return null
        val hex = littéral.removePrefix("#")
        return when (hex.length) {
            6 ->
                "${Integer.parseInt(hex.substring(0, 2), 16)}," +
                    "${Integer.parseInt(hex.substring(2, 4), 16)}," +
                    "${Integer.parseInt(hex.substring(4, 6), 16)}"
            8 -> {
                // Alpha en tête : ignoré pour la pastille RVB (l'alpha du
                // déclarant n'est pas celui de la RÉFÉRENCE, D3).
                "${Integer.parseInt(hex.substring(2, 4), 16)}," +
                    "${Integer.parseInt(hex.substring(4, 6), 16)}," +
                    "${Integer.parseInt(hex.substring(6, 8), 16)}"
            }
            else -> null
        }
    }

    /** Tous les éléments du document, en FLUX plat (miroir session). */
    private fun elementsDe(document: DocumentXml): Sequence<XmlElement> =
        document.racines.asSequence().flatMap { sequenceOf(it) + it.descendants().asSequence() }
}
