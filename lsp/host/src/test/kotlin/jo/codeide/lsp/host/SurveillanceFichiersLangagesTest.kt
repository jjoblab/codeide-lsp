package jo.codeide.lsp.host

import jo.codeide.lsp.kotlin.protocole.ServeurLangageKotlin
import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * CÂBLAGE du surveillant de fichiers aux TROIS langages (lot L1 du plan
 * 1.3.0 — correctif W1 : `pousserEvenementsFichiers` ne bouclait que sur
 * les serveurs Java, les gestionnaires didChangeWatchedFiles de Kotlin
 * (KP10) et XML (XP8) existaient mais n'étaient JAMAIS appelés par
 * l'hôte).
 *
 * Le chemin éprouvé est celui de la PRODUCTION : une session Java (qui
 * possède le surveillant) + les façades Kotlin/XML BRANCHÉES dessus, un
 * serveur réel par langue (moteur réel, transport in-process), un client
 * espion, et les événements d'UN TOUR RÉEL du [SurveillantFichiersHote]
 * (instantané avant/après modification externe) poussés par la session.
 */
@Timeout(120)
class SurveillanceFichiersLangagesTest {
    /** Dossier temporaire du projet (racine + sources). */
    @field:TempDir
    public lateinit var dossierTemp: File

    /** L'espace des trois langues : session Java + façades branchées. */
    private class Espace(
        public val session: SessionLsp,
        public val kotlin: SessionLspKotlin,
        public val xml: SessionLspXml,
    ) {
        public fun fermer() {
            kotlin.close()
            xml.close()
            session.close()
        }
    }

    /**
     * Ouvre l'espace complet : session Java (surveillant DÉSACTIVÉ pour
     * piloter les tours à la main — le test pousse lui-même les événements
     * d'un tour réel) + façades Kotlin/XML branchées sur son registre.
     */
    private fun espaceAvecSurveillanceManuelle(racine: File): Espace {
        val session =
            SessionLsp(
                racineProjet = racine,
                stockageIndex = dossierTemp.resolve("index-${System.nanoTime()}"),
                config = ConfigurationLsp(surveillanceFichiersMs = ConfigurationLsp.SURVEILLANCE_DESACTIVEE_MS),
            )
        val kotlin = SessionLspKotlin(racine, sessionJava = session)
        val xml = SessionLspXml(racine, sessionJava = session)
        return Espace(session, kotlin, xml)
    }

    /** Modèle minimal : UN module actif portant [racine] comme source. */
    private fun modeleUneRacine(racine: File): ProjetLsp =
        ProjetLsp(
            cheminRacine = racine.parentFile?.absolutePath ?: racine.absolutePath,
            modules =
                listOf(
                    ModuleLsp(
                        nom = ":app",
                        type = TypeModuleLsp.JVM,
                        variante = null,
                        sources =
                            EnsemblesSources(
                                principal = listOf(racine.path),
                                test = emptyList(),
                                generes = emptyList(),
                            ),
                        classpath = ClasspathInstantane(emptyList(), "instantane"),
                        dependancesModules = emptyList(),
                        androidJar = null,
                        ignore = false,
                        raisonIgnore = null,
                        avertissements = emptyList(),
                        empreinte = "module-app",
                    ),
                ),
            empreinte = "projet",
        )

    /** Client espion : collecte les publications de diagnostics. */
    private class EspionClient : LanguageClient {
        public val recus: CopyOnWriteArrayList<PublishDiagnosticsParams> = CopyOnWriteArrayList()

        override fun telemetryEvent(obj: Any?) {
            // Non sollicité.
        }

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            recus.add(params)
        }

        override fun showMessage(params: MessageParams) {
            // Non sollicité.
        }

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(MessageActionItem())

        override fun logMessage(params: MessageParams) {
            // Non sollicité.
        }
    }

    /**
     * Attend la publication de diagnostics d'une URI (debounce 50 ms du
     * pack — la publication est ASYNCHRONE, jamais synchrone au didOpen ;
     * `depuis` ignore les publications antérieures à cet indice).
     */
    private fun attendrePublication(
        espion: EspionClient,
        uri: String,
        depuis: Int = 0,
        delaiMs: Long = 10_000,
    ): org.eclipse.lsp4j.PublishDiagnosticsParams {
        val fin = System.currentTimeMillis() + delaiMs
        while (System.currentTimeMillis() < fin) {
            val recue = espion.recus.drop(depuis).lastOrNull { it.uri == uri }
            if (recue != null) return recue
            Thread.sleep(50)
        }
        throw AssertionError("aucune publication de $uri après $delaiMs ms")
    }

    /** Serveur Kotlin du pack réel + espion branchés (initialize + initialized). */
    private fun serveurKotlin(espace: Espace): Pair<ServeurLangageKotlin, EspionClient> {
        val serveur = espace.kotlin.serveur() as ServeurLangageKotlin
        val espion = EspionClient()
        serveur.connect(espion)
        serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        serveur.initialized(InitializedParams())
        return serveur to espion
    }

    /** Serveur XML réel + espion branchés (initialize fait). */
    private fun serveurXml(espace: Espace): Pair<XmlLanguageServer, EspionClient> {
        val serveur = espace.xml.serveur() as XmlLanguageServer
        val espion = EspionClient()
        serveur.connect(espion)
        serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        return serveur to espion
    }

    @Test
    fun `kotlin - un fichier externe modifie rafraichit les diagnostics du document ouvert`() {
        val racine = dossierTemp.resolve("projet-kt").apply { mkdirs() }
        val sources = racine.resolve("src/main/java").apply { mkdirs() }
        val espace = espaceAvecSurveillanceManuelle(racine)
        try {
            // Le classpath Kotlin connaît la racine (scan des sources).
            espace.kotlin.appliquerModele(modeleUneRacine(sources))
            val (serveur, espion) = serveurKotlin(espace)
            try {
                // A.kt OUVERT référence util() — Autre.kt FERMÉ ne la déclare
                // pas encore : la publication initiale porte kt.unresolved
                // (débouncée 50 ms par le pack : attente bornée).
                val a = sources.resolve("A.kt")
                a.writeText("fun principal() {\n    util()\n}\n")
                serveur.textDocumentService.didOpen(
                    DidOpenTextDocumentParams(TextDocumentItem(a.toURI().toString(), "kotlin", 1, a.readText())),
                )
                val initiale = attendrePublication(espion, a.toURI().toString())
                assertTrue(
                    initiale.diagnostics.any { it.code?.left == "kt.unresolved" },
                    "util() non déclaré : la publication initiale porte kt.unresolved",
                )

                // Modification EXTERNE de Autre.kt (terminal, build Gradle) :
                // un tour RÉEL du surveillant produit l'événement, la façade
                // le diffuse (le chemin complet de production).
                val surveillant = SurveillantFichiersHote(racine, periodeMs = 0L)
                val avant = surveillant.instantane()
                val autre = sources.resolve("Autre.kt")
                autre.writeText("fun util() {}\n")
                val evenements = surveillant.unTour(avant, surveillant.instantane())
                assertTrue(evenements.isNotEmpty(), "le .kt externe modifié produit un événement")
                espace.session.pousserEvenementsFichiers(DidChangeWatchedFilesParams(evenements))

                // DELTA ASSUMÉ de la bascule ADR 0136 : le serveur du pack
                // RAFRAÎCHIT son scan sur l'événement (fil dédié) mais ne
                // REPUBLIE PAS spontanément les documents ouverts — la
                // republication part à la PROCHAINE ÉDITION (didChange).
                // La preuve du rafraîchissement : le même texte repoussé
                // (nudge) republie SANS kt.unresolved — util() est résolue.
                Thread.sleep(500) // le scan rafraîchi du pack (fil dédié)
                val compteAvant = espion.recus.size
                serveur.textDocumentService.didChange(
                    org.eclipse.lsp4j.DidChangeTextDocumentParams(
                        org.eclipse.lsp4j.VersionedTextDocumentIdentifier(a.toURI().toString(), 2),
                        listOf(
                            org.eclipse.lsp4j.TextDocumentContentChangeEvent(a.readText()),
                        ),
                    ),
                )
                val apres = attendrePublication(espion, a.toURI().toString(), depuis = compteAvant)
                assertEquals(a.toURI().toString(), apres.uri)
                assertTrue(
                    apres.diagnostics.none { it.code?.left == "kt.unresolved" },
                    "après l'événement externe et le nudged didChange, util() est résolue",
                )
            } finally {
                serveur.shutdown().get(10, TimeUnit.SECONDS)
                serveur.exit()
            }
        } finally {
            espace.fermer()
        }
    }

    @Test
    fun `xml - une ressource externe creee rafraichit les diagnostics du document ouvert`() {
        val racine = dossierTemp.resolve("projet-xml").apply { mkdirs() }
        val sources = racine.resolve("src/main").apply { mkdirs() }
        val espace = espaceAvecSurveillanceManuelle(racine)
        try {
            // Le classpath XML connaît la racine (index des ressources).
            espace.xml.appliquerModele(modeleUneRacine(sources), null)
            val (serveur, espion) = serveurXml(espace)
            try {
                // activity.xml OUVERT référence @string/titre — le values
                // déclarant n'existe pas encore : référence non résolue.
                val layout = sources.resolve("res/layout").apply { mkdirs() }
                val activity = layout.resolve("activity.xml")
                activity.writeText(
                    "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                        "    android:text=\"@string/titre\" />\n",
                )
                serveur.textDocumentService.didOpen(
                    DidOpenTextDocumentParams(
                        TextDocumentItem(activity.toURI().toString(), "xml", 1, activity.readText()),
                    ),
                )
                val initiale = espion.recus.last()
                assertTrue(
                    initiale.diagnostics.any { it.code?.left == "xml.referenceNonResolue" },
                    "@string/titre non déclarée : la publication initiale porte la référence non résolue",
                )

                // Création EXTERNE du values déclarant (build, terminal) :
                // un tour RÉEL du surveillant produit l'événement Created.
                val surveillant = SurveillantFichiersHote(racine, periodeMs = 0L)
                val avant = surveillant.instantane()
                val values = sources.resolve("res/values").apply { mkdirs() }
                values
                    .resolve(
                        "strings.xml",
                    ).writeText("<resources>\n    <string name=\"titre\">Titre</string>\n</resources>\n")
                val evenements = surveillant.unTour(avant, surveillant.instantane())
                assertTrue(evenements.isNotEmpty(), "le .xml externe créé produit un événement")
                espace.session.pousserEvenementsFichiers(DidChangeWatchedFilesParams(evenements))

                // Le serveur XML a RE-SCANNÉ l'index puis republié : la
                // référence est résolue — plus de xml.referenceNonResolue.
                val apres = espion.recus.last()
                assertEquals(activity.toURI().toString(), apres.uri)
                assertTrue(
                    apres.diagnostics.none { it.code?.left == "xml.referenceNonResolue" },
                    "après l'événement externe, @string/titre est résolue (index rafraîchi)",
                )
            } finally {
                serveur.shutdown().get(10, TimeUnit.SECONDS)
                serveur.exit()
            }
        } finally {
            espace.fermer()
        }
    }

    @Test
    fun `serveur arrete - il ne recoit plus rien, les vivants si`() {
        val racine = dossierTemp.resolve("projet-arret").apply { mkdirs() }
        val espace = espaceAvecSurveillanceManuelle(racine)
        try {
            // Le classpath Kotlin connaît la racine (scan des sources —
            // SANS lui, l'index est muet : aucune preuve, aucun diagnostic).
            espace.kotlin.appliquerModele(modeleUneRacine(racine))
            val premier = serveurKotlin(espace)
            val second = serveurKotlin(espace)
            // Un document OUVERT sur le second, référençant une fonction
            // DÉCLARÉE NULLE PART au départ : publication initiale à
            // kt.unresolved (débouncée — attente bornée).
            val b = racine.resolve("B.kt")
            b.writeText("fun principal() {\n    externe()\n}\n")
            second.first.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(b.toURI().toString(), "kotlin", 1, b.readText())),
            )
            val initiale = attendrePublication(second.second, b.toURI().toString())
            assertTrue(
                initiale.diagnostics.any { it.code?.left == "kt.unresolved" },
                "externe() non déclarée : publication initiale à kt.unresolved",
            )
            // Le PREMIER serveur est arrêté (shutdown) : il quitte la liste
            // des vivants de la façade — la poussée ne doit l'ATTEINDRE
            // QUE le second (et ne jamais lever côté arrêté).
            premier.first.shutdown().get(10, TimeUnit.SECONDS)
            premier.first.exit()
            // Événement EXTERNE : Externe.kt déclare désormais la fonction.
            // Le vivant RAFRAÎCHIT son scan (delta bascule ADR 0136 : pas
            // de republication spontanée) — la preuve vit au nudged
            // didChange SUIVANT.
            racine.resolve("Externe.kt").writeText("fun externe() {}\n")
            espace.session.pousserEvenementsFichiers(
                DidChangeWatchedFilesParams(
                    listOf(FileEvent(racine.resolve("Externe.kt").toURI().toString(), FileChangeType.Created)),
                ),
            )
            Thread.sleep(500) // le scan rafraîchi du pack (fil dédié)
            val compteAvant = second.second.recus.size
            second.first.textDocumentService.didChange(
                org.eclipse.lsp4j.DidChangeTextDocumentParams(
                    org.eclipse.lsp4j.VersionedTextDocumentIdentifier(b.toURI().toString(), 2),
                    listOf(
                        org.eclipse.lsp4j.TextDocumentContentChangeEvent(b.readText()),
                    ),
                ),
            )
            val apres = attendrePublication(second.second, b.toURI().toString(), depuis = compteAvant)
            assertTrue(
                apres.diagnostics.none { it.code?.left == "kt.unresolved" },
                "le serveur VIVANT a rafraîchi son scan (externe() résolue après le nudged didChange)",
            )
            second.first.shutdown().get(10, TimeUnit.SECONDS)
            second.first.exit()
        } finally {
            espace.fermer()
        }
    }

    @Test
    fun `fermeture des facades - desinscription du surveillant, aucune fuite`() {
        val racine = dossierTemp.resolve("projet-fermeture").apply { mkdirs() }
        val espace = espaceAvecSurveillanceManuelle(racine)
        // Fermeture des façades : elles se DÉSINSCRIVENT du registre de la
        // session Java — une poussée ultérieure ne doit ni lever ni
        // atteindre un serveur mort (les listes de la façade sont vidées).
        espace.kotlin.close()
        espace.xml.close()
        espace.session.pousserEvenementsFichiers(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(racine.resolve("y.kt").toURI().toString(), FileChangeType.Changed)),
            ),
        )
        assertTrue(!espace.session.surveillanceVivante, "surveillance manuelle : aucune boucle vivante")
        // Fermetures IDEMPOTENTES des façades déjà fermées.
        espace.kotlin.close()
        espace.xml.close()
        espace.fermer()
    }

    @Test
    fun `interrupteur - surveillance desactivee, aucune boucle pour les trois langages`() {
        val racine = dossierTemp.resolve("projet-interrupteur").apply { mkdirs() }
        val session =
            SessionLsp(
                racineProjet = racine,
                stockageIndex = dossierTemp.resolve("index-coupe"),
                config = ConfigurationLsp(surveillanceFichiersMs = ConfigurationLsp.SURVEILLANCE_DESACTIVEE_MS),
            )
        try {
            assertTrue(!session.surveillanceVivante, "période 0 : aucune boucle installée")
            // Réglage dynamique à une période positive : la boucle revient
            // (l'interrupteur est respecté dans les DEUX sens).
            session.appliquer(ConfigurationLsp())
            assertTrue(session.surveillanceVivante, "période 5000 : la boucle est installée")
            // Retour à 0 : coupée.
            session.appliquer(ConfigurationLsp(surveillanceFichiersMs = ConfigurationLsp.SURVEILLANCE_DESACTIVEE_MS))
            assertTrue(!session.surveillanceVivante, "retour à 0 : la boucle est coupée")
        } finally {
            session.close()
        }
    }
}
