package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.assertPlage
import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeur
import jo.codeide.lsp.host.harnais.OutilsHarnais.ouvrir
import jo.codeide.lsp.host.harnais.OutilsHarnais.positionDe
import jo.codeide.lsp.host.harnais.OutilsHarnais.superDefinitionParTransport
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SCÉNARIOS DE NAVIGATION.
 *
 * Groupes C (complétion), D (survol), E (définition / définition de
 * type / implémentations), F (références / surlignage / renommage),
 * G (symboles de document et d'espace / chaînes de sélection).
 */
@Timeout(900)
@Suppress("LargeClass", "TooManyFunctions", "DEPRECATION")
class ScenariosNavigationTest {
    /** Noms de la réponse workspace/symbol — forme GAUCHE ou DROITE. */
    @Suppress("DEPRECATION")
    private fun nomsSymboles(
        reponse: org.eclipse.lsp4j.jsonrpc.messages.Either<
            List<org.eclipse.lsp4j.SymbolInformation>,
            List<org.eclipse.lsp4j.WorkspaceSymbol>,
        >,
    ): List<String> = reponse.left?.map { it.name } ?: reponse.right?.map { it.name } ?: emptyList()

    // ─────────────────────────────────────────────────────────────────
    // GROUPE C — Complétion
    // ─────────────────────────────────────────────────────────────────

    @Test
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario E2E
    // est UNE séquence d'aller-retours transport (ouvrir, compléter,
    // vérifier) inséparable sans perdre la lecture du scénario.
    @Suppress("LongMethod")
    fun `C1 - completion membre apres point - membres projet, extensions stdlib, kinds`() {
        val connexion = demarrerServeur("c1")
        try {
            val texte =
                """
                class Boite {
                    var contenu = 1
                    fun ouvrir() {}
                }
                fun f() {
                    val b = Boite()
                    b.
                }
                """.trimIndent()
            val uri = ouvrir(connexion, "c1.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("b."))
            val reponse =
                connexion.distant.textDocumentService
                    .completion(
                        CompletionParams(
                            TextDocumentIdentifier(uri),
                            Position(
                                curseur.line,
                                curseur.character + 2,
                            ),
                        ),
                    ).get(60, TimeUnit.SECONDS)
            val liste = reponse.right
            // COMPORTEMENT OBSERVÉ : le serveur marque TOUTES ses listes
            // isIncomplete=true (choix assumé — cap ~100 et tri côté serveur
            // : le client DOIT re-demander à chaque frappe ; fwcd/jdt.ls
            // renvoient false). Consigné dans le rapport (observation O1).
            assertEquals(
                java.lang.Boolean.TRUE,
                liste.isIncomplete,
                "isIncomplete=true permanent (comportement consigné)",
            )
            val libelles = liste.items.map { it.label }
            assertTrue("contenu" in libelles, "membre `contenu` attendu — reçus: $libelles")
            assertTrue(
                libelles.any { it.startsWith("ouvrir") },
                "membre `ouvrir()` attendu (les fonctions portent leurs parenthèses) — reçus: $libelles",
            )
            // COMPORTEMENT OBSERVÉ : pour un récepteur de type PROJET, la
            // chaîne de supertypes (jusqu'à Any) n'est pas lisible (pas de
            // bytecode projet) — seuls les membres DÉCLARÉS du projet
            // remontent (toString/equals/hashCode absents). Sous-rapport
            // D3 documenté dans le rapport (observation O3).
            assertTrue(
                libelles.none { it in listOf("toString", "equals", "hashCode") },
                "récepteur projet : AUCUN membre hérité du stdlib (comportement consigné O3)",
            )
            assertEquals(
                CompletionItemKind.Field,
                liste.items.first { it.label == "contenu" }!!.kind,
                "kind de la propriété membre",
            )
            assertEquals(
                CompletionItemKind.Method,
                liste.items.first { it.label.startsWith("ouvrir") }!!.kind,
                "kind de la fonction membre",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `C2 - completion membre apres point-sur - meme regime que le point`() {
        val connexion = demarrerServeur("c2")
        try {
            val texte =
                """
                class Boite { var contenu = 1 }
                fun f() {
                    var b: Boite? = null
                    b?.
                }
                """.trimIndent()
            val uri = ouvrir(connexion, "c2.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("b?."))
            val reponse =
                connexion.distant.textDocumentService
                    .completion(
                        CompletionParams(TextDocumentIdentifier(uri), Position(curseur.line, curseur.character + 2)),
                    ).get(60, TimeUnit.SECONDS)
            val libelles = reponse.right.items.map { it.label }
            assertTrue("contenu" in libelles, "membre après ?. attendu — reçus: $libelles")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `C3 - completion de portee - locales, premier niveau, projet, mots-cles`() {
        val connexion = demarrerServeur("c3")
        try {
            val texte =
                """
                val GLOBALE = 1
                fun principale() {
                    val locale = 2
                    l
                    v
                }
                """.trimIndent()
            val uri = ouvrir(connexion, "c3.kt", texte)
            connexion.client.attendre(uri)
            // a) Au préfixe « l » : noms en portée (locales, top-level du
            // fichier, projet) — OBSERVÉ : les NOMS ne sont PAS filtrés par
            // préfixe côté serveur (seuls les mots-clés le sont).
            val curseur = positionDe(texte, texte.indexOf("l\n"))
            val reponse =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            val libelles = reponse.right.items.map { it.label }
            assertTrue("locale" in libelles, "la locale en portée doit être proposée — reçus: $libelles")
            assertTrue("GLOBALE" in libelles, "le top-level du fichier doit être proposé — reçus: $libelles")
            assertTrue(
                libelles.any { it.startsWith("additionner") },
                "les déclarations du PROJET (racines) doivent être proposées — reçus: $libelles",
            )
            // b) Curseur APRÈS le « v » (préfixe « v ») : les MOTS-CLÉS
            // filtrés par préfixe survivent au cap (LIMITE_PROPOSITIONS=100,
            // mots-clés en FIN de tri — au préfixe vide ils sont coupés).
            val curseurV = positionDe(texte, texte.indexOf("v\n") + 1)
            val reponseV =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), curseurV))
                    .get(60, TimeUnit.SECONDS)
            val libellesV = reponseV.right.items.map { it.label }
            assertTrue(
                "val" in libellesV && "var" in libellesV,
                "les mots-clés val/var au préfixe v — reçus: $libellesV",
            )
            assertTrue(
                reponseV.right.items.any { it.kind == CompletionItemKind.Keyword },
                "au moins un item de kind Keyword",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `C4 - completion auto-import - FQN du classpath avec edition additionnelle`() {
        val connexion = demarrerServeur("c4")
        try {
            val texte =
                """
                fun f(): Double {
                    return PI
                }
                """.trimIndent()
            val uri = ouvrir(connexion, "c4.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("PI"))
            val reponse =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            val pi = reponse.right.items.firstOrNull { it.label == "PI" }
            assertNotNull(pi, "PI (kotlin.math) doit être proposé — reçus: ${reponse.right.items.map { it.label }}")
            assertNotNull(pi!!.additionalTextEdits, "l'auto-import doit porter additionalTextEdits")
            val edition = pi!!.additionalTextEdits.first()
            assertEquals(0, edition.range.start.line, "l'import s'insère en tête du fichier (aucun import existant)")
            assertTrue(
                edition.newText.startsWith("import kotlin.math.PI"),
                "l'édition insère l'import kotlin.math.PI — reçue: ${edition.newText}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `C5 - completion de paquet dans import - sous-paquets apres kotlin`() {
        val connexion = demarrerServeur("c5")
        try {
            val texte = "import kotlin.co\n"
            val uri = ouvrir(connexion, "c5.kt", texte)
            connexion.client.attendre(uri)
            val reponse =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(0, 16)))
                    .get(60, TimeUnit.SECONDS)
            val libelles = reponse.right.items.map { it.label }
            assertTrue(
                libelles.any { it.contains("collections") },
                "les sous-paquets de kotlin doivent être proposés — reçus: $libelles",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `C6 - completion aux limites - offset 0, fin de fichier, document inconnu, position hors bornes`() {
        val connexion = demarrerServeur("c6")
        try {
            val uri = ouvrir(connexion, "c6.kt", "fun f() { }\n")
            connexion.client.attendre(uri)
            // Offset 0
            val tete =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(0, 0)))
                    .get()
            assertTrue(tete.right.items.isNotEmpty(), "completion en (0,0) : mots-clés attendus")
            // Fin de fichier
            val fin =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 0)))
                    .get()
            assertNotNull(fin, "completion en fin de fichier ne doit pas échouer")
            // Document inconnu : liste vide, pas d'erreur de protocole
            val inconnu =
                connexion.distant.textDocumentService
                    .completion(
                        CompletionParams(TextDocumentIdentifier("file:///introuvable.kt"), Position(0, 0)),
                    ).get()
            assertTrue(inconnu.right.items.isEmpty(), "document inconnu : complétion vide")
            // Position hors bornes : pas d'exception
            val horsBornes =
                connexion.distant.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(99, 99)))
                    .get()
            assertNotNull(horsBornes, "position hors bornes ne doit pas lever")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("C6 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE D — Survol
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `D1 - survol declaration projet avec KDoc - MARKDOWN, signature et documentation`() {
        val connexion = demarrerServeur("d1")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("additionner(1, 2)"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(survol, "le survol de additionner ne doit pas être null")
            // ADR 0208 (option 2 de l'ADR 0207) : la KDoc d'usage est
            // attachée AUX FICHIERS AUSSI — la déclaration vit dans
            // Calcul.kt (fichier FERMÉ du scan) : le port de la session
            // relit sa déclaration sur disque (lecture bornée, UNE fois
            // par survol) et la KDoc accompagne l'usage, miroir du
            // document courant (S2) et des serveurs de référence.
            assertEquals(
                org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                survol.contents.right.kind,
                "CORRIGÉ (ADR 0208) : usage cross-fichier = KDoc relue du fichier déclarant",
            )
            val contenu = survol.contents.right.value
            assertTrue(contenu.contains("additionner"), "la signature doit être présente: $contenu")
            assertTrue(
                contenu.contains("Additionne"),
                "la KDoc TIERCE (fichier fermé) est rendue à l'usage: $contenu",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("D1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `D2 - survol sans KDoc - PLAINTEXT et signature seule`() {
        val connexion = demarrerServeur("d2")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("val total") + 6)
            // `total` est une locale sans KDoc → PLAINTEXT
            val survol =
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(survol, "survol de la locale attendu")
            assertEquals(org.eclipse.lsp4j.MarkupKind.PLAINTEXT, survol.contents.right.kind, "kind PLAINTEXT sans KDoc")
            assertTrue(
                survol.contents.right.value
                    .isNotBlank(),
                "une signature doit être rendue",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("D2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `D3 - survol membre du classpath - import explicite, recepteur local type ecrit`() {
        val connexion = demarrerServeur("d3")
        try {
            // Miroir EXACT de la fixture moteur « survol - membre qualifié
            // hérité (propriétaire classpath) » : import explicite + locale
            // au type écrit. Les variantes String/paramètre sont sondées
            // (S6, S10) et consignées dans le rapport (Découverte 5).
            val texte =
                """
                import android.widget.TextView
                fun f() {
                    val v: TextView = creer()
                    v.requestLayout()
                }
                fun creer(): TextView = TODO()
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "d3.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("requestLayout"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(survol, "le membre classpath `requestLayout` doit résoudre (vue JVM)")
            assertTrue(
                survol.contents.right.value
                    .contains("requestLayout"),
                "le survol doit parler du membre: ${survol.contents.right.value}",
            )
            assertTrue(
                survol.contents.right.value
                    .contains("android.view.View"),
                "le PROPRIÉTAIRE hérité est nommé (android.view.View): ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("D3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `D4 - survol document inconnu et position hors bornes - null sans exception`() {
        val connexion = demarrerServeur("d4")
        try {
            val survolInconnu =
                connexion.distant.textDocumentService
                    .hover(
                        org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier("file:///introuvable.kt"), Position(0, 0)),
                    ).get()
            assertNull(survolInconnu, "document inconnu : survol null")
            val uri = ouvrir(connexion, "d4.kt", "val x = 1\n")
            connexion.client.attendre(uri)
            val horsBornes =
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(50, 50)))
                    .get()
            assertNull(horsBornes, "position hors bornes : survol null")
            // La connexion doit être ENCORE VIVANTE.
            assertNotNull(
                connexion.distant.textDocumentService
                    .hover(org.eclipse.lsp4j.HoverParams(TextDocumentIdentifier(uri), Position(0, 4)))
                    .get(),
                "survol sur `x` après les cas limites",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("D4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE E — Définition, définition de type, implémentations
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `E1 - definition meme fichier - la plage vise le nom declare`() {
        val connexion = demarrerServeur("e1")
        try {
            val texte = "fun f() {\n    val cible = 1\n    val y = cible\n}\n"
            val uri = ouvrir(connexion, "e1.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.lastIndexOf("cible"))
            val reponse =
                connexion.distant.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            val lieux = reponse.left
            assertEquals(1, lieux.size, "une seule définition attendue")
            assertPlage((1 to 8) to (1 to 13), lieux.first().range, "plage du nom déclaré `cible`")
            assertEquals(uri, lieux.first().uri, "même document")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `E2 - definition multi-fichiers - vise le fichier FERME des racines sources`() {
        val connexion = demarrerServeur("e2")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("additionner(1, 2)"))
            val reponse =
                connexion.distant.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            val lieux = reponse.left
            assertEquals(1, lieux.size, "une définition attendue (Calcul.kt)")
            assertTrue(lieux.first().uri.endsWith("Calcul.kt"), "la cible vit dans Calcul.kt")
            val texteCible = File(connexion.racine, "src/jo/demo/Calcul.kt").readText()
            val positionNom = positionDe(texteCible, texteCible.indexOf("fun additionner") + 4)
            assertPlage(
                (positionNom.line to positionNom.character) to
                    (positionNom.line to positionNom.character + 11),
                lieux.first().range,
                "la plage vise le NOM `additionner` (11 caractères, pas le fun)",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `E3 - definition cible classpath - VIDE (sans source attachee, D3 documente)`() {
        val connexion = demarrerServeur("e3")
        try {
            val texte = "fun f(s: String) {\n    s.length\n}\n"
            val uri = ouvrir(connexion, "e3.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("length"))
            val reponse =
                connexion.distant.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertTrue(reponse.left.isEmpty(), "classpath sans source : définition VIDE (sous-rapport D3)")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `E4 - definition de type - la declaration du type ecrit`() {
        val connexion = demarrerServeur("e4")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("forme: Forme") + 9)
            val reponse =
                connexion.distant.textDocumentService
                    .typeDefinition(TypeDefinitionParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            val lieux = reponse.left
            assertEquals(1, lieux.size, "typeDefinition : la déclaration de Forme")
            assertTrue(lieux.first().uri.endsWith("Calcul.kt"), "la cible vit dans Calcul.kt")
            // La plage doit viser le NOM `Forme` de la déclaration.
            val texteCible = File(connexion.racine, "src/jo/demo/Calcul.kt").readText()
            val attendu = OutilsHarnais.positionDe(texteCible, texteCible.indexOf("interface Forme"))
            assertEquals(
                attendu.line,
                lieux
                    .first()
                    .range.start.line,
                "ligne du nom `Forme`",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `E5 - implementations - heritiers prouves par FQCN, homonymes exclus`() {
        val connexion = demarrerServeur("e5")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("Forme = Cercle"))
            val reponse =
                connexion.distant.textDocumentService
                    .implementation(
                        org.eclipse.lsp4j.ImplementationParams(TextDocumentIdentifier(uri), curseur),
                    ).get(60, TimeUnit.SECONDS)
            val lieux = reponse.left
            val nomsFichiers = lieux.map { it.uri.substringAfterLast('/') }
            assertTrue("Calcul.kt" in nomsFichiers, "les héritiers jo.demo vivent dans Calcul.kt")
            assertTrue(lieux.size >= 2, "Cercle ET Carre attendus — reçus: $nomsFichiers")
            assertTrue(
                "Homonymes.kt" !in nomsFichiers,
                "l'homonyme autre.Rond (Forme SANS preuve d'import jo.demo) " +
                    "ne doit JAMAIS être listé — reçus: $nomsFichiers",
            )
            // Les plages visent les NOMS déclarants (Cercle, Carre) dans Calcul.kt.
            val texteCible = File(connexion.racine, "src/jo/demo/Calcul.kt").readText()
            val caracteres = lieux.map { it.range.start.character }.sorted()
            val attendus =
                listOf("class Cercle", "class Carre")
                    .map { OutilsHarnais.positionDe(texteCible, texteCible.indexOf(it) + 6).character }
                    .sorted()
            assertEquals(attendus, caracteres, "les plages visent les noms des classes dérivées")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario E2E
    // est UNE séquence d'aller-retours transport (méthode, propriété,
    // capability, capacité vide sur classe racine) inséparable.
    @Suppress("LongMethod")
    fun `E9 - superDefinition par le transport - membre outrepassant, miroir du LSP Java (ADR 0210)`() {
        // initialize UNE SEULE fois (spéc LSP) avec la réponse capturée —
        // la négociation de demarrerServeur est différée ici.
        val connexion = demarrerServeur("e9", initialiser = false)
        try {
            val params = org.eclipse.lsp4j.InitializeParams()
            params.workspaceFolders =
                listOf(org.eclipse.lsp4j.WorkspaceFolder(connexion.racine.toURI().toString(), "harnais"))
            // a) La capability experimental annonce le provider (clé que
            //    le client cel-lsp y recherche — pattern jdt.ls). Le
            //    champ est un JsonElementTypeAdapter lsp4j : après le
            //    transport c'est un JsonObject (pas une Map).
            val capacites =
                connexion.distant
                    .initialize(params)
                    .get(120, TimeUnit.SECONDS)
                    .capabilities
            connexion.distant.initialized(org.eclipse.lsp4j.InitializedParams())
            val experimental = capacites.experimental as? com.google.gson.JsonObject
            assertTrue(
                experimental?.get("superDefinitionProvider")?.asBoolean == true,
                "superDefinitionProvider annoncé dans experimental: $experimental",
            )
            // b) Caret sur la DÉCLARATION OUTREPASSANTE « aire » de Cercle
            //    (Calcul.kt ouvert) : le même-nommé du supertype projet
            //    Forme — appel TYPÉ sur le proxy étendu, miroir du
            //    câble cel-lsp (CodeIdeLanguageServer.superDefinition).
            val uri = ouvrir(connexion, "src/jo/demo/Calcul.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Calcul.kt").readText()
            val curseurAire = positionDe(texte, texte.indexOf("override fun aire()") + "override fun ".length)
            val ciblesAire = superDefinitionParTransport(connexion, uri, curseurAire)
            assertEquals(1, ciblesAire.size, "UNE cible : aire de Forme — reçues: $ciblesAire")
            val cibleAire = ciblesAire.single()
            assertEquals(
                File(connexion.racine, "src/jo/demo/Calcul.kt").absolutePath,
                cibleAire.path,
                "chemin ABSOLU du fichier déclarant (Forme vit dans Calcul.kt)",
            )
            assertEquals("aire  ·  Forme", cibleAire.label, "libellé picker (miroir ecj)")
            assertEquals(
                texte.indexOf("fun aire()") + "fun ".length,
                cibleAire.start,
                "offset du NOM aire dans l'interface Forme",
            )
            // c) La PROPRIÉTÉ outrepassante « nom » : même mécanique.
            val curseurNom = positionDe(texte, texte.indexOf("override val nom") + "override val ".length)
            val ciblesNom = superDefinitionParTransport(connexion, uri, curseurNom)
            assertEquals(1, ciblesNom.size, "UNE cible : nom de Forme — reçues: $ciblesNom")
            assertEquals("nom  ·  Forme", ciblesNom.single().label, "libellé picker propriété")
            // d) Garde : la classe racine (Forme — aucune clause de
            //    supertype ÉCRITE) ne rend AUCUNE cible sur son propre
            //    membre (l'option Super n'apparaît pas, jamais un faux
            //    jalon).
            val curseurPerimetre = positionDe(texte, texte.indexOf("fun perimetre()") + "fun ".length)
            val ciblesPerimetre = superDefinitionParTransport(connexion, uri, curseurPerimetre)
            assertTrue(
                ciblesPerimetre.none { it.label == "perimetre  ·  Forme" },
                "perimetre n'est PAS déclaré dans un supertype : $ciblesPerimetre",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("E9 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE F — Références, surlignage, renommage
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `F1 - references - usages seuls puis declaration incluse`() {
        val connexion = demarrerServeur("f1")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("additionner(1, 2)"))

            val parametres = ReferenceParams()
            parametres.textDocument = TextDocumentIdentifier(uri)
            parametres.position = curseur
            parametres.context = ReferenceContext(false)
            val usages =
                connexion.distant.textDocumentService
                    .references(parametres)
                    .get(60, TimeUnit.SECONDS)
            assertEquals(1, usages.size, "sans déclaration : l'unique appel de Main.kt")
            assertTrue(usages.first().uri.endsWith("Main.kt"), "l'usage vit dans Main.kt")

            val avecDeclaration = ReferenceParams()
            avecDeclaration.textDocument = TextDocumentIdentifier(uri)
            avecDeclaration.position = curseur
            avecDeclaration.context = ReferenceContext(true)
            val complet =
                connexion.distant.textDocumentService
                    .references(avecDeclaration)
                    .get(60, TimeUnit.SECONDS)
            assertEquals(2, complet.size, "déclaration + usage")
            assertTrue(complet.first().uri.endsWith("Calcul.kt"), "la DÉCLARATION en tête (Calcul.kt)")
            assertTrue(complet.last().uri.endsWith("Main.kt"), "puis l'usage (Main.kt)")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `F2 - references d une locale - fichier declarant seul`() {
        val connexion = demarrerServeur("f2")
        try {
            val texte = "fun f() {\n    val locale = 1\n    val a = locale\n    val b = locale\n}\n"
            val uri = ouvrir(connexion, "f2.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("val a = locale") + 9)
            val parametres = ReferenceParams()
            parametres.textDocument = TextDocumentIdentifier(uri)
            parametres.position = curseur
            parametres.context = ReferenceContext(true)
            val lieux =
                connexion.distant.textDocumentService
                    .references(parametres)
                    .get(60, TimeUnit.SECONDS)
            assertEquals(3, lieux.size, "déclaration + 2 usages, TOUS dans le fichier déclarant")
            assertTrue(lieux.all { it.uri == uri }, "une locale ne traverse pas les fichiers")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `F3 - surlignage document - declaration Write, usages Read, homonymes exclus`() {
        val connexion = demarrerServeur("f3")
        try {
            val texte =
                """
                class Cercle {
                    private val rayon: Double = 1.0
                    fun aire(): Double = 3.14159 * rayon * rayon
                }
                fun autre(rayon: Int) = rayon
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "f3.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf("* rayon") + 2)
            val surlignages =
                connexion.distant.textDocumentService
                    .documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            assertEquals(3, surlignages.size, "déclaration + 2 usages du rayon DU CERCLE (membre du corps)")
            assertEquals(
                DocumentHighlightKind.Write,
                surlignages.first { it.range.start.character < 40 }.kind,
                "la déclaration est en Write",
            )
            assertTrue(
                surlignages.filter { it.range.start.character >= 40 }.all { it.kind == DocumentHighlightKind.Read },
                "les usages sont en Read",
            )
            // L'HOMONYME (paramètre de `autre`) ne doit PAS être surligné : il vit ligne 4.
            assertTrue(
                surlignages.none { it.range.start.line == 4 },
                "l'homonyme sans preuve de résolution reste hors surlignage",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `F4 - prepareRename - plage exacte, placeholder, null hors cible`() {
        val connexion = demarrerServeur("f4")
        try {
            val texte = "val cible = 1\nval y = cible\n"
            val uri = ouvrir(connexion, "f4.kt", texte)
            connexion.client.attendre(uri)
            // Caret AU MILIEU du nom (le contrat vise le jeton, pas une heuristique).
            val milieu = positionDe(texte, texte.indexOf("val y = cible") + 9 + 2)
            val reponse =
                connexion.distant.textDocumentService
                    .prepareRename(PrepareRenameParams(TextDocumentIdentifier(uri), milieu))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(reponse, "prepareRename au milieu du nom doit réussir")
            val resultat = reponse.second
            assertEquals("cible", resultat.placeholder, "placeholder = nom courant")
            assertPlage((0 to 4) to (0 to 9), resultat.range, "plage EXACTE du nom déclaré")
            // Hors cible (blanc) : null, la boîte cliente refuse proprement.
            val blanc = positionDe(texte, texte.indexOf('\n'))
            val hors =
                connexion.distant.textDocumentService
                    .prepareRename(PrepareRenameParams(TextDocumentIdentifier(uri), blanc))
                    .get(60, TimeUnit.SECONDS)
            assertNull(hors, "prepareRename sur un blanc doit être null")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `F5 - rename multi-fichiers - document ouvert ET fichier ferme du disque`() {
        val connexion = demarrerServeur("f5")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = positionDe(texte, texte.indexOf("additionner(1, 2)"))
            val edition =
                connexion.distant.textDocumentService
                    .rename(RenameParams(TextDocumentIdentifier(uri), curseur, "additioner"))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(edition.changes, "forme legacy `changes` (client non déclarant)")
            assertTrue("Calcul.kt" in edition.changes.keys.joinToString(), "la DÉCLARATION (fichier fermé) est éditée")
            assertTrue(uri in edition.changes.keys, "l'USAGE (document ouvert) est éditée")
            val toutes = edition.changes.values.flatten()
            assertTrue(toutes.all { it.newText == "additioner" }, "toutes les éditions portent le nouveau nom")
            assertTrue(toutes.size >= 2, "déclaration + usage au minimum")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `F6 - rename sans cible - erreur typée RequestFailed avec data renommage_sansCible`() {
        val connexion = demarrerServeur("f6")
        try {
            val texte = "val x = 1 + 1\n"
            val uri = ouvrir(connexion, "f6.kt", texte)
            connexion.client.attendre(uri)
            val curseur = positionDe(texte, texte.indexOf('+'))
            val parametres = RenameParams(TextDocumentIdentifier(uri), curseur, "autre")
            val future = connexion.distant.textDocumentService.rename(parametres)
            try {
                future.get(30, TimeUnit.SECONDS)
                throw AssertionError("le renommage hors cible doit ÉCHOUER (jamais un WorkspaceEdit silencieux)")
            } catch (echec: ExecutionException) {
                val erreur = echec.cause
                assertTrue(erreur is ResponseErrorException, "erreur JSON-RPC typée attendue — reçue: $erreur")
                assertEquals(
                    -32803,
                    (erreur as ResponseErrorException).responseError.code,
                    "code RequestFailed (-32803)",
                )
                val donnees = erreur.responseError.data
                // La représentation de `data` varie côté client LSP4J
                // (Map, JsonElement ou String selon le chemin de
                // désérialisation) : la preuve porte sur le CONTENU.
                val texteDonnees = donnees.toString()
                assertTrue(
                    texteDonnees.contains("\"code\"") && texteDonnees.contains("renommage.sansCible"),
                    "data.code typé — reçue: $texteDonnees",
                )
                assertTrue(texteDonnees.contains("f6.kt"), "data.uri — reçue: $texteDonnees")
            }
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("F6 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROUPE G — Symboles et sélection
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `G1 - documentSymbol - arbre hierarchique, kinds et selectionRange du nom`() {
        val connexion = demarrerServeur("g1")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Calcul.kt")
            connexion.client.attendre(uri)
            val symboles =
                connexion.distant.textDocumentService
                    .documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
                    .get(60, TimeUnit.SECONDS)
            val racines = symboles.map { it.right }
            assertTrue(racines.isNotEmpty(), "des symboles de premier niveau attendus")
            val noms = racines.map { it.name }
            assertTrue("additionner" in noms, "fonction top-level — reçus: $noms")
            assertTrue("Forme" in noms, "interface top-level — reçus: $noms")
            assertTrue("Cercle" in noms, "classe top-level — reçus: $noms")
            assertTrue("CONSTANTE_GLOBALE" in noms, "const top-level — reçus: $noms")
            assertEquals(SymbolKind.Method, racines.first { it.name == "additionner" }.kind, "FONCTION → Method")
            assertEquals(SymbolKind.Interface, racines.first { it.name == "Forme" }.kind, "INTERFACE → Interface")
            assertEquals(SymbolKind.Class, racines.first { it.name == "Cercle" }.kind, "CLASSE → Class")
            assertEquals(
                SymbolKind.Property,
                racines.first { it.name == "CONSTANTE_GLOBALE" }.kind,
                "PROPRIETE → Property",
            )
            val cercle = racines.first { it.name == "Cercle" }
            assertTrue(cercle.children.isNotEmpty(), "Cercle porte ses membres (aire, nom, perimetre)")
            assertTrue(cercle.children.any { it.name == "aire" }, "membre aire")
            assertTrue(cercle.children.any { it.name == "perimetre" }, "membre perimetre")
            // selectionRange = le NOM seul, range = la déclaration ENTIÈRE
            // (comparaison LEXICOGRAPHIQUE (ligne, colonne) — jamais les
            // colonnes seules entre lignes différentes).
            val debutCercle = cercle.range.start
            val finCercle = cercle.range.end
            val debutNom = cercle.selectionRange.start
            val finNom = cercle.selectionRange.end

            fun avantOuEgal(
                a: Position,
                b: Position,
            ) = a.line < b.line || (a.line == b.line && a.character <= b.character)
            assertTrue(avantOuEgal(debutCercle, debutNom), "range commence avant le nom")
            assertTrue(avantOuEgal(finNom, finCercle), "range finit après le nom")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("G1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `G2 - workspace symbol - requete contient insensible a la casse, multi-fichiers`() {
        val connexion = demarrerServeur("g2")
        try {
            val minuscule =
                connexion.distant.workspaceService
                    .symbol(WorkspaceSymbolParams("cerc"))
                    .get(60, TimeUnit.SECONDS)
            // La désérialisation Either côté client peut rendre la forme
            // droite (WorkspaceSymbol) — les DEUX portent le nom.
            val nomsCerc = nomsSymboles(minuscule)
            assertTrue(nomsCerc.isNotEmpty(), "« cerc » doit trouver Cercle")
            assertTrue("Cercle" in nomsCerc, "Cercle exact")
            minuscule.left?.let { informations ->
                assertTrue(
                    informations.all { it.location.uri.endsWith(".kt") },
                    "des URI de fichier",
                )
            }

            val majuscule =
                connexion.distant.workspaceService
                    .symbol(WorkspaceSymbolParams("CERC"))
                    .get(60, TimeUnit.SECONDS)
            val nomsMajuscule = nomsSymboles(majuscule)
            assertTrue(
                "Cercle" in nomsMajuscule,
                "« CERC » (insensible à la casse) doit trouver Cercle",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("G2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `G3 - workspace symbol - requete vide borne, locales JAMAIS listees`() {
        val connexion = demarrerServeur("g3")
        try {
            val tout =
                connexion.distant.workspaceService
                    .symbol(WorkspaceSymbolParams(""))
                    .get(60, TimeUnit.SECONDS)
            val noms = nomsSymboles(tout)
            assertTrue(noms.isNotEmpty(), "requête vide = tout le projet (plafonné)")
            assertTrue(noms.size <= 200, "le plafond de la palette est respecté (${noms.size} résultats)")
            assertTrue("total" !in noms, "la LOCALE de principal() ne remonte jamais")
            assertTrue("Cercle" in noms, "les déclarations du projet remontent")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("G3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `G4 - selectionRange - chaine des nœuds englobants, plusieurs positions en ordre`() {
        val connexion = demarrerServeur("g4")
        try {
            val uri = ouvrir(connexion, "src/jo/demo/Calcul.kt")
            connexion.client.attendre(uri)
            val texte = File(connexion.racine, "src/jo/demo/Calcul.kt").readText()
            val positionA = positionDe(texte, texte.indexOf("return a + b") + 7)
            val positionFonction = positionDe(texte, texte.indexOf("fun additionner") + 5)
            val reponse =
                connexion.distant.textDocumentService
                    .selectionRange(
                        SelectionRangeParams(
                            TextDocumentIdentifier(uri),
                            listOf(positionA, positionFonction),
                        ),
                    ).get(60, TimeUnit.SECONDS)
            assertEquals(2, reponse.size, "une chaîne PAR position, dans l'ordre")
            val chaineA = reponse.first()
            // La chaîne croît STRICTEMENT : chaque maillon englobe le précédent.
            var maillon = chaineA
            var compteur = 0
            while (maillon.parent != null) {
                val parent = maillon.parent!!
                assertTrue(
                    parent.range.start.line < maillon.range.start.line ||
                        (
                            parent.range.start.line == maillon.range.start.line &&
                                parent.range.start.character <= maillon.range.start.character
                        ),
                    "le parent englobe strictement l'enfant",
                )
                assertTrue(
                    parent.range.end.line > maillon.range.end.line ||
                        (
                            parent.range.end.line == maillon.range.end.line &&
                                parent.range.end.character >= maillon.range.end.character
                        ),
                    "le parent déborde à droite de l'enfant",
                )
                maillon = parent
                compteur++
            }
            assertTrue(compteur >= 3, "une chaîne profonde (au moins 4 maillons) — reçue: ${compteur + 1}")
            // Le DERNIER maillon couvre le fichier entier.
            assertTrue(
                maillon.range.start.line == 0 && maillon.range.start.character == 0,
                "la racine commence au fichier",
            )
            // L'ordre de la requête est conservé : la chaîne du second
            // curseur ENGLEOBE sa position (les nœuds du modèle du moteur
            // ne descendent pas au jeton de nom — comportement consigné).
            val chaineFonction = reponse.last()

            fun contient(
                r: Range,
                p: Position,
            ) = (r.start.line < p.line || (r.start.line == p.line && r.start.character <= p.character)) &&
                (p.line < r.end.line || (p.line == r.end.line && p.character <= r.end.character))
            assertTrue(
                contient(chaineFonction.range, positionFonction),
                "la chaîne du second curseur englobe sa position",
            )
            assertTrue(chaineFonction.parent != null, "la chaîne du second curseur a un parent")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("G4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }
}
