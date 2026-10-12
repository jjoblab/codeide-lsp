package jo.lspkotlin.api

import jo.lspkotlin.parse.ArbreSyntaxiqueKotlin
import jo.lspkotlin.parse.ParseurKotlin
import java.util.Properties

/**
 * Implémentation de référence du SPI [MoteurLangageKotlin] : la grammaire
 * vendorée derrière le parseur [ParseurKotlin].
 *
 * NE PAS référencer cette classe hors du module — elle n'est publique que
 * pour ServiceLoader (descripteur META-INF/services) ; tout consommateur
 * passe par [ServiceMoteurLangageKotlin.unique] (LSP_KOTLIN §6 : le pack
 * futur est chargé par DexClassLoader et résolu par descripteur, jamais
 * par type concret).
 */
public class MoteurKotlinVendore : MoteurLangageKotlin {
    private val parseur = ParseurKotlin()
    private val descripteur: Map<String, String> by lazy { lireDescripteurPack() }

    override val identifiant: String
        get() = descripteur["id"] ?: ID_DEFAUT

    override val version: String
        get() = descripteur["version"] ?: VERSION_DEFAUT

    override val versionMinimaleApp: String
        get() = descripteur["versionMinimaleApp"] ?: VERSION_DEFAUT

    override val versionGrammaire: String
        get() = descripteur["versionGrammaire"] ?: GRAMMAIRE_DEFAUT

    override fun analyserSource(
        texte: CharSequence,
        script: Boolean,
    ): ArbreSyntaxiqueKotlin = parseur.analyser(texte, script)

    private fun lireDescripteurPack(): Map<String, String> {
        val chemin = "jo/lspkotlin/pack/version.properties"
        val flux = MoteurKotlinVendore::class.java.classLoader.getResourceAsStream(chemin)
            ?: return emptyMap()
        val proprietes = Properties()
        flux.use { proprietes.load(it) }
        return proprietes.entries.associate { (it.key as? String ?: "") to (it.value as? String ?: "") }
    }

    private companion object {
        // Valeurs de repli si le descripteur manque — traçables en test
        // (ChargeurMoteurKotlinTest échoue s'il y recourt : le descripteur
        // EST partie du contrat de pack, son absence est un bug).
        private const val ID_DEFAUT = "jo.codeide.lsp.kotlin"
        private const val VERSION_DEFAUT = "inconnue"
        private const val GRAMMAIRE_DEFAUT = "inconnue"
    }
}
