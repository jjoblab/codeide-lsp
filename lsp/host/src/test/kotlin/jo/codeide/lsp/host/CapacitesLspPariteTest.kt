package jo.codeide.lsp.host

import jo.lspjava.engine.CompilationEnvironment
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspxml.api.SessionLangageXml
import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * PARITÉ capacités annoncées ↔ capacités réelles (lot LSP-1, ADR 0128 §4)
 * — le garde-fou de [CapacitesAnnoncees] : la table qui pilote les
 * interrupteurs de l'écran L2 ne peut pas mentir, chaque serveur est
 * INITIALISÉ pour de vrai et ses `ServerCapabilities` traduites par
 * [CapacitesAnnoncees.depuisServeur] doivent être EXACTEMENT la table.
 *
 * DIAGNOSTICS vit hors de la comparaison : les `ServerCapabilities` LSP
 * n'ont pas de champ diagnostics (publication implicite par
 * `publishDiagnostics`, contrat des trois moteurs) — la valeur vient de
 * la table elle-même.
 *
 * Les sessions Kotlin/XML sont des PROXYS jamais sollicités : `initialize`
 * construit les capacités sans toucher à la session (le proxy ÉCHOE si
 * c'était faux — le test devient menteur à son tour).
 */
@Timeout(60)
class CapacitesLspPariteTest {
    @Test
    fun `java - table = capacites reelles du serveur`() {
        val serveur = jo.lspjava.lsp.JavaLanguageServer(CompilationEnvironment()) {}
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.JAVA) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `kotlin - table = capacites reelles du serveur`() {
        val serveur = KotlinLanguageServer(proxyPassif(SessionLangageKotlin::class.java), "version-test")
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.KOTLIN) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `xml - table = capacites reelles du serveur`() {
        val serveur = XmlLanguageServer(proxyPassif(SessionLangageXml::class.java), "version-test")
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.XML) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    /** Initialize RÉEL d'un serveur → traduction publique de ses capacités. */
    private fun capaciteReelles(serveur: org.eclipse.lsp4j.services.LanguageServer): Set<CapaciteLsp> {
        val resultat = serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        return CapacitesAnnoncees.depuisServeur(resultat.capabilities)
    }

    /**
     * Proxy dynamique d'une interface de session : PASSIF (valeurs par
     * défaut du type de retour) — `initialize` ne lit aucun état de la
     * session pour construire les capacités, et le `shutdown` de fin de
     * test referme la session en no-op. Si une méthode devait UN JOUR
     * influencer les capacités annoncées, la parité du test le révélerait
     * (faux positif impossible : la table est comparée aux caps réelles).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxyPassif(interfaceDuLangage: Class<T>): T =
        Proxy.newProxyInstance(
            interfaceDuLangage.classLoader,
            arrayOf(interfaceDuLangage),
        ) { _, methode, _ ->
            when (methode.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> ' '
                else -> null
            }
        } as T
}
