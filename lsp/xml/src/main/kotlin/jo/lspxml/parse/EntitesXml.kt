package jo.lspxml.parse

/**
 * Entités XML — utilitaires PARTAGÉS parseur/règles (XE1) : validation
 * de FORME (`&nom;`, `&#123;`, `&#x1F;`) et décodage. Le registre exact
 * des noms appartient à aapt : la forme suffit à notre décision (X4
 * conservatoire — une forme inconnue reste TELLE QUELLE, jamais de
 * faux diagnostic).
 */

/** Base hexadécimale des entités numériques `&#x1F;`. */
private const val BASE_HEXA: Int = 16

/** Base décimale des entités numériques `&#123;`. */
private const val BASE_DECIMALE: Int = 10

/**
 * Longueur d'une entité LÉGALE démarrant à [debut] dans [texte] (l'&
 * et le `;` compris), `0` si la forme n'est pas légale — utilisée par
 * le parseur (esperluettes notées par section) et par les règles
 * (esperluettes dans les valeurs d'attribut) : UNE seule définition.
 */
internal fun longueurEntiteLegale(
    texte: CharSequence,
    debut: Int,
): Int {
    if (debut >= texte.length || texte[debut] != '&') return 0
    val finCorps = finCorpsEntite(texte, debut + 1)
    val legale = finCorps > debut + 1 && finCorps < texte.length && texte[finCorps] == ';'
    return if (legale) finCorps + 1 - debut else 0
}

/**
 * Fin du CORPS de l'entité démarrant à [debutCorps] (après l'&) —
 * gère le préfixe `#`/`#x` puis scanne les caractères admissibles.
 */
private fun finCorpsEntite(
    texte: CharSequence,
    debutCorps: Int,
): Int {
    var curseur = debutCorps
    var numerique = false
    if (curseur < texte.length && texte[curseur] == '#') {
        numerique = true
        curseur += 1
        if (curseur < texte.length && (texte[curseur] == 'x' || texte[curseur] == 'X')) curseur += 1
    }
    while (curseur < texte.length && texte[curseur].estCaractereEntite(numerique)) {
        curseur += 1
    }
    return curseur
}

/** Caractère de corps d'entité : chiffre (numérique) ou nom (nommée). */
private fun Char.estCaractereEntite(numerique: Boolean): Boolean =
    if (numerique) {
        isDigit() || this in 'a'..'f' || this in 'A'..'F'
    } else {
        isLetterOrDigit() || this == '.' || this == '-' || this == '_'
    }

/**
 * Décode les entités XML d'une valeur d'attribut (`&lt;` `&gt;` `&amp;`
 * `&apos;` `&quot;` + numériques) — les formes inconnues restent telles
 * quelles (conservatoire : aapt tranchera).
 */
internal fun decoderEntites(brute: String): String {
    if (!brute.contains('&')) return brute
    val sb = StringBuilder(brute.length)
    var curseur = 0
    while (curseur < brute.length) {
        val entite = decoderUneEntite(brute, curseur)
        if (entite == null) {
            sb.append(brute[curseur])
            curseur += 1
        } else {
            sb.append(entite.caractere)
            curseur = entite.fin
        }
    }
    return sb.toString()
}

/** Entité décodée (caractère + position APRÈS le `;`). */
private class EntiteDecodee(
    val caractere: Char,
    val fin: Int,
)

/** Décode l'entité démarrant à [debut] de [brute], `null` si illégale. */
private fun decoderUneEntite(
    brute: String,
    debut: Int,
): EntiteDecodee? {
    val longueur = longueurEntiteLegale(brute, debut)
    if (longueur == 0) return null
    val pointVirgule = debut + longueur - 1
    val caractere = decoderCorps(brute.substring(debut + 1, pointVirgule))
    return caractere?.let { EntiteDecodee(it, pointVirgule + 1) }
}

/** Corps d'entité (`lt`, `amp`, `#123`, `#x1F`) → caractère. */
private fun decoderCorps(corps: String): Char? =
    when (corps) {
        "lt" -> '<'
        "gt" -> '>'
        "amp" -> '&'
        "apos" -> '\''
        "quot" -> '"'
        else -> decoderNumerique(corps)
    }

/**
 * Entité numérique (`#123` ou `#x1F`) → caractère, `null` si invalide.
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — le décodage
 * numérique est BEST-EFFORT par contrat (une entité malformée reste
 * TELLE QU ELLE, l'exception est le signal, pas une perte).
 */
@Suppress("SwallowedException")
private fun decoderNumerique(corps: String): Char? {
    if (!corps.startsWith("#")) return null
    return try {
        if (corps.length > 2 && (corps[1] == 'x' || corps[1] == 'X')) {
            corps.substring(2).toInt(BASE_HEXA).toChar()
        } else {
            corps.substring(1).toInt(BASE_DECIMALE).toChar()
        }
    } catch (t: Throwable) {
        null
    }
}
