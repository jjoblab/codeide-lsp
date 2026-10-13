package jo.lspxml.session

import jo.lspxml.api.CorrectifXml
import jo.lspxml.api.EditionXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * QUICK-FIXES du moteur XML (XE4, LSP_XML.md §5 — « insérer xmlns,
 * renommer "did you mean" ») : le moteur rend des ÉDITIONS neutres
 * (décalages + remplacements), l'hôte traduit en CodeAction LSP —
 * miroir de `importsCandidats` Kotlin (le moteur possède la
 * SÉMANTIQUE, l'hôte possède le document).
 */
internal object CorrectifsXml {
    /** URI de l'espace de noms Android (insertion xmlns). */
    private const val URI_ANDROID: String = "http://schemas.android.com/apk/res/android"

    /** Nombre maximum de « did you mean » par balise. */
    private const val PLAFOND_DID_YOU_MEAN: Int = 4

    /**
     * Correctif du diagnostic `xml.manqueXmlnsAndroid` : insérer
     * `xmlns:android="…"` juste APRÈS le nom de la racine (là où
     * Android Studio l'insère).
     */
    internal fun insererXmlnsAndroid(document: DocumentXml): List<CorrectifXml> {
        val racine = document.racine ?: return emptyList()
        val insertion = racine.plageNom.fin
        return listOf(
            CorrectifXml(
                titre = "Insérer xmlns:android sur « ${racine.nom} »",
                editions =
                    listOf(
                        EditionXml(
                            debut = insertion,
                            fin = insertion,
                            texte = " xmlns:android=\"$URI_ANDROID\"",
                        ),
                    ),
            ),
        )
    }

    /**
     * Correctifs du diagnostic `xml.tagInconnu` : « did you mean » —
     * les balises du catalogue les PLUS PROCHES (préfixe commun le
     * plus long, puis distance d'édition bornée) ; chaque candidat
     * REMPLACE le nom (ouvrante ET fermante si présente).
     */
    internal fun renommerBalise(
        element: XmlElement,
        catalogue: CataloguePlateforme?,
    ): List<CorrectifXml> {
        if (catalogue == null) return emptyList()
        val candidats = candidatsProches(element.nom, catalogue)
        return candidats.map { candidat ->
            val editions =
                mutableListOf(
                    EditionXml(
                        debut = element.plageNom.debut,
                        fin = element.plageNom.fin,
                        texte = candidat,
                    ),
                )
            element.plageFermante?.let { fermante ->
                // Le nom dans la fermante : même longueur, décalée de
                // `</` depuis le début de fermante.
                editions.add(
                    EditionXml(
                        debut = fermante.debut + 2,
                        fin = fermante.debut + 2 + element.nom.length,
                        texte = candidat,
                    ),
                )
            }
            CorrectifXml(
                titre = "Remplacer « ${element.nom} » par « $candidat »",
                editions = editions,
            )
        }
    }

    /**
     * Candidats les plus proches : PREFIXE commun d'abord (TextV →
     * TextView), puis distance de Levenshtein bornée sur les noms du
     * catalogue.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les branches
     * sont les DEUX stratégies de rapprochement (préfixe exact, puis
     * distance) — chacune court-circuite l'autre.
     */
    @Suppress("ReturnCount")
    private fun candidatsProches(
        nom: String,
        catalogue: CataloguePlateforme,
    ): List<String> {
        if (nom.length < 2) return emptyList()
        val parPrefixe =
            catalogue.balises.keys
                .filter { it != nom && it.startsWith(nom) }
                .sorted()
                .take(PLAFOND_DID_YOU_MEAN)
        if (parPrefixe.isNotEmpty()) return parPrefixe
        // Distance bornée : au plus la moitié du nom tapé.
        val borne = nom.length / 2
        return catalogue.balises.keys
            .filter { it != nom && it.length >= 2 }
            .map { it to distance(nom, it, borne) }
            .filter { (_, distance) -> distance <= borne }
            .sortedBy { (nomCandidat, distance) -> distance * DISTANCE_POIDS + nomCandidat.length }
            .map { (nomCandidat, _) -> nomCandidat }
            .take(PLAFOND_DID_YOU_MEAN)
    }

    /**
     * Distance de Levenshtein BORNÉE ([borne] max — coupe les
     * candidats absurdes tôt), algorithme à une ligne précédente.
     */
    private fun distance(
        a: String,
        b: String,
        borne: Int,
    ): Int {
        if (kotlin.math.abs(a.length - b.length) > borne) return borne + 1
        val precedente = IntArray(b.length + 1) { it }
        val courante = IntArray(b.length + 1)
        for (indiceA in 1..a.length) {
            courante[0] = indiceA
            for (indiceB in 1..b.length) {
                val substitution =
                    precedente[indiceB - 1] + if (a[indiceA - 1] == b[indiceB - 1]) 0 else 1
                courante[indiceB] =
                    minOf(
                        precedente[indiceB] + 1,
                        courante[indiceB - 1] + 1,
                        substitution,
                    )
            }
            System.arraycopy(courante, 0, precedente, 0, courante.size)
        }
        return precedente[b.length]
    }

    /** Poids de tri : la distance domine, la longueur départage. */
    private const val DISTANCE_POIDS: Int = 10
}
