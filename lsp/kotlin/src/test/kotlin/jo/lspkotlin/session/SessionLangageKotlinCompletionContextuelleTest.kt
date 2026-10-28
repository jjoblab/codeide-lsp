package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.GenreCompletionKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la complétion CONTEXTUELLE Kotlin (vague 1 du chantier du
 * rapport d'analyse du 28/10/2026 — ADR 0228) : les trois contextes
 * portés du moteur Java 1.16.0, par le SPI (chemin de production D6) —
 * arguments NOMMÉS d'un appel, CONSTANTES d'enum des branches d'un
 * `when`, ATTRIBUTS d'annotation. Chaque test éprouve AUSSI la borne
 * conservatrice (D3) : position de valeur muette, candidat inconnu,
 * repli honnête sur le régime de nom.
 *
 * La vue factice porte une ANNOTATION et une ENUM du classpath
 * (`demo.Anno`, `demo.Couleur`) — les contextes attributs/constants
 * classpath y puisent leurs membres binaires.
 */
class SessionLangageKotlinCompletionContextuelleTest {
    /** Vue factice : annotation + enum du classpath, drapeaux JVM réalistes. */
    private val vue = VueContextuelleFactice()

    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    // ── Arguments nommés ───────────────────────────────────────────────

    @Test
    fun `arguments nommes - surcharge du projet, insertion avec separateur`() {
        val session = session()
        val source = "fun creer(nom: String, age: Int) { }\nfun demo() { creer( }\n"
        val uri = "file:///nommes.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("creer( ") + "creer(".length
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        val nom = parLibelle["nom ="]
        assertEquals(GenreCompletionKotlin.VARIABLE, nom?.genre)
        assertEquals("nom = ", nom?.texteInsertion)
        assertEquals("creer(nom: String, age: Int)", nom?.detail)
        assertNotNull(parLibelle["age ="])
        session.close()
    }

    @Test
    fun `arguments nommes - argument deja fourni en forme nommee exclu`() {
        val session = session()
        val source = "fun creer(nom: String, age: Int) { }\nfun demo() { creer(nom = \"x\", }\n"
        val uri = "file:///exclus.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("\"x\", ") + 5
        val libelles = session.completion(uri, decalage).map { it.libelle }
        assertTrue("`age =` devait être offert", "age =" in libelles)
        assertTrue("`nom =` déjà fourni ne devait pas revenir", "nom =" !in libelles)
        session.close()
    }

    @Test
    fun `arguments nommes - position de valeur muette (litteral chaine)`() {
        val session = session()
        val source = "fun creer(nom: String) { }\nfun demo() { creer(\" }\n"
        val uri = "file:///valeur.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("(\" ") + 2
        val propositions = session.completion(uri, decalage)
        // Une valeur en cours de frappe n'offre AUCUN argument nommé (le
        // régime de nom reprend — mots-clés visibles, préfixe vide).
        assertTrue(propositions.none { it.texteInsertion != null })
        assertTrue(propositions.any { it.libelle == "class" })
        session.close()
    }

    @Test
    fun `arguments nommes - appel membre qualifie du projet`() {
        val session = session()
        val source =
            "class Modele {\n  fun copier(titre: String): Modele = this\n}\n" +
                "fun demo() { val m = Modele(); m.copier( }\n"
        val uri = "file:///qualifie.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("copier( ") + "copier(".length
        val propositions = session.completion(uri, decalage)
        assertEquals("titre = ", propositions.firstOrNull { it.libelle == "titre =" }?.texteInsertion)
        session.close()
    }

    @Test
    fun `arguments nommes - appel inconnu, aucune invention (D3)`() {
        val session = session()
        val source = "fun demo() { inconnu( }\n"
        val uri = "file:///inconnu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("inconnu( ") + "inconnu(".length
        assertTrue(session.completion(uri, decalage).none { it.texteInsertion != null })
        session.close()
    }

    @Test
    fun `arguments nommes - filtre par prefixe du nom de parametre`() {
        val session = session()
        val source = "fun creer(nom: String, age: Int) { }\nfun demo() { creer(ag }\n"
        val uri = "file:///prefixe.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("ag ") + 2
        val libelles = session.completion(uri, decalage).map { it.libelle }
        assertTrue("age =" in libelles)
        assertTrue("nom =" !in libelles)
        session.close()
    }

    // ── Constantes d'enum (branches d'un when) ─────────────────────────

    @Test
    fun `constantes enum - when a sujet enum du projet, contexte exclusif`() {
        val session = session()
        val source =
            "enum class Couleur { ROUGE, VERT, BLEU }\n" +
                "fun demo(c: Couleur) {\n  when (c) {\n  \n}\n}\n"
        val uri = "file:///enum-projet.kt"
        session.ouvrirDocument(uri, source)
        // Préfixe vide, juste après l'accolade du when : position de
        // condition — les constantes ET RIEN D'AUTRE.
        val decalage = source.indexOf("when (c) {\n") + "when (c) {\n".length
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        assertEquals(GenreCompletionKotlin.ENUM, parLibelle["ROUGE"]?.genre)
        assertEquals("Couleur", parLibelle["ROUGE"]?.detail)
        assertNotNull(parLibelle["VERT"])
        assertNotNull(parLibelle["BLEU"])
        assertTrue(
            "le contexte est exclusif — aucun mot-clé ne devait suivre",
            propositions.none { it.genre == GenreCompletionKotlin.MOT_CLE },
        )
        session.close()
    }

    @Test
    fun `constantes enum - filtre par prefixe et sujet enum du classpath`() {
        val session = session()
        val source = "import demo.Couleur\nfun demo(c: Couleur) {\n  when (c) {\n    V\n}\n}\n"
        val uri = "file:///enum-classpath.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("V\n}") + 1
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        assertEquals(GenreCompletionKotlin.ENUM, parLibelle["VERT"]?.genre)
        assertEquals("Couleur", parLibelle["VERT"]?.detail)
        assertTrue("ROUGE ne devait pas passer le préfixe", parLibelle.keys.none { it == "ROUGE" })
        session.close()
    }

    @Test
    fun `constantes enum - sujet non enum, repli honnete sur le regime de nom`() {
        val session = session()
        val source = "fun demo(n: Int) {\n  when (n) {\n    r\n}\n}\n"
        val uri = "file:///pas-enum.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("r\n}") + 1
        val propositions = session.completion(uri, decalage)
        assertTrue(propositions.none { it.genre == GenreCompletionKotlin.ENUM })
        assertTrue("le régime de nom reprend (mots-clés)", propositions.any { it.libelle == "return" })
        session.close()
    }

    @Test
    fun `constantes enum - corps de branche muet`() {
        val session = session()
        val source =
            "enum class Couleur { ROUGE }\n" +
                "fun demo(c: Couleur) {\n  when (c) {\n    ROUGE -> 1 + r\n  }\n}\n"
        val uri = "file:///corps.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("1 + r") + 5
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "le corps d'une branche n'est pas une position de condition",
            propositions.none { it.genre == GenreCompletionKotlin.ENUM },
        )
        assertTrue(propositions.any { it.libelle == "return" })
        session.close()
    }

    // ── Attributs d'annotation ─────────────────────────────────────────

    @Test
    fun `attributs annotation - non fournis, insertion avec separateur`() {
        val session = session()
        val source = "import demo.Anno\n@Anno(\nclass A\n"
        val uri = "file:///anno.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("(\nclass") + 1
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        val level = parLibelle["level ="]
        assertEquals(GenreCompletionKotlin.PROPRIETE, level?.genre)
        assertEquals("level = ", level?.texteInsertion)
        assertEquals("demo.Anno", level?.detail)
        assertNotNull(parLibelle["nom ="])
        assertTrue(
            "le contexte est exclusif — aucun mot-clé ne devait suivre",
            propositions.none { it.genre == GenreCompletionKotlin.MOT_CLE },
        )
        session.close()
    }

    @Test
    fun `attributs annotation - attribut deja fourni exclu`() {
        val session = session()
        val source = "import demo.Anno\n@Anno(level = 1, no\nclass A\n"
        val uri = "file:///anno-exclus.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("no\nclass") + 2
        val libelles = session.completion(uri, decalage).map { it.libelle }
        assertTrue("`nom =` devait être offert", "nom =" in libelles)
        assertTrue("`level =` déjà fourni ne devait pas revenir", "level =" !in libelles)
        session.close()
    }

    @Test
    fun `attributs annotation - position de valeur muette`() {
        val session = session()
        val source = "import demo.Anno\n@Anno(level = \nclass A\n"
        val uri = "file:///anno-valeur.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("= \nclass") + 2
        val propositions = session.completion(uri, decalage)
        assertTrue(propositions.none { it.texteInsertion != null })
        assertTrue("le régime de nom reprend (mots-clés)", propositions.any { it.libelle == "class" })
        session.close()
    }

    @Test
    fun `attributs annotation - annotation non resolue, repli honnete`() {
        val session = session()
        val source = "@Inconnu(c\nclass A\n"
        val uri = "file:///anno-inconnu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("c\nclass") + 1
        val propositions = session.completion(uri, decalage)
        assertTrue(propositions.none { it.texteInsertion != null })
        assertTrue("le régime de nom reprend (mots-clés)", propositions.any { it.libelle == "class" })
        session.close()
    }

    /** Vue factice : une annotation et une enum du classpath démo. */
    private class VueContextuelleFactice : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                // annotation : methodes abstract publiques (attributs).
                "demo.Anno" to
                    MembresTypeKotlin(
                        0x2201,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0401, "level", "()Ldemo/Level;"),
                            MembreJvmKotlin(0x0401, "nom", "()Ljava/lang/String;"),
                            MembreJvmKotlin(0x0009, "helper", "()V"),
                        ),
                    ),
                // enum : constantes = champs statiques typés par elle-même.
                "demo.Couleur" to
                    MembresTypeKotlin(
                        0x4011,
                        listOf(
                            MembreJvmKotlin(0x0019, "ROUGE", "Ldemo/Couleur;"),
                            MembreJvmKotlin(0x0019, "VERT", "Ldemo/Couleur;"),
                            MembreJvmKotlin(0x0019, "BLEU", "Ldemo/Couleur;"),
                            MembreJvmKotlin(0x0002, "ordre", "I"),
                        ),
                        listOf(MembreJvmKotlin(0x0009, "values", "()[Ldemo/Couleur;")),
                    ),
            )

        override fun nomsParPrefixe(
            prefixe: String,
            limite: Int,
        ): List<jo.lspkotlin.api.NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = false

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = null
    }
}
