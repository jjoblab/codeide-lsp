package jo.lspxml.session

import jo.lspxml.api.MoteurLangageXml
import jo.lspxml.api.SessionLangageXml
import jo.lspxml.parse.ParseurXml
import java.util.Properties

/**
 * Implémentation de référence du SPI [MoteurLangageXml] (XE1, décision
 * X1 : moteur MAISON in-process — parseur tolérant écrit main, DOM
 * neutre, règles pures).
 *
 * NE PAS référencer cette classe hors du module — elle n'est publique
 * que pour ServiceLoader (descripteur META-INF/services) ; tout
 * consommateur passe par
 * [jo.lspxml.api.ServiceMoteurLangageXml.unique] (le pack « langue »
 * futur se branche là, miroir de `MoteurKotlinVendore`).
 *
 * @param parseur parseur tolérant — injecté pour les tests, le moteur
 *        de référence possède le sien (sans état, partagé par toutes
 *        les sessions).
 */
public class MoteurXmlMaison(
    private val parseur: ParseurXml = ParseurXml(),
) : MoteurLangageXml {
    /**
     * Cache de catalogues de plateforme (XE2) — PARTAGÉ par toutes les
     * sessions du moteur (un projet = un moteur côté façade hôte) :
     * une plateforme n'est chargée QU'UNE fois (§4.2), les connexions
     * successives ne re-lectent pas.
     */
    private val catalogues = jo.lspxml.catalog.CataloguesPartages()

    private val descripteur: Map<String, String> by lazy { lireDescripteurPack() }

    override val identifiant: String
        get() = descripteur["id"] ?: ID_DEFAUT

    override val version: String
        get() = descripteur["version"] ?: VERSION_DEFAUT

    override val versionMinimaleApp: String
        get() = descripteur["versionMinimaleApp"] ?: VERSION_DEFAUT

    override fun analyserSource(texte: CharSequence): jo.lspxml.parse.DocumentXml = parseur.analyser(texte)

    override fun creerSession(): SessionLangageXml = SessionLangageXmlImpl(parseur, catalogues)

    private fun lireDescripteurPack(): Map<String, String> {
        val chemin = "jo/lspxml/pack/version.properties"
        val flux =
            MoteurXmlMaison::class.java.classLoader.getResourceAsStream(chemin)
                ?: return emptyMap()
        val proprietes = Properties()
        flux.use { proprietes.load(it) }
        return proprietes.entries.associate { (it.key as? String ?: "") to (it.value as? String ?: "") }
    }

    private companion object {
        // Valeurs de repli si le descripteur manque — traçables en test
        // (ChargeurMoteurXmlTest échoue s'il y recourt : le descripteur
        // EST partie du contrat de pack, son absence est un bug).
        private const val ID_DEFAUT: String = "jo.codeide.lsp.xml"

        private const val VERSION_DEFAUT: String = "inconnue"
    }
}
