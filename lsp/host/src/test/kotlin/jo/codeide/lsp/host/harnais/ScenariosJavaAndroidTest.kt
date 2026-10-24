package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.client
import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeurJava
import jo.codeide.lsp.host.harnais.OutilsHarnais.ecrireClasspathJava
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SCÉNARIOS JAVA ANDROID (découverte D6, ADR 0215).
 *
 * Le CAS RAPPORTÉ PAR LE PROPRIÉTAIRE, rejoué au fil du VRAI transport
 * stdio JSON-RPC : un petit projet Android de deux fichiers `.java`
 * dans le MÊME paquet (`MainActivity.java` + `Greeter.java`) + un
 * ViewBinding (`ActivityMainBinding`) —
 *
 * > « Quand j'ouvre MainActivity, j'obtiens des diagnostics erreurs
 * > pour ActivityMainBinding et Greeter (cannot be resolved to a
 * > type) »
 *
 * CAUSE RACINE : tous les services du serveur Java consultaient le
 * contexte DÉFAUT (android.jar seul) — les moteurs PAR MODULE que
 * l'orchestrateur nourrissait (racines sources + generated + jars du
 * modèle) n'étaient JAMAIS routés : `AssociationFichier` (modèle)
 * faisait déjà l'association, il n'était branché nulle part.
 *
 * PREUVE DE RÉPARATION (chaîne complète de production) :
 *  D1 — didOpen AVANT sync : le régime défaut publie les erreurs du
 *       rapport ; la sync (notification harnais/appliquerClasspath →
 *       `session.appliquerClasspath` de production) → live-republish
 *       → les erreurs s'effacent SEULES (Greeter same-package,
 *       ActivityMainBinding generated) ;
 *  D2 — textDocument/definition sur `Greeter` → la déclaration dans
 *       Greeter.java (le moteur du MODULE balaie SES racines) ;
 *  D3 — textDocument/references sur `Greeter` → les deux occurrences ;
 *  D4 — definition sur `ActivityMainBinding` → la racine `generated`.
 */
@Timeout(900)
@Suppress("TooManyFunctions")
class ScenariosJavaAndroidTest {
    /** Ouvre MainActivity.java et retourne son URI. */
    private fun ouvrirMainActivity(connexion: OutilsHarnais.Connexion): String {
        val uri = File(connexion.racine, "src/com/ex/app/MainActivity.java").toURI().toString()
        val texte = File(connexion.racine, "src/com/ex/app/MainActivity.java").readText()
        connexion.distant.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "java", 1, texte)),
        )
        return uri
    }

    /** Pousse la sync de production (JSON sur disque → notification). */
    private fun pousserSync(connexion: OutilsHarnais.Connexion) {
        val fichier = ecrireClasspathJava(connexion.racine, OutilsHarnais.androidJar)
        connexion.endpoint.notify(
            "harnais/appliquerClasspath",
            mapOf("chemin" to fichier.absolutePath),
        )
    }

    private fun erreurNonResolue(
        publication: org.eclipse.lsp4j.PublishDiagnosticsParams,
        fragment: String,
    ): org.eclipse.lsp4j.Diagnostic? =
        publication.diagnostics.firstOrNull { d ->
            d.message.contains(fragment, ignoreCase = true)
        }

    @Test
    fun `D1 - Greeter meme paquet - le live-republish efface l erreur apres sync`() {
        val connexion = demarrerServeurJava("d1")
        try {
            val uri = ouvrirMainActivity(connexion)
            // RÉGIME DÉFAUT AVANT SYNC : le moteur défaut (jrt desktop,
            // sans racines sources du module) ne connaît NI Greeter NI
            // ActivityMainBinding — LE SYMPTÔME RAPPORTÉ.
            val avant = client(connexion).attendre(uri) { p -> p.diagnostics.isNotEmpty() }
            val greeterAvant = erreurNonResolue(avant, "Greeter cannot be resolved")
            assertNotNull(
                greeterAvant,
                "avant sync, le régime défaut doit signaler Greeter non résolu " +
                    "(symptôme du propriétaire) — reçus : ${avant.diagnostics.map { it.message }}",
            )

            // SYNC DE PRODUCTION → modèle → moteur :app → ContextListener
            // → live-republish des documents ouverts.
            pousserSync(connexion)

            val apres =
                client(connexion).attendre(uri, delaiMs = 60_000) { p ->
                    p.diagnostics.none { d -> d.message.contains("cannot be resolved", ignoreCase = true) }
                }
            assertEquals(
                0,
                apres.diagnostics.count { d -> d.message.contains("cannot be resolved", ignoreCase = true) },
                "après sync, AUCUN « cannot be resolved » ne doit rester : ${apres.diagnostics.map { it.message }}",
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D1bis - ActivityMainBinding generated - resolu par le moteur du module`() {
        val connexion = demarrerServeurJava("d1bis")
        try {
            val uri = ouvrirMainActivity(connexion)
            val avant = client(connexion).attendre(uri) { p -> p.diagnostics.isNotEmpty() }
            assertNotNull(
                erreurNonResolue(avant, "ActivityMainBinding cannot be resolved"),
                "avant sync, le régime défaut doit signaler le ViewBinding " +
                    "(symptôme du propriétaire) — reçus : ${avant.diagnostics.map { it.message }}",
            )
            pousserSync(connexion)
            val apres =
                client(connexion).attendre(uri, delaiMs = 60_000) { p ->
                    p.diagnostics.none { d -> d.message.contains("cannot be resolved", ignoreCase = true) }
                }
            assertEquals(
                0,
                apres.diagnostics.count { d -> d.message.contains("ActivityMainBinding") },
                "le ViewBinding de la racine generated doit résoudre sur le moteur :app",
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D2 - definition Greeter - declaration dans le fichier frere`() {
        val connexion = demarrerServeurJava("d2")
        try {
            // La sync D'ABORD (régime nominal de l'éditeur : le modèle
            // nourrit la session avant l'ouverture, miroir h4).
            pousserSync(connexion)
            // Laisse le modèle s'appliquer (l'invalidation est async côté
            // orchestrateur — la requête qui suit route par l'instantané).
            Thread.sleep(1_500)
            val uri = ouvrirMainActivity(connexion)
            val texte = File(connexion.racine, "src/com/ex/app/MainActivity.java").readText()
            val decalage = texte.indexOf("greeter.saluer()")
            assertTrue(decalage >= 0, "l'usage de Greeter doit exister dans MainActivity.java")
            // La position du TYPE « Greeter » dans la déclaration du champ.
            val positionGreeter = OutilsHarnais.positionDe(texte, texte.indexOf("Greeter greeter"))

            val reponse =
                connexion.distant.textDocumentService
                    .definition(
                        DefinitionParams(TextDocumentIdentifier(uri), positionGreeter),
                    ).get(120, TimeUnit.SECONDS)
            val cibles: List<Location> = reponse.left ?: emptyList()
            assertTrue(
                cibles.any { l -> l.uri.endsWith("Greeter.java") },
                "la définition de Greeter doit atterrir dans Greeter.java — reçues : " +
                    cibles.map { it.uri },
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D3 - references Greeter - les deux occurrences du meme paquet`() {
        val connexion = demarrerServeurJava("d3")
        try {
            pousserSync(connexion)
            Thread.sleep(1_500)
            val uri = ouvrirMainActivity(connexion)
            val texte = File(connexion.racine, "src/com/ex/app/MainActivity.java").readText()
            val positionGreeter = OutilsHarnais.positionDe(texte, texte.indexOf("Greeter greeter"))
            val contexte = ReferenceContext()
            contexte.setIncludeDeclaration(true)
            val reponse =
                connexion.distant.textDocumentService
                    .references(
                        ReferenceParams(
                            TextDocumentIdentifier(uri),
                            positionGreeter,
                            contexte,
                        ),
                    ).get(120, TimeUnit.SECONDS)
            assertNotNull(reponse, "references ne doit jamais être null")
            assertTrue(
                reponse.size >= 2,
                "Greeter est référencé au moins DEUX fois (déclaration du champ + usage saluer()) " +
                    "— reçues : ${reponse.size}",
            )
            assertTrue(
                reponse.any { l -> l.uri.endsWith("MainActivity.java") },
                "les occurrences de MÊME PAQUET vivent dans MainActivity.java — reçues : " +
                    reponse.map { it.uri },
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D4 - definition ActivityMainBinding - racine generated du modele`() {
        val connexion = demarrerServeurJava("d4")
        try {
            pousserSync(connexion)
            Thread.sleep(1_500)
            val uri = ouvrirMainActivity(connexion)
            val texte = File(connexion.racine, "src/com/ex/app/MainActivity.java").readText()
            val positionBinding =
                OutilsHarnais.positionDe(texte, texte.indexOf("ActivityMainBinding.inflate"))
            val reponse =
                connexion.distant.textDocumentService
                    .definition(
                        DefinitionParams(TextDocumentIdentifier(uri), positionBinding),
                    ).get(120, TimeUnit.SECONDS)
            val cibles: List<Location> = reponse.left ?: emptyList()
            assertTrue(
                cibles.any { l -> l.uri.contains("generated") && l.uri.endsWith("ActivityMainBinding.java") },
                "la définition du ViewBinding doit atterrir dans la racine GENERATED du module " +
                    "— reçues : ${cibles.map { it.uri }}",
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D5 - setContentView herite d Activity - jamais signale (bindings ecj)`() {
        val connexion = demarrerServeurJava("d5")
        try {
            pousserSync(connexion)
            Thread.sleep(1_500)
            val uri = ouvrirMainActivity(connexion)
            // La publication PROPRE peut être VIDE (aucune erreur du tout) :
            // on attend simplement l'ABSENCE du symptôme « cannot be resolved ».
            val apres =
                client(connexion).attendre(uri, delaiMs = 60_000) { p ->
                    p.diagnostics.none { d -> d.message.contains("cannot be resolved", ignoreCase = true) }
                }
            assertEquals(
                0,
                apres.diagnostics.count { d -> d.message.contains("setContentView") },
                "setContentView(View) est HÉRITÉ d'android.app.Activity (android.jar) — " +
                    "jamais de faux « cannot be resolved » : ${apres.diagnostics.map { it.message }}",
            )
        } finally {
            connexion.arreter()
        }
    }

    @Test
    fun `D6 - capacites go-to annoncees au fil du transport (branchement CodeIDE)`() {
        // initialiser=false : la négociation est faite ICI (une seule —
        // la garde I3 du serveur REJETTE un second initialize, contrat
        // LSP 3.17 que ce scénario relit au passage).
        val connexion = demarrerServeurJava("d6", initialiser = false)
        try {
            val params = org.eclipse.lsp4j.InitializeParams()
            params.workspaceFolders =
                listOf(org.eclipse.lsp4j.WorkspaceFolder(connexion.racine.toURI().toString(), "harnais"))
            val resultat = connexion.distant.initialize(params).get(120, TimeUnit.SECONDS)
            val capacites = resultat.capabilities
            assertTrue(OutilsHarnais.annonce(capacites.definitionProvider), "definitionProvider")
            assertTrue(OutilsHarnais.annonce(capacites.typeDefinitionProvider), "typeDefinitionProvider")
            assertTrue(OutilsHarnais.annonce(capacites.implementationProvider), "implementationProvider")
            assertTrue(OutilsHarnais.annonce(capacites.referencesProvider), "referencesProvider")
            val experimental = capacites.experimental
            // Le transport Gson désérialise le champ Object en JsonObject —
            // lecture explicite (le client cel-lsp de production fait son
            // propre mapping de cette map expérimentale).
            val superAnnonce =
                (experimental as? com.google.gson.JsonObject)
                    ?.get("superDefinitionProvider")
                    ?.asBoolean == true
            assertTrue(
                superAnnonce,
                "superDefinitionProvider (extension expérimentale, panneau GO TO) — " +
                    "reçue : $experimental (${experimental?.javaClass?.name})",
            )
        } finally {
            connexion.arreter()
        }
    }
}
