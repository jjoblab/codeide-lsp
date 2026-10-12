package jo.codeide.lsp.host

import jo.codeeditor.lsp.LanguageServerDefinition
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider
import jo.codeeditor.lsp.connection.StreamConnectionProvider
import jo.lspkotlin.api.MoteurLangageKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.eclipse.lsp4j.services.LanguageServer
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Façade de la session LSP Kotlin d'un projet (K2, LSP_KOTLIN.md §5) —
 * miroir volontairement MINCE de [SessionLsp] (Java) : même frontière
 * nette, même cycle de vie piloté par le ViewModel, même transport
 * in-process cel-lsp (ADR 0109 D2 — un changement de transport se
 * brancherait ici sans toucher au moteur ni à l'éditeur).
 *
 * Le moteur est résolu par le SPI ServiceLoader ([MoteurLangageKotlin],
 * D6 : c'est le SEUL type du moteur visible de l'hôte — jamais
 * l'implémentation concrète, jamais le parseur) ; chaque connexion LSP
 * reçoit sa PROPRE session moteur ([serveur]), les sessions vivantes
 * sont suivies et [close] referme celles qu'une connexion n'aurait pas
 * arrêtées (contrat « 0 ressource orpheline » du prompt 3).
 *
 * K2 : diagnostics syntaxiques seuls (documents + publication) — la
 * complétion, le hover et la définition rejoignent la façade en K3 avec
 * le modèle de symboles ; les définitions cel-lsp restent stables.
 *
 * @param racineProjet racine du projet ouvert (identité de la session).
 * @param moteur moteur de langue (SPI) — injectable pour les tests,
 *        résolution ServiceLoader par défaut (échec explicite si le
 *        module n'est pas branché : erreur de build, pas fantôme).
 */
public class SessionLspKotlin(
    racineProjet: File,
    private val moteur: MoteurLangageKotlin = ServiceMoteurLangageKotlin.unique(),
) : Closeable {
    /** Racine du projet ouvert (identité de la session). */
    public val racine: File = racineProjet

    /** Sessions moteur des connexions vivantes (fermées à [close] aussi). */
    private val sessionsVivantes = CopyOnWriteArrayList<SessionLangageKotlin>()

    private var fermee = false

    /**
     * UN serveur LSP4J pour une connexion (cel-lsp approvisionne par le
     * supplier du transport) : la session moteur est NEUVE et suivie —
     * shutdown/exit la referme et la retire, [close] rattrape les autres.
     */
    public fun serveur(): LanguageServer {
        synchronized(this) {
            check(!fermee) { "SessionLspKotlin fermée — créer une nouvelle session" }
        }
        val sessionLangage = moteur.creerSession()
        sessionsVivantes.add(sessionLangage)
        return KotlinLanguageServer(
            session = sessionLangage,
            versionServeur = moteur.version,
            aLArret = { sessionsVivantes.remove(sessionLangage) },
        )
    }

    /**
     * Les définitions cel-lsp du serveur Kotlin de CodeIDE — à enregistrer
     * dans le `LspProject` de l'espace (une par extension routée :
     * `.kt` et `.kts` — le mode script est déduit de l'URI côté serveur).
     */
    public fun definitions(): List<LanguageServerDefinition> =
        listOf(
            DefinitionServeurLspKotlin(EXTENSION_KT) { serveur() },
            DefinitionServeurLspKotlin(EXTENSION_SCRIPT) { serveur() },
        )

    /**
     * Arrêt exhaustif : sessions des connexions jamais arrêtées refermées,
     * décompte vidé. Idempotente (le Java l'est aussi — contrat h1).
     */
    public override fun close() {
        synchronized(this) {
            if (fermee) return
            fermee = true
        }
        sessionsVivantes.forEach { session ->
            try {
                session.close()
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "fermeture d'une session moteur échouée (ignorée)", t)
            }
        }
        sessionsVivantes.clear()
    }

    /** Définition cel-lsp in-process du serveur Kotlin (une extension). */
    private class DefinitionServeurLspKotlin(
        private val extension: String,
        private val fabrique: () -> LanguageServer,
    ) : LanguageServerDefinition(extension, NOM) {
        public override fun createConnectionProvider(uri: String): StreamConnectionProvider =
            InProcessStreamConnectionProvider(fabrique)
    }

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(SessionLspKotlin::class.java.name)

        /** Extensions et nom du serveur (langage Kotlin, K2). */
        private const val EXTENSION_KT: String = "kt"
        private const val EXTENSION_SCRIPT: String = "kts"
        private const val NOM: String = "codeide-kotlin"
    }
}
