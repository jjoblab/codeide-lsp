package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.appliquer
import jo.codeide.lsp.host.harnais.OutilsHarnais.assertPlage
import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeur
import jo.codeide.lsp.host.harnais.OutilsHarnais.modifier
import jo.codeide.lsp.host.harnais.OutilsHarnais.ouvrir
import jo.codeide.lsp.host.harnais.OutilsHarnais.positionDe
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SCÉNARIOS D'ÉDITION ET DE ROBUSTESSE.
 *
 * Groupes H (aide à la signature), I (actions de code / quick-fixes
 * d'import), J (formatage borné — ADR 0202), K (indices en ligne),
 * L (événements de fichiers externes), M (robustesse du protocole :
 * documents inconnus, positions hors bornes, messages inconnus).
 */
@Timeout(900)
@Suppress("LargeClass", "TooManyFunctions")
class ScenariosEditionTest {
    // ─────────────────────────────────────────────────────────────────
    // GROUPE H — Aide à la signature
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `H1 - signatureHelp - projet, parametre actif et labels prets`() {
        val connexion = demarrerServeur("h1")
        try {
            val texte =
                """
                fun plus(a: Int, b: Int): Int {
                    return a + b
                }
                fun principale() {
                    plus(1, 2)
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "h1.kt", texte)
            connexion.client.attendre(uri)
            // Curseur APRÈS la virgule du premier argument (le paramètre
            // sous le curseur est le SECOND, b).
            val curseur = positionDe(texte, texte.indexOf("plus(1,") + 7)
            val aide =
                connexion.distant.textDocumentService
                    .signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(aide.signatures, "des signatures sont attendues")
            assertTrue(aide.signatures.isNotEmpty(), "au moins une candidate")
            val signature = aide.signatures.first()
            assertTrue(signature.label.contains("plus"), "l'étiquette parle de la fonction: ${signature.label}")
            assertTrue(signature.label.contains("a: Int"), "paramètres dans l'étiquette: ${signature.label}")
            assertEquals(2, signature.parameters.size, "deux paramètres")
            assertEquals(0, aide.activeSignature, "signature active = la candidate")
            assertEquals(1, aide.activeParameter, "paramètre actif = b (curseur après la virgule)")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("H1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `H2 - signatureHelp de constructeur - candidate du projet`() {
        val connexion = demarrerServeur("h2")
        try {
            val texte =
                """
                class Boite(val taille: Int)
                fun f() {
                    val b = Boite(2)
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "h2.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("Boite(2"))
            val aide =
                connexion.distant.textDocumentService
                    .signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(aide.signatures.isNotEmpty(), "le constructeur doit être une candidate")
            val signature = aide.signatures.first()
            assertTrue(signature.label.contains("Boite"), "étiquette du constructeur: ${signature.label}")
            assertTrue(signature.label.contains("taille"), "le paramètre du constructeur: ${signature.label}")
            assertEquals(0, aide.activeParameter, "curseur sur le premier argument")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("H2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `H3 - signatureHelp hors appel - aide vide, jamais d exception`() {
        val connexion = demarrerServeur("h3")
        try {
            val texte = "val x = 1\n"
            val uri = ouvrir(connexion, "h3.kt", texte)
            connexion.client.attendre(uri)
            val aide =
                connexion.distant.textDocumentService
                    .signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(
                aide.signatures == null || aide.signatures.isEmpty(),
                "hors appel : AUCUNE candidate (sous-rapport D3)",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("H3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE I — Actions de code (quick-fixes d'import)
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `I1 - codeAction - un correctif par FQN candidate avec edition d import`() {
        val connexion = demarrerServeur("i1")
        try {
            // Classe de la PLATEFORME (android.jar) utilisée sans import —
            // miroir du scénario CodeIDE (Intent est LE cas d'usage réel).
            val texte = "val intention: Intent? = null\n"
            val uri = ouvrir(connexion, "i1.kt", texte)
            val diagnostic = connexion.client.unique(uri, "kt.unresolved")
            assertTrue(
                diagnostic.message.contains("Intent"),
                "le diagnostic vise Intent: ${diagnostic.message}",
            )
            val params =
                CodeActionParams(
                    TextDocumentIdentifier(uri),
                    diagnostic.range,
                    CodeActionContext(listOf(diagnostic)),
                )
            val reponse =
                connexion.distant.textDocumentService
                    .codeAction(params)
                    .get(60, TimeUnit.SECONDS)
            val actions = reponse.map { it.right }
            assertTrue(actions.isNotEmpty(), "au moins un correctif d'import attendu")
            assertTrue(actions.size <= 8, "plafond de 8 correctifs par diagnostic")
            val action = actions.first()
            assertEquals(CodeActionKind.QuickFix, action.kind, "kind QuickFix")
            assertTrue(
                action.title.contains("android.content.Intent"),
                "le titre porte le FQN: ${action.title}",
            )
            assertNotNull(action.edit, "l'action porte son WorkspaceEdit")
            val editions = action.edit.changes?.get(uri)
            assertNotNull(editions, "l'édition vise le document courant")
            assertTrue(
                editions!!.first().newText.startsWith("import android.content.Intent"),
                "l'édition insère l'import: ${editions.first().newText}",
            )
            assertEquals(
                0,
                editions
                    .first()
                    .range.start.line,
                "insertion en tête (aucun import existant)",
            )
            assertTrue(
                action.diagnostics.isNotEmpty(),
                "le correctif référence le diagnostic ciblé",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("I1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `I2 - codeAction - sans diagnostic kt_unresolved, AUCUN correctif`() {
        val connexion = demarrerServeur("i2")
        try {
            val texte = "import kotlin.math.PI\nval x = 1\n"
            val uri = ouvrir(connexion, "i2.kt", texte)
            val diagnostic = connexion.client.unique(uri, "kt.unusedImport")
            val params =
                CodeActionParams(
                    TextDocumentIdentifier(uri),
                    diagnostic.range,
                    CodeActionContext(listOf(diagnostic)),
                )
            val reponse =
                connexion.distant.textDocumentService
                    .codeAction(params)
                    .get(60, TimeUnit.SECONDS)
            assertTrue(reponse.isEmpty(), "un diagnostic non ciblé ne produit AUCUN correctif d'import")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("I2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE J — Formatage borné (ADR 0202)
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `J1 - formatting - re-indentation seule, contenu des lignes jamais reecrit`() {
        val connexion = demarrerServeur("j1")
        try {
            val texte = "class A {\nfun b() {\nprintln(1)\n}\n}\n"
            val uri = ouvrir(connexion, "j1.kt", texte)
            connexion.client.attendre(uri)
            val editions =
                connexion.distant.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true)))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(editions.isNotEmpty(), "des éditions de ré-indentation attendues")
            // Chaque édition ne remplace QUE la plaine de tête (le contenu reste).
            for (edition in editions) {
                assertTrue(
                    edition.newText.trim().isEmpty(),
                    "une édition de ré-indentation n'écrit que du blanc: ${edition.newText}",
                )
                assertTrue(edition.range.start.character == 0, "l'édition commence en tête de ligne")
            }
            val resultat = appliquer(texte, editions)
            assertEquals(
                "class A {\n    fun b() {\n        println(1)\n    }\n}\n",
                resultat,
                "ré-indentation canonique à 4 espaces",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("J1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `J2 - formatting idempotent - reformater un document formate ne change rien`() {
        val connexion = demarrerServeur("j2")
        try {
            val propre = "class A {\n    fun b() {\n        println(1)\n    }\n}\n"
            val uri = ouvrir(connexion, "j2.kt", "class A {\nfun b() {\nprintln(1)\n}\n}\n")
            val premiere =
                connexion.distant.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true)))
                    .get(60, TimeUnit.SECONDS)
            modifier(connexion, uri, propre, 2)
            val seconde =
                connexion.distant.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true)))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(
                seconde.isEmpty(),
                "le document déjà formaté ne produit AUCUNE édition (idempotence) — ${seconde.size} reçues",
            )
            assertTrue(premiere.isNotEmpty(), "le passage initial devait produire des éditions")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("J2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `J3 - formatting refuse honnetement un document en erreur de syntaxe`() {
        val connexion = demarrerServeur("j3")
        try {
            val uri = ouvrir(connexion, "j3.kt", "val x = (\n")
            val editions =
                connexion.distant.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true)))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(
                editions.isEmpty(),
                "document en erreur : liste VIDE (on ne reformate pas ce qu'on ne comprend pas)",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("J3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `J4 - formatting - chaînes brutes et KDoc intacts`() {
        val connexion = demarrerServeur("j4")
        try {
            val texte =
                "class A {\nfun b(): String {\nreturn \"\"\"brut\n   degueu\n   lignes\"\"\"\n}\n}\n"
            val uri = ouvrir(connexion, "j4.kt", texte)
            connexion.client.attendre(uri)
            val editions =
                connexion.distant.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true)))
                    .get(60, TimeUnit.SECONDS)
            val resultat = appliquer(texte, editions)
            val lignes = resultat.split("\n")
            // Les lignes INTERNES de la chaîne brute (lignes 3 et 4 du original) restent BYTES IDENTIQUES.
            assertEquals("   degueu", lignes.getOrNull(3), "ligne interne de chaîne brute intacte")
            assertEquals("   lignes\"\"\"", lignes.getOrNull(4), "ligne interne de chaîne brute intacte")
            // Le reste est ré-indenté.
            assertEquals("    fun b(): String {", lignes.getOrNull(1), "la fonction est ré-indentée")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("J4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `J5 - rangeFormatting - les editions ne sortent JAMAIS de la plage demandee`() {
        val connexion = demarrerServeur("j5")
        try {
            val texte = "class A {\nfun b() {\nprintln(1)\n}\n}\n"
            val uri = ouvrir(connexion, "j5.kt", texte)
            connexion.client.attendre(uri)
            val plageDemandee = Range(Position(1, 0), Position(3, 5))
            val editions =
                connexion.distant.textDocumentService
                    .rangeFormatting(
                        DocumentRangeFormattingParams(
                            TextDocumentIdentifier(uri),
                            FormattingOptions(4, true),
                            plageDemandee,
                        ),
                    ).get(60, TimeUnit.SECONDS)
            assertTrue(editions.isNotEmpty(), "des éditions dans la plage attendues")
            for (edition in editions) {
                assertTrue(
                    edition.range.start.line >= plageDemandee.start.line,
                    "édition hors plage (ligne ${edition.range.start.line} < ${plageDemandee.start.line})",
                )
                assertTrue(
                    edition.range.end.line <= plageDemandee.end.line,
                    "édition hors plage (ligne ${edition.range.end.line} > ${plageDemandee.end.line})",
                )
            }
            val resultat = appliquer(texte, editions)
            val lignes = resultat.split("\n")
            assertEquals("class A {", lignes[0], "hors plage : ligne 0 inchangée")
            assertEquals("}", lignes[4], "hors plage : ligne 4 inchangée")
            assertEquals("    fun b() {", lignes[1], "dans la plage : ré-indentée")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("J5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE K — Indices en ligne
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `K1 - inlayHint - noms de parametres aux appels positionnels prouves`() {
        val connexion = demarrerServeur("k1")
        try {
            val texte =
                """
                fun plus(a: Int, b: Int): Int {
                    return a + b
                }
                fun principale() {
                    plus(1, 2)
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "k1.kt", texte)
            connexion.client.attendre(uri)
            val indices =
                connexion.distant.textDocumentService
                    .inlayHint(InlayHintParams(TextDocumentIdentifier(uri), Range(Position(0, 0), Position(5, 1))))
                    .get(60, TimeUnit.SECONDS)
            val parametres = indices.filter { it.kind == InlayHintKind.Parameter }
            assertTrue(
                parametres.isNotEmpty(),
                "des indices de paramètres attendus — reçus: ${indices.map { it.label }}",
            )
            val libelles = parametres.map { it.label.left }
            assertTrue("a =" in libelles, "l'indice `a =` attendu — reçus: $libelles")
            assertTrue("b =" in libelles, "l'indice `b =` attendu — reçus: $libelles")
            val ligneAppel = positionDe(texte, texte.indexOf("plus(1, 2)")).line
            assertTrue(
                parametres.all { it.position.line == ligneAppel },
                "les indices vivent sur la ligne de l'appel",
            )
            // Les positions ordonnées : a avant b.
            val tries = parametres.sortedBy { it.position.character }
            assertEquals("a =", tries.first().label.left, "l'indice du premier paramètre vient en premier")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("K1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `K2 - inlayHint - types CERTAINS des declarations sans type ecrit`() {
        val connexion = demarrerServeur("k2")
        try {
            val texte =
                """
                fun principale() {
                    val entier = 1
                    val texteBrut = "bonjour"
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "k2.kt", texte)
            connexion.client.attendre(uri)
            val indices =
                connexion.distant.textDocumentService
                    .inlayHint(InlayHintParams(TextDocumentIdentifier(uri), Range(Position(0, 0), Position(3, 1))))
                    .get(60, TimeUnit.SECONDS)
            val types = indices.filter { it.kind == InlayHintKind.Type }
            val libelles = types.map { it.label.left }
            assertTrue(": Int" in libelles, "le littéral Int est CERTAIN — reçus: $libelles")
            assertTrue(": String" in libelles, "le littéral String est CERTAIN — reçus: $libelles")
            assertTrue(
                indices.none { it.kind == InlayHintKind.Parameter },
                "aucun indice de paramètre hors appel",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("K2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `K3 - inlayHint - plage vide et document inconnu rendent vide`() {
        val connexion = demarrerServeur("k3")
        try {
            val texte = "val x = 1\n"
            val uri = ouvrir(connexion, "k3.kt", texte)
            connexion.client.attendre(uri)
            val plageVide =
                connexion.distant.textDocumentService
                    .inlayHint(InlayHintParams(TextDocumentIdentifier(uri), Range(Position(0, 2), Position(0, 2))))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(plageVide.isEmpty(), "plage vide : liste vide")
            val inconnu =
                connexion.distant.textDocumentService
                    .inlayHint(
                        InlayHintParams(
                            TextDocumentIdentifier("file:///introuvable.kt"),
                            Range(Position(0, 0), Position(0, 5)),
                        ),
                    ).get(60, TimeUnit.SECONDS)
            assertTrue(inconnu.isEmpty(), "document inconnu : liste vide")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("K3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE L — Événements de fichiers externes
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `L1 - didChangeWatchedFiles - rafraichit les sources puis republie`() {
        val connexion = demarrerServeur("l1")
        try {
            val texte =
                """
                package jo.demo

                fun principale() {
                    val n = nouvelleFonction()
                }
                """.trimIndent() + "\n"
            val uri =
                ouvrir(
                    connexion,
                    "src/jo/demo/Audite.kt".also {
                        File(connexion.racine, it).writeText(texte)
                    },
                )
            val diagnostic = connexion.client.unique(uri, "kt.unresolved")
            assertTrue(
                diagnostic.message.contains("nouvelleFonction"),
                "le diagnostic vise nouvelleFonction: ${diagnostic.message}",
            )

            // Le fichier CIBLE apparaît sur le disque (modifié hors éditeur).
            val cheminCalcul = File(connexion.racine, "src/jo/demo/Calcul.kt")
            cheminCalcul.appendText("\nfun nouvelleFonction(): Int = 7\n")

            val compteAvant = connexion.client.publicationsDe(uri).size
            connexion.distant.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(
                    listOf(FileEvent(cheminCalcul.toURI().toString(), FileChangeType.Changed)),
                ),
            )
            // République après rafraîchissement : le diagnostic disparaît.
            val republication =
                connexion.client.attendre(
                    uri,
                ) { connexion.client.publicationsDe(uri).size > compteAvant }
            assertTrue(
                republication.diagnostics.none { it.code?.left == "kt.unresolved" },
                "après le rafraîchissement, nouvelleFonction RÉSOUT — reçus: ${republication.diagnostics.map {
                    it.code
                }}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("L1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `L2 - didChangeConfiguration - no-op silencieux, la connexion survit`() {
        val connexion = demarrerServeur("l2")
        try {
            connexion.distant.workspaceService.didChangeConfiguration(
                DidChangeConfigurationParams(mapOf("kotlin" to mapOf("langue" to "2.2"))),
            )
            val uri = ouvrir(connexion, "l2.kt", "val x = 1\n")
            connexion.client.attendre(uri)
            assertNotNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get(30, TimeUnit.SECONDS),
                "la connexion doit être vivante après didChangeConfiguration",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("L2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE M — Robustesse du protocole
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `M1 - requetes sur document inconnu - vides, aucune erreur, connexion vivante`() {
        val connexion = demarrerServeur("m1")
        try {
            val introuvable = TextDocumentIdentifier("file:///introuvable-m1.kt")
            assertNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(introuvable, Position(0, 0)))
                    .get(),
                "hover : null",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .definition(org.eclipse.lsp4j.DefinitionParams(introuvable, Position(0, 0)))
                    .get()
                    .left
                    .isEmpty(),
                "definition : vide",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .documentSymbol(org.eclipse.lsp4j.DocumentSymbolParams(introuvable))
                    .get()
                    .isEmpty(),
                "documentSymbol : vide",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .foldingRange(org.eclipse.lsp4j.FoldingRangeRequestParams(introuvable))
                    .get()
                    .isEmpty(),
                "foldingRange : vide",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .formatting(
                        DocumentFormattingParams(introuvable, FormattingOptions(4, true)),
                    ).get()
                    .isEmpty(),
                "formatting : vide",
            )
            // La connexion survit : un document réel répond toujours après.
            val uri = ouvrir(connexion, "m1.kt", "val x = 1\n")
            connexion.client.attendre(uri)
            assertNotNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get(),
                "la connexion est vivante après le déluge de requêtes inconnues",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("M1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `M2 - positions hors bornes - aucun plantage, reponses honnetes`() {
        val connexion = demarrerServeur("m2")
        try {
            val texte = "val x = 1\nval y = 2\n"
            val uri = ouvrir(connexion, "m2.kt", texte)
            connexion.client.attendre(uri)
            val loin = Position(999, 999)
            assertNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), loin))
                    .get(),
                "survol hors bornes : null",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .definition(
                        org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(uri), loin),
                    ).get()
                    .left
                    .isEmpty(),
                "définition hors bornes : vide",
            )
            assertTrue(
                connexion.distant.textDocumentService
                    .documentHighlight(
                        org.eclipse.lsp4j.DocumentHighlightParams(TextDocumentIdentifier(uri), loin),
                    ).get()
                    .isEmpty(),
                "surlignage hors bornes : vide",
            )
            // La connexion survit toujours.
            assertNotNull(
                connexion.distant.textDocumentService
                    .hover(
                        org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(0, 4)),
                    ).get(20, TimeUnit.SECONDS),
                "connexion vivante",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("M2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `M3 - messages inconnus du protocole - ignores, jamais de deconnexion`() {
        val connexion = demarrerServeur("m3")
        try {
            val uri = ouvrir(connexion, "m3.kt", "val x = 1\n")
            connexion.client.attendre(uri)
            // Notification $/ inconnue (framing LSP brut).
            envoyerBrut(connexion, """{"jsonrpc":"2.0","method":"$/harnaisSonde","params":{}}""")
            // Requête inconnue (le serveur répondra MethodNotFound — tolérable, pas de mort).
            envoyerBrut(
                connexion,
                """{"jsonrpc":"2.0","id":987654,"method":"harnais/sonde","params":{}}""",
            )
            Thread.sleep(500)
            assertTrue(connexion.processus.isAlive, "le serveur doit survivre aux messages inconnus")
            // La connexion typée fonctionne toujours.
            assertNotNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get(20, TimeUnit.SECONDS),
                "la connexion typée survit aux messages inconnus",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("M3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    /** Écrit un message JSON-RPC brut sur l'entrée du serveur (framing Content-Length). */
    private fun envoyerBrut(
        connexion: OutilsHarnais.Connexion,
        json: String,
    ) {
        val octets = json.toByteArray(Charsets.UTF_8)
        val flux = connexion.processus.outputStream
        synchronized(flux) {
            flux.write("Content-Length: ${octets.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flux.write(octets)
            flux.flush()
        }
    }
}
