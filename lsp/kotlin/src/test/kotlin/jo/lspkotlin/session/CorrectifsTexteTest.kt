package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.GenreCorrectifKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des correctifs d'EXPRESSION (chantier 3, ADR 0232 — wrap /
 * cast / surround) — par le SPI (chemin de production D6) : chaque
 * test éprouve l'édition CONCRÈTE (plage + texte) ET la borne
 * conservatrice (D3) — un correctif ne s'offre JAMAIS sans la preuve
 * du diagnostic correspondant (l'alignement est par construction :
 * mêmes marches, mêmes gardes).
 *
 * Les primitives (Int, String…) résolvent par IMPORTS_DEFAUT du
 * résolveur — la session NUE suffit (aucun classpath factice).
 */
@Suppress("LargeClass")
class CorrectifsTexteTest {
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin =
        ServiceMoteurLangageKotlin.unique().creerSession()

    // ── kt.unsafeNullable : ?., !!, entourage ─────────────────────────

    @Test
    fun `acces non sur - appel sur, assertion non nulle et entourage multi-ligne`() {
        val session = session()
        val source = "fun demo(s: String?) {\n  s.length\n}\n"
        val uri = "file:///sur.kt"
        session.ouvrirDocument(uri, source)
        val recepteur = source.indexOf("s.length")
        val correctifs = session.correctifs(uri, recepteur)
        assertEquals(3, correctifs.size)
        // `?.` : le point INSERTION juste avant (s?.length).
        val appelSur = correctifs[0]
        assertEquals(GenreCorrectifKotlin.APPEL_SUR, appelSur.genre)
        assertEquals(recepteur + 1, appelSur.debut)
        assertEquals(recepteur + 1, appelSur.fin)
        assertEquals("?", appelSur.texte)
        // `!!` : juste après le récepteur (s!!.length).
        val assertion = correctifs[1]
        assertEquals(GenreCorrectifKotlin.ASSERTION_NON_NULLE, assertion.genre)
        assertEquals(recepteur + 1, assertion.debut)
        assertEquals(recepteur + 1, assertion.fin)
        assertEquals("!!", assertion.texte)
        // Entourage : l'instruction POSSEDE sa ligne — multi-ligne, le
        // corps gagne un pas d'indentation, la garde ferme à l'indent
        // de l'instruction (miroir Android Studio).
        val entourage = correctifs[2]
        assertEquals(GenreCorrectifKotlin.TEST_NULLITE, entourage.genre)
        assertEquals(recepteur, entourage.debut)
        assertEquals(recepteur + "s.length".length, entourage.fin)
        assertEquals("s", entourage.argument)
        assertEquals(
            "if (s != null) {\n      s.length\n  }",
            entourage.texte,
        )
        session.close()
    }

    @Test
    fun `acces non sur - instruction partageant sa ligne, entourage en ligne`() {
        val session = session()
        val source = "fun demo(s: String?) { s.length }\n"
        val uri = "file:///sur-ligne.kt"
        session.ouvrirDocument(uri, source)
        val recepteur = source.indexOf("s.length")
        val entourage =
            session.correctifs(uri, recepteur)
                .single { it.genre == GenreCorrectifKotlin.TEST_NULLITE }
        assertEquals(
            "if (s != null) { s.length }",
            entourage.texte,
        )
        session.close()
    }

    @Test
    fun `acces non sur - propriete locale entouree, l'instruction entiere`() {
        val session = session()
        val source = "fun demo(s: String?) {\n  val n = s.length\n}\n"
        val uri = "file:///sur-locale.kt"
        session.ouvrirDocument(uri, source)
        val recepteur = source.indexOf("s.length")
        val entourage =
            session.correctifs(uri, recepteur)
                .single { it.genre == GenreCorrectifKotlin.TEST_NULLITE }
        val instruction = source.indexOf("val n")
        assertEquals(instruction, entourage.debut)
        assertEquals(instruction + "val n = s.length".length, entourage.fin)
        assertEquals(
            "if (s != null) {\n      val n = s.length\n  }",
            entourage.texte,
        )
        session.close()
    }

    @Test
    fun `acces non sur - premier niveau sans bloc, pas d'entourage (D3)`() {
        val session = session()
        val source = "val s: String? = null\nval n = s.length\n"
        val uri = "file:///sur-sommet.kt"
        session.ouvrirDocument(uri, source)
        val recepteur = source.indexOf("s.length")
        val correctifs = session.correctifs(uri, recepteur)
        // `?.` et `!!` restent offerts — l'entourage n'a pas de cible
        // LÉGALE au premier niveau (`if` interdit au sommet d'un
        // fichier Kotlin).
        assertEquals(
            listOf(GenreCorrectifKotlin.APPEL_SUR, GenreCorrectifKotlin.ASSERTION_NON_NULLE),
            correctifs.map { it.genre },
        )
        session.close()
    }

    @Test
    fun `acces non sur - segment imbrique, le point du segment le plus profond`() {
        val session = session()
        val source = "fun demo(s: String?) {\n  s.length.trim()\n}\n"
        val uri = "file:///sur-chaine.kt"
        session.ouvrirDocument(uri, source)
        val recepteur = source.indexOf("s.length")
        val correctifs = session.correctifs(uri, recepteur)
        val appelSur = correctifs.single { it.genre == GenreCorrectifKotlin.APPEL_SUR }
        // Le point corrigé est celui de SON segment (s?.length.trim(),
        // jamais s.length?.trim()).
        assertEquals(source.indexOf(".length"), appelSur.debut)
        // Le sélecteur `length` n'est PAS un récepteur simple : aucune
        // preuve, aucun correctif (précision D3).
        assertTrue(session.correctifs(uri, source.indexOf("length")).isEmpty())
        session.close()
    }

    @Test
    fun `acces sur ou garde de nullite - aucun correctif (D3)`() {
        val session = session()
        val source =
            "fun demo(s: String?) {\n" +
                "  val a = s?.length\n" +
                "  if (s != null) {\n" +
                "    s.length\n" +
                "  }\n" +
                "}\n"
        val uri = "file:///sur-ok.kt"
        session.ouvrirDocument(uri, source)
        assertTrue(session.correctifs(uri, source.indexOf("s?.length")).isEmpty())
        assertTrue(session.correctifs(uri, source.indexOf("s.length")).isEmpty())
        session.close()
    }

    @Test
    fun `diagnostic perime - le moteur re-prouve, rend vide (honnête)`() {
        val session = session()
        val uri = "file:///perime.kt"
        session.ouvrirDocument(uri, "fun demo(s: String?) {\n  s.length\n}\n")
        val recepteur = "fun demo(s: String?) {\n  ".length
        assertTrue(session.correctifs(uri, recepteur).isNotEmpty())
        // L'édition corrige l'accès : la même offset ne prouve plus
        // RIEN (SAFE_ACCESS, pas DOT_QUALIFIED) — le diagnostic que le
        // client tient encore est périmé, le moteur ne s'en souvient
        // pas (la preuve est structurelle, jamais une mémoire).
        session.modifierDocument(uri, "fun demo(s: String?) {\n  s?.length\n}\n", script = false)
        assertTrue(session.correctifs(uri, recepteur).isEmpty())
        session.close()
    }

    // ── kt.typeMismatch : changement de type, conversion ─────────────

    @Test
    fun `litteral chaine vers Int - changement de type et conversion toInt`() {
        val session = session()
        val source = "val x: Int = \"5\"\n"
        val uri = "file:///type.kt"
        session.ouvrirDocument(uri, source)
        val initialiseur = source.indexOf("\"5\"")
        val correctifs = session.correctifs(uri, initialiseur)
        assertEquals(2, correctifs.size)
        // Changement de type : la plage du type ÉCRIT remplacée par le
        // représentant de la famille du littéral.
        val changement = correctifs[0]
        assertEquals(GenreCorrectifKotlin.CHANGEMENT_TYPE, changement.genre)
        assertEquals(source.indexOf("Int"), changement.debut)
        assertEquals(source.indexOf("Int") + "Int".length, changement.fin)
        assertEquals("String", changement.texte)
        assertEquals("String", changement.argument)
        // Conversion : l'appel standard collé à l'initialiseur.
        val conversion = correctifs[1]
        assertEquals(GenreCorrectifKotlin.CONVERSION, conversion.genre)
        assertEquals(initialiseur + "\"5\"".length, conversion.debut)
        assertEquals(initialiseur + "\"5\"".length, conversion.fin)
        assertEquals(".toInt()", conversion.texte)
        assertEquals(".toInt()", conversion.argument)
        session.close()
    }

    @Test
    fun `cible non primitive - changement de type seul (D3)`() {
        val session = session()
        val source = "class Vue\nval v: Vue = \"x\"\n"
        val uri = "file:///type-vue.kt"
        session.ouvrirDocument(uri, source)
        val initialiseur = source.indexOf("\"x\"")
        val correctifs = session.correctifs(uri, initialiseur)
        assertEquals(listOf(GenreCorrectifKotlin.CHANGEMENT_TYPE), correctifs.map { it.genre })
        assertEquals("String", correctifs.single().texte)
        session.close()
    }

    @Test
    fun `matrice de conversion - fermee par famille (D3)`() {
        val session = session()
        val source =
            "val a: String = true\n" +
                "val b: String = 'c'\n" +
                "val c: Int = 'c'\n" +
                "val d: Int = 2.5\n" +
                "val e: String = 2.5f\n" +
                "val f: Boolean = \"vrai\"\n"
        val uri = "file:///matrice.kt"
        session.ouvrirDocument(uri, source)
        // Booléen → String : toString() total.
        assertEquals(
            listOf(GenreCorrectifKotlin.CHANGEMENT_TYPE, GenreCorrectifKotlin.CONVERSION),
            session.correctifs(uri, source.indexOf("true")).map { it.genre },
        )
        assertEquals(
            ".toString()",
            session.correctifs(uri, source.indexOf("true"))
                .single { it.genre == GenreCorrectifKotlin.CONVERSION }.texte,
        )
        // Caractère → String : toString() total.
        assertEquals(
            ".toString()",
            session.correctifs(uri, source.indexOf("'c'"))
                .single { it.genre == GenreCorrectifKotlin.CONVERSION }.texte,
        )
        // Caractère → Int : AUCUNE conversion (Char.toInt() déprécié
        // depuis Kotlin 1.5 — offrir un correctif déprécié serait une
        // régression déguisée, D3).
        assertEquals(
            listOf(GenreCorrectifKotlin.CHANGEMENT_TYPE),
            session.correctifs(uri, source.lastIndexOf("'c'")).map { it.genre },
        )
        // Décimal → Int : toInt() (tronque, mais compile).
        assertEquals(
            ".toInt()",
            session.correctifs(uri, source.indexOf("2.5")).single { it.genre == GenreCorrectifKotlin.CONVERSION }.texte,
        )
        // Décimal f → String : le représentant de la famille est FLOAT
        // (le littéral porte le suffixe f), la conversion toString().
        val versChaine = session.correctifs(uri, source.indexOf("2.5f"))
        assertEquals("Float", versChaine.single { it.genre == GenreCorrectifKotlin.CHANGEMENT_TYPE }.texte)
        assertEquals(
            ".toString()",
            versChaine.single { it.genre == GenreCorrectifKotlin.CONVERSION }.texte,
        )
        // Chaîne → Boolean : toBoolean() (total — jamais d'exception).
        assertEquals(
            ".toBoolean()",
            session.correctifs(uri, source.indexOf("\"vrai\""))
                .single { it.genre == GenreCorrectifKotlin.CONVERSION }.texte,
        )
        session.close()
    }

    @Test
    fun `type nullable ou conversion legale - aucun correctif (D3)`() {
        val session = session()
        val source = "val a: Long = 1\nval b: String? = \"s\"\n"
        val uri = "file:///type-ok.kt"
        session.ouvrirDocument(uri, source)
        assertTrue(session.correctifs(uri, source.indexOf("1")).isEmpty())
        assertTrue(session.correctifs(uri, source.indexOf("\"s\"")).isEmpty())
        session.close()
    }

    // ── Alignement correctif ⟺ diagnostic ────────────────────────────

    @Test
    fun `alignement - un correctif si et seulement si le diagnostic correspondant`() {
        val session = session()
        val source =
            "fun demo(s: String?) {\n" +
                "  s.length\n" +
                "  val x: Int = \"5\"\n" +
                "  val ok: Long = 1\n" +
                "}\n"
        val uri = "file:///aligne.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        val cibles =
            diagnostics.filter {
                it.code == CodesDiagnosticsKotlin.ACCES_NON_SUR ||
                    it.code == CodesDiagnosticsKotlin.TYPE_INCOMPATIBLE
            }
        assertEquals(2, cibles.size)
        // Chaque diagnostic ciblé porte AU MOINS un correctif à SON
        // offset (l'alignement est par construction : mêmes marches).
        cibles.forEach { diagnostic ->
            assertTrue(
                "aucun correctif pour ${diagnostic.code} à ${diagnostic.debut}",
                session.correctifs(uri, diagnostic.debut).isNotEmpty(),
            )
        }
        // Les positions SANS diagnostic ne rendent RIEN (le littéral
        // légal, l'en-tête du fichier).
        assertTrue(session.correctifs(uri, source.indexOf("1")).isEmpty())
        assertTrue(session.correctifs(uri, 0).isEmpty())
        session.close()
    }

    @Test
    fun `document inconnu ou ferme - liste vide honnete`() {
        val session = session()
        assertTrue(session.correctifs("file:///inconnu.kt", 0).isEmpty())
        val uri = "file:///ferme.kt"
        session.ouvrirDocument(uri, "fun demo(s: String?) {\n  s.length\n}\n")
        session.fermerDocument(uri)
        assertTrue(session.correctifs(uri, "fun demo(s: String?) {\n  ".length).isEmpty())
        session.close()
    }
}
