package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCTION des COMPANION OBJECTS (ADR 0222) — le quatrième monde
 * Kotlin pour la parité Android Studio : `Boite.creer()` (membres du
 * compagnon accessibles par le NOM de la classe), compagnons ANONYMES
 * (« Companion » effectif — le repli générique nomJeton() lisait le
 * PREMIER MEMBRE : le compagnon était enregistré sous « creer » et ses
 * membres sous « pkg.Boite.creer »), compagnons NOMMÉS, this implicite
 * du corps, et le COMPANION DU CLASSPATH (`fqcn$Companion` —
 * `Regex.escape`, `UInt.MAX_VALUE` sur le stdlib réel).
 *
 * Miroir Android Studio : le nom d'une classe PRÊTE les membres de son
 * compagnon — survol, complétion, signatures, typage des chaînes.
 */
class ReproductionCompanionTest {
    /** Session nourrie du stdlib réel + vue factice Regex/Regex$Companion. */
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    ?.codeSource?.location?.toURI(),
                            ).path,
                            "repro:stdlib",
                        ),
                    ),
                vue = vueRegex(),
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    /**
     * Vue factice miroir du stdlib pour le compagnon de classe : les
     * membres d'INSTANCE de `kotlin.text.Regex$Companion` (imbriqués
     * par `$`, miroir du bytecode réel — escape/unescape y vivent).
     */
    private fun vueRegex(): VueClasspathKotlin = object : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "kotlin.text.Regex" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "kotlin.text.Regex\$Companion" to
                    MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "escape", "(Ljava/lang/String;)Ljava/lang/String;"),
                            MembreJvmKotlin(0x0001, "unescape", "(Ljava/lang/String;)Ljava/lang/String;"),
                        ),
                    ),
                "kotlin.UInt" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "kotlin.UInt\$Companion" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(MembreJvmKotlin(0x0009, "MAX_VALUE", "I")),
                        emptyList(),
                    ),
                "java.lang.Object" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )

        override fun nomsParPrefixe(prefixe: String, limite: Int) = emptyList<NomTypeKotlin>()
        override fun typesDansPaquet(paquet: String) = emptyList<String>()
        override fun estPaquet(nom: String) = nom == "kotlin.text" || nom == "kotlin"
        override fun sousPaquetsDirects(nom: String) = emptyList<String>()
        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]
        override fun octetsClasse(fqcn: String): ByteArray? =
            if (membres.containsKey(fqcn)) octets(fqcn) else null

        private fun octets(fqcn: String): ByteArray {
            val sortie = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                flux.writeShort(3)
                flux.writeByte(1)
                val nom = fqcn.replace('.', '/').toByteArray(Charsets.UTF_8)
                flux.writeShort(nom.size)
                flux.write(nom)
                flux.writeByte(7)
                flux.writeShort(1)
                flux.writeShort(0x0021)
                flux.writeShort(1)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
            }
            return sortie.toByteArray()
        }
    }

    private fun nonResolus(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ) = session.diagnostics(uri).filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }

    // ── Projet : compagnons anonyme, nommé, this implicite ───────────

    @Test
    fun `companion anonyme - Boite point creer(), zero faux positif et capacites`() {
        val session = session()
        val source =
            """
            package demo

            class Boite {
              companion object {
                fun creer(): Boite = Boite()
                const val TAILLE = 3
              }
              fun ouvrir(): String = "ouverte"
            }

            fun usage(): String {
              val b = Boite.creer()
              val t = Boite.TAILLE
              return b.ouvrir()
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///companion-anonyme.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("companion-anonyme => ${resultats.map { it.message }}")
        assertEquals("zéro faux positif sur le patron companion — obtenu : $resultats", 0, resultats.size)

        // Survol du membre du compagnon via le NOM de la classe.
        val survol = session.survol(uri, source.indexOf("creer"))
        System.out.println("companion-survol creer => ${survol?.valeur}")
        assertNotNull("le survol de Boite.creer répond", survol)
        assertTrue("la signature du compagnon se rend — obtenu : ${survol?.valeur}", survol!!.valeur.contains("creer"))

        // Complétion membre après `Boite.` : les membres du compagnon.
        val apresPoint = source.indexOf("Boite.creer") + "Boite.".length
        val completions = session.completion(uri, apresPoint)
        System.out.println("companion-completion Boite. => ${completions.map { it.libelle }.take(8)}")
        assertTrue(
            "creer doit s'offrir après Boite. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle.startsWith("creer") },
        )
        assertTrue(
            "TAILLE doit s'offrir après Boite. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle == "TAILLE" },
        )

        // Signature de l'appel du compagnon (curseur DANS la
        // parenthèse de l'USAGE).
        val signature = session.signature(uri, source.indexOf("Boite.creer(") + "Boite.creer".length + 1)
        System.out.println("companion-signature => ${signature?.signatures?.size}")
        assertTrue(
            "les candidates de Boite.creer() se proposent — obtenu : ${signature?.signatures?.map { it.etiquette }}",
            signature != null && signature.signatures.isNotEmpty(),
        )

        // La CHAÎNE type : `Boite.creer()` rend un Boite — la complétion
        // après `b.` offre le membre ouvrir de la CLASSE.
        val apresB = source.indexOf("b.ouvrir") + "b.".length
        val completionsB = session.completion(uri, apresB)
        assertTrue(
            "ouvrir doit s'offrir après b. (le compagnon a typé) — obtenu : ${completionsB.map { it.libelle }}",
            completionsB.any { it.libelle.startsWith("ouvrir") },
        )
        session.close()
    }

    @Test
    fun `companion nomme - membres sous la classe ET sous l objet`() {
        val session = session()
        val source =
            """
            package demo

            class Atelier {
              companion object Usine {
                fun batir(): Int = 1
              }
            }

            fun usage(): Int = Atelier.batir()
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///companion-nomme.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        assertEquals("le compagnon NOMMÉ prête ses membres au nom de classe — obtenu : $resultats", 0, resultats.size)
        val apresPoint = source.indexOf("Atelier.") + "Atelier.".length
        val completions = session.completion(uri, apresPoint)
        assertTrue(
            "batir doit s'offrir après Atelier. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle.startsWith("batir") },
        )
        session.close()
    }

    @Test
    fun `corps du compagnon - this implicite des membres`() {
        val session = session()
        val source =
            """
            package demo

            class Compteur {
              companion object {
                const val SEUIL = 2
                fun suivant(): Int = 1
                fun total(): Int = suivant() + SEUIL
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///companion-corps.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        assertEquals("les noms NUS du corps du compagnon résolvent — obtenu : $resultats", 0, resultats.size)
        session.close()
    }

    @Test
    fun `objet simple non-regression - ADR 0220 inchangée`() {
        val session = session()
        val source =
            """
            package demo

            object Fabrique {
              fun assembler(): Int = 1
            }

            fun usage(): Int = Fabrique.assembler()
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///companion-objet-simple.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        assertEquals("l'objet simple reste référençable (ADR 0220) — obtenu : $resultats", 0, resultats.size)
        session.close()
    }

    // ── Classpath : compagnons des bibliothèques ($Companion) ─────────

    @Test
    fun `companion du classpath - Regex point escape resolve et s offre`() {
        val session = session()
        val source =
            """
            import kotlin.text.Regex

            fun echapper(s: String): String = Regex.escape(s)
            """.trimIndent()
        val uri = "file:///companion-classpath.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        System.out.println("companion-classpath => ${resultats.map { it.message }}")
        assertEquals(
            "le callee du compagnon du classpath résout (sans index d'extensions pour escape, " +
                "la sélection par types complets porte la preuve) — obtenu : $resultats",
            0,
            resultats.size,
        )
        // Le survol rend la signature du compagnon.
        val survol = session.survol(uri, source.indexOf("escape"))
        System.out.println("companion-classpath-survol => ${survol?.valeur}")
        assertNotNull("le survol de Regex.escape répond", survol)
        assertTrue("la signature du compagnon se rend — obtenu : ${survol?.valeur}", survol!!.valeur.contains("escape"))
        // La complétion après `Regex.` offre les membres du compagnon.
        val apresPoint = source.indexOf("Regex.") + "Regex.".length
        val completions = session.completion(uri, apresPoint)
        System.out.println("companion-classpath-completion => ${completions.map { it.libelle }}")
        assertTrue(
            "escape doit s'offrir après Regex. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle.startsWith("escape") },
        )
        assertTrue(
            "unescape doit s'offrir après Regex. — obtenu : ${completions.map { it.libelle }}",
            completions.any { it.libelle.startsWith("unescape") },
        )
        session.close()
    }

    @Test
    fun `companion du classpath - UInt point MAX_VALUE champ typé`() {
        val session = session()
        // La chaîne RÉELLE : le champ du compagnon TYPE (Int), l'appel
        // membre enchaîné (`plus`) résout par la sélection par types
        // complets — zéro faux positif.
        val source =
            """
            fun seuil(): Int = UInt.MAX_VALUE.plus(1)
            """.trimIndent()
        val uri = "file:///companion-uint.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        assertEquals("le champ du compagnon du classpath résout — obtenu : $resultats", 0, resultats.size)
        // Le SURVOL du champ rend la signature du compagnon.
        val survol = session.survol(uri, source.indexOf("MAX_VALUE"))
        System.out.println("companion-uint-survol => ${survol?.valeur}")
        assertNotNull("le survol de UInt.MAX_VALUE répond", survol)
        assertTrue(
            "la signature du champ du compagnon se rend — obtenu : ${survol?.valeur}",
            survol!!.valeur.contains("MAX_VALUE"),
        )
        session.close()
    }

    @Test
    fun `garde D3 - membre introuvable du compagnon reste signale`() {
        val session = session()
        // VRAI NÉGATIF : `Regex.echappe` n'existe NI comme membre, NI
        // comme compagnon, NI comme extension homonyme — l'absence est
        // prouvée (ADR 0223), le sélecteur est signalé.
        val source =
            """
            import kotlin.text.Regex

            fun casse(): String = Regex.echappe("a")
            """.trimIndent()
        val uri = "file:///companion-garde.kt"
        session.ouvrirDocument(uri, source)
        val resultats = nonResolus(session, uri)
        assertEquals(
            "le membre introuvable du compagnon est signalé — obtenu : ${resultats.map { it.message }}",
            1,
            resultats.size,
        )
        assertEquals("Référence non résolue : « echappe »", resultats.single().message)
        session.close()
    }
}
