package jo.lspxml.catalog

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache de catalogues de plateforme PARTAGÉ par les sessions d'un
 * moteur (XE2, LSP_XML.md §4.2 : « le catalogue est chargé
 * paresseusement, une fois par session, empreinte de version SDK » —
 * l'empreinte est le CHEMIN de la plateforme : le contenu d'une
 * plateforme installée ne change pas sous nos pieds).
 *
 * Tolérant (X4) : plateforme absente/inconnue → catalogue `null`
 * CACHÉ (pas de re-lecture à chaque frappe) ; le classpath pousse une
 * NOUVELLE plateforme = nouvelle clé = nouveau chargement.
 */
internal class CataloguesPartages {
    /** Entrée de cache (le catalogue PEUT être null — ConcurrentHashMap interdit les valeurs nulles). */
    private class Entree(val catalogue: CataloguePlateforme?)

    /** Catalogues chargés (clé : chemin ABSOLU de la plateforme). */
    private val charges = ConcurrentHashMap<String, Entree>()

    /**
     * Catalogue de la plateforme [dossier] — `null` si absent (jamais
     * une exception : la complétion sous-rapporte, le diagnostic reste
     * syntaxique).
     */
    internal fun catalogue(dossier: File?): CataloguePlateforme? {
        if (dossier == null) return null
        return charges
            .computeIfAbsent(dossier.absolutePath) { Entree(chargerSurement(dossier)) }
            .catalogue
    }

    /**
     * La plateforme est-elle DÉJÀ chargée (ADR 0213 — issue REPRIS du
     * préchauffage : un catalogue déjà en cache ne re-travaille pas) ?
     * `false` pour un dossier absent (`null` compris).
     */
    internal fun dejaCharge(dossier: File?): Boolean =
        dossier != null && charges.containsKey(dossier.absolutePath)

    /**
     * Chargement TOUJOURS défensif (un attrs.xml exotique = null).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — le
     * chargement est BEST-EFFORT par contrat (X4 : une plateforme
     * illisible = complétion absente, l'exception est le signal, pas
     * une perte).
     */
    @Suppress("SwallowedException")
    private fun chargerSurement(dossier: File): CataloguePlateforme? =
        try {
            CataloguePlateforme.charger(dossier)
        } catch (t: Throwable) {
            null
        }
}
