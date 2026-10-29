package jo.codeide.lsp.host.harnais

import jo.codeide.lsp.host.harnais.OutilsHarnais.demarrerServeur
import jo.codeide.lsp.host.harnais.OutilsHarnais.ouvrir
import jo.codeide.lsp.host.harnais.OutilsHarnais.trop
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * HARNais d'audit — SONDES DIFFÉRENTIELLES d'investigation.
 *
 * Scénarios qui isolaient les comportements suspects repérés par la
 * première passe ; les cinq découvertes (D1–D5) sont CORRIGÉES par les
 * ADR 0203–0207 — les sondes verrouillent désormais le comportement
 * CORRIGÉ sur le transport réel (classpath stdlib + android.jar) :
 * surlignage same-file des top-level (0204), KDoc aux usages du
 * document courant (0207), preuve d'import pour kt.unresolved (0203),
 * alias JVM kotlin.String↔java.lang.String (0205), propriétés de
 * constructeur primaire (0206).
 */
@Timeout(600)
class ScenariosSondesTest {
    private fun compteSurlignages(
        connexion: OutilsHarnais.Connexion,
        uri: String,
        colonne: Int,
    ): Int =
        connexion.distant.textDocumentService
            .documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), Position(0, colonne)))
            .get(60, TimeUnit.SECONDS)
            .size

    @Test
    fun `S1 - usages same-file d une propriete top-level prouves (ADR 0204)`() {
        val connexion = demarrerServeur("s1")
        try {
            val uri = ouvrir(connexion, "s1.kt", "val cle = 1; val x = cle; val y = cle\n")
            connexion.client.attendre(uri)
            val nombre = compteSurlignages(connexion, uri, 4)
            println("SONDE S1 (top-level PPTÉ, usages même fichier) = $nombre")
            // ADR 0204 : le garde « homonyme top-level local » exclut la
            // cible ELLE-MÊME (identité uri + plage du nom) — les usages
            // same-file sont prouvés, symétriques du cross-file.
            assertEquals(3, nombre, "CORRIGÉ (ADR 0204) : déclaration + 2 usages")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S1 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S2 - idem S1 avec un emoji - le garde corrige, pas l emoji (ADR 0204)`() {
        val connexion = demarrerServeur("s2")
        try {
            val uri = ouvrir(connexion, "s2.kt", "val cle = \"😀\"; val x = cle; val y = cle\n")
            connexion.client.attendre(uri)
            val nombre = compteSurlignages(connexion, uri, 4)
            println("SONDE S2 (emoji + même ligne) = $nombre")
            assertEquals(3, nombre, "CORRIGÉ (ADR 0204) — comme S1, l'emoji n'y est pour rien")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S2 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S3 - idem avec emoji, usages sur lignes distinctes (ADR 0204)`() {
        val connexion = demarrerServeur("s3")
        try {
            val uri = ouvrir(connexion, "s3.kt", "val cle = \"😀\"\nval x = cle\nval y = cle\n")
            connexion.client.attendre(uri)
            val nombre = compteSurlignages(connexion, uri, 4)
            println("SONDE S3 (emoji, usages sur autres lignes) = $nombre")
            assertEquals(3, nombre, "CORRIGÉ (ADR 0204) — l'emoji n'y est pour rien")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S3 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario E2E
    // est UNE séquence d'aller-retours transport (survol déclaré, survol
    // usage, deux MARKDOWN vérifiés) inséparable sans perdre la lecture.
    @Suppress("LongMethod")
    fun `S4 - KDoc aux usages du document courant - miroir du nom declare (ADR 0207)`() {
        val connexion = demarrerServeur("s4")
        try {
            val texte =
                """
                /**
                 * Additionne les deux entiers.
                 */
                fun plus(a: Int, b: Int): Int {
                    return a + b
                }
                fun principale() {
                    plus(1, 2)
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "s4.kt", texte)
            connexion.client.attendre(uri)
            // a) Sur le NOM DÉCLARÉ : la KDoc est attachée (MARKDOWN).
            val curseurDeclaration = OutilsHarnais.positionDe(texte, texte.indexOf("fun plus") + 5)
            val survolDeclaration =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseurDeclaration))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(survolDeclaration, "survol sur le nom déclaré")
            assertEquals(
                org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                survolDeclaration.contents.right.kind,
                "survol du NOM DÉCLARÉ : MARKDOWN + KDoc (S2 documenté)",
            )
            assertTrue(
                survolDeclaration.contents.right.value
                    .contains("Additionne"),
                "la KDoc est rendue sur le nom déclaré",
            )
            // b) Sur l'USAGE, MÊME document : KDoc VIVE attachée
            // (ADR 0207 — miroir de a) ; les serveurs de référence
            // montrent la doc aux usages.
            val curseurUsage = OutilsHarnais.positionDe(texte, texte.indexOf("plus(1, 2)"))
            val survolUsage =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseurUsage))
                    .get(60, TimeUnit.SECONDS)
            assertNotNull(survolUsage, "survol sur l'usage")
            assertEquals(
                org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                survolUsage.contents.right.kind,
                "CORRIGÉ (ADR 0207) : usage dans le document courant = KDoc vive",
            )
            assertTrue(
                survolUsage.contents.right.value
                    .contains("Additionne"),
                "la KDoc est rendue sur l'usage du document courant",
            )
            assertTrue(
                survolUsage.contents.right.value
                    .contains("plus"),
                "la signature est rendue sur l'usage",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S4 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S5 - appelable du classpath par nom simple sans import - kt unresolved (ADR 0203)`() {
        val connexion = demarrerServeur("s5")
        try {
            val uri = ouvrir(connexion, "s5.kt", "fun f(): Double {\n    return PI\n}\n")
            val publication = connexion.client.attendre(uri)
            val unresolved = publication.diagnostics.filter { it.code?.left == "kt.unresolved" }
            println("SONDE S5 (PI sans import) — diagnostics kt.unresolved = ${unresolved.size}")
            // CORRIGÉ (ADR 0203) : la voie 6 exige la preuve de visibilité
            // (paquet courant, import exact, joker, paquets par défaut) —
            // `kotlin.math` n'en fait pas partie, kotlinc et le moteur
            // signalent ENFIN le même « unresolved reference: PI ».
            assertEquals(
                1,
                unresolved.size,
                "CORRIGÉ (ADR 0203) : PI sans import est signalé",
            )
            assertTrue(unresolved.single().message.contains("PI"), "le message nomme PI")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S5 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S6 - recepteur PARAMETRE String - membre declare rendu via l alias JVM (ADR 0205)`() {
        val connexion = demarrerServeur("s6")
        try {
            val texte = "fun f(s: String) {\n    s.length\n}\n"
            val uri = ouvrir(connexion, "s6.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("length"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            println("SONDE S6 (récepteur = paramètre s: String) survol = ${survol?.contents?.right?.value}")
            // CORRIGÉ (ADR 0205) : le maillon kotlin.String se lit via
            // l'alias java.lang.String — `length` déclare et rend, le
            // propriétaire reste le FQCN Kotlin.
            assertNotNull(survol, "le membre DÉCLARÉ de String doit résoudre (alias JVM)")
            assertTrue(
                survol.contents.right.value
                    .contains("length"),
                "le survol doit parler de length : ${survol.contents.right.value}",
            )
            assertTrue(
                survol.contents.right.value
                    .contains("kotlin.String"),
                "le propriétaire rendu reste le FQCN Kotlin : ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S6 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S7 - propriete de CONSTRUCTEUR - usage non qualifie prouve (ADR 0206)`() {
        val connexion = demarrerServeur("s7")
        try {
            val texte =
                """
                class Boite(private val contenu: Int) {
                    fun lire(): Int = contenu
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "s7.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("contenu") + 2)
            val surlignages =
                connexion.distant.textDocumentService
                    .documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
                    .size
            println("SONDE S7 (val de constructeur, usage non qualifié) = $surlignages")
            // CORRIGÉ (ADR 0206) : le scan émet la PROPRIÉTÉ membre
            // (conteneur renseigné) — miroir exact du membre de CORPS (S8).
            assertEquals(2, surlignages, "CORRIGÉ (ADR 0206) : déclaration + usage non qualifié")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S7 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S9 - String locale type ecrit, sans import explicite - membre declare rendu (ADR 0205)`() {
        val connexion = demarrerServeur("s9")
        try {
            val texte = "fun f() {\n    val s: String = \"bonjour\"\n    s.length\n}\n"
            val uri = ouvrir(connexion, "s9.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("length"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            println("SONDE S9 (String locale type écrit, SANS import) survol = ${survol?.contents?.right?.value}")
            assertNotNull(survol, "CORRIGÉ (ADR 0205) : length résout sans import écrit (imports par défaut)")
            assertTrue(
                survol.contents.right.value
                    .contains("length"),
                "le survol doit parler de length : ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S9 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S10 - String locale type ecrit, AVEC import explicite kotlin String - membre declare rendu (ADR 0205)`() {
        val connexion = demarrerServeur("s10")
        try {
            val texte = "import kotlin.String\nfun f() {\n    val s: String = \"bonjour\"\n    s.length\n}\n"
            val uri = ouvrir(connexion, "s10.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("length"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            println(
                "SONDE S10 (String locale type écrit, AVEC import kotlin.String) " +
                    "survol = ${survol?.contents?.right?.value}",
            )
            assertNotNull(survol, "CORRIGÉ (ADR 0205) : l'import explicite ne change rien — l'alias fait le travail")
            assertTrue(
                survol.contents.right.value
                    .contains("length"),
                "le survol doit parler de length : ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S10 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S11 - survol du TYPE String lui-meme - voie survolTypeClasspath retablie (ADR 0205)`() {
        val connexion = demarrerServeur("s11")
        try {
            val texte = "val s: String = \"a\"\n"
            val uri = ouvrir(connexion, "s11.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("String"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            println("SONDE S11 (survol du TYPE String) = ${survol?.contents?.right?.value}")
            // CORRIGÉ (ADR 0205, effet de bord positif) : membresDe
            // répond désormais pour kotlin.String — la voie
            // survolTypeClasspath n'est plus masquée par l'échec (la
            // sonde d'audit voyait la FONCTION stdlib String(...)).
            assertNotNull(survol, "le TYPE String doit survoler comme classe")
            assertTrue(
                survol.contents.right.value
                    .contains("kotlin.String"),
                "le survol du type porte son FQCN Kotlin : ${survol.contents.right.value}",
            )
            assertTrue(
                survol.contents.right.value
                    .contains("public class kotlin.String"),
                "la FICHE (ADR 0230) rend la CLASSE et ses qualifiants : ${survol.contents.right.value}",
            )
            assertTrue(
                survol.contents.right.value
                    .contains("Interfaces :"),
                "la fiche STRUCTURELLE accompagne la signature (ADR 0230) : ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S11 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S12 - survol du TYPE Intent importe - miroir android, la voie type est intacte`() {
        val connexion = demarrerServeur("s12")
        try {
            val texte = "import android.content.Intent\nval i: Intent? = null\n"
            val uri = ouvrir(connexion, "s12.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("Intent"))
            val survol =
                connexion.distant.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
            println("SONDE S12 (survol du TYPE Intent importé) = ${survol?.contents?.right?.value}")
            assertNotNull(survol, "le type importé d'android.jar survole (garde du cas non aliasé)")
            assertTrue(
                survol.contents.right.value
                    .contains("android.content.Intent"),
                "le FQCN complet est rendu : ${survol.contents.right.value}",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S12 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    fun `S8 - sonde occurrences - membre du CORPS utilise non qualifie (miroir F3)`() {
        val connexion = demarrerServeur("s8")
        try {
            val texte =
                """
                class Boite {
                    private val contenu: Int = 1
                    fun lire(): Int = contenu
                }
                """.trimIndent() + "\n"
            val uri = ouvrir(connexion, "s8.kt", texte)
            connexion.client.attendre(uri)
            val curseur = OutilsHarnais.positionDe(texte, texte.indexOf("= contenu") + 2)
            val surlignages =
                connexion.distant.textDocumentService
                    .documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), curseur))
                    .get(60, TimeUnit.SECONDS)
                    .size
            println("SONDE S8 (val du corps, usage non qualifié) = $surlignages")
            assertEquals(2, surlignages, "membre du corps : déclaration + usage (voie occurrenceMembreEnglobant)")
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S8 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }

    @Test
    // Exemption detekt ciblée (règle 16) : LongMethod — un scénario E2E
    // est UNE séquence d'aller-retours transport (KDoc disque, édition
    // du document tiers, re-survol) inséparable sans perdre la lecture.
    @Suppress("LongMethod")
    fun `S13 - KDoc TIERCE OUVERTE et modifiee - l arbre VIF gagne sur le disque (ADR 0208)`() {
        val connexion = demarrerServeur("s13")
        try {
            val uriCalcul = ouvrir(connexion, "src/jo/demo/Calcul.kt")
            val uriMain = ouvrir(connexion, "src/jo/demo/Main.kt")
            connexion.client.attendre(uriMain)
            val texteMain = java.io.File(connexion.racine, "src/jo/demo/Main.kt").readText()
            val curseur = OutilsHarnais.positionDe(texteMain, texteMain.indexOf("additionner(1, 2)"))

            fun survolUsage(): String {
                val survol =
                    connexion.distant.textDocumentService
                        .hover(HoverParams(TextDocumentIdentifier(uriMain), curseur))
                        .get(60, TimeUnit.SECONDS)
                assertNotNull(survol, "survol de l'usage additionner")
                return survol.contents.right.value
            }
            // a) Fichier déclarant FERMÉ : KDoc relue du DISQUE (ADR 0208).
            val avant = survolUsage()
            println("SONDE S13 (KDoc tierce, fichier fermé) = $avant")
            assertTrue(avant.contains("Additionne"), "KDoc disque rendue à l'usage: $avant")
            // b) Le document TIERS est OUVERT puis MODIFIÉ : sa KDoc
            //    VIVE remplace celle du disque (dominance des documents
            //    ouverts — jamais le disque périmé).
            val texteVif =
                java.io
                    .File(connexion.racine, "src/jo/demo/Calcul.kt")
                    .readText()
                    .replace("Additionne [a] et [b].", "Additionne [a] et [b] — VERSION VIVANTE.")
            OutilsHarnais.modifier(connexion, uriCalcul, texteVif, 2)
            connexion.client.attendre(uriCalcul)
            val apres = survolUsage()
            println("SONDE S13 (KDoc tierce, document ouvert édité) = $apres")
            assertTrue(
                apres.contains("VERSION VIVANTE"),
                "l'arbre VIF du document tiers gagne (ADR 0208 dominance): $apres",
            )
            assertTrue(
                !apres.contains("Additionne [a] et [b].") || apres.contains("VERSION VIVANTE"),
                "la KDoc périmée du disque n'est plus rendue telle quelle: $apres",
            )
            connexion.arreter()
        } catch (t: Throwable) {
            connexion.detruire()
            throw AssertionError("S13 échoué — stderr serveur :\n${connexion.stderr.trop}", t)
        }
    }
}
