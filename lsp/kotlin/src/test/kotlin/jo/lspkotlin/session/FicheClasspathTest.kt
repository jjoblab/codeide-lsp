package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tests du chantier P1-D « documentation du classpath au survol et à
 * la signature » (ADR 0230, feuille de route du rapport d'analyse du
 * 28/10/2026) — par le SPI (chemin de production D6) :
 *
 *  - la FICHE STRUCTURELLE du survol d'un type du classpath (genre,
 *    héritage direct, interfaces, constantes d'enum, champs publics)
 *    prouvée par la lecture brute du `.class` — les OCTETS sont
 *    synthétiques (super et interfaces réels, miroir
 *    LecteurClassBrutTest), les MEMBRES vivent dans la vue factice ;
 *  - la KDoc qui accompagne les candidates de l'aide à la signature :
 *    document courant (arbre vif), fichier tiers FERMÉ (lecture
 *    disque + parse bornées), fichier tiers OUVERT (l'arbre vif
 *    évince le disque périmé) — et la candidate du CLASSPATH qui
 *    reste muette (le bytecode ne porte pas de doc, D3).
 *
 * Chaque test éprouve AUSSI la borne conservatrice : un binaire
 * illisible tombe sur le genre seul, un champ synthétique ou le
 * marqueur `INSTANCE` d'un objet ne se montrent jamais.
 */
class FicheClasspathTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    private val vue = VueFicheFactice()

    private fun session(
        racines: List<File> = emptyList(),
    ): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    // ── Fiche de survol d'un type du classpath ─────────────────────────

    @Test
    fun `survol type classpath - classe, heritage direct et champs publics`() {
        val session = session()
        val source =
            "import android.widget.TextView\n" +
                "fun dessiner(vue: TextView) {}\n"
        val uri = "file:///dessin.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("TextView)"))
        assertEquals("public class android.widget.TextView extends android.view.View", survol?.valeur)
        assertEquals(
            "Champs publics : CharSequence texte",
            survol?.documentation,
        )
        session.close()
    }

    @Test
    fun `survol type classpath - interface, section Interfaces, jamais d extends`() {
        val session = session()
        val source =
            "import demo.Robot\n" +
                "fun piloter(robot: Robot) {}\n"
        val uri = "file:///pilotage.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("Robot)"))
        assertEquals("public interface demo.Robot", survol?.valeur)
        assertEquals(
            "Interfaces : java.lang.Runnable, demo.Capable",
            survol?.documentation,
        )
        session.close()
    }

    @Test
    fun `survol type classpath - enum, constantes servies, super Enum masque`() {
        val session = session()
        val source =
            "import demo.Couleur\n" +
                "fun peindre(c: Couleur) {}\n"
        val uri = "file:///peinture.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("Couleur)"))
        assertEquals("public enum demo.Couleur", survol?.valeur)
        assertEquals("Constantes : ROUGE, VERT, BLEU", survol?.documentation)
        session.close()
    }

    @Test
    fun `survol type classpath - objet Kotlin par champ INSTANCE, marqueur exclu des champs`() {
        val session = session()
        val source =
            "import demo.Outil\n" +
                "fun verifier(o: Outil) {}\n"
        val uri = "file:///outils.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("Outil)"))
        assertEquals("public object demo.Outil", survol?.valeur)
        assertEquals("Champs publics : String version", survol?.documentation)
        session.close()
    }

    @Test
    fun `survol type classpath - plafonds d affichage, champs 8 et constantes 20`() {
        val session = session()
        val source =
            "import demo.Nombre\n" +
                "import demo.ArcEnCiel\n" +
                "fun mesurer(n: Nombre, a: ArcEnCiel) {}\n"
        val uri = "file:///mesures.kt"
        session.ouvrirDocument(uri, source)
        val survolNombre = session.survol(uri, source.indexOf("Nombre,"))
        assertEquals("public class demo.Nombre", survolNombre?.valeur)
        assertEquals(
            "Champs publics : Int a, Int b, Int c, Int d, Int e, Int f, Int g, Int h, …",
            survolNombre?.documentation,
        )
        val survolArc = session.survol(uri, source.indexOf("ArcEnCiel)"))
        assertEquals("public enum demo.ArcEnCiel", survolArc?.valeur)
        val attendu =
            "Constantes : " + (1..20).joinToString(", ") { indice -> "C$indice" } + ", …"
        assertEquals(attendu, survolArc?.documentation)
        session.close()
    }

    @Test
    fun `survol type classpath - binaire illisible, genre et champs sans heritage ni interfaces`() {
        val session = session()
        val source =
            "import demo.SansBinaire\n" +
                "fun charger(s: SansBinaire) {}\n"
        val uri = "file:///chargement.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("SansBinaire)"))
        // Octets absents de la vue : le genre (drapeaux des membres)
        // et les champs restent prouvés, l'héritage et les interfaces
        // se taisent (D3 — jamais une ligne inventée).
        assertEquals("public class demo.SansBinaire", survol?.valeur)
        assertEquals("Champs publics : String nom", survol?.documentation)
        session.close()
    }

    // ── KDoc des candidates de l'aide à la signature ──────────────────

    @Test
    fun `signature - KDoc du document courant accompagne la candidate`() {
        val session = session()
        val source =
            "/**\n" +
                " * Additionne deux entiers.\n" +
                " * @param a le premier terme\n" +
                " */\n" +
                "fun ajouter(a: Int, b: Int): Int = a + b\n" +
                "fun usage(): Int = ajouter(1, 2)\n"
        val uri = "file:///calcul.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("ajouter(1") + "ajouter(".length)
        assertEquals("ajouter(a: Int, b: Int): Int", aide?.signatures?.get(aide.signatureActive)?.etiquette)
        assertEquals(
            "Additionne deux entiers.\n@param a — le premier terme",
            aide?.signatures?.get(aide.signatureActive)?.documentation,
        )
        session.close()
    }

    @Test
    fun `signature - KDoc cross-fichier par le port, fichier tiers FERME`() {
        val bibli =
            "package outils\n" +
                "/**\n" +
                " * Triple la valeur.\n" +
                " */\n" +
                "fun triple(x: Int): Int = x * 3\n"
        val appelant =
            "package outils\n" +
                "fun essai(): Int = triple(2)\n"
        val racine = dossierTemporaire.newFolder("src")
        File(racine, "Biblio.kt").writeText(bibli)
        val session = session(racines = listOf(racine))
        val uri = "fixture://Essai.kt"
        session.ouvrirDocument(uri, appelant)
        val aide = session.signature(uri, appelant.indexOf("triple(") + "triple(".length)
        assertEquals("triple(x: Int): Int", aide?.signatures?.get(aide.signatureActive)?.etiquette)
        assertEquals("Triple la valeur.", aide?.signatures?.get(aide.signatureActive)?.documentation)
        session.close()
    }

    @Test
    fun `signature - KDoc cross-fichier, fichier tiers OUVERT, arbre vif et non disque perime`() {
        val bibliDisque =
            "package outils\n" +
                "/**\n" +
                " * Triple la valeur (version disque).\n" +
                " */\n" +
                "fun triple(x: Int): Int = x * 3\n"
        val bibliVive =
            "package outils\n" +
                "/**\n" +
                " * Triple la valeur (version vivante).\n" +
                " */\n" +
                "fun triple(x: Int): Int = x * 3\n"
        val appelant =
            "package outils\n" +
                "fun essai(): Int = triple(2)\n"
        val racine = dossierTemporaire.newFolder("src")
        File(racine, "Biblio.kt").writeText(bibliDisque)
        val session = session(racines = listOf(racine))
        val uriBibli = File(racine, "Biblio.kt").toURI().toString()
        session.ouvrirDocument(uriBibli, bibliVive)
        val uri = "fixture://Essai.kt"
        session.ouvrirDocument(uri, appelant)
        val aide = session.signature(uri, appelant.indexOf("triple(") + "triple(".length)
        val documentation = aide?.signatures?.get(aide.signatureActive)?.documentation
        assertTrue(
            "la KDoc vient de l'arbre VIF du document tiers ouvert — obtenu : $documentation",
            documentation?.contains("version vivante") == true,
        )
        session.close()
    }

    @Test
    fun `signature - candidate du classpath sans documentation, le bytecode est muet`() {
        val session = session()
        val source =
            "import android.widget.TextView\n" +
                "fun regler(vue: TextView) {\n" +
                "  vue.setText(\"x\")\n" +
                "}\n"
        val uri = "file:///reglage.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("setText(") + "setText(".length)
        assertEquals("setText(CharSequence): Unit", aide?.signatures?.get(aide.signatureActive)?.etiquette)
        assertNull(
            "la candidate JVM ne porte AUCUNE documentation (bytecode sans doc, D3)",
            aide?.signatures?.get(aide.signatureActive)?.documentation,
        )
        session.close()
    }

    @Test
    fun `signature - membre vif de la classe englobante, KDoc vive`() {
        val session = session()
        val source =
            "class Machine {\n" +
                "  /**\n" +
                "   * Redémarre la machine.\n" +
                "   */\n" +
                "  fun redemarrer(force: Boolean) {}\n" +
                "  fun essai() {\n" +
                "    redemarrer(true)\n" +
                "  }\n" +
                "}\n"
        val uri = "file:///machine.kt"
        session.ouvrirDocument(uri, source)
        val aide = session.signature(uri, source.indexOf("redemarrer(true)") + "redemarrer(".length)
        assertEquals("redemarrer(force: Boolean)", aide?.signatures?.get(aide.signatureActive)?.etiquette)
        assertEquals("Redémarre la machine.", aide?.signatures?.get(aide.signatureActive)?.documentation)
        session.close()
    }

    // ── Vue factice : membres déclarés + octets super/interfaces ──────

    /**
     * Vue du classpath de test : les MEMBRES (champs, méthodes,
     * drapeaux de classe) vivent dans une table, les OCTETS
     * (superclasse et interfaces directes, lus par LecteurClassBrut)
     * sont synthétisés — miroir de LecteurClassBrutTest, aucune
     * méthode ni attribut (la fiche ne lit PAS les membres du
     * binaire : les champs viennent de la vue).
     */
    private class VueFicheFactice : VueClasspathKotlin {
        /** Drapeau JVM `enum` des classes/champs (0x4000). */
        private val drapeauEnum: Int = 0x4000

        /** Drapeau JVM `static` (0x0008) + `final` (0x0010). */
        private val statiqueFinal: Int = 0x0018

        /** Drapeau JVM `public` (0x0001). */
        private val drapeauPublic: Int = 0x0001

        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                // Classe publique : un champ public, un PRIVÉ (exclu),
                // un synthétique `$` (exclu).
                "android.widget.TextView" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(
                            MembreJvmKotlin(drapeauPublic, "texte", "Ljava/lang/CharSequence;"),
                            MembreJvmKotlin(0x0002, "cache", "Ljava/lang/String;"),
                            MembreJvmKotlin(drapeauPublic, "\$observateur", "Ljava/lang/Object;"),
                        ),
                        listOf(MembreJvmKotlin(drapeauPublic, "setText", "(Ljava/lang/CharSequence;)V")),
                    ),
                // Interface publique à DEUX interfaces directes.
                "demo.Robot" to
                    MembresTypeKotlin(
                        0x0201,
                        emptyList(),
                        listOf(MembreJvmKotlin(drapeauPublic, "avance", "()V")),
                    ),
                // Enum : trois constantes (drapeau champ ENUM) + le
                // champ synthétique `$VALUES` (privé ET `$`).
                "demo.Couleur" to
                    MembresTypeKotlin(
                        drapeauEnum or drapeauPublic,
                        listOf(
                            MembreJvmKotlin(drapeauEnum or statiqueFinal or drapeauPublic, "ROUGE", "Ldemo/Couleur;"),
                            MembreJvmKotlin(drapeauEnum or statiqueFinal or drapeauPublic, "VERT", "Ldemo/Couleur;"),
                            MembreJvmKotlin(drapeauEnum or statiqueFinal or drapeauPublic, "BLEU", "Ldemo/Couleur;"),
                            MembreJvmKotlin(0x000A, "\$VALUES", "[Ldemo/Couleur;"),
                        ),
                        emptyList(),
                    ),
                // Objet Kotlin : marqueur INSTANCE (statique final) +
                // un vrai champ public.
                "demo.Outil" to
                    MembresTypeKotlin(
                        0x0031,
                        listOf(
                            MembreJvmKotlin(statiqueFinal or drapeauPublic, "INSTANCE", "Ldemo/Outil;"),
                            MembreJvmKotlin(drapeauPublic, "version", "Ljava/lang/String;"),
                        ),
                        emptyList(),
                    ),
                // Dix champs publics : le plafond 8 + « … ».
                "demo.Nombre" to
                    MembresTypeKotlin(
                        0x0021,
                        ('a'..'j').map { lettre -> MembreJvmKotlin(drapeauPublic, lettre.toString(), "I") },
                        emptyList(),
                    ),
                // 22 constantes : le plafond 20 + « … ».
                "demo.ArcEnCiel" to
                    MembresTypeKotlin(
                        drapeauEnum or drapeauPublic,
                        (1..22).map { indice ->
                            MembreJvmKotlin(
                                drapeauEnum or statiqueFinal or drapeauPublic,
                                "C$indice",
                                "Ldemo/ArcEnCiel;",
                            )
                        },
                        emptyList(),
                    ),
                // AUCUN octet servi : le binaire illisible de la garde.
                "demo.SansBinaire" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(MembreJvmKotlin(drapeauPublic, "nom", "Ljava/lang/String;")),
                        emptyList(),
                    ),
            )

        /** Superclasse et interfaces par FQCN (les octets en dérivent). */
        private val hierarchy: Map<String, Pair<String?, List<String>>> =
            mapOf(
                "android.widget.TextView" to ("android.view.View" to emptyList()),
                "demo.Robot" to ("java.lang.Object" to listOf("java.lang.Runnable", "demo.Capable")),
                "demo.Couleur" to ("java.lang.Enum" to emptyList()),
                "demo.Outil" to ("java.lang.Object" to emptyList()),
                "demo.Nombre" to ("java.lang.Object" to emptyList()),
                "demo.ArcEnCiel" to ("java.lang.Enum" to emptyList()),
            )

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = nom == "demo" || nom == "android" || nom == "android.widget"

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? =
            hierarchy[fqcn]?.let { (superFqcn, interfaces) ->
                octetsSynthetiques(fqcn, superFqcn, interfaces)
            }

        /** Classe minimale : identité, super, interfaces (cf. LecteurClassBrutTest). */
        private fun octetsSynthetiques(
            fqcn: String,
            superFqcn: String?,
            interfaces: List<String>,
        ): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0) // minor
                flux.writeShort(52) // major (Java 8)
                val paires = 1 + (if (superFqcn != null) 1 else 0) + interfaces.size
                flux.writeShort(paires * 2 + 1) // +1 : index 0 inutilisé
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val octets = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(octets.size)
                    flux.write(octets)
                }
                fun classe(idxUtf8: Int) {
                    flux.writeByte(7)
                    flux.writeShort(idxUtf8)
                }
                var index = 1
                val thisUtf8 = index
                utf8(fqcn.replace('.', '/'))
                index += 2
                classe(thisUtf8)
                var superClasse = 0
                if (superFqcn != null) {
                    val superUtf8 = index
                    utf8(superFqcn.replace('.', '/'))
                    index += 2
                    classe(superUtf8)
                    superClasse = superUtf8 + 1
                }
                val ifaceClasses = mutableListOf<Int>()
                for (iface in interfaces) {
                    val ifaceUtf8 = index
                    utf8(iface.replace('.', '/'))
                    index += 2
                    classe(ifaceUtf8)
                    ifaceClasses.add(ifaceUtf8 + 1)
                }
                flux.writeShort(0x0021) // access_flags (public super)
                flux.writeShort(thisUtf8 + 1) // this_class
                flux.writeShort(superClasse) // super_class (0 = Object)
                flux.writeShort(ifaceClasses.size)
                ifaceClasses.forEach { flux.writeShort(it) }
                flux.writeShort(0) // fields_count
                flux.writeShort(0) // methods_count
                flux.writeShort(0) // attributes_count
            }
            return sortie.toByteArray()
        }
    }
}
