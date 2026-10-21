package jo.lspxml.context

import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.Plage
import jo.lspxml.parse.XmlElement

/**
 * Scanner de POSITION du curseur (XE2, LSP_XML.md §4.1 « context » —
 * motif projet tiers de référence : UN scanner décide où est le caret, les
 * contributeurs de complétion n'ont plus qu'à consommer) : nom de
 * BALISE (juste après `<`), nom d'ATTRIBUT (dans une balise ouverte),
 * VALEUR (entre guillemets) ou MUET (texte, fermante, hors balise —
 * jamais de proposition inventée, X4).
 *
 * Le scan est LEXICAL depuis le dernier `<` avant le curseur (le DOM
 * tolérant ne garantit pas un élément pour un nom à moitié tapé) : il
 * RE-parcourt la balise en respectant les guillemets — un `>` DANS une
 * valeur ne clôt pas la balise, un `<` DANS une valeur borne le scan
 * conservativement (Muet — mieux vaut sous-compléter que mal
 * compléter).
 */
public object ContexteCurseur {
    /**
     * Où est le curseur dans [texte] à [decalage] ?
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque sortie
     * est un ÉTAT du scanner (sans `<`, caractère de structure, zone
     * ouverte) — un return unique noierait la machine.
     *
     * @param texte source intégrale du document.
     * @param decalage position du curseur (décalage de caractères).
     * @param document DOM tolérant (parent/élément courants — le scan
     *        lexical donne la ZONE, le DOM donne les CONTEXTES).
     */
    @Suppress("ReturnCount")
    public fun analyser(
        texte: CharSequence,
        decalage: Int,
        document: DocumentXml,
    ): Contexte {
        val borne = decalage.coerceIn(0, texte.length)
        // Dernier `<` STRICTEMENT avant le curseur : `<|` (rien tapé)
        // relève du nom de balise, `a<|` aussi.
        var lt = -1
        for (indice in (borne - 1) downTo 0) {
            if (texte[indice] == '<') {
                lt = indice
                break
            }
        }
        if (lt < 0) return Contexte.Muet
        val apres = texte.getOrNull(lt + 1)
        if (apres == null) return Contexte.NomDeBalise("", conteneurDe(document, lt))
        if (apres == '/' || apres == '!' || apres == '?') {
            // Fermante, commentaire, PI : jamais de complétion.
            return Contexte.Muet
        }
        return scannerBalise(texte, borne, lt, document)
    }

    /**
     * Scan du CONTENU de la balise ouverte à [lt] (respect des quotes).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/Complexity — le
     * scan traverse les ÉTATS du protocole de balise (nom, blancs,
     * attributs, fermeture) — chaque branche est une position de
     * curseur DISTINCTE, pas un regroupement de convenance.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun scannerBalise(
        texte: CharSequence,
        decalage: Int,
        lt: Int,
        document: DocumentXml,
    ): Contexte {
        var curseur = lt + 1
        val longueur = texte.length

        // ── Nom de balise ─────────────────────────────────────────
        val debutNom = curseur
        while (curseur < longueur && !estSeparateurNom(texte[curseur])) curseur += 1
        val finNom = curseur
        if (decalage <= finNom) {
            val prefixe = texte.subSequence(debutNom, minOf(decalage, finNom)).toString()
            return Contexte.NomDeBalise(prefixe, conteneurDe(document, lt))
        }
        // Sauter les blancs (le curseur vient de passer le nom).
        while (curseur < longueur && texte[curseur].isWhitespace()) curseur += 1
        if (curseur >= longueur) {
            // `<Nom |` en fin de frappe : zone d'attribut ouverte.
            return Contexte.NomAttribut("", elementDe(document, lt))
        }

        // ── Attributs ─────────────────────────────────────────────
        while (curseur < longueur) {
            when (val courant = texte[curseur]) {
                '>' -> return Contexte.Muet // balise CLOSE après le curseur : zone texte

                '/' -> {
                    if (texte.getOrNull(curseur + 1) == '>') return Contexte.Muet
                    curseur += 1
                }

                else -> {
                    if (courant.isWhitespace()) {
                        curseur += 1
                        continue
                    }
                    val scan = scannerAttribut(texte, decalage, curseur, document, lt)
                    scan.contexte?.let { return it }
                    // La POSITION suivante voyage dans le résultat (un
                    // paramètre Kotlin est un val — la boucle avance).
                    curseur = scan.prochain
                }
            }
        }
        // Fin de texte sans `>` : zone d'attribut ouverte (frappe).
        return Contexte.NomAttribut("", elementDe(document, lt))
    }

    /** Résultat du scan d'un attribut : contexte éventuel + position SUIVANTE. */
    private class ResultatScan(
        val contexte: Contexte?,
        val prochain: Int,
    )

    /**
     * Scan d'UN attribut depuis [debut] — nom, `=` optionnel, valeur
     * quotée optionnelle ; rend le contexte si le curseur y tombe,
     * `null` sinon AVEC la position suivant l'attribut (la boucle
     * appelante avance — garantie de progrès : au moins un caractère).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/Complexity — les
     * branches sont les ÉTATS de reprise du guillemet (dans le nom,
     * après le `=`, dans la valeur, au-delà) — le même contrat que
     * lireValeurAttribut du parseur.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun scannerAttribut(
        texte: CharSequence,
        decalage: Int,
        debut: Int,
        document: DocumentXml,
        lt: Int,
    ): ResultatScan {
        val longueur = texte.length
        var curseur = debut
        while (curseur < longueur && !estSeparateurAttribut(texte[curseur])) curseur += 1
        val finNom = curseur
        // Nom de l'attribut (avec préfixe `android:` éventuel).
        if (decalage <= finNom) {
            val prefixe = texte.subSequence(debut, minOf(decalage, finNom)).toString()
            return ResultatScan(Contexte.NomAttribut(prefixe, elementDe(document, lt)), finNom)
        }
        // Chercher un `=` (en sautant les blancs) puis une valeur quotée.
        var sonde = curseur
        while (sonde < longueur && texte[sonde].isWhitespace()) sonde += 1
        if (sonde < longueur && texte[sonde] == '=') {
            sonde += 1
            while (sonde < longueur && texte[sonde].isWhitespace()) sonde += 1
            val quote = texte.getOrNull(sonde)
            if (quote == '"' || quote == '\'') {
                var fin = sonde + 1
                while (fin < longueur && texte[fin] != quote && texte[fin] != '<') fin += 1
                if (decalage > sonde && decalage <= fin) {
                    // Curseur DANS la valeur (après l'ouverture, jusqu'à
                    // la fermeture incluse — frappe en cours).
                    val prefixe = texte.subSequence(sonde + 1, minOf(decalage, fin)).toString()
                    return ResultatScan(
                        Contexte.Valeur(
                            prefixeTape = prefixe,
                            element = elementDe(document, lt),
                            attribut = texte.subSequence(debut, finNom).toString(),
                        ),
                        fin,
                    )
                }
                // Curseur au-delà : continuer APRÈS la valeur.
                val apresValeur = if (fin < longueur && texte[fin] == quote) fin + 1 else fin
                return ResultatScan(null, apresValeur)
            }
        }
        // Attribut sans valeur : avancer d'un cran minimum (jamais de
        // boucle infinie) puis jusqu'au prochain non-blanc.
        return ResultatScan(null, maxOf(finNom, debut + 1))
    }

    /**
     * Élément LE PLUS PROFOND dont la balise ouvrante COMMENCE à [lt]
     * (le tag en cours de frappe, nom encore incomplet — le parseur
     * l'a créé dès que le nom existe) ; `null` si aucun (nom vide).
     */
    private fun elementDe(
        document: DocumentXml,
        lt: Int,
    ): XmlElement? {
        var trouve: XmlElement? = null
        fun sonder(element: XmlElement) {
            if (element.plageOuvrante.debut == lt) trouve = element
            element.enfants.forEach { sonder(it) }
        }
        document.racines.forEach { sonder(it) }
        return trouve
    }

    /**
     * Conteneur LE PLUS PROFOND ouvert à [position] (le PARENT d'un
     * nom de balise en frappe) : ouvrante AVANT la position, fermante
     * (si elle existe) APRÈS — le document est le flux, la pile du
     * parseur est implicite dans les plages.
     */
    private fun conteneurDe(
        document: DocumentXml,
        position: Int,
    ): XmlElement? {
        var trouve: XmlElement? = null
        var profondeur = -1
        fun sonder(element: XmlElement, niveau: Int) {
            val ouvre = element.plageOuvrante.debut < position
            val referme =
                element.plageFermante == null ||
                    (element.plageFermante as Plage).debut > position
            if (ouvre && referme && niveau > profondeur) {
                trouve = element
                profondeur = niveau
            }
            element.enfants.forEach { sonder(it, niveau + 1) }
        }
        document.racines.forEach { sonder(it, 0) }
        return trouve
    }

    /** Séparateur de nom de balise (espace, `/`, `>`, `?`). */
    private fun estSeparateurNom(c: Char): Boolean =
        c.isWhitespace() || c == '/' || c == '>' || c == '?'

    /** Séparateur de nom d'attribut (espace, `=`, `/`, `>`, `<`, quotes). */
    private fun estSeparateurAttribut(c: Char): Boolean =
        c.isWhitespace() || c == '=' || c == '/' || c == '>' || c == '<' || c == '"' || c == '\''
}

/** Zone du curseur — le scanner tranche, les contributeurs consomment. */
public sealed interface Contexte {
    /** Aucune complétion (texte, fermante, hors balise). */
    public data object Muet : Contexte

    /**
     * Nom de BALISE en frappe (juste après `<`).
     *
     * @property prefixeTape ce qui est déjà tapé du nom (peut être vide).
     * @property parent conteneur ouvert à la position (le futur parent
     *           de l'élément — XE2 propose toutes les vues, le parent
     *           servira au filtrage ultérieur).
     */
    public data class NomDeBalise(
        public val prefixeTape: String,
        public val parent: XmlElement?,
    ) : Contexte

    /**
     * Nom d'ATTRIBUT en frappe (dans une balise ouverte, avant le `=`).
     *
     * @property prefixeTape préfixe tapé (avec son deux-points —
     *           `android:te` : les contributeurs DISTINGUENT les
     *           espaces de noms sur ce préfixe).
     * @property element élément porteur (tag en cours — `null` si le
     *           nom de balise n'existe pas encore).
     */
    public data class NomAttribut(
        public val prefixeTape: String,
        public val element: XmlElement?,
    ) : Contexte

    /**
     * VALEUR d'attribut en frappe (entre guillemets).
     *
     * @property prefixeTape valeur partielle tapée.
     * @property element élément porteur.
     * @property attribut nom COMPLET de l'attribut en cours de valeur
     *           (`android:text`) — le contributeur valeurs en déduit
     *           le format (XE3).
     */
    public data class Valeur(
        public val prefixeTape: String,
        public val element: XmlElement?,
        public val attribut: String,
    ) : Contexte
}
