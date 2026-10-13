package jo.lspxml.parse

/**
 * Parseur XML TOLÉRANT écrit main (XE1, décision X1 — leçon projet de référence
 * §2 du cadrage : un parseur strict, XmlPullParser compris, est
 * inutilisable pour l'ÉDITION ; il faut du tolérant).
 *
 * **Ne lève JAMAIS** sur un buffer à moitié tapé : les balises non
 * fermées se rétablissent par une PILE DE NOMS OUVERTS avec diagnostics
 * de récupération (cadrage §4.2) :
 * - une balise ouvrante jamais refermée à la fin du texte → diagnostic
 *   `xml.tagNonFermee` SUR SON NOM (sauf balise EN ÉDITION — le `>`
 *   n'est pas encore tapé, aucun marqueur à chaud) ;
 * - une balise fermante sans ouvrante correspondante → `xml.tagOrpheline`
 *   sur le nom de la fermante, la fermante est IGNORÉE (l'arbre vit) ;
 * - une fermante qui referme des éléments intermédiaires ouverts
 *   (`<a><b></a>` — b implicitement refermé par `</a>`) → UN diagnostic
 *   `xml.tagFermanteIncoherente` sur la fermante, les intermédiaires
 *   sont refermés IMPLICITEMENT (reprise par pile, jamais de rejet) ;
 * - un guillemet de valeur non refermé → `xml.guillemetNonFerme`, la
 *   valeur s'arrête au prochain `<` (ou à la fin : frappe en cours) et
 *   la balise devient EN ÉDITION (pas de second marqueur en cascade) ;
 * - les esperluettes non échappées du TEXTE sont notées par section
 *   (la règle `xml.entiteInvalide` les transforme en diagnostics).
 *
 * Ce que le parseur IGNORE volontairement (conservatoire, X4) : les
 * `<` isolés dans le texte (rejetés par aapt, hors périmètre initial),
 * les valeurs non quotées (tolérées silencieusement), le DOCTYPE
 * (consommé, jamais validé — les ressources n'en portent pas).
 *
 * Construction en DEUX phases : le parcours empile/dépile des nœuds EN
 * CONSTRUCTION (mutables, enfants par référence) ; [construire]
 * matérialise ensuite l'arbre immuable TOUJOURS DESCENDANT (le parent
 * existe avant ses enfants — la référence [XmlElement.parent] est
 * résolue d'office, jamais différée).
 *
 * Le parseur est SANS ÉTAT entre deux appels (le partage par le moteur
 * est sûr) ; l'analyse est un seul passage, mémoire proportionnelle à
 * l'arbre — budget < 10 ms pour 1 500 lignes (cadrage §6).
 */
public class ParseurXml {
    /**
     * Analyse [texte] en un DOM tolérant — ne lève jamais.
     */
    public fun analyser(texte: CharSequence): DocumentXml {
        val etat = Etat(texte)
        etat.executer()
        return etat.construire()
    }

    // ── Machine interne ─────────────────────────────────────────────

    /**
     * État de l'analyse : curseur + pile de noms ouverts + diagnostics
     * de récupération trouvés au passage (les règles de STRUCTURE vivent
     * dans rules/, ici : la SYNTAXE et la reprise).
     *
     * Exemption detekt ciblée (règle 16) : TooManyFunctions — machine
     * à états du parseur, une fonction PAR construction lexicale
     * (commence, consommer, fermer…) — la découper en fichiers casserait
     * la lecture UNITAIRE du parcours.
     */
    @Suppress("TooManyFunctions")
    private class Etat(
        private val texte: CharSequence,
    ) {
        private val longueur = texte.length

        /** Curseur de lecture. */
        private var position = 0

        /** Pile des éléments OUVERTS (le dernier = le plus profond). */
        private val ouverts = ArrayDeque<ElementEnConstruction>()

        /** Racines en construction (tous les éléments de premier niveau). */
        private val racinesConstruites = mutableListOf<ElementEnConstruction>()

        /** Sections de texte + esperluettes non échappées. */
        private val sections = mutableListOf<DocumentXml.SectionTexte>()

        /** Diagnostics de récupération produits au passage. */
        private val diagnostics = mutableListOf<DiagnosticRecuperation>()

        /**
         * Élément en construction — mutable pendant le parcours, la
         * liste [enfants] porte des NŒUDS de construction (la
         * matérialisation immuable est faite par [construire]).
         */
        private class ElementEnConstruction(
            val nom: String,
            val plageNom: Plage,
            val debutBalise: Int,
            val parent: ElementEnConstruction?,
        ) {
            val attributs = mutableListOf<XmlAttribut>()

            val enfants = mutableListOf<ElementEnConstruction>()

            var autoFermante = false

            var enEdition = false

            var plageFermante: Plage? = null

            var finBalise = -1
        }

        /** Diagnostic de récupération syntaxique (offsets + code). */
        internal data class DiagnosticRecuperation(
            val code: String,
            val message: String,
            val plage: Plage,
        )

        // ── Parcours ───────────────────────────────────────────────

        fun executer() {
            while (position < longueur) {
                if (texte[position] != '<') {
                    consommerTexte()
                } else {
                    when {
                        commence("<![CDATA[") -> consommerJusqua("]]>")

                        commence("<!--") -> consommerJusqua("-->")

                        commence("<?") -> consommerJusqua("?>")

                        commence("<!") -> consommerJusqua(">")

                        commence("</") -> consommerFermante()

                        else -> consommerOuvrante()
                    }
                }
            }
            fermerToutALaFin()
        }

        /**
         * Construit le DOM immuable depuis l'état — matérialisation
         * DESCENDANTE : le [XmlElement] du parent précède ceux de ses
         * enfants (sa liste est peupliée APRÈS sa création, jamais
         * fuit avant la fin de [construire]).
         */
        fun construire(): DocumentXml {
            fun materialiser(
                noeud: ElementEnConstruction,
                parent: XmlElement?,
            ): XmlElement =
                XmlElement(
                    nom = noeud.nom,
                    attributs = noeud.attributs.toList(),
                    enfants = mutableListOf(),
                    parent = parent,
                    autoFermante = noeud.autoFermante,
                    enEdition = noeud.enEdition,
                    plageNom = noeud.plageNom,
                    plageOuvrante = Plage(noeud.debutBalise, maxOf(noeud.finBalise, noeud.debutBalise)),
                    plageFermante = noeud.plageFermante,
                ).also { construit ->
                    val enfantsConstruits = construit.enfants as MutableList<XmlElement>
                    noeud.enfants.forEach { enfant ->
                        enfantsConstruits.add(materialiser(enfant, construit))
                    }
                }

            val racines = racinesConstruites.map { materialiser(it, null) }
            return DocumentXml(
                racine = racines.firstOrNull(),
                racines = racines,
                sectionsTexte = sections.toList(),
                recuperations = diagnostics.map { RecuperationParseur(it.code, it.message, it.plage) },
                longueur = longueur,
            )
        }

        /** [motif] commence-t-il à la position courante ? */
        private fun commence(motif: String): Boolean = texte.regionMatches(position, motif, 0, motif.length)

        /**
         * Consomme une section de TEXTE jusqu'au prochain `<` : les
         * esperluettes non échappées y sont notées (plages — la règle
         * `xml.entiteInvalide` les transforme).
         */
        private fun consommerTexte() {
            val debut = position
            val esperluettes = mutableListOf<Plage>()
            while (position < longueur && texte[position] != '<') {
                if (texte[position] == '&' && longueurEntiteLegale(texte, position) == 0) {
                    esperluettes.add(Plage(position, position + 1))
                    // Une entité valide saute d'un bloc (les `;` y sont).
                }
                position += 1
            }
            sections.add(DocumentXml.SectionTexte(Plage(debut, position), esperluettes))
        }

        /**
         * Consomme jusqu'au [motif] fermant (CDATA, commentaire, PI,
         * DOCTYPE best-effort) ; motif introuvable : jusqu'à la fin
         * (frappe en cours — tolérant, SANS diagnostic : le bloc est
         * en train d'être tapé).
         */
        private fun consommerJusqua(motif: String) {
            val fin = chercher(motif, depuis = position)
            position = if (fin < 0) longueur else fin + motif.length
        }

        /** Prochaine occurrence de [motif] depuis [depuis], -1 si absente. */
        private fun chercher(motif: String, depuis: Int): Int {
            var curseur = depuis
            while (curseur + motif.length <= longueur) {
                var pareil = true
                for (indice in motif.indices) {
                    if (texte[curseur + indice] != motif[indice]) {
                        pareil = false
                        break
                    }
                }
                if (pareil) return curseur
                curseur += 1
            }
            return -1
        }

        // ── Balise fermante ────────────────────────────────────────

        /**
         * Consomme `</nom …>` : reprise par PILE (cadrage §4.2) —
         * correspondance exacte au sommet, sinon RECHERCHE dans la pile
         * (les intermédiaires sont refermés implicitement avec UN
         * diagnostic d'incohérence), sinon orpheline ignorée.
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — chaque
         * sortie porte une DÉCISION de reprise DISTINCTE (nom
         * illisible, pile vide, orpheline, sommet exact, incohérence)
         * — un return unique noierait la machine de reprise.
         */
        @Suppress("ReturnCount")
        private fun consommerFermante() {
            val debut = position
            position += 2 // `</`
            val plageNom = lireNomDeBalise()
            if (plageNom == null) {
                // `</>` ou `</` isolé : consommé jusqu'au `>` (tolérant).
                consommerJusqua(">")
                return
            }
            val nom = texte.substring(plageNom.debut, plageNom.fin)
            // Jusqu'au `>` (les fermantes ne portent pas d'attributs —
            // du texte parasite est consommé silencieusement).
            val fin = chercher(">", depuis = position)
            val finBalise = if (fin < 0) longueur else fin + 1
            position = finBalise

            if (ouverts.isEmpty()) {
                diagnostics.add(
                    DiagnosticRecuperation(
                        code = CODE_TAG_ORPHELINE,
                        message = "balise fermante « /$nom » sans balise ouvrante",
                        plage = Plage(debut, finBalise),
                    ),
                )
                return
            }
            val sommet = ouverts.last()
            if (sommet.nom == nom) {
                sommet.plageFermante = Plage(debut, finBalise)
                fermer(sommet)
                return
            }
            // Recherche du nom dans la pile (reprise projet de référence) :
            val profondeur = ouverts.indexOfLast { it.nom == nom }
            if (profondeur < 0) {
                diagnostics.add(
                    DiagnosticRecuperation(
                        code = CODE_TAG_ORPHELINE,
                        message = "balise fermante « /$nom » sans balise ouvrante",
                        plage = Plage(debut, finBalise),
                    ),
                )
                return
            }
            val implicites = ouverts.size - 1 - profondeur
            diagnostics.add(
                DiagnosticRecuperation(
                    code = CODE_TAG_FERMANTE_INCOHERENTE,
                    message =
                        "« /$nom » referme $implicites élément(s) non fermé(s) " +
                            "« ${ouverts.last().nom} »",
                    plage = Plage(debut, finBalise),
                ),
            )
            // Refermeture implicite : du sommet jusqu'au trouvé (inclus).
            while (ouverts.size - 1 > profondeur) {
                fermer(ouverts.last())
            }
            ouverts.last().plageFermante = Plage(debut, finBalise)
            fermer(ouverts.last())
        }

        // ── Balise ouvrante ────────────────────────────────────────

        /**
         * Consomme `<nom attr="v" …>` ou `<nom … />` : l'élément entre
         * dans la pile ; les attributs se lisent jusqu'au `>` (ou à la
         * fin du texte : balise EN ÉDITION — frappe en cours, l'élément
         * reste pour la complétion, sans diagnostic).
         */
        private fun consommerOuvrante() {
            val debutBalise = position
            position += 1 // `<`
            val plageNom = lireNomDeBalise()
            if (plageNom == null) {
                // `<` isolé (texte parasite) : un caractère de texte —
                // conservatoire X4, aucun diagnostic (hors périmètre).
                position = debutBalise + 1
                return
            }
            val element =
                ElementEnConstruction(
                    nom = texte.substring(plageNom.debut, plageNom.fin),
                    plageNom = plageNom,
                    debutBalise = debutBalise,
                    parent = ouverts.lastOrNull(),
                )
            ouverts.addLast(element)
            lireAttributs(element)
        }

        /**
         * Lit les attributs de [element] jusqu'au `>`/`/>`/fin : noms,
         * `=`, valeurs quotées ou tolérées. Le [finBalise] de l'élément
         * est posé ici (`>` vu) ou laissé en ÉDITION (fin de texte).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — chaque
         * sortie est un ÉTAT de fin DISTINCT (fin de texte, `>` vu,
         * auto-fermante, `<` de reprise) — le when du protocole de
         * fin de balise.
         */
        @Suppress("ReturnCount")
        private fun lireAttributs(element: ElementEnConstruction) {
            while (true) {
                sauterBlancs()
                if (position >= longueur) {
                    element.enEdition = true
                    element.finBalise = longueur
                    return
                }
                val courant = texte[position]
                when {
                    courant == '>' -> {
                        position += 1
                        element.finBalise = position
                        return
                    }

                    courant == '<' -> {
                        // `<` dans une balise ouverte (guillemet non
                        // refermé, frappe en cours) : la balise devient
                        // EN ÉDITION, le `<` appartient à la suite du
                        // texte (le parseur y revient — pile vivante).
                        element.enEdition = true
                        element.finBalise = position
                        return
                    }

                    courant == '/' -> {
                        position += 1
                        if (position < longueur && texte[position] == '>') {
                            position += 1
                            element.autoFermante = true
                            element.finBalise = position
                            fermer(element)
                            return
                        }
                        // `/` parasite : consommé, la lecture continue.
                    }

                    courant == '?' -> {
                        // `<?xml …?>` résiduel en corps : consommé.
                        position += 1
                    }

                    else -> lireUnAttribut(element)
                }
            }
        }

        /**
         * Lit UN attribut (nom [= valeur]) et l'attache à [element] —
         * nom, puis [lireValeurAttribut] si un `=` suit.
         */
        private fun lireUnAttribut(element: ElementEnConstruction) {
            val debutNom = position
            while (position < longueur && !texte[position].estSeparateurAttribut()) {
                position += 1
            }
            if (position == debutNom) {
                // Caractère parasite dans la balise : consommé pour
                // progresser (jamais de boucle infinie).
                position += 1
                return
            }
            val plageNom = Plage(debutNom, position)
            val nom = texte.substring(debutNom, position)
            sauterBlancs()
            if (position >= longueur || texte[position] != '=') {
                // Attribut SANS valeur (outils `tools:`… ou fin de
                // frappe) : légal, toléré.
                element.attributs.add(XmlAttribut.deNomBrut(nom, null, null, plageNom, null))
                if (position >= longueur) {
                    element.enEdition = true
                    element.finBalise = longueur
                }
                return
            }
            position += 1
            sauterBlancs()
            lireValeurAttribut(element, nom, plageNom)
        }

        /**
         * Lit la VALEUR de l'attribut [nom] ([plageNom] son nom) :
         * quotée (`"…"`/`'…'`, reprise au `<` ou fin), ou NUE tolérée.
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount/Complexity —
         * les branches sont les ÉTATS de reprise du guillemet (fermé,
         * fin de texte = frappe en cours SANS marqueur, cassé par un
         * `<` = diagnostic) — le contrat de tolérance X1 lui-même.
         */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        private fun lireValeurAttribut(
            element: ElementEnConstruction,
            nom: String,
            plageNom: Plage,
        ) {
            if (position >= longueur) {
                // `=` en fin de frappe : la valeur va venir.
                element.attributs.add(XmlAttribut.deNomBrut(nom, null, null, plageNom, null))
                element.enEdition = true
                element.finBalise = longueur
                return
            }
            val quote = texte[position]
            if (quote != '"' && quote != '\'') {
                lireValeurNue(element, nom, plageNom)
                return
            }
            position += 1
            val debutValeur = position
            var fin = position
            while (fin < longueur && texte[fin] != quote) {
                if (texte[fin] == '<') break
                fin += 1
            }
            val brute = texte.substring(debutValeur, fin)
            element.attributs.add(
                XmlAttribut.deNomBrut(
                    nom = nom,
                    valeur = decoderEntites(brute),
                    valeurBrute = brute,
                    plageNom = plageNom,
                    plageValeur = Plage(debutValeur, fin),
                ),
            )
            when {
                fin < longueur && texte[fin] == quote -> position = fin + 1

                fin >= longueur -> {
                    // Guillemet courant jusqu'à la FIN du texte : frappe
                    // en cours — le guillemet va se refermer, AUCUN
                    // marqueur à chaud (X1) ; la balise passera EN
                    // ÉDITION par lireAttributs.
                    position = fin
                }

                else -> {
                    // Guillemet non refermé CASSÉ par un `<` : reprise
                    // au chevron — diagnostic de récupération, la
                    // balise passera EN ÉDITION (pas de cascade).
                    diagnostics.add(
                        DiagnosticRecuperation(
                            code = CODE_GUILLEMET_NON_FERME,
                            message = "guillemet non fermé de la valeur de « $nom »",
                            plage = Plage(debutValeur, fin),
                        ),
                    )
                    position = fin
                }
            }
        }

        /**
         * Valeur NON quotée : tolérée silencieusement (X4 — aapt la
         * rejette, notre périmètre initial la garde).
         */
        private fun lireValeurNue(
            element: ElementEnConstruction,
            nom: String,
            plageNom: Plage,
        ) {
            val debutValeur = position
            while (position < longueur && !texte[position].estSeparateurValeurNue()) {
                position += 1
            }
            val brute = texte.substring(debutValeur, position)
            element.attributs.add(
                XmlAttribut.deNomBrut(
                    nom = nom,
                    valeur = decoderEntites(brute),
                    valeurBrute = brute,
                    plageNom = plageNom,
                    plageValeur = Plage(debutValeur, position),
                ),
            )
        }

        /** Nom de balise : tout sauf séparateurs de structure/espaces. */
        private fun lireNomDeBalise(): Plage? {
            val debut = position
            while (position < longueur && !texte[position].estSeparateurNom()) {
                position += 1
            }
            if (position == debut) return null
            return Plage(debut, position)
        }

        private fun sauterBlancs() {
            while (position < longueur && texte[position].isWhitespace()) position += 1
        }

        /**
         * Fin de texte : refermeture de toute la pile (avec diagnostics
         * sauf éléments en édition — frappe en cours, X1).
         */
        private fun fermerToutALaFin() {
            while (ouverts.isNotEmpty()) {
                val element = ouverts.last()
                if (element.finBalise < 0) {
                    element.finBalise = longueur
                    element.enEdition = true
                }
                if (!element.enEdition) {
                    diagnostics.add(
                        DiagnosticRecuperation(
                            code = CODE_TAG_NON_FERME,
                            message = "balise « ${element.nom} » non fermée",
                            plage = element.plageNom,
                        ),
                    )
                }
                fermer(element)
            }
        }

        /**
         * Dépile [element] (sommet de la pile par construction) et
         * l'attache aux enfants de son parent — ou aux racines. La
         * matérialisation immuable est DEFÉRÉE à [construire].
         */
        private fun fermer(element: ElementEnConstruction) {
            ouverts.removeLast()
            val parent = element.parent
            if (parent != null) {
                parent.enfants.add(element)
            } else {
                racinesConstruites.add(element)
            }
        }

        private fun CharSequence.substring(debut: Int, fin: Int): String {
            val sb = StringBuilder(fin - debut)
            for (indice in debut until fin) sb.append(this[indice])
            return sb.toString()
        }

        /** Séparateur de nom de balise (espace, `/`, `>`, `?`). */
        private fun Char.estSeparateurNom(): Boolean =
            isWhitespace() || this == '/' || this == '>' || this == '?'

        /** Séparateur de nom d'attribut (espace, `=`, `/`, `>`, `<`). */
        private fun Char.estSeparateurAttribut(): Boolean =
            isWhitespace() || this == '=' || this == '/' || this == '>' || this == '<'

        /** Fin d'une valeur non quotée (espace, `>`, `/`, `<`). */
        private fun Char.estSeparateurValeurNue(): Boolean =
            isWhitespace() || this == '>' || this == '/' || this == '<'
    }

    private companion object {
        private const val CODE_TAG_NON_FERME: String = "xml.tagNonFermee"
        private const val CODE_TAG_ORPHELINE: String = "xml.tagOrpheline"
        private const val CODE_TAG_FERMANTE_INCOHERENTE: String = "xml.tagFermanteIncoherente"
        private const val CODE_GUILLEMET_NON_FERME: String = "xml.guillemetNonFerme"
    }
}
