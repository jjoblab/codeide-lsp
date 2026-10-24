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

    /**
     * Index des appelables Kotlin du classpath (K3) — PARTAGÉ par
     * toutes les sessions du moteur (un projet = un moteur côté façade
     * hôte) : segments disque par empreinte, un jar inchangé n'est
     * jamais redécodé (ADR 0111 §3 pour l'esprit, l'identité vient de
     * l'hôte W6) ; cache MÉMOIRE et garde d'empreintes depuis l'ADR
     * 0211.
     */
    private val indexAppelables = jo.lspkotlin.index.IndexAppelablesKotlin()

    /**
     * Scan des racines sources du projet (K3) — PARTAGÉ lui aussi : les
     * connexions LSP successives ne re-scannent pas (invalidé quand les
     * racines changent ; les documents OUVERTS dominent côté session).
     */
    private val scanProjet = jo.lspkotlin.session.ScanProjet(parseur)

    /**
     * Pilote des passages d'indexation (ADR 0211/0213/0214 — demandes
     * D1/D3/D4/D5 de CodeIDE) : nourrissage UNE fois par sync sur fil
     * dédié, événements de progression, annulation coopérative,
     * reprise ciblée des échecs.
     */
    private val passeur =
        jo.lspkotlin.index.PasseurIndexationKotlin(indexAppelables, scanProjet)

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

    override fun creerSession(): SessionLangageKotlin =
        jo.lspkotlin.session.SessionLangageKotlinParse(parseur, indexAppelables, scanProjet)

    /** ADR 0211 (D1) — nourrissage moteur, une fois par sync, fil dédié. */
    override fun definirClasspath(classpath: ClasspathKotlin): Boolean =
        passeur.definirClasspath(classpath)

    /** ADR 0213 (D3) — progression observable (types neutres du SPI). */
    override fun enregistrerObservateurIndexation(
        observateur: jo.lspkotlin.api.ObservateurIndexationKotlin,
    ): java.io.Closeable = passeur.enregistrer(observateur)

    /** ADR 0214 (D5) — annulation coopérative du passage en vol. */
    override fun annulerPassage(): Boolean = passeur.annulerPassage()

    /** ADR 0214 (D4) — reprise ciblée des échecs du dernier passage. */
    override fun reesayerEchecs(): Boolean = passeur.reesayerEchecs()

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
