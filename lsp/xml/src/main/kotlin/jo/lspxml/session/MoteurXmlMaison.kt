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

    /**
     * Index des ressources du PROJET (XE3) — PARTAGÉ lui aussi : les
     * racines sources sont celles du PROJET, les connexions LSP
     * successives n'ont chacune QUE leurs documents ouverts.
     */
    private val indexRessources = jo.lspxml.resources.IndexRessources()

    /**
     * Pilote des passages d'indexation (ADR 0211/0213/0214 — demandes
     * D1-annexe/D3/D4/D5 de CodeIDE) : nourrissage UNE fois par sync
     * sur fil dédié (pré-chauffe XE2 + AAR + racines res), événements
     * de progression, annulation coopérative, reprise ciblée.
     */
    private val passeur =
        jo.lspxml.session.PasseurIndexationXml(indexRessources, catalogues)

    private val descripteur: Map<String, String> by lazy { lireDescripteurPack() }

    override val identifiant: String
        get() = descripteur["id"] ?: ID_DEFAUT

    override val version: String
        get() = descripteur["version"] ?: VERSION_DEFAUT

    override val versionMinimaleApp: String
        get() = descripteur["versionMinimaleApp"] ?: VERSION_DEFAUT

    override fun analyserSource(texte: CharSequence): jo.lspxml.parse.DocumentXml = parseur.analyser(texte)

    override fun creerSession(): SessionLangageXml = SessionLangageXmlImpl(parseur, catalogues, indexRessources)

    /** ADR 0211 (D1-d) — nourrissage moteur, une fois par sync, fil dédié. */
    override fun definirClasspath(classpath: jo.lspxml.api.ClasspathXml): Boolean =
        passeur.definirClasspath(classpath)

    /** ADR 0213 (D3) — progression observable (types neutres du SPI). */
    override fun enregistrerObservateurIndexation(
        observateur: jo.lspxml.api.ObservateurIndexationXml,
    ): java.io.Closeable = passeur.enregistrer(observateur)

    /** ADR 0214 (D5) — annulation coopérative du passage en vol. */
    override fun annulerPassage(): Boolean = passeur.annulerPassage()

    /** ADR 0214 (D4) — reprise ciblée des échecs du dernier passage. */
    override fun reesayerEchecs(): Boolean = passeur.reesayerEchecs()

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
