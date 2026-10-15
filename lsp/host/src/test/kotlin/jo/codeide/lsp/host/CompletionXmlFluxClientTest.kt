package jo.codeide.lsp.host

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EnsemblesSources
import jo.codeide.lsp.model.ModuleLsp
import jo.codeide.lsp.model.ProjetLsp
import jo.codeide.lsp.model.TypeModuleLsp
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeActionCapabilities
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.HoverCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Régression v0.134.4 — complétion XML de bout en bout (retour
 * utilisateur : « le LSP XML ne propose plus de complétion ») :
 * simulation du flux RÉEL cel-lsp (initialize avec les capacités que
 * cel-lsp envoie VRAIMENT — sans textDocument.definition —, didOpen,
 * didChange full, completion) sur un projet PORTANT une plateforme
 * (catalogue widgets.txt + attrs.xml).
 *
 * La cause racine côté client (porte de contexte cel-ui qui rejette la
 * complétion dans les valeurs d'attribut XML et sous une apostrophe non
 * appariée) vit dans la bibliothèque code-editor ; CE test garantit que
 * le SERVEUR, lui, répond correctement dans toutes ces zones — la
 * régression XP9 (définition non déclarée) y est aussi verrouillée.
 */
class CompletionXmlFluxClientTest {
    @field:TempDir
    public lateinit var dossierTemp: File

    private class Espion : LanguageClient {
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

    /** Montage : plateforme factice (widgets.txt + attrs.xml styleables). */
    private fun plateformeFactice(): File {
        val plateforme = dossierTemp.resolve("platforms/android-34").apply { mkdirs() }
        val data = plateforme.resolve("data")
        data.resolve("res/values").mkdirs()
        data.resolve("widgets.txt").writeText(
            buildString {
                append("Wandroid.widget.TextView android.widget.View java.lang.Object\n")
                append("Wandroid.widget.Button android.widget.View java.lang.Object\n")
                append("Wandroid.widget.ImageView android.widget.View java.lang.Object\n")
                append("Wandroid.widget.EditText android.widget.View java.lang.Object\n")
                append("Wandroid.view.View java.lang.Object\n")
            },
        )
        data.resolve("res/values/attrs.xml").writeText(
            """
            <resources>
              <declare-styleable name="LinearLayout">
                <attr name="orientation">
                  <enum name="horizontal" value="0" />
                  <enum name="vertical" value="1" />
                </attr>
                <attr name="gravity" />
              </declare-styleable>
              <declare-styleable name="ViewGroup_Layout">
                <attr name="layout_width" format="dimension">
                  <enum name="match_parent" value="-1" />
                  <enum name="wrap_content" value="-2" />
                </attr>
                <attr name="layout_height" format="dimension">
                  <enum name="match_parent" value="-1" />
                  <enum name="wrap_content" value="-2" />
                </attr>
              </declare-styleable>
              <declare-styleable name="TextView">
                <attr name="text" format="string" />
                <attr name="textColor" format="color" />
              </declare-styleable>
              <attr name="id" format="reference" />
            </resources>
            """.trimIndent(),
        )
        return plateforme
    }

    /** Position (ligne, colonne) d'un décalage — aide de test. */
    private fun positionDe(
        texte: String,
        decalage: Int,
    ): Position {
        var ligne = 0
        var dernier = 0
        for (i in 0 until decalage) {
            if (texte[i] == '\n') {
                ligne += 1
                dernier = i + 1
            }
        }
        return Position(ligne, decalage - dernier)
    }

    /**
     * Exemption detekt ciblée (règle 16) : LongMethod — le test est un
     * SCÉNARIO de flux client complet (initialize → classpath → didOpen →
     * 3 didChange → 4 complétions) dont chaque étape alimente la
     * suivante ; le découper casserait la chronologie du flux réel cel-lsp
     * que la régression exige de couvrir d'un seul tenant.
     */
    @Suppress("LongMethod")
    @Test
    public fun `flux cel-lsp - completion balises attributs et valeurs, definition declaree`() {
        val plateforme = plateformeFactice()
        val facade = SessionLspXml(dossierTemp.resolve("projet"))
        val espion = Espion()
        val serveur = facade.serveur() as XmlLanguageServer
        serveur.connect(espion)

        // 1. initialize avec les caps RÉELLES de cel-lsp 3.69.0 : PAS
        //    de textDocument.definition (le client consomme pourtant la
        //    définition) — v0.134.4 : le serveur doit quand même la
        //    déclarer.
        val params = InitializeParams()
        val clientCaps = ClientCapabilities()
        val textDocCaps = org.eclipse.lsp4j.TextDocumentClientCapabilities()
        textDocCaps.completion = CompletionCapabilities()
        textDocCaps.hover = HoverCapabilities()
        textDocCaps.codeAction = CodeActionCapabilities()
        textDocCaps.documentSymbol = DocumentSymbolCapabilities()
        clientCaps.textDocument = textDocCaps
        params.capabilities = clientCaps
        val init = serveur.initialize(params).join()
        assertTrue(
            init.capabilities.definitionProvider.left,
            "XP9 corrigé : définition déclarée même sans caps client",
        )
        assertTrue(init.capabilities.completionProvider != null)

        // 2. Classpath avec plateforme + racine sources.
        val racine = dossierTemp.resolve("src/main").apply { mkdirs() }
        racine.resolve("res/layout").mkdirs()
        facade.appliquerModele(
            ProjetLsp(
                dossierTemp.path,
                listOf(
                    ModuleLsp(
                        nom = ":app",
                        type = TypeModuleLsp.ANDROID,
                        variante = "debug",
                        sources =
                            EnsemblesSources(
                                principal = listOf(racine.path),
                                test = emptyList(),
                                generes = emptyList(),
                            ),
                        classpath = ClasspathInstantane(emptyList(), "instantane"),
                        dependancesModules = emptyList(),
                        androidJar = plateforme.resolve("android.jar").apply { writeBytes(byteArrayOf(1)) }.path,
                        ignore = false,
                        raisonIgnore = null,
                        avertissements = emptyList(),
                        empreinte = "module-app",
                    ),
                ),
                "projet",
            ),
            null,
        )

        // 3. didOpen d'un layout réaliste (contient UNE APOSTROPHE
        //    française — le cas qui empoisonne la porte cel-ui ; le
        //    SERVEUR doit rester correct en dessous).
        val uri = "file://${racine.resolve("res/layout/activity_main.xml").path}"
        val texte =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:layout_width=\"match_parent\" android:layout_height=\"match_parent\">\n" +
                "    <TextView android:text=\"Nom d'utilisateur\" />\n" +
                "</LinearLayout>\n"
        serveur.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, texte)),
        )

        fun completionA(
            texteCourant: String,
            decalage: Int,
        ): List<String> {
            val paramsCompletion = CompletionParams()
            paramsCompletion.textDocument = TextDocumentIdentifier(uri)
            paramsCompletion.position = positionDe(texteCourant, decalage)
            val resultat = serveur.textDocumentService.completion(paramsCompletion).join()
            return resultat.right?.items?.map { it.label } ?: emptyList()
        }

        // 4a. Balise en frappe : « <Te » (remplacement du TextView).
        val texteB = texte.replace("    <TextView", "    <Te")
        serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(texteB)),
            ),
        )
        val decalageTe = texteB.indexOf("<Te") + 3
        assertEquals(listOf("TextView"), completionA(texteB, decalageTe), "balise <Te → TextView")

        // 4b. Valeur d'attribut en frappe : android:orientation="| — le
        //     curseur est DANS les guillemets (zone refusée par la
        //     porte cel-ui, légitime pour le serveur XML).
        val texteC =
            texte.replace(
                "android:layout_height=\"match_parent\">",
                "android:layout_height=\"match_parent\" android:orientation=\"\">",
            )
        serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 3),
                listOf(TextDocumentContentChangeEvent(texteC)),
            ),
        )
        val decalageValeur = texteC.indexOf("android:orientation=\"") + "android:orientation=\"".length
        val valeurs = completionA(texteC, decalageValeur)
        assertTrue(
            valeurs.containsAll(listOf("vertical", "horizontal")),
            "valeurs d'énumération dans les guillemes : $valeurs",
        )

        // 4c. Attribut en frappe : android:te| dans la balise TextView
        //     (SOUS l'apostrophe française).
        val texteD = texte.replace("<TextView android:text", "<TextView android:te")
        serveur.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 4),
                listOf(TextDocumentContentChangeEvent(texteD)),
            ),
        )
        val decalageAttribut = texteD.indexOf("android:te") + "android:te".length
        val attributs = completionA(texteD, decalageAttribut)
        assertTrue(
            attributs.contains("android:text"),
            "attribut android:text proposé sous l'apostrophe : $attributs",
        )
        assertFalse(attributs.isEmpty())

        // 4d. isIncomplete honnête (XP16) : une liste courte n'est PAS
        //     marquée incomplète.
        val listeTe =
            serveur.textDocumentService
                .completion(
                    CompletionParams(
                        TextDocumentIdentifier(uri),
                        positionDe(texteD, decalageTe.coerceAtMost(texteD.length)),
                    ),
                ).join()
                .right
        if ((listeTe?.items?.size ?: 0) < 60) {
            assertEquals(false, listeTe?.isIncomplete, "liste courte = complète")
        }
        facade.close()
    }
}
