package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tests du contrat de session K3 (LSP_KOTLIN.md §5, K3 « symboles +
 * complétion + survol + définition ») — par le SPI (chemin de
 * production D6), avec :
 *  - une VUE factice (types/paquets/membres/octet de classes
 *    synthétiques — la couture neutre est éprouvée telle quelle) ;
 *  - le jar kotlin-stdlib RÉEL du classpath de test comme entrée
 *    d'appelables (extensions véritables) ;
 *  - des racines sources temporaires (scan projet, définition
 *    inter-fichiers).
 */
class SessionLangageKotlinK3Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Vue factice : le minimum pour éprouver chaque requête. */
    private val vue = VueClasspathFactice()

    private fun session(
        entrees: List<EntreeClasspathKotlin> = emptyList(),
        racines: List<File> = emptyList(),
    ): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = entrees,
                vue = vue,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Le jar kotlin-stdlib du classpath de test. */
    private fun entreeStdlib(): EntreeClasspathKotlin =
        EntreeClasspathKotlin(
            KindEntreeKotlin.JAR,
            File(
                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                    .codeSource.location.toURI(),
            ).path,
            "test:stdlib",
        )

    // ── Complétion de noms ─────────────────────────────────────────────

    @Test
    fun `complétion nom - locales, membres, mots-clés par préfixe`() {
        val session = session()
        val source =
            """
            class Vue {
                val titre: String = "a"
                fun demo(nom: String) {
                    val compte = 1
                    co
                }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///vue.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("co\n") + 2
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        // Locale en portée (pertinence LOCALE, genre VARIABLE).
        assertEquals(GenreCompletionKotlin.VARIABLE, parLibelle["compte"]?.genre)
        // Mot-clé du langage.
        assertNotNull(parLibelle["companion"])
        // Le membre `titre` de l'englobante est proposé (sans préfixe
        // commun, il n'apparaît pas sous "co" — il est filtré).
        assertTrue(parLibelle.keys.none { it == "titre" })
        session.close()
    }

    @Test
    fun `complétion nom - types du classpath avec auto-import`() {
        val session = session()
        val source = "fun main() { val v: TextV }\n"
        val uri = "file:///main.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("TextV") + 5
        val propositions = session.completion(uri, decalage)
        val texteVue = propositions.firstOrNull { it.libelle == "TextView" }
        // La vue factice porte android.widget.TextView : FQCN en détail,
        // import à dériver côté hôte (hors paquet/import).
        assertEquals("android.widget.TextView", texteVue?.detail)
        assertEquals("android.widget.TextView", texteVue?.fqnImport)
        assertEquals(GenreCompletionKotlin.CLASSE, texteVue?.genre)
        session.close()
    }

    // ── Complétion membre ──────────────────────────────────────────────

    @Test
    fun `complétion membre - champ et méthode le long de la chaîne de supertypes`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo() {
                val v: TextView = creer()
                v.
            }
            fun creer(): TextView = creer()
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///demo.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("v.") + 2
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        // Champ DÉCLARÉ de TextView (vue factice).
        assertEquals(GenreCompletionKotlin.PROPRIETE, parLibelle["texte"]?.genre)
        assertEquals("CharSequence", parLibelle["texte"]?.detail)
        // Méthode HÉRITÉE de View (chaîne via octets synthétiques).
        assertEquals(GenreCompletionKotlin.FONCTION, parLibelle["requestLayout()"]?.genre)
        session.close()
    }

    @Test
    fun `complétion membre - extensions réelles du stdlib sur String`() {
        val session = session(entrees = listOf(entreeStdlib()))
        val source = "fun demo() { val s = \"x\"; s. }\n"
        val uri = "file:///chaine.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("s.") + 2
        val propositions = session.completion(uri, decalage)
        // kotlin.String : membre JVM de la vue factice + extensions du
        // stdlib décodées (trim est public, extension de String).
        assertTrue(propositions.any { it.libelle == "length()" })
        assertTrue(
            "une extension réelle du stdlib devait être offerte",
            propositions.any { it.libelle == "trim()" },
        )
        session.close()
    }

    @Test
    fun `complétion membre - récepteur de type PROJET (déclarations de la classe)`() {
        val session = session()
        val source =
            """
            class Modele {
                val nom: String = "a"
                fun afficher() { }
                fun demo() {
                    val m = Modele()
                    m.
                }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///modele.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("m.") + 2
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        assertEquals(GenreCompletionKotlin.PROPRIETE, parLibelle["nom"]?.genre)
        assertEquals(GenreCompletionKotlin.FONCTION, parLibelle["afficher()"]?.genre)
        session.close()
    }

    @Test
    fun `complétion membre - récepteur non résolu, aucune proposition (D3)`() {
        val session = session()
        val source = "fun demo(x: Inconnu) { x. }\n"
        val uri = "file:///inconnu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("x.") + 2
        assertTrue(session.completion(uri, decalage).isEmpty())
        session.close()
    }

    @Test
    fun `complétion import - sous-paquets du paquet tapé`() {
        val session = session()
        val source = "import android.wid\n"
        val uri = "file:///imports.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("wid") + 3
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        assertEquals(GenreCompletionKotlin.PAQUET, parLibelle["widget"]?.genre)
        assertEquals("android.widget", parLibelle["widget"]?.detail)
        session.close()
    }

    // ── Survol ─────────────────────────────────────────────────────────

    @Test
    fun `survol - locale avec type écrit`() {
        val session = session()
        val source = "fun demo() { val titre: String = \"a\"; }\n"
        val uri = "file:///survol.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("titre") + 1)
        assertEquals("val titre: String", survol?.valeur)
        session.close()
    }

    @Test
    fun `survol - fonction du fichier avec signature complète`() {
        val session = session()
        val source = "fun creer(nom: String): Int = 1\n"
        val uri = "file:///fonction.kt"
        session.ouvrirDocument(uri, source)
        val survol = session.survol(uri, source.indexOf("creer") + 1)
        assertEquals("fun creer(nom: String): Int", survol?.valeur)
        session.close()
    }

    @Test
    fun `survol - membre qualifié hérité (propriétaire classpath)`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo() {
                val v: TextView = creer()
                v.requestLayout()
            }
            fun creer(): TextView = creer()
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///herit.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("requestLayout") + 3
        val survol = session.survol(uri, decalage)
        assertEquals("fun requestLayout(): Unit — android.view.View", survol?.valeur)
        session.close()
    }

    // ── Définition ─────────────────────────────────────────────────────

    @Test
    fun `définition - locale du document courant (plage du nom)`() {
        val session = session()
        val source = "fun demo() { val titre = 1 }\n"
        val uri = "file:///def.kt"
        session.ouvrirDocument(uri, source)
        val definitions = session.definitions(uri, source.indexOf("titre") + 1)
        assertEquals(1, definitions.size)
        assertEquals(uri, definitions.single().uri)
        assertEquals(source.indexOf("titre"), definitions.single().debut)
        assertEquals(source.indexOf("titre") + "titre".length, definitions.single().fin)
        session.close()
    }

    @Test
    fun `définition - déclaration d'un AUTRE fichier des racines sources`() {
        val racine = dossierTemporaire.newFolder("src")
        val fichierAutre = File(racine, "Autre.kt")
        fichierAutre.writeText("package demo\n\nclass Autre { fun biz() { } }\n")
        val session = session(racines = listOf(racine))
        val source = "import demo.Autre\n\nfun main() { val a = Autre() }\n"
        val uri = "file:///principal.kt"
        session.ouvrirDocument(uri, source)
        val definitions = session.definitions(uri, source.indexOf("Autre()") + 1)
        assertEquals(1, definitions.size)
        assertEquals(fichierAutre.toURI().toString(), definitions.single().uri)
        val contenu = fichierAutre.readText()
        // Plage = le NOM déclaré dans l'AUTRE fichier (« class Autre »).
        assertEquals(contenu.indexOf("Autre"), definitions.single().debut)
        assertEquals(contenu.indexOf("Autre") + "Autre".length, definitions.single().fin)
        session.close()
    }

    @Test
    fun `définition - cible classpath, liste vide (D3 documenté)`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun creer(): TextView = creer()
            fun demo() { creer() }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///classpath.kt"
        session.ouvrirDocument(uri, source)
        // `creer` est une fonction du PROJET → définition locale ; la
        // cible TYPE (TextView) elle-même rend vide : pas d'URI factice.
        val definitions = session.definitions(uri, source.indexOf("creer()") + 1)
        assertEquals(uri, definitions.single().uri)
        val surType = session.definitions(uri, source.indexOf("TextView") + 1)
        assertTrue(surType.isEmpty())
        session.close()
    }

    // ── Tolérances ─────────────────────────────────────────────────────

    @Test
    fun `document inconnu - requêtes vides, jamais d'exception`() {
        val session = session()
        assertTrue(session.completion("file:///x.kt", 0).isEmpty())
        assertEquals(null, session.survol("file:///x.kt", 0))
        assertTrue(session.definitions("file:///x.kt", 0).isEmpty())
        session.close()
    }

    @Test
    fun `après close - définirClasspath et requêtes sont des no-ops`() {
        val session = session()
        session.close()
        session.definirClasspath(ClasspathKotlin(emptyList(), null, emptyList(), null))
        assertTrue(session.completion("file:///x.kt", 0).isEmpty())
        assertEquals(null, session.survol("file:///x.kt", 0))
    }

    // ── Vue factice ────────────────────────────────────────────────────

    /**
     * Vue de classpath factice — un seul univers :
     *  - types `TextView` (android.widget), `Vue` du paquet courant ;
     *  - TextView : champ `texte` (CharSequence) + super `android.view.View`
     *    (méthode `requestLayout`) + super Object ;
     *  - kotlin.String : méthode `length()` (super Object) ;
     *  - paquets android{.widget,.view}, paquet demo vide.
     */
    private class VueClasspathFactice : VueClasspathKotlin {
        /** Paquets connus de la vue factice. */
        private val paquetsConnus: Set<String> =
            setOf("android", "android.widget", "android.view", "kotlin.text", "demo")

        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "android.widget.TextView" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(MembreJvmKotlin(0x0001, "texte", "Ljava/lang/CharSequence;")),
                        listOf(MembreJvmKotlin(0x0001, "setText", "(Ljava/lang/CharSequence;)V")),
                    ),
                "android.view.View" to
                    MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(MembreJvmKotlin(0x0001, "requestLayout", "()V")),
                    ),
                "kotlin.String" to
                    MembresTypeKotlin(0x0011, emptyList(), listOf(MembreJvmKotlin(0x0001, "length", "()I"))),
            )

        private val superTypes: Map<String, String?> =
            mapOf(
                "android.widget.TextView" to "android.view.View",
                "android.view.View" to "java.lang.Object",
                "kotlin.String" to "java.lang.Object",
            )

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
            if (prefixe == "TextV") {
                listOf(NomTypeKotlin("TextView", listOf("android.widget.TextView")))
            } else {
                emptyList()
            }

        override fun typesDansPaquet(paquet: String): List<String> =
            when (paquet) {
                "android.widget" -> listOf("android.widget.TextView")
                else -> emptyList()
            }

        override fun estPaquet(nom: String): Boolean =
            nom in paquetsConnus

        override fun sousPaquetsDirects(nom: String): List<String> =
            when (nom) {
                "android" -> listOf("android.view", "android.widget")
                else -> emptyList()
            }

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = octetsSynthetiques(fqcn, superTypes[fqcn])

        /** Classe minimale (cf. LecteurClassBrutTest) au fil de l'eau. */
        private fun octetsSynthetiques(
            fqcn: String,
            superFqcn: String?,
        ): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                val paires = 1 + (if (superFqcn != null) 1 else 0)
                flux.writeShort(paires * 2 + 1)
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val octets = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(octets.size)
                    flux.write(octets)
                }
                fun classe(idx: Int) {
                    flux.writeByte(7)
                    flux.writeShort(idx)
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
                flux.writeShort(0x0021)
                flux.writeShort(thisUtf8 + 1)
                flux.writeShort(superClasse)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
                flux.writeShort(0)
            }
            return sortie.toByteArray()
        }
    }
}
