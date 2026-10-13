package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.catalog.CataloguePlateforme
import jo.lspxml.context.Contexte
import jo.lspxml.resources.IndexRessources

/**
 * CONTRIBUTEURS de complétion de VALEURS (XE3, LSP_XML.md §4.3) —
 * séparés de [CompletionXml] (balises/attributs) : références
 * `@type/…` de l'INDEX PROJET, framework `@android:…` (public-final),
 * attributs de style `?attr/…` et énumérations/fanions du catalogue.
 * Conservateur (X4) : ce que l'index et le catalogue ne prouvent pas
 * n'apparaît pas.
 */
internal object CompletionValeursXml {
    /** Limite de propositions par contributeur (palette bornée). */
    private const val PLAFOND_PROPOSITIONS: Int = 60

    /**
     * Complétion de la VALEUR d'un attribut (XE3, §4.3) — selon le
     * préfixe tapé : références `@type/…` de l'INDEX PROJET (types
     * présents, puis noms du type), framework `@android:…` si le
     * catalogue le prouve, et énumérations/fanions du catalogue quand
     * l'attribut est connu (X4 : ce que l'index et le catalogue ne
     * prouvent pas n'apparaît pas).
     *
     * La fonction est INTERNE : l'index projet est une machinerie du
     * moteur, la SESSION l'arbore derrière le contrat neutre.
     *
     * @param contexte zone (préfixe tapé, élément, attribut en cours).
     * @param catalogue catalogue plateforme (énumérations, framework).
     * @param index index des ressources du projet.
     */
    internal fun valeurs(
        contexte: Contexte.Valeur,
        catalogue: CataloguePlateforme?,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        if (prefixe.startsWith("@")) {
            propositions.addAll(referencesProjet(prefixe, index))
            propositions.addAll(referencesFramework(prefixe, catalogue))
        }
        if (prefixe.startsWith("?")) {
            propositions.addAll(attributsDeStyle(prefixe, index))
        }
        // Énumérations/fanions de l'ATTRIBUT (catalogue).
        propositions.addAll(valeursDEnumeration(contexte, catalogue))
        return propositions.sortedBy { it.texteTri }.take(PLAFOND_PROPOSITIONS)
    }

    /** Références `@type/…` de l'INDEX PROJET (types présents seuls). */
    private fun referencesProjet(
        prefixe: String,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        if (!index.estChaud) return emptyList()
        val apresArobase = prefixe.substring(1)
        val separant = apresArobase.indexOf('/')
        return if (separant < 0) {
            propositionsDeTypes(apresArobase, index)
        } else {
            propositionsDeNoms(apresArobase, separant, index)
        }
    }

    /** `@str` : les TYPES du projet qui matchent. */
    private fun propositionsDeTypes(
        apresArobase: String,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (type in index.types().sorted()) {
            if (apresArobase.isEmpty() || type.startsWith(apresArobase)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "@$type/",
                        genre = GenreCompletionXml.REFERENCE,
                        detail = "ressources $type — projet",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE, "@$type/"),
                    ),
                )
            }
        }
        return propositions
    }

    /** `@string/he` : noms du TYPE filtrés. */
    private fun propositionsDeNoms(
        apresArobase: String,
        separant: Int,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        val type = apresArobase.substring(0, separant)
        val debutNom = apresArobase.substring(separant + 1)
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in index.nomsParType(type).take(PLAFOND_PROPOSITIONS)) {
            if (debutNom.isEmpty() || nom.startsWith(debutNom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "@$type/$nom",
                        genre = GenreCompletionXml.REFERENCE,
                        detail = "projet",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE, "@$type/$nom"),
                    ),
                )
            }
        }
        return propositions
    }

    /** Références framework `@android:type/…` (public-final.xml). */
    private fun referencesFramework(
        prefixe: String,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        val apresPrefixe = formeFramework(prefixe, catalogue) ?: return emptyList()
        val separant = apresPrefixe.indexOf('/')
        return if (separant < 0) {
            typesFramework(apresPrefixe, catalogue)
        } else {
            nomsFramework(apresPrefixe, separant, catalogue)
        }
    }

    /** Reste du préfixe s'il est une forme framework VALIDE. */
    private fun formeFramework(
        prefixe: String,
        catalogue: CataloguePlateforme?,
    ): String? {
        val valide =
            !catalogue?.ressourcesFramework.isNullOrEmpty() &&
                (prefixe.startsWith("@android:") || prefixe.startsWith("@*android:"))
        return if (valide) prefixe.substringAfter("android:") else null
    }

    /** `@android:str` : les TYPES du framework qui matchent. */
    private fun typesFramework(
        apresPrefixe: String,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (type in catalogue!!.ressourcesFramework.keys.sorted()) {
            if (apresPrefixe.isEmpty() || type.startsWith(apresPrefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "@android:$type/",
                        genre = GenreCompletionXml.REFERENCE,
                        detail = "ressources $type — framework",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE_FW, "@android:$type/"),
                    ),
                )
            }
        }
        return propositions
    }

    /** `@android:string/he` : noms du type filtrés. */
    private fun nomsFramework(
        apresPrefixe: String,
        separant: Int,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        val type = apresPrefixe.substring(0, separant)
        val debutNom = apresPrefixe.substring(separant + 1)
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in (catalogue!!.ressourcesFramework[type] ?: emptySet()).sorted()) {
            if (debutNom.isEmpty() || nom.startsWith(debutNom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "@android:$type/$nom",
                        genre = GenreCompletionXml.REFERENCE,
                        detail = "framework",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE_FW, "@android:$type/$nom"),
                    ),
                )
            }
        }
        return propositions
    }

    /** Attributs de style `?attr/…` (index projet). */
    private fun attributsDeStyle(
        prefixe: String,
        index: IndexRessources,
    ): List<PropositionCompletionXml> {
        val apresInterrogation = prefixe.substring(1).removePrefix("attr/")
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in index.nomsParType("attr")) {
            if (apresInterrogation.isEmpty() || nom.startsWith(apresInterrogation)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = "?attr/$nom",
                        genre = GenreCompletionXml.REFERENCE,
                        detail = "attribut — projet",
                        texteTri = ClassementXml.cleTri(ClassementXml.RANG_REFERENCE, "?attr/$nom"),
                    ),
                )
            }
        }
        return propositions
    }

    /** Énumérations/fanions de l'attribut en cours (catalogue). */
    private fun valeursDEnumeration(
        contexte: Contexte.Valeur,
        catalogue: CataloguePlateforme?,
    ): List<PropositionCompletionXml> {
        val attr = catalogue?.attributs?.get(contexte.attribut.substringAfter(':'))
            ?: return emptyList()
        val prefixe = contexte.prefixeTape
        val enum =
            attr.enumerations.map { nom ->
                PropositionCompletionXml(
                    libelle = nom,
                    genre = GenreCompletionXml.VALEUR_ENUM,
                    detail = "enum",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                )
            }
        val fanions =
            attr.fanions.map { nom ->
                PropositionCompletionXml(
                    libelle = nom,
                    genre = GenreCompletionXml.VALEUR_FANION,
                    detail = "flag (combinables par |)",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                )
            }
        return (enum + fanions).filter { prefixe.isEmpty() || it.libelle.startsWith(prefixe) }
    }

}
